/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * 26.2 render-API stand-ins: MultiBufferSource / BufferSource / Tesselator.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.GeneratedLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * MC 26.2 deleted {@code net/minecraft/client/renderer/MultiBufferSource} (and its
 * {@code $BufferSource}) and {@code com/mojang/blaze3d/vertex/Tesselator}, two of the most
 * referenced client rendering types in modding; it also replaced the {@code VertexFormat$Mode}
 * enum with {@code com/mojang/blaze3d/PrimitiveTopology} (verified against the real 26.1.2 and
 * 26.2 client jars: both classes exist on 26.1.2 and are absent on 26.2, and PrimitiveTopology
 * carries the identical constants LINES/DEBUG_LINES/DEBUG_LINE_STRIP/POINTS/TRIANGLES/
 * TRIANGLE_STRIP/TRIANGLE_FAN/QUADS with the same public fields). A 1.21.x mod translated to
 * 26.1 loads and runs; carried on to 26.2 it dies with {@code NoClassDefFoundError} as soon as
 * ANY of its classes referencing these types loads, even if the render path never runs.
 *
 * <p>Stand-ins generated here (embedded per-mod, then class-redirected):
 * <ul>
 *   <li>{@code MultiBufferSource} interface with the exact 26.1 shape: abstract
 *       {@code getBuffer(rendertype/RenderType)} plus the {@code immediate(...)} statics.</li>
 *   <li>{@code BufferSource}: {@code getBuffer} returns a REAL 26.2
 *       {@code BufferBuilder} constructed from the RenderType's own {@code format()} and
 *       {@code primitiveTopology()} (both verified present on 26.2), so mod geometry code
 *       actually runs. The {@code endBatch} family is STAGED: building works, frame submission
 *       needs the 26.2 submit pipeline, so it warns once and drops the batch instead of
 *       crashing.</li>
 *   <li>{@code Tesselator}: {@code getInstance()/begin(topology, format)} returns a real
 *       {@code BufferBuilder} backed by a {@code ByteBufferBuilder} (26.2 public ctors
 *       verified), so immediate-mode geometry code links and builds.</li>
 * </ul>
 *
 * <p>Registered from the 26.1 to 26.2 shim layer on all loaders. This makes render-referencing
 * mods LOAD on 26.2 and lets their buffer-building code execute; actually drawing the built
 * geometry through {@code SubmitNodeCollector.submitCustomGeometry} is the follow-up stage.
 */
public final class RenderBufferSynthetics {

    private RenderBufferSynthetics() {}

    public static final String MBS = "com/retromod/shim/common/embedded/MultiBufferSource";
    public static final String BS = "com/retromod/shim/common/embedded/MultiBufferSourceImpl";
    public static final String TES = "com/retromod/shim/common/embedded/Tesselator";

    private static final String OLD_MBS = "net/minecraft/client/renderer/MultiBufferSource";
    private static final String OLD_BS = "net/minecraft/client/renderer/MultiBufferSource$BufferSource";
    private static final String OLD_TES = "com/mojang/blaze3d/vertex/Tesselator";
    private static final String OLD_MODE = "com/mojang/blaze3d/vertex/VertexFormat$Mode";
    private static final String TOPOLOGY = "com/mojang/blaze3d/PrimitiveTopology";

    static final String LEVEL_CONTEXT = "net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderContext";
    // Fabric API's older names for the context, in either package an old mod may name.
    static final String[] OLD_WORLD_CONTEXTS = {"net/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext",
            "net/fabricmc/fabric/api/client/rendering/v1/world/WorldRenderContext"};
    private static final String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String BUFFER_BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String BYTE_BUFFER_BUILDER = "com/mojang/blaze3d/vertex/ByteBufferBuilder";
    private static final String VERTEX_FORMAT = "com/mojang/blaze3d/vertex/VertexFormat";
    private static final String DEFAULT_VERTEX_FORMAT = "com/mojang/blaze3d/vertex/DefaultVertexFormat";

