// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeManager;

public abstract class MinecraftServer {
    public abstract boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId owner);
    public RecipeManager getRecipeManager() { throw new UnsupportedOperationException(); }
    public RegistryAccess.Frozen registryAccess() { throw new UnsupportedOperationException(); }
    public ReloadableServerRegistries.Holder reloadableRegistries() { throw new UnsupportedOperationException(); }
}
