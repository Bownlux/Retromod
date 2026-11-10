/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ForgeGui's leftHeight and rightHeight follow Forge 1.20.1's status bars, which mods such as AppleSkin draw against. */
class HeightsEmulationTest {

    private static final String HEIGHTS = "com.retromod.legacyapi.forge.client.LegacyHudHeights";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith("retromod.shared.forge.hud."));
    }

    @AfterEach
    void restore() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith("retromod.shared.forge.hud."));
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    static List<String> hosts() {
        return LegacyHudGame.HOSTS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("20 health, 8 absorption and underwater give the old heights at each Pre and Post, and an overlay's change carries on")
    void oldHeightsAtEachPreAndPost(String host) throws Exception {
        LegacyHudGame game = LegacyHudGame.create(host, dir, transformer, List.of(new LegacyHudGame.Mod("moda", "",
                "event.registerAbove(VanillaGuiOverlay.FOOD_LEVEL.id(), \"thirst\", draws(\"moda:thirst\"));"))).started();
        game.player("absorption", 8f).player("underwater", true);
        // AppleSkin reads rightHeight in Pre(FOOD_LEVEL) and both sides in the Posts; a thirst bar above the food raises it.
        List<String> frame = game.flag("raise moda:thirst").frame();
        assertEquals(List.of(
                "moda pre minecraft:player_health 39/39", "moda pre minecraft:armor_level 59/39",
                "moda pre minecraft:food_level 69/39", "moda pre minecraft:air_level 69/49", "draw health_bar",
                "moda post minecraft:player_health 59/39", "moda post minecraft:armor_level 69/39",
                "moda post minecraft:food_level 69/49", "moda pre moda:thirst 69/49", "draw moda:thirst 69/49",
                "moda post moda:thirst 69/59", "moda post minecraft:air_level 69/69", "moda pre minecraft:mount_health 69/69"),
                LegacyHudCoordinatorTest.between(frame, "moda pre minecraft:player_health", "moda pre minecraft:mount_health"));

        // Riding a horse: no food bar, and its 15 hearts take two rows on the right.
        game.player("vehicle", game.mount(30f)).player("underwater", false).unflag("raise moda:thirst");
        frame = game.frame();
        assertTrue(frame.contains("moda post minecraft:food_level 69/39"), frame.toString());
        assertTrue(frame.contains("moda post minecraft:mount_health 69/59"), frame.toString());

        // A game mode that cannot hurt the player draws no bars and counts none.
        game.flag("creative");
        assertTrue(game.frame().contains("moda post minecraft:mount_health 39/39"));
    }

    @Test
    @DisplayName("Each bar rises as Forge 1.20.1's ForgeGui counted it, packing heart rows closer past two")
    void barsRiseAsForgeGuiCounted() throws Exception {
        assertArrayEquals(new int[]{10, 0}, rise("player_health", survival(20, 0, false, 0, false, 300)));
        assertArrayEquals(new int[]{20, 0}, rise("player_health", survival(20, 8, false, 0, false, 300)));
        // Four rows 8 pixels apart, and the last row's full 10.
        assertArrayEquals(new int[]{34, 0}, rise("player_health", survival(40, 40, false, 0, false, 300)));
        assertArrayEquals(new int[]{37, 0}, rise("player_health", survival(200, 0, false, 0, false, 300)));
        assertArrayEquals(new int[]{10, 0}, rise("armor_level", survival(20, 0, false, 0, false, 300)));
        assertArrayEquals(new int[]{0, 10}, rise("food_level", survival(20, 0, false, 0, false, 300)));
        assertArrayEquals(new int[]{0, 0}, rise("food_level", survival(20, 0, true, 30, false, 300)), "a mount's hearts replace it");
        assertArrayEquals(new int[]{0, 10}, rise("air_level", survival(20, 0, false, 0, true, 300)));
        assertArrayEquals(new int[]{0, 10}, rise("air_level", survival(20, 0, false, 0, false, 299)));
        assertArrayEquals(new int[]{0, 0}, rise("air_level", survival(20, 0, false, 0, false, 300)));
        assertArrayEquals(new int[]{0, 20}, rise("mount_health", survival(20, 0, true, 30, false, 300)));
        assertArrayEquals(new int[]{0, 30}, rise("mount_health", survival(20, 0, true, 100, false, 300)), "at most 30 hearts");
        assertArrayEquals(new int[]{0, 0}, rise("crosshair", survival(20, 0, false, 0, false, 300)));
        Object none = Class.forName(HEIGHTS + "$Status").getField("NONE").get(null);
        assertArrayEquals(new int[]{0, 0}, rise("player_health", none));
    }

    private static Object survival(float maxHealth, float absorption, boolean mounted, float mountMaxHealth, boolean underwater,
            int air) throws Exception {
        Constructor<?> status = Class.forName(HEIGHTS + "$Status").getConstructor(boolean.class, float.class, float.class,
                boolean.class, float.class, boolean.class, int.class, int.class);
        return status.newInstance(true, maxHealth, absorption, mounted, mountMaxHealth, underwater, air, 300);
    }

    private static int[] rise(String path, Object status) throws Exception {
        Class<?> heights = Class.forName(HEIGHTS);
        Method rise = heights.getMethod("rise", String.class, Class.forName(HEIGHTS + "$Status"));
        return (int[]) rise.invoke(null, path, status);
    }
}
