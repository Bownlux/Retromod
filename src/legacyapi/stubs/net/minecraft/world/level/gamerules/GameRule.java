// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.gamerules;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.serialization.Codec;
import java.util.function.ToIntFunction;
import net.minecraft.world.flag.FeatureFlagSet;

public final class GameRule<T> {
    public GameRule(GameRuleCategory category, GameRuleType type, ArgumentType<T> argument,
            GameRules.VisitorCaller<T> visitorCaller, Codec<T> valueCodec, ToIntFunction<T> commandResult,
            T defaultValue, FeatureFlagSet requiredFeatures) { throw new UnsupportedOperationException(); }
    public T defaultValue() { throw new UnsupportedOperationException(); }
    public String id() { throw new UnsupportedOperationException(); }
    public GameRuleType gameRuleType() { throw new UnsupportedOperationException(); }
    public com.mojang.serialization.DataResult<T> deserialize(String text) { throw new UnsupportedOperationException(); }
}
