/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/** Parses {@code loader_api_runtime} and decides whether the loader API runtime is on. */
public final class LoaderApiSetting {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    public static final String CONFIG_KEY = "loader_api_runtime";
    public static final String SYSTEM_PROPERTY = "retromod.loaderApiRuntime";
    public static final String CLI_FLAG = "--loader-api-runtime";

    /** The three values the setting accepts. */
    public enum Mode { AUTO, ON, OFF }

    /** Where the deciding value came from, highest precedence first. */
    public enum Source { SYSTEM_PROPERTY, CLI_FLAG, CONFIG, DEFAULT }

    /** The decided value, with enough context to explain it in one log line. */
    public record Effective(boolean on, Mode mode, Source source, String retromodVersion) {

        /** A short reason, such as {@code auto: snapshot build 1.4.0-snapshot.2}. */
        public String reason() {
            String where = switch (source) {
                case SYSTEM_PROPERTY -> "-D" + SYSTEM_PROPERTY;
                case CLI_FLAG -> CLI_FLAG;
                case CONFIG -> "config/retromod/config.json";
                case DEFAULT -> "default";
            };
            if (mode != Mode.AUTO) {
                return (on ? "true" : "false") + " from " + where;
            }
            String build = on ? "snapshot build " : "release build ";
            return "auto: " + build + retromodVersion
                    + (source == Source.DEFAULT ? "" : ", set by " + where);
        }
    }

    private LoaderApiSetting() {}

    /** Parses a raw string value. Null means unset; an unknown value warns and counts as auto. */
    public static Mode parse(String raw, String where) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "auto":
                return Mode.AUTO;
            case "true":
                return Mode.ON;
            case "false":
                return Mode.OFF;
            default:
                LOGGER.warn("{} has an unknown value '{}'. Use auto, true or false. Using auto.",
                        where, raw);
                return Mode.AUTO;
        }
    }

    /** Parses a config value, which may be a JSON boolean or a string. */
    public static Mode parse(JsonPrimitive raw, String where) {
        if (raw == null) return null;
        if (raw.isBoolean()) return raw.getAsBoolean() ? Mode.ON : Mode.OFF;
        if (raw.isString()) return parse(raw.getAsString(), where);
        return parse(raw.toString(), where);
    }

    /**
     * Decides the effective value. Precedence, highest first: the system property, the CLI flag,
     * the config key, then {@code auto}.
     */
    public static Effective resolve(String systemProperty, String cliFlag,
            Optional<JsonPrimitive> config, String retromodVersion) {
        // The first source that is set decides; an unknown value there counts as auto.
        Mode mode = parse(systemProperty, "-D" + SYSTEM_PROPERTY);
        Source source = Source.SYSTEM_PROPERTY;
        if (mode == null) {
            mode = parse(cliFlag, CLI_FLAG);
            source = Source.CLI_FLAG;
        }
        if (mode == null && config != null && config.isPresent()) {
            mode = parse(config.get(), CONFIG_KEY + " in config/retromod/config.json");
            source = Source.CONFIG;
        }
        if (mode == null) {
            mode = Mode.AUTO;
            source = Source.DEFAULT;
        }
        boolean on = switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> RetromodVersion.isSnapshotBuild(retromodVersion);
        };
        return new Effective(on, mode, source, retromodVersion);
    }

    /**
     * Removes {@code --loader-api-runtime=<value>} or {@code --loader-api-runtime <value>} from CLI
     * arguments, so commands that read positional arguments never see it.
     *
     * @return the remaining arguments in their original order, with the flag value in {@code flag[0]}
     */
    public static String[] stripCliFlag(String[] args, String[] flag) {
        java.util.List<String> kept = new java.util.ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith(CLI_FLAG + "=")) {
                flag[0] = arg.substring(CLI_FLAG.length() + 1);
            } else if (arg.equals(CLI_FLAG) && i + 1 < args.length) {
                flag[0] = args[++i];
            } else {
                kept.add(arg);
            }
        }
        return kept.toArray(new String[0]);
    }
}
