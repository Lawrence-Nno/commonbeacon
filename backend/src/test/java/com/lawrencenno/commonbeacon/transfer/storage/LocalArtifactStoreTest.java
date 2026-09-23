package com.lawrencenno.commonbeacon.transfer.storage;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactStoreTest {
    @TempDir Path root;
    @Test void privatePermissionsAndImmutablePublicationSurviveReopening() throws IOException {
        var store=new LocalArtifactStore(root,10000,0);UUID key=UUID.randomUUID();
        var result=store.write(key,100,o->o.write("payload".getBytes()));
        assertThat(new LocalArtifactStore(root,10000,0).inspect(key)).contains(result);
        assertThatThrownBy(()->store.write(key,100,o->o.write(2))).hasMessage("ARTIFACT_ALREADY_EXISTS");
        var file=root.resolve(key+".blob");
        var posix=Files.getFileAttributeView(file,PosixFileAttributeView.class);
        if(posix!=null)assertThat(posix.readAttributes().permissions()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        else {
            var acl=Files.getFileAttributeView(file,AclFileAttributeView.class);
            var owner=acl.getOwner();
            assertThat(acl.getAcl()).allMatch(e->e.principal().equals(owner) && e.type()==AclEntryType.ALLOW);
        }
    }
    @Test void partialFailureAndByteLimitLeaveNoPublishedOrTemporaryFile() throws IOException {
        var store=new LocalArtifactStore(root,10000,0);UUID key=UUID.randomUUID();
        assertThatThrownBy(()->store.write(key,10,o->{o.write(1);throw new IOException("interrupted");})).isInstanceOf(IOException.class);
        assertThat(root.resolve(key+".part")).doesNotExist();assertThat(store.inspect(key)).isEmpty();
        assertThatThrownBy(()->store.write(key,2,o->o.write(new byte[3]))).hasMessage("ARTIFACT_LIMIT_EXCEEDED");
        assertThat(root.resolve(key+".part")).doesNotExist();
    }
    @Test void quotaIncludesExistingFilesAndDeletionIsIdempotent() throws IOException {
        var store=new LocalArtifactStore(root,10,0);UUID key=UUID.randomUUID();store.write(key,8,o->o.write(new byte[8]));
        var usage=store.usage().orElseThrow();assertThat(usage.usedBytes()).isEqualTo(8);
        assertThat(usage.quotaBytes()).isEqualTo(10);assertThat(usage.usableBytes()).isPositive();assertThat(usage.minimumFreeBytes()).isZero();
        assertThatThrownBy(()->store.write(UUID.randomUUID(),3,o->o.write(1))).hasMessage("ARTIFACT_QUOTA_EXCEEDED");
        store.delete(key);store.delete(key);assertThat(store.inspect(key)).isEmpty();
    }
    @Test void concurrentStoresCannotReplaceTheSameKey() throws Exception {
        var a=new LocalArtifactStore(root,10000,0);var b=new LocalArtifactStore(root,10000,0);UUID key=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first=()->{try{a.write(key,10,o->o.write(1));return true;}catch(IOException e){return false;}};
            Callable<Boolean> second=()->{try{b.write(key,10,o->o.write(2));return true;}catch(IOException e){return false;}};
            var futures=pool.invokeAll(List.of(first,second));assertThat(futures.get(0).get() ^ futures.get(1).get()).isTrue();
        }
        assertThat(a.inspect(key)).isPresent();
    }
    @Test void refusesApplicationDirectoriesAndNonRegularArtifactPaths() throws IOException {
        assertThatThrownBy(()->new LocalArtifactStore(Path.of("artifacts"),10000,0)).hasMessage("ARTIFACT_ROOT_MUST_BE_EXTERNAL");
        assertThatThrownBy(()->new LocalArtifactStore(Path.of("").toAbsolutePath().resolve("public"),10000,0)).hasMessage("ARTIFACT_ROOT_MUST_BE_EXTERNAL");
        var store=new LocalArtifactStore(root,10000,0);UUID key=UUID.randomUUID();Files.createDirectory(root.resolve(key+".blob"));
        assertThatThrownBy(()->store.inspect(key)).hasMessage("UNSAFE_ARTIFACT_PATH");
        assertThatThrownBy(()->store.delete(key)).hasMessage("UNSAFE_ARTIFACT_PATH");
        assertThat(root.resolve(key+".blob")).isDirectory();
    }
    @Test void refusesTakingOverAnUnrelatedDirectory() throws IOException {
        Files.writeString(root.resolve("important.txt"),"keep");
        assertThatThrownBy(()->new LocalArtifactStore(root,10000,0)).hasMessage("ARTIFACT_ROOT_NOT_EMPTY");
        assertThat(Files.readString(root.resolve("important.txt"))).isEqualTo("keep");
    }
}
