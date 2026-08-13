/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge entry point for the Retromod test mod.
 *
 * <p>Compiled against MC 1.20.1 / Forge 47.x. Retromod transforms it forward
 * to whatever MC version the host is running. On a 26.1.2 host this exercises
 * Forge-targeted redirects + the Mojang-name surface.
 *
 * <p>Each test logs {@code [Retromod-Test-Forge] N (description): success/fail}.
 * Grep for {@code [Retromod-Test-Forge]} in the log to see results.
 *
 * <p>Immediate phase only. Forge has equivalent lifecycle events through
 * {@code MinecraftForge.EVENT_BUS} for deferred phases - those would be a
 * follow-up.
 */
@Mod("retromod_test_mod_forge")
public class RetromodTestModForge {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String PREFIX = "[Retromod-Test-Forge]";

    public RetromodTestModForge() {
        runTests();
    }

    private void runTests() {
        LOG.info("{} Starting tests", PREFIX);
        int n = 0, passed = 0;

        n++; passed += check(n, "mod loaded", () -> true);
        n++; passed += check(n, "Component.literal", () -> {
            Component c = Component.literal("hello");
            return c != null && "hello".equals(c.getString());
        });
        n++; passed += check(n, "ResourceLocation 2-arg ctor (factory)", () -> {
            ResourceLocation id = new ResourceLocation("retromod", "test");
            return id != null && "retromod:test".equals(id.toString());
        });
        n++; passed += check(n, "Blocks.STONE static field", () -> Blocks.STONE != null);
        n++; passed += check(n, "Items.DIAMOND static field", () -> Items.DIAMOND != null);
        n++; passed += check(n, "Block.defaultBlockState()", () ->
            Blocks.STONE.defaultBlockState() != null);
        n++; passed += check(n, "BlockState round-trip", () ->
            Blocks.STONE.defaultBlockState().getBlock() == Blocks.STONE);
        // #192 uses the same target-SRG member after starting from the older
        // func_176223_P spelling. The owner-qualified conversion itself is covered by
        // TargetSrgMapperTest; this is the Forge load-time target-member smoke check.
        n++; passed += check(n, "#192 Forge 1.20.1 target SRG block state", () ->
            Blocks.DIRT.defaultBlockState().getBlock() == Blocks.DIRT);

        // The 1.20.1 production jar stores this call as m_21211_. Forge 1.20.6+
        // exposes getUseItem instead, so resolving this call catches the #204 namespace gap.
        n++; passed += check(n, "#204 Forge official-name LivingEntity.getUseItem", () -> {
            try {
                callLegacyGetUseItem(null);
                return false;
            } catch (NullPointerException expected) {
                return true;
            }
        });

        // ─── #85/#115: FMLJavaModLoadingContext Forge->NeoForge bridge ───
        // The idiom nearly every Forge @Mod constructor opens with, to grab its event bus for
        // DeferredRegister. On a Forge host this is the native FML class and the check passes
        // trivially. On a NeoForge host NeoForge DELETED
        // net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext, so Retromod must redirect
        // this reference to the B4 bridge synthetic and EMBED it per-mod
        // (com/retromod/embedded/<mod>/FMLJavaModLoadingContext). If that redirect+embed
        // regresses, this line throws NoClassDefFoundError for the Forge class - exactly the
        // #85/#115 construct crash (Macaw's / Management Wanted on NeoForge 26.2). The guarding
        // try/catch in check() turns a regression into a visible fail line instead of taking the
        // whole mod down, but the reference a native @Mod would crash on is the same one.
        // only meaningful when this Forge mod runs on a NeoForge host (deploy this jar to a
        // NeoForge instance's retromod-input/); on a Forge host it is native. The host-independent
        // redirect assertion lives in the JUnit ForgeEventMigrationTest.
        n++; passed += check(n, "#85/#115 FMLJavaModLoadingContext.get().getModEventBus() bridge", () ->
            net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus() != null);

        // #207: these Forge FML types moved as a package, and 26.x replaced the dist field with
        // an accessor. Class literals keep the checks side-effect free while still forcing linkage.
        n++; passed += check(n, "#207 Forge FML package migration", () ->
            net.minecraftforge.fml.loading.FMLEnvironment.dist != null
                && net.minecraftforge.fml.util.thread.SidedThreadGroups.SERVER != null
                && net.minecraftforge.forgespi.language.IModInfo.class.getName() != null);
        n++; passed += check(n, "#207 Forge spawn egg replacement is loadable", () ->
            net.minecraftforge.common.ForgeSpawnEggItem.class.getName() != null);

        LOG.info("{} SUMMARY: {}/{} passed", PREFIX, passed, n);
    }

    @FunctionalInterface
    private interface BoolCheck { boolean run() throws Throwable; }

    private int check(int n, String description, BoolCheck body) {
        try {
            if (body.run()) {
                LOG.info("{} {} ({}): success", PREFIX, n, description);
                return 1;
            } else {
                LOG.warn("{} {} ({}): fail: assertion was false", PREFIX, n, description);
                return 0;
            }
        } catch (Throwable t) {
            LOG.warn("{} {} ({}): fail: {}: {}", PREFIX, n, description,
                    t.getClass().getSimpleName(), t.getMessage());
            return 0;
        }
    }

    private static ItemStack callLegacyGetUseItem(LivingEntity entity) {
        return entity.getUseItem();
    }
}
