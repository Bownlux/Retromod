// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeManager;

public abstract class MinecraftServer {
    public abstract boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId owner);
    public Services services() { throw new UnsupportedOperationException(); }
    public RecipeManager getRecipeManager() { throw new UnsupportedOperationException(); }
    public RegistryAccess.Frozen registryAccess() { throw new UnsupportedOperationException(); }
    public ReloadableServerRegistries.Holder reloadableRegistries() { throw new UnsupportedOperationException(); }
    public net.minecraft.server.notifications.NotificationManager notificationManager() { throw new UnsupportedOperationException(); }
    public final net.minecraft.server.level.ServerLevel overworld() { throw new UnsupportedOperationException(); }
    // 1.21.2 to 26.2 only.
    public net.minecraft.world.level.block.entity.FuelValues fuelValues() { throw new UnsupportedOperationException(); }
    public java.nio.file.Path getWorldPath(net.minecraft.world.level.storage.LevelResource resource) { throw new UnsupportedOperationException(); }
}
