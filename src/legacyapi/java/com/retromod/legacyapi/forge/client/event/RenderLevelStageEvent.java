/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.forge.client.LegacyLevelStages;
import com.retromod.legacyapi.forge.eventbus.LegacyEvent;
import com.retromod.legacyapi.forge.eventbus.LegacyEvents;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.event.InheritableEvent;
import net.minecraftforge.fml.event.IModBusEvent;
import org.joml.Matrix4f;

/** Forge 1.20.1's game-bus event for each stage of the world render, which Forge 26.2 removed, with the old event rules. */
public class RenderLevelStageEvent extends LegacyEvent implements InheritableEvent {

    private final Stage stage;
    private final LevelRenderer levelRenderer;
    private final PoseStack poseStack;
    private final Matrix4f projectionMatrix;
    private final int renderTick;
    private final float partialTick;
    private final Camera camera;
    private final Frustum frustum;
    private final Matrix4f modelViewMatrix;

    public RenderLevelStageEvent(Stage stage, LevelRenderer levelRenderer, PoseStack poseStack, Matrix4f projectionMatrix,
            int renderTick, float partialTick, Camera camera, Frustum frustum) {
        this(stage, levelRenderer, poseStack, projectionMatrix, renderTick, partialTick, camera, frustum,
                poseStack == null ? new Matrix4f() : new Matrix4f(poseStack.last().pose()));
    }

    public RenderLevelStageEvent(Stage stage, LevelRenderer levelRenderer, PoseStack poseStack, Matrix4f projectionMatrix,
            int renderTick, float partialTick, Camera camera, Frustum frustum, Matrix4f modelViewMatrix) {
        this.stage = stage;
        this.levelRenderer = levelRenderer;
        this.poseStack = poseStack;
        this.projectionMatrix = projectionMatrix;
        this.renderTick = renderTick;
        this.partialTick = partialTick;
        this.camera = camera;
        this.frustum = frustum;
        this.modelViewMatrix = modelViewMatrix;
    }

    public Stage getStage() {
        return stage;
    }

    public LevelRenderer getLevelRenderer() {
        return levelRenderer;
    }

    /** The camera's rotation, which Forge 1.20.1 put here rather than in the model-view matrix. */
    public PoseStack getPoseStack() {
        return poseStack;
    }

