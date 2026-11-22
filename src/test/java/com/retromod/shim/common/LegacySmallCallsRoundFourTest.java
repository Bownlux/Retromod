/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The fourth round of removed members: boss bars, commands, inputs, labels, block sheets, rotations and vertex offsets. */
class LegacySmallCallsRoundFourTest {

    private static final String BOSS = "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/world/BossEvent$BossBarColor;"
            + "Lnet/minecraft/world/BossEvent$BossBarOverlay;)";
    private static final String WIDGET = "()L" + LegacySmallCallsBridge.STRING_WIDGET + ";";
    private static final String DISPATCH_ROTATION = "net/minecraft/client/renderer/block/dispatch/BlockModelRotation";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("each old call reaches its rebuilt form on a 26.3 host")
    void fourthRoundIsBridged() {
        RetromodTransformer t = fresh();
        LegacySmallCallsBridge.registerFourthRound(t, host263()::get);

        List<String> refs = refs(t.transformClass(oldCalls(LegacySmallCallsBridge.PEARL_FORMAT), "test/four/Old"));

        assertTrue(refs.contains(LegacySmallCallsBridge.BOSS_EVENTS + ".create" + BOSS + "L" + LegacySmallCallsBridge.BOSS_EVENT + ";"),
                refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.CLIENT + ".sendUnsignedCommand(L" + LegacySmallCallsBridge.PACKET_LISTENER
                + ";Ljava/lang/String;)Z"), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.CLIENT + ".selected(I)Z"), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.WORLD + ".getDescriptionId(L" + LegacySmallCallsBridge.ITEM_STACK
                + ";)Ljava/lang/String;"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.CLIENT + ".alignLeft(")), refs.toString());
        assertTrue(refs.contains("F " + DISPATCH_ROTATION + ".IDENTITY"), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.BLOCK_SHEETS + ".solidBlockSheet" + LegacySmallCallsBridge.RENDER_TYPE), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.CULLED_BLOCK_SHEETS + ".translucentCullBlockSheet"
                + LegacySmallCallsBridge.RENDER_TYPE), "26.3 has the culled type: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.PEARL_VERTEX_OFFSETS + ".getOffset(")), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("X0_Y0") || r.contains(".<init>" + BOSS)), refs.toString());
    }

    @Test
    @DisplayName("1.21.11 keeps its own sheets, offsets and rotation, and draws the culled sheet unculled")
    void olderHostsKeepWhatTheyHave() {
        RetromodTransformer t = fresh();
        ClassNode sheets = withMethods(new String[][]{{"solidBlockSheet", LegacySmallCallsBridge.RENDER_TYPE},
                {"cutoutBlockSheet", LegacySmallCallsBridge.RENDER_TYPE}});
        ClassNode format = withMethods(new String[][]{{"getOffset", "(L" + LegacySmallCallsBridge.BLAZE_FORMAT + "Element;)I"}});
        ClassNode boss = withMethods(new String[][]{{"<init>", BOSS + "V"}});
        Map<String, ClassNode> host = Map.of(LegacySmallCallsBridge.SHEETS, sheets, LegacySmallCallsBridge.BLAZE_FORMAT, format,
                LegacySmallCallsBridge.BOSS_EVENT, boss, LegacySmallCallsBridge.RENDER_TYPES, new ClassNode());
        LegacySmallCallsBridge.registerFourthRound(t, host::get);

        List<String> refs = refs(t.transformClass(oldCalls(LegacySmallCallsBridge.BLAZE_FORMAT), "test/four/Old"));

        assertTrue(refs.contains(LegacySmallCallsBridge.SHEETS + ".solidBlockSheet" + LegacySmallCallsBridge.RENDER_TYPE), refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.BLOCK_SHEETS + ".translucentBlockSheet" + LegacySmallCallsBridge.RENDER_TYPE),
                refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.BLAZE_FORMAT + ".getOffset(L" + LegacySmallCallsBridge.BLAZE_FORMAT + "Element;)I"),
                refs.toString());
        assertTrue(refs.contains(LegacySmallCallsBridge.BOSS_EVENT + ".<init>" + BOSS + "V"), refs.toString());
    }

    @Test
    @DisplayName("26.2 reads vertex offsets through the blaze3d helper")
    void blazeFormatOn262() {
        RetromodTransformer t = fresh();
        LegacySmallCallsBridge.registerVertexOffsets(t, Map.of(LegacySmallCallsBridge.BLAZE_FORMAT, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(oldCalls(LegacySmallCallsBridge.BLAZE_FORMAT), "test/four/Old"));

        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacySmallCallsBridge.VERTEX_OFFSETS + ".getOffset(")), refs.toString());
    }

    @Test
    @DisplayName("a boss bar subclass's old super call gets a random id first and keeps its own arguments in order")
    void bossSubclassGetsRandomId() throws Exception {
        RetromodTransformer t = fresh();
        LegacySmallCallsBridge.registerFourthRound(t, host263()::get);
        byte[] bar = t.transformClass(bossSubclass(), "test/four/Bar");

        Map<String, byte[]> classes = new java.util.HashMap<>();
        classes.put("test.four.Bar", bar);
        classes.put(LegacySmallCallsBridge.BOSS_EVENT.replace('/', '.'), recordingBossEvent());
        for (String part : new String[]{"net/minecraft/network/chat/Component", "net/minecraft/world/BossEvent$BossBarColor",
                "net/minecraft/world/BossEvent$BossBarOverlay"}) {
            classes.put(part.replace('/', '.'), emptyClass(part));
        }
        ClassLoader game = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Object[] parts = new Object[3];
        Class<?>[] types = new Class<?>[3];
        String[] names = {"net.minecraft.network.chat.Component", "net.minecraft.world.BossEvent$BossBarColor",
                "net.minecraft.world.BossEvent$BossBarOverlay"};
        for (int i = 0; i < 3; i++) {
            types[i] = game.loadClass(names[i]);
            parts[i] = types[i].getConstructor().newInstance();
        }
        Object created = game.loadClass("test.four.Bar").getConstructor(types).newInstance(parts);

        Object[] seen = (Object[]) created.getClass().getField("seen").get(created);
        assertInstanceOf(java.util.UUID.class, seen[0], "the id comes first");
        assertArrayEquals(parts, java.util.Arrays.copyOfRange(seen, 1, 4), "name, color and overlay keep their order");
    }

    /** {@code class Bar extends ServerBossEvent { Bar(name, color, overlay) { super(name, color, overlay); } }} */
    private static byte[] bossSubclass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/four/Bar", null, LegacySmallCallsBridge.BOSS_EVENT, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", BOSS + "V", null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 3; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacySmallCallsBridge.BOSS_EVENT, "<init>", BOSS + "V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 4);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A stand-in for the 26.x boss bar whose id constructor keeps what it was handed in {@code seen}. */
    private static byte[] recordingBossEvent() {
        String owner = LegacySmallCallsBridge.BOSS_EVENT;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "seen", "[Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/UUID;" + BOSS.substring(1) + "V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int slot = 1; slot <= 4; slot++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, slot - 1);
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitInsn(Opcodes.AASTORE);
        }
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, "seen", "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<String, ClassNode> host263() {
        ClassNode rotation = new ClassNode();
        rotation.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "IDENTITY", "L" + DISPATCH_ROTATION + ";", null, null));
        return Map.of(LegacySmallCallsBridge.SHEETS, new ClassNode(), LegacySmallCallsBridge.PEARL_FORMAT, new ClassNode(),
                LegacySmallCallsBridge.RENDER_TYPES, withMethods(new String[][]{{"entityTranslucentCull",
                        "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"}}),
                DISPATCH_ROTATION, rotation, LegacySmallCallsBridge.BOSS_EVENT, new ClassNode());
    }

    private static RetromodTransformer fresh() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        return t;
    }

    private static byte[] oldCalls(String format) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/four/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LegacySmallCallsBridge.BOSS_EVENT);
        mv.visitInsn(Opcodes.DUP);
        for (int i = 0; i < 3; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacySmallCallsBridge.BOSS_EVENT, "<init>", BOSS + "V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.PACKET_LISTENER, "sendUnsignedCommand", "(Ljava/lang/String;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySmallCallsBridge.COMMON_INPUTS, "selected", "(I)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.ITEM_STACK, "getDescriptionId", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySmallCallsBridge.STRING_WIDGET, "alignLeft", WIDGET, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, DISPATCH_ROTATION, "X0_Y0", "L" + DISPATCH_ROTATION + ";");
        mv.visitInsn(Opcodes.POP);
        for (String sheet : new String[]{"solidBlockSheet", "translucentCullBlockSheet"}) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySmallCallsBridge.SHEETS, sheet, LegacySmallCallsBridge.RENDER_TYPE, false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, format, "getOffset", "(L" + format + "Element;)I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(6, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode withMethods(String[][] methods) {
        ClassNode node = new ClassNode();
        for (String[] method : methods) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, method[0], method[1], null, null));
        return node;
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
