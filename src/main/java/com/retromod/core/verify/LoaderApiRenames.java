/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.verify;

import com.retromod.util.RetromodLog;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hand-curated rename table for high-impact mod-loader API changes that
 * {@link McSymbolIndex} can't cover. Loader APIs (Fabric's ~40 independently
 * versioned modules, etc.) have no single indexable artifact per MC version,
 * so this table is maintained by hand and scoped to the renames that break
 * mods in practice.
 *
 * <p>The JSON at {@code /retromod/loader-api-renames.json} has one section per
 * loader ({@code "fabric"}, {@code "neoforge"}, {@code "forge"}), each with:
 * <ul>
 *   <li>{@code renamed_classes}: old internal-name to new internal-name</li>
 *   <li>{@code renamed_methods}: {@code "owner#name desc"} to {@code "newOwner#newName newDesc"}</li>
 *   <li>{@code removed_classes}: internal-names deleted with no replacement</li>
 * </ul>
 *
 * <p>Instances are immutable after construction and safe to share across threads.
 */
public final class LoaderApiRenames {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApiRenames");

    private static final String RESOURCE_PATH = "/retromod/loader-api-renames.json";

    // kept as strings so this class has no dependency on the rest of Retromod
    private static final String[] LOADER_KEYS = {"fabric", "neoforge", "forge"};

    private static volatile LoaderApiRenames instance;

    private final Map<String, String> renamedClasses;
    private final Map<String, String> renamedMethods;
    private final Set<String> removedClasses;

    /** Returned when the JSON fails to load: every lookup misses. */
    private static final LoaderApiRenames EMPTY = new LoaderApiRenames(
            Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet());

    private LoaderApiRenames(Map<String, String> renamedClasses,
                             Map<String, String> renamedMethods,
                             Set<String> removedClasses) {
        this.renamedClasses = renamedClasses;
        this.renamedMethods = renamedMethods;
        this.removedClasses = removedClasses;
    }

    /**
     * Process-wide loader rename table, loaded from the bundled JSON on first
     * call and cached. A failed load yields an empty table rather than throwing,
     * so a bad data file doesn't break transformation.
     */
    public static LoaderApiRenames getInstance() {
        LoaderApiRenames local = instance;
        if (local != null) return local;
        synchronized (LoaderApiRenames.class) {
            if (instance != null) return instance;
            instance = loadFromResource();
            return instance;
        }
    }

    /** @return the new internal name if {@code oldInternalName} was renamed, else {@code null} */
    public String getClassRename(String oldInternalName) {
        return renamedClasses.get(oldInternalName);
    }

    /**
     * @return key {@code "newOwner#newName newDesc"} if the member was renamed,
     *         else {@code null}. Callers parse the key to extract components.
     */
    public String getMethodRename(String owner, String name, String descriptor) {
        return renamedMethods.get(memberKey(owner, name, descriptor));
    }

    /** @return {@code true} if the class was deleted with no replacement (a broken reference) */
    public boolean isRemoved(String internalName) {
        return removedClasses.contains(internalName);
    }

    public int size() {
        return renamedClasses.size() + renamedMethods.size() + removedClasses.size();
    }

    /** Canonical member-key format for method rename lookups. Package-private for tests. */
    static String memberKey(String owner, String name, String descriptor) {
        return owner + "#" + name + " " + descriptor;
    }

    private static LoaderApiRenames loadFromResource() {
        try (InputStream in = LoaderApiRenames.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warn("Loader API rename table not found at {} - loader-API checks disabled",
                        RESOURCE_PATH);
                return EMPTY;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return buildFromJson(root);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read loader API rename table: {}", e.getMessage());
            return EMPTY;
        } catch (Exception e) {
            LOGGER.warn("Loader API rename table is malformed: {}", e.getMessage());
            return EMPTY;
        }
    }

    private static LoaderApiRenames buildFromJson(JsonObject root) {
        Map<String, String> classes = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        Set<String> removed = new HashSet<>();

        // The "forge" section holds Forge to NeoForge migration renames, correct
        // only on a NeoForge runtime. On Forge they rewrite mods to NeoForge
        // classes that don't exist, crashing every mod's constructor.
        boolean skipForgeSection;
        try {
            skipForgeSection = !com.retromod.util.McReflect.isNeoForge();
        } catch (Throwable t) {
            // McReflect absent on the CLI / in tests: include the section there
            skipForgeSection = false;
        }

        // Per-loader try-catch so one malformed section doesn't wipe the others.
        for (String loader : LOADER_KEYS) {
            if (!root.has(loader)) continue;
            if ("forge".equals(loader) && skipForgeSection) {
                LOGGER.debug("Skipping loader-api 'forge' section (runtime is not NeoForge - Forge→NeoForge migrations don't apply)");
                continue;
            }
            try {
                JsonObject loaderSection = root.getAsJsonObject(loader);

                if (loaderSection.has("renamed_classes")) {
                    JsonObject classMap = loaderSection.getAsJsonObject("renamed_classes");
                    for (Map.Entry<String, ?> e : classMap.entrySet()) {
                        String oldName = e.getKey();
                        String newName = classMap.get(oldName).getAsString();
                        // first-wins on conflict: deterministic regardless of iteration order
                        classes.putIfAbsent(oldName, newName);
                    }
                }

                if (loaderSection.has("renamed_methods")) {
                    JsonObject methodMap = loaderSection.getAsJsonObject("renamed_methods");
                    for (Map.Entry<String, ?> e : methodMap.entrySet()) {
                        methods.putIfAbsent(e.getKey(), methodMap.get(e.getKey()).getAsString());
                    }
                }

                if (loaderSection.has("removed_classes")) {
                    loaderSection.getAsJsonArray("removed_classes")
                            .forEach(el -> removed.add(el.getAsString()));
                }
            } catch (Exception sectionError) {
                LOGGER.warn("Loader-API rename section '{}' is malformed, skipping it: {}",
                        loader, sectionError.getMessage());
            }
        }

        LOGGER.debug(RetromodLog.DETAIL, "Loaded loader-API rename table: {} classes, {} methods, {} removed",
                classes.size(), methods.size(), removed.size());

        return new LoaderApiRenames(
                Collections.unmodifiableMap(classes),
                Collections.unmodifiableMap(methods),
                Collections.unmodifiableSet(removed));
    }

    /**
     * Test-only: build an instance from explicit maps, bypassing the JSON load.
     * Useful for unit tests that want a controlled rename table.
     */
    public static LoaderApiRenames forTesting(Map<String, String> renamedClasses,
                                              Map<String, String> renamedMethods,
                                              Set<String> removedClasses) {
        return new LoaderApiRenames(
                Map.copyOf(Objects.requireNonNullElse(renamedClasses, Map.of())),
                Map.copyOf(Objects.requireNonNullElse(renamedMethods, Map.of())),
                Set.copyOf(Objects.requireNonNullElse(removedClasses, Set.of())));
    }
}
