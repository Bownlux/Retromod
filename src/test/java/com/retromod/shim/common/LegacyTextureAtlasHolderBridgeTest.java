/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.forge.Forge_1_21_10_to_1_21_11;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.BridgeHarness.*;
import static com.retromod.shim.common.LegacyTextureAtlasHolderBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** JEI's GUI sprites: a mod's own atlas on the TextureAtlasHolder 26.x removed, stitched by the host's sprite loader. */
class LegacyTextureAtlasHolderBridgeTest {

    private static final String SPRITES = "mod/GuiSprites";
    private static final String PROBE = "testhost/Probe";
    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(shared());
        old.put("net/minecraft/client/resources/TextureAtlasHolder.java", """
                package net.minecraft.client.resources;
                import net.minecraft.client.renderer.texture.*;
                import net.minecraft.resources.Identifier;
                public abstract class TextureAtlasHolder implements AutoCloseable {
                    public TextureAtlasHolder(TextureManager manager, Identifier atlas, Identifier info) {}
                    protected TextureAtlasSprite getSprite(Identifier id) { return null; }
                    public void close() {}
                }""");
        // Like JEI's JeiSpriteUploader: its own atlas, named by an atlas info file, and a public getSprite.
        old.put("mod/GuiSprites.java", """
                package mod;
                import net.minecraft.client.renderer.texture.*;
                import net.minecraft.resources.Identifier;
                public class GuiSprites extends net.minecraft.client.resources.TextureAtlasHolder {
                    public GuiSprites(TextureManager manager) {
                        super(manager, Identifier.fromNamespaceAndPath("jei", "textures/atlas/gui.png"), Identifier.fromNamespaceAndPath("jei", "gui"));
                    }
                    @Override public TextureAtlasSprite getSprite(Identifier id) { return super.getSprite(id); }
                    public static Object build(Object manager) { return new GuiSprites((TextureManager) manager); }
                    public static String sprite(Object holder, String path) {
                        return String.valueOf(((GuiSprites) holder).getSprite(Identifier.fromNamespaceAndPath("jei", path)));
                    }
                }""");
        // A holder that names one of Minecraft's own atlases, as a copy of its painting atlas manager would.
        old.put("mod/PaintingSprites.java", """
                package mod;
                import net.minecraft.client.renderer.texture.*;
                import net.minecraft.resources.Identifier;
                public class PaintingSprites extends net.minecraft.client.resources.TextureAtlasHolder {
                    public PaintingSprites(TextureManager manager) {
                        super(manager, Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/paintings.png"),
                                Identifier.fromNamespaceAndPath("minecraft", "paintings"));
                    }
                    public static Object build(Object manager) { return new PaintingSprites((TextureManager) manager); }
                }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        host = compile(dir.resolve("host"), hostSources());
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The old holder registers its atlas, stitches the sprites its atlas info lists on reload, and getSprite finds them")
    void holderStitchesItsOwnAtlas() throws Throwable {
        Run old = new Run(host, mod);
        assertInstanceOf(NoClassDefFoundError.class, assertThrows(Throwable.class, () -> old.call(PROBE, "build")),
                "26.x has no TextureAtlasHolder");

        Run game = new Run(host, transform("26.2", LegacyTextureAtlasHolderBridge::register, mod));
        Object holder = game.call(PROBE, "build");
        assertEquals("[register jei:textures/atlas/gui.png]", game.call(PROBE, "log"), "the atlas is a texture of its own");
        assertEquals("missing", game.call(SPRITES, "sprite", holder, "slot"), "nothing is stitched before the first reload");

        game.call(PROBE, "reload", holder);
        assertEquals("[register jei:textures/atlas/gui.png, stitch jei:gui mip 0 sections 0 on prepare, barrier,"
                + " upload jei:textures/atlas/gui.png on apply]", game.call(PROBE, "log"),
                "loaded and stitched while preparing, uploaded after every listener prepared");
        assertEquals("jei:slot", game.call(SPRITES, "sprite", holder, "slot"));
        assertEquals("missing", game.call(SPRITES, "sprite", holder, "absent"), "a sprite the atlas info does not list");
    }

    @Test
    @DisplayName("Each loader's 26.1 shim rebases the holder on 26.1 to 26.3")
    void everyLoaderOn26() {
        String rebuilt = REBUILT;
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1())) {
            for (String target : List.of("26.1", "26.2", "26.3")) {
                ClassNode node = new ClassNode();
                new ClassReader(transform(target, shim::registerRedirects, mod).get(SPRITES)).accept(node, 0);
                assertEquals(rebuilt, node.superName, shim.getShimName() + " on " + target);
            }
        }
    }

    @Test
    @DisplayName("A holder naming one of Minecraft's own atlases gets an id of its own, and Minecraft's atlas is left alone")
    void minecraftAtlasIdIsNotTaken() throws Throwable {
        Run game = new Run(host, transform("26.2", LegacyTextureAtlasHolderBridge::register, mod));
        game.call("mod/PaintingSprites", "build", game.call(PROBE, "manager"));
        assertEquals("[register retromod:legacy_atlas/minecraft/textures/atlas/paintings.png]", game.call(PROBE, "log"));
    }

    @Test
    @DisplayName("The 1.21.11 layers rebase the holder on 1.21.11, which lacks the class, and no earlier host takes them")
    void hostsFrom1_21_11() {
        for (VersionShim shim : List.<VersionShim>of(new NeoForge_1_21_10_to_1_21_11(), new Forge_1_21_10_to_1_21_11())) {
            ClassNode node = new ClassNode();
            new ClassReader(transform("1.21.11", shim::registerRedirects, mod).get(SPRITES)).accept(node, 0);
            assertEquals(REBUILT, node.superName, shim.getShimName());
            // The rebuilt holder names Identifier, which 1.21.11 introduced.
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.21.10"), shim.getShimName() + " on 1.21.10");
        }
    }

    @Test
    @DisplayName("The host gate takes a host without the old class whose sprite loader stitches from code")
    void hostShape() {
        assertTrue(hostStitchesFromCode(shapes(null, loader(true))), "the 26.x shape");
        assertTrue(hostStitchesFromCode(name -> null), "nothing readable: the 26.1 shim stands for the host");
        assertFalse(hostStitchesFromCode(shapes(new ClassNode(), loader(true))), "a host that still has the class keeps it");
        assertFalse(hostStitchesFromCode(shapes(null, loader(false))), "a sprite loader of another shape");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
            "net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar",
            "net/neoforged/minecraft-client-patched/26.1.2.60-beta/minecraft-client-patched-26.1.2.60-beta.jar",
            "net/neoforged/minecraft-client-patched/26.2.0.0-beta/minecraft-client-patched-26.2.0.0-beta.jar",
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
            "com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar",
            "com/mojang/minecraft/26.2/minecraft-26.2-client.jar",
            "com/mojang/minecraft/26.3/minecraft-26.3-client.jar"})
    @DisplayName("Every 1.21.11 and 26.x client jar on this machine passes the host gate")
    void realHostsPassTheGate(String jar) throws Exception {
        Path file = Path.of(LIBRARIES + jar);
        Assumptions.assumeTrue(Files.isRegularFile(file), "no " + file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Function<String, ClassNode> read = name -> {
                try {
                    var entry = zip.getEntry(name + ".class");
                    if (entry == null) return null;
                    ClassNode node = new ClassNode();
                    new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                    return node;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            assertTrue(hostStitchesFromCode(read), jar);
        }
    }

    private static Function<String, ClassNode> shapes(ClassNode old, ClassNode loader) {
        return name -> name.equals(OLD) ? old : name.equals(SPRITE_LOADER) ? loader : null;
    }

    private static ClassNode loader(boolean current) {
        ClassNode node = new ClassNode();
        node.name = SPRITE_LOADER;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "loadAndStitch", current ? LOAD_AND_STITCH_DESC : "()V", null, null));
        return node;
    }

    // Identifier, textures and the reload interfaces, the same on the old and new side.
    private static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public record Identifier(String namespace, String path) {
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    public String getNamespace() { return namespace; }
                    public String getPath() { return path; }
                    @Override public String toString() { return namespace + ":" + path; }
                }""");
        sources.put("net/minecraft/client/renderer/texture/TextureAtlasSprite.java", """
                package net.minecraft.client.renderer.texture;
                public record TextureAtlasSprite(String name) { @Override public String toString() { return name; } }""");
        sources.put("net/minecraft/client/renderer/texture/AbstractTexture.java", """
                package net.minecraft.client.renderer.texture;
                public abstract class AbstractTexture implements AutoCloseable { public void close() {} }""");
        sources.put("net/minecraft/client/renderer/texture/TextureManager.java", """
                package net.minecraft.client.renderer.texture;
                public class TextureManager {
                    public void register(net.minecraft.resources.Identifier id, AbstractTexture texture) { testhost.Log.note("register " + id); }
                }""");
        sources.put("testhost/Log.java", """
                package testhost;
                public final class Log {
                    public static final java.util.List<String> SEEN = new java.util.ArrayList<>();
                    public static void note(String note) { SEEN.add(note); }
                }""");
        return sources;
    }

    /** The 26.x atlas: the sprite loader stitches what the atlas info lists, and the atlas uploads it. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = shared();
        sources.put("net/minecraft/client/renderer/texture/TextureAtlas.java", """
                package net.minecraft.client.renderer.texture;
                import net.minecraft.resources.Identifier;
                public class TextureAtlas extends AbstractTexture {
                    private final Identifier location;
                    private java.util.Map<Identifier, TextureAtlasSprite> sprites = java.util.Map.of();
                    public TextureAtlas(Identifier location) { this.location = location; }
                    public Identifier location() { return location; }
                    public void upload(SpriteLoader.Preparations preparations) {
                        testhost.Log.note("upload " + location + " on " + Thread.currentThread().getName());
                        sprites = preparations.regions();
                    }
                    public TextureAtlasSprite getSprite(Identifier id) { return sprites.getOrDefault(id, new TextureAtlasSprite("missing")); }
                    public void clearTextureData() { sprites = java.util.Map.of(); }
                }""");
        sources.put("net/minecraft/client/renderer/texture/SpriteLoader.java", """
                package net.minecraft.client.renderer.texture;
                import java.util.*;
                import java.util.concurrent.*;
                import net.minecraft.resources.Identifier;
                public class SpriteLoader {
                    private SpriteLoader() {}
                    public static SpriteLoader create(TextureAtlas atlas) { return new SpriteLoader(); }
                    // The atlas info jei:gui lists one sprite, jei:slot.
                    public CompletableFuture<Preparations> loadAndStitch(net.minecraft.server.packs.resources.ResourceManager manager, Identifier info,
                            int mip, Executor executor, Set<net.minecraft.server.packs.metadata.MetadataSectionType<?>> sections) {
                        return CompletableFuture.supplyAsync(() -> {
                            testhost.Log.note("stitch " + info + " mip " + mip + " sections " + sections.size() + " on " + Thread.currentThread().getName());
                            Map<Identifier, TextureAtlasSprite> regions = new HashMap<>();
                            if (info.equals(Identifier.fromNamespaceAndPath("jei", "gui"))) {
                                regions.put(Identifier.fromNamespaceAndPath("jei", "slot"), new TextureAtlasSprite("jei:slot"));
                            }
                            return new Preparations(regions, CompletableFuture.completedFuture(null));
                        }, executor);
                    }
                    public record Preparations(Map<Identifier, TextureAtlasSprite> regions, CompletableFuture<Void> readyForUpload) {}
                }""");
        sources.put("net/minecraft/server/packs/resources/ResourceManager.java",
                "package net.minecraft.server.packs.resources; public interface ResourceManager {}");
        sources.put("net/minecraft/server/packs/metadata/MetadataSectionType.java",
                "package net.minecraft.server.packs.metadata; public final class MetadataSectionType<T> {}");
        sources.put("net/minecraft/server/packs/resources/PreparableReloadListener.java", """
                package net.minecraft.server.packs.resources;
                import java.util.concurrent.*;
                public interface PreparableReloadListener {
                    CompletableFuture<Void> reload(SharedState state, Executor prepare, PreparationBarrier barrier, Executor apply);
                    final class SharedState {
                        private final ResourceManager manager;
                        public SharedState(ResourceManager manager) { this.manager = manager; }
                        public ResourceManager resourceManager() { return manager; }
                    }
                    interface PreparationBarrier { <T> CompletableFuture<T> wait(T value); }
                }""");
        // A resource reload, with named threads for the prepare and apply steps.
        sources.put("testhost/Probe.java", """
                package testhost;
                import java.util.concurrent.*;
                import net.minecraft.server.packs.resources.PreparableReloadListener;
                public final class Probe {
                    public static Object build() throws Exception {
                        return Class.forName("mod.GuiSprites", true, Probe.class.getClassLoader())
                                .getMethod("build", Object.class).invoke(null, new net.minecraft.client.renderer.texture.TextureManager());
                    }
                    public static void reload(Object holder) throws Exception {
                        ExecutorService prepare = Executors.newSingleThreadExecutor(r -> new Thread(r, "prepare"));
                        ExecutorService apply = Executors.newSingleThreadExecutor(r -> new Thread(r, "apply"));
                        try {
                            PreparableReloadListener.PreparationBarrier barrier = new PreparableReloadListener.PreparationBarrier() {
                                public <T> CompletableFuture<T> wait(T value) { Log.note("barrier"); return CompletableFuture.completedFuture(value); }
                            };
                            ((PreparableReloadListener) holder).reload(new PreparableReloadListener.SharedState(new net.minecraft.server.packs.resources.ResourceManager() {}),
                                    prepare, barrier, apply).get(10, TimeUnit.SECONDS);
                        } finally {
                            prepare.shutdownNow();
                            apply.shutdownNow();
                        }
                    }
                    public static String log() { return Log.SEEN.toString(); }
                    public static Object manager() { return new net.minecraft.client.renderer.texture.TextureManager(); }
                }""");
        return sources;
    }
}
