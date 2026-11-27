// Compile-only stub of a Fabric API host type on 26.x for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.renderer.v1.model;

import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

public interface FabricBlockStateModel {
    default void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            Predicate<Direction> cullTest) {
        throw new UnsupportedOperationException();
    }
}
