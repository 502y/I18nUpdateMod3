package i18nupdatemod.core.v2;

import i18nupdatemod.core.I18nConfig;
import i18nupdatemod.core.ResourcePackConverter;
import i18nupdatemod.entity.GameAssetDetail;
import i18nupdatemod.entity.ModTranslation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ResourcePackV2 {
    private static String getAssetBaseUrl() {
        Properties config = new Properties();
        try (InputStream input = ResourcePackV2.class.getResourceAsStream("/i18n-build.properties")) {
            if (input == null) throw new IOException("Missing bundled build configuration");
            config.load(input);
            String baseUrl = config.getProperty("assetBaseUrl");
            if (baseUrl == null || baseUrl.isEmpty())
                throw new IOException("Missing assetBaseUrl in build configuration");
            return baseUrl;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load bundled build configuration", e);
        }
    }

    public static Path update(String minecraftVersion, List<ModTranslation> mods,

                              Path resourcePackDirectory, Path cacheRoot) throws Exception {
        GameAssetDetail plan = I18nConfig.getAssetDetail(minecraftVersion);
        String baseUrl = getAssetBaseUrl();

        ResourcePackDownloader.Manifest manifest = ResourcePackDownloader.loadManifest(baseUrl, plan.targetVersion);
        Map<String, String> namespaces = ResourcePackDownloader.selectNamespaces(mods, manifest);

        Files.createDirectories(resourcePackDirectory);
        List<Path> sources = ResourcePackDownloader.download(
                plan.targetVersion, namespaces, manifest.blackList, cacheRoot, baseUrl);
        Path icon = ResourcePackDownloader.downloadIcon(baseUrl, plan.targetVersion, cacheRoot);

        Path convertedCache = cacheRoot.resolve(minecraftVersion).resolve(plan.convertedFileName);
        Path convertedOutput = resourcePackDirectory.resolve(plan.convertedFileName);
        new ResourcePackConverter(sources, convertedCache, false)
                .convert(plan.packMetaData, plan.description, new HashSet<>(namespaces.values()), icon);
        Files.copy(convertedCache, convertedOutput, StandardCopyOption.REPLACE_EXISTING);
        return convertedOutput;
    }
}
