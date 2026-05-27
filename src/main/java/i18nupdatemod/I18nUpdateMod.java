package i18nupdatemod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import i18nupdatemod.core.GameConfig;
import i18nupdatemod.core.I18nConfig;
import i18nupdatemod.core.ResourcePack;
import i18nupdatemod.core.ResourcePackConverter;
import i18nupdatemod.entity.GameAssetDetail;
import i18nupdatemod.entity.GameMetaData;
import i18nupdatemod.mod.ModNamespaceScanner;
import i18nupdatemod.sync.Rule;
import i18nupdatemod.sync.SyncClient;
import i18nupdatemod.sync.SyncResourcePack;
import i18nupdatemod.util.FileUtil;
import i18nupdatemod.util.Log;
import i18nupdatemod.util.Version;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class I18nUpdateMod {
    public static final String MOD_ID = "i18nupdatemod";
    public static String MOD_VERSION;

    public static final Gson GSON = new Gson();

    // TODO: replace with production URL or read from i18nMetaData.json once finalised.
    private static final String SYNC_SERVER_URL = "http://203.135.99.76:30063";

    public static void init(Path minecraftPath, String minecraftVersion, String loader) {
        try (InputStream is = I18nUpdateMod.class.getResourceAsStream("/i18nMetaData.json")) {
            MOD_VERSION = GSON.fromJson(new InputStreamReader(is), JsonObject.class).get("version").getAsString();
        } catch (Exception e) {
            Log.warning("Error getting version: " + e);
        }

        Log.info(String.format("I18nUpdate Mod %s is loaded in %s with %s", MOD_VERSION, minecraftVersion, loader));
        Log.debug(String.format("Minecraft path: %s", minecraftPath));
        String localStorage = getLocalStoragePos(minecraftPath);
        Log.debug(String.format("Local Storage Pos: %s", localStorage));

        try {
            Class.forName("com.netease.mc.mod.network.common.Library");
            Log.warning("I18nUpdateMod will get resource pack from Internet, whose content is uncontrolled.");
            Log.warning("This behavior contraries to Netease Minecraft developer content review rule: " +
                    "forbidden the content in game not match the content for reviewing.");
            Log.warning("To follow this rule, I18nUpdateMod won't download any thing.");
            Log.warning("I18nUpdateMod会从互联网获取内容不可控的资源包。");
            Log.warning("这一行为违背了网易我的世界「开发者内容审核制度」：禁止上传与提审内容不一致的游戏内容。");
            Log.warning("为了遵循这一制度，I18nUpdateMod不会下载任何内容。");
            return;
        } catch (ClassNotFoundException ignored) {
        }

        Path resourcePacksDir = minecraftPath.resolve("resourcepacks");
        FileUtil.setResourcePackDirPath(resourcePacksDir);

        int minecraftMajorVersion = Integer.parseInt(minecraftVersion.split("\\.")[1]);

        try {
            //Asset metadata is used by both flows for pack format + description.
            GameAssetDetail assets = I18nConfig.getAssetDetail(minecraftVersion, loader);
            GameMetaData metaData = I18nConfig.getPackFormat(minecraftVersion);
            String description = getResourcePackDescription(assets.downloads);

            String appliedFilename;
            if (trySyncFlow(localStorage, resourcePacksDir, minecraftPath, minecraftVersion, metaData, description)) {
                appliedFilename = syncOutputFilename(minecraftVersion);
            } else {
                //Fallback: full-download flow. Re-scan with empty rules to get base
                //namespaces for the asset filter (no CFPA expansion needed).
                Set<String> baseNamespaces = ModNamespaceScanner.resolveNamespaces(minecraftPath, Collections.emptyList());
                HashSet<String> modDomainsSet = new HashSet<>(baseNamespaces);

                List<ResourcePack> languagePacks = new ArrayList<>();
                for (GameAssetDetail.AssetDownloadDetail it : assets.downloads) {
                    FileUtil.setTemporaryDirPath(Paths.get(localStorage, "." + MOD_ID, it.targetVersion));
                    ResourcePack languagePack = new ResourcePack(it.fileName);
                    languagePack.checkUpdate(it.fileUrl, it.md5Url);
                    languagePacks.add(languagePack);
                }

                FileUtil.setTemporaryDirPath(Paths.get(localStorage, "." + MOD_ID, minecraftVersion));
                ResourcePackConverter converter = new ResourcePackConverter(languagePacks, assets.covertFileName);
                converter.convert(metaData, description, modDomainsSet);
                appliedFilename = assets.covertFileName;
            }

            //Apply resource pack
            GameConfig config = new GameConfig(minecraftPath.resolve("options.txt"));
            config.addResourcePack("Minecraft-Mod-Language-Modpack",
                    (minecraftMajorVersion <= 12 ? "" : "file/") + appliedFilename);
            config.writeToFile();
        } catch (Exception e) {
            Log.warning(String.format("Failed to update resource pack: %s", e));
//            e.printStackTrace();
        }
    }

    private static boolean trySyncFlow(String localStorage,
                                       Path resourcePacksDir,
                                       Path minecraftPath,
                                       String minecraftVersion,
                                       GameMetaData metaData,
                                       String description) {
        // The server only serves a fixed set of MC versions (matching the official
        // CFPA resource packs). Map the actual game version to the highest entry
        // in metaData.convertFrom — the same set used by the legacy full-download
        // flow to pick which pack to merge from.
        String syncVersion = pickSyncVersion(metaData);
        if (syncVersion == null) {
            Log.info("Sync: no supported version for %s, falling back", minecraftVersion);
            return false;
        }
        try {
            SyncClient client = new SyncClient(SYNC_SERVER_URL);

            // 1. Rules first (cheap network); 2. single jar walk with rule context
            //    produces final CFPA-resolved namespace set in one pass.
            List<Rule> rules = client.fetchRules(syncVersion);
            Log.info("Sync: fetched %d rules for %s", rules.size(), syncVersion);
            Set<String> resolved = ModNamespaceScanner.resolveNamespaces(minecraftPath, rules);
            Log.debug("Sync: resolved %d CFPA-aware namespaces", resolved.size());

            Path syncCache = Paths.get(localStorage, "." + MOD_ID, syncVersion, "sync");
            SyncResourcePack syncPack = new SyncResourcePack(
                    client,
                    syncCache,
                    resourcePacksDir,
                    syncVersion,
                    syncOutputFilename(minecraftVersion));
            Path produced = syncPack.apply(new ArrayList<>(resolved), metaData, description);
            return produced != null;
        } catch (Exception e) {
            Log.warning("Sync flow failed, falling back to full download: " + e);
            return false;
        }
    }

    /** Highest version in {@code metaData.convertFrom}, or null when none parseable. */
    private static String pickSyncVersion(GameMetaData metaData) {
        if (metaData == null || metaData.convertFrom == null || metaData.convertFrom.isEmpty()) {
            return null;
        }
        String highestRaw = null;
        Version highestParsed = null;
        for (String candidate : metaData.convertFrom) {
            Version parsed = Version.from(candidate);
            if (parsed == null) continue;
            if (highestParsed == null || parsed.compareTo(highestParsed) > 0) {
                highestParsed = parsed;
                highestRaw = candidate;
            }
        }
        return highestRaw;
    }

    private static String syncOutputFilename(String minecraftVersion) {
        return String.format("Minecraft-Mod-Language-Modpack-Sync-%s.zip", minecraftVersion);
    }

    private static String getResourcePackDescription(List<GameAssetDetail.AssetDownloadDetail> downloads) {
        return downloads.size() > 1 ?
                String.format("该包由%s版本合并\n作者：CFPA团队及汉化项目贡献者",
                        downloads.stream().map(it -> it.targetVersion).collect(Collectors.joining("和"))) :
                String.format("该包对应的官方支持版本为%s\n作者：CFPA团队及汉化项目贡献者",
                        downloads.get(0).targetVersion);

    }

    public static String getLocalStoragePos(Path minecraftPath) {
        Path userHome = Paths.get(System.getProperty("user.home"));
        Path oldPath = userHome.resolve("." + MOD_ID);
        if (Files.exists(oldPath)) {
            return userHome.toString();
        }

        // https://developer.apple.com/documentation/foundation/url/3988452-applicationsupportdirectory#discussion
        String macAppSupport = System.getProperty("os.name").contains("OS X") ?
                userHome.resolve("Library/Application Support").toString() : null;
        String localAppData = System.getenv("LocalAppData");

        // XDG_DATA_HOME fallbacks to ~/.local/share
        // https://specifications.freedesktop.org/basedir-spec/latest/#variables
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome == null) {
            xdgDataHome = userHome.resolve(".local/share").toString();
        }

        return Stream.of(localAppData, macAppSupport).filter(
                Objects::nonNull
        ).findFirst().orElse(xdgDataHome);
    }

}