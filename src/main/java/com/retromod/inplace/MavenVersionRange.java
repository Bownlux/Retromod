/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.inplace;

import com.retromod.core.RetromodVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the Maven-style version ranges Forge and NeoForge mods declare, such as
 * {@code [1.19.2,1.20)}, {@code [1.20.1]}, or {@code [1.20,1.20.2),[1.21,)}.
 *
 * <p>Only one question is answered: does a range certainly contain a version. Anything this
 * class cannot read gives "no", because the caller uses a "no" to keep a jar the loader would
 * refuse away from the loader, and a wrong "yes" would let that jar stop the launch before
 * Retromod runs. Written from the documented range syntax, not from loader code.
 */
final class MavenVersionRange {

    private MavenVersionRange() {}

    /** One bounded interval of a range. A null bound is open. */
    private record Interval(String lower, boolean lowerInclusive, String upper,
            boolean upperInclusive) {

        boolean contains(String version) {
            if (lower != null) {
                int compared = RetromodVersion.compareMcVersions(version, lower);
                if (compared < 0 || (compared == 0 && !lowerInclusive)) return false;
            }
            if (upper != null) {
                int compared = RetromodVersion.compareMcVersions(version, upper);
                if (compared > 0 || (compared == 0 && !upperInclusive)) return false;
            }
            return true;
        }
    }

    /**
     * True only when {@code spec} is readable and contains {@code version}. A bare version is
     * accepted only when it equals {@code version}, and {@code *} accepts everything.
     */
    static boolean certainlyContains(String spec, String version) {
        if (spec == null || version == null || version.isBlank()) return false;
        String text = spec.replaceAll("\\s+", "");
        if (text.equals("*")) return true;
        if (!text.startsWith("[") && !text.startsWith("(")) {
            return isVersion(text) && RetromodVersion.compareMcVersions(text, version) == 0;
        }
        Optional<List<Interval>> intervals = parse(text);
        if (intervals.isEmpty()) return false;
        for (Interval interval : intervals.get()) {
            if (interval.contains(version)) return true;
        }
        return false;
    }

    /**
     * True when {@code spec} is readable and accepts every version from some point on, such
     * as {@code [43,)} or {@code *}. For a version Retromod cannot see, such as an installed
     * library's or NeoForge's FML version, that is the most it can know: those versions only
     * grow, while an upper bound may already be behind them.
     */
    static boolean openAbove(String spec) {
        if (spec == null) return false;
        String text = spec.replaceAll("\\s+", "");
        if (text.equals("*")) return true;
        if (!text.startsWith("[") && !text.startsWith("(")) return false;
        Optional<List<Interval>> intervals = parse(text);
        if (intervals.isEmpty()) return false;
        for (Interval interval : intervals.get()) {
            if (interval.upper() == null) return true;
        }
        return false;
    }

    private static Optional<List<Interval>> parse(String text) {
        List<Interval> intervals = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            char open = text.charAt(position);
            if (open != '[' && open != '(') return Optional.empty();
            int close = indexOfClose(text, position + 1);
            if (close < 0) return Optional.empty();
            String body = text.substring(position + 1, close);
            boolean upperInclusive = text.charAt(close) == ']';
            Optional<Interval> interval = interval(body, open == '[', upperInclusive);
            if (interval.isEmpty()) return Optional.empty();
            intervals.add(interval.get());
            position = close + 1;
            if (position < text.length()) {
                if (text.charAt(position) != ',') return Optional.empty();
                position++;
            }
        }
        return intervals.isEmpty() ? Optional.empty() : Optional.of(intervals);
    }

    private static int indexOfClose(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ']' || c == ')') return i;
            if (c == '[' || c == '(') return -1;
        }
        return -1;
    }

    private static Optional<Interval> interval(String body, boolean lowerInclusive,
            boolean upperInclusive) {
        int comma = body.indexOf(',');
        if (comma < 0) {
            // [1.20.1] pins one version; (1.20.1) is not a valid range.
            if (!lowerInclusive || !upperInclusive || !isVersion(body)) return Optional.empty();
            return Optional.of(new Interval(body, true, body, true));
        }
        if (body.indexOf(',', comma + 1) >= 0) return Optional.empty();
        String lower = body.substring(0, comma);
        String upper = body.substring(comma + 1);
        if ((!lower.isEmpty() && !isVersion(lower)) || (!upper.isEmpty() && !isVersion(upper))) {
            return Optional.empty();
        }
        return Optional.of(new Interval(lower.isEmpty() ? null : lower, lowerInclusive,
                upper.isEmpty() ? null : upper, upperInclusive));
    }

    /**
     * A version this class can compare: it starts with a number, like {@code 1.20.1}. Each
     * number stays within nine digits, because the comparison reads them as {@code int}; a
     * date-style version such as {@code 20230615000000} is unreadable, which means "no".
     */
    static boolean isVersion(String text) {
        return text != null && text.length() <= 64
                && text.matches("\\d{1,9}(\\.\\d{1,9})*([-+][0-9A-Za-z.+-]*)?");
    }
}
