/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client;

import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.forge.client.event.RenderLevelStageEvent;
import com.retromod.shim.common.embedded.LegacyLog;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/** The one Forge frame pass that fires every copy's old world render stages, after the vanilla world passes. */
public final class LegacyLevelStagePass implements FramePassManager.PassDefinition {

    // The first copy to install registers the pass; the slot says how far that got.
    private static final int NOT_ASKED = 0;
    private static final int WAITING = 1;
    private static final int ADDED = 2;

    private static final Function<Object[], Object> ENDPOINT = LegacyLevelStagePass::handle;
    private static final Set<String> NOTED = ConcurrentHashMap.newKeySet();
    private static volatile List<Function<Object[], Object>> ordered = List.of();
    private static volatile List<Function<Object[], Object>> orderedFrom;
    private static volatile Method[] bobMethods;
    private static volatile boolean bobUnavailable;

    LegacyLevelStagePass() {}

    /** Lists this copy with the shared pass, and has the pass added if no copy asked for it yet. */
    static void attach() {
        LegacyLevelStages.endpoints().add(ENDPOINT);
        AtomicInteger pass = passState();
        if (!pass.compareAndSet(NOT_ASKED, WAITING)) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.levelRenderer != null) {
            // AddFramePassEvent fired when the world renderer was built. Forge reads its pass list every frame
            // on the render thread without a lock, so the pass is added there.
            minecraft.execute(() -> addPass(null));
            return;
        }
        AddFramePassEvent.BUS.addListener(LegacyLevelStagePass::addPass);
    }

    private static AtomicInteger passState() {
        return LegacySharedSlots.get(LegacyLevelStages.PASS, LegacyLevelStages.PROTOCOL, AtomicInteger.class, AtomicInteger::new);
    }

    // Forge 1.20.1 posted RegisterStageEvent right after building the world renderer, where AddFramePassEvent fires now.
    private static void addPass(AddFramePassEvent event) {
        if (passState().getAndSet(ADDED) == ADDED) return;
        // This runs while Minecraft starts, where a throw would stop the game.
        try {
            if (event != null) postRegisterStages();
            Identifier id = Identifier.fromNamespaceAndPath("retromod", "legacy_level_stages");
            if (event != null) {
                event.addPass(id, new LegacyLevelStagePass());
            } else {
                ForgeHooksClient.addFramePass(id, new LegacyLevelStagePass());
            }
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not add its frame pass, so the old RenderLevelStageEvent listeners of "
                    + owners() + " are not called: " + failure);
        }
    }

    // Each mod in mod order, then each copy that mod installed, as Forge 1.20.1 posted mod-bus events.
    private static void postRegisterStages() {
        // Without FML's list, the order the copies were installed in stands in.
        List<String> mods = modOrder();
        for (Function<Object[], Object> copy : LegacyLevelStages.endpoints()) {
            for (Object owner : ownersOf(copy)) {
                if (owner instanceof String namespace && !mods.contains(namespace)) mods.add(namespace);
            }
        }
        for (String mod : mods) {
            for (Function<Object[], Object> copy : LegacyLevelStages.endpoints()) {
                try {
                    copy.apply(new Object[]{"register", mod});
                } catch (RuntimeException | LinkageError failure) {
                    LegacyLog.warn("A RegisterStageEvent listener of " + mod + " threw; Retromod went on with the next"
                            + " mod. " + stackTrace(failure));
                }
            }
        }
    }

    /** One request to this copy, as LegacyLevelStages.ENDPOINTS lists them. */
    static Object handle(Object[] request) {
        switch ((String) request[0]) {
            case "listens":
                return RenderLevelStageEvent.hasListeners();
            case "stage":
                RenderLevelStageEvent.postStage((Integer) request[1], (Object[]) request[2]);
                return null;
            case "register":
                if (LegacyClientRenderBootstrap.namespaces().contains(request[1])) {
                    RenderLevelStageEvent.postRegister(LegacyClientRenderBootstrap.modBusGroup((String) request[1]));
                }
                return null;
            case "owners":
                return LegacyClientRenderBootstrap.namespaces();
            case "protocol":
                return LegacyLevelStages.PROTOCOL;
            default:
                throw new IllegalArgumentException("Unknown level stage request " + request[0]);
        }
    }

    @Override
    public void extracts(LevelTargetBundle targets, FramePass pass) {
        // The pass draws into the main target, and Forge has no other way to keep it in the frame graph.
        targets.main = pass.readsAndWrites(targets.main);
        pass.disableCulling();
    }

    /** Forge 26.3's extracts, which also hands over the frame's state. */
    public void extracts(LevelTargetBundle targets, FramePass pass, LevelRenderState state) {
        extracts(targets, pass);
    }

    @Override
    public void executes(LevelRenderState state) {
        fire(state, LegacyLevelStages.endpoints(), LegacyLevelStages.flushers());
    }

    /** Fires every stage, stage by stage across the copies that listen in mod order, with the flushers after each stage. */
    static void fire(LevelRenderState state, List<Function<Object[], Object>> endpoints, List<Runnable> flushers) {
        List<Function<Object[], Object>> listening = new ArrayList<>();
        for (Function<Object[], Object> copy : inModOrder(endpoints)) {
            if (Boolean.TRUE.equals(ask(copy, new Object[]{"listens"}))) listening.add(copy);
        }
        if (listening.isEmpty()) return;
        Object[] frame;
        Matrix4f rotation;
        // A member a later host renamed fails here, once per frame, so it is written out only the first time.
        try {
            rotation = new Matrix4f(state.cameraRenderState.viewRotationMatrix);
            frame = frame(state, rotation);
        } catch (RuntimeException | LinkageError failure) {
            if (NOTED.add("frame " + failure.getClass().getName())) {
                LegacyLog.warn("Retromod could not build the frame old RenderLevelStageEvent listeners of " + owners()
                        + " get, so they are not called: " + stackTrace(failure));
            }
            return;
        }
        PoseStack poseStack = (PoseStack) frame[LegacyLevelStages.POSE_STACK];
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            for (int stage = 0; stage < LegacyLevelStages.STAGES; stage++) {
                // Forge 1.20.1 put the camera rotation in the model-view matrix only around AFTER_WEATHER; every
                // other stage had identity there and the rotation in the event's PoseStack.
                if (stage == LegacyLevelStages.AFTER_WEATHER) {
                    modelView.set(rotation);
                } else {
                    modelView.identity();
                }
                for (Function<Object[], Object> copy : listening) {
                    ask(copy, new Object[]{"stage", stage, frame});
                    // A pose one mod left pushed or changed, with or without throwing, does not reach the next mod.
                    resetPose(poseStack, rotation);
                }
                for (Runnable flush : flushers) {
                    try {
                        flush.run();
                    } catch (RuntimeException | LinkageError failure) {
                        if (NOTED.add("flush " + flush.getClass().getName())) {
                            LegacyLog.warn("Retromod could not end a buffer batch after an old render stage: "
                                    + stackTrace(failure));
                        }
                    }
                }
            }
        } finally {
            modelView.popMatrix();
        }
    }

    private static void resetPose(PoseStack poseStack, Matrix4f rotation) {
        while (!poseStack.isEmpty()) poseStack.popPose();
        poseStack.setIdentity();
        poseStack.mulPose(rotation);
    }

    // One copy's answer; a copy whose listener throws is noted once and the others still run.
    private static Object ask(Function<Object[], Object> copy, Object[] request) {
        try {
            return copy.apply(request);
        } catch (RuntimeException | LinkageError failure) {
            // The same failure comes back every frame, so only its first is written out.
            if (NOTED.add(System.identityHashCode(copy) + " " + failure.getClass().getName())) {
                String stage = request.length > 1 ? " at " + RenderLevelStageEvent.stageName((Integer) request[1]) : "";
                LegacyLog.warn("An old RenderLevelStageEvent listener of " + ownersOf(copy) + " threw" + stage
                        + "; Retromod went on with the other mods' listeners. " + stackTrace(failure));
            }
            return null;
        }
    }

    // The copies in mod order, each placed by the first mod that installed it, as Forge 1.20.1 ran mods' listeners.
    // Copies install while mods construct, so the order is worked out again only when one is added.
    private static List<Function<Object[], Object>> inModOrder(List<Function<Object[], Object>> endpoints) {
        List<Function<Object[], Object>> cached = ordered;
        if (orderedFrom == endpoints && cached.size() == endpoints.size()) return cached;
        List<String> mods = modOrder();
        List<Function<Object[], Object>> sorted = new ArrayList<>(endpoints);
        // A stable sort, so copies whose mods the list does not name keep their install order, last.
        sorted.sort(Comparator.comparingInt(copy -> firstMod(copy, mods)));
        ordered = sorted;
        orderedFrom = endpoints;
        return sorted;
    }

    private static int firstMod(Function<Object[], Object> copy, List<String> mods) {
        int first = Integer.MAX_VALUE;
        for (Object owner : ownersOf(copy)) {
            int index = mods.indexOf(owner);
            if (index >= 0) first = Math.min(first, index);
        }
        return first;
    }

    // FML's mod order, or none when FML cannot give it.
    private static List<String> modOrder() {
        List<String> mods = new ArrayList<>();
        try {
            ModList.forEachModInOrder(mod -> mods.add(mod.getNamespace()));
        } catch (RuntimeException | LinkageError noModList) {
            mods.clear();
        }
        return mods;
    }

    // The members of RenderLevelStageEvent for this frame, from the state the world render extracted.
    private static Object[] frame(LevelRenderState state, Matrix4f rotation) {
        Minecraft minecraft = Minecraft.getInstance();
        CameraRenderState camera = state.cameraRenderState;
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(rotation);
        Matrix4f projection = new Matrix4f(camera.projectionMatrix);
        addBob(projection, minecraft.gameRenderer, camera);
        Object[] frame = new Object[LegacyLevelStages.FRAME_SIZE];
        frame[LegacyLevelStages.LEVEL_RENDERER] = minecraft.levelRenderer;
        frame[LegacyLevelStages.POSE_STACK] = poseStack;
        frame[LegacyLevelStages.PROJECTION] = projection;
        // The client tick count, as Forge 1.20.1's LevelRenderer kept it, without the float loss of a long game time.
        frame[LegacyLevelStages.RENDER_TICK] = minecraft.gui.hud.getGuiTicks();
        // The partial tick LevelRenderer.render reads for the same frame.
        frame[LegacyLevelStages.PARTIAL_TICK] = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        frame[LegacyLevelStages.CAMERA] = minecraft.gameRenderer.mainCamera();
        frame[LegacyLevelStages.FRUSTUM] = camera.cullFrustum;
        frame[LegacyLevelStages.MODEL_VIEW] = new Matrix4f(rotation);
        return frame;
    }

    // GameRenderer tilts its own copy of the projection for damage and view bobbing, and Forge 1.20.1 handed that
    // copy to the stages; the shared state keeps the untilted one. The nausea and portal warp GameRenderer adds after
    // the tilt are not repeated here.
    private static void addBob(Matrix4f projection, GameRenderer renderer, CameraRenderState camera) {
        Method[] bob = bobMethods();
        if (bob == null) return;
        try {
            PoseStack tilt = new PoseStack();
            bob[0].invoke(renderer, camera, tilt);
            // The same option GameRenderer checks before it bobs the view.
            if (renderer.gameRenderState().optionsRenderState.bobView) bob[1].invoke(renderer, camera, tilt);
            projection.mul(tilt.last().pose());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            bobUnavailable = true;
            LegacyLog.warn("Retromod could not add the hurt tilt and view bobbing to the projection old render stage"
                    + " listeners get, so what they place on screen can drift while the camera bobs: " + failure);
        }
    }

    // GameRenderer's private bobHurt and bobView, looked up once.
    private static Method[] bobMethods() {
        Method[] found = bobMethods;
        if (found != null || bobUnavailable) return found;
        try {
            // Minecraft's jar has no module descriptor, so its packages are open to reflection, as Forge's own
            // ObfuscationReflectionHelper relies on.
            Method hurt = GameRenderer.class.getDeclaredMethod("bobHurt", CameraRenderState.class, PoseStack.class);
            Method view = GameRenderer.class.getDeclaredMethod("bobView", CameraRenderState.class, PoseStack.class);
            hurt.setAccessible(true);
            view.setAccessible(true);
            found = new Method[]{hurt, view};
            bobMethods = found;
            return found;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            bobUnavailable = true;
            LegacyLog.warn("Retromod found no hurt tilt or view bobbing on this GameRenderer, so the projection old render"
                    + " stage listeners get leaves them out: " + failure);
            return null;
        }
    }

    private static List<Object> owners() {
        List<Object> owners = new ArrayList<>();
        for (Function<Object[], Object> copy : LegacyLevelStages.endpoints()) owners.addAll(ownersOf(copy));
        return owners;
    }

    // The namespaces that installed a copy, or none when the copy cannot say.
    private static List<?> ownersOf(Function<Object[], Object> copy) {
        try {
            return copy.apply(new Object[]{"owners"}) instanceof List<?> owners ? owners : List.of();
        } catch (RuntimeException | LinkageError unreadable) {
            return List.of();
        }
    }

    private static String stackTrace(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
