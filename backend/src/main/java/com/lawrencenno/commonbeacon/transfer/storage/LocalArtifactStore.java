package com.lawrencenno.commonbeacon.transfer.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import com.lawrencenno.commonbeacon.transfer.archive.ArchiveCodec;
import static java.nio.file.StandardOpenOption.*;

/** An owner-only directory, deliberately outside the application directory.
 * All cooperating processes serialize file mutations with a filesystem lock. */
public final class LocalArtifactStore implements ArtifactStore {
    private final Path root;
    private final long quota;
    private final long minimumFree;
    public LocalArtifactStore(Path directory,long quota,long minimumFree) throws IOException {
        if(!directory.isAbsolute() || directory.normalize().startsWith(Path.of("").toAbsolutePath().normalize()))
            throw new IOException("ARTIFACT_ROOT_MUST_BE_EXTERNAL");
        if(quota<1 || minimumFree<0)throw new IllegalArgumentException("INVALID_STORAGE_BUDGET");
        this.root=directory.normalize();this.quota=quota;this.minimumFree=minimumFree;
        for(Path part=root;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("UNSAFE_ARTIFACT_ROOT");
        Files.createDirectories(root);
        if(!root.toRealPath().equals(root))throw new IOException("UNSAFE_ARTIFACT_ROOT");
        try(var existing=Files.list(root)) {
            if(existing.anyMatch(file -> !file.getFileName().toString().equals(".store.lock")
                    && !file.getFileName().toString().matches("[0-9a-f-]{36}\\.(part|blob)")))
                throw new IOException("ARTIFACT_ROOT_NOT_EMPTY");
        }
        protect(root,true);
        Path lock=root.resolve(".store.lock");
        if(!Files.exists(lock,LinkOption.NOFOLLOW_LINKS)) {
            try {Files.createFile(lock);}catch(FileAlreadyExistsException ignored) { /* competing startup */ }
        }
        safe(lock);protect(lock,false);
    }
    private static void protect(Path path,boolean directory) throws IOException {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) {posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));return;}
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IOException("PRIVATE_PERMISSIONS_UNSUPPORTED");
        var builder=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if(directory)builder.setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(builder.build()));
    }
    private void safe(Path path) throws IOException {
        if(Files.isSymbolicLink(root) || !root.toRealPath().equals(root) || Files.isSymbolicLink(path)
            || (Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)))throw new IOException("UNSAFE_ARTIFACT_PATH");
    }
    @FunctionalInterface private interface Action<T> {T run() throws IOException;}
    private <T> T locked(Action<T> action) throws IOException {
        // Avoid OverlappingFileLockException for multiple stores in the same JVM.
        synchronized(LocalArtifactStore.class) {
            var lock=root.resolve(".store.lock");safe(lock);
            try(var channel=FileChannel.open(lock,WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.lock()) {return action.run();}
        }
    }
    private Path path(UUID key,String suffix) {return root.resolve(Objects.requireNonNull(key).toString()+suffix);}
    private long used() throws IOException {
        long sum=0;
        try(var files=Files.list(root)) {
            for(var file:files.toList()) {safe(file);sum=Math.addExact(sum,Files.size(file));}
        }
        return sum;
    }
    @Override public Stored write(UUID key,long limit,Writer writer) throws IOException {
        if(limit<1 || limit>268435456L)throw new IllegalArgumentException("INVALID_ARTIFACT_LIMIT");
        return locked(() -> {
            var partial=path(key,".part");var finished=path(key,".blob");safe(partial);safe(finished);
            if(Files.exists(partial) || Files.exists(finished))throw new IOException("ARTIFACT_ALREADY_EXISTS");
            if(used()+limit>quota || Files.getFileStore(root).getUsableSpace()-limit<minimumFree)throw new IOException("ARTIFACT_QUOTA_EXCEEDED");
            Files.createFile(partial);protect(partial,false);
            var digest=ArchiveCodec.sha256();long[] count={0};
            try {
                try(var channel=FileChannel.open(partial,WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    var output=java.nio.channels.Channels.newOutputStream(channel);
                    writer.write(new OutputStream() {
                        @Override public void write(int b) throws IOException {write(new byte[]{(byte)b},0,1);}
                        @Override public void write(byte[] b,int offset,int length) throws IOException {
                            Objects.checkFromIndexSize(offset,length,b.length);
                            if(count[0]+length>limit)throw new IOException("ARTIFACT_LIMIT_EXCEEDED");
                            output.write(b,offset,length);digest.update(b,offset,length);count[0]+=length;
                        }
                        @Override public void flush() throws IOException {output.flush();}
                    });
                    channel.force(true);
                }
                // Same-directory atomic rename only; no non-atomic fallback or replacement.
                Files.move(partial,finished,StandardCopyOption.ATOMIC_MOVE);
                return new Stored(key,count[0],HexFormat.of().formatHex(digest.digest()));
            } catch(IOException | RuntimeException e) {Files.deleteIfExists(partial);throw e;}
        });
    }
    @Override public Optional<Stored> inspect(UUID key) throws IOException {
        return locked(() -> {
            var file=path(key,".blob");safe(file);if(!Files.exists(file))return Optional.empty();
            var digest=ArchiveCodec.sha256();long count=0;
            try(var stream=Files.newInputStream(file,READ,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[65536];int n;
                while((n=stream.read(buffer))!=-1) {count+=n;if(count>268435456L)throw new IOException("ARTIFACT_LIMIT_EXCEEDED");digest.update(buffer,0,n);}
            }
            return Optional.of(new Stored(key,count,HexFormat.of().formatHex(digest.digest())));
        });
    }
    @Override public java.nio.channels.SeekableByteChannel openChannel(UUID key) throws IOException {
        return locked(() -> {var file=path(key,".blob");safe(file);return Files.newByteChannel(file,Set.of(READ,LinkOption.NOFOLLOW_LINKS));});
    }
    @Override public void delete(UUID key) throws IOException {
        locked(() -> {for(String suffix:List.of(".part",".blob")){var file=path(key,suffix);safe(file);Files.deleteIfExists(file);}return null;});
    }
    @Override public InputStream open(UUID key) throws IOException {
        return locked(() -> {var file=path(key,".blob");safe(file);return Files.newInputStream(file,READ,LinkOption.NOFOLLOW_LINKS);});
    }
    @Override public Set<UUID> keysOlderThan(Instant cutoff) throws IOException {
        return locked(() -> {
            var keys=new HashSet<UUID>();
            try(var files=Files.list(root)) {
                for(var file:files.toList()) {
                    safe(file);String name=file.getFileName().toString();
                    if(name.matches("[0-9a-f-]{36}\\.(part|blob)") && Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff))
                        keys.add(UUID.fromString(name.substring(0,36)));
                }
            }
            return keys;
        });
    }
}
