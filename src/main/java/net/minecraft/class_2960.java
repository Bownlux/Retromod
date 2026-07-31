/*
 * Compile-time stub for Minecraft's class_2960 (Identifier / ResourceLocation).
 *
 * Exists only so a Retromod class can name the MC type at compile time - same trick
 * the @Mod stubs use (net/{minecraftforge,neoforged}/fml/common/Mod.java). The
 * resulting .class is excluded from the production jar (see pom.xml &lt;excludes&gt;),
 * so MC's real {@code class_2960} wins at runtime.
 *
 * Used by {@link com.retromod.shim.api.fabric.embedded.FabricItemGroupBuilderShim#build}
 * (fixes #57: legacy {@code FabricItemGroupBuilder.build(Identifier, Supplier)} call sites
 * were resolving to a non-existent shim overload → {@code NoSuchMethodError}).
 */
package net.minecraft;

public class class_2960 {}
