/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads and writes the SNBT in an old {@code nbt} or {@code tag} item field, keeping each NBT type. */
final class LegacyItemNbt {

    // The type matters: the components that replaced item NBT in 1.20.5 keep the mod's own keys as
    // NBT, and a mod reads them back by type, so an int stored where a byte was would read as 0.

    /** One NBT value. */
    sealed interface Tag permits Compound, ListTag, ArrayTag, Num, Str {
    }

    /** A compound, in the order the data wrote it. */
    record Compound(Map<String, Tag> entries) implements Tag {
        Compound {
            entries = new LinkedHashMap<>(entries);
        }

        Tag get(String key) {
            return entries.get(key);
        }

        String string(String key) {
            return entries.get(key) instanceof Str str ? str.value() : null;
        }
    }

    record ListTag(List<Tag> items) implements Tag {
        ListTag {
            items = List.copyOf(items);
        }
    }

    /** A typed array: {@code B} for bytes, {@code I} for ints, {@code L} for longs. */
    record ArrayTag(char kind, List<Num> values) implements Tag {
        ArrayTag {
            values = List.copyOf(values);
        }
    }

    /** A number of {@code type} b, s, i, l, f or d, kept as unsuffixed digits so nothing rounds. */
    record Num(char type, String text) implements Tag {
        boolean integral() {
            return type == 'b' || type == 's' || type == 'i' || type == 'l';
        }

