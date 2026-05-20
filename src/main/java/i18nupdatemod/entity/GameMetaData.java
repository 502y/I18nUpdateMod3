package i18nupdatemod.entity;

import java.util.List;

public class GameMetaData {
    public String gameVersions;
    public Integer packFormat, minFormat, maxFormat;
    public List<String> convertFrom;

    public boolean useNewFormat() { return minFormat != null && maxFormat != null; }
}
