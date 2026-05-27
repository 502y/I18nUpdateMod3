package i18nupdatemod.sync;

/**
 * Pure-Java xxHash64 streaming implementation, matching the server's
 * {@code github.com/cespare/xxhash/v2}. Seed is fixed to 0 (the library
 * default) since the server uses {@code xxhash.New()} without specifying one.
 * <p>
 * Algorithm reference: https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md
 */
public final class XxHash64 {
    private static final long PRIME1 = 0x9E3779B185EBCA87L;
    private static final long PRIME2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME3 = 0x165667B19E3779F9L;
    private static final long PRIME4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME5 = 0x27D4EB2F165667C5L;

    private final long seed;
    private long v1, v2, v3, v4;
    private long totalLength;
    private final byte[] buffer = new byte[32];
    private int bufferSize;

    public XxHash64() {
        this(0L);
    }

    public XxHash64(long seed) {
        this.seed = seed;
        reset();
    }

    public void reset() {
        v1 = seed + PRIME1 + PRIME2;
        v2 = seed + PRIME2;
        v3 = seed;
        v4 = seed - PRIME1;
        totalLength = 0;
        bufferSize = 0;
    }

    public void update(byte b) {
        update(new byte[]{b}, 0, 1);
    }

    public void update(byte[] input) {
        update(input, 0, input.length);
    }

    public void update(byte[] input, int offset, int length) {
        if (length <= 0) return;
        totalLength += length;

        if (bufferSize + length < 32) {
            System.arraycopy(input, offset, buffer, bufferSize, length);
            bufferSize += length;
            return;
        }

        int cursor = offset;
        int end = offset + length;

        if (bufferSize > 0) {
            int fill = 32 - bufferSize;
            System.arraycopy(input, cursor, buffer, bufferSize, fill);
            consumeStripe(buffer, 0);
            cursor += fill;
            bufferSize = 0;
        }

        while (cursor + 32 <= end) {
            consumeStripe(input, cursor);
            cursor += 32;
        }

        if (cursor < end) {
            int remaining = end - cursor;
            System.arraycopy(input, cursor, buffer, 0, remaining);
            bufferSize = remaining;
        }
    }

    private void consumeStripe(byte[] data, int offset) {
        v1 = round(v1, readLongLE(data, offset));
        v2 = round(v2, readLongLE(data, offset + 8));
        v3 = round(v3, readLongLE(data, offset + 16));
        v4 = round(v4, readLongLE(data, offset + 24));
    }

    public long sum() {
        long hash;
        if (totalLength >= 32) {
            hash = Long.rotateLeft(v1, 1)
                    + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12)
                    + Long.rotateLeft(v4, 18);
            hash = mergeRound(hash, v1);
            hash = mergeRound(hash, v2);
            hash = mergeRound(hash, v3);
            hash = mergeRound(hash, v4);
        } else {
            hash = seed + PRIME5;
        }
        hash += totalLength;

        int idx = 0;
        while (idx + 8 <= bufferSize) {
            long k = round(0L, readLongLE(buffer, idx));
            hash = Long.rotateLeft(hash ^ k, 27) * PRIME1 + PRIME4;
            idx += 8;
        }
        if (idx + 4 <= bufferSize) {
            long k = (long) readIntLE(buffer, idx) & 0xFFFFFFFFL;
            hash = Long.rotateLeft(hash ^ (k * PRIME1), 23) * PRIME2 + PRIME3;
            idx += 4;
        }
        while (idx < bufferSize) {
            long k = (long) (buffer[idx] & 0xFF);
            hash = Long.rotateLeft(hash ^ (k * PRIME5), 11) * PRIME1;
            idx++;
        }

        hash ^= hash >>> 33;
        hash *= PRIME2;
        hash ^= hash >>> 29;
        hash *= PRIME3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long round(long acc, long input) {
        acc += input * PRIME2;
        acc = Long.rotateLeft(acc, 31);
        return acc * PRIME1;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0L, val);
        acc ^= val;
        return acc * PRIME1 + PRIME4;
    }

    private static long readLongLE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24)
                | ((long) (data[offset + 4] & 0xFF) << 32)
                | ((long) (data[offset + 5] & 0xFF) << 40)
                | ((long) (data[offset + 6] & 0xFF) << 48)
                | ((long) (data[offset + 7] & 0xFF) << 56);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