        /** The value as a long, or null when it is not an integral number. */
        Long asLong() {
            if (!integral()) return null;
            try {
                return Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
    }

    record Str(String value) implements Tag {
    }

    // Text longer or deeper than this is not read.
    private static final int MAX_DEPTH = 64;
    private static final int MAX_LENGTH = 256 * 1024;

    // The old parser's number shapes. A number with a point and no suffix is a double, and a whole
    // number with no suffix is an int.
    private static final Pattern DOUBLE_NO_SUFFIX = Pattern.compile(
            "[-+]?(?:[0-9]+[.]|[0-9]*[.][0-9]+)(?:e[-+]?[0-9]+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOUBLE = Pattern.compile(
            "[-+]?(?:[0-9]+[.]?|[0-9]*[.][0-9]+)(?:e[-+]?[0-9]+)?d", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOAT = Pattern.compile(
            "[-+]?(?:[0-9]+[.]?|[0-9]*[.][0-9]+)(?:e[-+]?[0-9]+)?f", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTEGRAL = Pattern.compile("[-+]?(?:0|[1-9][0-9]*)([bslBSL]?)");
    private static final Pattern UNQUOTED = Pattern.compile("[A-Za-z0-9._+-]+");

    private LegacyItemNbt() {
    }

    /** Reads a compound written as SNBT, or returns null when it is not one the old parser read. */
    static Compound parseCompound(String snbt) {
        // Anything the old parser would have rejected comes back as null, so the caller leaves the
        // file as it was rather than guess.
        if (snbt == null || snbt.length() > MAX_LENGTH) return null;
        try {
            Reader reader = new Reader(snbt);
            Tag tag = reader.value(0);
            reader.skipWhitespace();
            if (!reader.atEnd() || !(tag instanceof Compound compound)) return null;
            return compound;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Reads an {@code nbt} field the way Forge did: a string is SNBT, and so is an object's JSON text. */
    static Compound parseField(JsonElement field) {
        if (field instanceof JsonPrimitive primitive && primitive.isString()) {
            return parseCompound(primitive.getAsString());
        }
        // An object is read as its own JSON text, so {"Damage": 5} is an int and 5.0 a double.
        if (field instanceof JsonObject object) return parseCompound(object.toString());
        return null;
    }

    /** The SNBT text of a value, typed so that every SNBT parser since 1.13 reads it back the same. */
    static String write(Tag tag) {
        StringBuilder out = new StringBuilder();
        write(tag, out);
        return out.toString();
    }

    private static void write(Tag tag, StringBuilder out) {
        if (tag instanceof Compound compound) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Tag> entry : compound.entries().entrySet()) {
                if (!first) out.append(',');
                first = false;
                // A key is quoted unless it is only letters, digits and the marks . _ + -.
                String key = entry.getKey();
                if (UNQUOTED.matcher(key).matches()) out.append(key);
                else quote(key, out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (tag instanceof ListTag list) {
            out.append('[');
            for (int i = 0; i < list.items().size(); i++) {
                if (i > 0) out.append(',');
                write(list.items().get(i), out);
            }
            out.append(']');
        } else if (tag instanceof ArrayTag array) {
            // A typed array starts with its kind, as in [I;1,2,3].
            out.append('[').append(array.kind()).append(';');
            for (int i = 0; i < array.values().size(); i++) {
                if (i > 0) out.append(',');
                write(array.values().get(i), out);
            }
            out.append(']');
        } else if (tag instanceof Num num) {
            // Every number gets its type suffix (an int needs none) and every string is quoted, a form
            // the old parser and the one 1.21.5 rewrote both read the same way.
            out.append(numberText(num));
            switch (num.type()) {
                case 'b' -> out.append('b');
                case 's' -> out.append('s');
                case 'l' -> out.append('L');
                case 'f' -> out.append('f');
                case 'd' -> out.append('d');
                default -> { }
            }
        } else if (tag instanceof Str str) {
            quote(str.value(), out);
        }
    }

    /** Digits both parsers accept: no leading plus, and digits on both sides of the point. */
    private static String numberText(Num num) {
        String text = num.text().startsWith("+") ? num.text().substring(1) : num.text();
        if (num.integral()) return text;
        // .5 becomes 0.5 and 5. becomes 5.0, keeping the sign and any exponent.
        String sign = text.startsWith("-") ? "-" : "";
        String digits = sign.isEmpty() ? text : text.substring(1);
        if (digits.startsWith(".")) digits = "0" + digits;
        int exponent = digits.toLowerCase(java.util.Locale.ROOT).indexOf('e');
        String mantissa = exponent < 0 ? digits : digits.substring(0, exponent);
        String rest = exponent < 0 ? "" : digits.substring(exponent);
        if (mantissa.endsWith(".")) mantissa = mantissa + "0";
        return sign + mantissa + rest;
    }

    /** Writes {@code value} in double quotes, with quotes and backslashes escaped. */
    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        out.append('"');
    }

    /** The value as plain JSON numbers and strings, for a component whose codec reads JSON. */
    static JsonElement toJson(Tag tag) {
        // The NBT type is lost, so this is only for values whose type the component fixes itself,
        // such as a damage or a color.
        if (tag instanceof Compound compound) {
            JsonObject object = new JsonObject();
            for (Map.Entry<String, Tag> entry : compound.entries().entrySet()) {
                object.add(entry.getKey(), toJson(entry.getValue()));
            }
            return object;
        }
        if (tag instanceof ListTag list) {
            JsonArray array = new JsonArray();
            for (Tag item : list.items()) array.add(toJson(item));
            return array;
        }
        if (tag instanceof ArrayTag array) {
            JsonArray out = new JsonArray();
            for (Num value : array.values()) out.add(toJson(value));
            return out;
        }
        if (tag instanceof Num num) return new JsonPrimitive(new BigDecimal(numberText(num)));
        return new JsonPrimitive(((Str) tag).value());
    }

    /** Whether the JSON form of this value reads back with the same NBT types. */
    static boolean jsonKeepsTypes(Tag tag) {
        // JSON given where a compound is expected narrows each number to the smallest type that holds
        // it, so an int 5 would come back a byte. Only 1.20.5 and 1.20.6 read custom data solely as
        // JSON and need this check.
        if (tag instanceof Compound compound) {
            for (Tag value : compound.entries().values()) {
                if (!jsonKeepsTypes(value)) return false;
            }
            return true;
        }
        // A number in a list, or any typed array, does not keep its type through JSON.
        if (tag instanceof ListTag list) {
            for (Tag item : list.items()) {
                if (item instanceof Num || !jsonKeepsTypes(item)) return false;
            }
            return true;
        }
        if (tag instanceof ArrayTag) return false;
        if (tag instanceof Num num) return narrowedType(num) == num.type();
        return true;
    }

    /** The type a JSON number narrows to: b, s, i or l when it is whole, f or d when it is not. */
    private static char narrowedType(Num num) {
        BigDecimal value;
        try {
            value = new BigDecimal(numberText(num));
        } catch (NumberFormatException unreadable) {
            return '?';
        }
        try {
            long whole = value.longValueExact();
            if ((byte) whole == whole) return 'b';
            if ((short) whole == whole) return 's';
            if ((int) whole == whole) return 'i';
            return 'l';
        } catch (ArithmeticException fractional) {
            double d = value.doubleValue();
            return (float) d == d ? 'f' : 'd';
        }
    }

    /** A recursive-descent reader with the 1.13 to 1.20.4 SNBT grammar and typing rules. */
    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return at >= text.length();
        }

        void skipWhitespace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private char peek() {
            if (atEnd()) throw new IllegalArgumentException("unexpected end");
            return text.charAt(at);
        }

        private void expect(char c) {
            skipWhitespace();
            if (peek() != c) throw new IllegalArgumentException("expected " + c);
            at++;
        }

        Tag value(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("too deep");
            skipWhitespace();
            // The first character picks compound, list or quoted string. Anything else is an
            // unquoted token, typed as the old parser did.
            char c = peek();
            if (c == '{') return compound(depth);
            if (c == '[') return listOrArray(depth);
            if (c == '"' || c == '\'') return new Str(quoted());
            return typed(unquoted());
        }

        private Compound compound(int depth) {
            expect('{');
            Map<String, Tag> entries = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                at++;
                return new Compound(entries);
            }
            // Read key: value pairs up to the closing brace. A key may be quoted.
            while (true) {
                skipWhitespace();
                String key = peek() == '"' || peek() == '\'' ? quoted() : unquoted();
                expect(':');
                entries.put(key, value(depth + 1));
                skipWhitespace();
                char next = peek();
                at++;
                if (next == '}') return new Compound(entries);
                if (next != ',') throw new IllegalArgumentException("expected , or }");
            }
        }

        private Tag listOrArray(int depth) {
            expect('[');
            skipWhitespace();
            // [B;, [I; and [L; start a typed array. Anything else is a list.
            if (at + 1 < text.length() && text.charAt(at + 1) == ';'
                    && "BIL".indexOf(text.charAt(at)) >= 0) {
                char kind = text.charAt(at);
                at += 2;
                return array(kind);
            }
            List<Tag> items = new ArrayList<>();
            if (peek() == ']') {
                at++;
                return new ListTag(items);
            }
            while (true) {
                items.add(value(depth + 1));
                skipWhitespace();
                char next = peek();
                at++;
                if (next == ']') return new ListTag(items);
                if (next != ',') throw new IllegalArgumentException("expected , or ]");
            }
        }

        private ArrayTag array(char kind) {
            // Each element must have the array's own type: b in [B;, i in [I;, l in [L;.
            char expected = Character.toLowerCase(kind) == 'i' ? 'i' : Character.toLowerCase(kind);
            List<Num> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                at++;
                return new ArrayTag(kind, values);
            }
            while (true) {
                skipWhitespace();
                if (!(typed(unquoted()) instanceof Num num) || num.type() != expected) {
                    throw new IllegalArgumentException("array element of the wrong type");
                }
                values.add(num);
                skipWhitespace();
                char next = peek();
                at++;
                if (next == ']') return new ArrayTag(kind, values);
                if (next != ',') throw new IllegalArgumentException("expected , or ]");
            }
        }

        private String quoted() {
            char quote = peek();
            at++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                at++;
                if (c == quote) return out.toString();
                // Only a backslash or a quote can follow a backslash.
                if (c == '\\') {
                    char escaped = peek();
                    if (escaped != '\\' && escaped != '"' && escaped != '\'') {
                        throw new IllegalArgumentException("invalid escape");
                    }
                    at++;
                    out.append(escaped);
                } else {
                    out.append(c);
                }
            }
        }

        private String unquoted() {
            // An unquoted token is ASCII letters and digits, plus _ - . and +.
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (!(Character.isLetterOrDigit(c) && c < 128) && c != '_' && c != '-'
                        && c != '.' && c != '+') {
                    break;
                }
                at++;
            }
            if (start == at) throw new IllegalArgumentException("expected a value");
            return text.substring(start, at);
        }

