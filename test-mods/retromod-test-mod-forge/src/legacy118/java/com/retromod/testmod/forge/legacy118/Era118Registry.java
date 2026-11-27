/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge.legacy118;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** #412: a menu named with setRegistryName and registered from a RegistryEvent.Register handler, as MCreator 1.18.2 mods do. */
public final class Era118Registry {

    private Era118Registry() {}

    @SubscribeEvent
    public static void registerMenus(RegistryEvent.Register<MenuType<?>> event) {
        MenuType<?> menu = new MenuType<>((id, inventory) -> null);
        menu.setRegistryName("legacy_menu");
        event.getRegistry().registerAll(menu);
    }
}
