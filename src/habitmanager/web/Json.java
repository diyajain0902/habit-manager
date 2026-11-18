package habitmanager.web;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal JSON writer (no external libraries). Values passed to array() and object()
 * must already be valid JSON, e.g. produced by quote(), number() or another call here.
 */
final class Json {

    private Json() {
    }

    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    static String bool(boolean value) {
        return String.valueOf(value);
    }

    static String array(Collection<String> jsonValues) {
        return jsonValues.stream().collect(Collectors.joining(",", "[", "]"));
    }

    static String object(Map<String, String> jsonFields) {
        return jsonFields.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
