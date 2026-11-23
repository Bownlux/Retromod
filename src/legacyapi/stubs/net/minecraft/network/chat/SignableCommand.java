// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.chat;

public record SignableCommand<S>() {
    public static <S> boolean hasSignableArguments(com.mojang.brigadier.ParseResults<S> parsed) { throw new UnsupportedOperationException(); }
}
