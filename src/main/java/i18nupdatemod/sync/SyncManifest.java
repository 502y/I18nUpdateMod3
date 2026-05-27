package i18nupdatemod.sync;

import java.util.Collections;
import java.util.List;

/**
 * Decoded from the {@code X-Manifest} response header (base64-decoded JSON).
 * Tells the client which CFPA namespaces are inside the zstd-compressed tar
 * body of a {@code POST /sync} response, and which to delete locally.
 */
public class SyncManifest {
    public List<String> added;
    public List<String> updated;
    public List<String> deleted;

    public List<String> added() {
        return added == null ? Collections.emptyList() : added;
    }

    public List<String> updated() {
        return updated == null ? Collections.emptyList() : updated;
    }

    public List<String> deleted() {
        return deleted == null ? Collections.emptyList() : deleted;
    }

    public boolean isEmpty() {
        return added().isEmpty() && updated().isEmpty() && deleted().isEmpty();
    }
}
