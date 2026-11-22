/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Bridges BufferUploader and the matrix-based RenderSystem projection calls that 1.21.11 and 26.x removed. */
public final class LegacyBufferUploaderBridge {

    static final String UPLOADER = "com/mojang/blaze3d/vertex/BufferUploader";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacyBufferUploader";
    static final String PROJECTION = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyProjection";
    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";

    static final String MESH = "Lcom/mojang/blaze3d/vertex/MeshData;";
    static final String MATRIX = "Lorg/joml/Matrix4f;";
    static final String SORTING = "Lcom/mojang/blaze3d/vertex/VertexSorting;";
    static final String OLD_SET_PROJECTION = "(" + MATRIX + SORTING + ")V";

    private LegacyBufferUploaderBridge() {}

    /** Registers each bridge whose old member the host no longer has; offline, the shim's host gate decides alone. */
    public static void register(RetromodTransformer t) {
        boolean gameReadable = ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null;
        if (uploaderMissing(ClassResourceInspector.read(UPLOADER))) registerUploader(t);
        if (matrixProjectionMissing(ClassResourceInspector.read(RENDER_SYSTEM), gameReadable)) registerProjection(t);
    }

    /** drawWithShader and draw go to the helper, which takes the mesh as an Object; reset and invalidate do nothing. */
    static void registerUploader(RetromodTransformer t) {
        t.registerMethodRedirect(UPLOADER, "drawWithShader", "(" + MESH + ")V", HELPER, "drawWithShader", "(Ljava/lang/Object;)V");
        t.registerMethodRedirect(UPLOADER, "draw", "(" + MESH + ")V", HELPER, "draw", "(Ljava/lang/Object;)V");
        t.registerMethodRedirect(UPLOADER, "reset", "()V", HELPER, "reset", "()V");
        t.registerMethodRedirect(UPLOADER, "invalidate", "()V", HELPER, "reset", "()V");
    }

    /** setProjectionMatrix(Matrix4f, VertexSorting), getProjectionMatrix and getVertexSorting keep their values in the rebuilt class. */
    static void registerProjection(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, PROJECTION);
        t.registerMethodRedirect(RENDER_SYSTEM, "setProjectionMatrix", OLD_SET_PROJECTION, PROJECTION, "setProjectionMatrix",
                OLD_SET_PROJECTION);
        t.registerMethodRedirect(RENDER_SYSTEM, "getProjectionMatrix", "()" + MATRIX, PROJECTION, "getProjectionMatrix", "()" + MATRIX);
        t.registerMethodRedirect(RENDER_SYSTEM, "getVertexSorting", "()" + SORTING, PROJECTION, "getVertexSorting", "()" + SORTING);
    }

    /** Whether the host lacks BufferUploader, which 1.21.11 to 26.3 all do; offline nothing is readable, so it counts as missing. */
    static boolean uploaderMissing(ClassNode uploader) {
        return uploader == null;
    }

    /** Whether the host's RenderSystem lost the matrix form of setProjectionMatrix. */
    static boolean matrixProjectionMissing(ClassNode renderSystem, boolean gameReadable) {
        if (renderSystem == null) return !gameReadable;
        for (MethodNode method : renderSystem.methods) {
            if (method.name.equals("setProjectionMatrix") && method.desc.equals(OLD_SET_PROJECTION)) return false;
        }
        return true;
    }
}
