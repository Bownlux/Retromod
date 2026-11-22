// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.gamerules;

public class GameRules {
    public GameRules(net.minecraft.world.flag.FeatureFlagSet enabledFeatures) { throw new UnsupportedOperationException(); }
    public <T> T get(GameRule<T> rule) { throw new UnsupportedOperationException(); }
    public <T> void set(GameRule<T> rule, T value, net.minecraft.server.MinecraftServer server) { throw new UnsupportedOperationException(); }
    public interface VisitorCaller<T> {
        void call(GameRuleTypeVisitor visitor, GameRule<T> rule);
    }
}
