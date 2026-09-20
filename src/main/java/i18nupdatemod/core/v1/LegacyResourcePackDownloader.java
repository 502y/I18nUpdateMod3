package i18nupdatemod.core.v1;

import i18nupdatemod.entity.GameMetaData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LegacyResourcePackDownloader {
    private LegacyResourcePackDownloader() {
    }

    public static List<Path> download(GameMetaData metadata, String loader,
                                      Path resourcePackDirectory, Path cacheRoot) throws Exception {
        Files.createDirectories(resourcePackDirectory);

        List<AssetDownloadDetail> downloads = LegacyConfig.getLegacyDownloads(metadata, loader);
        List<Path> sourcePaths = new ArrayList<>(downloads.size());
        for (AssetDownloadDetail item : downloads) {
            Path cachePath = cacheRoot.resolve(item.targetVersion).resolve(item.fileName);
            Files.createDirectories(cachePath.getParent());

            ResourcePack resourcePack = new ResourcePack(
                    resourcePackDirectory.resolve(item.fileName), cachePath);
            resourcePack.checkUpdate(item.fileUrl, item.md5Url);
            sourcePaths.add(resourcePack.getTmpFilePath());
        }
        return sourcePaths;
    }
}
