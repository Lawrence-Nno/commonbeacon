package com.lawrencenno.commonbeacon.transfer.inspection;

import com.lawrencenno.commonbeacon.transfer.archive.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Strict native-v1 ZIP reader. No names become paths and no entry is extracted.
 * Reads only bounded headers plus streaming entry buffers from private quarantine. */
public final class QuarantineZip {
    @FunctionalInterface public interface Check {void run() throws IOException;}
    public static final class Rejected extends IOException {
        public final String code;
        Rejected(String code) {super(code);this.code=code;}
    }
    private record Entry(String name,int flags,int method,long crc,long compressed,long size,long offset,long data) {}
    private final SeekableByteChannel file;
    private final Check check;
    private long total;
    public QuarantineZip(SeekableByteChannel file,Check check) {this.file=file;this.check=check;}
    private static void require(boolean ok,String code)throws Rejected {if(!ok)throw new Rejected(code);}
    private ByteBuffer bytes(long offset,int length)throws IOException {
        check.run();require(offset>=0 && length>=0 && offset+length<=file.size(),"INVALID_ZIP");
        var b=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);file.position(offset);
        while(b.hasRemaining())if(file.read(b)<0)throw new Rejected("INVALID_ZIP");
        return b.flip();
    }
    private static int u16(ByteBuffer b,int at){return Short.toUnsignedInt(b.getShort(at));}
    private static long u32(ByteBuffer b,int at){return Integer.toUnsignedLong(b.getInt(at));}
    private String name(long offset,int length)throws IOException {
        require(length>0 && length<=32,"INVALID_ENTRY_NAME");
        String name=StandardCharsets.US_ASCII.decode(bytes(offset,length)).toString();
        boolean known=name.equals("manifest.json") || Arrays.stream(ArchiveFormat.Entity.values()).anyMatch(e->e.file().equals(name));
        require(known,"INVALID_ENTRY_NAME");return name;
    }
    private Map<String,Entry> directory()throws IOException {
        long size=file.size();require(size>=22 && size<=67108864L,"ARCHIVE_SIZE_LIMIT");
        // Native v1 excludes comments, extra fields, split archives and ZIP64.
        var end=bytes(size-22,22);
        require(u32(end,0)==0x06054b50L && u16(end,20)==0 && u16(end,4)==0 && u16(end,6)==0,"INVALID_ZIP");
        int count=u16(end,10);long offset=u32(end,16),length=u32(end,12);
        require(count>=1 && count<=10 && count==u16(end,8),"ENTRY_LIMIT");
        require(offset+length==size-22,"INVALID_ZIP");
        long cursor=offset;var entries=new LinkedHashMap<String,Entry>();
        for(int i=0;i<count;i++) {
            var h=bytes(cursor,46);require(u32(h,0)==0x02014b50L,"INVALID_ZIP");
            int flags=u16(h,8),method=u16(h,10),n=u16(h,28);
            require((flags & ~0x0808)==0 && (method==0 || method==8) && u16(h,6)<=20,"UNSUPPORTED_ZIP_FEATURE");
            require(u16(h,30)==0 && u16(h,32)==0 && u16(h,34)==0,"UNSUPPORTED_ZIP_FEATURE");
            long attributes=u32(h,38);int type=(int)(attributes>>>16)&0170000;
            require((type==0 || type==0100000) && (attributes&0x10)==0,"NON_REGULAR_ENTRY");
            String name=name(cursor+46,n);long raw=u32(h,24),compressed=u32(h,20);
            require(raw<=(name.equals("manifest.json")?65536L:134217728L) && compressed<=67108864L,"ENTRY_SIZE_LIMIT");
            require(raw<=Math.max(1,compressed)*100L,"COMPRESSION_RATIO_LIMIT");
            require(entries.putIfAbsent(name,new Entry(name,flags,method,u32(h,16),compressed,raw,u32(h,42),0))==null,"DUPLICATE_ENTRY");
            cursor+=46+n;require(cursor<=offset+length,"INVALID_ZIP");
        }
        require(cursor==offset+length,"INVALID_ZIP");
        var ordered=new ArrayList<>(entries.values());ordered.sort(Comparator.comparingLong(Entry::offset));cursor=0;
        for(var e:ordered) {
            require(e.offset==cursor,"OVERLAPPING_OR_HIDDEN_ENTRY");
            var h=bytes(cursor,30);require(u32(h,0)==0x04034b50L,"INVALID_ZIP");
            int n=u16(h,26);
            require(u16(h,4)<=20 && u16(h,6)==e.flags && u16(h,8)==e.method && u16(h,28)==0,"HEADER_MISMATCH");
            require(name(cursor+30,n).equals(e.name),"HEADER_MISMATCH");
            long data=cursor+30+n;cursor=data+e.compressed;require(cursor<=offset,"INVALID_ZIP");
            if((e.flags&8)==0) require(u32(h,14)==e.crc && u32(h,18)==e.compressed && u32(h,22)==e.size,"HEADER_MISMATCH");
            else {
                require(u32(h,14)==0 && u32(h,18)==0 && u32(h,22)==0,"HEADER_MISMATCH");
                var d=bytes(cursor,4);if(u32(d,0)==0x08074b50L)cursor+=4;
                d=bytes(cursor,12);require(u32(d,0)==e.crc && u32(d,4)==e.compressed && u32(d,8)==e.size,"HEADER_MISMATCH");cursor+=12;
            }
            entries.put(e.name,new Entry(e.name,e.flags,e.method,e.crc,e.compressed,e.size,e.offset,data));
        }
        require(cursor==offset && entries.containsKey("manifest.json"),"INVALID_ZIP");return entries;
    }
    private InputStream stream(Entry e)throws IOException {
        var raw=new InputStream() {
            long position=e.data,left=e.compressed;
            public int read()throws IOException {byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
            public int read(byte[] b,int off,int len)throws IOException {
                if(len==0)return 0;if(left==0)return -1;check.run();file.position(position);
                int n=file.read(ByteBuffer.wrap(b,off,(int)Math.min(len,left)));require(n>0,"INVALID_ZIP");position+=n;left-=n;return n;
            }
        };
        var inflater=e.method==8?new Inflater(true):null;
        InputStream decoded=inflater==null?raw:new InflaterInputStream(raw,inflater,65536);
        return new FilterInputStream(decoded) {
            long count;final CRC32 crc=new CRC32();boolean finished;
            public int read()throws IOException {byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
            public int read(byte[] b,int off,int len)throws IOException {
                check.run();int n=super.read(b,off,Math.min(len,65536));
                if(n>0) {count+=n;total+=n;require(count<=e.size && total<=268500992L,"DECOMPRESSED_SIZE_LIMIT");require(count<=Math.max(1,e.compressed)*100L,"COMPRESSION_RATIO_LIMIT");crc.update(b,off,n);}
                else if(n<0 && !finished) {
                    finished=true;require(count==e.size && crc.getValue()==e.crc,"ZIP_INTEGRITY_MISMATCH");
                    if(inflater!=null)require(inflater.finished() && inflater.getBytesRead()==e.compressed,"ZIP_INTEGRITY_MISMATCH");
                }
                return n;
            }
            public void close()throws IOException {try{super.close();}finally{if(inflater!=null)inflater.end();}}
        };
    }
    public ArchiveFormat.Result inspect(java.util.function.Consumer<ArchiveFormat.Row> visitor)throws IOException {
        var entries=directory();byte[] manifest;
        try(var in=stream(entries.remove("manifest.json"))){manifest=in.readNBytes(65537);require(manifest.length<=65536,"MANIFEST_LIMIT");}
        var files=new LinkedHashMap<String,ArchiveCodec.Input>();
        for(var e:entries.values())files.put(e.name,()->stream(e));
        return new ArchiveCodec().validateCompanyImport(manifest,files,visitor);
    }
}
