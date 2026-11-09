/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.TextureAtlasHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.LoggerFactory;

/** A mod's own sprite atlas on TextureAtlasHolder, as JEI's GUI sprites are, which 26.x removed. Client only. */
final class LegacyAtlasCase {

    private static final String BUILT = "An old TextureAtlasHolder registers as a reload listener (client reload listeners event)";
    private static final String STITCHED = "Its atlas stitches the sprite its atlas info lists (first client tick in a world)";
    private static final ResourceLocation APPLE = new ResourceLocation("minecraft", "item/apple");

    private static Sprites sprites;
    private static boolean reported;

    private LegacyAtlasCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(LegacyAtlasCase::onReloadListeners);
            MinecraftForge.EVENT_BUS.addListener(LegacyAtlasCase::onClientTick);
        } catch (Throwable t) {
            log("Old atlas checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // JEI builds its holder here and hands it to the game as a reload listener.
    private static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        try {
            sprites = new Sprites(Minecraft.getInstance().getTextureManager());
            event.registerReloadListener(sprites);
            log(BUILT, "success");
        } catch (Throwable t) {
            log(BUILT, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reported || Minecraft.getInstance().level == null) return;
        reported = true;
        if (sprites == null) {
            log(STITCHED, "fail: no holder was built");
            return;
        }
        try {
            ResourceLocation name = sprites.getSprite(APPLE).contents().name();
            log(STITCHED, name.equals(APPLE) ? "success" : "fail: found " + name);
        } catch (Throwable t) {
            log(STITCHED, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** Like JEI's sprite uploader: its own atlas texture, its atlas info file, and a public getSprite. */
    static final class Sprites extends TextureAtlasHolder {
        Sprites(TextureManager manager) {
            super(manager, new ResourceLocation("retromod_test_mod_forge", "textures/atlas/legacy_test.png"),
                    new ResourceLocation("retromod_test_mod_forge", "legacy_test"));
        }

        @Override
        public TextureAtlasSprite getSprite(ResourceLocation location) {
            return super.getSprite(location);
        }
    }
}
