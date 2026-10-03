package io.github.mikestitt.corbelsflightlog.nt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON NetworkTables 4 control messages need, and no more: objects become
 * {@code Map<String, Object>}, arrays {@code List<Object>}, numbers
 * {@code Long} when they are whole and {@code Double} otherwise, and
 * {@code true}, {@code false} and {@code null} themselves.
 */
final class Json {

    private Json() {
    }

    /** Thrown for text that is not JSON. */
    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    static Object parse(String text) throws ParseException {
        Parser p = new Parser(text);
        p.space();
        Object value = p.value();
        p.space();
        if (p.i != text.length()) throw new ParseException("text after the value at " + p.i);
        return value;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            string(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(d);
            }
        } else if (value instanceof Number) {
            out.append(((Number) value).longValue());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(out, e.getKey());
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() throws ParseException {
            if (i >= s.length()) throw new ParseException("ran out of text");
            return s.charAt(i);
        }

        Object value() throws ParseException {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') return word("true", Boolean.TRUE);
            if (c == 'f') return word("false", Boolean.FALSE);
            if (c == 'n') return word("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new ParseException("unexpected '" + c + "' at " + i);
        }

        Object word(String w, Object value) throws ParseException {
            if (!s.startsWith(w, i)) throw new ParseException("expected " + w + " at " + i);
            i += w.length();
            return value;
        }

        Map<String, Object> object() throws ParseException {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            space();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                space();
                if (peek() != '"') throw new ParseException("expected a key at " + i);
                String key = string();
                space();
                if (peek() != ':') throw new ParseException("expected ':' at " + i);
                i++;
                space();
                map.put(key, value());
                space();
                char c = peek();
                i++;
                if (c == '}') return map;
                if (c != ',') throw new ParseException("expected ',' or '}' at " + (i - 1));
            }
        }

        List<Object> array() throws ParseException {
            List<Object> list = new ArrayList<>();
            i++;
            space();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                space();
                list.add(value());
                space();
                char c = peek();
                i++;
                if (c == ']') return list;
                if (c != ',') throw new ParseException("expected ',' or ']' at " + (i - 1));
            }
        }

        String string() throws ParseException {
            StringBuilder out = new StringBuilder();
            i++;
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new ParseException("short \\u escape");
                        try {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad \\u escape at " + i);
                        }
                        i += 4;
                        break;
                    default:
                        throw new ParseException("bad escape \\" + e + " at " + (i - 1));
                }
            }
        }

        Object number() throws ParseException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            boolean whole = true;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    whole = false;
                    i++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, i);
            try {
                if (whole) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException tooBig) {
                        return Double.parseDouble(text);
                    }
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new ParseException("bad number '" + text + "' at " + start);
            }
        }
    }
}
