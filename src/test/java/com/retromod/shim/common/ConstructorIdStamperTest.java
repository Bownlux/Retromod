/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #366: ProjectE builds one {@code Properties} for its torch and wall torch before either is
 * registered, so 26.2 refused both with "Block id not set".
 */
class ConstructorIdStamperTest {

    private static final String PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String BRIDGE = "com/retromod/shim/forge/embedded/RegistryIdBridge";

    @Test
    @DisplayName("A Minecraft block constructor takes its properties through the id bridge, a mod's own does not")
    void blockConstructorsAreStamped() {
        ClassNode torch = read(ConstructorIdStamper.apply(torch(), BRIDGE));

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : torch.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of(BRIDGE + ".applyBlock", "net/minecraft/world/level/block/TorchBlock.<init>"), calls);
        new ClassReader(ConstructorIdStamper.apply(torch(), BRIDGE)).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    @DisplayName("Properties before another argument, as in NeoForge's sword constructor, are stamped too")
    void propertiesBeforeTheLastArgumentAreStamped() {
        String item = "Lnet/minecraft/world/item/Item$Properties;";
        String tool = "Lnet/minecraft/world/item/component/Tool;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Sword", null, "com/retromod/generated/LegacySwordItem", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + item + tool + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitInsn(ACONST_NULL);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, "com/retromod/generated/LegacySwordItem", "<init>",
                "(Ljava/lang/Object;" + item + tool + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();

        byte[] stamped = ConstructorIdStamper.apply(cw.toByteArray(), BRIDGE);

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : read(stamped).methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of(BRIDGE + ".applyItem", "com/retromod/generated/LegacySwordItem.<init>"), calls);
        new ClassReader(stamped).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @Test
    @DisplayName("A Block::new reference, as Moonlight's block sets use, builds the block with stamped properties")
    void blockConstructorReferenceIsStamped() throws Exception {
        byte[] stamped = ConstructorIdStamper.apply(variantType(false), BRIDGE);

        ClassNode node = read(stamped);
        Handle reference = (Handle) indy(node).bsmArgs[1];
        assertEquals(H_INVOKESTATIC, reference.getTag());
        assertEquals("mod/VariantType", reference.getOwner(), "the factory lives in the mod's own class");
        new ClassReader(stamped).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);

        // Run it: the lambda links to the private factory, and the block receives the bridge's properties.
        HostStubs host = new HostStubs().add(block()).add(bridge()).add("mod/VariantType", stamped);
        @SuppressWarnings("unchecked")
        Function<Object, Object> make = (Function<Object, Object>) host.method("mod/VariantType", "block").invoke(null);
        Object properties = host.load(PROPERTIES.substring(1, PROPERTIES.length() - 1)).getConstructor().newInstance();
        Object built = make.apply(properties);
        Object received = built.getClass().getField("properties").get(built);
        assertNotSame(properties, received, "Block.<init> must see what applyBlock returned");
        assertEquals(properties.getClass(), received.getClass());
    }

    @Test
    @DisplayName("A serializable Block::new reference keeps its constructor handle")
    void serializableReferenceIsLeftAlone() {
        byte[] original = variantType(true);
        byte[] stamped = ConstructorIdStamper.apply(original, BRIDGE);

        Handle reference = (Handle) indy(read(stamped)).bsmArgs[1];
        assertEquals(H_NEWINVOKESPECIAL, reference.getTag(), "its deserializer checks the original constructor");
    }

    @Test
    @DisplayName("A mixin's Block::new reference is not given a factory, since its methods merge into Minecraft's class")
    void mixinReferenceIsLeftAlone() {
        ClassNode mixin = read(variantType(false));
        mixin.invisibleAnnotations = new ArrayList<>(List.of(
                new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;")));

        assertEquals(0, ConstructorReferenceExpander.expand(mixin, (owner, desc) -> true));
        assertEquals(H_NEWINVOKESPECIAL, ((Handle) indy(mixin).bsmArgs[1]).getTag());
    }

    private static final String BLOCK = "net/minecraft/world/level/block/Block";

    /** {@code class VariantType { static Function<Properties, Block> block() { return Block::new; } }} */
    private static byte[] variantType(boolean serializable) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/VariantType", null, "java/lang/Object", null);
        MethodVisitor block = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "block", "()Ljava/util/function/Function;", null, null);
        block.visitCode();
        Handle constructor = new Handle(H_NEWINVOKESPECIAL, BLOCK, "<init>", "(" + PROPERTIES + ")V", false);
        Type erased = Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;");
        Type exact = Type.getMethodType("(" + PROPERTIES + ")L" + BLOCK + ";");
        String lookup = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
        if (serializable) {
            block.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                    new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory",
                            lookup + "[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false),
                    erased, constructor, exact, java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE);
        } else {
            block.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                    new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                            lookup + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                                    + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                    erased, constructor, exact);
        }
        block.visitInsn(ARETURN);
        block.visitMaxs(0, 0);
        block.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class Block { public Object properties; public Block(Properties p) { properties = p; } }} */
    private static byte[] block() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, BLOCK, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "properties", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + PROPERTIES + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, BLOCK, "properties", "Ljava/lang/Object;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Properties applyBlock(Properties p)} that hands back new properties, so the test sees it was called. */
    private static byte[] bridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, BRIDGE, null, "java/lang/Object", null);
        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "applyBlock", "(" + PROPERTIES + ")" + PROPERTIES,
                null, null);
        apply.visitCode();
        String properties = PROPERTIES.substring(1, PROPERTIES.length() - 1);
        apply.visitTypeInsn(NEW, properties);
        apply.visitInsn(DUP);
        apply.visitMethodInsn(INVOKESPECIAL, properties, "<init>", "()V", false);
        apply.visitInsn(ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static org.objectweb.asm.tree.InvokeDynamicInsnNode indy(ClassNode node) {
        for (var method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) return indy;
            }
        }
        throw new AssertionError("no invokedynamic in " + node.name);
    }

    /** {@code class Torch extends TorchBlock { Torch(Properties p) { super(FLAME, p); } }}. */
    private static byte[] torch() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Torch", null, "net/minecraft/world/level/block/TorchBlock", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + PROPERTIES + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitInsn(ACONST_NULL);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/level/block/TorchBlock", "<init>",
                "(Lnet/minecraft/core/particles/SimpleParticleType;" + PROPERTIES + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
