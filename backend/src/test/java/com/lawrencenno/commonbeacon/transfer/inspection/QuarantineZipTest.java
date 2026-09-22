package com.lawrencenno.commonbeacon.transfer.inspection;

import static org.assertj.core.api.Assertions.*;
import com.lawrencenno.commonbeacon.transfer.archive.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class QuarantineZipTest {
    @TempDir Path root;
    public static Map<String,byte[]> fixture(String profile)throws IOException {
        var files=new LinkedHashMap<String,byte[]>();
        try(var paths=Files.list(Path.of("src/test/resources/data-transfer/v1/"+profile))) {
            for(var p:paths.sorted().toList())files.put(p.getFileName().toString(),Files.readAllBytes(p));
        }
        return files;
    }
    public static byte[] zip(Map<String,byte[]> files,boolean stored)throws IOException {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)) {
            for(var file:files.entrySet()) {
                var e=new ZipEntry(file.getKey());
                if(stored){var crc=new CRC32();crc.update(file.getValue());e.setMethod(ZipEntry.STORED);e.setSize(file.getValue().length);e.setCrc(crc.getValue());}
                zip.putNextEntry(e);zip.write(file.getValue());zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    ArchiveFormat.Result inspect(byte[] bytes)throws IOException {
        Path file=root.resolve("quarantine.blob");Files.write(file,bytes);
        try(var channel=Files.newByteChannel(file)) {return new QuarantineZip(channel,()->{}).inspect(row->{});}
    }
    static int central(byte[] bytes) {for(int i=0;i<bytes.length-4;i++)if(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(i)==0x02014b50)return i;throw new AssertionError();}
    @ParameterizedTest @ValueSource(booleans={true,false}) void acceptsNativeCompanyFixtures(boolean stored)throws Exception {
        assertThat(inspect(zip(fixture("company-default"),stored)).valid()).isTrue();
        assertThat(inspect(zip(fixture("company-full"),stored)).valid()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"../users.jsonl","/users.jsonl","C:/users.jsonl","users.JSONL","./users.jsonl","users.jsonl/","nested.zip","https://example.test/x","a.sql"})
    void rejectsUnknownOrUnsafeMembersWithoutExtraction(String name)throws Exception {
        var files=fixture("company-default");files.put(name,new byte[]{1});
        assertThatThrownBy(()->inspect(zip(files,false))).isInstanceOf(QuarantineZip.Rejected.class);
        try(var paths=Files.list(root)){assertThat(paths.count()).isEqualTo(1);}
    }
    @ParameterizedTest @ValueSource(strings={"encrypted","symlink","method","extra","duplicate","offset","crc","truncated","trailing"})
    void rejectsHostileStructures(String kind)throws Exception {
        byte[] archive=zip(fixture("company-default"),true);var b=ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);int c=central(archive);
        switch(kind) {
            case "encrypted" -> b.putShort(c+8,(short)1);
            case "symlink" -> b.putInt(c+38,0120777<<16);
            case "method" -> b.putShort(c+10,(short)99);
            case "extra" -> b.putShort(c+30,(short)1);
            case "offset" -> b.putInt(c+42,1);
            case "crc" -> {b.putInt(c+16,0);b.putInt(14,0);}
            case "duplicate" -> {int n=Short.toUnsignedInt(b.getShort(c+28));int next=c+46+n;b.putInt(next+42,0);}
            case "truncated" -> archive=Arrays.copyOf(archive,archive.length-3);
            case "trailing" -> archive=Arrays.copyOf(archive,archive.length+3);
        }
        byte[] hostile=archive;assertThatThrownBy(()->inspect(hostile)).isInstanceOf(IOException.class);
    }
    @Test void checksActualInflatedSizeRatherThanTrustingDeclaredSize()throws Exception {
        var files=fixture("company-default");files.put("acceptances.jsonl",new byte[1024*1024]);
        var archive=zip(files,false);var b=ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);int c=central(archive);
        // First entry is acceptances: patch central size and its descriptor consistently.
        b.putInt(c+24,1);int data=30+Short.toUnsignedInt(b.getShort(26));int compressed=b.getInt(c+20);
        b.putInt(data+compressed+12,1);
        assertThatThrownBy(()->inspect(archive)).hasMessage("DECOMPRESSED_SIZE_LIMIT");
    }
    @Test void rejectsPersonalProfileAndMalformedRecordsWithoutEchoingValues()throws Exception {
        assertThat(inspect(zip(fixture("personal"),false)).issues()).anyMatch(i->i.code().equals("COMPANY_PROFILE_REQUIRED"));
        var files=fixture("company-default");files.put("users.jsonl",new byte[]{(byte)0xff,10});
        var result=inspect(zip(files,false));assertThat(result.valid()).isFalse();assertThat(result.issues()).anyMatch(i->i.code().equals("INVALID_ROW"));
        files.put("users.jsonl",("private-value".repeat(30000)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result=inspect(zip(files,true));assertThat(result.issues()).anyMatch(i->i.code().equals("LINE_LIMIT"));
        assertThat(result.issues().toString()).doesNotContain("private-value");
    }
    @Test void rejectsDuplicateAllowlistedNamesInTheCentralDirectory()throws Exception {
        byte[] archive=zip(fixture("company-full"),true);var b=ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int pos=central(archive);
        while(b.getInt(pos)==0x02014b50) {
            int length=Short.toUnsignedInt(b.getShort(pos+28));
            if(new String(archive,pos+46,length,java.nio.charset.StandardCharsets.US_ASCII).equals("reports.jsonl")) {
                System.arraycopy("replies.jsonl".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,archive,pos+46,length);break;
            }
            pos+=46+length;
        }
        assertThatThrownBy(()->inspect(archive)).hasMessage("DUPLICATE_ENTRY");
    }
    @Test void rejectsExcessiveCompressionRatio()throws Exception {
        var files=fixture("company-default");files.put("users.jsonl",new byte[1024*1024]);
        assertThatThrownBy(()->inspect(zip(files,false))).hasMessage("COMPRESSION_RATIO_LIMIT");
    }
    @Test void progressCanCancelBeforeReadingAnyEntry()throws Exception {
        var p=root.resolve("quarantine.blob");Files.write(p,zip(fixture("company-default"),false));
        try(var channel=Files.newByteChannel(p)) {
            assertThatThrownBy(()->new QuarantineZip(channel,()->{throw new IOException("CANCELLED");}).inspect(row->{})).hasMessage("CANCELLED");
        }
    }
}
