/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ForgeFmlPackageMigrationTest {

    private static final String OLD_FML = "net/minecraftforge/fml/loading/FMLEnvironment";
    private static final String NEW_FML = "net/neoforged/fml/loading/FMLEnvironment";
    private static final String OLD_DIST = "Lnet/minecraftforge/api/distmarker/Dist;";
    private static final String NEW_DIST = "Lnet/neoforged/api/distmarker/Dist;";

    @AfterEach
    void reset() {
        McReflect.setForceNeoForge(false);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldFmlPackagesAndDistFieldMoveToCurrentNeoForgeApi() {
        RetromodTransformer transformer = configuredTransformer();
        byte[] output = transformer.transformClass(fmlFixture(), "test/LegacyFmlUser.class");

        boolean[] shape = new boolean[3];
        new ClassReader(output).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if ("info".equals(name)
                        && descriptor.equals("(Lnet/neoforged/neoforgespi/language/IModInfo;)"
                                + "Lnet/neoforged/neoforgespi/language/IModInfo;")) {
                    shape[1] = true;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals(NEW_FML)
                                && methodName.equals("getDist")
                                && methodDescriptor.equals("()" + NEW_DIST)) {
                            shape[0] = true;
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                            String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC
                                && owner.equals("net/neoforged/fml/util/thread/SidedThreadGroups")
                                && fieldName.equals("SERVER")
                                && fieldDescriptor.equals(
                                        "Lnet/neoforged/fml/util/thread/SidedThreadGroup;")) {
                            shape[2] = true;
                        }
                    }
                };
            }
        }, 0);

        String constantPool = new String(output, StandardCharsets.ISO_8859_1);
        assertTrue(shape[0], "FMLEnvironment.dist should call NeoForge FMLEnvironment.getDist()");
        assertTrue(shape[1], "IModInfo method descriptors should move to neoforgespi");
        assertTrue(shape[2], "SidedThreadGroups.SERVER should move with its field descriptor");
        assertFalse(constantPool.contains("net/minecraftforge/fml/loading/FMLEnvironment"));
        assertFalse(constantPool.contains("net/minecraftforge/fml/util/thread/SidedThread"));
        assertFalse(constantPool.contains("net/minecraftforge/forgespi/language/IModInfo"));
    }

    @Test
    void forgeSpawnEggConstructionUsesTheEmbeddedModernReplacement() {
        RetromodTransformer transformer = configuredTransformer();
        byte[] output = transformer.transformClass(spawnEggFixture(), "test/LegacyEggFactory.class");

        String replacement = "net/neoforged/neoforge/common/DeferredSpawnEggItem";
        boolean[] shape = new boolean[2];
        new ClassReader(output).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && type.equals(replacement)) shape[0] = true;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESPECIAL && owner.equals(replacement)
                                && methodName.equals("<init>")
                                && methodDescriptor.equals("(Ljava/util/function/Supplier;II"
                                        + "Lnet/minecraft/world/item/Item$Properties;)V")) {
                            shape[1] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(shape[0], "ForgeSpawnEggItem allocation should use the embedded replacement");
        assertTrue(shape[1], "the supplier-based spawn egg constructor should keep its call shape");
        assertFalse(new String(output, StandardCharsets.ISO_8859_1)
                .contains("net/minecraftforge/common/ForgeSpawnEggItem"));
    }

    private static RetromodTransformer configuredTransformer() {
        McReflect.setForceNeoForge(true);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);
        return transformer;
    }

    private static byte[] fmlFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyFmlUser", null,
                "java/lang/Object", null);

        MethodVisitor side = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "side", "()" + OLD_DIST, null, null);
        side.visitCode();
        side.visitFieldInsn(Opcodes.GETSTATIC, OLD_FML, "dist", OLD_DIST);
        side.visitInsn(Opcodes.ARETURN);
        side.visitMaxs(0, 0);
        side.visitEnd();

        String oldInfo = "Lnet/minecraftforge/forgespi/language/IModInfo;";
        MethodVisitor info = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "info", "(" + oldInfo + ")" + oldInfo, null, null);
        info.visitCode();
        info.visitVarInsn(Opcodes.ALOAD, 0);
        info.visitInsn(Opcodes.ARETURN);
        info.visitMaxs(0, 0);
        info.visitEnd();

        MethodVisitor serverGroup = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "serverGroup", "()Ljava/lang/ThreadGroup;", null, null);
        serverGroup.visitCode();
        serverGroup.visitFieldInsn(Opcodes.GETSTATIC,
                "net/minecraftforge/fml/util/thread/SidedThreadGroups", "SERVER",
                "Lnet/minecraftforge/fml/util/thread/SidedThreadGroup;");
        serverGroup.visitInsn(Opcodes.ARETURN);
        serverGroup.visitMaxs(0, 0);
        serverGroup.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] spawnEggFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyEggFactory", null,
                "java/lang/Object", null);
        String egg = "net/minecraftforge/common/ForgeSpawnEggItem";
        String properties = "Lnet/minecraft/world/item/Item$Properties;";
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "(Ljava/util/function/Supplier;" + properties + ")"
                        + "Lnet/minecraft/world/item/SpawnEggItem;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, egg);
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, egg, "<init>",
                "(Ljava/util/function/Supplier;II" + properties + ")V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