    private static final String L_RT = "L" + RENDER_TYPE + ";";
    private static final String L_VC = "L" + VERTEX_CONSUMER + ";";
    private static final String L_BB = "L" + BUFFER_BUILDER + ";";
    private static final String L_BBB = "L" + BYTE_BUFFER_BUILDER + ";";
    private static final String L_VF = "L" + VERTEX_FORMAT + ";";
    private static final String L_TOPO = "L" + TOPOLOGY + ";";
    private static final String L_MBS = "L" + MBS + ";";
    private static final String L_BS = "L" + BS + ";";
    static final String LEGACY_BEGIN = "(" + L_BB + "Ljava/lang/Object;Ljava/lang/Object;)V";

    /** Starting size of the sink builder's buffer, which grows as needed. */
    private static final int SINK_CAPACITY = 1024;

    /** Default per-RenderType buffer capacity, matching vanilla's transient buffer size. */
    private static final int BUFFER_CAPACITY = 786432;

    /** Register the synthetics and every redirect mapping the deleted 26.1 types onto them. */
    public static void register(RetromodTransformer t) {
        t.registerSyntheticClass(MBS, generateInterface());
        t.registerSyntheticClass(BS, generateBufferSource());
        t.registerSyntheticClass(TES, generateTesselator());

        t.registerClassRedirect(OLD_MBS, MBS);
        t.registerClassRedirect(OLD_BS, BS);
        t.registerClassRedirect(OLD_TES, TES);
        // VertexFormat$Mode -> PrimitiveTopology: a real 26.2 enum with the identical constant
        // set and public fields, so GETSTATIC Mode.QUADS, ordinal(), values() all keep working.
        t.registerClassRedirect(OLD_MODE, TOPOLOGY);
        registerRemovedAccessors(t);
        registerPre121BuilderCalls(t);
        // A game call still naming a stand-in links nowhere; it is dropped last, and one that returned a buffer source
        // or tesselator gets the shared stand-in, whose batches are dropped like every other.
        t.registerStandInType(MBS, BS + ".shared()" + L_BS);
        t.registerStandInType(BS, BS + ".shared()" + L_BS);
        t.registerStandInType(TES, TES + ".getInstance()L" + TES + ";");
        // A dropped call that returned a vertex consumer or builder gets a sink builder, whose vertices are dropped.
        t.registerStandInResult(VERTEX_CONSUMER, TES + ".sink()" + L_BB);
        t.registerStandInResult(BUFFER_BUILDER, TES + ".sink()" + L_BB);
    }