    /** Forge 1.21's {@code getPoseStack()}, which returned this matrix; old calls of that shape come here. */
    public Matrix4f getModelViewMatrix() {
        return modelViewMatrix;
    }

    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
    }

    public int getRenderTick() {
        return renderTick;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public Camera getCamera() {
        return camera;
    }

    public Frustum getFrustum() {
        return frustum;
    }

    /** Whether anything in this mod's copy listens for a stage, so the shared pass can pass it by. */
    public static boolean hasListeners() {
        return LegacyEvents.busOf(BusGroup.DEFAULT, RenderLevelStageEvent.class).hasListeners();
    }

    /** Posts this copy's event for one stage of the shared pass, from a frame laid out as LegacyLevelStages names it. */
    public static void postStage(int index, Object[] frame) {
        // Forge 1.20.1 fired AFTER_LEVEL from GameRenderer with its bob PoseStack; here it gets the view rotation
        // like every other stage.
        if (index < LegacyLevelStages.REGISTERED_STAGES) {
            post(Stage.vanilla(index), frame);
            return;
        }
        // Forge 1.20.1 fired a registered stage only while its RenderType's block layer drew, so one registered
        // without a RenderType never fired. Here every registered stage fires after AFTER_LEVEL.
        for (Stage registered : Stage.registered()) post(registered, frame);
    }

    /** The name of the stage at {@code index} of the shared pass, for notes. */
    public static String stageName(int index) {
        return index < LegacyLevelStages.REGISTERED_STAGES ? Stage.vanilla(index).toString() : "the stages mods registered";
    }

    private static void post(Stage stage, Object[] frame) {
        RenderLevelStageEvent event = new RenderLevelStageEvent(stage, (LevelRenderer) frame[LegacyLevelStages.LEVEL_RENDERER],
                (PoseStack) frame[LegacyLevelStages.POSE_STACK], (Matrix4f) frame[LegacyLevelStages.PROJECTION],
                (Integer) frame[LegacyLevelStages.RENDER_TICK], (Float) frame[LegacyLevelStages.PARTIAL_TICK],
                (Camera) frame[LegacyLevelStages.CAMERA], (Frustum) frame[LegacyLevelStages.FRUSTUM],
                (Matrix4f) frame[LegacyLevelStages.MODEL_VIEW]);
        LegacyEvents.post(BusGroup.DEFAULT, event);
    }

    /** Posts this copy's RegisterStageEvent to one mod's group, when anything there listens for it. */
    public static void postRegister(BusGroup group) {
        if (group == null || !LegacyEvents.busOf(group, RegisterStageEvent.class).hasListeners()) return;
        LegacyEvents.post(group, new RegisterStageEvent());
    }

    /** A point in the world render. The constants are singletons, so old {@code ==} checks hold within a mod. */
    public static class Stage {

        // Forge 1.20.1's order, which the shared pass keeps. Building them reads no game class, since an old
        // static initializer may name a stage before any client class can load.
        private static final List<Stage> VANILLA = new ArrayList<>();
        private static final List<Stage> REGISTERED = new CopyOnWriteArrayList<>();
        private static final Map<RenderType, Stage> REGISTERED_TYPES = new IdentityHashMap<>();

        public static final Stage AFTER_SKY = vanilla("after_sky");
        public static final Stage AFTER_SOLID_BLOCKS = vanilla("after_solid_blocks");
        public static final Stage AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS = vanilla("after_cutout_mipped_blocks");
        public static final Stage AFTER_CUTOUT_BLOCKS = vanilla("after_cutout_blocks");
        public static final Stage AFTER_ENTITIES = vanilla("after_entities");
        public static final Stage AFTER_BLOCK_ENTITIES = vanilla("after_block_entities");
        public static final Stage AFTER_TRANSLUCENT_BLOCKS = vanilla("after_translucent_blocks");
        public static final Stage AFTER_TRIPWIRE_BLOCKS = vanilla("after_tripwire_blocks");
        public static final Stage AFTER_PARTICLES = vanilla("after_particles");
        public static final Stage AFTER_WEATHER = vanilla("after_weather");
        public static final Stage AFTER_LEVEL = vanilla("after_level");

        private static volatile Map<RenderType, Stage> blockLayers;

        private final String name;

        private Stage(String name) {
            this.name = name;
        }

        private static Stage vanilla(String path) {
            Stage stage = new Stage("minecraft:" + path);
            VANILLA.add(stage);
            return stage;
        }

        static Stage vanilla(int index) {
            return VANILLA.get(index);
        }

        static List<Stage> registered() {
            return REGISTERED;
        }

        static synchronized Stage register(Identifier name, RenderType renderType) throws IllegalArgumentException {
            Stage stage = new Stage(name.toString());
            if (renderType != null) {
                if (fromRenderType(renderType) != null) {
                    throw new IllegalArgumentException("A render stage is already registered for the render type " + renderType);
                }
                REGISTERED_TYPES.put(renderType, stage);
            }
            REGISTERED.add(stage);
            return stage;
        }

        @Override
        public String toString() {
            return name;
        }

        /** The stage for a block layer's render type, or for one a mod registered; null for any other. */
        public static Stage fromRenderType(RenderType renderType) {
            // Minecraft 26.2 keeps three block layers, so cutout mipped answers as cutout and tripwire as translucent.
            Stage layer = blockLayers().get(renderType);
            if (layer != null) return layer;
            synchronized (Stage.class) {
                return REGISTERED_TYPES.get(renderType);
            }
        }

        // The block layer tokens resolve on first use, when render types can load.
        private static Map<RenderType, Stage> blockLayers() {
            Map<RenderType, Stage> layers = blockLayers;
            if (layers == null) {
                layers = new IdentityHashMap<>();
                layers.put(RenderTypes.solidMovingBlock(), AFTER_SOLID_BLOCKS);
                layers.put(RenderTypes.cutoutMovingBlock(), AFTER_CUTOUT_BLOCKS);
                layers.put(RenderTypes.translucentMovingBlock(), AFTER_TRANSLUCENT_BLOCKS);
                blockLayers = layers;
            }
            return layers;
        }
    }

    /** Forge 1.20.1's mod-bus event for adding stages, posted once the world renderer exists, as it was then. */
    public static class RegisterStageEvent extends LegacyEvent implements IModBusEvent, InheritableEvent {

        public RegisterStageEvent() {}

        /** A new stage; Minecraft 26.2 has no custom block layers, so it fires right after AFTER_LEVEL. */
        public Stage register(Identifier name, RenderType renderType) throws IllegalArgumentException {
            return Stage.register(name, renderType);
        }
    }
}
