/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Forge 1.20.1's ModelResourceLocation, kept as an Identifier whose path ends in {@code #variant}. */
public final class LegacyModelLocations {

    // Forge 1.20.1's location was an Identifier subclass, and old code passes one wherever an Identifier goes.
    // Identifier is final now, so the location is an Identifier itself. Its path carries the variant the way
    // the old toString() printed it, so equals, hashCode and toString keep telling variants apart.
    public static final String INVENTORY = "inventory";
    public static final String STANDALONE = "standalone";
    private static final char SEPARATOR = '#';
    private static final String MISSING_PATH = "builtin/missing";
    // When Identifier's own constructor is out of reach, the variant is spelled with path characters instead.
    private static final String MARKER = "/__retromod_variant__/";

    // Blocks keep their states for the whole game, so the lookup by variant is built once per block.
    private static final Map<Block, Map<String, BlockState>> VARIANTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Constructor<Identifier> exact;
    private static volatile boolean exactUnavailable;

    private LegacyModelLocations() {}

    /** {@code new ModelResourceLocation(id, variant)}. */
    public static Identifier of(Identifier id, String variant) {
        return of(id.getNamespace(), path(id), variant);
    }

    /** {@code new ModelResourceLocation(namespace, path, variant)}. */
    public static Identifier of(String namespace, String path, String variant) {
        // The id itself is checked first, as Forge 1.20.1's constructor checked it, so no invalid id is ever built.
        Identifier.fromNamespaceAndPath(namespace, path);
        String normalized = variant == null ? "" : variant.toLowerCase(Locale.ROOT);
        Identifier made = exact(namespace, path + SEPARATOR + normalized);
        return made != null ? made : Identifier.fromNamespaceAndPath(namespace, path + MARKER + encode(normalized));
    }

    /** {@code new ModelResourceLocation(location, variant)}, the location read as {@code namespace:path}. */
    public static Identifier of(String location, String variant) {
        return of(parseId(location), variant);
    }

    /** {@code new ModelResourceLocation("namespace:path#variant")}. */
    public static Identifier parse(String location) {
        int split = location.indexOf(SEPARATOR);
        if (split < 0) return of(parseId(location), "");
        return of(parseId(location.substring(0, split)), location.substring(split + 1));
    }

    /** {@code ModelResourceLocation.vanilla(path, variant)}. */
    public static Identifier vanilla(String path, String variant) {
        return of("minecraft", path, variant);
    }

    /** Forge 1.21's {@code ModelResourceLocation.inventory(id)}. */
    public static Identifier inventory(Identifier id) {
        return of(id, INVENTORY);
    }

    /** Forge 1.21's {@code ModelResourceLocation.standalone(id)}, for a model registered on its own. */
    public static Identifier standalone(Identifier id) {
        return of(id, STANDALONE);
    }

    /** Forge 1.20.1's {@code ModelBakery.MISSING_MODEL_LOCATION}, read as a call so loading this class builds nothing. */
    public static Identifier missingModelLocation() {
        return of("minecraft", MISSING_PATH, "missing");
    }

    /** Forge 1.21's {@code ModelBakery.MISSING_MODEL_LOCATION}, a plain id; its located form is MISSING_MODEL_VARIANT. */
    public static Identifier missingModelId() {
        return Identifier.withDefaultNamespace(MISSING_PATH);
    }

    /** Whether the location names the missing model, with or without its variant. */
    public static boolean isMissing(Identifier location) {
        return location.getNamespace().equals("minecraft") && path(location).equals(MISSING_PATH);
    }

    /** {@code location instanceof ModelResourceLocation}: true for a location with a variant, false for a plain id. */
    public static boolean isLocation(Object location) {
        return location instanceof Identifier id && hasVariant(id);
    }

    /** {@code getVariant()}: the variant, or empty for a plain identifier. */
    public static String getVariant(Identifier location) {
        String path = location.getPath();
        int split = path.lastIndexOf(SEPARATOR);
        if (split >= 0) return path.substring(split + 1);
        int marker = path.lastIndexOf(MARKER);
        return marker < 0 ? "" : decode(path.substring(marker + MARKER.length()));
    }

    /** Forge 1.21's {@code id()}: the location without its variant. */
    public static Identifier id(Identifier location) {
        return hasVariant(location) ? Identifier.fromNamespaceAndPath(location.getNamespace(), path(location)) : location;
    }

    /** Whether the identifier is a model location with a variant, not a plain identifier. */
    public static boolean hasVariant(Identifier location) {
        String path = location.getPath();
        return path.indexOf(SEPARATOR) >= 0 || path.contains(MARKER);
    }

    /** The path without the variant, as Forge 1.20.1's {@code getPath()} answered for a model location. */
    public static String path(Identifier location) {
        String path = location.getPath();
        int split = path.lastIndexOf(SEPARATOR);
        if (split >= 0) return path.substring(0, split);
        int marker = path.lastIndexOf(MARKER);
        return marker < 0 ? path : path.substring(0, marker);
    }

