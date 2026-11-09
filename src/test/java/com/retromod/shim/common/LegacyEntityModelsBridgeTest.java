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
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
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
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.BridgeHarness.*;
import static com.retromod.shim.common.LegacyEntityModelsBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** An old renderer that asks for Minecraft's entity models while items register, before a 26.x game has its model manager. */
class LegacyEntityModelsBridgeTest {

    private static final String RENDERER = "mod/SkilletRenderer";
    private static final String PROBE = "testhost/Probe";
    private static final String GETTER_CALL = MINECRAFT + "." + GETTER + GETTER_DESC;
    private static final String HELPER_CALL = MODELS + "." + HELPER + HELPER_DESC;
    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    /** The 26.x shape: the getter asks a model manager the game creates after mods construct, which swaps its set on reload. */
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> sources = new HashMap<>(hostSources());
        sources.put("mod/SkilletRenderer.java", """
                package mod;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.model.geom.EntityModelSet;
                import net.minecraft.client.model.geom.ModelLayerLocation;
                // Like Farmer's Delight's skillet renderer, built from initializeClient while items register.
                public class SkilletRenderer {
                    public static final ModelLayerLocation PLAYER = new ModelLayerLocation("minecraft:player", "main");
                    private final EntityModelSet models;
                    public SkilletRenderer() { this.models = Minecraft.getInstance().getEntityModels(); }
                    public static Object build() { return new SkilletRenderer(); }
                    // What the set it kept answers, as onResourceManagerReload or a draw would ask it later.
                    public static String bake(Object renderer) {
                        try { return ((SkilletRenderer) renderer).models.bakeLayer(PLAYER).bakedFrom; }
                        catch (RuntimeException e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
                    }
                }""");
        Map<String, byte[]> compiled = compile(dir.resolve("shapes"), sources);
        mod = only(compiled, "mod/");
        host = new HashMap<>(compiled);
        host.keySet().removeIf(name -> name.startsWith("mod/"));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A renderer built before the model manager exists throws as written, and once bridged bakes like the game's set")
    void earlyRendererBakesLikeTheHostSet() throws Throwable {
        Run old = new Run(host, mod);
        old.call(PROBE, "startGame");
        assertInstanceOf(NullPointerException.class, assertThrows(Throwable.class, () -> old.call(RENDERER, "build")),
                "26.x asks a model manager that does not exist yet");

        Run game = new Run(host, transform("26.2", LegacyEntityModelsBridge::register, mod));
        game.call(PROBE, "startGame");
        Object early = game.call(RENDERER, "build");
        assertEquals("IllegalArgumentException: No model for layer minecraft:player#main", game.call(RENDERER, "bake", early),
                "before the game has models it has no layers, as an empty set has none");

        game.call(PROBE, "createModelManager");
        assertEquals(game.call(PROBE, "hostBake"), game.call(RENDERER, "bake", early), "with the empty set the manager starts with");
        game.call(PROBE, "reload", "first");
        assertEquals("player from the first reload", game.call(PROBE, "hostBake"));
        assertEquals("player from the first reload", game.call(RENDERER, "bake", early), "the set it kept bakes from the loaded set");
        game.call(PROBE, "reload", "second");
        assertEquals("player from the second reload", game.call(RENDERER, "bake", early),
                "a later reload replaces the game's set, and the kept set follows it");
        assertEquals("player from the second reload", game.call(RENDERER, "bake", game.call(RENDERER, "build")),
                "a renderer built after loading bakes the same");
    }

    @Test
    @DisplayName("A mod built for 1.21.4 or later keeps the call, which it already makes only once the game has models")
    void newerModsKeepTheCall() {
        Map<String, byte[]> out = transform("26.2", t -> {
            LegacyEntityModelsBridge.register(t);
            t.setModSourceVersion("1.21.4");
        }, mod);
        assertTrue(calls(out.get(RENDERER), "<init>").contains(GETTER_CALL));
        RetromodTransformer.getInstance().setModSourceVersion("1.20.1");
        assertTrue(calls(transform("26.2", LegacyEntityModelsBridge::register, mod).get(RENDERER), "<init>").contains(HELPER_CALL));
    }

