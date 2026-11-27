// Compile-only stub of a NeoForge 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.client.extensions.common;

import net.minecraft.client.model.Model;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// NeoForge 1.21.4 dropped getCustomRenderer, which old mods still declare and call.
public interface IClientItemExtensions {
    static IClientItemExtensions of(ItemStack stack) { throw new UnsupportedOperationException(); }
    static IClientItemExtensions of(Item item) { throw new UnsupportedOperationException(); }
    default Model getHumanoidArmorModel(ItemStack stack, EquipmentClientInfo.LayerType type, Model original) { throw new UnsupportedOperationException(); }
    default Model getGenericArmorModel(ItemStack stack, EquipmentClientInfo.LayerType type, Model original) { throw new UnsupportedOperationException(); }
    default int getArmorLayerTintColor(ItemStack stack, EquipmentClientInfo.Layer layer, int index, int fallback) { throw new UnsupportedOperationException(); }
    default Identifier getArmorTexture(ItemStack stack, EquipmentClientInfo.LayerType type, EquipmentClientInfo.Layer layer, Identifier fallback) {
        throw new UnsupportedOperationException();
    }
}
