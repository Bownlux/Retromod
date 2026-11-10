/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A color handler's foliage color getters on 1.21.4 and newer, which turned them into constants, for every loader. */
class Legacy1214MemberBridgeTest {

    private static final String MOD = "mod/LeafColors";
    private static final String INTERMEDIARY_MOD = "mod/IntermediaryLeafColors";
    private static final List<String> GETTERS =
            List.of("getEvergreenColor", "getBirchColor", "getDefaultColor", "getMangroveColor");

    @TempDir
    static Path dir;

    /** A 1.21.3 mod in Mojang names, as NeoForge and Forge run it, and Fabric after the 26.x remap. */
    private static Map<String, byte[]> mod;
    /** The same mod in the intermediary names a Fabric host before 26.1 runs. */
    private static Map<String, byte[]> intermediaryMod;
    /** 1.21.3's shape: four getters that return the colors. */
    private static Map<String, byte[]> host1213;
    /** 1.21.4 to 26.3's shape: four constants and no getters. */
    private static Map<String, byte[]> host1214;
    private static Map<String, byte[]> intermediaryHost1214;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, byte[]> old = compile(dir.resolve("old"), Map.of(
                "net/minecraft/world/level/FoliageColor.java", """
                        package net.minecraft.world.level;
                        public class FoliageColor {
                            public static int getEvergreenColor() { return -10380959; }
                            public static int getBirchColor() { return -8345771; }
                            public static int getDefaultColor() { return -12012264; }
                            public static int getMangroveColor() { return -7158200; }
                        }""",
                "mod/LeafColors.java", """
                        package mod;
                        import java.util.function.IntSupplier;
                        import net.minecraft.world.level.FoliageColor;
                        public class LeafColors {
                            public static int[] colors() {
                                return new int[] {FoliageColor.getEvergreenColor(), FoliageColor.getBirchColor(),
                                        FoliageColor.getDefaultColor(), FoliageColor.getMangroveColor()};
                            }
                            public static int fallback() {
                                IntSupplier color = FoliageColor::getDefaultColor;
                                return color.getAsInt();
                            }
                        }"""));
        mod = only(old, "mod/");
        host1213 = only(old, "net/");
        host1214 = compile(dir.resolve("host"), Map.of(
                "net/minecraft/world/level/FoliageColor.java", """
                        package net.minecraft.world.level;
                        public class FoliageColor {
                            public static final int FOLIAGE_EVERGREEN = -10380959;
                            public static final int FOLIAGE_BIRCH = -8345771;
                            public static final int FOLIAGE_DEFAULT = -12012264;
                            public static final int FOLIAGE_MANGROVE = -7158200;
                            public static int get(double temperature, double downfall) { return FOLIAGE_DEFAULT; }
                        }"""));
        intermediaryMod = only(compile(dir.resolve("old-intermediary"), Map.of(
                "net/minecraft/class_1926.java", """
                        package net.minecraft;
                        public class class_1926 {
                            public static int method_8342() { return -10380959; }
                            public static int method_8343() { return -8345771; }
                            public static int method_8341() { return -12012264; }
                            public static int method_43717() { return -7158200; }
                        }""",
                "mod/IntermediaryLeafColors.java", """
                        package mod;
                        import net.minecraft.class_1926;
                        public class IntermediaryLeafColors {
                            public static int[] colors() {
                                return new int[] {class_1926.method_8342(), class_1926.method_8343(),
                                        class_1926.method_8341(), class_1926.method_43717()};
                            }
                        }""")), "mod/");
        intermediaryHost1214 = compile(dir.resolve("host-intermediary"), Map.of(
                "net/minecraft/class_1926.java", """
                        package net.minecraft;
                        public class class_1926 {
                            public static final int field_55215 = -10380959;
                            public static final int field_55216 = -8345771;
                            public static final int field_55217 = -12012264;
                            public static final int field_55218 = -7158200;
                        }"""));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 1.21.4 the calls fail as written and return the 1.21.3 colors once bridged")
    void bridgedCallsReturnTheOldColors() throws Throwable {
        Run old = new Run(host1214, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(MOD, "colors")),
                "1.21.4 has no getDefaultColor");

