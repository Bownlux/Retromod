/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.extensions;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** The client extension an old armor item gets on NeoForge 26.x when it chose its texture in code and has no extension of its own. */
public final class LegacyArmorExtensions implements IClientItemExtensions {

    @Override
    public Identifier getArmorTexture(ItemStack stack, EquipmentClientInfo.LayerType type, EquipmentClientInfo.Layer layer, Identifier fallback) {
        Identifier own = LegacyArmorHooks.armorTexture(this, stack, type, layer, fallback);
        return own != null ? own : fallback;
    }

    /** Watches the extension registration from the constructing mod's bus, once per copy. */
    public static void listen(IEventBus own) {
        own.addListener(EventPriority.HIGHEST, RegisterClientExtensionsEvent.class, event -> registerLast(event, own));
    }

    /**
     * NeoForge posts this event one priority at a time across the mods in load order, so a lowest-priority listener added
     * now to the last mod's bus runs after every registration made before the event's last phase.
     */
    static void registerLast(RegisterClientExtensionsEvent event, IEventBus own) {
        try {
            lastBus(own).addListener(EventPriority.LOWEST, RegisterClientExtensionsEvent.class, LegacyArmorExtensions::register);
        } catch (RuntimeException | LinkageError failure) {
            // Without the mod list, the constructing mod's own bus still registers after the mods ahead of it.
            LegacyLog.warnOnce("armor extensions last bus", "Retromod could not reach the last mod's event bus, so old armor textures"
                    + " register from the old mod's own bus: " + failure);
            try {
                own.addListener(EventPriority.LOWEST, RegisterClientExtensionsEvent.class, LegacyArmorExtensions::register);
            } catch (RuntimeException | LinkageError again) {
                LegacyLog.warnOnce("armor extensions own bus", "Retromod could not register old armor textures, so those items keep"
                        + " their equipment asset's: " + again);
            }
        }
    }

    private static IEventBus lastBus(IEventBus own) {
        IEventBus last = own;
        for (ModContainer mod : ModList.get().getSortedMods()) {
            IEventBus bus = mod.getEventBus();
            if (bus != null) last = bus;
        }
        return last;
    }

    /** Registers one for each item of this copy's own jar that overrides the old texture hook and has no extension yet. */
    static void register(RegisterClientExtensionsEvent event) {
        int registered = 0;
        try {
            Module own = LegacyArmorExtensions.class.getModule();
            for (Item item : BuiltInRegistries.ITEM) {
                if (registerItem(event, item, own)) registered++;
            }
        } catch (RuntimeException | LinkageError failure) {
            // The registry walk itself failed; the items it did not reach keep their equipment asset's texture.
            LegacyLog.warnOnce("armor extensions", "Retromod could not finish registering old armor textures: " + failure);
        }
        if (registered > 0) LegacyLog.debug("Retromod registered armor texture extensions for " + registered + " old armor item(s)");
    }

    private static boolean registerItem(RegisterClientExtensionsEvent event, Item item, Module own) {
        try {
            // Only this jar's items: each translated jar carries its own copy of this class.
            if (item.getClass().getModule() != own || !LegacyArmorHooks.choosesTexture(item) || event.isItemRegistered(item)) return false;
            event.registerItem(new LegacyArmorExtensions(), item);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("armor extension " + item.getClass().getName(), "Retromod could not give the old armor item "
                    + item.getClass().getName() + " its own texture, so it keeps its equipment asset's: " + failure);
            return false;
        }
    }
}
