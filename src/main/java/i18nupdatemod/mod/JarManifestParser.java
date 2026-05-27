package i18nupdatemod.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import i18nupdatemod.entity.ModMetaData;
import i18nupdatemod.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads mod manifest files from the jar(s) backing a {@link JarRef} and
 * resolves them into a {@link ModMetaData} (id, displayName, authors).
 * <p>
 * Supports the same formats as the legacy ModUtil scanner: {@code mcmod.info},
 * {@code fabric.mod.json}, {@code quilt.mod.json}, and Forge's
 * {@code META-INF/mods.toml}. When a single jar carries multiple mod entries
 * (mcmod.info or mods.toml [[mods]] arrays), the entry whose id matches the
 * caller-supplied namespace is preferred; otherwise the only entry wins; else
 * a default empty record is returned.
 */
public final class JarManifestParser {
    public static final String MCMOD_INFO = "mcmod.info";
    public static final String FABRIC_MOD_JSON = "fabric.mod.json";
    public static final String QUILT_MOD_JSON = "quilt.mod.json";
    public static final String MODS_TOML = "META-INF/mods.toml";

    public static final List<String> MANIFEST_FILES =
            java.util.Arrays.asList(MCMOD_INFO, FABRIC_MOD_JSON, QUILT_MOD_JSON, MODS_TOML);

    /**
     * Parse manifest bytes collected from a single jar's central directory.
     * {@code manifestBytes} maps each known manifest filename to its raw
     * content (entries absent in the jar simply aren't in the map).
     */
    public static ModMetaData parse(Map<String, byte[]> manifestBytes, String namespace) {
        Map<String, Entry> entriesById = new LinkedHashMap<>();
        for (String manifestFile : MANIFEST_FILES) {
            byte[] bytes = manifestBytes.get(manifestFile);
            if (bytes == null) continue;
            try {
                for (Entry parsed : parseManifest(manifestFile, bytes)) {
                    if (parsed.id != null && !parsed.id.isEmpty()) {
                        entriesById.putIfAbsent(parsed.id, parsed);
                    }
                }
            } catch (Exception e) {
                Log.warning("JarManifestParser: parse %s failed: %s", manifestFile, e);
            }
        }

        Entry chosen = entriesById.get(namespace);
        if (chosen == null && entriesById.size() == 1) {
            chosen = entriesById.values().iterator().next();
        }
        if (chosen == null) {
            return new ModMetaData(namespace, null, new ArrayList<>());
        }
        return new ModMetaData(namespace, chosen.displayName,
                chosen.authors != null ? chosen.authors : new ArrayList<>());
    }

    private static List<Entry> parseManifest(String filename, byte[] bytes) {
        switch (filename) {
            case MCMOD_INFO:
                return parseMcmodInfo(bytes);
            case FABRIC_MOD_JSON:
                return parseFabricModJson(bytes);
            case QUILT_MOD_JSON:
                return parseQuiltModJson(bytes);
            case MODS_TOML:
                return parseForgeModsToml(bytes);
            default:
                return new ArrayList<>();
        }
    }

    // -- format parsers (lifted from legacy ModUtil, extended to capture displayName) --

