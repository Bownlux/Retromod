/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyForgeAddLayersBridge;
import com.retromod.shim.neoforge.LegacyNeoForgeAddLayersBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1's AddLayers lookups, as Curios and Supplementaries make them, on Forge 26.2 and NeoForge 26.x. */
class LegacyAddLayersBridgeTest {

    private static final String LAYERS = "mod/Layers";
    private static final String PROBE = "testhost/Probe";
    private static final String FORGE_EVENT = "net/minecraftforge/client/event/EntityRenderersEvent$AddLayers";
    private static final String NEO_EVENT = "net/neoforged/neoforge/client/event/EntityRenderersEvent$AddLayers";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> forgeHost;
    private static Map<String, byte[]> neoHost;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(shared());
        old.put("net/minecraftforge/client/event/EntityRenderersEvent.java", """
                package net.minecraftforge.client.event;
                import net.minecraft.client.renderer.entity.LivingEntityRenderer;
                public class EntityRenderersEvent {
                    public static class AddLayers extends EntityRenderersEvent {
                        public java.util.Set<String> getSkins() { return null; }
                        public <R extends LivingEntityRenderer> R getSkin(String name) { return null; }
                        public <R extends LivingEntityRenderer> R getRenderer(net.minecraft.world.entity.EntityType<?> type) { return null; }
                    }
                }""");
        // Every skin by name, then a mob's renderer, as the mods' AddLayers listeners read them.
        old.put("mod/Layers.java", """
                package mod;
                import net.minecraftforge.client.event.EntityRenderersEvent;
                public class Layers {
                    public static String onAddLayers(Object event) {
                        EntityRenderersEvent.AddLayers layers = (EntityRenderersEvent.AddLayers) event;
                        StringBuilder out = new StringBuilder();
                        for (String skin : layers.getSkins()) out.append(skin).append("=").append((Object) layers.getSkin(skin)).append(", ");
                        out.append("giant=").append((Object) layers.getSkin("giant")).append(", ");
                        out.append("zombie=").append((Object) layers.getRenderer(net.minecraft.world.entity.EntityType.ZOMBIE)).append(", ");
                        // 1.20.1 gave null for a renderer that is not a living one, as the ender dragon's is not.
                        return out.append("dragon=").append((Object) layers.getRenderer(net.minecraft.world.entity.EntityType.ENDER_DRAGON)).toString();
                    }
                }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        forgeHost = compile(dir.resolve("forge"), hostSources(false));
        neoHost = compile(dir.resolve("neo"), hostSources(true));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().setModSourceVersion(null);
    }

    @Test
    @DisplayName("Forge 26.2: the old lookups fail as written and answer by name once bridged")
    void forgeHost() throws Throwable {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        Run old = new Run(forgeHost, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(LAYERS, "onAddLayers", old.call(PROBE, "event"))));

        Run game = new Run(forgeHost, transform("26.2", t -> {
            // The harness clears the transformer first, so the mod's version is set with the rules.
            t.setModSourceVersion("1.20.1");
            LegacyForgeAddLayersBridge.register(t);
        }, mod));
        assertEquals("slim=slim player, default=wide player, giant=null, zombie=zombie renderer, dragon=null",
                game.call(LAYERS, "onAddLayers", game.call(PROBE, "event")));
    }

    @Test
    @DisplayName("NeoForge 26.x: a Forge 1.20.1 mod's lookups, renamed to NeoForge's event, answer by name too")
    void neoForgeHost() throws Throwable {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        Run game = new Run(neoHost, transform("26.3", t -> {
            t.setModSourceVersion("1.20.1");
            // The Forge-to-NeoForge event rename that NeoForge hosts run for Forge mods.
            t.registerClassRedirect(FORGE_EVENT, NEO_EVENT);
            LegacyNeoForgeAddLayersBridge.register(t);
        }, mod));
        assertEquals("slim=slim player, default=wide player, giant=null, zombie=zombie renderer, dragon=null",
                game.call(LAYERS, "onAddLayers", game.call(PROBE, "event")));
    }

    @Test
    @DisplayName("NeoForge's own getSkins() stays for a mod built after 1.20.1, which expects model types from it")
    void laterModKeepsNeoForgeGetSkins() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        Map<String, byte[]> out = transform("26.3", t -> {
            t.setModSourceVersion("1.21.1");
            t.registerClassRedirect(FORGE_EVENT, NEO_EVENT);
            LegacyNeoForgeAddLayersBridge.register(t);
        }, mod);
        assertTrue(calls(out.get(LAYERS), "onAddLayers").contains(NEO_EVENT + ".getSkins()Ljava/util/Set;"), calls(out.get(LAYERS), "onAddLayers").toString());
    }

    @Test
    @DisplayName("Each loader's bridge stays off the other loader's host")
    void loaderGates() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        Map<String, byte[]> onNeo = transform("26.2", LegacyForgeAddLayersBridge::register, mod);
        assertTrue(calls(onNeo.get(LAYERS), "onAddLayers").contains(FORGE_EVENT + ".getSkins()Ljava/util/Set;"), "the Forge bridge on NeoForge");
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        Map<String, byte[]> onForge = transform("26.2", LegacyNeoForgeAddLayersBridge::register, mod);
        assertTrue(calls(onForge.get(LAYERS), "onAddLayers").contains(FORGE_EVENT + ".getSkins()Ljava/util/Set;"), "the NeoForge bridge on Forge");
    }

    private static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/world/entity/EntityType.java", """
                package net.minecraft.world.entity;
                public class EntityType<T> { public static final EntityType<?> ZOMBIE = new EntityType<>(), ENDER_DRAGON = new EntityType<>(); }""");
        sources.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public class EntityRenderer<T> {
                    private final String name;
                    public EntityRenderer(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/renderer/entity/LivingEntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public class LivingEntityRenderer<T> extends EntityRenderer<T> { public LivingEntityRenderer(String name) { super(name); } }""");
        return sources;
    }

    /** The 26.x events: Forge's keyed by model type with getEntityRenderer, NeoForge's with getSkins() returning the types. */
    private static Map<String, String> hostSources(boolean neoForge) {
        Map<String, String> sources = new HashMap<>(shared());
        sources.put("net/minecraft/world/entity/player/PlayerModelType.java", """
                package net.minecraft.world.entity.player;
                public enum PlayerModelType {
                    SLIM("slim"), WIDE("wide");
                    private final String id;
                    PlayerModelType(String id) { this.id = id; }
                    public String getSerializedName() { return id; }
                }""");
        sources.put("net/minecraft/client/renderer/entity/player/AvatarRenderer.java", """
                package net.minecraft.client.renderer.entity.player;
                public class AvatarRenderer<T> extends net.minecraft.client.renderer.entity.LivingEntityRenderer<T> {
                    public AvatarRenderer(String name) { super(name); }
                }""");
        String players = """
                    private final java.util.Map<PlayerModelType, AvatarRenderer<?>> players = new java.util.EnumMap<>(PlayerModelType.class);
                    private final java.util.Map<EntityType<?>, EntityRenderer<?>> renderers = new java.util.HashMap<>();
                    public AddLayers() {
                        players.put(PlayerModelType.SLIM, new AvatarRenderer<>("slim player"));
                        players.put(PlayerModelType.WIDE, new AvatarRenderer<>("wide player"));
                        renderers.put(EntityType.ZOMBIE, new net.minecraft.client.renderer.entity.LivingEntityRenderer<>("zombie renderer"));
                        renderers.put(EntityType.ENDER_DRAGON, new EntityRenderer<>("dragon renderer"));
                    }
                """;
        String imports = """
                import net.minecraft.client.renderer.entity.EntityRenderer;
                import net.minecraft.client.renderer.entity.player.AvatarRenderer;
                import net.minecraft.world.entity.EntityType;
                import net.minecraft.world.entity.player.PlayerModelType;
                """;
        if (neoForge) {
            sources.put("net/neoforged/neoforge/client/event/EntityRenderersEvent.java", "package net.neoforged.neoforge.client.event;\n" + imports + """
                    public abstract class EntityRenderersEvent {
                        public static class AddLayers extends EntityRenderersEvent {
                    """ + players + """
                            public java.util.Set<PlayerModelType> getSkins() { return players.keySet(); }
                            public <R extends AvatarRenderer<?>> R getPlayerRenderer(PlayerModelType type) { return (R) players.get(type); }
                            public <T, R extends EntityRenderer<T>> R getRenderer(EntityType<? extends T> type) { return (R) renderers.get(type); }
                        }
                    }""");
            sources.put("testhost/Probe.java", """
                    package testhost;
                    public class Probe { public static Object event() { return new net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers(); } }""");
        } else {
            sources.put("net/minecraftforge/client/event/EntityRenderersEvent.java", "package net.minecraftforge.client.event;\n" + imports + """
                    public interface EntityRenderersEvent {
                        final class AddLayers implements EntityRenderersEvent {
                    """ + players + """
                            public java.util.Set<PlayerModelType> getModelTypes() { return players.keySet(); }
                            public <R extends EntityRenderer<?>> R getPlayerRenderer(PlayerModelType type) { return (R) players.get(type); }
                            public <T, R extends EntityRenderer<T>> R getEntityRenderer(EntityType<? extends T> type) { return (R) renderers.get(type); }
                        }
                    }""");
            sources.put("testhost/Probe.java", """
                    package testhost;
                    public class Probe { public static Object event() { return new net.minecraftforge.client.event.EntityRenderersEvent.AddLayers(); } }""");
        }
        return sources;
    }
}
