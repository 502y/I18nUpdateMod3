package i18nupdatemod.sync;

/**
 * One entry of the {@code GET /rules/:mcVersion} payload. Server never parses
 * this — client uses it to construct CFPA identifiers.
 * <p>
 * {@link #identifier} is either one of the {@link CommonIdentifier} wire
 * values ({@code "author"} / {@code "displayName"}) or a relative file path
 * inside the mod jar whose UTF-8 content becomes the identifier. Callers
 * dispatch via {@link CommonIdentifier#fromWire(String)}.
 */
public class Rule {
    public String namespace;
    public String identifier;

    public enum CommonIdentifier {
        AUTHOR("author"),
        DISPLAY_NAME("displayName");

        private final String wire;

        CommonIdentifier(String wire) {
            this.wire = wire;
        }

        public static CommonIdentifier fromWire(String wire) {
            for (CommonIdentifier value : values()) {
                if (value.wire.equals(wire)) {
                    return value;
                }
            }
            return null;
        }
    }
}
