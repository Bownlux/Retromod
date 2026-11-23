// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.gamerules;

public interface GameRuleTypeVisitor {
    default void visitBoolean(GameRule<Boolean> rule) { throw new UnsupportedOperationException(); }
    default void visitInteger(GameRule<Integer> rule) { throw new UnsupportedOperationException(); }
}