    private static List<Entry> parseMcmodInfo(byte[] bytes) {
        List<Entry> result = new ArrayList<>();
        JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("modList")) {
            array = root.getAsJsonObject().getAsJsonArray("modList");
        } else {
            return result;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            Entry entry = new Entry();
            if (obj.has("modid") && obj.get("modid").isJsonPrimitive()) {
                entry.id = obj.get("modid").getAsString();
            }
            if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
                entry.displayName = obj.get("name").getAsString();
            }
            JsonElement authors = obj.has("authorList") ? obj.get("authorList")
                    : obj.has("authors") ? obj.get("authors") : null;
            entry.authors = readStringArray(authors);
            result.add(entry);
        }
        return result;
    }

    private static List<Entry> parseFabricModJson(byte[] bytes) {
        List<Entry> result = new ArrayList<>();
        JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) return result;
        JsonObject obj = root.getAsJsonObject();
        Entry entry = new Entry();
        if (obj.has("id") && obj.get("id").isJsonPrimitive()) {
            entry.id = obj.get("id").getAsString();
        }
        if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
            entry.displayName = obj.get("name").getAsString();
        }
        entry.authors = readFabricAuthors(obj.get("authors"));
        result.add(entry);
        return result;
    }

    private static List<Entry> parseQuiltModJson(byte[] bytes) {
        List<Entry> result = new ArrayList<>();
        JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) return result;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("quilt_loader") || !obj.get("quilt_loader").isJsonObject()) return result;
        JsonObject loader = obj.getAsJsonObject("quilt_loader");
        Entry entry = new Entry();
        if (loader.has("id") && loader.get("id").isJsonPrimitive()) {
            entry.id = loader.get("id").getAsString();
        }
        ArrayList<String> authors = new ArrayList<>();
        if (loader.has("metadata") && loader.get("metadata").isJsonObject()) {
            JsonObject meta = loader.getAsJsonObject("metadata");
            if (meta.has("name") && meta.get("name").isJsonPrimitive()) {
                entry.displayName = meta.get("name").getAsString();
            }
            if (meta.has("contributors") && meta.get("contributors").isJsonObject()) {
                for (Map.Entry<String, JsonElement> contributor : meta.getAsJsonObject("contributors").entrySet()) {
                    authors.add(contributor.getKey());
                }
            }
        }
        entry.authors = authors;
        result.add(entry);
        return result;
    }

    private static List<Entry> parseForgeModsToml(byte[] bytes) {
        List<Entry> result = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<Map<String, Object>> rows = MiniToml.readTableArray(text, "mods");
        for (Map<String, Object> row : rows) {
            Entry entry = new Entry();
            Object id = row.get("modId");
            if (id instanceof String) {
                entry.id = (String) id;
            }
            Object displayName = row.get("displayName");
            if (displayName instanceof String) {
                entry.displayName = (String) displayName;
            }
            Object authors = row.get("authors");
            if (authors instanceof String) {
                entry.authors = splitCommaAuthors((String) authors);
            } else if (authors instanceof List) {
                ArrayList<String> list = new ArrayList<>();
                for (Object element : (List<?>) authors) {
                    if (element instanceof String) list.add(((String) element).trim());
                }
                entry.authors = list;
            } else {
                entry.authors = new ArrayList<>();
            }
            result.add(entry);
        }
        return result;
    }

    private static ArrayList<String> readStringArray(JsonElement element) {
        ArrayList<String> out = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return out;
        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonPrimitive()) out.add(child.getAsString().trim());
        }
        return out;
    }

    private static ArrayList<String> readFabricAuthors(JsonElement element) {
        ArrayList<String> out = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return out;
        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonPrimitive()) {
                out.add(child.getAsString().trim());
            } else if (child.isJsonObject()) {
                JsonObject obj = child.getAsJsonObject();
                if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
                    out.add(obj.get("name").getAsString().trim());
                }
            }
        }
        return out;
    }

    private static ArrayList<String> splitCommaAuthors(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private JarManifestParser() {
    }

    private static final class Entry {
        String id;
        String displayName;
        ArrayList<String> authors;
    }

    /**
     * Minimal TOML reader covering the subset used by Forge mods.toml. Lifted
     * verbatim from the legacy ModUtil so the parser behavior stays unchanged.
     */
    static final class MiniToml {
        static List<Map<String, Object>> readTableArray(String text, String name) {
            List<Map<String, Object>> tables = new ArrayList<>();
            String[] lines = text.split("\\r?\\n");
            boolean inTargetTable = false;
            Map<String, Object> current = null;
            boolean inMultilineString = false;

            int i = 0;
            while (i < lines.length) {
                String raw = lines[i];
                i++;
                String line = stripComment(raw).trim();

                if (inMultilineString) {
                    if (line.contains("\"\"\"") || line.contains("'''")) {
                        inMultilineString = false;
                    }
                    continue;
                }

                if (line.isEmpty()) continue;

                if (line.startsWith("[[") && line.endsWith("]]")) {
                    String section = line.substring(2, line.length() - 2).trim();
                    if (section.equals(name)) {
                        current = new HashMap<>();
                        tables.add(current);
                        inTargetTable = true;
                    } else {
                        inTargetTable = false;
                        current = null;
                    }
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inTargetTable = false;
                    current = null;
                    continue;
                }

                if (!inTargetTable) continue;

                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                if (value.startsWith("\"\"\"") || value.startsWith("'''")) {
                    String trip = value.substring(0, 3);
                    int close = value.indexOf(trip, 3);
                    if (close < 0) {
                        inMultilineString = true;
                    }
                    continue;
                }

                if (value.startsWith("\"") || value.startsWith("'")) {
                    current.put(key, unquote(value));
                } else if (value.startsWith("[")) {
                    String collected = value;
                    while (!isArrayClosed(collected) && i < lines.length) {
                        collected = collected + " " + stripComment(lines[i]).trim();
                        i++;
                    }
                    current.put(key, parseInlineArray(collected));
                }
            }
            return tables;
        }

        private static String stripComment(String line) {
            boolean inStr = false;
            char strCh = 0;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (inStr) {
                    if (c == '\\' && j + 1 < line.length()) {
                        sb.append(c).append(line.charAt(j + 1));
                        j++;
                        continue;
                    }
                    if (c == strCh) inStr = false;
                    sb.append(c);
                } else {
                    if (c == '#') break;
                    if (c == '"' || c == '\'') {
                        inStr = true;
                        strCh = c;
                    }
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private static boolean isArrayClosed(String s) {
            int depth = 0;
            boolean inStr = false;
            char strCh = 0;
            for (int j = 0; j < s.length(); j++) {
                char c = s.charAt(j);
                if (inStr) {
                    if (c == '\\' && j + 1 < s.length()) {
                        j++;
                        continue;
                    }
                    if (c == strCh) inStr = false;
                } else {
                    if (c == '"' || c == '\'') {
                        inStr = true;
                        strCh = c;
                    } else if (c == '[') depth++;
                    else if (c == ']') depth--;
                }
            }
            return depth <= 0;
        }

        private static List<String> parseInlineArray(String s) {
            List<String> out = new ArrayList<>();
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start < 0 || end < 0 || end <= start) return out;
            String body = s.substring(start + 1, end);
            List<String> parts = splitTopLevel(body);
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
                    out.add(unquote(trimmed));
                }
            }
            return out;
        }

        private static List<String> splitTopLevel(String body) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inStr = false;
            char strCh = 0;
            int depth = 0;
            for (int j = 0; j < body.length(); j++) {
                char c = body.charAt(j);
                if (inStr) {
                    if (c == '\\' && j + 1 < body.length()) {
                        cur.append(c).append(body.charAt(j + 1));
                        j++;
                        continue;
                    }
                    if (c == strCh) inStr = false;
                    cur.append(c);
                } else {
                    if (c == '"' || c == '\'') {
                        inStr = true;
                        strCh = c;
                        cur.append(c);
                    } else if (c == '[') {
                        depth++;
                        cur.append(c);
                    } else if (c == ']') {
                        depth--;
                        cur.append(c);
                    } else if (c == ',' && depth == 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else {
                        cur.append(c);
                    }
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }

        private static String unquote(String s) {
            String trimmed = s.trim();
            if (trimmed.length() >= 2) {
                char first = trimmed.charAt(0);
                char last = trimmed.charAt(trimmed.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    String body = trimmed.substring(1, trimmed.length() - 1);
                    if (first == '"') {
                        body = body.replace("\\\"", "\"").replace("\\\\", "\\")
                                .replace("\\n", "\n").replace("\\t", "\t");
                    }
                    return body;
                }
            }
            return trimmed;
        }
    }
}
