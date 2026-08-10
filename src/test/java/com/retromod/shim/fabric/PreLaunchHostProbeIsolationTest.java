/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.EnvironmentDetector;
import com.retromod.core.TransformVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** Guards Fabric pre-launch probes against defining Minecraft classes before mod transformation. */
class PreLaunchHostProbeIsolationTest {

    private final ClassLoader savedContext = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void reset() {
        Thread.currentThread().setContextClassLoader(savedContext);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("host shape probing reads class resources without defining Minecraft classes")
    void entityProbeDoesNotLoadMinecraftClass() {
        byte[] entityBytes = entityHostShape();
        AtomicInteger minecraftLoads = new AtomicInteger();
        ClassLoader resourcesOnly = new ClassLoader(savedContext) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals("net/minecraft/class_1297.class")) {
                    return new ByteArrayInputStream(entityBytes);
                }
                return super.getResourceAsStream(name);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.")) {
                    minecraftLoads.incrementAndGet();
                    throw new ClassNotFoundException("Minecraft classes must not be defined by a probe");
                }
                return super.loadClass(name, resolve);
            }
        };
        Thread.currentThread().setContextClassLoader(resourcesOnly);

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Pre1_17EntityFieldBridge.register(transformer);

        assertEquals(0, minecraftLoads.get(), "the probe must not ask the loader to define Entity");
        assertEquals(1, transformer.getFieldAccessorRedirectCount(),
                "the resource shape should still register the onGround bridge");
    }

    @Test
    @DisplayName("all pre-launch host probes avoid Class.forName and reflection member discovery")
    void probeClassesContainNoDefiningReflection() throws Exception {
        List<Class<?>> probes = List.of(
                EnvironmentDetector.class,
                TransformVerifier.class,
                Pre1_17EntityFieldBridge.class,
                Pre1_17ModelBridge.class,
                Pre1_18_2BiomeCategoryBridge.class,
                Pre1_19TextBridge.class,
                Pre1_20MaterialBridge.class,
                Pre1_20_5IdentifierCtorBridge.class,
                Pre1_21_2EntityTypeBuildBridge.class,
                Pre1_21_2InteractionResultBridge.class
        );
        for (Class<?> probe : probes) {
            String resource = probe.getName().replace('.', '/') + ".class";
            try (InputStream in = probe.getClassLoader().getResourceAsStream(resource)) {
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, 0);
                node.methods.forEach(method -> method.instructions.forEach(insn -> {
                    if (insn instanceof MethodInsnNode call
                            && ((call.owner.equals("java/lang/Class")
                                    && (call.name.equals("forName") || call.name.startsWith("getDeclared")))
                                || (call.owner.equals("java/lang/ClassLoader")
                                    && call.name.equals("loadClass")))) {
                        fail(probe.getSimpleName() + " defines or reflects on a host class via "
                                + call.owner + "." + call.name);
                    }
                }));
            }
        }
    }

    private static byte[] entityHostShape() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/class_1297",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "field_5952", "Z", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "method_24828", "()Z", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "method_24830", "(Z)V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
