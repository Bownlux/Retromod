/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * {@link ClassWriter} that safely handles {@link #getCommonSuperClass} in
 * modded Minecraft environments.
 *
 * <h3>The problem</h3>
 * <p>ASM's default {@code getCommonSuperClass} implementation uses
 * {@link Class#forName(String)} to resolve the two type names against the
 * current classloader. That works fine for standard JDK classes but fails
 * in two common Retromod situations:
 *
 * <ul>
 *   <li><b>AOT compilation off the game thread.</b> When Retromod compiles a
 *       mod's bytecode outside of Minecraft (CLI mode, or pre-launch on a
 *       background thread), MC's classloader isn't reachable. Any reference
 *       to a {@code net.minecraft.*} class throws
 *       {@link ClassNotFoundException}, which ASM wraps in
 *       {@link TypeNotPresentException} and re-throws, aborting the whole
 *       compilation.</li>
 *   <li><b>Intermediary names remapped to Mojang names not on the classpath.</b>
 *       Retromod rewrites class names mid-transform; the resulting bytecode
 *       can reference target-MC classes that the source-MC classpath
 *       doesn't contain. Same failure mode.</li>
 * </ul>
 *
 * <h3>The fix</h3>
 * <p>Catch any throwable from the superclass call and ask
 * {@link com.retromod.core.RetromodTransformer#resolveCommonSuperClass} instead, which reads the
 * hierarchy out of the jar being transformed when it can and otherwise guesses conservatively.
 *
 * <p>This used to answer {@code "java/lang/Object"} unconditionally. That is always accepted for
 * the merge itself, but it is not always safe: a value typed {@code Object} is rejected the moment
 * it is passed somewhere that wants a specific type, which is how a mod that branched between two
 * of its own screens ended up failing verification on {@code setScreen} (#180). Widening is a last
 * resort, not a default.
 *
 * <h3>Use whenever</h3>
 * <p>any {@code ClassWriter} is constructed with {@link #COMPUTE_FRAMES}
 * or {@link #COMPUTE_MAXS} on bytecode that may reference MC classes.
 * Crash report that motivated the extraction: "AOT compilation failed:
 * journeymap-fabric-26.1.2-6.0.0-beta.78.jar / TypeNotPresentException:
 * Type net/minecraft/client/gui/GuiGraphics not present".
 */
public class SafeClassWriter extends ClassWriter {

    public SafeClassWriter(int flags) {
        super(flags);
    }

    public SafeClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return super.getCommonSuperClass(type1, type2);
        } catch (Exception | LinkageError e) {
            // Includes TypeNotPresentException, ClassNotFoundException, NoClassDefFoundError.
            //
            // java/lang/Object is always accepted by the verifier for the merge itself, but it is
            // not always safe: if the merged value is then passed somewhere that wants a specific
            // type, the method is rejected (#180). So ask the transformer, which can read the
            // hierarchy out of the jar being transformed and falls back to the naming-based guess
            // that keeps exception merges intact (#94).
            return com.retromod.core.RetromodTransformer.resolveCommonSuperClass(type1, type2);
        }
    }
}
