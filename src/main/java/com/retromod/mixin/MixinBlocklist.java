/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * Mixin internal name ({@code a/b/C}) → set of handler method names to strip.
     * An empty set means "strip every injector handler on the class".
     */
    private static volatile Map<String, Set<String>> blocked;

    /**
     * Mixin internal names whose <b>entire</b> mixin should be neutralized, not
     * just handler methods. For cases handler-stripping can't fix: mixins that add
     * an interface to a target class (True Darkness's
     * {@code MixinLightTexture implements LightmapAccess}, #68) or have a critical
     * {@code @Inject} failure where the surrounding mixin is interdependent. Here
     * {@link MixinCompatibilityTransformer} rewrites the {@code @Mixin} annotation
     * to point at a non-existent target so the mixin framework skips the whole
     * class, the same "target not found" path MC's own moved inner classes take.
     *
     * <p>Set via {@code "strip": "class"} on a blocklist entry. Defaults to the
     * handler-strip behavior when absent.
     */
    private static volatile Set<String> fullStrip;

    private MixinBlocklist() {}

    /**
     * Method names to strip for the given mixin, or {@code null} if the mixin is
     * not blocklisted. An empty (but non-null) set means "strip all injectors".
     */
    public static Set<String> methodsToStrip(String mixinInternalName) {
        return entries().get(mixinInternalName);
    }

    /**
     * Whether the entire mixin should be neutralized (not just its handlers).
     * When true, callers should rewrite the {@code @Mixin} annotation to target
     * nothing rather than surgically removing methods.
     */
    public static boolean isFullStrip(String mixinInternalName) {
        entries(); // ensure loaded
        Set<String> fs = fullStrip;
        return fs != null && fs.contains(mixinInternalName);
    }

    /** Whether the blocklist has any entries (lets callers skip work cheaply). */
    public static boolean isEmpty() {
        return entries().isEmpty();
    }

    private static Map<String, Set<String>> entries() {
        Map<String, Set<String>> b = blocked;
        if (b == null) {
            synchronized (MixinBlocklist.class) {
                b = blocked;
                if (b == null) {
                    Set<String> fs = new HashSet<>();
                    b = load(fs);
                    fullStrip = fs;
                    blocked = b;
                }
            }
        }
        return b;
    }

    private static Map<String, Set<String>> load(Set<String> fullStripOut) {
        Map<String, Set<String>> result = new HashMap<>();

        // bundled curated list
        try (InputStream in = MixinBlocklist.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in != null) {
                parseInto(new InputStreamReader(in, StandardCharsets.UTF_8), result, fullStripOut, "bundled");
            } else {
                LOGGER.debug("{} not present", BUNDLED_RESOURCE);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read bundled mixin blocklist: {}", e.getMessage());
        }

        // user override / extension
        try {
            if (Files.isRegularFile(USER_FILE)) {
                try (Reader r = Files.newBufferedReader(USER_FILE, StandardCharsets.UTF_8)) {
                    parseInto(r, result, fullStripOut, "user config");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read user mixin blocklist {}: {}", USER_FILE, e.getMessage());
        }

        if (!result.isEmpty()) {
            LOGGER.info("Mixin blocklist active: {} mixin class(es) ({} full-class strips)",
                    result.size(), fullStripOut.size());
        }
        return result;
    }

    private static void parseInto(Reader reader, Map<String, Set<String>> out,
                                  Set<String> fullStripOut, String source) {
        JsonElement parsed = JsonParser.parseReader(reader);
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
            Set<String> methods = out.computeIfAbsent(mixin, k -> new HashSet<>());
            if (o.has("methods") && o.get("methods").isJsonArray()) {
                for (JsonElement m : o.getAsJsonArray("methods")) {
                    String name = m.getAsString().trim();
                    if (!name.isEmpty()) methods.add(name);
                }
            }
            // "strip": "class" → neutralize the whole mixin, not just handlers.
            if (o.has("strip") && "class".equalsIgnoreCase(o.get("strip").getAsString().trim())) {
                fullStripOut.add(mixin);
            }
            n++;
        }
        LOGGER.debug("Loaded {} mixin blocklist entr(ies) from {}", n, source);
    }

    static void setForTesting(Map<String, Set<String>> e) { blocked = e; }
    static void setForTesting(Map<String, Set<String>> e, Set<String> fs) { blocked = e; fullStrip = fs; }
    static void resetForTesting() { blocked = null; fullStrip = null; }
}
