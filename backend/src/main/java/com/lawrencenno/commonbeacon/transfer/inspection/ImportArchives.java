package com.lawrencenno.commonbeacon.transfer.inspection;

import com.lawrencenno.commonbeacon.transfer.archive.ArchiveFormat;
import com.lawrencenno.commonbeacon.transfer.job.TransferJob;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.function.Consumer;

/** Provider dispatch never publishes domain records. Both readers feed native validation. */
public final class ImportArchives {
    private ImportArchives() {}
    public static ArchiveFormat.Result inspect(TransferJob.Provider provider, SeekableByteChannel channel,
            QuarantineZip.Check check, Consumer<ArchiveFormat.Row> visitor) throws IOException {
        return provider == TransferJob.Provider.DISCOURSE
            ? new DiscourseArchive(channel, check).inspect(visitor)
            : new QuarantineZip(channel, check).inspect(visitor);
    }
}
