/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

/**
 * Stands in for the loot {@code NumberProvider} interface that 26.3 removed, along with the old
 * {@code ConstantValue} and {@code UniformGenerator}. A mod built earlier keeps passing values of
 * this type around, and {@link LegacyNumberProviders} converts them when a 26.3 API receives one.
 *
 * <p>It is an interface so that a parameter of this type accepts the 26.3 providers too: the JVM
 * verifier checks no assignability for interface types, and a mod may hand an int provider such as
 * the moved {@code BinomialDistributionGenerator} to a parameter that used to take the old type.
 */
public interface LegacyNumberProvider {
}