        /** Types an unquoted token as the old parser did; a number that overflows stays text. */
        private static Tag typed(String token) {
            // Try a float or double suffix first, then a bare decimal, then a whole number with an
            // optional b, s or L suffix.
            try {
                if (FLOAT.matcher(token).matches()) {
                    String digits = token.substring(0, token.length() - 1);
                    Float.parseFloat(digits);
                    return new Num('f', digits);
                }
                if (DOUBLE.matcher(token).matches()) {
                    String digits = token.substring(0, token.length() - 1);
                    Double.parseDouble(digits);
                    return new Num('d', digits);
                }
                if (DOUBLE_NO_SUFFIX.matcher(token).matches()) {
                    Double.parseDouble(token);
                    return new Num('d', token);
                }
                var integral = INTEGRAL.matcher(token);
                if (integral.matches()) {
                    String suffix = integral.group(1).toLowerCase(java.util.Locale.ROOT);
                    String digits = suffix.isEmpty() ? token : token.substring(0, token.length() - 1);
                    long value = Long.parseLong(digits.startsWith("+") ? digits.substring(1) : digits);
                    char type = suffix.isEmpty() ? 'i' : suffix.charAt(0);
                    // A number out of its type's range stays text, as the old parser kept it.
                    boolean fits = switch (type) {
                        case 'b' -> value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
                        case 's' -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
                        case 'i' -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
                        default -> true;
                    };
                    if (fits) return new Num(type, digits);
                }
            } catch (NumberFormatException overflow) {
                // The old parser kept a number it could not hold as a string.
            }
            // true and false are bytes.
            if (token.equalsIgnoreCase("true")) return new Num('b', "1");
            if (token.equalsIgnoreCase("false")) return new Num('b', "0");
            return new Str(token);
        }
    }
}
