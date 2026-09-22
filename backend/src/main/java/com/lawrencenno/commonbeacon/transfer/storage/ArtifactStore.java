package com.lawrencenno.commonbeacon.transfer.storage;

import java.io.*;
import java.time.Instant;
import java.util.*;

public interface ArtifactStore {
    @FunctionalInterface interface Writer { void write(OutputStream output) throws IOException; }
    record Stored(UUID key,long bytes,String sha256) {}
    Stored write(UUID key,long limit,Writer writer) throws IOException;
    Optional<Stored> inspect(UUID key) throws IOException;
    default InputStream open(UUID key) throws IOException {throw new IOException("ARTIFACT_READ_UNAVAILABLE");}
    default java.nio.channels.SeekableByteChannel openChannel(UUID key) throws IOException {throw new IOException("ARTIFACT_SEEK_UNAVAILABLE");}
    void delete(UUID key) throws IOException;
    Set<UUID> keysOlderThan(Instant cutoff) throws IOException;
}
