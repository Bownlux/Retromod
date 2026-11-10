/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the 26.3 compost and fuel item components from an old compost chance or burn time. */
public final class LegacyItemComponentValues {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final String COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";

    // The two components and the builder call that sets one, shared by the Fabric and NeoForge bridges.
    public static final Member COMPOSTABLE = new Member("net/minecraft/core/component/DataComponents",
            "COMPOSTABLE", COMPONENT_TYPE, true);
    public static final Member COOKING_FUEL = new Member("net/minecraft/core/component/DataComponents",
            "COOKING_FUEL", COMPONENT_TYPE, true);
    public static final Member BUILDER_SET = Member.virtual("net/minecraft/core/component/DataComponentMap$Builder",
            "set", "(" + COMPONENT_TYPE + "Ljava/lang/Object;)Lnet/minecraft/core/component/DataComponentMap$Builder;");

    private static final String INTS = "net/minecraft/world/level/storage/loot/providers/number/ints/";
    private static final String FLOATS = "net/minecraft/world/level/storage/loot/providers/number/floats/";
    static final Member NEW_COMPOSTABLE = Member.constructor("net/minecraft/world/item/component/Compostable",
            "(" + RESOURCE_KEY + ")V");
    static final Member NEW_COOKING_FUEL = Member.constructor("net/minecraft/world/item/component/CookingFuel",
            "(L" + INTS + "ResolvableInt;L" + FLOATS + "ResolvableFloat;)V");
    static final Member NEW_INT_CONSTANT = Member.constructor(INTS + "ResolvableInt$Constant", "(I)V");
    static final Member FLOAT_FROM_KEY = Member.staticMethod(FLOATS + "ResolvableFloat", "fromKey",
            "(" + RESOURCE_KEY + ")L" + FLOATS + "ResolvableFloat;");
    static final Member CREATE_KEY = Member.staticMethod("net/minecraft/resources/ResourceKey", "create",
            "(" + RESOURCE_KEY + "Lnet/minecraft/resources/Identifier;)" + RESOURCE_KEY);
    static final Member INT_PROVIDERS = new Member("net/minecraft/core/registries/Registries",
            "CONTEXT_INT_PROVIDER", RESOURCE_KEY, true);
    static final Member FLOAT_PROVIDERS = new Member("net/minecraft/core/registries/Registries",
            "CONTEXT_FLOAT_PROVIDER", RESOURCE_KEY, true);
    static final Member IDENTIFIER = Member.staticMethod("net/minecraft/resources/Identifier",
            "fromNamespaceAndPath", "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");

    // Vanilla's composting tiers, by the old chance each stands for. A component names one of
    // these or a whole number of layers, so an old chance takes the nearest tier.
    private static final Map<Float, String> COMPOST_TIERS = Map.of(
            0.3f, "compostable/low", 0.5f, "compostable/low_medium", 0.65f, "compostable/medium",
            0.85f, "compostable/medium_high", 1.0f, "compostable/always_add_one");
    private static final String FUEL_SPEED = "cooking/speed_default";

    private LegacyItemComponentValues() {}

    /** The compost component for an old chance, at the nearest of vanilla's tiers. */
    public static Object compostable(float chance) {
        String tier = nearestTier(chance);
        if (Math.abs(nearestTierChance(chance) - chance) > 0.001f && REPORTED.add("compost-round:" + chance)) {
            LOGGER.debug("Composting chance {} uses vanilla's {} tier, the nearest 26.3 has", chance, tier);
        }
        Object key = call(HostLink.require(CREATE_KEY), call(HostLink.require(INT_PROVIDERS)), id(tier));
        return call(HostLink.require(NEW_COMPOSTABLE), key);
    }

    /** The fuel component for an old burn time, with vanilla's default cooking speed. */
    public static Object cookingFuel(int ticks) {
        Object burnTime = call(HostLink.require(NEW_INT_CONSTANT), ticks);
        Object speedKey = call(HostLink.require(CREATE_KEY), call(HostLink.require(FLOAT_PROVIDERS)), id(FUEL_SPEED));
        Object speed = call(HostLink.require(FLOAT_FROM_KEY), speedKey);
        return call(HostLink.require(NEW_COOKING_FUEL), burnTime, speed);
    }

    /** The vanilla compost tier nearest to an old chance, such as {@code compostable/low}. */
    public static String nearestTier(float chance) {
        return COMPOST_TIERS.get(nearestTierChance(chance));
    }

    private static float nearestTierChance(float chance) {
        float best = 1.0f;
        for (float tier : COMPOST_TIERS.keySet()) {
            if (Math.abs(tier - chance) < Math.abs(best - chance)) best = tier;
        }
        return best;
    }

    private static Object id(String path) {
        return call(HostLink.require(IDENTIFIER), "minecraft", path);
    }

    private static Object call(MethodHandle handle, Object... args) {
        try {
            // Fixed arity, so an array passed to a varargs member is not wrapped in another array.
            return handle.asFixedArity().invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("A host call failed: " + handle, e);
        }
    }
}
