package net.minecraftforge.client;

/** Compile-only: Forge 1.18.2's item render properties, which Forge 1.19 renamed IClientItemExtensions. */
public interface IItemRenderProperties {
    default net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getItemStackRenderer() {
        return null;
    }
}
