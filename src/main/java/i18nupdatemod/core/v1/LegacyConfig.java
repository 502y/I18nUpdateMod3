package i18nupdatemod.core.v1;

import i18nupdatemod.core.I18nConfig;
import i18nupdatemod.entity.AssetMetaData;
import i18nupdatemod.entity.GameMetaData;
import i18nupdatemod.util.Log;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static i18nupdatemod.core.v1.AssetUtil.getFastestUrl;
import static i18nupdatemod.core.v1.AssetUtil.getGitIndex;

public class LegacyConfig {
    /**
     * <a href="https://github.com/CFPAOrg/Minecraft-Mod-Language-Package">CFPAOrg/Minecraft-Mod-Language-Package</a>
     */
    private static final String CFPA_ASSET_ROOT = "http://downloader1.meitangdehulu.com:22943/";

    public static List<AssetDownloadDetail> getLegacyDownloads(GameMetaData convert, String loader) {
        String assetRoot = getFastestUrl();
        Log.debug("Using asset root: " + assetRoot);

        if (assetRoot.equals("https://raw.githubusercontent.com/")) {
            return createDownloadDetailsFromGit(convert, loader);
        }
        return createDownloadDetails(convert, loader, assetRoot);
    }

    private static AssetMetaData getAssetMetaData(String minecraftVersion, String loader) {
        List<AssetMetaData> current = I18nConfig.getMetaData().assets.stream()
                .filter(it -> it.targetVersion.equals(minecraftVersion))
                .collect(Collectors.toList());
        return current.stream()
                .filter(it -> it.loader.equalsIgnoreCase(loader)).findFirst().orElseGet(() -> current.get(0));
    }

    private static List<AssetDownloadDetail> createDownloadDetails(GameMetaData convert, String loader, String assetRoot) {
        return convert.convertFrom.stream().map(it -> getAssetMetaData(it, loader)).map(it -> {
            AssetDownloadDetail adi = new AssetDownloadDetail();
            adi.fileName = it.filename;
            adi.fileUrl = assetRoot + it.filename;
            adi.md5Url = assetRoot + it.md5Filename;
            adi.targetVersion = it.targetVersion;
            return adi;
        }).collect(Collectors.toList());
    }

    private static List<AssetDownloadDetail> createDownloadDetailsFromGit(GameMetaData convert, String loader) {
        try {
            Map<String, String> index = getGitIndex();
            String releaseTag;
            String version = convert.convertFrom.get(0);

            if (loader.toLowerCase().contains("fabric")) {
                releaseTag = index.get(version + "-fabric");
            } else {
                releaseTag = index.get(version);
            }
            if (releaseTag == null) {
                Log.debug("Error getting index: " + version + "-" + loader);
                Log.debug(index.toString());
                throw new Exception();
            }
            String assetRoot = "https://github.com/CFPAOrg/Minecraft-Mod-Language-Package/releases/download/" + releaseTag + "/";

            return createDownloadDetails(convert, loader, assetRoot);
        } catch (Exception ignore) {
            return createDownloadDetails(convert, loader, CFPA_ASSET_ROOT);
        }
    }
}
