package i18nupdatemod.entity;

import java.util.ArrayList;

public class ModMetaData {
    public String domain;
    public String displayName;
    public ArrayList<String> author;

    public ModMetaData(String domain, ArrayList<String> author) {
        this(domain, null, author);
    }

    public ModMetaData(String domain, String displayName, ArrayList<String> author) {
        this.domain = domain;
        this.displayName = displayName;
        this.author = author;
    }
}
