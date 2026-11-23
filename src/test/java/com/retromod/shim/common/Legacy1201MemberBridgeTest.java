/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.retromod.shim.common.Legacy1201MemberBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** The members 1.20.1 mods call reach their rebuilt forms on 26.x hosts, and stay as they are where the host keeps them. */
class Legacy1201MemberBridgeTest {

    private static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    private String savedVersion;

    /** Each old call: opcode, owner, name, descriptor, the helper the call must reach, and the helper's method when renamed. */
    private static final String[][] OLD_CALLS = {
            {"virtual", SERVER_PLAYER, "isRespawnForced", "()Z", RESPAWN},
            {"virtual", SERVER_PLAYER, "getRespawnPosition", "()" + L_POS, RESPAWN},
            {"virtual", SERVER_PLAYER, "setRespawnPosition", SET_RESPAWN, RESPAWN},
            {"virtual", SERVER_LEVEL, "getSharedSpawnPos", "()" + L_POS, RESPAWN},
            {"static", PLAYER, "findRespawnPositionAndUseSpawnBlock", FIND_RESPAWN, RESPAWN},
            {"static", SERVER_PLAYER, "findRespawnPositionAndUseSpawnBlock", FIND_RESPAWN, RESPAWN},
            {"static", NBT_UTILS, "readGameProfile", "(" + L_COMPOUND + ")" + L_PROFILE, PROFILE_TAGS},
            {"virtual", USER, "getGameProfile", "()" + L_PROFILE, CLIENT_MEMBERS},
            {"virtual", MINECRAFT, "getProfiler", "()Lnet/minecraft/util/profiling/ProfilerFiller;", CLIENT_MEMBERS},
            {"virtual", SPRITE, "getU", "(D)F", CLIENT_MEMBERS},
            {"virtual", OS, "openUri", "(Ljava/lang/String;)V", OS_OPENERS},
            {"interface", LEVEL_ACCESSOR, "levelEvent", "(L" + PLAYER + ";I" + L_POS + "I)V", LEVEL_EVENTS},
            {"virtual", SERVER_LEVEL, "gameEvent", "(" + L_ENTITY + L_GAME_EVENT + L_POS + ")V", LEVEL_EVENTS},
            {"virtual", PLACE_SETTINGS, "setKeepLiquids", "(Z)L" + PLACE_SETTINGS + ";", STRUCTURE_SETTINGS},
            {"virtual", BUF, "readAnySizeNbt", "()" + L_COMPOUND, NBT_READS},
            {"virtual", BUF, "readComponent", "()Lnet/minecraft/network/chat/Component;", BUF_COMPONENTS},
            {"static", INGREDIENT, "of", "(Lnet/minecraft/tags/TagKey;)L" + INGREDIENT + ";", INGREDIENTS},
            {"virtual", MINECRAFT, "getTextureAtlas", "(L" + IDENTIFIER + ";)Ljava/util/function/Function;", MODEL_ATLASES},
            {"static", NBT_IO, "read", "(Ljava/io/File;)" + L_COMPOUND, NBT_FILES},
            {"static", IDENTIFIER, "isValidResourceLocation", "(Ljava/lang/String;)Z", IDENTIFIER_CHECKS},
            {"static", FABRIC_DIMENSIONS, "teleport", TELEPORT, PORTAL_INFOS},
            {"virtual", BLOCK_ITEM, "updateCustomBlockEntityTag", OLD_UPDATE_TAG, BLOCK_ITEMS},
            {"virtual", MODEL, "renderToBuffer", "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    CLIENT_MEMBERS},
            {"virtual", SCREEN, "extractBackground", "(L" + GRAPHICS + ";)V", CLIENT_MEMBERS, "renderBackground"}};

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("offline for 26.3, each old call reaches its rebuilt helper")
    void oldCallsReachTheirHelpersOn263() {
        List<String> refs = transform("26.3", name -> null);
        for (String[] call : OLD_CALLS) {
            String helperMethod = call.length > 5 ? call[5] : call[2];
            assertTrue(refs.stream().anyMatch(ref -> ref.startsWith(call[4] + "." + helperMethod + "(")),
                    call[2] + " must reach " + call[4] + ": " + refs);
        }
        assertTrue(refs.contains(MINECRAFT + ".schedule(Ljava/lang/Runnable;)V"), "tell must become schedule: " + refs);
        assertTrue(refs.contains(CLIENT_MEMBERS + ".renderDebug(L" + OPTIONS + ";)Z"), "the debug switch read must reach the overlay: " + refs);
        assertTrue(refs.contains(CLIENT_MEMBERS + ".setRenderDebug(L" + OPTIONS + ";Z)V"), "a write must become the setter: " + refs);
        assertTrue(refs.contains(MINECRAFT + ".getCameraEntity()" + L_ENTITY), refs.toString());
        assertTrue(refs.contains("com/mojang/blaze3d/vertex/PoseStack.mulPose(Lorg/joml/Matrix4fc;)V"), refs.toString());
        assertTrue(refs.contains("F " + RENDER_SHAPE + ".INVISIBLE"), refs.toString());
    }

    @Test
    @DisplayName("a host that still has a member keeps the mod's call to it")
    void aHostWithTheOldMemberKeepsTheCall() {
        ClassNode hostPlayer = new ClassNode();
        hostPlayer.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "isRespawnForced", "()Z", null, null));
        ClassNode hostSettings = new ClassNode();
        hostSettings.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "setKeepLiquids", "(Z)L" + PLACE_SETTINGS + ";", null, null));

        List<String> refs = transform("26.3", name -> name.equals(SERVER_PLAYER) ? hostPlayer
                : name.equals(PLACE_SETTINGS) ? hostSettings : null);

        assertTrue(refs.contains(SERVER_PLAYER + ".isRespawnForced()Z"), refs.toString());
        assertTrue(refs.contains(PLACE_SETTINGS + ".setKeepLiquids(Z)L" + PLACE_SETTINGS + ";"), refs.toString());
        assertTrue(refs.stream().anyMatch(ref -> ref.startsWith(RESPAWN + ".getRespawnPosition(")),
                "the getters the host lacks are still bridged: " + refs);
    }

    @Test
    @DisplayName("26.2 keeps the openers, the structure lookups and the block item hook that 26.3 removed")
    void removalsOf263StayOffOn262() {
        List<String> refs = transform("26.2", name -> null);
        assertTrue(refs.contains(OS + ".openUri(Ljava/lang/String;)V"), refs.toString());
        assertTrue(refs.contains(BLOCK_ITEM + ".updateCustomBlockEntityTag" + OLD_UPDATE_TAG), refs.toString());
        assertTrue(refs.stream().anyMatch(ref -> ref.startsWith(RESPAWN + ".isRespawnForced(")),
                "older removals still apply on 26.2: " + refs);
    }

    @Test
    @DisplayName("a target before 26.1 registers nothing, since the helpers are built on 26.x shapes")
    void olderTargetsRegisterNothing() {
        List<String> refs = transform("1.21.1", name -> null);
        for (String[] call : OLD_CALLS) {
            assertFalse(refs.stream().anyMatch(ref -> ref.startsWith(call[4] + ".")), call[2] + ": " + refs);
        }
    }

    @Test
    @DisplayName("the 1.20.1 portal target becomes a transition built by the helper")
    void portalInfoConstructorReachesTheFactory() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Legacy1201MemberBridge.register(t, name -> null);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/era1201/Portal", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "target", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, TRANSITION);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSITION, "<init>", PORTAL_INFO, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        List<String> refs = refs(t.transformClass(writer.toByteArray(), "test/era1201/Portal"));
        assertTrue(refs.contains(PORTAL_INFOS + ".create(" + L_VEC + L_VEC + "FF)L" + TRANSITION + ";"), refs.toString());
    }

    @Test
    @DisplayName("a 1.20.1 list's super call passes its own height and top, computed from the old top and bottom")
    void listConstructorTakesItsOwnBounds() throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Legacy1201MemberBridge.register(t, name -> null);
        String list = SELECTION_LISTS[1];

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/era1201/ModList", null, list, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + MINECRAFT + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        for (int value : new int[]{200, 240, 32, 208, 20}) mv.visitIntInsn(Opcodes.SIPUSH, value);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, list, "<init>", OLD_LIST_CTOR, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(t.transformClass(writer.toByteArray(), "test/era1201/ModList")).accept(out, 0);
        MethodNode ctor = out.methods.get(0);
        assertTrue(refs(out).contains(list + ".<init>" + NEW_LIST_CTOR), refs(out).toString());
        assertTrue(java.util.Arrays.stream(ctor.instructions.toArray()).anyMatch(insn -> insn.getOpcode() == Opcodes.ISUB),
                "the list's height is its bottom minus its top");
        new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter()).analyze(out.name, ctor);
    }

    @Test
    @DisplayName("a 1.20.1 model's super call gets an empty root part before its render type function")
    void modelConstructorGetsARoot() throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Legacy1201MemberBridge.register(t, name -> null);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/era1201/GlobeModel", null, MODEL, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MODEL, "<init>", OLD_MODEL_CTOR, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(t.transformClass(writer.toByteArray(), "test/era1201/GlobeModel")).accept(out, 0);
        List<String> refs = refs(out);
        assertTrue(refs.contains(MODEL + ".<init>" + NEW_MODEL_CTOR), refs.toString());
        assertTrue(refs.contains(MODEL_PART + ".<init>(Ljava/util/List;Ljava/util/Map;)V"), refs.toString());
        new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter())
                .analyze(out.name, out.methods.get(0));
    }

    @Test
    @DisplayName("an old draw named through EntityModel, a Model subclass, reaches the helper too")
    void oldDrawThroughEntityModelReachesTheHelper() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Legacy1201MemberBridge.register(t, name -> null);
        String entityModel = "net/minecraft/client/model/EntityModel";
        ClassWriter base = new ClassWriter(0);
        base.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, entityModel, null, MODEL, null);
        base.visitEnd();

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/era1201/Drawer", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "()V", null, null);
        mv.visitCode();
        call(mv, Opcodes.INVOKEVIRTUAL, entityModel, "renderToBuffer", OLD_MODEL_DRAW, true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        List<String> refs;
        try (var jar = t.pushJarClassBytesProvider(java.util.Map.of(entityModel, base.toByteArray())::get)) {
            refs = refs(t.transformClass(writer.toByteArray(), "test/era1201/Drawer"));
        }
        assertTrue(refs.contains(CLIENT_MEMBERS + ".renderToBuffer(L" + MODEL + ";" + OLD_MODEL_DRAW.substring(1)), refs.toString());
    }

    private List<String> transform(String target, Function<String, ClassNode> host) {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Legacy1201MemberBridge.register(t, host);
        ClassNode out = new ClassNode();
        new ClassReader(t.transformClass(oldCalls(), "test/era1201/Old")).accept(out, 0);
        // Every rewrite keeps the stack balanced, reads and writes alike.
        for (MethodNode method : out.methods) {
            try {
                new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicInterpreter()).analyze(out.name, method);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException e) {
                throw new AssertionError("the rewritten calls must keep a valid stack: " + e.getMessage(), e);
            }
        }
        return refs(out);
    }

    /** One method that makes every old call, with null or zero arguments. */
    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/era1201/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (String[] call : OLD_CALLS) {
            boolean isStatic = call[0].equals("static");
            call(mv, isStatic ? Opcodes.INVOKESTATIC : call[0].equals("interface") ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                    call[1], call[2], call[3], !isStatic);
        }
        call(mv, Opcodes.INVOKEVIRTUAL, MINECRAFT, "tell", "(Ljava/lang/Runnable;)V", true);
        call(mv, Opcodes.INVOKEVIRTUAL, "com/mojang/blaze3d/vertex/PoseStack", "mulPoseMatrix", "(Lorg/joml/Matrix4f;)V", true);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, OPTIONS, "renderDebug", "Z");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, OPTIONS, "renderDebug", "Z");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, MINECRAFT, "cameraEntity", L_ENTITY);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, RENDER_SHAPE, "ENTITYBLOCK_ANIMATED", "L" + RENDER_SHAPE + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Pushes a receiver when asked and a default for each argument, calls, and drops any result. */
    private static void call(MethodVisitor mv, int opcode, String owner, String name, String desc, boolean receiver) {
        if (receiver) mv.visitInsn(Opcodes.ACONST_NULL);
        for (Type argument : Type.getArgumentTypes(desc)) mv.visitInsn(defaultOf(argument));
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == Opcodes.INVOKEINTERFACE);
        Type result = Type.getReturnType(desc);
        if (result.getSort() != Type.VOID) mv.visitInsn(result.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
    }

    private static int defaultOf(Type type) {
        return switch (type.getSort()) {
            case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.DOUBLE -> Opcodes.DCONST_0;
            case Type.LONG -> Opcodes.LCONST_0;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL;
            default -> Opcodes.ICONST_0;
        };
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return refs(node);
    }

    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
