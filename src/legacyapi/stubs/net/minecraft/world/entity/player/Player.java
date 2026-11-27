// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.entity.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public class Player extends LivingEntity {
    public void displayClientMessage(net.minecraft.network.chat.Component text, boolean overlay) { throw new UnsupportedOperationException(); }
    public net.minecraft.world.inventory.AbstractContainerMenu containerMenu;
    public Inventory getInventory() { throw new UnsupportedOperationException(); }
    public GameProfile getGameProfile() { throw new UnsupportedOperationException(); }
    public ItemEntity drop(ItemStack stack, boolean randomly, Prediction prediction) { throw new UnsupportedOperationException(); }
    public boolean isCreative() { throw new UnsupportedOperationException(); }
    public net.minecraft.server.permissions.PermissionSet permissions() { throw new UnsupportedOperationException(); }
    public float getLuck() { throw new UnsupportedOperationException(); }
    public double blockInteractionRange() { throw new UnsupportedOperationException(); }
    public void sendSystemMessage(net.minecraft.network.chat.Component message) { throw new UnsupportedOperationException(); }
}
