// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.common;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

public class ForgeHooks {
    public static boolean canEntityDestroy(ServerLevel level, BlockPos pos, LivingEntity entity) { throw new UnsupportedOperationException(); }
}
