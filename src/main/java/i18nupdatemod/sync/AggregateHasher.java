package i18nupdatemod.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Mirrors the server's {@code internal/hash/xxhash.go#FileAggregate}.
 * <p>
 * For sorted relative paths under {@code namespaceRoot}:
 * <pre>
 *   hasher.update(pathBytes)
 *   hasher.update(0x00)
 *   hasher.update(fileBytes)
 * </pre>
 * Result is the lowercase hex of the big-endian sum64.
 */
public final class AggregateHasher {
    private static final byte SEPARATOR = (byte) 0x00;
    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    private AggregateHasher() {
    }

    /**
     * @param namespaceRoot directory rooted at {@code <packBase>/assets/<namespace>/}
     * @return hex hash, lowercase, no padding stripping (always 16 chars)
     */
    public static String hashNamespace(Path namespaceRoot) throws IOException {
        List<String> relPaths = collectRelativePaths(namespaceRoot);
        return hashFiles(namespaceRoot, relPaths);
    }

    static String hashFiles(Path root, List<String> relPaths) throws IOException {
        List<String> sortedPaths = new ArrayList<>(relPaths);
        Collections.sort(sortedPaths);

        XxHash64 hasher = new XxHash64();
        byte[] copyBuffer = new byte[COPY_BUFFER_SIZE];

        for (String relPath : sortedPaths) {
            hasher.update(relPath.getBytes(StandardCharsets.UTF_8));
            hasher.update(SEPARATOR);

            Path filePath = root.resolve(relPath);
            try (InputStream in = Files.newInputStream(filePath)) {
                int read;
                while ((read = in.read(copyBuffer)) != -1) {
                    hasher.update(copyBuffer, 0, read);
                }
            }
        }
        return formatLowerHex(hasher.sum());
    }

    private static List<String> collectRelativePaths(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String rel = root.relativize(file).toString().replace('\\', '/');
                out.add(rel);
            });
        }
        return out;
    }

    private static String formatLowerHex(long value) {
        char[] out = new char[16];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 15; i >= 0; i--) {
            out[i] = digits[(int) (value & 0xF)];
            value >>>= 4;
        }
        return new String(out);
    }
}
