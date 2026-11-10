/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** An item drawn by an old BlockEntityWithoutLevelRenderer, which Minecraft 1.21.4 removed. */
public class LegacyRendererItem extends Item {

    // First read inside the item register event, so the item is built while its id is being registered.
    public static final Item INSTANCE = new LegacyRendererItem();

    private LegacyRendererItem() {
        super(new Item.Properties());
    }

    // Forge calls this on a client only, so a dedicated server never loads the renderer.
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // A renderer built right here, while items register, as Farmer's Delight builds its skillet's.
        EarlyModelsCase.buildDuringRegistration();
        consumer.accept(ItemRendererCases.properties());
    }
}
