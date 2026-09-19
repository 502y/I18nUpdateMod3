package i18nupdatemod.entity;

import java.util.List;

public class GameAssetDetail {
    public List<AssetDownloadDetail> downloads;
    public String convertedFileName;
    public GameMetaData packMetaData;
    public String description;

    public static class AssetDownloadDetail {
        public String fileName;
        public String fileUrl;
        public String md5Url;
        public String targetVersion;
    }
}
