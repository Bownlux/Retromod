// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraftforge.event.entity.living;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityEvent;

public interface LivingEvent extends EntityEvent {
    @Override
    LivingEntity getEntity();
}
