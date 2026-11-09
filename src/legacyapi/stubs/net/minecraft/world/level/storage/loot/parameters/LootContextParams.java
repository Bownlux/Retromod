// Compile-only stub of a 1.21.2 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage.loot.parameters;

import net.minecraft.util.context.ContextKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class LootContextParams {
    public static final ContextKey<Entity> THIS_ENTITY = null;
    public static final ContextKey<Vec3> ORIGIN = null;
    public static final ContextKey<DamageSource> DAMAGE_SOURCE = null;
    public static final ContextKey<Entity> ATTACKING_ENTITY = null;
    public static final ContextKey<Entity> DIRECT_ATTACKING_ENTITY = null;
    public static final ContextKey<Player> LAST_DAMAGE_PLAYER = null;
}
