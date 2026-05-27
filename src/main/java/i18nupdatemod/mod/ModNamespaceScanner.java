package i18nupdatemod.mod;

import i18nupdatemod.entity.ModMetaData;
import i18nupdatemod.sync.Rule;
import i18nupdatemod.sync.XxHash64;
import i18nupdatemod.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Single entry point of the client-side scan: walk every jar under {@code mods/}
 * (including JarInJar) once, and produce the final set of namespace identifiers
 * to ask the server about.
 * <p>
 * For each base namespace contributed by a jar:
 * <ul>
 *   <li>If no rule applies — emit the base namespace. Multiple jars with the
 *       same base namespace collapse to a single entry.</li>
 *   <li>If a rule applies — emit {@code base-CFPA-<identifier>}, computing the
 *       identifier from this jar's own manifest (author / displayName) or by
 *       reading the rule-specified file from this jar. Each jar contributes
 *       its own CFPA-suffixed entry, naturally disambiguated.</li>
 * </ul>
 * The mod's own namespace ({@code i18nupdatemod}) is dropped from the result.
 */
public final class ModNamespaceScanner {
    private static final String SELF_NAMESPACE = "i18nupdatemod";
    private static final String ASSETS_PREFIX = "assets/";

    private ModNamespaceScanner() {
    }

