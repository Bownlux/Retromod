/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A NeoForge 1.21.1 mod's own placement filter, modifier and repeating placement, shaped like Farmer's Delight's, translated
 * for 26.3 by the CLI and run on the real 26.3 classes: each type is the codec 26.3 registers, reads its modifier, and the
 * modifier places the positions its old code makes.
 */
class LegacyPlacementModifierHostTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    /** The 26.3 client and the libraries its bootstrap loads. */
    private static final List<String> HOST = List.of("com/mojang/minecraft/26.3/minecraft-26.3-client.jar",
            "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar", "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
            "com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar", "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar",
            "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar", "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            "com/mojang/logging/1.7.12/logging-1.7.12.jar", "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar",
            "com/mojang/authlib/10.0.77/authlib-10.0.77.jar", "org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar",
            "io/netty/netty-buffer/4.2.16.Final/netty-buffer-4.2.16.Final.jar", "io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar",
            "io/netty/netty-codec-base/4.2.16.Final/netty-codec-base-4.2.16.Final.jar",
            "io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar",
            "io/netty/netty-handler/4.2.16.Final/netty-handler-4.2.16.Final.jar", "org/joml/joml/1.10.9/joml-1.10.9.jar",
            "com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar", "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
            "com/ibm/icu/icu4j/78.3/icu4j-78.3.jar", "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar");

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("each old type is the registered codec of its modifier, and each modifier places what its old code makes")
    void oldModifiersPlaceOnTheRealHost() throws Exception {
        List<URL> host = new ArrayList<>();
        for (String jar : HOST) {
            Path file = LIBRARIES.resolve(jar);
            assumeTrue(Files.isRegularFile(file), "no local " + file.getFileName() + "; the 26.3 game jars are local test material");
            host.add(file.toUri().toURL());
        }
        Path translated = translate(LIBRARIES.resolve(HOST.get(1)));
        host.add(0, translated.toUri().toURL());

        try (URLClassLoader game = new URLClassLoader(host.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            bootstrap(game);
            Class<?> modifierType = Class.forName("net.minecraft.world.level.levelgen.placement.PlacementModifier", true, game);
            Class<?> mapCodec = Class.forName("com.mojang.serialization.MapCodec", true, game);
            Class<?> types = game.loadClass("mod.Types");
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos", true, game);
            Class<?> randomSource = Class.forName("net.minecraft.util.RandomSource", true, game);
            Method modify = modifierType.getMethod("modify",
                    Class.forName("net.minecraft.world.level.levelgen.placement.PlacementContext", false, game),
                    randomSource, blockPos, Consumer.class);
            Object origin = blockPos.getConstructor(int.class, int.class, int.class).newInstance(4, 70, -2);
            Object random = randomSource.getMethod("create", long.class).invoke(null, 5L);

            for (Object[] expected : new Object[][] {{"FILTER", 1}, {"TWICE", 2}, {"THRICE", 3}}) {
                Object type = types.getField((String) expected[0]).get(null);
                assertTrue(mapCodec.isInstance(type), expected[0] + " is a codec 26.3 can register");
                assertTrue(type.getClass().getName().endsWith(".LegacyPlacementModifierType"),
                        expected[0] + " is a placement modifier type of its own, which instanceof tells from other codecs");
                Object modifier = parse(game, type);
                assertTrue(modifierType.isInstance(modifier), expected[0] + " reads a 26.3 placement modifier");
                assertSame(type, modifierType.getMethod("codec").invoke(modifier),
                        expected[0] + ": the modifier names the registered codec, so the host can write it back");
                List<Object> placed = new ArrayList<>();
                // The modifiers here never read the context, so none is built.
                modify.invoke(modifier, null, random, origin, (Consumer<Object>) placed::add);
                assertEquals(expected[1], placed.size(), expected[0] + " places as many positions as its old code made");
                for (Object position : placed) assertEquals(origin, position);
            }

            // A block state provider: its type is the registered codec, and the host's getState runs the old one.
            Class<?> providerType = Class.forName("net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider", true, game);
            Class<?> level = Class.forName("net.minecraft.world.level.LevelAccessor", false, game);
            Object stone = blockState(game, "STONE");
            game.loadClass("mod.StoneProvider").getField("STATE").set(null, stone);
            Object type = types.getField("STONE").get(null);
            assertTrue(mapCodec.isInstance(type), "the provider type is a codec 26.3 can register");
            Object provider = parse(game, type);
            assertTrue(providerType.isInstance(provider));
            assertSame(type, providerType.getMethod("codec").invoke(provider));
            assertSame(stone, providerType.getMethod("getState", level, randomSource, blockPos).invoke(provider, null, random, origin),
                    "the host's getState answers with the old one");

            // Old code asks any provider for a state without a level: its own as before, a simple host one too.
            Method pick = game.loadClass("mod.Pick").getMethod("pick", providerType, randomSource, blockPos);
            assertSame(stone, pick.invoke(null, provider, random, origin));
            Object dirt = blockState(game, "DIRT");
            Object simple = providerType.getMethod("of", Class.forName("net.minecraft.world.level.block.state.BlockState", false, game))
                    .invoke(null, dirt);
            assertSame(dirt, pick.invoke(null, simple, random, origin));
        }
    }

    /** {@code Blocks.<name>.defaultBlockState()} on the bootstrapped host. */
    private static Object blockState(ClassLoader game, String name) throws Exception {
        Object block = Class.forName("net.minecraft.world.level.block.Blocks", true, game).getField(name).get(null);
        return Class.forName("net.minecraft.world.level.block.Block", true, game).getMethod("defaultBlockState").invoke(block);
    }

    /** Reads an empty JSON object with {@code type}, through the codec the host builds from a map codec. */
    private static Object parse(ClassLoader game, Object type) throws Exception {
        Object codec = Class.forName("com.mojang.serialization.MapCodec", true, game).getMethod("codec").invoke(type);
        Class<?> ops = Class.forName("com.mojang.serialization.JsonOps", true, game);
        Object json = Class.forName("com.google.gson.JsonObject", true, game).getConstructor().newInstance();
        Method parse = Class.forName("com.mojang.serialization.Decoder", true, game)
                .getMethod("parse", Class.forName("com.mojang.serialization.DynamicOps", true, game), Object.class);
        Object result = parse.invoke(codec, ops.getField("INSTANCE").get(null), json);
        return Class.forName("com.mojang.serialization.DataResult", true, game).getMethod("getOrThrow").invoke(result);
    }

    private static void bootstrap(ClassLoader game) throws Exception {
        // Bootstrap routes the standard streams into the game's logger, which this test JVM keeps using.
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            Class.forName("net.minecraft.SharedConstants", true, game).getMethod("tryDetectVersion").invoke(null);
            Class.forName("net.minecraft.server.Bootstrap", true, game).getMethod("bootStrap").invoke(null);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    /** The fixture mod, translated for NeoForge 26.3 by the CLI, so the embedded copies are what runs. */
    private Path translate(Path dfu) throws Exception {
        Map<String, byte[]> classes = BridgeHarness.only(BridgeHarness.compile(dir.resolve("compile"), sources121(), dfu.toString()), "mod/");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                + "modId=\"placed\"\nversion=\"1.0.0\"\n[[dependencies.placed]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        classes.forEach((name, bytes) -> jar.put(name + ".class", bytes));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("placed.jar");
        Files.write(input, LoaderApiFixtures.jar(jar));
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path output = Files.createDirectories(dir.resolve("out")).resolve("placed.jar");
        CliHarness.transform(input, output, "26.3");
        assertTrue(Files.isRegularFile(output), "the CLI must write " + output);
        return output;
    }

    /** The 1.21.1 placement classes, and a mod that extends them and makes its types as Farmer's Delight does. */
    private static Map<String, String> sources121() {
        String placement = "net/minecraft/world/level/levelgen/placement/";
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        sources.put("net/minecraft/util/RandomSource.java", "package net.minecraft.util; public interface RandomSource {}");
        sources.put(placement + "PlacementContext.java", "package net.minecraft.world.level.levelgen.placement; public class PlacementContext {}");
        sources.put(placement + "PlacementModifierType.java", """
                package net.minecraft.world.level.levelgen.placement;
                public interface PlacementModifierType<P extends PlacementModifier> { com.mojang.serialization.MapCodec<P> codec(); }""");
        sources.put(placement + "PlacementModifier.java", """
                package net.minecraft.world.level.levelgen.placement;
                import java.util.stream.Stream;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                public abstract class PlacementModifier {
                    public abstract Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin);
                    public abstract PlacementModifierType<?> type();
                }""");
        sources.put(placement + "PlacementFilter.java", """
                package net.minecraft.world.level.levelgen.placement;
                import java.util.stream.Stream;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                public abstract class PlacementFilter extends PlacementModifier {
                    public final Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin) { return null; }
                    protected abstract boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos origin);
                }""");
        sources.put(placement + "RepeatingPlacement.java", """
                package net.minecraft.world.level.levelgen.placement;
                import java.util.stream.Stream;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                public abstract class RepeatingPlacement extends PlacementModifier {
                    protected abstract int count(RandomSource random, BlockPos origin);
                    public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin) { return null; }
                }""");
        String providers = "net/minecraft/world/level/levelgen/feature/stateproviders/";
        sources.put("net/minecraft/world/level/block/state/BlockState.java",
                "package net.minecraft.world.level.block.state; public class BlockState {}");
        sources.put(providers + "BlockStateProviderType.java", """
                package net.minecraft.world.level.levelgen.feature.stateproviders;
                public class BlockStateProviderType<P extends BlockStateProvider> {
                    public BlockStateProviderType(com.mojang.serialization.MapCodec<P> codec) {}
                    public com.mojang.serialization.MapCodec<P> codec() { return null; }
                }""");
        sources.put(providers + "BlockStateProvider.java", """
                package net.minecraft.world.level.levelgen.feature.stateproviders;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                public abstract class BlockStateProvider {
                    protected abstract BlockStateProviderType<?> type();
                    public abstract BlockState getState(RandomSource random, BlockPos pos);
                }""");
        sources.put("mod/StoneProvider.java", """
                package mod;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.levelgen.feature.stateproviders.*;
                public class StoneProvider extends BlockStateProvider {
                    public static BlockState STATE;
                    @Override protected BlockStateProviderType<?> type() { return Types.STONE; }
                    @Override public BlockState getState(RandomSource random, BlockPos pos) { return STATE; }
                }""");
        sources.put("mod/Pick.java", """
                package mod;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
                public final class Pick {
                    public static BlockState pick(BlockStateProvider provider, RandomSource random, BlockPos pos) {
                        return provider.getState(random, pos);
                    }
                }""");
        sources.put("mod/ShapeFilter.java", """
                package mod;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.levelgen.placement.*;
                public class ShapeFilter extends PlacementFilter {
                    @Override protected boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos origin) { return origin != null; }
                    @Override public PlacementModifierType<?> type() { return Types.FILTER; }
                }""");
        sources.put("mod/Twice.java", """
                package mod;
                import java.util.stream.Stream;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.levelgen.placement.*;
                public class Twice extends PlacementModifier {
                    @Override public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos origin) {
                        return Stream.of(origin, origin);
                    }
                    @Override public PlacementModifierType<?> type() { return Types.TWICE; }
                }""");
        sources.put("mod/Thrice.java", """
                package mod;
                import net.minecraft.core.BlockPos;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.levelgen.placement.*;
                public class Thrice extends RepeatingPlacement {
                    @Override protected int count(RandomSource random, BlockPos origin) { return 3; }
                    @Override public PlacementModifierType<?> type() { return Types.THRICE; }
                }""");
        sources.put("mod/Types.java", """
                package mod;
                import com.mojang.serialization.MapCodec;
                import net.minecraft.world.level.levelgen.placement.*;
                public final class Types {
                    public static final PlacementModifierType<ShapeFilter> FILTER = convert(MapCodec.unit(new ShapeFilter()));
                    public static final PlacementModifierType<Twice> TWICE = convert(MapCodec.unit(new Twice()));
                    public static final PlacementModifierType<Thrice> THRICE = convert(MapCodec.unit(new Thrice()));
                    public static final net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType<StoneProvider> STONE =
                            new net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType<>(MapCodec.unit(new StoneProvider()));
                    private static <P extends PlacementModifier> PlacementModifierType<P> convert(MapCodec<P> codec) {
                        return () -> codec;
                    }
                }""");
        return sources;
    }
}
