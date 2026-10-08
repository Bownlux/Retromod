/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 1.12.2 generators and {@code EnumHelper} materials load against the generated stand-ins. */
class Forge1122WorldgenBridgeTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
    }

    private static RetromodTransformer bridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);
        Forge1122WorldgenBridge.register(t);
        return t;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static boolean declares(ClassNode type, String name, String desc) {
        return type.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** {@code class Spire extends WorldGenerator { Spire() { super(true); } generate(...) } }. */
    private static byte[] generator() {
        String old = "net/minecraft/world/gen/feature/WorldGenerator";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Spire", null, old, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitInsn(ICONST_1);
        ctor.visitMethodInsn(INVOKESPECIAL, old, "<init>", "(Z)V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        // The SRG id a 1.12.2 jar carries; the remap renames it.
        MethodVisitor gen = cw.visitMethod(ACC_PUBLIC, "func_180709_b",
                Forge1122WorldgenBridge.GENERATE_DESC, null, null);
        gen.visitCode();
        gen.visitVarInsn(ALOAD, 0);
        gen.visitVarInsn(ALOAD, 1);
        gen.visitVarInsn(ALOAD, 3);
        gen.visitInsn(ACONST_NULL);
        gen.visitMethodInsn(INVOKEVIRTUAL, "test/Spire", Forge1122WorldgenBridge.SET_BLOCK,
                Forge1122WorldgenBridge.SET_BLOCK_DESC, false);
        gen.visitInsn(ICONST_1);
        gen.visitInsn(IRETURN);
        gen.visitMaxs(0, 0);
        gen.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void generatorSubclassesExtendALoadableStandIn() {
        RetromodTransformer t = bridged();
        // The bundled 1.12.2 SRG table's entry for WorldGenerator.generate.
        t.registerSrgNameMappings(java.util.Map.of("func_180709_b", "generate"), java.util.Map.of());
        ClassNode spire = node(t.transformClass(generator(), "test/Spire.class"));
        assertEquals(Forge1122WorldgenBridge.GENERATOR, spire.superName);

        ClassNode standIn = node(Forge1122WorldgenBridge.generatorBytes());
        for (org.objectweb.asm.tree.MethodNode override : spire.methods) {
            if (override.name.equals("<init>")) continue;
            assertTrue(declares(standIn, override.name, override.desc),
                    override.name + override.desc + " must override the stand-in's abstract generate, "
                            + "or callers of the generator fail with NoSuchMethodError or AbstractMethodError");
        }
        assertTrue(declares(standIn, "<init>", "(Z)V"), "the notify constructor must link");
        assertTrue(declares(standIn, "<init>", "()V"));
        assertTrue(declares(standIn, Forge1122WorldgenBridge.SET_BLOCK, Forge1122WorldgenBridge.SET_BLOCK_DESC),
                "setBlockAndNotifyAdequately is inherited from the stand-in");
        MethodInsnNode place = Arrays.stream(standIn.methods.stream()
                        .filter(m -> m.name.equals(Forge1122WorldgenBridge.SET_BLOCK)).findFirst().orElseThrow()
                        .instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .findFirst().orElseThrow();
        assertEquals("setBlock", place.name, "blocks are placed through the host level");

        ClassNode ore = node(Forge1122WorldgenBridge.minableBytes());
        assertEquals(Forge1122WorldgenBridge.GENERATOR, ore.superName);
        assertTrue(declares(ore, "<init>", "(Lnet/minecraft/world/level/block/state/BlockState;I)V"));
    }

    @Test
    void vanillaTreeGeneratorsHaveTheConstructorsTheirSubclassesCall() {
        RetromodTransformer t = bridged();
        String tree = "com/retromod/generated/forge1122/WorldGenAbstractTree";
        String trees = "com/retromod/generated/forge1122/WorldGenTrees";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/DeadTree", null,
                "net/minecraft/world/gen/feature/WorldGenTrees", null);
        cw.visitEnd();
        assertEquals(trees, node(t.transformClass(cw.toByteArray(), "test/DeadTree.class")).superName);

        ClassNode treesStandIn = node(Forge1122WorldgenBridge.vanillaGeneratorBytes(trees, tree, false, "(Z)V"));
        ClassNode treeStandIn = node(Forge1122WorldgenBridge.vanillaGeneratorBytes(tree,
                Forge1122WorldgenBridge.GENERATOR, true, "()V", "(Z)V"));
        assertTrue(declares(treesStandIn, Forge1122WorldgenBridge.GENERATE, Forge1122WorldgenBridge.GENERATE_DESC));
        assertTrue((treeStandIn.access & ACC_ABSTRACT) != 0);
        MethodInsnNode superCall = Arrays.stream(treesStandIn.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).findFirst().orElseThrow();
        assertTrue(declares(treeStandIn, superCall.name, superCall.desc),
                "each stand-in constructor must reach a constructor its parent declares");
    }

    /** {@code static ArmorMaterial RUBY = EnumHelper.addArmorMaterial(...)} after the remap. */
    private static byte[] armorMaterialHolder() {
        String material = "Lnet/minecraft/world/item/ArmorMaterial;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Materials", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "RUBY", material, null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("ruby");
        clinit.visitLdcInsn("mod:ruby");
        clinit.visitIntInsn(BIPUSH, 15);
        clinit.visitInsn(ICONST_4);
        clinit.visitIntInsn(NEWARRAY, T_INT);
        clinit.visitIntInsn(BIPUSH, 9);
        clinit.visitInsn(ACONST_NULL);
        clinit.visitInsn(FCONST_0);
        clinit.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/common/util/EnumHelper", "addArmorMaterial",
                "(Ljava/lang/String;Ljava/lang/String;I[IILnet/minecraft/sounds/SoundEvent;F)" + material, false);
        clinit.visitFieldInsn(PUTSTATIC, "test/Materials", "RUBY", material);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void enumHelperCallsLinkAgainstTheStandIn() {
        RetromodTransformer t = bridged();
        byte[] remapped = t.transformClass(armorMaterialHolder(), "test/Materials.class");
        ClassNode cn = node(Forge1122EventBridge.rewriteLegacyCalls(remapped));
        MethodInsnNode call = Arrays.stream(cn.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(mi -> mi.name.equals("addArmorMaterial")).findFirst().orElseThrow();

        assertEquals(Forge1122WorldgenBridge.ENUM_HELPER, call.owner);
        assertTrue(declares(node(Forge1122WorldgenBridge.enumHelperBytes()), call.name, call.desc),
                "the erased call " + call.desc + " must exist on the stand-in, or the static "
                        + "initializer fails before any content registers");
    }
}
