/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;

/**
 * Bridges 1.21.4-era Fabric world rendering, the {@code v1.WorldRenderEvents} API and the
 * {@code VertexBuffer} drawing that went with it, onto 26.1 and newer hosts.
 *
 * <p>Fabric API 26.1 replaced {@code WorldRenderEvents} with {@code LevelRenderEvents}, renaming
 * every event and its callback method. A plain class redirect left {@code END} and the
 * {@code End} callback unresolved, so a mod's client entrypoint died on its first registration
 * (#296, Phantom Shapes). A synthetic holder now copies the matching live events, and each old
 * callback interface extends its successor with a forwarding default. The mapping follows the
 * position in the frame:
 * <ul>
 *   <li>{@code START} and {@code AFTER_SETUP} to {@code START_MAIN}</li>
 *   <li>{@code BEFORE_ENTITIES} to {@code AFTER_OPAQUE_TERRAIN}</li>
 *   <li>{@code AFTER_ENTITIES} to {@code AFTER_SOLID_FEATURES}</li>
 *   <li>{@code AFTER_TRANSLUCENT} to {@code AFTER_TRANSLUCENT_FEATURES}</li>
 *   <li>{@code BEFORE_DEBUG_RENDER} to {@code BEFORE_GIZMOS}</li>
 *   <li>{@code LAST} and {@code END} to {@code END_MAIN}</li>
 * </ul>
 * The two block-outline events changed their callback contract and stay unmapped.
 *
 * <p>Context accessors the new context dropped ({@code camera}, {@code frustum}, {@code world},
 * {@code tickCounter}) read the same values from the client. {@code VertexBuffer} and its shader
 * calls go to {@link com.retromod.polyfill.minecraft.LegacyVertexBuffer}.
 *
 * <p>Registered from the Fabric 1.21.11 to 26.1 shim, so it only runs on 26.1 and newer hosts.
 */
public final class LegacyLevelRenderBridge {

    private static final String FAPI = "net/fabricmc/fabric/api/client/rendering/v1/";
    static final String OLD_EVENTS = FAPI + "WorldRenderEvents";
    static final String LEVEL_EVENTS = FAPI + "level/LevelRenderEvents";
    static final String LEVEL_CONTEXT = FAPI + "level/LevelRenderContext";
    private static final String TERRAIN_CONTEXT = FAPI + "level/LevelTerrainRenderContext";
    static final String SYNTH = "com/retromod/generated/legacyevents/WorldRenderEvents";

    private static final String CONTEXT_POLYFILL = "com/retromod/polyfill/minecraft/LegacyWorldRenderContext";
    static final String VERTEX_BUFFER_POLYFILL = "com/retromod/polyfill/minecraft/LegacyVertexBuffer";

    /** {old field, old callback, old method, new field, new callback, new method, new context}. */
    private static final String[][] EVENTS = {
        {"START", "Start", "onStart", "START_MAIN", "StartMain", "startMain", TERRAIN_CONTEXT},
        {"AFTER_SETUP", "AfterSetup", "afterSetup", "START_MAIN", "StartMain", "startMain", TERRAIN_CONTEXT},
        {"BEFORE_ENTITIES", "BeforeEntities", "beforeEntities",
            "AFTER_OPAQUE_TERRAIN", "AfterOpaqueTerrain", "afterOpaqueTerrain", TERRAIN_CONTEXT},
        {"AFTER_ENTITIES", "AfterEntities", "afterEntities",
            "AFTER_SOLID_FEATURES", "AfterSolidFeatures", "afterSolidFeatures", LEVEL_CONTEXT},
        {"AFTER_TRANSLUCENT", "AfterTranslucent", "afterTranslucent",
            "AFTER_TRANSLUCENT_FEATURES", "AfterTranslucentFeatures", "afterTranslucentFeatures", LEVEL_CONTEXT},
        {"BEFORE_DEBUG_RENDER", "DebugRender", "beforeDebugRender",
            "BEFORE_GIZMOS", "BeforeGizmos", "beforeGizmos", LEVEL_CONTEXT},
        {"LAST", "Last", "onLast", "END_MAIN", "EndMain", "endMain", LEVEL_CONTEXT},
        {"END", "End", "onEnd", "END_MAIN", "EndMain", "endMain", LEVEL_CONTEXT},
    };

    private LegacyLevelRenderBridge() {}

    public static void register(RetromodTransformer t) {
        registerEvents(t);
        registerContextAccessors(t);
        registerVertexBuffer(t);
    }

    private static void registerEvents(RetromodTransformer t) {
        String[][] holderFields = new String[EVENTS.length][];
        for (int i = 0; i < EVENTS.length; i++) {
            String[] row = EVENTS[i];
            holderFields[i] = new String[]{row[0], LEVEL_EVENTS.replace('/', '.'), row[3]};
            String callback = SYNTH + "$" + row[1];
            t.registerSyntheticClass(callback,
                    callbackBridge(callback, LEVEL_EVENTS + "$" + row[4], row[2], row[5], row[6]));
            t.registerClassRedirect(OLD_EVENTS + "$" + row[1], callback);
        }
        t.registerSyntheticClass(SYNTH, SamBridgeSynthetics.eventHolder(SYNTH, holderFields));
        t.registerClassRedirect(OLD_EVENTS, SYNTH);
    }

