/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Farmer's Delight's and Waystones' block entity renderers and a mod's own entity renderer, written for 1.20.1, drawn on 26.x. */
class LegacyOldRendererDrawTest {

    private static final String FRAME = "testhost/Frame";
    private static final String PLATE = "mod/Plate";
    private static final String GHOST = "mod/Ghost";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> modern;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(shared());
        old.put("net/minecraft/client/renderer/MultiBufferSource.java", """
                package net.minecraft.client.renderer;
                public interface MultiBufferSource {
                    com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.rendertype.RenderType type);
                }""");
        old.put("net/minecraft/client/renderer/entity/ItemRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public class ItemRenderer {
                    public void renderStatic(net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.ItemDisplayContext context, int light,
                            int overlay, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                            net.minecraft.world.level.Level level, int seed) {}
                }""");
        old.put("net/minecraft/client/renderer/blockentity/BlockEntityRendererProvider.java", """
                package net.minecraft.client.renderer.blockentity;
                public interface BlockEntityRendererProvider {
                    class Context { public net.minecraft.client.renderer.entity.ItemRenderer getItemRenderer() { return null; } }
                }""");
        old.put("net/minecraft/client/renderer/blockentity/BlockEntityRenderer.java", """
                package net.minecraft.client.renderer.blockentity;
                public interface BlockEntityRenderer<T extends net.minecraft.world.level.block.entity.BlockEntity> {
                    void render(T blockEntity, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose,
                            net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay);
                }""");
        old.put("net/minecraft/client/renderer/entity/EntityRendererProvider.java", """
                package net.minecraft.client.renderer.entity;
                public interface EntityRendererProvider { class Context {} }""");
        old.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher.java", """
                package net.minecraft.client.renderer.entity;
                public class EntityRenderDispatcher {
                    public void setRenderShadow(boolean on) {}
                    public void render(net.minecraft.world.entity.Entity entity, double x, double y, double z, float yaw, float partialTick,
                            com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light) {}
                }""");
        old.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public abstract class EntityRenderer<T extends net.minecraft.world.entity.Entity> {
                    protected EntityRenderer(EntityRendererProvider.Context context) {}
                    public void render(T entity, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack pose,
                            net.minecraft.client.renderer.MultiBufferSource buffers, int light) {}
                }""");
        old.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public net.minecraft.client.renderer.entity.EntityRenderDispatcher getEntityRenderDispatcher() { return null; }
                }""");
        // Like Farmer's Delight's stove: the item renderer from the context, an item on top, and a little geometry of its own.
        old.put("mod/Plate.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.world.item.*;
                public class Plate implements net.minecraft.client.renderer.blockentity.BlockEntityRenderer<mod.PlateEntity> {
                    static final net.minecraft.client.renderer.rendertype.RenderType TYPE = new net.minecraft.client.renderer.rendertype.RenderType("plate");
                    private final net.minecraft.client.renderer.entity.ItemRenderer items;
                    public Plate(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context context) { items = context.getItemRenderer(); }
                    @Override
                    public void render(mod.PlateEntity plate, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
                        buffers.getBuffer(TYPE).addVertex(plate.id, partialTick, light);
                        pose.pushPose();
                        pose.last().y = 1;
                        items.renderStatic(new ItemStack(plate.dish), ItemDisplayContext.FIXED, light, overlay, pose, buffers, plate.level, 0);
                        pose.popPose();
                        if (plate.dish.equals("broken")) throw new IllegalStateException("after the dish");
                    }
                }""");
        old.put("mod/PlateEntity.java", """
                package mod;
                public class PlateEntity extends net.minecraft.world.level.block.entity.BlockEntity {
                    public String dish = "pie";
                    public int id = 4;
                }""");
        // A mod's own entity renderer that draws its rider through the dispatcher and asks for the base name tag.
        old.put("mod/Ghost.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.renderer.MultiBufferSource;
                public class Ghost extends net.minecraft.client.renderer.entity.EntityRenderer<mod.GhostEntity> {
                    static final net.minecraft.client.renderer.rendertype.RenderType TYPE = new net.minecraft.client.renderer.rendertype.RenderType("ghost");
                    public Ghost(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) { super(context); }
                    @Override
                    public void render(mod.GhostEntity ghost, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
                        buffers.getBuffer(TYPE).addVertex(yaw, partialTick, light);
                        if (ghost.rider != null) {
                            Minecraft.getInstance().getEntityRenderDispatcher().setRenderShadow(false);
                            Minecraft.getInstance().getEntityRenderDispatcher().render(ghost.rider, 0, 2, 0, 0f, partialTick, pose, buffers, light);
                            Minecraft.getInstance().getEntityRenderDispatcher().setRenderShadow(true);
                        }
                        if (ghost.name.equals("Broken")) throw new IllegalStateException("after the rider");
                        super.render(ghost, yaw, partialTick, pose, buffers, light);
                    }
                }""");
        old.put("mod/GhostEntity.java", """
                package mod;
                public class GhostEntity extends net.minecraft.world.entity.Entity { public net.minecraft.world.entity.Entity rider; }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        host = compile(dir.resolve("host"), hostSources());
        // A renderer written for 26.x, with its own state and submit.
        Map<String, String> current = new HashMap<>(hostSources());
        current.put("mod/NewPlate.java", """
                package mod;
                import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
                public class NewPlate implements net.minecraft.client.renderer.blockentity.BlockEntityRenderer<mod.PlateEntity, BlockEntityRenderState> {
                    public BlockEntityRenderState createRenderState() { return new BlockEntityRenderState(); }
                    public void submit(BlockEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack pose,
                            net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera) {}
                }""");
        current.put("mod/PlateEntity.java", old.get("mod/PlateEntity.java"));
        modern = only(compile(dir.resolve("modern"), current), "mod/NewPlate");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old block entity renderer draws its geometry and its item from the state 26.x hands it")
    void blockEntityDraws() throws Throwable {
        Map<String, byte[]> out = transform("26.2", bridgeOn(host), mod);
        assertDoesNotThrow(() -> new ClassReader(out.get(PLATE)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0));
        Run game = new Run(host, out);
        assertEquals("[plate (4.0,0.25,240.0), item pie@FIXED seed 0 light 240 (0.0,1.0,0.0)]", game.call(FRAME, "block", "pie"),
                "the light the host extracted, the partial tick, and the dish at the pose it was drawn at");
        Class<?> plate = game.loadClass("mod.Plate");
        assertEquals(plate.getInterfaces().length, plate.getGenericInterfaces().length, "the generic signature names the added interface too");
        assertEquals("net.minecraft.client.renderer.blockentity.BlockEntityRenderer<mod.PlateEntity, "
                + "net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState>", plate.getGenericInterfaces()[0].getTypeName(),
                "the host's two type arguments, so reflection on the renderer's generic interfaces works");
    }

    @Test
    @DisplayName("An old block entity renderer that throws draws nothing, stays off, and the game keeps running")
    void throwingBlockEntityStops() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        assertEquals("[]", game.call(FRAME, "block", "broken"), "neither the geometry nor the dish it drew before it threw");
        assertEquals("[]", game.call(FRAME, "block", "pie"), "the same renderer stays off until a reload");
    }

    @Test
    @DisplayName("An old entity renderer draws, draws its rider without a shadow through the dispatcher, and gets the base draw it asked for")
    void entityDraws() throws Throwable {
        Map<String, byte[]> out = transform("26.2", bridgeOn(host), mod);
        assertDoesNotThrow(() -> new ClassReader(out.get(GHOST)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0));
        Run game = new Run(host, out);
        assertEquals("[ghost (15.0,0.5,240.0), rider at (0.0,2.0,0.0) shadow 0.0, base Boo]", game.call(FRAME, "entity", "Boo"),
                "the yaw between the last two ticks, the partial tick and the host's light; then the rider, then the name tag");
        assertEquals("[]", game.call(FRAME, "entity", "Broken"), "a renderer that throws leaves neither its geometry nor its rider");
        assertEquals("net.minecraft.client.renderer.entity.EntityRenderer<mod.GhostEntity, net.minecraft.client.renderer.entity.state.EntityRenderState>",
                game.loadClass("mod.Ghost").getGenericSuperclass().getTypeName());
    }

    @Test
    @DisplayName("A renderer written for 26.x, and a mod built for the render-state API, are left as they are")
    void modernStaysAsWritten() {
        ClassNode before = node(modern.get("mod/NewPlate"));
        ClassNode after = node(transform("26.2", bridgeOn(host), modern).get("mod/NewPlate"));
        assertEquals(before.interfaces, after.interfaces);
        assertEquals(before.methods.size(), after.methods.size());
        Map<String, byte[]> newer = transform("26.2", t -> {
            t.setModSourceVersion("1.21.10");
            bridgeOn(host).accept(t);
        }, mod);
        assertFalse(node(newer.get(PLATE)).methods.stream().anyMatch(m -> m.name.equals("submit")), "a 1.21.10 mod keeps its own shape");
        assertFalse(node(newer.get(GHOST)).methods.stream().anyMatch(m -> m.name.equals("submit")));
    }

    @Test
    @DisplayName("Each loader's 26.1 shim gives both renderers a submit on 26.1 to 26.3")
    void everyLoaderOn26() {
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1())) {
            for (String target : List.of("26.1", "26.2", "26.3")) {
                Map<String, byte[]> out = transform(target, shim::registerRedirects, mod);
                for (String renderer : List.of(PLATE, GHOST)) {
                    ClassNode node = node(out.get(renderer));
                    assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("submit")) && node.interfaces.stream()
                            .anyMatch(i -> i.endsWith("$OldRenderer")), shim.getShimName() + " on " + target + ": " + renderer);
                }
            }
        }
    }

    // The bridges with the stand-in host readable as the game's loader would read it, and the 26.2 buffer stand-ins.
    private static Consumer<RetromodTransformer> bridgeOn(Map<String, byte[]> classes) {
        return t -> {
            RenderBufferSynthetics.register(t);
            LegacyItemDrawBridge.register(t, LegacyPlayerLayersBridgeTest.reader(classes));
        };
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    // Classes both sides share: vertices, poses, render types, entities, block entities and items.
    private static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>(LegacyPlayerLayersBridgeTest.shared());
        sources.putAll(LegacyItemDrawBridgeTest.items());
        sources.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public String name;
                    public EntityType<?> type;
                    public double x, y, z;
                    public float yRotO, yRot;
                    public float getYRot() { return yRot; }
                }""");
        sources.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}");
        sources.put("net/minecraft/world/level/block/entity/BlockEntity.java", """
                package net.minecraft.world.level.block.entity;
                public class BlockEntity { public int light = 240; public net.minecraft.world.level.Level level; }""");
        return sources;
    }

    /** The 26.2 shapes: renderers that create, extract and submit a state, items resolved into states, and a frame timer. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(shared());
        Map<String, String> items = LegacyItemDrawBridgeTest.hostSources();
        for (String name : List.of("net/minecraft/client/renderer/item/ItemModelResolver.java",
                "net/minecraft/client/renderer/item/ItemStackRenderState.java", "net/minecraft/client/renderer/OrderedSubmitNodeCollector.java",
                "net/minecraft/client/renderer/SubmitNodeCollector.java", "net/minecraft/client/DeltaTracker.java")) {
            sources.put(name, items.get(name));
        }
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static final Minecraft instance = new Minecraft();
                    public static long frameTime;
                    public final net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = new net.minecraft.client.renderer.entity.EntityRenderDispatcher();
                    public static Minecraft getInstance() { return instance; }
                    public long getFrameTimeNs() { return frameTime; }
                    public net.minecraft.client.renderer.item.ItemModelResolver getItemModelResolver() { return new net.minecraft.client.renderer.item.ItemModelResolver(); }
                    public net.minecraft.client.renderer.entity.EntityRenderDispatcher getEntityRenderDispatcher() { return dispatcher; }
                }""");
        sources.put("net/minecraft/world/phys/Vec3.java", "package net.minecraft.world.phys; public class Vec3 {}");
        sources.put("net/minecraft/client/renderer/feature/ModelFeatureRenderer.java", """
                package net.minecraft.client.renderer.feature;
                public class ModelFeatureRenderer { public record CrumblingOverlay(int progress) {} }""");
        sources.put("net/minecraft/client/renderer/state/level/CameraRenderState.java", """
                package net.minecraft.client.renderer.state.level;
                public class CameraRenderState {}""");
        sources.put("net/minecraft/client/renderer/blockentity/state/BlockEntityRenderState.java", """
                package net.minecraft.client.renderer.blockentity.state;
                public class BlockEntityRenderState {
                    public int lightCoords;
                    public static void extractBase(net.minecraft.world.level.block.entity.BlockEntity blockEntity, BlockEntityRenderState state,
                            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay breakProgress) {
                        state.lightCoords = blockEntity.light;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/blockentity/BlockEntityRendererProvider.java", """
                package net.minecraft.client.renderer.blockentity;
                public interface BlockEntityRendererProvider { class Context {} }""");
        sources.put("net/minecraft/client/renderer/blockentity/BlockEntityRenderer.java", """
                package net.minecraft.client.renderer.blockentity;
                import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
                public interface BlockEntityRenderer<T extends net.minecraft.world.level.block.entity.BlockEntity, S extends BlockEntityRenderState> {
                    S createRenderState();
                    default void extractRenderState(T blockEntity, S state, float partialTick, net.minecraft.world.phys.Vec3 cameraPos,
                            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay breakProgress) {
                        BlockEntityRenderState.extractBase(blockEntity, state, breakProgress);
                    }
                    void submit(S state, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            net.minecraft.client.renderer.state.level.CameraRenderState camera);
                }""");
        sources.put("net/minecraft/client/renderer/entity/state/EntityRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class EntityRenderState {
                    public net.minecraft.world.entity.EntityType<?> entityType;
                    public double x, y, z;
                    public float ageInTicks;
                    public int lightCoords;
                    public float shadowRadius;
                    public final java.util.List<Object> shadowPieces = new java.util.ArrayList<>();
                    public String nameTag;
                }""");
        sources.put("net/minecraft/client/renderer/entity/EntityRendererProvider.java", """
                package net.minecraft.client.renderer.entity;
                public interface EntityRendererProvider { class Context {} }""");
        // Like 26.2's: the base extract fills the light, the shadow and the name tag, and the base submit draws the name tag.
        sources.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                import net.minecraft.client.renderer.entity.state.EntityRenderState;
                public abstract class EntityRenderer<T extends net.minecraft.world.entity.Entity, S extends EntityRenderState> {
                    protected EntityRenderer(EntityRendererProvider.Context context) {}
                    public abstract S createRenderState();
                    public void extractRenderState(T entity, S state, float partialTick) {
                        state.lightCoords = 240; state.shadowRadius = 0.5f; state.shadowPieces.add("piece"); state.nameTag = entity.name;
                    }
                    public void submit(S state, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            net.minecraft.client.renderer.state.level.CameraRenderState camera) {
                        collector.submitCustomGeometry(pose, new net.minecraft.client.renderer.rendertype.RenderType("base " + state.nameTag), (p, b) -> {});
                    }
                }""");
        // A rider's renderer, which reports where and with what shadow the dispatcher submitted it.
        sources.put("testhost/Rider.java", """
                package testhost;
                import net.minecraft.client.renderer.entity.state.EntityRenderState;
                public class Rider extends net.minecraft.client.renderer.entity.EntityRenderer<net.minecraft.world.entity.Entity, EntityRenderState> {
                    public Rider() { super(null); }
                    public EntityRenderState createRenderState() { return new EntityRenderState(); }
                    public void submit(EntityRenderState state, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            net.minecraft.client.renderer.state.level.CameraRenderState camera) {
                        collector.submitCustomGeometry(pose, new net.minecraft.client.renderer.rendertype.RenderType("rider at (" + pose.last().x + ","
                                + pose.last().y + "," + pose.last().z + ") shadow " + state.shadowRadius), (p, b) -> {});
                    }
                }""");
        sources.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher.java", """
                package net.minecraft.client.renderer.entity;
                import net.minecraft.client.renderer.entity.state.EntityRenderState;
                @SuppressWarnings({"rawtypes", "unchecked"})
                public class EntityRenderDispatcher {
                    public final EntityRenderer rider = new testhost.Rider();
                    public <E extends net.minecraft.world.entity.Entity> EntityRenderState extractEntity(E entity, float partialTick) {
                        EntityRenderState state = rider.createRenderState();
                        rider.extractRenderState(entity, state, partialTick);
                        return state;
                    }
                    public <S extends EntityRenderState> void submit(S state, net.minecraft.client.renderer.state.level.CameraRenderState camera,
                            double x, double y, double z, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
                        pose.pushPose();
                        pose.last().x += (float) x; pose.last().y += (float) y; pose.last().z += (float) z;
                        rider.submit(state, pose, collector, camera);
                        pose.popPose();
                    }
                }""");
        // One frame: the host makes the state, extracts into it and submits it; the collector draws what was submitted.
        sources.put("testhost/Frame.java", """
                package testhost;
                import java.util.*;
                @SuppressWarnings({"rawtypes", "unchecked"})
                public class Frame {
                    static net.minecraft.client.renderer.blockentity.BlockEntityRenderer plate;
                    static net.minecraft.client.renderer.entity.EntityRenderer ghost;
                    public static String block(String dish) throws Exception {
                        if (plate == null) plate = (net.minecraft.client.renderer.blockentity.BlockEntityRenderer) Class.forName("mod.Plate", true,
                                Frame.class.getClassLoader()).getConstructor(net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context.class)
                                .newInstance(new net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context());
                        net.minecraft.world.level.block.entity.BlockEntity be = (net.minecraft.world.level.block.entity.BlockEntity)
                                Class.forName("mod.PlateEntity", true, Frame.class.getClassLoader()).getConstructor().newInstance();
                        be.getClass().getField("dish").set(be, dish);
                        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state = plate.createRenderState();
                        plate.extractRenderState(be, state, 0.25f, null, null);
                        return draw(collector -> plate.submit(state, new com.mojang.blaze3d.vertex.PoseStack(), collector,
                                new net.minecraft.client.renderer.state.level.CameraRenderState()));
                    }
                    public static String entity(String name) throws Exception {
                        if (ghost == null) ghost = (net.minecraft.client.renderer.entity.EntityRenderer) Class.forName("mod.Ghost", true,
                                Frame.class.getClassLoader()).getConstructor(net.minecraft.client.renderer.entity.EntityRendererProvider.Context.class)
                                .newInstance(new net.minecraft.client.renderer.entity.EntityRendererProvider.Context());
                        net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity)
                                Class.forName("mod.GhostEntity", true, Frame.class.getClassLoader()).getConstructor().newInstance();
                        entity.name = name; entity.yRotO = 10; entity.yRot = 20;
                        net.minecraft.world.entity.Entity rider = new net.minecraft.world.entity.Entity();
                        rider.name = "rider";
                        entity.getClass().getField("rider").set(entity, rider);
                        Object state = ghost.createRenderState();
                        ghost.extractRenderState(entity, (net.minecraft.client.renderer.entity.state.EntityRenderState) state, 0.5f);
                        return draw(collector -> ghost.submit((net.minecraft.client.renderer.entity.state.EntityRenderState) state,
                                new com.mojang.blaze3d.vertex.PoseStack(), collector, new net.minecraft.client.renderer.state.level.CameraRenderState()));
                    }
                    private static String draw(java.util.function.Consumer<net.minecraft.client.renderer.SubmitNodeCollector> submit) {
                        List<String> drawn = new ArrayList<>();
                        net.minecraft.client.renderer.SubmitNodeCollector collector = (pose, type, renderer) -> {
                            drawn.add(type.toString());
                            renderer.render(pose.last(), new Recorder(drawn));
                        };
                        submit.accept(collector);
                        net.minecraft.client.Minecraft.frameTime++;
                        return drawn.toString();
                    }
                    static final class Recorder implements com.mojang.blaze3d.vertex.VertexConsumer {
                        private final List<String> drawn;
                        Recorder(List<String> drawn) { this.drawn = drawn; }
                        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
                            drawn.set(drawn.size() - 1, drawn.get(drawn.size() - 1) + " (" + x + "," + y + "," + z + ")"); return this;
                        }
                        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) { return this; }
                    }
                }""");
        return sources;
    }
}
