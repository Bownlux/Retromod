/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.material;

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/** The old surface rule members 26.3 removed, rebuilt on its material rules. */
public final class LegacyMaterialRules {

    // The stone depth checks the old SurfaceRules kept as constants.
    public static final MaterialCondition ON_FLOOR = MaterialRules.stoneDepthCheck(0, false, CaveSurface.FLOOR);
    public static final MaterialCondition UNDER_FLOOR = MaterialRules.stoneDepthCheck(0, true, CaveSurface.FLOOR);
    public static final MaterialCondition DEEP_UNDER_FLOOR = MaterialRules.stoneDepthCheck(0, true, 6, CaveSurface.FLOOR);
    public static final MaterialCondition VERY_DEEP_UNDER_FLOOR =
            MaterialRules.stoneDepthCheck(0, true, 30, CaveSurface.FLOOR);
    public static final MaterialCondition ON_CEILING = MaterialRules.stoneDepthCheck(0, false, CaveSurface.CEILING);
    public static final MaterialCondition UNDER_CEILING = MaterialRules.stoneDepthCheck(0, true, CaveSurface.CEILING);

    // Owns the stand-alone biome holders a key condition is read through; they carry a key and no biome.
    @SuppressWarnings("rawtypes")
    private static final HolderOwner KEYS_ONLY = new HolderOwner() {};
    // Every mod's copy of the key condition is its own class, so another jar finds the keys by name.
    private static final ClassValue<Method> BIOME_KEYS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            if (!type.getName().endsWith(".LegacyMaterialRules$KeyBiomeCondition")) return null;
            try {
                return type.getMethod("retromod$biomeKeys");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    private LegacyMaterialRules() {}

    /** The old {@code isBiome(keys)}: a condition that reads the biome where it is tested, with no server needed. */
    public static MaterialCondition isBiome(ResourceKey<?>... keys) {
        return new KeyBiomeCondition(keys.clone());
    }

    /** The old {@code rule.apply(context)}, which 26.3 does by compiling the rule against the context. */
    public static Object applyRule(Object rule, Object context) {
        return ((MaterialRule) rule).compile((MaterialRuleContext) context);
    }

    /** The same call where the mod named the old types. */
    public static RuleEvaluator applyRule(Object rule, MaterialRuleContext context) {
        return ((MaterialRule) rule).compile(context);
    }

    /** The old {@code condition.apply(context)}, compiled the same way. */
    public static Object applyCondition(Object condition, Object context) {
        return ((MaterialCondition) condition).compile((MaterialRuleContext) context);
    }

    /** The same call where the mod named the old types. */
    public static ConditionEvaluator applyCondition(Object condition, MaterialRuleContext context) {
        return ((MaterialCondition) condition).compile(context);
    }

    /** The old {@code Context.biome} supplier, which reads the biome where the context stands when called. */
    public static Supplier<Object> biome(Object context) {
        MaterialRuleContext rules = (MaterialRuleContext) context;
        return rules::getBiome;
    }

    /** The old {@code instanceof BiomeConditionSource}: 26.3's biome condition, or the key condition of any mod. */
    public static boolean isBiomeCondition(Object condition) {
        return condition instanceof BiomeCondition || biomeKeysOf(condition) != null;
    }

    /** The old cast; a key condition passes as a 26.3 condition over stand-alone holders of its keys. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BiomeCondition asBiomeCondition(Object condition) {
        ResourceKey[] keys = condition instanceof BiomeCondition ? null : biomeKeysOf(condition);
        if (keys == null) return (BiomeCondition) condition;
        List<Holder> holders = new ArrayList<>();
        for (ResourceKey key : keys) holders.add(Holder.Reference.createStandAlone(KEYS_ONLY, key));
        return new BiomeCondition(HolderSet.direct((List) holders));
    }

    /** The old {@code biomes} field, a list of keys, read from the condition's biome holders. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<ResourceKey> biomes(BiomeCondition condition) {
        List<ResourceKey> keys = new ArrayList<>();
        condition.biomes().stream().forEach(biome -> ((Holder) biome).unwrapKey().ifPresent(key -> keys.add((ResourceKey) key)));
        return keys;
    }

    @SuppressWarnings("rawtypes")
    private static ResourceKey[] biomeKeysOf(Object condition) {
        Method keys = condition == null ? null : BIOME_KEYS.get(condition.getClass());
        if (keys == null) return null;
        try {
            return (ResourceKey[]) keys.invoke(condition);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** The old {@code NoiseGeneratorSettings.surfaceRule()}, now the value of the settings' rule holder. */
    public static MaterialRule surfaceRule(Object settings) {
        return ((NoiseGeneratorSettings) settings).materialRule().value();
    }

    /** A biome condition kept as keys and matched against the biome the context reads at each block. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final class KeyBiomeCondition implements MaterialCondition {
        private static final ConditionEvaluator NEVER = () -> false;
        private static final ConditionEvaluator ALWAYS = () -> true;

        private final ResourceKey[] keys;

        KeyBiomeCondition(ResourceKey[] keys) {
            this.keys = keys;
        }

        @Override
        public ConditionEvaluator compile(MaterialRuleContext context) {
            // A surface step names the biomes its chunk can hold; a carver names none.
            Set<Holder> possible = context.possibleBiomes();
            if (possible != null) {
                boolean any = false;
                boolean all = true;
                for (Holder biome : possible) {
                    if (matches(biome)) any = true;
                    else all = false;
                }
                if (!any) return NEVER;
                if (all) return ALWAYS;
            }
            return new AtBiome(context);
        }

        /** The keys, which another mod reads by this name, as Citadel groups rules by biome. */
        public ResourceKey[] retromod$biomeKeys() {
            return keys.clone();
        }

        boolean matches(Holder biome) {
            for (ResourceKey key : keys) {
                if (biome.is(key)) return true;
            }
            return false;
        }

        /** Not written back to data: the condition holds only keys. */
        @Override
        public MapCodec codec() {
            return MapCodec.unit(this);
        }

        /** Tests the biome once per block height, as the host's own biome condition does. */
        final class AtBiome extends MaterialRuleContext.LazyYCondition {
            AtBiome(MaterialRuleContext context) {
                super(context);
            }

            @Override
            protected boolean compute() {
                return matches(context.getBiome());
            }
        }
    }
}