    @Test
    @DisplayName("Each loader's 26.1 shim bridges the call on 26.1 to 26.3, and no shim a 1.21.11 host takes does")
    void everyLoaderOn26() {
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1())) {
            for (String target : List.of("26.1", "26.2", "26.3")) {
                Map<String, byte[]> out = transform(target, shim::registerRedirects, mod);
                assertTrue(calls(out.get(RENDERER), "<init>").contains(HELPER_CALL), shim.getShimName() + " on " + target);
            }
        }
        for (String loader : List.of("fabric", "forge", "neoforge")) {
            Map<String, byte[]> out = transform("1.21.11", t -> {
                for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
                    if (shim.getModLoaderType().equals(loader) && ShimRegistry.isAvailableOnHost(shim, "1.21.11")) {
                        ShimRegistration.register(shim, t);
                    }
                }
            }, mod);
            assertTrue(calls(out.get(RENDERER), "<init>").contains(GETTER_CALL), loader + " on 1.21.11");
        }
    }

    @Test
    @DisplayName("The host gate takes the 26.x model manager and an extendable set, and reads no client class as a server or offline run")
    void hostShape() {
        assertTrue(hostManagesEntityModels(shapes(set(0, true), manager(true))), "the 26.x shape");
        assertTrue(hostManagesEntityModels(name -> null), "nothing readable: the 26.1 shim stands for the host");
        assertFalse(hostManagesEntityModels(shapes(set(Opcodes.ACC_FINAL, true), manager(true))), "a final set cannot be extended");
        assertFalse(hostManagesEntityModels(shapes(set(0, false), manager(true))), "no public constructor taking the layers");
        assertFalse(hostManagesEntityModels(shapes(set(0, true), manager(false))), "a manager without entityModels, as before 1.21.4");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
            "net/neoforged/minecraft-client-patched/26.1.2.60-beta/minecraft-client-patched-26.1.2.60-beta.jar",
            "net/neoforged/minecraft-client-patched/26.2.0.0-beta/minecraft-client-patched-26.2.0.0-beta.jar",
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
            "com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar",
            "com/mojang/minecraft/26.2/minecraft-26.2-client.jar",
            "com/mojang/minecraft/26.3/minecraft-26.3-client.jar"})
    @DisplayName("Every 26.x client jar on this machine passes the host gate")
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
            assertTrue(hostManagesEntityModels(read), jar);
        }
    }

    private static Function<String, ClassNode> shapes(ClassNode set, ClassNode manager) {
        return name -> name.equals(SET) ? set : name.equals(MODEL_MANAGER) ? manager : null;
    }

    private static ClassNode set(int extraAccess, boolean mapConstructor) {
        ClassNode node = new ClassNode();
        node.name = SET;
        node.access = Opcodes.ACC_PUBLIC | extraAccess;
        node.methods.add(new MethodNode(mapConstructor ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE, "<init>", "(Ljava/util/Map;)V", null, null));
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "bakeLayer",
                "(Lnet/minecraft/client/model/geom/ModelLayerLocation;)Lnet/minecraft/client/model/geom/ModelPart;", null, null));
        return node;
    }

    private static ClassNode manager(boolean entityModels) {
        ClassNode node = new ClassNode();
        node.name = MODEL_MANAGER;
        if (entityModels) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "entityModels", "()Ljava/util/function/Supplier;", null, null));
        return node;
    }

    /** Minecraft 26.2's entity model classes, behaving as the game does around mod loading and resource reloads. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/client/model/geom/ModelLayerLocation.java", """
                package net.minecraft.client.model.geom;
                public record ModelLayerLocation(String model, String layer) {
                    @Override public String toString() { return model + "#" + layer; }
                }""");
        sources.put("net/minecraft/client/model/geom/ModelPart.java", """
                package net.minecraft.client.model.geom;
                public final class ModelPart {
                    public final String bakedFrom;
                    public ModelPart(String bakedFrom) { this.bakedFrom = bakedFrom; }
                }""");
        sources.put("net/minecraft/client/model/geom/builders/LayerDefinition.java", """
                package net.minecraft.client.model.geom.builders;
                public class LayerDefinition {
                    private final String name;
                    public LayerDefinition(String name) { this.name = name; }
                    public net.minecraft.client.model.geom.ModelPart bakeRoot() { return new net.minecraft.client.model.geom.ModelPart(name); }
                }""");
        sources.put("net/minecraft/client/model/geom/EntityModelSet.java", """
                package net.minecraft.client.model.geom;
                import java.util.Map;
                import net.minecraft.client.model.geom.builders.LayerDefinition;
                public class EntityModelSet {
                    public static final EntityModelSet EMPTY = new EntityModelSet(Map.of());
                    private final Map<ModelLayerLocation, LayerDefinition> roots;
                    public EntityModelSet(Map<ModelLayerLocation, LayerDefinition> roots) { this.roots = roots; }
                    public ModelPart bakeLayer(ModelLayerLocation layer) {
                        LayerDefinition definition = roots.get(layer);
                        if (definition == null) throw new IllegalArgumentException("No model for layer " + layer);
                        return definition.bakeRoot();
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelManager.java", """
                package net.minecraft.client.resources.model;
                import java.util.Map;
                import java.util.function.Supplier;
                import net.minecraft.client.model.geom.EntityModelSet;
                import net.minecraft.client.model.geom.ModelLayerLocation;
                import net.minecraft.client.model.geom.builders.LayerDefinition;
                public class ModelManager {
                    private EntityModelSet entityModelSet = EntityModelSet.EMPTY;
                    public Supplier<EntityModelSet> entityModels() { return () -> entityModelSet; }
                    // A resource reload builds a new set and replaces the old one.
                    public void apply(Map<ModelLayerLocation, LayerDefinition> roots) { entityModelSet = new EntityModelSet(roots); }
                }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                import net.minecraft.client.model.geom.EntityModelSet;
                import net.minecraft.client.resources.model.ModelManager;
                public class Minecraft {
                    static Minecraft instance;
                    ModelManager modelManager;
                    public static Minecraft getInstance() { return instance; }
                    public ModelManager getModelManager() { return modelManager; }
                    public EntityModelSet getEntityModels() { return modelManager.entityModels().get(); }
                }""");
        // Steps of the game's start, in the order Minecraft's constructor and first reload take them.
        sources.put("testhost/Probe.java", """
                package testhost;
                import java.util.Map;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.model.geom.ModelLayerLocation;
                import net.minecraft.client.model.geom.builders.LayerDefinition;
                public final class Probe {
                    public static void startGame() { net.minecraft.client.Steps.start(); }
                    public static void createModelManager() { net.minecraft.client.Steps.createModelManager(); }
                    public static void reload(String which) {
                        Minecraft.getInstance().getModelManager().apply(Map.of(new ModelLayerLocation("minecraft:player", "main"),
                                new LayerDefinition("player from the " + which + " reload")));
                    }
                    // The game's own getter, never transformed.
                    public static String hostBake() {
                        try { return Minecraft.getInstance().getEntityModels().bakeLayer(new ModelLayerLocation("minecraft:player", "main")).bakedFrom; }
                        catch (RuntimeException e) { return e.getClass().getSimpleName() + ": " + e.getMessage(); }
                    }
                }""");
        sources.put("net/minecraft/client/Steps.java", """
                package net.minecraft.client;
                public final class Steps {
                    public static void start() { Minecraft.instance = new Minecraft(); }
                    public static void createModelManager() { Minecraft.instance.modelManager = new net.minecraft.client.resources.model.ModelManager(); }
                }""");
        return sources;
    }
}
