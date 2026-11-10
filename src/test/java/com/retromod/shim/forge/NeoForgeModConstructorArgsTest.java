/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 47.2 {@code @Mod} constructor that takes FMLJavaModLoadingContext is one NeoForge's FML can call. */
class NeoForgeModConstructorArgsTest {

    private static final String MOD = "test/ContextMod";
    private static final String CONTEXT = NeoForgeModConstructorArgs.CONTEXT;
    private static final String CONTAINER = NeoForgeModConstructorArgs.MOD_CONTAINER;
    private static final String BUS = "net/neoforged/bus/api/IEventBus";
    private static final String NEO_MOD = "Lnet/neoforged/fml/common/Mod;";

    @Test
    @DisplayName("#400: the context parameter becomes the mod's container, an argument FML hands over")
    void contextBecomesContainer() {
        MethodNode constructor = constructor(NeoForgeModConstructorArgs.apply(modClass(NEO_MOD, "L" + CONTEXT + ";")));
        assertEquals("(L" + CONTAINER + ";)V", constructor.desc);
    }

    @Test
    @DisplayName("types FML already hands over stay, and a second container slot takes the other container type")
    void keepsAcceptedTypes() {
        MethodNode withBus = constructor(NeoForgeModConstructorArgs.apply(
                modClass(NEO_MOD, "L" + BUS + ";L" + CONTEXT + ";")));
        assertEquals("(L" + BUS + ";L" + CONTAINER + ";)V", withBus.desc);
        MethodNode withContainer = constructor(NeoForgeModConstructorArgs.apply(
                modClass(NEO_MOD, "L" + CONTAINER + ";L" + CONTEXT + ";")));
        assertEquals("(L" + CONTAINER + ";L" + NeoForgeModConstructorArgs.FML_MOD_CONTAINER + ";)V", withContainer.desc);
    }

    @Test
    @DisplayName("a class without @Mod keeps its constructor")
    void otherClassesUnchanged() {
        byte[] plain = modClass(null, "L" + CONTEXT + ";");
        assertSame(plain, NeoForgeModConstructorArgs.apply(plain));
    }

    @Test
    @DisplayName("#392: FML builds the mod with its container, and the mod's context answers from that container")
    void modReadsItsOwnBus() throws Exception {
        Map<String, byte[]> classes = Map.of(
                BUS.replace('/', '.'), busInterface(),
                CONTAINER.replace('/', '.'), containerStub(),
                "test.TestContainer", testContainer(),
                CONTEXT.replace('/', '.'), ForgeNeoForgeSynthetics.generateFmlJavaModLoadingContext(),
                MOD.replace('/', '.'), NeoForgeModConstructorArgs.apply(modClass(NEO_MOD, "L" + CONTEXT + ";")));
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> busType = loader.loadClass(BUS.replace('/', '.'));
        Object hostBus = Proxy.newProxyInstance(loader, new Class<?>[]{busType}, (p, m, a) -> null);
        Class<?> containerType = loader.loadClass("test.TestContainer");
        containerType.getField("BUS").set(null, hostBus);
        Object container = containerType.getConstructor().newInstance();

        // NeoForge asks for exactly one public constructor and fills each parameter by its type.
        Class<?> modType = loader.loadClass(MOD.replace('/', '.'));
        Constructor<?>[] constructors = modType.getConstructors();
        assertEquals(1, constructors.length);
        Object mod = constructors[0].newInstance(container);
        assertSame(hostBus, modType.getField("bus").get(mod), "the mod must read its own container's event bus");
    }

    /** A post-remap {@code @Mod} class whose constructor stores {@code context.getModEventBus()} when it has a context. */
    private static byte[] modClass(String annotation, String params) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        if (annotation != null) {
            AnnotationVisitor av = cw.visitAnnotation(annotation, true);
            av.visit("value", "contextmod");
            av.visitEnd();
        }
        cw.visitField(Opcodes.ACC_PUBLIC, "bus", "L" + BUS + ";", null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + params + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int contextSlot = params.indexOf(CONTEXT) < params.indexOf(';') ? 1 : 2;
        // The branch gives the body a stack map frame that names the context type.
        Label skip = new Label();
        m.visitVarInsn(Opcodes.ALOAD, contextSlot);
        m.visitJumpInsn(Opcodes.IFNULL, skip);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, contextSlot);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT, "getModEventBus", "()L" + BUS + ";", false);
        m.visitFieldInsn(Opcodes.PUTFIELD, MOD, "bus", "L" + BUS + ";");
        m.visitLabel(skip);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode constructor(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
    }

    private static byte[] busInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, BUS, null,
                "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** NeoForge's ModContainer, reduced to the one member the context calls here. */
    private static byte[] containerStub() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, CONTAINER, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getEventBus", "()L" + BUS + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A container whose event bus the test sets. */
    private static byte[] testContainer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/TestContainer", null, CONTAINER, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BUS", "L" + BUS + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTAINER, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "getEventBus", "()L" + BUS + ";", null, null);
        get.visitCode();
        get.visitFieldInsn(Opcodes.GETSTATIC, "test/TestContainer", "BUS", "L" + BUS + ";");
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
