package com.lawrencenno.commonbeacon.transfer.export;

import com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec;
import com.lawrencenno.commonbeacon.transfer.job.TransferJob;
import com.lawrencenno.commonbeacon.transfer.storage.ArtifactStore;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Packages a completed private snapshot only. No database transaction is held here. */
public final class CompanyArchive {
    @FunctionalInterface public interface Check {void run() throws IOException;}
    private final ArtifactStore store;
    public CompanyArchive(ArtifactStore store) {this.store=store;}
    private InputStream entry(UUID key,CompanySnapshot.Entry entry,Check check) throws IOException {
        var input=store.open(key);
        try {input.skipNBytes(entry.offset());}
        catch(IOException e){input.close();throw e;}
        return new FilterInputStream(input) {
            long left=entry.bytes();
            @Override public int read() throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
            @Override public int read(byte[] b,int off,int length) throws IOException {
                if(length==0)return 0;if(left==0)return -1;check.run();
                int n=in.read(b,off,(int)Math.min(length,left));if(n<0)throw new EOFException("INCOMPLETE_SNAPSHOT");left-=n;return n;
            }
        };
    }
    public void validate(UUID key,CompanySnapshot.Dataset dataset,Check check) throws IOException {
        var files=new LinkedHashMap<String,ArchiveCodec.Input>();
        for(var entry:dataset.entries())files.put(entry.entity().file(),()->entry(key,entry,check));
        var result=new ArchiveCodec().validate(dataset.manifest(),files,row->{
            try {check.run();}catch(IOException e){throw new UncheckedIOException(e);}
        });
        if(!result.valid())throw new CompanySnapshot.ExportFailure(TransferJob.Failure.INVALID_SOURCE_DATA);
    }
    public ArtifactStore.Stored packageSnapshot(UUID source,UUID target,CompanySnapshot.Dataset dataset,Check check) throws IOException {
        // Measure raw DEFLATE before choosing a ZIP method. No extra compressed spill files.
        var stored=new ArrayList<Boolean>();byte[] buffer=new byte[65536];
        for(var entry:dataset.entries()) {
            check.run();var deflater=new Deflater(Deflater.DEFAULT_COMPRESSION,true);
            try(var input=entry(source,entry,check);var output=new DeflaterOutputStream(OutputStream.nullOutputStream(),deflater,65536)) {
                int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);output.finish();
                stored.add(entry.bytes()>100L*Math.max(1,deflater.getBytesWritten()));
            }finally{deflater.end();}
        }
        // Open before write: the private store serializes mutations with a filesystem lock.
        try(var input=new BufferedInputStream(store.open(source),65536)) {
            return store.write(target,67108864L,output->{
                try(var zip=new ZipOutputStream(output)) {
                    var manifest=new ZipEntry("manifest.json");var crc=new CRC32();crc.update(dataset.manifest());
                    manifest.setMethod(ZipEntry.STORED);manifest.setSize(dataset.manifest().length);manifest.setCrc(crc.getValue());
                    zip.putNextEntry(manifest);zip.write(dataset.manifest());zip.closeEntry();
                    for(int i=0;i<dataset.entries().size();i++) {
                        var entry=dataset.entries().get(i);var item=new ZipEntry(entry.entity().file());
                        if(stored.get(i)){item.setMethod(ZipEntry.STORED);item.setSize(entry.bytes());item.setCrc(entry.crc());}
                        zip.putNextEntry(item);long remaining=entry.bytes();var digest=ArchiveCodec.sha256();
                        while(remaining>0) {
                            check.run();int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                            if(n<0)throw new EOFException("INCOMPLETE_SNAPSHOT");zip.write(buffer,0,n);digest.update(buffer,0,n);remaining-=n;
                        }
                        if(!HexFormat.of().formatHex(digest.digest()).equals(entry.hash()))throw new IOException("SNAPSHOT_CHANGED");
                        zip.closeEntry();
                    }
                    if(input.read()!=-1)throw new IOException("SNAPSHOT_CHANGED");check.run();
                }
            });
        }
    }
}
