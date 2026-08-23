/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.api.common.MixinExtrasApiShim;
import com.retromod.shim.api.fabric.FabricApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownSourceApiShimRegistrationTest {

    private static final String BOOTSTRAP =
            "com/llamalad7/mixinextras/MixinExtrasBootstrap";
    private static final String COMPAT =
            "com/retromod/shim/api/common/embedded/MixinExtrasShim";

    @AfterEach
    void resetTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void knownSourceCliRegistersCommonApiOnly() throws Exception {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new MixinExtrasApiShim());
        AtomicInteger versionRegistrations = new AtomicInteger();
        registry.register(unsafeVersionShim(versionRegistrations));

        Field field = RetromodCli.class.getDeclaredField("shimRegistry");
        field.setAccessible(true);
        Object saved = field.get(null);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            field.set(null, registry);
            assertEquals(1, RetromodCli.registerApiRedirects(
                    transformer, "fabric", List.of()));
            assertEquals(0, versionRegistrations.get(),
                    "the API layer must not replay version-chain shims");

            MethodInsnNode call = onlyCall(transformer.transformClass(
                    caller(), "fixture/KnownSourceCaller"));
            assertEquals(COMPAT, call.owner);
            assertEquals("noopInit", call.name);
        } finally {
            field.set(null, saved);
        }
    }

    @Test
    void apiVersionNumbersCannotBecomeMinecraftGraphEdges() {
        ShimRegistry registry = new ShimRegistry();
        registry.register(new FabricApiShim());

        assertTrue(registry.findShimChain("fabric", "0.50.0", "0.100.0").isEmpty(),
                "an API version collision must not form a Minecraft version chain");
    }

    private static VersionShim unsafeVersionShim(AtomicInteger registrations) {
        return new VersionShim() {
            public String getShimName() { return "unsafe fixture version shim"; }
            public String getSourceVersion() { return "1.21.4"; }
            public String getTargetVersion() { return "26.1"; }
            public String getModLoaderType() { return "fabric"; }
            public void registerRedirects(RetromodTransformer transformer) {
                registrations.incrementAndGet();
            }
        };
    }

    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/KnownSourceCaller",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, BOOTSTRAP, "init", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream()
                .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst().orElseThrow();
    }
}
