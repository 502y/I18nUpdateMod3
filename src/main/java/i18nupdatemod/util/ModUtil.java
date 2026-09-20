package i18nupdatemod.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moandjiezana.toml.Toml;
import i18nupdatemod.entity.ModTranslation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ModUtil {
    /**
     * Discover local mod JARs for all loaders, including their nested JARs.
     * This deliberately scans installed candidates rather than a loader's
     * resolved mod list; development directories are not included.
     */
    public static List<ModTranslation> getModsFromModsFolder(Path minecraftPath) {
        List<ModTranslation> result = new ArrayList<>();
        if (minecraftPath == null) {
            return result;
        }

        Path modsPath = minecraftPath.resolve("mods");
        if (!Files.isDirectory(modsPath)) {
            return result;
        }

        List<Path> entries;
        try (Stream<Path> stream = Files.list(modsPath)) {
            entries = stream
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            Log.warning("Failed to list mods directory %s: %s", modsPath, e);
            return result;
        }

        for (Path entry : entries) {
            try {
                scanArchive(entry, Collections.<String>emptyList(), result);
            } catch (Exception e) {
                Log.warning("Failed to read mod %s: %s", entry, e);
            }
        }
        return result;
    }

    private static void scanArchive(Path source, List<String> nestedJars,
                                    List<ModTranslation> output) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            scanArchive(input, source, nestedJars, output);
        }
    }

    private static void scanArchive(InputStream input, Path source, List<String> nestedJars,
                                    List<ModTranslation> output) throws IOException {
        ParsedMod parsed = new ParsedMod(source, nestedJars);
        List<NestedArchive> nestedArchives = new ArrayList<>();
        try (JarInputStream jar = new JarInputStream(input)) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                String path = safeArchivePath(entry.getName());
                if (path == null) {
                    continue;
                }
                collectNamespace(parsed.namespaces, path);

                String metadataKind = metadataKind(path);
                boolean nestedArchive = !entry.isDirectory() && path.toLowerCase().endsWith(".jar");
                if (metadataKind != null || nestedArchive) {
                    byte[] bytes = readCurrentEntry(jar);
                    if (metadataKind != null) {
                        try {
                            parseMetadata(parsed.metadata, metadataKind, bytes);
                        } catch (Exception e) {
                            Log.warning("Failed to parse metadata %s in %s: %s", path, source, e);
                        }
                    }
                    if (nestedArchive) {
                        List<String> chain = new ArrayList<>(nestedJars);
                        chain.add(path);
                        nestedArchives.add(new NestedArchive(bytes, chain, path));
                    }
                }
            }
        }

        addTranslations(parsed, output);
        for (NestedArchive nestedArchive : nestedArchives) {
            try (InputStream nestedInput = new ByteArrayInputStream(nestedArchive.bytes)) {
                scanArchive(nestedInput, source, nestedArchive.chain, output);
            } catch (Exception e) {
                Log.warning("Failed to parse nested jar %s inside %s: %s", nestedArchive.name, source, e);
            }
        }
    }

    private static void addTranslations(ParsedMod parsed, List<ModTranslation> output) {
        List<String> namespaces = new ArrayList<>(parsed.namespaces);
        Collections.sort(namespaces);
        for (String namespace : namespaces) {
            MetadataRecord metadata = chooseMetadata(parsed, namespace);
            output.add(new ModTranslation(
                    namespace,
                    metadata == null ? null : metadata.authors,
                    metadata == null ? null : metadata.displayName,
                    parsed.source,
                    parsed.nestedJars));
        }
    }

    private static MetadataRecord chooseMetadata(ParsedMod parsed, String namespace) {
        List<MetadataRecord> exact = new ArrayList<>();
        for (MetadataRecord record : parsed.metadata) {
            if (record.ownerIds.contains(namespace)) {
                exact.add(record);
            }
        }
        if (!exact.isEmpty()) {
            return mergeMetadata(exact);
        }

        // A single metadata document and a single discovered namespace are an
        // unambiguous owner even when a loader uses an alias for its mod id.
        if (parsed.metadata.size() == 1 && parsed.namespaces.size() == 1) {
            return parsed.metadata.get(0);
        }
        return null;
    }

    private static MetadataRecord mergeMetadata(List<MetadataRecord> records) {
        MetadataRecord merged = new MetadataRecord();
        for (MetadataRecord record : records) {
            for (String ownerId : record.ownerIds) {
                if (!merged.ownerIds.contains(ownerId)) {
                    merged.ownerIds.add(ownerId);
                }
            }
            merged.displayName = mergeString(merged.displayName, record.displayName);
            merged.authors = mergeAuthors(merged.authors, record.authors);
        }
        return merged;
    }

    private static String mergeString(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null || left.equals(right)) {
            return left;
        }
        return null;
    }

    private static List<String> mergeAuthors(List<String> left, List<String> right) {
        if (left == null) {
            return right;
        }
        if (right == null || left.equals(right)) {
            return left;
        }
        return null;
    }

    private static void parseMetadata(List<MetadataRecord> records, String kind, byte[] bytes) {
        if ("json".equals(kind)) {
            parseJsonMetadata(records, bytes);
        } else {
            parseTomlMetadata(records, bytes);
        }
    }

    private static void parseJsonMetadata(List<MetadataRecord> records, byte[] bytes) {
        JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        collectJsonRecords(records, root);
    }

    private static void collectJsonRecords(List<MetadataRecord> records, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectJsonRecords(records, child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement modList = object.get("modList");
        if (modList != null && modList.isJsonArray()) {
            collectJsonRecords(records, modList);
            return;
        }

        MetadataRecord record = new MetadataRecord();
        addOwner(record.ownerIds, stringValue(object.get("modid")));
        addOwner(record.ownerIds, stringValue(object.get("modId")));
        addOwner(record.ownerIds, stringValue(object.get("id")));
        JsonElement provides = object.get("provides");
        if (provides != null && provides.isJsonArray()) {
            for (JsonElement provided : provides.getAsJsonArray()) {
                addOwner(record.ownerIds, stringValue(provided));
            }
        }

        record.displayName = firstString(object, "displayName", "name");
        record.authors = firstAuthors(object, "authors", "authorList");
        records.add(record);
    }

    private static void parseTomlMetadata(List<MetadataRecord> records, byte[] bytes) {
        Toml root = new Toml().read(new ByteArrayInputStream(bytes));
        List<Toml> mods = root.getTables("mods");
        if (mods == null) {
            return;
        }
        for (Toml mod : mods) {
            Map<String, Object> values = mod.toMap();
            MetadataRecord record = new MetadataRecord();
            addOwner(record.ownerIds, valueString(values.get("modId")));
            addOwner(record.ownerIds, valueString(values.get("modid")));
            record.displayName = firstValueString(values, "displayName", "name");
            record.authors = authorsValue(values.get("authors"));
            records.add(record);
        }
    }

    private static String firstString(JsonObject object, String first, String second) {
        String value = stringValue(object.get(first));
        return value == null ? stringValue(object.get(second)) : value;
    }

    private static List<String> firstAuthors(JsonObject object, String first, String second) {
        JsonElement value = object.get(first);
        if (value != null) {
            return authorsJson(value);
        }
        return authorsJson(object.get(second));
    }

    private static List<String> authorsJson(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        List<String> authors = new ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement author : value.getAsJsonArray()) {
                if (author != null && author.isJsonObject()) {
                    addAuthor(authors, stringValue(author.getAsJsonObject().get("name")));
                } else {
                    addAuthor(authors, stringValue(author));
                }
            }
        } else {
            addAuthor(authors, stringValue(value));
        }
        return authors;
    }

    private static List<String> authorsValue(Object value) {
        if (value == null) {
            return null;
        }
        List<String> authors = new ArrayList<>();
        if (value instanceof Iterable) {
            for (Object author : (Iterable<?>) value) {
                if (author instanceof Map) {
                    addAuthor(authors, valueString(((Map<?, ?>) author).get("name")));
                } else {
                    addAuthor(authors, valueString(author));
                }
            }
        } else {
            addAuthor(authors, valueString(value));
        }
        return authors;
    }

    private static void addAuthor(List<String> authors, String author) {
        if (author != null && !author.isEmpty() && !authors.contains(author)) {
            authors.add(author);
        }
    }

    private static void addOwner(List<String> ownerIds, String ownerId) {
        if (ownerId != null && !ownerId.isEmpty() && !ownerIds.contains(ownerId)) {
            ownerIds.add(ownerId);
        }
    }

    private static String stringValue(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static String valueString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String firstValueString(Map<String, Object> values, String first, String second) {
        String value = valueString(values.get(first));
        return value == null ? valueString(values.get(second)) : value;
    }

    private static String safeArchivePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\u0000') >= 0) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }
        String[] components = normalized.split("/", -1);
        StringBuilder result = new StringBuilder(normalized.length());
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                return null;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(component);
        }
        return result.toString();
    }

    private static void collectNamespace(Set<String> namespaces, String path) {
        if (path == null || !path.startsWith("assets/")) {
            return;
        }
        String remainder = path.substring("assets/".length());
        int separator = remainder.indexOf('/');
        String namespace = separator < 0 ? remainder : remainder.substring(0, separator);
        if (!namespace.isEmpty()) {
            namespaces.add(namespace);
        }
    }

    private static String metadataKind(String path) {
        String normalized = path == null ? "" : path.toLowerCase();
        if ("mcmod.info".equals(normalized) || "meta-inf/mcmod.info".equals(normalized)) {
            return "json";
        }
        if ("fabric.mod.json".equals(normalized)) {
            return "json";
        }
        if ("meta-inf/mods.toml".equals(normalized) || "meta-inf/neoforge.mods.toml".equals(normalized)) {
            return "toml";
        }
        return null;
    }

    private static byte[] readCurrentEntry(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static class ParsedMod {
        final Path source;
        final List<String> nestedJars;
        final Set<String> namespaces = new LinkedHashSet<>();
        final List<MetadataRecord> metadata = new ArrayList<>();

        ParsedMod(Path source, List<String> nestedJars) {
            this.source = source;
            this.nestedJars = new ArrayList<>(nestedJars);
        }
    }

    private static class NestedArchive {
        final byte[] bytes;
        final List<String> chain;
        final String name;

        NestedArchive(byte[] bytes, List<String> chain, String name) {
            this.bytes = bytes;
            this.chain = chain;
            this.name = name;
        }
    }

    private static class MetadataRecord {
        final List<String> ownerIds = new ArrayList<>();
        List<String> authors;
        String displayName;
    }
}
