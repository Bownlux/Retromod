/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Rebuilds custom render types made from state shards on 26.1 to 26.3: the shards become values, and create(...) picks a host pipeline. */
public final class LegacyRenderTypeBridge {

    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/client/renderer/rendertype/";
    static final String SHARD = ROOT + "LegacyRenderStateShard";
    static final String STATE = ROOT + "LegacyCompositeState";
    static final String HOLDER = ROOT + "LegacyRenderTypeHolder";
    static final String RESOLVER = "com/retromod/shim/common/embedded/LegacyRenderTypeResolver";

    static final String OLD_SHARD = "net/minecraft/client/renderer/RenderStateShard";
    static final String OLD_TYPE = "net/minecraft/client/renderer/RenderType";
    static final String HOST_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";
    static final String OLD_STATE = OLD_TYPE + "$CompositeState";
    static final String OLD_COMPOSITE_TYPE = OLD_TYPE + "$CompositeRenderType";
    static final String SETUP_BUILDER = "net/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder";

    // The shard kinds the corpus names; each keeps its old nested name on the rebuilt class.
    static final String[] SHARD_KINDS = {"BooleanStateShard", "CullStateShard", "LightmapStateShard", "OverlayStateShard",
            "DepthTestStateShard", "WriteMaskStateShard", "TransparencyStateShard", "LayeringStateShard", "OutputStateShard",
            "TexturingStateShard", "OffsetTexturingStateShard", "EmptyTextureStateShard", "TextureStateShard", "ShaderStateShard",
            "LineStateShard"};

    static final String STRING = "Ljava/lang/String;";
    static final String HOST_FORMAT = "Lcom/mojang/renderpearl/api/vertex/VertexFormat;";
    static final String HOST_TOPOLOGY = "Lcom/mojang/renderpearl/api/pipeline/PrimitiveTopology;";
    static final String OLD_FORMAT = "Lcom/mojang/blaze3d/vertex/VertexFormat;";
    static final String OLD_MODE = "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;";

    private LegacyRenderTypeBridge() {}

    /** The host's render type shape: which holder, which format and mode types, and which old calls it still has. */
    enum Shape {
        // 1.21.11 and 26.1 keep the vertex mode, draw(), bufferSize() and mode(), with output targets and buffer sizes.
        V26_1(HOLDER + "261", OLD_FORMAT, OLD_MODE, true),
        // 26.2 renamed the mode to a primitive topology and dropped draw() and bufferSize().
        V26_2(HOLDER + "262", OLD_FORMAT, "Lcom/mojang/blaze3d/PrimitiveTopology;", false),
        // 26.3 moved both types into renderpearl and dropped output targets.
        V26_3(HOLDER, HOST_FORMAT, HOST_TOPOLOGY, false);

        final String holder;
        final String format;
        final String topology;
        final boolean keepsOldCalls;

        Shape(String holder, String format, String topology, boolean keepsOldCalls) {
            this.holder = holder;
            this.format = format;
            this.topology = topology;
            this.keepsOldCalls = keepsOldCalls;
        }
    }

    /** Registers on 26.3 hosts, from the 26.3 layer. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read, Shape.V26_3);
    }

    /** Registers on 1.21.11 (Forge and NeoForge), 26.1 and 26.2 hosts; a 26.3 host is left to its own layer. */
    public static void registerBefore263(RetromodTransformer t) {
        Shape shape = shape(ClassResourceInspector.read(SETUP_BUILDER), ClassResourceInspector.read(OLD_STATE));
        if (shape == Shape.V26_1 || shape == Shape.V26_2) register(t, ClassResourceInspector::read, shape);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host, Shape wanted) {
        if (shape(host.apply(SETUP_BUILDER), host.apply(OLD_STATE)) != wanted) return;
        RebuiltApiClasses.registerTree(t, SHARD);
        RebuiltApiClasses.registerTree(t, STATE);
        RebuiltApiClasses.registerTree(t, wanted.holder);
        registerClasses(t, wanted);
        registerFactories(t, wanted);
        registerInstanceCalls(t, wanted);
        registerConstants(t);
    }

    /** Shards and the composite state become the rebuilt values; a created type is the host's own render type. */
    static void registerClasses(RetromodTransformer t, Shape shape) {
        t.registerClassRedirect(OLD_SHARD, SHARD);
        for (String kind : SHARD_KINDS) t.registerClassRedirect(OLD_SHARD + "$" + kind, SHARD + "$" + kind);
        t.registerClassRedirect(OLD_STATE, STATE);
        t.registerClassRedirect(OLD_STATE + "$CompositeStateBuilder", STATE + "$CompositeStateBuilder");
        t.registerClassRedirect(OLD_COMPOSITE_TYPE, HOST_TYPE);
        // A class that extended the old RenderType extends the holder, which has its constructor and constants. No
        // mod built for 26.x can extend the host type, whose constructor is private, so rebasing it is safe too.
        t.registerSuperclassRebase(OLD_TYPE, shape.holder);
        t.registerSuperclassRebase(HOST_TYPE, shape.holder);
    }