    /**
     * The 1.20.x immediate mode, where {@code getBuilder()} handed out the one builder, {@code begin(mode, format)}
     * started it and {@code end()} drew it. Each old builder is a position-only builder on the stand-in's buffer, so every
     * old vertex call links and runs; like the other stand-in batches, nothing it builds is drawn.
     */
    private static void emitPre121ImmediateMode(ClassWriter cw) {
        // public static synchronized BufferBuilder sink(): a builder of a small tesselator kept for dropped calls, so
        // handing one out never resets the shared tesselator's builder a mod may still be filling.
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "sinkTesselator", "L" + TES + ";", null, null).visitEnd();
        MethodVisitor sk = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED, "sink", "()" + L_BB, null, null);
        sk.visitCode();
        Label ready = new Label();
        sk.visitFieldInsn(GETSTATIC, TES, "sinkTesselator", "L" + TES + ";");
        sk.visitJumpInsn(IFNONNULL, ready);
        sk.visitTypeInsn(NEW, TES);
        sk.visitInsn(DUP);
        sk.visitIntInsn(SIPUSH, SINK_CAPACITY);
        sk.visitMethodInsn(INVOKESPECIAL, TES, "<init>", "(I)V", false);
        sk.visitFieldInsn(PUTSTATIC, TES, "sinkTesselator", "L" + TES + ";");
        sk.visitLabel(ready);
        sk.visitFieldInsn(GETSTATIC, TES, "sinkTesselator", "L" + TES + ";");
        sk.visitMethodInsn(INVOKEVIRTUAL, TES, "getBuilder", "()" + L_BB, false);
        sk.visitInsn(ARETURN);
        sk.visitMaxs(0, 0);
        sk.visitEnd();

        // public static BufferBuilder legacyBuilder(int capacity): the 1.20.x new BufferBuilder(capacity), a builder of
        // its own stand-in tesselator, so its begin, vertex and end calls run like the shared one's.
        MethodVisitor nb = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "legacyBuilder", "(I)" + L_BB, null, null);
        nb.visitCode();
        nb.visitTypeInsn(NEW, TES);
        nb.visitInsn(DUP);
        nb.visitVarInsn(ILOAD, 0);
        nb.visitMethodInsn(INVOKESPECIAL, TES, "<init>", "(I)V", false);
        nb.visitMethodInsn(INVOKEVIRTUAL, TES, "getBuilder", "()" + L_BB, false);
        nb.visitInsn(ARETURN);
        nb.visitMaxs(0, 0);
        nb.visitEnd();

        // public BufferBuilder getBuilder(): drops what the last old builder left, then starts a new one.
        MethodVisitor gb = cw.visitMethod(ACC_PUBLIC, "getBuilder", "()" + L_BB, null, null);
        gb.visitCode();
        gb.visitVarInsn(ALOAD, 0);
        gb.visitFieldInsn(GETFIELD, TES, "buffer", L_BBB);
        gb.visitMethodInsn(INVOKEVIRTUAL, BYTE_BUFFER_BUILDER, "clear", "()V", false);
        gb.visitVarInsn(ALOAD, 0);
        gb.visitFieldInsn(GETSTATIC, TOPOLOGY, "QUADS", L_TOPO);
        gb.visitFieldInsn(GETSTATIC, DEFAULT_VERTEX_FORMAT, "POSITION", L_VF);
        gb.visitMethodInsn(INVOKEVIRTUAL, TES, "begin", "(" + L_TOPO + L_VF + ")" + L_BB, false);
        gb.visitInsn(ARETURN);
        gb.visitMaxs(0, 0);
        gb.visitEnd();

        // public void end(): the old draw; the geometry is discarded instead.
        MethodVisitor en = cw.visitMethod(ACC_PUBLIC, "end", "()V", null, null);
        en.visitCode();
        en.visitVarInsn(ALOAD, 0);
        en.visitFieldInsn(GETFIELD, TES, "buffer", L_BBB);
        en.visitMethodInsn(INVOKEVIRTUAL, BYTE_BUFFER_BUILDER, "clear", "()V", false);
        en.visitInsn(RETURN);
        en.visitMaxs(0, 0);
        en.visitEnd();

        // public static void legacyBegin(BufferBuilder, Object mode, Object format): the old builder's begin, which
        // has nothing left to start. Plain Object parameters keep the descriptor the same on 26.2 and 26.3.
        MethodVisitor lb = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "legacyBegin", LEGACY_BEGIN, null, null);
        lb.visitCode();
        lb.visitInsn(RETURN);
        lb.visitMaxs(0, 0);
        lb.visitEnd();
    }

    /** The old builder's {@code begin(mode, format)} and {@code end()}, under every name its arguments may have by now. */
    static void registerPre121BuilderCalls(RetromodTransformer t) {
        String[][] beginArgs = {{OLD_MODE, VERTEX_FORMAT}, {TOPOLOGY, VERTEX_FORMAT},
                {"com/mojang/renderpearl/api/pipeline/PrimitiveTopology", "com/mojang/renderpearl/api/vertex/VertexFormat"}};
        for (String[] args : beginArgs) {
            t.registerMethodRedirect(BUFFER_BUILDER, "begin", "(L" + args[0] + ";L" + args[1] + ";)V", TES, "legacyBegin",
                    LEGACY_BEGIN, true);
        }
        // 1.21 builders take their memory, topology and format up front, so the old capacity form builds a stand-in's.
        t.registerConstructorRedirect(BUFFER_BUILDER, "(I)V", TES, "legacyBuilder", "(I)" + L_BB);
        // end() returned the finished buffer, which build() returns now.
        t.registerMethodRedirect(BUFFER_BUILDER, "end", "()Lcom/mojang/blaze3d/vertex/MeshData;", BUFFER_BUILDER, "build",
                "()Lcom/mojang/blaze3d/vertex/MeshData;");
    }

    /**
     * Host members that handed out the removed types are gone with them. The link check names them by the stand-in,
     * since the class redirect rewrites their descriptors, so each one needs a redirect to the stand-in's own source.
     */
    static void registerRemovedAccessors(RetromodTransformer t) {
        // RenderSystem.renderThreadTesselator() was the render thread's shared tesselator.
        t.registerMethodRedirect("com/mojang/blaze3d/systems/RenderSystem", "renderThreadTesselator",
                "()L" + OLD_TES + ";", TES, "getInstance", "()L" + TES + ";");
        // Fabric's level render context handed out the frame's buffer source; 26.2 submits nodes instead, so old
        // code gets the shared stand-in, whose batches are dropped with one warning.
        if (!levelContextHasConsumers(ClassResourceInspector.read(LEVEL_CONTEXT))) {
            java.util.List<String> owners = new java.util.ArrayList<>(java.util.List.of(OLD_WORLD_CONTEXTS));
            owners.add(LEVEL_CONTEXT);
            for (String owner : owners) {
                // The context becomes the helper's argument, which it ignores.
                t.registerMethodRedirect(owner, "consumers", "()L" + OLD_MBS + ";", BS, "sharedFor", "(Ljava/lang/Object;)" + L_BS, true);
            }
        }
    }

    /** Whether the host's Fabric level render context still has consumers(); a host without Fabric API reads null. */
    static boolean levelContextHasConsumers(org.objectweb.asm.tree.ClassNode context) {
        if (context == null) return false;
        for (org.objectweb.asm.tree.MethodNode method : context.methods) {
            if (method.name.equals("consumers")) return true;
        }
        return false;
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    /** The MultiBufferSource stand-in interface, exact 26.1 shape. */
    public static byte[] generateInterface() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, MBS, null, "java/lang/Object", null);

        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getBuffer", "(" + L_RT + ")" + L_VC, null, null)
                .visitEnd();

        // static BufferSource immediate(ByteBufferBuilder b) { return new Impl(b); }
        MethodVisitor im = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "immediate",
                "(" + L_BBB + ")" + L_BS, null, null);
        im.visitCode();
        im.visitTypeInsn(NEW, BS);
        im.visitInsn(DUP);
        im.visitVarInsn(ALOAD, 0);
        im.visitMethodInsn(INVOKESPECIAL, BS, "<init>", "(" + L_BBB + ")V", false);
        im.visitInsn(ARETURN);
        im.visitMaxs(0, 0);
        im.visitEnd();

        // static BufferSource immediateWithBuffers(SequencedMap m, ByteBufferBuilder b):
        // the per-type fixed buffers are an allocation optimization; the stand-in builds
        // per-RenderType buffers itself, so the map is accepted and ignored.
        MethodVisitor iwb = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "immediateWithBuffers",
                "(Ljava/util/SequencedMap;" + L_BBB + ")" + L_BS, null, null);
        iwb.visitCode();
        iwb.visitTypeInsn(NEW, BS);
        iwb.visitInsn(DUP);
        iwb.visitVarInsn(ALOAD, 1);
        iwb.visitMethodInsn(INVOKESPECIAL, BS, "<init>", "(" + L_BBB + ")V", false);
        iwb.visitInsn(ARETURN);
        iwb.visitMaxs(0, 0);
        iwb.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The BufferSource stand-in: real per-RenderType 26.2 BufferBuilders; staged endBatch.
     */
    public static byte[] generateBufferSource() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC, BS, null, "java/lang/Object", new String[]{MBS});

        cw.visitField(ACC_PRIVATE | ACC_FINAL, "buffers", "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "warned", "Z", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "shared", L_BS, null, null).visitEnd();

        // public static synchronized BufferSource shared(): one stand-in per mod copy, so repeated calls do not
        // allocate a new set of buffers each frame.
        MethodVisitor sh = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED, "shared", "()" + L_BS, null, null);
        sh.visitCode();
        sh.visitFieldInsn(GETSTATIC, BS, "shared", L_BS);
        Label made = new Label();
        sh.visitJumpInsn(IFNONNULL, made);
        sh.visitTypeInsn(NEW, BS);
        sh.visitInsn(DUP);
        sh.visitTypeInsn(NEW, BYTE_BUFFER_BUILDER);
        sh.visitInsn(DUP);
        sh.visitIntInsn(SIPUSH, 256);
        sh.visitMethodInsn(INVOKESPECIAL, BYTE_BUFFER_BUILDER, "<init>", "(I)V", false);
        sh.visitMethodInsn(INVOKESPECIAL, BS, "<init>", "(" + L_BBB + ")V", false);
        sh.visitFieldInsn(PUTSTATIC, BS, "shared", L_BS);
        sh.visitLabel(made);
        sh.visitFieldInsn(GETSTATIC, BS, "shared", L_BS);
        sh.visitInsn(ARETURN);
        sh.visitMaxs(0, 0);
        sh.visitEnd();

        // public static BufferSource sharedFor(Object context): the same, for a call whose receiver becomes the argument.
        MethodVisitor sf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sharedFor", "(Ljava/lang/Object;)" + L_BS, null, null);
        sf.visitCode();
        sf.visitMethodInsn(INVOKESTATIC, BS, "shared", "()" + L_BS, false);
        sf.visitInsn(ARETURN);
        sf.visitMaxs(0, 0);
        sf.visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_BBB + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitTypeInsn(NEW, "java/util/HashMap");
        c.visitInsn(DUP);
        c.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        c.visitFieldInsn(PUTFIELD, BS, "buffers", "Ljava/util/Map;");
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // public VertexConsumer getBuffer(RenderType rt):
        //   BufferBuilder b = (BufferBuilder) buffers.get(rt);
        //   if (b == null) {
        //     b = new BufferBuilder(new ByteBufferBuilder(CAP), rt.primitiveTopology(), rt.format());
        //     buffers.put(rt, b);
        //   }
        //   return b;
        MethodVisitor g = cw.visitMethod(ACC_PUBLIC, "getBuffer", "(" + L_RT + ")" + L_VC, null, null);
        g.visitCode();
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, BS, "buffers", "Ljava/util/Map;");
        g.visitVarInsn(ALOAD, 1);
        g.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        g.visitTypeInsn(CHECKCAST, BUFFER_BUILDER);
        g.visitVarInsn(ASTORE, 2);
        g.visitVarInsn(ALOAD, 2);
        Label have = new Label();
        g.visitJumpInsn(IFNONNULL, have);
        g.visitTypeInsn(NEW, BUFFER_BUILDER);
        g.visitInsn(DUP);
        g.visitTypeInsn(NEW, BYTE_BUFFER_BUILDER);
        g.visitInsn(DUP);
        g.visitLdcInsn(BUFFER_CAPACITY);
        g.visitMethodInsn(INVOKESPECIAL, BYTE_BUFFER_BUILDER, "<init>", "(I)V", false);
        g.visitVarInsn(ALOAD, 1);
        g.visitMethodInsn(INVOKEVIRTUAL, RENDER_TYPE, "primitiveTopology", "()" + L_TOPO, false);
        g.visitVarInsn(ALOAD, 1);
        g.visitMethodInsn(INVOKEVIRTUAL, RENDER_TYPE, "format", "()" + L_VF, false);
        g.visitMethodInsn(INVOKESPECIAL, BUFFER_BUILDER, "<init>",
                "(" + L_BBB + L_TOPO + L_VF + ")V", false);
        g.visitVarInsn(ASTORE, 2);
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, BS, "buffers", "Ljava/util/Map;");
        g.visitVarInsn(ALOAD, 1);
        g.visitVarInsn(ALOAD, 2);
        g.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        g.visitInsn(POP);
        g.visitLabel(have);
        g.visitVarInsn(ALOAD, 2);
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        // The endBatch family: STAGED. Warn once, drop the built batches, never crash.
        emitEndBatch(cw, "endBatch", "()V");
        emitEndBatch(cw, "endBatch", "(" + L_RT + ")V");
        emitEndBatch(cw, "endLastBatch", "()V");

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitEndBatch(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        // if (!warned) { warned = true; warn once in the game log; }
        m.visitFieldInsn(GETSTATIC, BS, "warned", "Z");
        Label skip = new Label();
        m.visitJumpInsn(IFNE, skip);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTSTATIC, BS, "warned", "Z");
        GeneratedLog.pushLogger(m);
        m.visitLdcInsn("[Retromod] An old mod drew through MultiBufferSource, which Retromod cannot draw on "
                + "this Minecraft version yet, so that mod's custom geometry does not show.");
        GeneratedLog.warn(m);
        m.visitLabel(skip);
        // buffers.clear(); - drop the batch so memory doesn't accumulate across frames
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, BS, "buffers", "Ljava/util/Map;");
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "clear", "()V", true);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** The Tesselator stand-in: 26.1 shape over the surviving 26.2 buffer classes. */
    public static byte[] generateTesselator() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC, TES, null, "java/lang/Object", null);

        cw.visitField(ACC_PRIVATE | ACC_STATIC, "INSTANCE", "L" + TES + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "buffer", L_BBB, null, null).visitEnd();

        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitTypeInsn(NEW, TES);
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, TES, "<init>", "()V", false);
        ci.visitFieldInsn(PUTSTATIC, TES, "INSTANCE", "L" + TES + ";");
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        // public Tesselator() { this(786432); }  /  public Tesselator(int cap)
        MethodVisitor c0 = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c0.visitCode();
        c0.visitVarInsn(ALOAD, 0);
        c0.visitLdcInsn(BUFFER_CAPACITY);
        c0.visitMethodInsn(INVOKESPECIAL, TES, "<init>", "(I)V", false);
        c0.visitInsn(RETURN);
        c0.visitMaxs(0, 0);
        c0.visitEnd();

        MethodVisitor c1 = cw.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        c1.visitCode();
        c1.visitVarInsn(ALOAD, 0);
        c1.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c1.visitVarInsn(ALOAD, 0);
        c1.visitTypeInsn(NEW, BYTE_BUFFER_BUILDER);
        c1.visitInsn(DUP);
        c1.visitVarInsn(ILOAD, 1);
        c1.visitMethodInsn(INVOKESPECIAL, BYTE_BUFFER_BUILDER, "<init>", "(I)V", false);
        c1.visitFieldInsn(PUTFIELD, TES, "buffer", L_BBB);
        c1.visitInsn(RETURN);
        c1.visitMaxs(0, 0);
        c1.visitEnd();

        MethodVisitor gi = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getInstance",
                "()L" + TES + ";", null, null);
        gi.visitCode();
        gi.visitFieldInsn(GETSTATIC, TES, "INSTANCE", "L" + TES + ";");
        gi.visitInsn(ARETURN);
        gi.visitMaxs(0, 0);
        gi.visitEnd();

        MethodVisitor in = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "()V", null, null);
        in.visitCode();
        in.visitInsn(RETURN);
        in.visitMaxs(0, 0);
        in.visitEnd();

        // public BufferBuilder begin(PrimitiveTopology mode, VertexFormat format)
        // (the mod's original VertexFormat$Mode parameter arrives as PrimitiveTopology
        // through the class redirect; the constant set is identical)
        MethodVisitor b = cw.visitMethod(ACC_PUBLIC, "begin",
                "(" + L_TOPO + L_VF + ")" + L_BB, null, null);
        b.visitCode();
        b.visitTypeInsn(NEW, BUFFER_BUILDER);
        b.visitInsn(DUP);
        b.visitVarInsn(ALOAD, 0);
        b.visitFieldInsn(GETFIELD, TES, "buffer", L_BBB);
        b.visitVarInsn(ALOAD, 1);
        b.visitVarInsn(ALOAD, 2);
        b.visitMethodInsn(INVOKESPECIAL, BUFFER_BUILDER, "<init>",
                "(" + L_BBB + L_TOPO + L_VF + ")V", false);
        b.visitInsn(ARETURN);
        b.visitMaxs(0, 0);
        b.visitEnd();

        MethodVisitor cl = cw.visitMethod(ACC_PUBLIC, "clear", "()V", null, null);
        cl.visitCode();
        cl.visitInsn(RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();

        emitPre121ImmediateMode(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
