/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A processor written before 26.2 runs and serializes on a host whose processor is an interface (48 mods, YUNG's). */
class LegacyStructureProcessorBridgeTest {

    private static final String PROCESSOR = LegacyStructureProcessorBridge.PROCESSOR;
    private static final String BASE = LegacyStructureProcessorBridge.BASE;
    private static final String TEMPLATES = "net/minecraft/world/level/levelgen/structure/templatesystem/";
    private static final String INFO = "L" + TEMPLATES + "StructureTemplate$StructureBlockInfo;";
    private static final String OLD_PROCESS = "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;" + INFO + INFO + "L" + TEMPLATES + "StructurePlaceSettings;)" + INFO;
    private static final String NEW_PROCESS = "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;" + INFO + "L" + TEMPLATES
            + "StructurePlaceSettings;)" + INFO;
    private static final String TYPE = TEMPLATES + "StructureProcessorType";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyStructureProcessorBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void anOldProcessorMovesOntoTheRebuiltBase() {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(oldProcessor(), "mod/RotProcessor")).accept(node, 0);

        assertEquals(BASE, node.superName, "the interface cannot be a superclass, and the base keeps the old hooks");
        MethodInsnNode superCall = null;
        for (AbstractInsnNode insn : node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst()
                .orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL) superCall = call;
        }
        assertEquals(BASE, superCall.owner, "the super constructor call follows the new base");
    }

    @Test
    void onTheReal263ClassesTheHostsHooksReachTheOldOnes() throws Exception {
        Path client = hostJar("com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        Path dfu = hostJar("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
        Path fastutil = hostJar("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
        Assumptions.assumeTrue(client != null && dfu != null && fastutil != null,
                "Minecraft 26.3 is not installed in Prism's libraries");
        Map<String, byte[]> modClasses = new HashMap<>();
        modClasses.put("mod.RotProcessor", transformer.transformClass(oldProcessor(), "mod/RotProcessor"));
        modClasses.put("mod.RotType", oldType());
        try (URLClassLoader host = new HostLoader(List.of(client, dfu, fastutil), modClasses)) {
            Class<?> processorType = host.loadClass("mod.RotProcessor");
            Object processor = processorType.getConstructor().newInstance();
            Class<?> hostProcessor = host.loadClass(PROCESSOR.replace('/', '.'));
            assertTrue(hostProcessor.isInstance(processor), "the old processor is a host processor");

            // codec() answers the codec the mod's old type lambda returns.
            Object codec = hostProcessor.getMethod("codec").invoke(processor);
            java.lang.reflect.Field typeCodec = host.loadClass("mod.RotType").getField("CODEC");
            typeCodec.setAccessible(true);
            assertSame(typeCodec.get(null), codec);

            // The host's processBlock reaches the mod's old one with the processed block as the current one.
            Class<?> info = host.loadClass((TEMPLATES + "StructureTemplate$StructureBlockInfo").replace('/', '.'));
            Object processed = info.getConstructors()[0].newInstance(null, null, null);
            Method process = null;
            for (Method method : hostProcessor.getMethods()) {
                if (method.getName().equals("processBlock")) process = method;
            }
            Object result = process.invoke(processor, null, null, null, null, processed, null);
            assertSame(processed, result, "the old hook returns its current block, which is the processed one");
            assertSame(processed, processorType.getField("lastCurrent").get(null));
        }
    }

    // ---- fixtures ----

    /** A 1.21.1 processor: getType() and the old processBlock, which records its current block. */
    private static byte[] oldProcessor() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/RotProcessor", null, PROCESSOR, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastCurrent", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, PROCESSOR, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor type = cw.visitMethod(Opcodes.ACC_PROTECTED, "getType", "()L" + TYPE + ";", null, null);
        type.visitCode();
        type.visitTypeInsn(Opcodes.NEW, "mod/RotType");
        type.visitInsn(Opcodes.DUP);
        type.visitMethodInsn(Opcodes.INVOKESPECIAL, "mod/RotType", "<init>", "()V", false);
        type.visitInsn(Opcodes.ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();
        MethodVisitor process = cw.visitMethod(Opcodes.ACC_PUBLIC, "processBlock", OLD_PROCESS, null, null);
        process.visitCode();
        process.visitVarInsn(Opcodes.ALOAD, 5);
        process.visitFieldInsn(Opcodes.PUTSTATIC, "mod/RotProcessor", "lastCurrent", "Ljava/lang/Object;");
        process.visitVarInsn(Opcodes.ALOAD, 5);
        process.visitInsn(Opcodes.ARETURN);
        process.visitMaxs(0, 0);
        process.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** What a {@code () -> CODEC} type lambda becomes: an object whose codec() answers a map codec. */
    private static byte[] oldType() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_FINAL, "mod/RotType", null, "java/lang/Object", new String[] {TYPE});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC",
                "Lcom/mojang/serialization/MapCodec;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("rot");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/serialization/MapCodec", "unit",
                "(Ljava/lang/Object;)Lcom/mojang/serialization/MapCodec;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "mod/RotType", "CODEC", "Lcom/mojang/serialization/MapCodec;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor codec = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", "()Lcom/mojang/serialization/MapCodec;", null, null);
        codec.visitCode();
        codec.visitFieldInsn(Opcodes.GETSTATIC, "mod/RotType", "CODEC", "Lcom/mojang/serialization/MapCodec;");
        codec.visitInsn(Opcodes.ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path hostJar(String relative) {
        Path jar = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries", relative);
        return Files.isRegularFile(jar) ? jar : null;
    }

    /** The host jars, with Retromod's rebuilt classes and the mod's classes defined beside them. */
    private static final class HostLoader extends URLClassLoader {
        private final Map<String, byte[]> modClasses;

        HostLoader(List<Path> jars, Map<String, byte[]> modClasses) throws Exception {
            super(urls(jars), ClassLoader.getPlatformClassLoader());
            this.modClasses = modClasses;
        }

        private static URL[] urls(List<Path> jars) throws Exception {
            URL[] urls = new URL[jars.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
            return urls;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = modClasses.get(name);
            if (bytes == null && name.startsWith("com.retromod.legacyapi.")) {
                try (InputStream in = LegacyStructureProcessorBridgeTest.class.getClassLoader()
                        .getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    bytes = in.readAllBytes();
                } catch (java.io.IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            return super.findClass(name);
        }
    }
}
