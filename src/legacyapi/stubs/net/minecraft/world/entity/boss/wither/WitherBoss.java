// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity.boss.wither;

import net.minecraft.world.entity.Mob;

// Extends Monster on the host, which extends Mob.
public class WitherBoss extends Mob {
    public WitherBoss(net.minecraft.world.entity.EntityType<? extends WitherBoss> type, net.minecraft.world.level.Level level) { super(type, level); }
    public boolean isPowered() { throw new UnsupportedOperationException(); }
}
