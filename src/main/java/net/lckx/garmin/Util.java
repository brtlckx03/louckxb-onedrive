package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Small helpers every page generator uses: reading the parsed Garmin JSON, and writing a file in one step. */
final class Util {
    private Util() {}

    /** The value at a path of keys in parsed JSON (maps), or null when a key is missing. */
    static Object at(Object root, String... path) {
        Object cur = root;
        for (String p : path) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    /** A JSON number, or null when it is missing or not a number. */
    static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    /** A JSON number, or {@code def} when it is missing or not a number. */
    static double numOr(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    /** A JSON value as text, "" when missing. */
    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Writes the file in one step, so a browser refreshing meanwhile never sees half a page. */
    static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
