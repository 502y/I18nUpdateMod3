package i18nupdatemod.sync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import i18nupdatemod.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin HTTP client for the i18n sync server. Two endpoints:
 * <ul>
 *   <li>{@code GET  /rules/:mcVersion}   → {@code List<Rule>} JSON</li>
 *   <li>{@code POST /sync}               → zstd(tar) body with X-Manifest header</li>
 * </ul>
 * Throws on non-2xx or transport errors so the caller can fall back to the
 * legacy full-download flow.
 */
public final class SyncClient {
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(5);
    private static final int READ_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(60);

    private final String baseURL;

    public SyncClient(String baseURL) {
        this.baseURL = stripTrailingSlash(baseURL);
    }

    public List<Rule> fetchRules(String mcVersion) throws IOException, URISyntaxException {
        URL url = new URI(baseURL + "/rules/" + mcVersion).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code == 304) {
            return Collections.emptyList();
        }
        if (code / 100 != 2) {
            throw new IOException("/rules returned HTTP " + code);
        }
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<Rule>>() {
            }.getType();
            List<Rule> rules = GSON.fromJson(reader, listType);
            return rules == null ? Collections.emptyList() : rules;
        } finally {
            conn.disconnect();
        }
    }

    public SyncResult requestSync(String mcVersion, Map<String, String> namespaceHashes)
            throws IOException, URISyntaxException {
        URL url = new URI(baseURL + "/sync").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/zstd");
        conn.setDoOutput(true);

        byte[] payload = buildRequestBody(mcVersion, namespaceHashes);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload);
        }

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_NO_CONTENT) {
            return SyncResult.noChange();
        }
        if (code / 100 != 2) {
            throw new IOException("/sync returned HTTP " + code);
        }

        String manifestB64 = conn.getHeaderField("X-Manifest");
        if (manifestB64 == null || manifestB64.isEmpty()) {
            throw new IOException("missing X-Manifest header");
        }
        byte[] manifestJSON = Base64.getDecoder().decode(manifestB64);
        SyncManifest manifest = GSON.fromJson(
                new String(manifestJSON, StandardCharsets.UTF_8), SyncManifest.class);

        byte[] zstdBody = drain(conn.getInputStream());
        conn.disconnect();
        return SyncResult.withPayload(manifest, zstdBody);
    }

    private static byte[] buildRequestBody(String mcVersion, Map<String, String> namespaceHashes) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("mcVersion", mcVersion);
        envelope.put("mods", namespaceHashes);
        return GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static final class SyncResult {
        public final boolean noChange;
        public final SyncManifest manifest;
        public final byte[] zstdBody;

        private SyncResult(boolean noChange, SyncManifest manifest, byte[] zstdBody) {
            this.noChange = noChange;
            this.manifest = manifest;
            this.zstdBody = zstdBody;
        }

        public static SyncResult noChange() {
            return new SyncResult(true, null, null);
        }

        public static SyncResult withPayload(SyncManifest manifest, byte[] zstdBody) {
            return new SyncResult(false, manifest, zstdBody);
        }
    }

}