        int[] expected = (int[]) new Run(host1213, mod).call(MOD, "colors");
        Run bridged = new Run(host1214, transform("1.21.4", Legacy1214MemberBridge::register, mod));
        assertArrayEquals(expected, (int[]) bridged.call(MOD, "colors"), "the colors 1.21.3 returned");
        assertEquals(expected[2], bridged.call(MOD, "fallback"), "a method reference to getDefaultColor");
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"neoforge, 1.21.4", "neoforge, 1.21.11", "neoforge, 26.3", "forge, 1.21.4", "forge, 26.2",
            "forge, 26.3", "fabric, 26.1.2", "fabric, 26.3"})
    @DisplayName("each loader's shims for a 1.21.4 or newer host bridge the calls, up to 26.3")
    void everyLoaderBridgesItUpToTheNewestHost(String loader, String host) throws Throwable {
        Map<String, byte[]> out = transform(host, hostShims(loader, host), mod);

        assertEquals(bridged(), calls(out.get(MOD), "colors"), loader + " " + host);
        assertArrayEquals((int[]) new Run(host1213, mod).call(MOD, "colors"),
                (int[]) new Run(host1214, out).call(MOD, "colors"));
    }

    @ParameterizedTest(name = "Fabric {0}")
    @ValueSource(strings = {"1.21.4", "1.21.11"})
    @DisplayName("a Fabric host from 1.21.4 to 1.21.11 bridges the intermediary calls")
    void intermediaryHostsBridgeTheIntermediaryCalls(String host) throws Throwable {
        Run old = new Run(intermediaryHost1214, intermediaryMod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> old.call(INTERMEDIARY_MOD, "colors")), "method_8341 is gone from " + host);

        Map<String, byte[]> out = transform(host, hostShims("fabric", host), intermediaryMod);

        assertEquals(bridged(), calls(out.get(INTERMEDIARY_MOD), "colors"));
        int[] colors = (int[]) new Run(intermediaryHost1214, out).call(INTERMEDIARY_MOD, "colors");
        assertArrayEquals((int[]) new Run(host1213, mod).call(MOD, "colors"), colors);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"neoforge", "forge", "fabric"})
    @DisplayName("a 1.21.3 host, which still has the getters, keeps the calls")
    void olderHostKeepsTheCalls(String loader) {
        List<String> kept = GETTERS.stream().map(name -> Legacy1214MemberBridge.FOLIAGE + "." + name + "()I").toList();
        assertEquals(kept, calls(transform("1.21.3", hostShims(loader, "1.21.3"), mod).get(MOD), "colors"));
        List<String> keptIntermediary = List.of("method_8342", "method_8343", "method_8341", "method_43717").stream()
                .map(id -> Legacy1214MemberBridge.FOLIAGE_INTERMEDIARY + "." + id + "()I").toList();
        assertEquals(keptIntermediary, calls(transform("1.21.3", hostShims(loader, "1.21.3"), intermediaryMod)
                .get(INTERMEDIARY_MOD), "colors"));
    }

    /** The four calls, each sent to the helper's getter of the same name. */
    private static List<String> bridged() {
        return GETTERS.stream().map(name -> Legacy1214MemberBridge.HELPER + "." + name + "()I").toList();
    }

    /** Registers the shims {@code loader}'s entry point registers on {@code host}, with its host flags set. */
    private static Consumer<RetromodTransformer> hostShims(String loader, String host) {
        Set<String> types = switch (loader) {
            case "neoforge" -> Set.of("neoforge", "forge", "common");
            case "forge" -> Set.of("forge", "common");
            default -> Set.of("fabric", "common");
        };
        return transformer -> {
            McReflect.setForceForge(loader.equals("forge"));
            McReflect.setForceNeoForge(loader.equals("neoforge"));
            for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                    ShimRegistration.register(shim, transformer);
                }
            }
        };
    }
}
