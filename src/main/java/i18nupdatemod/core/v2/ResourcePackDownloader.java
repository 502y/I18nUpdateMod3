package i18nupdatemod.core.v2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import i18nupdatemod.entity.ModTranslation;
import i18nupdatemod.util.DigestUtil;
import i18nupdatemod.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ResourcePackDownloader {
    private static final long UPDATE_TIME_GAP = TimeUnit.DAYS.toMillis(1);
    private static final long ICON_UPDATE_TIME_GAP = TimeUnit.DAYS.toMillis(30);

    public static Manifest loadManifest(String baseUrl, String version) throws IOException {
        String root = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String url = root + encode(version) + "/Manifest.json";
        try (InputStream input = fetch(url)) {
            return parseManifest(input);
        }
    }

    private static Manifest parseManifest(InputStream input) throws IOException {
        final JsonObject json;
        try {
            JsonElement root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                throw new IOException("Manifest root must be an object");
            }
            json = root.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid manifest JSON", e);
        }

        Manifest result = new Manifest();
        if (!json.has("blackList") || !json.has("rules")) {
            throw new IOException("Manifest must contain blackList and rules");
        }
        JsonElement blackList = json.get("blackList");
        if (blackList != null) {
            if (!blackList.isJsonArray()) {
                throw new IOException("Manifest blackList must be an array");
            }
            for (JsonElement item : blackList.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    throw new IOException("Manifest blackList must contain strings");
                }
                result.blackList.add(item.getAsString());
            }
        }

        JsonElement rules = json.get("rules");
        if (rules != null) {
            if (!rules.isJsonObject()) {
                throw new IOException("Manifest rules must be an object");
            }
            for (Map.Entry<String, JsonElement> entry : rules.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new IOException("Manifest rules must map strings to strings");
                }
                result.rules.put(entry.getKey(), value.getAsString());
            }
        }
        return result;
    }

    /**
     * 解析Manifest，根据规则排除模组、生成NS
     */
    public static Map<String, String> selectNamespaces(List<ModTranslation> mods, Manifest manifest) {
        if (mods == null) {
            return new LinkedHashMap<>();
        }
        if (manifest == null) {
            throw new NullPointerException("manifest");
        }

        List<String> blackList = manifest.blackList == null
                ? Collections.emptyList() : manifest.blackList;
        Map<String, String> rules = manifest.rules == null
                ? Collections.emptyMap() : manifest.rules;
        Map<String, String> selected = new LinkedHashMap<>();
        for (ModTranslation mod : mods) {
            if (mod == null) {
                continue;
            }
            String rawNamespace = mod.namespace;
            if (rawNamespace == null || !rawNamespace.matches("[a-z0-9_.-]+")) {
                Log.warning("Invalid translation namespace: %s", rawNamespace);
                continue;
            }
            if (blackList.contains(rawNamespace)) {
                continue;
            }

            String namespace = resolveNamespace(mod, rules.get(rawNamespace));
            if (selected.containsKey(namespace)) {
                // 太多了，没事别看
                Log.debug("Duplicate namespace %s, rawNamespace %s", namespace, rawNamespace);
                continue;
            }
            selected.put(namespace, rawNamespace);
        }
        return selected;
    }

    /**
     * 下载新流程的资源
     *
     * <p>NS不存在、下载失败、MD5不匹配、解压失败时跳过更新，已有缓存仍参与组包。其他错误回滚到旧流程。</p>
     */
    public static List<Path> download(String version, Map<String, String> namespaces,
                                      List<String> blackList, Path cacheRoot,
                                      String baseUrl) throws IOException, NoSuchAlgorithmException {
        if (namespaces == null) {
            throw new NullPointerException("namespaces");
        }
        if (cacheRoot == null) {
            throw new NullPointerException("cacheRoot");
        }

        List<String> blocked = blackList == null
                ? Collections.emptyList() : blackList;
        String root = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        Path modCache = cacheRoot.resolve(version).resolve("mods");
        Files.createDirectories(modCache);
        deleteBlacklisted(modCache, blocked);

        String versionUrl = root + encode(version) + "/";
        List<Path> sourcePaths = new ArrayList<>();
        for (Map.Entry<String, String> entry : namespaces.entrySet()) {
            String namespace = entry.getKey();
            String rawNamespace = entry.getValue();
            if (namespace == null || rawNamespace == null
                    || blocked.contains(namespace) || blocked.contains(rawNamespace)) {
                continue;
            }

            Path cached = modCache.resolve(encode(namespace) + ".zip");
            Path md5File = modCache.resolve(encode(namespace) + ".md5");
            String assetUrl = versionUrl + "assets/" + encode(namespace);
            try {
                updateMod(assetUrl, rawNamespace, cached, md5File);
            } catch (HttpStatusException e) {
                if (e.status == 404 || e.status == 410) {
                    // 太多了，没事别看（
                    //Log.debug("No exact translation asset for %s/%s; keeping local cache if present", version, namespace);
                } else {
                    Log.warning("Translation asset %s/%s returned HTTP %s; aborting new pipeline",
                            version, namespace, e.status);
                    throw e;
                }
            } catch (AssetFailure e) {
                Log.warning("Failed to update translation %s; keeping local cache if present: %s",
                        namespace, e.getMessage());
            }
            if (Files.isRegularFile(cached)) {
                sourcePaths.add(cached);
            }
        }
        return sourcePaths;
    }

    public static Path downloadIcon(String baseUrl, String version, Path cacheRoot) {
        Path cached = cacheRoot.resolve("shared").resolve("pack.png");
        Path temporary = null;
        try {
            if (Files.isRegularFile(cached)
                    && Files.getLastModifiedTime(cached).toMillis() > System.currentTimeMillis() - ICON_UPDATE_TIME_GAP) {
                return cached;
            }
            Files.createDirectories(cached.getParent());
            temporary = Files.createTempFile(cached.getParent(), "pack-icon-", ".tmp");
            String root = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
            try (InputStream input = fetch(root + encode(version) + "/pack.png")) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] signature = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            try (InputStream input = Files.newInputStream(temporary)) {
                for (byte expected : signature) {
                    if (input.read() != (expected & 255)) throw new IOException("Invalid pack.png signature");
                }
            }
            Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Updated shared resource pack icon: %s", cached);
        } catch (Exception e) {
            Log.warning("Failed to update resource pack icon; retaining cached icon if present: %s", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    Log.warning("Failed to remove temporary icon %s: %s", temporary, e);
                }
            }
        }
        return Files.isRegularFile(cached) ? cached : null;
    }

    private static String resolveNamespace(ModTranslation mod, String identifier) {
        if (identifier == null) {
            return mod.namespace;
        }
        try {
            String value;
            if ("author".equals(identifier)) {
                value = mod.authors == null || mod.authors.isEmpty()
                        ? null : Collections.min(mod.authors);
            } else if ("displayName".equals(identifier)) {
                value = mod.displayName;
            } else {
                value = ModIdentity.getFileMd5(mod, identifier);
            }
            if (value != null && !value.trim().isEmpty()) {
                return mod.namespace + "-CFPA-" + value;
            }
        } catch (Exception e) {
            Log.warning("Cannot identify translation %s using %s; using raw namespace: %s",
                    mod.namespace, identifier, e);
        }
        return mod.namespace;
    }

    private static void updateMod(String assetUrl, String rawNamespace,
                                  Path cached, Path md5File)
            throws IOException, AssetFailure, NoSuchAlgorithmException {
        if (Files.isRegularFile(cached) && Files.isRegularFile(md5File)
                && Files.getLastModifiedTime(cached).toMillis()
                > System.currentTimeMillis() - UPDATE_TIME_GAP) {
            return;
        }

        String remoteMd5 = readRemoteText(assetUrl + ".md5").trim();
        if (!remoteMd5.matches("[0-9a-fA-F]{32}")) {
            throw new AssetFailure("Invalid asset MD5: " + assetUrl);
        }
        if (Files.isRegularFile(cached) && Files.isRegularFile(md5File)
                && remoteMd5.equalsIgnoreCase(
                new String(Files.readAllBytes(md5File), StandardCharsets.UTF_8).trim())) {
            return;
        }

        Path archive = Files.createTempFile(cached.getParent(), "translation-", ".tar.lzma");
        Path decoded = Files.createTempFile(cached.getParent(), "translation-", ".zip.tmp");
        try {
            downloadRemote(assetUrl + ".tar.lzma", archive);
            if (!remoteMd5.equalsIgnoreCase(DigestUtil.md5Hex(archive))) {
                throw new AssetFailure("Download MD5 not match: " + assetUrl);
            }
            try {
                TranslationArchive.unpack(archive, decoded, rawNamespace);
            } catch (TranslationArchive.LocalIoException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new AssetFailure("Failed to decode translation archive: " + assetUrl, e);
            }

            Files.move(decoded, cached, StandardCopyOption.REPLACE_EXISTING);
            Files.write(md5File, remoteMd5.getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(decoded);
        }
    }

    private static String readRemoteText(String url) throws IOException, AssetFailure {
        InputStream input = fetchAsset(url);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                int count;
                try {
                    count = input.read(buffer);
                } catch (IOException e) {
                    throw new AssetFailure("Failed to read " + url, e);
                }
                if (count < 0) {
                    break;
                }
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                input.close();
            } catch (IOException e) {
                throw new AssetFailure("Failed to close " + url, e);
            }
        }
    }

    private static void downloadRemote(String url, Path destination)
            throws IOException, AssetFailure {
        InputStream input = fetchAsset(url);
        OutputStream output = null;
        IOException localFailure = null;
        AssetFailure assetFailure = null;
        try {
            try {
                output = Files.newOutputStream(destination);
            } catch (IOException e) {
                localFailure = e;
            }
            if (output != null) {
                try {
                    byte[] buffer = new byte[32 * 1024];
                    while (true) {
                        int count;
                        try {
                            count = input.read(buffer);
                        } catch (IOException e) {
                            throw new AssetFailure("Failed to read " + url, e);
                        }
                        if (count < 0) {
                            break;
                        }
                        if (count > 0) {
                            output.write(buffer, 0, count);
                        }
                    }
                } catch (AssetFailure e) {
                    assetFailure = e;
                } catch (IOException e) {
                    localFailure = e;
                }
                try {
                    output.close();
                } catch (IOException e) {
                    if (localFailure == null) {
                        localFailure = e;
                    } else {
                        localFailure.addSuppressed(e);
                    }
                }
            }
        } finally {
            try {
                input.close();
            } catch (IOException e) {
                AssetFailure closeFailure = new AssetFailure("Failed to close " + url, e);
                if (localFailure != null) {
                    localFailure.addSuppressed(closeFailure);
                } else if (assetFailure != null) {
                    assetFailure.addSuppressed(closeFailure);
                } else {
                    assetFailure = closeFailure;
                }
            }
        }
        if (localFailure != null) {
            if (assetFailure != null) {
                localFailure.addSuppressed(assetFailure);
            }
            throw localFailure;
        }
        if (assetFailure != null) {
            throw assetFailure;
        }
    }

    private static InputStream fetchAsset(String url) throws IOException, AssetFailure {
        try {
            return fetch(url);
        } catch (HttpStatusException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new AssetFailure("Failed to fetch " + url, e);
        }
    }

    private static void deleteBlacklisted(Path cache, List<String> blackList) throws IOException {
        if (blackList.isEmpty()) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cache)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".zip") && !name.endsWith(".md5")) {
                    continue;
                }
                String encoded = name.substring(0, name.lastIndexOf('.'));
                final String namespace;
                try {
                    namespace = URLDecoder.decode(encoded, "UTF-8");
                } catch (IllegalArgumentException e) {
                    continue;
                }
                int marker = namespace.indexOf("-CFPA-");
                String rawNamespace = marker < 0 ? namespace : namespace.substring(0, marker);
                if (blackList.contains(namespace) || blackList.contains(rawNamespace)) {
                    Files.deleteIfExists(file);
                    Log.info("Deleted blacklisted translation cache: %s", file);
                }
            }
        }
    }

    private static String encode(String segment) throws IOException {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
    }

    private static InputStream fetch(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(33000);
        try {
            int status = connection.getResponseCode();
            if (status >= 400 && status <= 599) {
                throw new HttpStatusException(url, status);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Unexpected HTTP " + status + ": " + url);
            }
            return new FilterInputStream(connection.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        connection.disconnect();
                    }
                }
            };
        } catch (IOException | RuntimeException e) {
            connection.disconnect();
            throw e;
        }
    }

    private static class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(String url, int status) {
            super("HTTP " + status + ": " + url);
            this.status = status;
        }
    }

    public static class Manifest {
        public List<String> blackList = new ArrayList<>();
        public Map<String, String> rules = new LinkedHashMap<>();
    }

    private static class AssetFailure extends Exception {
        AssetFailure(String message) {
            super(message);
        }

        AssetFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

