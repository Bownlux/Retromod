/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** The client half of {@link ForgeSmallRemovals}: a vanilla chest screen for its menu, registered as a 1.20.1 mod does. */
final class ForgeSmallRemovalsClient {

    private ForgeSmallRemovalsClient() {}

    static void registerScreen(FMLClientSetupEvent event) {
        // Client setup runs mods in parallel, so the screen map is written on the main thread.
        event.enqueueWork(() -> MenuScreens.register(ForgeSmallRemovals.POS_MENU.get(), ContainerScreen::new));
    }
}