    /**
     * An old callback interface extending its successor. The old method stays the single
     * abstract method, so a mod's lambda still links; the successor's method forwards to it,
     * casting a terrain context to the full context that Fabric API's single implementation is.
     */
    static byte[] callbackBridge(String synth, String successor, String oldMethod, String newMethod,
            String newContext) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                synth, null, "java/lang/Object", new String[]{successor});
        String oldDesc = "(L" + LEVEL_CONTEXT + ";)V";
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, oldMethod, oldDesc, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, newMethod, "(L" + newContext + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (!LEVEL_CONTEXT.equals(newContext)) mv.visitTypeInsn(Opcodes.CHECKCAST, LEVEL_CONTEXT);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, synth, oldMethod, oldDesc, true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void registerContextAccessors(RetromodTransformer t) {
        embed(t, CONTEXT_POLYFILL);
        t.registerMethodRedirect(LEVEL_CONTEXT, "matrixStack", "()Lcom/mojang/blaze3d/vertex/PoseStack;",
                LEVEL_CONTEXT, "poseStack", "()Lcom/mojang/blaze3d/vertex/PoseStack;");
        t.registerMethodRedirect(LEVEL_CONTEXT, "worldRenderer", "()Lnet/minecraft/client/renderer/LevelRenderer;",
                LEVEL_CONTEXT, "levelRenderer", "()Lnet/minecraft/client/renderer/LevelRenderer;");
        String[][] accessors = {
            {"camera", "()Lnet/minecraft/client/Camera;"},
            {"frustum", "()Lnet/minecraft/client/renderer/culling/Frustum;"},
            {"world", "()Lnet/minecraft/client/multiplayer/ClientLevel;"},
            {"tickCounter", "()Lnet/minecraft/client/DeltaTracker;"},
        };
        for (String[] accessor : accessors) {
            // Receiver-as-first-argument shape: the transformer devirtualizes and casts back.
            t.registerMethodRedirect(LEVEL_CONTEXT, accessor[0], accessor[1],
                    CONTEXT_POLYFILL, accessor[0], "(Ljava/lang/Object;)Ljava/lang/Object;");
        }
    }

    private static void registerVertexBuffer(RetromodTransformer t) {
        String vbo = VERTEX_BUFFER_POLYFILL;
        for (String part : new String[]{"", "$Usage", "$Shader", "$CoreShaders", "$Draw"}) {
            embed(t, vbo + part);
        }
        t.registerClassRedirect("com/mojang/blaze3d/vertex/VertexBuffer", vbo);
        t.registerClassRedirect("com/mojang/blaze3d/buffers/BufferUsage", vbo + "$Usage");
        t.registerClassRedirect("net/minecraft/client/renderer/ShaderProgram", vbo + "$Shader");
        t.registerClassRedirect("net/minecraft/client/renderer/CoreShaders", vbo + "$CoreShaders");

        String mesh = "Lcom/mojang/blaze3d/vertex/MeshData;";
        t.registerMethodRedirect(vbo, "upload", "(" + mesh + ")V", vbo, "upload", "(Ljava/lang/Object;)V");
        for (String program : new String[]{"com/mojang/blaze3d/opengl/GlProgram",
                "net/minecraft/client/renderer/CompiledShaderProgram"}) {
            t.registerMethodRedirect(vbo, "drawWithShader",
                    "(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;L" + program + ";)V",
                    vbo, "drawWithShader", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V");
            t.registerMethodRedirect("com/mojang/blaze3d/systems/RenderSystem", "getShader",
                    "()L" + program + ";", vbo, "getShader", "()Ljava/lang/Object;");
            t.registerMethodRedirect("com/mojang/blaze3d/systems/RenderSystem", "setShader",
                    "(L" + vbo + "$Shader;)L" + program + ";", vbo, "setShader",
                    "(Ljava/lang/Object;)Ljava/lang/Object;");
            t.registerMethodRedirect("com/mojang/blaze3d/systems/RenderSystem", "setShader",
                    "(Lnet/minecraft/client/renderer/ShaderProgram;)L" + program + ";", vbo, "setShader",
                    "(Ljava/lang/Object;)Ljava/lang/Object;");
        }
        t.registerMethodRedirect("com/mojang/blaze3d/systems/RenderSystem", "getProjectionMatrix",
                "()Lorg/joml/Matrix4f;", vbo, "getProjectionMatrix", "()Ljava/lang/Object;");
    }

    /** Ships one of Retromod's own polyfill classes with the mods that reference it. */
    private static void embed(RetromodTransformer t, String internalName) {
        if (t.getSyntheticClasses().containsKey(internalName)) return;
        try (InputStream in = LegacyLevelRenderBridge.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in != null) t.registerSyntheticClass(internalName, in.readAllBytes());
        } catch (Exception e) {
            // Retromod's own jar always carries the class; a miss leaves the jar-resident copy.
        }
    }
}
