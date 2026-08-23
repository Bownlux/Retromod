/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodVersion;
import com.retromod.util.JsonSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Curated, user-extensible list of mixin handler methods that fatally crash on
 * the target MC and can't be repaired by remapping.
 *
 * <p>Some mixin failures are recoverable in place (a renamed {@code @At} target
 * gets redirected, a {@code CAPTURE_FAILHARD} gets downgraded to {@code FAILSOFT}).
 * Others can't, such as a MixinExtras {@code @WrapOperation} /
 * {@code @ModifyExpressionValue} handler capturing a {@code @Local} from a vanilla
 * method whose local-variable layout changed between MC versions: the {@code @Local}
 * resolves to the wrong slot, MixinExtras emits an invalid bridge method, and the
 * JVM rejects it with {@code VerifyError: Bad local variable type} at class-load
 * time, before any soft-fail logic can run.
 *
 * <p>Auto-detecting that case isn't safe (the local often still exists at a
 * different slot, so a naive check would strip working mixins), so this is a
 * curated escape hatch like the incompatible-mods list. The bundled
 * {@code /retromod/mixin-blocklist.json} names the known-bad handlers, and
 * {@link MixinCompatibilityTransformer} removes them during transformation. The
 * mod then loads with that one feature inert instead of failing to boot.
 *
 * <p>Users extend or override via {@code config/retromod/mixin-blocklist.json}
 * (same format); entries from both files are merged.
 */
public final class MixinBlocklist {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-MixinBlocklist");

    private static final String BUNDLED_RESOURCE = "/retromod/mixin-blocklist.json";
    private static final Path USER_FILE = Path.of("config/retromod/mixin-blocklist.json");
    private static final long MAX_BLOCKLIST_BYTES = 2L * 1024 * 1024;

    /** Mixin internal name ({@code a/b/C}) to its independently gated block rules. */
    private static volatile Map<String, List<BlockRule>> blocked;

    private record TargetRange(String atLeast, String atMost) {
        boolean includes(String target) {
            if (atLeast == null && atMost == null) return true;
            if (target == null || target.isBlank()) return false;
            if (atLeast != null && RetromodVersion.compareMcVersions(target, atLeast) < 0) {
                return false;
            }
            return atMost == null || RetromodVersion.compareMcVersions(target, atMost) <= 0;
        }
    }

    private record BlockRule(Set<String> methods, boolean fullStrip, TargetRange targets) {
        boolean applies() {
            return targets.includes(RetromodVersion.TARGET_MC_VERSION);
        }
    }

    private MixinBlocklist() {}

    /**
     * Method names to strip for the given mixin, or {@code null} if the mixin is
     * not blocklisted. An empty (but non-null) set means "strip all injectors".
     */
    public static Set<String> methodsToStrip(String mixinInternalName) {
        List<BlockRule> rules = entries().get(mixinInternalName);
        if (rules == null) return null;

        Set<String> methods = new HashSet<>();
        boolean active = false;
        for (BlockRule rule : rules) {
            if (!rule.applies()) continue;
            active = true;
            if (rule.methods().isEmpty()) return Set.of();
            methods.addAll(rule.methods());
        }
        return active ? Set.copyOf(methods) : null;
    }

    /**
     * Whether the entire mixin should be neutralized (not just its handlers).
     * When true, callers should rewrite the {@code @Mixin} annotation to target
     * nothing rather than surgically removing methods.
     */
    public static boolean isFullStrip(String mixinInternalName) {
        List<BlockRule> rules = entries().get(mixinInternalName);
        if (rules == null) return false;
        return rules.stream().anyMatch(rule -> rule.fullStrip() && rule.applies());
    }

    /** Whether the blocklist has any entries (lets callers skip work cheaply). */
    public static boolean isEmpty() {
        return entries().isEmpty();
    }

    private static Map<String, List<BlockRule>> entries() {
        Map<String, List<BlockRule>> b = blocked;
        if (b == null) {
            synchronized (MixinBlocklist.class) {
                b = blocked;
                if (b == null) {
                    b = load();
                    blocked = b;
                }
            }
        }
        return b;
    }

    private static Map<String, List<BlockRule>> load() {
        Map<String, List<BlockRule>> result = new HashMap<>();

        // bundled curated list
        try (InputStream in = MixinBlocklist.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in != null) {
                parseInto(JsonSecurity.readUtf8(in, MAX_BLOCKLIST_BYTES,
                        JsonSecurity.DEFAULT_MAX_DEPTH, "Bundled mixin blocklist"),
                        result, "bundled");
            } else {
                LOGGER.debug("{} not present", BUNDLED_RESOURCE);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read bundled mixin blocklist: {}", e.getMessage());
        }

        // user override / extension
        try {
            if (Files.isRegularFile(USER_FILE)) {
                parseInto(JsonSecurity.readUtf8(USER_FILE, MAX_BLOCKLIST_BYTES,
                        JsonSecurity.DEFAULT_MAX_DEPTH, "User mixin blocklist"),
                        result, "user config");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read user mixin blocklist {}: {}", USER_FILE, e.getMessage());
        }

        if (!result.isEmpty()) {
            long fullStrips = result.values().stream().flatMap(List::stream)
                    .filter(BlockRule::fullStrip).count();
            LOGGER.info("Mixin blocklist active: {} mixin class(es) ({} full-class strip rules)",
                    result.size(), fullStrips);
        }
        return result;
    }

    private static void parseInto(String json, Map<String, List<BlockRule>> out, String source)
            throws java.io.IOException {
        JsonSecurity.validate(json, MAX_BLOCKLIST_BYTES,
                JsonSecurity.DEFAULT_MAX_DEPTH, "Mixin blocklist " + source);
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed == null || !parsed.isJsonObject()) return;
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("blocked") || !root.get("blocked").isJsonArray()) return;

        JsonArray arr = root.getAsJsonArray("blocked");
        int n = 0;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("mixin")) continue;
            // Accept both '.'-separated and '/'-separated class names.
            String mixin = o.get("mixin").getAsString().trim().replace('.', '/');
            if (mixin.isEmpty()) continue;
            Set<String> methods = new HashSet<>();
            if (o.has("methods") && o.get("methods").isJsonArray()) {
                for (JsonElement m : o.getAsJsonArray("methods")) {
                    String name = m.getAsString().trim();
                    if (!name.isEmpty()) methods.add(name);
                }
            }
            boolean fullStrip = o.has("strip")
                    && "class".equalsIgnoreCase(o.get("strip").getAsString().trim());
            String targetAtLeast = optionalString(o, "targetAtLeast");
            String targetAtMost = optionalString(o, "targetAtMost");
            out.computeIfAbsent(mixin, ignored -> new ArrayList<>()).add(new BlockRule(
                    Set.copyOf(methods), fullStrip,
                    new TargetRange(targetAtLeast, targetAtMost)));
            n++;
        }
        LOGGER.debug("Loaded {} mixin blocklist entr(ies) from {}", n, source);
    }

    private static String optionalString(JsonObject object, String key) {
        if (!object.has(key)) return null;
        String value = object.get(key).getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    static void setForTesting(Map<String, Set<String>> entries) {
        setForTesting(entries, Set.of());
    }

    static void setForTesting(Map<String, Set<String>> entries, Set<String> fullStrips) {
        Map<String, List<BlockRule>> rules = new HashMap<>();
        entries.forEach((mixin, methods) -> rules.put(mixin, List.of(new BlockRule(
                Set.copyOf(methods), fullStrips.contains(mixin), new TargetRange(null, null)))));
        blocked = rules;
    }

    static void resetForTesting() { blocked = null; }
}
