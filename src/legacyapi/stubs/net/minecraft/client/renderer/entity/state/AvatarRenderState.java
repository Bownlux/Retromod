// Compile-only stub of a 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.entity.state;

import net.minecraft.world.entity.player.PlayerSkin;

// HumanoidRenderState sits between this and LivingEntityRenderState on the host.
public class AvatarRenderState extends LivingEntityRenderState {
    public PlayerSkin skin;
}
