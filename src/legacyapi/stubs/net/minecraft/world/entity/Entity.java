// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class Entity {
    public double xOld;
    public double yOld;
    public double zOld;
    public int tickCount;
    public float yRotO;

    public EntityType<?> getType() { throw new UnsupportedOperationException(); }
    public final net.minecraft.world.phys.Vec3 position() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.entity.item.ItemEntity spawnAtLocation(net.minecraft.server.level.ServerLevel level, net.minecraft.world.item.ItemStack stack) { throw new UnsupportedOperationException(); }
    public float getYRot() { throw new UnsupportedOperationException(); }
    public final double getX() { throw new UnsupportedOperationException(); }
    public final double getY() { throw new UnsupportedOperationException(); }
    public final double getZ() { throw new UnsupportedOperationException(); }
    public Level level() { throw new UnsupportedOperationException(); }
    public java.util.Set<String> entityTags() { throw new UnsupportedOperationException(); }
    public boolean startRiding(Entity vehicle, boolean force, boolean sendEvents) { throw new UnsupportedOperationException(); }
    public BlockPos blockPosition() { throw new UnsupportedOperationException(); }
    public Entity getVehicle() { throw new UnsupportedOperationException(); }
    public boolean showVehicleHealth() { throw new UnsupportedOperationException(); }
    public boolean isUnderWater() { throw new UnsupportedOperationException(); }
    public int getAirSupply() { throw new UnsupportedOperationException(); }
    public int getMaxAirSupply() { throw new UnsupportedOperationException(); }
    public boolean shouldShowName() { throw new UnsupportedOperationException(); }
    public boolean hasCustomName() { throw new UnsupportedOperationException(); }
}