    public static Set<String> resolveNamespaces(Path minecraftPath, List<Rule> rules) {
        Map<String, Rule> ruleByNamespace = indexRules(rules);
        Set<String> result = new LinkedHashSet<>();
        Path modsDir = minecraftPath.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return result;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : jars) {
                scanOuterJar(jar, ruleByNamespace, result);
            }
        } catch (IOException e) {
            Log.warning("ModNamespaceScanner: failed listing %s: %s", modsDir, e);
        }
        result.remove(SELF_NAMESPACE);
        return result;
    }

    private static Map<String, Rule> indexRules(List<Rule> rules) {
        if (rules == null) return Collections.emptyMap();
        Map<String, Rule> result = new HashMap<>(rules.size());
        for (Rule rule : rules) {
            if (rule == null || rule.namespace == null) continue;
            result.put(rule.namespace, rule);
        }
        return result;
    }

    // -- outer jar (random-access ZipFile) --

    private static void scanOuterJar(Path jarPath, Map<String, Rule> rules, Set<String> result) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ScanContext context = new ScanContext();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();

                String namespace = extractAssetNamespace(entryName);
                if (namespace != null) {
                    context.namespaces.add(namespace);
                    continue;
                }
                if (JarManifestParser.MANIFEST_FILES.contains(entryName)) {
                    context.manifestBytes.put(entryName, drain(zip.getInputStream(entry)));
                    continue;
                }
                if (isNestedJarEntry(entryName)) {
                    context.nestedJarBytes.add(drain(zip.getInputStream(entry)));
                }
            }
            applyRules(context, rules, result, name -> readEntryFromZipFile(zip, name));
            for (byte[] nestedBytes : context.nestedJarBytes) {
                scanNestedJarBytes(nestedBytes, rules, result);
            }
        } catch (IOException e) {
            Log.warning("ModNamespaceScanner: failed scanning %s: %s", jarPath, e);
        }
    }

    // -- nested jar (in-memory ZipInputStream) --

    private static void scanNestedJarBytes(byte[] nestedBytes, Map<String, Rule> rules, Set<String> result) {
        ScanContext context = new ScanContext();
        Map<String, byte[]> arbitraryEntries = new HashMap<>(); // for file-path identifier lookups
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(nestedBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                String namespace = extractAssetNamespace(entryName);
                if (namespace != null) {
                    context.namespaces.add(namespace);
                    continue;
                }
                if (JarManifestParser.MANIFEST_FILES.contains(entryName)) {
                    context.manifestBytes.put(entryName, drain(zis));
                    continue;
                }
                if (isNestedJarEntry(entryName)) {
                    context.nestedJarBytes.add(drain(zis));
                    continue;
                }
                // Buffer non-manifest, non-nested entries that might match a file-path identifier.
                // Bounded: rule.identifier paths are typically small text markers; few per ns.
                if (isLikelyIdentifierFile(entryName, rules)) {
                    arbitraryEntries.put(entryName, drain(zis));
                }
            }
        } catch (IOException e) {
            Log.warning("ModNamespaceScanner: failed scanning nested jar: %s", e);
            return;
        }
        applyRules(context, rules, result, arbitraryEntries::get);
        for (byte[] deeperBytes : context.nestedJarBytes) {
            scanNestedJarBytes(deeperBytes, rules, result);
        }
    }

    /** True if any rule's file-path identifier matches this entry name. */
    private static boolean isLikelyIdentifierFile(String entryName, Map<String, Rule> rules) {
        for (Rule rule : rules.values()) {
            if (Rule.CommonIdentifier.fromWire(rule.identifier) == null
                    && entryName.equals(rule.identifier)) {
                return true;
            }
        }
        return false;
    }

    // -- rule application (shared) --

    private static void applyRules(ScanContext context,
                                   Map<String, Rule> rules,
                                   Set<String> result,
                                   ArbitraryEntryReader arbitraryReader) {
        for (String namespace : context.namespaces) {
            Rule rule = rules.get(namespace);
            if (rule == null) {
                result.add(namespace);
                continue;
            }
            String identifier = resolveIdentifier(rule, context.manifestBytes, arbitraryReader);
            if (identifier == null || identifier.isEmpty()) {
                Log.debug("ModNamespaceScanner: empty identifier for %s, using base ns", namespace);
                result.add(namespace);
            } else {
                result.add(namespace + "-CFPA-" + identifier);
            }
        }
    }

    private static String resolveIdentifier(Rule rule,
                                            Map<String, byte[]> manifestBytes,
                                            ArbitraryEntryReader arbitraryReader) {
        Rule.CommonIdentifier common = Rule.CommonIdentifier.fromWire(rule.identifier);
        if (common != null) {
            ModMetaData detail = JarManifestParser.parse(manifestBytes, rule.namespace);
            switch (common) {
                case AUTHOR:
                    return firstNonEmpty(detail.author);
                case DISPLAY_NAME:
                    return detail.displayName;
                default:
                    return null;
            }
        }
        byte[] bytes = arbitraryReader.read(rule.identifier);
        if (bytes == null) return null;
        XxHash64 hasher = new XxHash64();
        hasher.update(bytes);
        return String.format("%016x", hasher.sum());
    }

    // -- helpers --

    private static String extractAssetNamespace(String entryName) {
        if (!entryName.startsWith(ASSETS_PREFIX)) return null;
        int namespaceStart = ASSETS_PREFIX.length();
        int namespaceEnd = entryName.indexOf('/', namespaceStart);
        if (namespaceEnd <= namespaceStart) return null;
        return entryName.substring(namespaceStart, namespaceEnd);
    }

    private static boolean isNestedJarEntry(String entryName) {
        return entryName.endsWith(".jar");
    }

    private static byte[] readEntryFromZipFile(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return drain(in);
        } catch (IOException e) {
            Log.warning("ModNamespaceScanner: read %s failed: %s", entryName, e);
            return null;
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        int read;
        while ((read = in.read(buf)) != -1) {
            sink.write(buf, 0, read);
        }
        return sink.toByteArray();
    }

    private static String firstNonEmpty(List<String> values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    @FunctionalInterface
    private interface ArbitraryEntryReader {
        byte[] read(String entryName);
    }

    private static final class ScanContext {
        final Set<String> namespaces = new LinkedHashSet<>();
        final Map<String, byte[]> manifestBytes = new LinkedHashMap<>();
        final java.util.List<byte[]> nestedJarBytes = new java.util.ArrayList<>();
    }
}