    /** RenderType.create(...) in both old shapes, as each spelling of its descriptor may reach the transformer. */
    static void registerFactories(RetromodTransformer t, Shape shape) {
        String host = "L" + HOST_TYPE + ";";
        String state = "L" + STATE + ";";
        String withFlags = "(" + STRING + shape.format + shape.topology + "IZZ" + state + ")" + host;
        String plain = "(" + STRING + shape.format + shape.topology + "I" + state + ")" + host;
        for (String[] formatAndMode : new String[][]{{shape.format, shape.topology}, {OLD_FORMAT, OLD_MODE}}) {
            for (String stateName : new String[]{OLD_STATE, STATE}) {
                for (String returned : new String[]{OLD_COMPOSITE_TYPE, HOST_TYPE}) {
                    String head = "(" + STRING + formatAndMode[0] + formatAndMode[1];
                    String tail = "L" + stateName + ";)L" + returned + ";";
                    for (String owner : new String[]{OLD_TYPE, HOST_TYPE}) {
                        t.registerMethodRedirect(owner, "create", head + "IZZ" + tail, shape.holder, "create", withFlags);
                        t.registerMethodRedirect(owner, "create", head + "I" + tail, shape.holder, "create", plain);
                    }
                }
            }
        }
    }

    /** Calls on a render type the host no longer answers, sent to the resolver or to the host's new name. */
    static void registerInstanceCalls(RetromodTransformer t, Shape shape) {
        for (String owner : new String[]{OLD_TYPE, HOST_TYPE}) {
            t.registerMethodRedirect(owner, "setupRenderState", "()V", RESOLVER, "setupRenderState", "(Ljava/lang/Object;)V", true);
            t.registerMethodRedirect(owner, "clearRenderState", "()V", RESOLVER, "clearRenderState", "(Ljava/lang/Object;)V", true);
            // 26.1 still answers draw(), bufferSize() and mode() itself.
            if (shape.keepsOldCalls) continue;
            t.registerMethodRedirect(owner, "bufferSize", "()I", RESOLVER, "bufferSize", "(Ljava/lang/Object;)I", true);
            t.registerMethodRedirect(owner, "draw", "(Lcom/mojang/blaze3d/vertex/MeshData;)V", RESOLVER, "draw",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
            for (String mode : new String[]{shape.topology, OLD_MODE}) {
                t.registerMethodRedirect(owner, "mode", "()" + mode, HOST_TYPE, "primitiveTopology", "()" + shape.topology);
            }
        }
    }

    /** Shard constants read through RenderType itself, which inherited them from RenderStateShard. */
    static void registerConstants(RetromodTransformer t) {
        for (String name : shardConstants()) {
            for (String owner : new String[]{OLD_TYPE, HOST_TYPE}) t.registerFieldRedirect(owner, name, SHARD, name);
        }
    }

    /** The public static constants of the rebuilt shard class, read from its own bytes. */
    static List<String> shardConstants() {
        List<String> names = new ArrayList<>();
        try (InputStream in = LegacyRenderTypeBridge.class.getClassLoader().getResourceAsStream(SHARD + ".class")) {
            if (in == null) return names;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            for (FieldNode field : node.fields) {
                int wanted = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
                if ((field.access & wanted) == wanted) names.add(field.name);
            }
        } catch (IOException unreadable) {
            // Without the constants only reads through RenderType itself stay unresolved.
        }
        return names;
    }

    /** The host's shape from its render setup builder: output targets and buffer sizes on 26.1, output targets on 26.2,
     *  neither on 26.3. A host that still has the old composite state needs nothing; offline, the target version decides. */
    static Shape shape(ClassNode setupBuilder, ClassNode oldState) {
        if (oldState != null) return null;
        if (setupBuilder == null) return shapeForVersion(RetromodVersion.TARGET_MC_VERSION);
        boolean outputs = hasMethod(setupBuilder, "setOutputTarget");
        boolean bufferSizes = hasMethod(setupBuilder, "bufferSize");
        if (outputs && bufferSizes) return Shape.V26_1;
        return outputs ? Shape.V26_2 : Shape.V26_3;
    }

    static Shape shapeForVersion(String version) {
        if (RetromodVersion.compareMcVersions(version, "26.3") >= 0) return Shape.V26_3;
        if (RetromodVersion.compareMcVersions(version, "26.2") >= 0) return Shape.V26_2;
        return RetromodVersion.compareMcVersions(version, "1.21.11") >= 0 ? Shape.V26_1 : null;
    }

    private static boolean hasMethod(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }
}
