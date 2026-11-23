/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A {@code Block::new} reference builds its block through a factory that stamps the registration id, as a {@code new} does. */
class FabricConstructorReferenceStampTest {

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String CTOR = "(L" + PROPS + ";)V";

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        ClassResourceInspector.setOfflineHost(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A reference to a public game constructor that takes Properties points at a stamping factory")
    void constructorReferenceIsStamped() throws Exception {
        Path block = dir.resolve(BLOCK + ".class");
        Files.createDirectories(block.getParent());
        Files.write(block, blockStub());
        ClassResourceInspector.setOfflineHost(new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, null));

        byte[] mod = modWithReference(BLOCK, false);
        byte[] stamped = FabricRegistryIdAdapter.apply(mod);

        ClassNode node = new ClassNode();
        new ClassReader(stamped).accept(node, 0);
        InvokeDynamicInsnNode indy = null;
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) if (insn instanceof InvokeDynamicInsnNode found) indy = found;
        }
        assertNotNull(indy);
        Handle target = (Handle) indy.bsmArgs[1];
        assertEquals(Opcodes.H_INVOKESTATIC, target.getTag());
        String factory = FabricRegistryIdAdapter.stampedFactory(BLOCK, CTOR);
        assertEquals(factory, target.getOwner());
        assertEquals("(L" + PROPS + ";)L" + BLOCK + ";", target.getDesc());

        ClassNode created = new ClassNode();
        new ClassReader(RetromodTransformer.getInstance().getSyntheticClasses().get(factory)).accept(created, 0);
        MethodNode create = created.methods.stream().filter(m -> m.name.equals("create")).findFirst().orElseThrow();
        java.util.List<String> calls = new java.util.ArrayList<>();
        create.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode call) calls.add(call.name); });
        assertEquals(java.util.List.of("stampBlock", "<init>"), calls, "the properties are stamped, then the block is built");
    }

    @Test
    @DisplayName("A reference the protected-constructor bridge already moved to its subclass is stamped too")
    void openSubclassReferenceIsStamped() {
        String open = com.retromod.shim.common.ProtectedConstructorBridge.GENERATED_PREFIX + "StairBlock$abc";
        byte[] stamped = FabricRegistryIdAdapter.apply(modWithReference(open, false));
        Handle target = (Handle) referenceOf(stamped).bsmArgs[1];
        assertEquals(Opcodes.H_INVOKESTATIC, target.getTag());
        assertEquals(FabricRegistryIdAdapter.stampedFactory(open, CTOR), target.getOwner());
    }

    @Test
    @DisplayName("A serializable reference stays as written, since its deserializer checks the original constructor")
    void serializableReferenceStays() {
        String open = com.retromod.shim.common.ProtectedConstructorBridge.GENERATED_PREFIX + "StairBlock$abc";
        Handle target = (Handle) referenceOf(FabricRegistryIdAdapter.apply(modWithReference(open, true))).bsmArgs[1];
        assertEquals(Opcodes.H_NEWINVOKESPECIAL, target.getTag());
    }

    private static InvokeDynamicInsnNode referenceOf(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) if (insn instanceof InvokeDynamicInsnNode found) return found;
        }
        throw new AssertionError("no constructor reference");
    }

    private static byte[] blockStub() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BLOCK, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Function<Properties, Block> factory() { return Owner::new; }}, serializable when asked, in a class that registers. */
    private static byte[] modWithReference(String owner, boolean serializable) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Blocks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()Ljava/util/function/Function;", null, null);
        m.visitCode();
        Handle reference = new Handle(Opcodes.H_NEWINVOKESPECIAL, owner, "<init>", CTOR, false);
        if (serializable) {
            Handle alt = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                            + "Ljava/lang/invoke/CallSite;", false);
            m.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", alt,
                    Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"), reference,
                    Type.getType("(L" + PROPS + ";)L" + owner + ";"), java.lang.invoke.LambdaMetafactory.FLAG_SERIALIZABLE);
        } else {
            Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
            m.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                    Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"), reference,
                    Type.getType("(L" + PROPS + ";)L" + owner + ";"));
        }
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
