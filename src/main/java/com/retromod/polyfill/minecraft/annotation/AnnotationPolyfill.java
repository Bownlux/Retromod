/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.annotation;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Redirects annotation classes that 26.1 and the Forge->NeoForge move dropped or relocated:
 * JSR 305 nullability -> jspecify, Guava VisibleForTesting -> JetBrains, and Forge dist markers
 * -> NeoForge (or stripped).
 */
public class AnnotationPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Annotation Migration (JSR 305 -> JSpecify, Forge -> NeoForge)";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "javax/annotation/Nullable",
            "javax/annotation/Nonnull",
            "com/google/common/annotations/VisibleForTesting",
            "net/minecraftforge/api/distmarker/OnlyIn",
            "net/minecraftforge/api/distmarker/Dist"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // Pure redirects; the replacement libraries ship with MC 26.1.
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // JSR 305 nullability -> jspecify (bundled with 26.1).
        transformer.registerClassRedirect(
            "javax/annotation/Nullable",
            "org/jspecify/annotations/Nullable");

        transformer.registerClassRedirect(
            "javax/annotation/Nonnull",
            "org/jspecify/annotations/NonNull");

        // javax.annotation.concurrent.Immutable still exists, so leave it alone.

        transformer.registerClassRedirect(
            "com/google/common/annotations/VisibleForTesting",
            "org/jetbrains/annotations/VisibleForTesting");

        // NeoForge dropped @OnlyIn; point it at a no-op @Retention so it doesn't CNFE.
        transformer.registerClassRedirect(
            "net/minecraftforge/api/distmarker/OnlyIn",
            "java/lang/annotation/Retention");

        transformer.registerClassRedirect(
            "net/minecraftforge/api/distmarker/Dist",
            "net/neoforged/api/distmarker/Dist");

        transformer.registerClassRedirect(
            "net/minecraftforge/api/distmarker/OnlyIns",
            "java/lang/annotation/Retention");

        // FindBugs annotations some old Forge mods used.
        transformer.registerClassRedirect(
            "edu/umd/cs/findbugs/annotations/Nullable",
            "org/jspecify/annotations/Nullable");

        transformer.registerClassRedirect(
            "edu/umd/cs/findbugs/annotations/NonNull",
            "org/jspecify/annotations/NonNull");

        transformer.registerClassRedirect(
            "edu/umd/cs/findbugs/annotations/SuppressFBWarnings",
            "java/lang/SuppressWarnings");
    }
}
