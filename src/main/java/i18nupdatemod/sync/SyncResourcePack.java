package i18nupdatemod.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import i18nupdatemod.entity.GameMetaData;
import i18nupdatemod.util.Log;
import io.airlift.compress.zstd.ZstdInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Drives the {@code /sync}-based update flow given a pre-constructed list of
 * CFPA-suffixed namespaces (built upstream from rules + mod source):
 * <ol>
 *   <li>Hash each namespace's local cache dir from scratch (no persisted
 *       hashes — files on disk can be mutated between runs).</li>
 *   <li>POST /sync; receive zstd(tar) + manifest.</li>
 *   <li>Apply: delete removed namespaces, extract added/updated entries.</li>
 *   <li>Repackage the local cache directory into a Minecraft resource pack zip.</li>
 * </ol>
 */
public final class SyncResourcePack {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String NO_LOCAL_HASH = "0";

    private final SyncClient syncClient;
    private final Path cacheDir;
    private final Path resourcePacksDir;
    private final String mcVersion;
    private final String outputFilename;

    public SyncResourcePack(SyncClient syncClient,
                            Path cacheDir,
                            Path resourcePacksDir,
                            String mcVersion,
                            String outputFilename) {
        this.syncClient = syncClient;
        this.cacheDir = cacheDir;
        this.resourcePacksDir = resourcePacksDir;
        this.mcVersion = mcVersion;
        this.outputFilename = outputFilename;
    }

    /**
     * Returns the produced resource-pack zip path. Always rebuilds the zip from
     * the current cache directory because the sync cache is shared across MC
     * instances (per-user) while the zip lives under each instance's own
     * {@code resourcepacks/} — another instance may have refreshed the cache
     * since this instance's zip was last written, even when /sync reports 204
     * for this caller.
     * <p>
     * Returns null only when the cache is genuinely empty (no namespace dirs
     * extracted yet); caller should fall back in that case.
     */
    public Path apply(List<String> namespaces, GameMetaData gameMeta, String description)
            throws IOException, URISyntaxException {
        Files.createDirectories(cacheDir);

        Log.debug("Sync: client asking server about %d namespaces", namespaces.size());

        Map<String, String> uploadHashes = computeLocalHashes(namespaces);

        SyncClient.SyncResult result = syncClient.requestSync(mcVersion, uploadHashes);
        if (result.noChange) {
            Log.info("Sync: server reports no change (204)");
        } else {
            applyManifest(result);
        }

        Path assetsDir = cacheDir.resolve("assets");
        if (!Files.isDirectory(assetsDir)) {
            return null;
        }
        Path outputZip = resourcePacksDir.resolve(outputFilename);
        repackage(outputZip, namespaces, gameMeta, description);
        return outputZip;
    }

    private Map<String, String> computeLocalHashes(List<String> namespaces) throws IOException {
        Map<String, String> result = new HashMap<>(namespaces.size());
        for (String namespace : namespaces) {
            Path namespaceDir = cacheDir.resolve("assets").resolve(namespace);
            if (Files.isDirectory(namespaceDir)) {
                result.put(namespace, AggregateHasher.hashNamespace(namespaceDir));
            } else {
                result.put(namespace, NO_LOCAL_HASH);
            }
        }
        return result;
    }

    private void applyManifest(SyncClient.SyncResult result) throws IOException {
        SyncManifest manifest = result.manifest;
        Log.info("Sync: manifest added=%d updated=%d deleted=%d",
                manifest.added().size(), manifest.updated().size(), manifest.deleted().size());

        // 1. Drop deleted namespaces first to keep disk usage low.
        for (String namespace : manifest.deleted()) {
            Path namespaceDir = cacheDir.resolve("assets").resolve(namespace);
            if (Files.exists(namespaceDir)) {
                FileUtils.deleteDirectory(namespaceDir.toFile());
            }
        }

        // 2. Extract zstd(tar) payload. Entries are pre-namespaced (assets/<ns>/...).
        try (ZstdInputStream zstdIn = new ZstdInputStream(new ByteArrayInputStream(result.zstdBody));
             TarStreamReader tar = new TarStreamReader(zstdIn)) {
            byte[] buffer = new byte[16 * 1024];
            TarStreamReader.Entry entry;
            while ((entry = tar.nextEntry()) != null) {
                if (!entry.regularFile) continue;
                Path target = cacheDir.resolve(entry.name);
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = tar.read(buffer, 0, buffer.length)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private void repackage(Path outputZip, List<String> namespaces, GameMetaData gameMeta, String description)
            throws IOException {
        Files.createDirectories(outputZip.getParent());
        Path tmpZip = outputZip.resolveSibling(outputZip.getFileName() + ".tmp");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmpZip), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("pack.mcmeta"));
            zos.write(buildPackMeta(gameMeta, description));
            zos.closeEntry();

            writeAssetsToZip(zos, namespaces);
        }
        Files.move(tmpZip, outputZip, StandardCopyOption.REPLACE_EXISTING);
        Log.info("Sync: wrote resource pack %s", outputZip);
    }

    /**
     * Emit one zip entry per file under {@code cache/assets/<ns>/} for each ns
     * in {@code namespaces}. Cache may carry residue from previously-installed
     * mods; restricting to the current namespace list keeps the pack tight.
     * <p>
     * Each ns dir's filename is stripped of its CFPA suffix on output — the
     * server-side disambiguator doesn't exist in the game's namespace lookup.
     * When multiple CFPA-suffixed dirs share a base namespace (rare),
     * first-write-wins to avoid ZipOutputStream duplicate-entry errors.
     */
    private void writeAssetsToZip(ZipOutputStream zos, List<String> namespaces) throws IOException {
        Set<String> writtenEntries = new HashSet<>();
        List<String> sortedNamespaces = new ArrayList<>(namespaces);
        java.util.Collections.sort(sortedNamespaces);

        for (String namespace : sortedNamespaces) {
            Path namespaceDir = cacheDir.resolve("assets").resolve(namespace);
            if (!Files.isDirectory(namespaceDir)) continue;

            String entryPrefix = "assets/" + stripCfpaSuffix(namespace) + "/";

            List<Path> files = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.walk(namespaceDir)) {
                stream.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
            for (Path file : files) {
                String rel = namespaceDir.relativize(file).toString().replace('\\', '/');
                String entryName = entryPrefix + rel;
                if (!writtenEntries.add(entryName)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entryName));
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    IOUtils.copy(in, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static String stripCfpaSuffix(String namespace) {
        int idx = namespace.lastIndexOf("-CFPA-");
        return idx > 0 ? namespace.substring(0, idx) : namespace;
    }

    private byte[] buildPackMeta(GameMetaData gameMeta, String description) {
        PackMeta meta = new PackMeta();
        meta.pack = new PackMeta.Pack();
        meta.pack.pack_format = gameMeta.useNewFormat() ? null : gameMeta.packFormat;
        meta.pack.min_format = gameMeta.useNewFormat() ? gameMeta.minFormat : null;
        meta.pack.max_format = gameMeta.useNewFormat() ? gameMeta.maxFormat : null;
        meta.pack.description = description;
        return GSON.toJson(meta).getBytes(StandardCharsets.UTF_8);
    }

    private static final class PackMeta {
        Pack pack;

        private static final class Pack {
            Integer pack_format;
            Integer min_format;
            Integer max_format;
            String description;
        }
    }
}
