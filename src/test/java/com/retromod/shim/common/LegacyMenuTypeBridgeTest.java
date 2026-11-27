/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #412 (Ancient Revival, Forge 1.18.2 on Forge 1.20.1): a 1.19.2 or older mod builds menus with MenuType's one-argument constructor. */
class LegacyMenuTypeBridgeTest {

    private static final String MENU_TYPE = "net/minecraft/world/inventory/MenuType";
    private static final String OLD_CTOR = "(Lnet/minecraft/world/inventory/MenuType$MenuSupplier;)V";
    private static final String MOD = "test/menus/ModMenus";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyMenuTypeBridge.register(transformer, LegacyMenuTypeBridge.MOJANG, null, null);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new MenuType(supplier) builds the menu type through the helper")
    void directConstructionUsesTheHelper() {
        List<AbstractInsnNode> out = transform(MOD, "java/lang/Object", mv -> {
            mv.visitTypeInsn(NEW, MENU_TYPE);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, MENU_TYPE, "<init>", OLD_CTOR, false);
            mv.visitInsn(POP);
        });

        assertTrue(out.stream().noneMatch(i -> i instanceof TypeInsnNode t && t.getOpcode() == NEW),
                "the old allocation must be gone");
        MethodInsnNode call = only(out, MethodInsnNode.class);
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyMenuTypeBridge.HELPER, call.owner);
        assertEquals("create", call.name);
    }

    @Test
    @DisplayName("a MenuType subclass passes the default feature flags to its super call")
    void subclassSuperCallGetsTheFlags() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, MENU_TYPE, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", OLD_CTOR, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitMethodInsn(INVOKESPECIAL, MENU_TYPE, "<init>", OLD_CTOR, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD)).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        cn.methods.get(0).instructions.forEach(insns::add);
        FieldInsnNode flags = only(insns, FieldInsnNode.class);
        assertEquals("net/minecraft/world/flag/FeatureFlags", flags.owner);
        assertEquals("DEFAULT_FLAGS", flags.name);
        MethodInsnNode superCall = only(insns, MethodInsnNode.class);
        assertEquals(LegacyMenuTypeBridge.MOJANG.newCtor(), superCall.desc);
    }

    @Test
    @DisplayName("the helper builds the two-argument menu type, and on NeoForge sends a Forge factory to NeoForge's builder")
    void helpersAreWellFormed() {
        byte[] plain = LegacyMenuTypeBridge.generateHelper(LegacyMenuTypeBridge.MOJANG, null, null);
        byte[] neo = LegacyMenuTypeBridge.generateHelper(LegacyMenuTypeBridge.MOJANG, "test/Factory", "test/Menus");
        byte[] fabric = LegacyMenuTypeBridge.generateHelper(LegacyMenuTypeBridge.INTERMEDIARY, null, null);
        for (byte[] bytes : new byte[][]{plain, neo, fabric}) {
            new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
        assertTrue(calls(neo).contains("test/Menus.create"), "the NeoForge helper must use NeoForge's menu builder");
        assertFalse(calls(plain).contains("test/Menus.create"));
        assertTrue(calls(fabric).contains("net/minecraft/class_3917.<init>"), "a pre-26.1 Fabric host runs intermediary names");
        // Vanilla keeps the two-argument constructor private, so a refused call falls back to reflection.
        ClassNode helper = new ClassNode();
        new ClassReader(plain).accept(helper, 0);
        assertEquals("java/lang/IllegalAccessError", helper.methods.stream().filter(m -> m.name.equals("create"))
                .findFirst().orElseThrow().tryCatchBlocks.get(0).type);
        assertTrue(calls(plain).contains("java/lang/Class.getDeclaredConstructor"));
    }

    @Test
    @DisplayName("the NeoForge registration replaces the plain helper, and a later plain one does not undo it")
    void neoForgeHelperWins() {
        LegacyMenuTypeBridge.register(transformer, LegacyMenuTypeBridge.MOJANG, "test/Factory", "test/Menus");
        LegacyMenuTypeBridge.register(transformer, LegacyMenuTypeBridge.MOJANG, null, null);
        assertTrue(calls(transformer.getSyntheticClasses().get(LegacyMenuTypeBridge.HELPER)).contains("test/Menus.create"));
    }

    @Test
    @DisplayName("Fabric 26.x: the extended menu bridge's own helper does not replace this one")
    void extendedMenuBridgeKeepsItsOwnHelper() {
        String host = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.3";
            com.retromod.shim.fabric.Pre1_20_5ScreenHandlerBridge.registerMojang(transformer);
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = host;
        }
        ClassNode helper = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(LegacyMenuTypeBridge.HELPER)).accept(helper, 0);
        assertTrue(helper.methods.stream().anyMatch(m -> m.name.equals("create")
                        && m.desc.equals("(Ljava/lang/Object;)L" + MENU_TYPE + ";")),
                "a redirected new MenuType(supplier) calls create(Object), so the helper must keep it");
    }

    @Test
    @DisplayName("on a Forge 1.20.1 host the embedded helper reads the flags by their SRG name")
    void embeddedHelperUsesTargetSrgNames(@TempDir Path mod) throws Exception {
        com.retromod.mapping.TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);
        // A mod class that calls the helper, so the embedder pulls it in.
        Files.createDirectories(mod.resolve("test/menus"));
        Files.write(mod.resolve(MOD + ".class"), transformer.transformClass(modClass(MOD, "java/lang/Object", mv -> {
            mv.visitTypeInsn(NEW, MENU_TYPE);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, MENU_TYPE, "<init>", OLD_CTOR, false);
            mv.visitInsn(POP);
        }), MOD));
        assertEquals(1, SyntheticEmbedder.embed(mod, "srg-menus", transformer));

        Path embedded;
        try (var walk = Files.walk(mod)) {
            embedded = walk.filter(p -> p.toString().endsWith("LegacyMenuTypeConstructors.class")).findFirst().orElseThrow();
        }
        ClassNode helper = new ClassNode();
        new ClassReader(Files.readAllBytes(embedded)).accept(helper, 0);
        List<String> fields = new ArrayList<>();
        for (MethodNode method : helper.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
            }
        }
        assertFalse(fields.isEmpty());
        assertTrue(fields.stream().allMatch("net/minecraft/world/flag/FeatureFlags.f_244332_"::equals), fields.toString());
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
            }
        }
        return out;
    }

    private List<AbstractInsnNode> transform(String name, String superName, Consumer<MethodVisitor> body) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(modClass(name, superName, body), name)).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("run")) m.instructions.forEach(insns::add);
        }
        return insns;
    }

    private static byte[] modClass(String name, String superName, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static <T extends AbstractInsnNode> T only(List<AbstractInsnNode> insns, Class<T> type) {
        List<T> found = insns.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), "expected one " + type.getSimpleName() + ", found " + found.size());
        return found.get(0);
    }
}