    // Forge 1.20.1's location kept its path apart from its variant, so the Identifier members it inherited read the
    // path alone. These answer for a call made on the old location type.

    public static Identifier withPrefix(Identifier location, String prefix) {
        return id(location).withPrefix(prefix);
    }

    public static Identifier withSuffix(Identifier location, String suffix) {
        return id(location).withSuffix(suffix);
    }

    public static Identifier withPath(Identifier location, String path) {
        return id(location).withPath(path);
    }

    public static Identifier withPath(Identifier location, UnaryOperator<String> path) {
        return id(location).withPath(path);
    }

    public static String toLanguageKey(Identifier location) {
        return id(location).toLanguageKey();
    }

    public static String toLanguageKey(Identifier location, String prefix) {
        return id(location).toLanguageKey(prefix);
    }

    public static String toLanguageKey(Identifier location, String prefix, String suffix) {
        return id(location).toLanguageKey(prefix, suffix);
    }

    public static String toShortLanguageKey(Identifier location) {
        return id(location).toShortLanguageKey();
    }

    public static String toDebugFileName(Identifier location) {
        return id(location).toDebugFileName();
    }

    /** {@code BlockModelShaper.stateToModelLocation(state)}: the block's id and the state's properties. */
    public static Identifier forState(BlockState state) {
        return forState(BuiltInRegistries.BLOCK.getKey(state.getBlock()), state);
    }

    /** {@code BlockModelShaper.stateToModelLocation(blockId, state)}. */
    public static Identifier forState(Identifier blockId, BlockState state) {
        return of(blockId, variantOf(state));
    }

    /** Forge 1.20.1's variant of a state: each property as {@code name=value}, sorted by name, joined by commas. */
    public static String variantOf(BlockState state) {
        List<Property<?>> properties = new ArrayList<>(state.getBlock().getStateDefinition().getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        StringBuilder variant = new StringBuilder();
        for (Property<?> property : properties) {
            if (variant.length() > 0) variant.append(',');
            variant.append(property.getName()).append('=').append(valueName(state, property));
        }
        return variant.toString();
    }

    /** Whether the state has every {@code name=value} pair the variant lists; an empty variant matches every state. */
    static boolean matches(BlockState state, String variant) {
        if (variant.isEmpty()) return true;
        List<Property<?>> properties = new ArrayList<>(state.getBlock().getStateDefinition().getProperties());
        for (String pair : variant.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 0) return false;
            String name = pair.substring(0, equals);
            Property<?> property = properties.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
            if (property == null || !valueName(state, property).equals(pair.substring(equals + 1))) return false;
        }
        return true;
    }

    /** The state a variant names: the one it spells, else the first it matches, else null. */
    static BlockState stateOf(Block block, String variant) {
        Map<String, BlockState> byVariant = VARIANTS.computeIfAbsent(block, LegacyModelLocations::variants);
        BlockState exact = byVariant.get(variant);
        if (exact != null) return exact;
        for (BlockState state : byVariant.values()) {
            if (matches(state, variant)) return state;
        }
        return null;
    }

    // Each state of a block by its variant, in the block's own state order.
    private static Map<String, BlockState> variants(Block block) {
        Map<String, BlockState> byVariant = new LinkedHashMap<>();
        for (BlockState state : block.getStateDefinition().getPossibleStates()) byVariant.put(variantOf(state), state);
        return byVariant;
    }

    /** The block a location names, or null when no block has that id. */
    static Block blockOf(Identifier location) {
        Identifier id = id(location);
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        // The block registry answers air for an unknown id.
        return block != null && id.equals(BuiltInRegistries.BLOCK.getKey(block)) ? block : null;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Identifier parseId(String location) {
        int colon = location.indexOf(':');
        return colon < 0 ? Identifier.withDefaultNamespace(location)
                : Identifier.fromNamespaceAndPath(location.substring(0, colon), location.substring(colon + 1));
    }

    // Identifier's private constructor stores the path as given, so the old "path#variant" text can be kept.
    private static Identifier exact(String namespace, String path) {
        if (exactUnavailable) return null;
        try {
            Constructor<Identifier> make = exact;
            if (make == null) {
                make = Identifier.class.getDeclaredConstructor(String.class, String.class);
                make.setAccessible(true);
                exact = make;
            }
            return make.newInstance(namespace, path);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            exactUnavailable = true;
            LegacyLog.warnOnce("legacy model location " + LegacyModelLocations.class.getName(), "Retromod could not keep"
                    + " old model locations in their namespace:path#variant form (" + unavailable + "), so they print"
                    + " with " + MARKER + " in place of #.");
            return null;
        }
    }

    // Each character outside lowercase letters, digits, '_' and '-' becomes '.' and two hex digits.
    private static String encode(String variant) {
        StringBuilder out = new StringBuilder();
        for (char c : variant.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('.').append(String.format(Locale.ROOT, "%02x", c & 0xFF));
            }
        }
        return out.toString();
    }

    private static String decode(String encoded) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '.' && i + 2 < encoded.length()) {
                out.append((char) Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
