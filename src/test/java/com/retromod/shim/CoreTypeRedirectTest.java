/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class redirect is global, so it must never name a type that is still present.
 *
 * <p>The JEI bridge redirected {@code PoseStack} to {@code GuiGraphics} because JEI had swapped the
 * parameter type on its own {@code draw()}. That is a method signature change, and the method
 * redirect beside it already handled it. The class redirect rewrote every {@code PoseStack} in the
 * whole mod instead, including the 3D stack an entity renderer uses, and the 26.1 move then carried
 * {@code GuiGraphics} on to {@code GuiGraphicsExtractor}. An ordinary {@code pushPose} came out as
 * {@code GuiGraphicsExtractor.pushPose}, which does not exist. Scanning 13 popular mods found it in
 * 12 of them, around 500 call sites, and nothing in the suite noticed.
 *
 * <p>Each type below is present on every host Retromod supports, so redirecting it is wrong by
 * construction, whatever the intent. A signature change wants a method redirect; only a class that
 * is actually gone wants a class redirect.
 */
class CoreTypeRedirectTest {

    /** Types that survive on 26.1 through 26.3 and are pervasive in mod code. */
    private static final List<String> MUST_NOT_BE_REDIRECTED = List.of(
            "com/mojang/blaze3d/vertex/PoseStack",
            "net/minecraft/world/item/ItemStack",
            "net/minecraft/world/level/Level",
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/player/Player",
            "net/minecraft/core/BlockPos",
            "net/minecraft/nbt/CompoundTag",
            "net/minecraft/world/item/Item",
            "net/minecraft/world/level/block/Block");

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("no shim redirects a core type that still exists")
    void noShimRedirectsACoreType() {
        Map<String, String> offenders = new TreeMap<>();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            transformer.clearRedirectsForTesting();
            try {
                shim.registerRedirects(transformer);
            } catch (Throwable notRegisterable) {
                continue; // a shim needing a host it cannot see here is not what this is about
            }
            Map<String, String> redirects = transformer.getClassRedirects();
            for (String core : MUST_NOT_BE_REDIRECTED) {
                String to = redirects.get(core);
                if (to != null) offenders.put(shim.getShimName() + ": " + core, to);
            }
        }
        assertTrue(offenders.isEmpty(),
                "a class redirect rewrites every reference in the mod, so redirecting a type that "
                + "is still present breaks working code everywhere it appears. If an API changed a "
                + "parameter type, redirect that method instead: " + offenders);
    }
}
