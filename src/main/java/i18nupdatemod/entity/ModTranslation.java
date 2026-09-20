package i18nupdatemod.entity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata for one resource namespace supplied by a mod.
 *
 * <p>The source is the original mod archive. For nested Fabric or Forge
 * jars, {@code nestedJars} contains the entry names from the outer archive to
 * the innermost archive.</p>
 */
public class ModTranslation {
    public String namespace;
    public List<String> authors;
    public String displayName;

    public final Path source;
    public final List<String> nestedJars;

    public ModTranslation(String namespace, List<String> authors, String displayName,
                          Path source, List<String> nestedJars) {
        this.namespace = namespace;
        this.authors = authors == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(authors));
        this.displayName = displayName;
        this.source = source;
        this.nestedJars = nestedJars == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(nestedJars));
    }
}
