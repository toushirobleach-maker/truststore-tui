package app.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class TarGzExtractor {
    private TarGzExtractor() {
    }

    public static byte[] extractSingleFile(byte[] tarGzBytes) throws IOException {
        try (
            ByteArrayInputStream bais = new ByteArrayInputStream(tarGzBytes);
            GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(bais);
            TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)
        ) {
            TarArchiveEntry entry;
            int fileCount = 0;
            byte[] extracted = null;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                fileCount++;
                if (fileCount > 1) {
                    throw new IOException("tar.gz must contain exactly one file");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                tarIn.transferTo(out);
                extracted = out.toByteArray();
            }
            if (fileCount != 1 || extracted == null) {
                throw new IOException("tar.gz must contain exactly one file");
            }
            return extracted;
        }
    }
}
