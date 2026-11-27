/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Vanishable;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.LoggerFactory;

/** Calls #404, #415 and similar 1.20.1 mods make at load: a shield listener, a Vanishable item and a GeckoLib bone. */
public final class ShieldAndBoneCases {

    private static float heardAmount = -1;

    private ShieldAndBoneCases() {}

    static void register() {
        // Penumbra Phantasm, Cataclysm and Puzzles Lib listen for ShieldBlockEvent from their constructors.
        MinecraftForge.EVENT_BUS.addListener(ShieldAndBoneCases::onShieldBlock);
        MinecraftForge.EVENT_BUS.addListener(ShieldAndBoneCases::onServerStarted);
    }

    private static void onShieldBlock(ShieldBlockEvent event) {
        if (!(event.getEntity() instanceof ArmorStand)) return;
        heardAmount = event.getOriginalBlockedDamage();
        event.setShieldTakesDamage(false);
        event.setCanceled(true);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        log("#415 ShieldBlockEvent reaches an old listener, which spares the shield and cancels the block (server started)",
                shieldResult(event));
        log("#415 An item that implements Vanishable loads (server started)", result(() ->
                Vanishable.class.isAssignableFrom(Class.forName("com.retromod.testmod.forge.VanishingTool"))
                        ? "success" : "fail: the item no longer implements the marker"));
        log("#404 A GeckoLib 4 bone turned through CoreGeoBone (server started)", boneResult());
    }

    private static String shieldResult(ServerStartedEvent event) {
        return result(() -> {
            ArmorStand stand = EntityType.ARMOR_STAND.create(event.getServer().overworld());
            if (stand == null) return "fail: no armor stand was made";
            DamageSource source = stand.damageSources().generic();
            ShieldBlockEvent posted = new ShieldBlockEvent(stand, source, 4.0F);
            MinecraftForge.EVENT_BUS.post(posted);
            if (heardAmount != 4.0F) return "fail: the listener read " + heardAmount;
            if (posted.getDamageSource() != source) return "fail: the event lost its damage source";
            if (posted.shieldTakesDamage()) return "fail: the shield still takes damage";
            return posted.isCanceled() ? "success" : "fail: the block was not cancelled";
        });
    }

    // The bone class is only touched where GeckoLib 4 is installed; GeckoLib 5 has no bone API of this shape.
    private static String boneResult() {
        return result(() -> {
            String version = ModList.get().getModContainerById("geckolib")
                    .map(container -> container.getModInfo().getVersion().toString()).orElse(null);
            if (version == null) return "skipped: GeckoLib is not installed";
            if (!version.startsWith("4.")) return "skipped: GeckoLib " + version + " is not GeckoLib 4";
            return GeckoLibBoneCalls.turnBone();
        });
    }

    private static String result(java.util.concurrent.Callable<String> check) {
        try {
            return check.call();
        } catch (Throwable t) {
            return "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
