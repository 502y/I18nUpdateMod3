package i18nupdatemod.sync;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal POSIX tar reader with PAX extended-header support. Sufficient for
 * archives produced by Go's {@code archive/tar} writer, which emits a PAX
 * "x" header before any entry whose path exceeds the 100-byte ustar Name
 * field. Globally-applied "g" headers and prefix continuation are supported;
 * other GNU long-link entries ('L' / 'K') are not (Go doesn't emit them by
 * default).
 */
public final class TarStreamReader implements AutoCloseable {
    private static final int BLOCK_SIZE = 512;

    private final InputStream input;
    private final byte[] header = new byte[BLOCK_SIZE];
    private long currentEntryRemaining;
    private long currentEntryPaddingSkipNeeded;
    private String pendingLongName;

    public TarStreamReader(InputStream input) {
        this.input = input;
    }

    /**
     * Advances to the next user-visible entry, transparently consuming PAX
     * extended headers. Returns null when archive ends.
     */
    public Entry nextEntry() throws IOException {
        while (true) {
            skipPreviousEntryRemainder();

            if (!readFully(header)) {
                return null;
            }
            if (isAllZero(header)) {
                return null;
            }

            String ustarName = parseString(header, 0, 100);
            String prefix = parseString(header, 345, 155);
            String fallbackName = prefix.isEmpty() ? ustarName : prefix + "/" + ustarName;

            long size = parseOctal(header, 124, 12);
            char typeFlag = (char) (header[156] & 0xFF);

            currentEntryRemaining = size;
            currentEntryPaddingSkipNeeded = size % BLOCK_SIZE == 0 ? 0 : BLOCK_SIZE - (size % BLOCK_SIZE);

            if (typeFlag == 'x' || typeFlag == 'g') {
                // PAX header: payload is a key=value record list. Extract "path"
                // for the next entry (per-file 'x'), or merge into globals ('g',
                // we currently ignore globals — Go writer doesn't use them).
                byte[] paxPayload = drainCurrentEntry();
                if (typeFlag == 'x') {
                    String pathOverride = extractPaxRecord(paxPayload, "path");
                    if (pathOverride != null) {
                        pendingLongName = pathOverride;
                    }
                }
                continue;
            }

            String name = pendingLongName != null ? pendingLongName : fallbackName;
            pendingLongName = null;

            if (name.isEmpty()) {
                // skip entry with empty name
                continue;
            }
            boolean isRegularFile = (typeFlag == '0' || typeFlag == 0);
            return new Entry(name, size, isRegularFile);
        }
    }

    /**
     * Read at most {@code bufLen} bytes of the current entry's payload.
     * Returns -1 when entry payload is exhausted.
     */
    public int read(byte[] buf, int off, int bufLen) throws IOException {
        if (currentEntryRemaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(bufLen, currentEntryRemaining);
        int read = input.read(buf, off, toRead);
        if (read == -1) {
            throw new EOFException("Unexpected end of tar payload");
        }
        currentEntryRemaining -= read;
        return read;
    }

    private byte[] drainCurrentEntry() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = read(buf, 0, buf.length)) != -1) {
            sink.write(buf, 0, read);
        }
        return sink.toByteArray();
    }

    private void skipPreviousEntryRemainder() throws IOException {
        if (currentEntryRemaining > 0) {
            skipExactly(currentEntryRemaining);
            currentEntryRemaining = 0;
        }
        if (currentEntryPaddingSkipNeeded > 0) {
            skipExactly(currentEntryPaddingSkipNeeded);
            currentEntryPaddingSkipNeeded = 0;
        }
    }

    private void skipExactly(long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                int b = input.read();
                if (b < 0) {
                    throw new EOFException("Unexpected end of tar while skipping");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private boolean readFully(byte[] dst) throws IOException {
        int total = 0;
        while (total < dst.length) {
            int read = input.read(dst, total, dst.length - total);
            if (read < 0) {
                return total == 0;
            }
            total += read;
        }
        return true;
    }

    private static boolean isAllZero(byte[] data) {
        for (byte b : data) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String parseString(byte[] data, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] data, int offset, int length) {
        long value = 0;
        int end = offset + length;
        int idx = offset;
        while (idx < end && (data[idx] == ' ' || data[idx] == 0)) {
            idx++;
        }
        while (idx < end) {
            byte b = data[idx];
            if (b < '0' || b > '7') break;
            value = (value << 3) + (b - '0');
            idx++;
        }
        return value;
    }

    /**
     * PAX records look like: {@code <length> <key>=<value>\n} where
     * {@code length} is the byte length of the entire record including the
     * trailing newline and length itself.
     */
    private static String extractPaxRecord(byte[] payload, String key) {
        int cursor = 0;
        while (cursor < payload.length) {
            int spaceIdx = indexOf(payload, cursor, (byte) ' ');
            if (spaceIdx < 0) return null;
            String lengthStr = new String(payload, cursor, spaceIdx - cursor, StandardCharsets.US_ASCII);
            int length;
            try {
                length = Integer.parseInt(lengthStr);
            } catch (NumberFormatException e) {
                return null;
            }
            if (length <= 0 || cursor + length > payload.length) {
                return null;
            }
            int eqIdx = indexOf(payload, spaceIdx + 1, (byte) '=');
            if (eqIdx < 0 || eqIdx >= cursor + length) {
                cursor += length;
                continue;
            }
            String recordKey = new String(payload, spaceIdx + 1, eqIdx - (spaceIdx + 1), StandardCharsets.UTF_8);
            if (recordKey.equals(key)) {
                int valueLength = (cursor + length - 1) - (eqIdx + 1); // exclude trailing \n
                return new String(payload, eqIdx + 1, valueLength, StandardCharsets.UTF_8);
            }
            cursor += length;
        }
        return null;
    }

    private static int indexOf(byte[] data, int from, byte target) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean regularFile;

        public Entry(String name, long size, boolean regularFile) {
            this.name = name;
            this.size = size;
            this.regularFile = regularFile;
        }
    }
}
