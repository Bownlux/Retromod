/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyEnchantmentRegistry;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Gives old code that reads {@code BuiltInRegistries.ENCHANTMENT}, gone since 1.21, the loaded world's enchantment registry. */
public class LegacyEnchantmentRegistryShim implements MinecraftVersionedApiShim {

    /** The two old members under one host's names: the field, and {@code ItemStack.enchant(Enchantment, int)}. */
    record Names(String builtIn, String field, String fieldDesc, String stack, String enchant, String enchantDesc) {}

    static final Names MOJANG = new Names("net/minecraft/core/registries/BuiltInRegistries", "ENCHANTMENT",
            "Lnet/minecraft/core/Registry;", "net/minecraft/world/item/ItemStack", "enchant",
            "(Lnet/minecraft/world/item/enchantment/Enchantment;I)V");
    // Fabric before 26.1: the 1.20.x intermediary names, which 1.21 to 1.21.11 keep for ItemStack.enchant.
    static final Names INTERMEDIARY = new Names("net/minecraft/class_7923", "field_41176",
            "Lnet/minecraft/class_2378;", "net/minecraft/class_1799", "method_7978", "(Lnet/minecraft/class_1887;I)V");
    /** The view, embedded in each mod that reads the field. */
    public static final String HELPER = "com/retromod/shim/common/embedded/LegacyEnchantmentRegistry";

    @Override
    public String getShimName() {
        return "Data-Driven Enchantment Registry";
    }

    @Override
    public String getSourceVersion() {
        return "1.20.6";
    }

    /** BuiltInRegistries.ENCHANTMENT still exists below 1.21, so nothing is rewritten on those hosts. */
    @Override
    public String getTargetVersion() {
        return "1.21";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        String target = RetromodVersion.TARGET_MC_VERSION;
        // Fabric hosts before 26.1 run intermediary names, so the old jar's intermediary names are what to match.
        Names names = mojangNamedHost(target) ? MOJANG : INTERMEDIARY;
        register(transformer, names, lacksField(ClassResourceInspector::read, names, target),
                lacksOldEnchant(ClassResourceInspector::read, names, target));
    }

    /** Registers the view under Mojang names, for the field read and the old enchant call. */
    static void register(RetromodTransformer transformer, boolean field, boolean enchant) {
        register(transformer, MOJANG, field, enchant);
    }

    /** Registers the view for the field read and the old enchant call, each only where the host lost it. */
    static void register(RetromodTransformer transformer, Names names, boolean field, boolean enchant) {
        if (!field && !enchant) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyEnchantmentRegistry.class);
        // 1.21 moved enchantments into data packs, so each world loads its own registry and the field is gone.
        if (field) {
            transformer.registerFieldRedirect(names.builtIn(), names.field(), names.fieldDesc(),
                    HELPER, "enchantments", "()Ljava/lang/Object;");
        }
        // The same release made ItemStack.enchant take the enchantment's holder, which the view finds in that world.
        if (enchant) {
            transformer.registerMethodRedirect(names.stack(), names.enchant(), names.enchantDesc(),
                    HELPER, "enchant", "(Ljava/lang/Object;Ljava/lang/Object;I)V", true);
        }
    }

    /** Whether the host's BuiltInRegistries lacks the field; offline, a target from 1.21. */
    static boolean lacksField(Function<String, ClassNode> host, Names names, String target) {
        ClassNode builtIn = host.apply(names.builtIn());
        if (builtIn == null) return fromVersion(target);
        return builtIn.fields.stream().noneMatch(f -> f.name.equals(names.field()));
    }

    /** Whether the host's ItemStack lacks {@code enchant(Enchantment, int)}; offline, a target from 1.21. */
    static boolean lacksOldEnchant(Function<String, ClassNode> host, Names names, String target) {
        ClassNode stack = host.apply(names.stack());
        if (stack == null) return fromVersion(target);
        return stack.methods.stream().noneMatch(m -> m.name.equals(names.enchant()) && m.desc.equals(names.enchantDesc()));
    }

    // NeoForge, Forge, and Fabric from 26.1 run Mojang names; Fabric before 26.1 runs intermediary ones.
    static boolean mojangNamedHost(String target) {
        return McReflect.isNeoForge() || McReflect.isForge() || McReflect.isForgeTarget()
                || RetromodVersion.isUnobfuscatedTarget(target);
    }

    // Offline the CLI cannot read the host's classes, so the target version answers.
    private static boolean fromVersion(String target) {
        return target != null && RetromodVersion.compareMcVersions(target, "1.21") >= 0;
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
