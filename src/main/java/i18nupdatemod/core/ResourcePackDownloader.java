package i18nupdatemod.core;

import i18nupdatemod.entity.GameAssetDetail;
import i18nupdatemod.util.FileUtil;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class ResourcePackDownloader {
    public static List<Path> download(List<GameAssetDetail.AssetDownloadDetail> downloads,
                                      Path resourcePackDirectory, Path cacheRoot)
            throws IOException, URISyntaxException, NoSuchAlgorithmException {
        FileUtil.safeCreateDir(resourcePackDirectory);
        List<Path> sourcePaths = new ArrayList<>(downloads.size());
        for (GameAssetDetail.AssetDownloadDetail item : downloads) {
            Path cachePath = cacheRoot.resolve(item.targetVersion).resolve(item.fileName);
            FileUtil.safeCreateDir(cachePath.getParent());
            ResourcePack resourcePack = new ResourcePack(resourcePackDirectory.resolve(item.fileName), cachePath);
            resourcePack.checkUpdate(item.fileUrl, item.md5Url);
            sourcePaths.add(resourcePack.getTmpFilePath());
        }
        return sourcePaths;
    }
}
