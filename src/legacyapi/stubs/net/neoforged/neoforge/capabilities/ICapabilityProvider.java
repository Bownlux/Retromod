// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.capabilities;

public interface ICapabilityProvider<O, C, T> {
    T getCapability(O object, C context);
}
