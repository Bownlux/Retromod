/*
 * Retromod - compile-time stub of net.minecraftforge.fml.common.Mod
 * Copyright (c) 2026 Bownlux. MIT License.
 *
 * This is not a copy of Forge's Mod annotation. It exists only so that
 * com.retromod.core.RetromodForge compiles without Forge on the build
 * classpath. Retromod is a multi-loader mod (Fabric / NeoForge / Forge)
 * that ships as a single artifact; pulling in the full Forge dep tree
 * just to resolve one annotation type would bloat the build for no real
 * gain.
 *
 * AT RUNTIME under Forge, Forge's classloader provides the REAL
 * net.minecraftforge.fml.common.Mod class - the one that FML's mod
 * scanner actually reads. Forge's class-loading hierarchy gets first
 * dibs on classes in its own packages, so our stub here is shadowed
 * and never used at runtime.
 *
 * Under Fabric / the standalone CLI, this class is loaded but never
 * referenced (the @Mod annotation only appears on RetromodForge,
 * which Fabric/CLI never instantiate). Dead weight, but tiny - one
 * class file, ~250 bytes.
 *
 * If you ever consider deleting this file: the compile WILL fail for
 * RetromodForge.java because it references @Mod by FQN. Either keep
 * the stub, or add a real Forge dependency to pom.xml at provided
 * scope.
 */
package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stub. The real {@code Mod} annotation is provided by Forge
 * at runtime; this minimal definition only carries the {@code value()}
 * attribute that FML's mod scanner reads.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    /** The mod ID this class is the entry point for. Must match {@code mods.toml}. */
    String value();
}
