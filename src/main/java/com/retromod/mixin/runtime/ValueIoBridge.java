/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin.runtime;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 1.3.0 track A / Phase 4 (#48): runtime edge-bridge for the {@code CompoundTag -> ValueOutput /
 * ValueInput} save-data refactor (1.21.5 / 26.x). A 1.21.1 mod's {@code @Inject} save-data handler
 * captures a {@code CompoundTag}; the modern methods pass a {@code ValueOutput}/{@code ValueInput}
 * instead. {@link com.retromod.mixin.MixinValueIoAdapter} keeps the mod's handler body UNCHANGED
 * (still operating on a real {@code CompoundTag}) and bridges only at the edges through the two
 * methods here, so no per-instruction body rewrite (which would risk silent save corruption) is
 * needed.
 *
 * <p><b>Zero compile-time Minecraft (or SLF4J) dependency on purpose:</b> Retromod is built without
 * Minecraft on the classpath, and a per-mod embedded copy of this class (Forge/NeoForge, for JPMS
 * split-package safety) must load with only what the mod's own module can see. So every Minecraft
 * type is reached reflectively by name and every result is typed {@code Object}; the synthesized
 * handler {@code CHECKCAST}s it back to {@code CompoundTag} (a universal, always-present NBT type).
 *
 * <p><b>Fails safe:</b> every path is wrapped so that any reflection failure yields a fresh empty
 * {@code CompoundTag} (or {@code null} only if even that can't be constructed). A write handler then
 * writes into a throwaway tag and a read handler reads defaults, i.e. the feature goes inert, which
 * is exactly the current blocklist-strip behavior, never worse and never a crash.
 *
 * <p><b>Verified against the 26.2 jar (the two facts this bridge depends on):</b>
 * <ul>
 *   <li>{@code TagValueOutput.buildResult()} is literally {@code getfield output; areturn} over its
 *       {@code private final CompoundTag output} accumulator, i.e. it hands back the LIVE tag that is
 *       ultimately serialized, so a write handler writing into it at {@code @At("HEAD")} persists
 *       (and coexists with vanilla's own subsequent writes into the same tag).</li>
 *   <li>{@code TagValueInput} holds its source in a single {@code private final CompoundTag input}
 *       field (the only {@code CompoundTag}-typed field on the class), so the by-type field walk
 *       below lands on the real source unambiguously.</li>
 * </ul>
 * The one thing this static analysis cannot confirm is that the concrete {@code ValueOutput}/{@code
 * ValueInput} an entity/block-entity receives at runtime is always the {@code Tag*} implementation
 * (a wrapper would take the inert fail-safe path); that last step is what an in-game save/load
 * round-trip verifies.
 */
public final class ValueIoBridge {

    private ValueIoBridge() {}

    private static final String COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";

    /**
     * The live backing {@code CompoundTag} for a write-side {@code ValueOutput}. The standard
     * implementation ({@code TagValueOutput}, used by entities/block-entities) exposes a pure
     * {@code CompoundTag buildResult()} getter that returns its live accumulator, so writing into
     * the returned tag lands in what gets saved. Returns a throwaway empty tag if the argument is
     * some other {@code ValueOutput}.
     */
    public static Object outputTag(Object valueOutput) {
        if (valueOutput != null) {
            try {
                Method m = LegacyPublicMethods.method(valueOutput.getClass(), "buildResult");
                if (COMPOUND_TAG.equals(m.getReturnType().getName())) {
                    Object tag = m.invoke(valueOutput);
                    if (tag != null) return tag;
                }
            } catch (Throwable ignored) {
                // not a TagValueOutput (or an unexpected shape) -> throwaway tag below
            }
        }
        return newCompoundTag();
    }

    /**
     * The source {@code CompoundTag} for a read-side {@code ValueInput}. The standard implementation
     * ({@code TagValueInput}) holds its source tag in a single {@code CompoundTag}-typed field with
     * no public getter, so it is located by TYPE (robust to a field rename) walking up the class
     * hierarchy. Returns an empty tag if no such field is found, so the handler reads defaults.
     */
    public static Object inputTag(Object valueInput) {
        if (valueInput != null) {
            try {
                for (Class<?> c = valueInput.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (COMPOUND_TAG.equals(f.getType().getName())) {
                            f.setAccessible(true);
                            Object tag = f.get(valueInput);
                            if (tag != null) return tag;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // reflection blocked or unexpected shape -> empty tag below
            }
        }
        return newCompoundTag();
    }

    /** A fresh empty {@code CompoundTag}, or {@code null} if the class can't be constructed. */
    private static Object newCompoundTag() {
        try {
            return Class.forName(COMPOUND_TAG).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }
}
