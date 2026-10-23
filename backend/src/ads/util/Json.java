package ads.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer for the API layer (doc 08 §1: no external jars).
 *
 * <p>Reading ({@link #parse}) maps objects to {@code LinkedHashMap<String, Object>} (field
 * order kept), arrays to {@code ArrayList<Object>}, strings to {@code String}, integers that
 * fit in a long to {@code Long}, every other number to {@code Double}, {@code true}/{@code
 * false} to {@code Boolean} and {@code null} to {@code null}. It is RFC 8259 strict: no
 * trailing commas, comments, NaN/Infinity, leading zeros, unescaped control characters or
 * duplicate keys, and nothing after the value. Nesting is capped at {@link #MAX_DEPTH} so a
 * hostile body cannot exhaust the stack. Errors are {@link JsonException} with the offset.
 *
 * <p>Writing ({@link #write}) accepts the same types (plus any {@code Number}, arrays of
 * strings and nested maps/lists) and escapes strings per the RFC. Non-finite doubles are
 * refused rather than written as invalid JSON.
 *
 * <p>java.util is allowed here: this is the API layer, not a structure or the matching path
 * (doc 08 §3 rule 7).
 */
public final class Json {
  public static final int MAX_DEPTH = 64;

  private Json() {
  }

  /** A syntax error, with the character offset where it was detected. */
  public static final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int offset;

    JsonException(String message, int offset) {
      super(message + " at offset " + offset);
      this.offset = offset;
    }

    public int offset() {
      return offset;
    }
  }

  // ---------------------------------------------------------------- reading

  public static Object parse(String text) throws JsonException {
    Reader r = new Reader(text);
    r.skipWhitespace();
    Object value = r.value(0);
    r.skipWhitespace();
    if (r.pos != text.length()) {
      throw new JsonException("unexpected content after the JSON value", r.pos);
    }
    return value;
  }

  private static final class Reader {
    private final String s;
    private int pos;

    Reader(String s) {
      this.s = s;
    }

    void skipWhitespace() {
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
        else break;
      }
    }

    Object value(int depth) throws JsonException {
      if (depth > MAX_DEPTH) throw new JsonException("nesting deeper than " + MAX_DEPTH, pos);
      if (pos >= s.length()) throw new JsonException("unexpected end of input", pos);
      char c = s.charAt(pos);
      switch (c) {
        case '{': return object(depth);
        case '[': return array(depth);
        case '"': return string();
        case 't': return literal("true", Boolean.TRUE);
        case 'f': return literal("false", Boolean.FALSE);
        case 'n': return literal("null", null);
        default:
          if (c == '-' || (c >= '0' && c <= '9')) return number();
          throw new JsonException("unexpected character '" + printable(c) + "'", pos);
      }
    }

    private Object literal(String word, Object value) throws JsonException {
      if (!s.startsWith(word, pos)) throw new JsonException("invalid literal", pos);
      pos += word.length();
      return value;
    }

    private Map<String, Object> object(int depth) throws JsonException {
      Map<String, Object> m = new LinkedHashMap<>();
      pos++; // '{'
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return m;
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') throw new JsonException("expected a string key", pos);
        int keyAt = pos;
        String key = string();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        Object v = value(depth + 1);
        if (m.containsKey(key)) throw new JsonException("duplicate key \"" + key + "\"", keyAt);
        m.put(key, v);
        skipWhitespace();
        char c = peek();
        pos++;
        if (c == '}') return m;
        if (c != ',') throw new JsonException("expected ',' or '}'", pos - 1);
      }
    }

    private List<Object> array(int depth) throws JsonException {
      List<Object> a = new ArrayList<>();
      pos++; // '['
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return a;
      }
      while (true) {
        skipWhitespace();
        a.add(value(depth + 1));
        skipWhitespace();
        char c = peek();
        pos++;
        if (c == ']') return a;
        if (c != ',') throw new JsonException("expected ',' or ']'", pos - 1);
      }
    }

    private String string() throws JsonException {
      pos++; // opening quote
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) throw new JsonException("unterminated string", pos);
        char c = s.charAt(pos++);
        if (c == '"') return sb.toString();
        if (c < 0x20) throw new JsonException("unescaped control character in string", pos - 1);
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= s.length()) throw new JsonException("unterminated escape", pos);
        char e = s.charAt(pos++);
        switch (e) {
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          case '/': sb.append('/'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u': {
            if (pos + 4 > s.length()) throw new JsonException("short \\u escape", pos);
            int code = 0;
            for (int i = 0; i < 4; i++) {
              int d = Character.digit(s.charAt(pos + i), 16);
              if (d < 0) throw new JsonException("bad hex digit in \\u escape", pos + i);
              code = code * 16 + d;
            }
            pos += 4;
            sb.append((char) code);
            break;
          }
          default:
            throw new JsonException("invalid escape \\" + printable(e), pos - 1);
        }
      }
    }

    private Object number() throws JsonException {
      int start = pos;
      if (peek() == '-') pos++;
      if (peek() == '0') {
        pos++;
      } else if (peek() >= '1' && peek() <= '9') {
        while (peek() >= '0' && peek() <= '9') pos++;
      } else {
        throw new JsonException("invalid number", pos);
      }
      boolean integral = true;
      if (peek() == '.') {
        integral = false;
        pos++;
        if (!(peek() >= '0' && peek() <= '9')) throw new JsonException("digit expected", pos);
        while (peek() >= '0' && peek() <= '9') pos++;
      }
      if (peek() == 'e' || peek() == 'E') {
        integral = false;
        pos++;
        if (peek() == '+' || peek() == '-') pos++;
        if (!(peek() >= '0' && peek() <= '9')) throw new JsonException("digit expected", pos);
        while (peek() >= '0' && peek() <= '9') pos++;
      }
      String text = s.substring(start, pos);
      if (integral) {
        try {
          return Long.parseLong(text);
        } catch (NumberFormatException tooBig) {
          // falls through to a double, like JavaScript
        }
      }
      double d = Double.parseDouble(text);
      if (Double.isInfinite(d)) throw new JsonException("number out of range", start);
      return d;
    }

    private char peek() {
      return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private void expect(char c) throws JsonException {
      if (peek() != c) throw new JsonException("expected '" + c + "'", pos);
      pos++;
    }

    private static String printable(char c) {
      return c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c);
    }
  }

  // ---------------------------------------------------------------- writing

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    write(sb, value);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String) {
      quote(sb, (String) v);
    } else if (v instanceof Boolean) {
      sb.append(v.toString());
    } else if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException("JSON cannot represent " + d);
      }
      sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
    } else if (v instanceof Number) {
      sb.append(v.toString());
    } else if (v instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
        if (!first) sb.append(',');
        first = false;
        quote(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (v instanceof Iterable) {
      sb.append('[');
      boolean first = true;
      for (Object item : (Iterable<?>) v) {
        if (!first) sb.append(',');
        first = false;
        write(sb, item);
      }
      sb.append(']');
    } else if (v instanceof Object[]) {
      sb.append('[');
      Object[] a = (Object[]) v;
      for (int i = 0; i < a.length; i++) {
        if (i > 0) sb.append(',');
        write(sb, a[i]);
      }
      sb.append(']');
    } else {
      throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        default:
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
