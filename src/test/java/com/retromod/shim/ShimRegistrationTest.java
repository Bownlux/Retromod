/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** One shim that throws while registering is skipped alone, and the shims after it still apply. */
class ShimRegistrationTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a shim that throws is skipped, and the next shim still registers")
    void throwingShimDoesNotDropTheNextOne() throws Exception {
        VersionShim broken = new TestShim("1.20.1", "1.21.1", registering -> {
            throw new IllegalStateException("host probe failed");
        });
        VersionShim working = new TestShim("1.21.1", "1.21.2",
                registering -> registering.registerClassRedirect("test/OldType", "test/NewType"));
        boolean[] results = new boolean[2];

        String log = LoaderApiTestSupport.captureErr(() -> {
            results[0] = ShimRegistration.register(broken, transformer);
            results[1] = ShimRegistration.register(working, transformer);
        });

        assertFalse(results[0], "the throwing shim reports that it did not register");
        assertTrue(results[1], "the shim after it still registers");
        assertEquals("test/NewType", transformer.getClassRedirects().get("test/OldType"));
        assertTrue(log.contains(TestShim.class.getName()) && log.contains("1.20.1")
                && log.contains("1.21.1"), "the warning names the shim and its versions: " + log);
    }

    @Test
    @DisplayName("a shim that needs a missing class is contained too")
    void linkageErrorIsContained() throws Exception {
        VersionShim missingClass = new TestShim("1.20.1", "1.21.1", registering -> {
            throw new NoClassDefFoundError("net/example/Gone");
        });

        String log = LoaderApiTestSupport.captureErr(() ->
                assertFalse(ShimRegistration.register(missingClass, transformer)));

        assertTrue(log.contains("net/example/Gone"), "the cause is in the log: " + log);
    }

    @Test
    @DisplayName("a healthy shim registers without a warning")
    void healthyShimLogsNothing() throws Exception {
        VersionShim working = new TestShim("1.21.1", "1.21.2",
                registering -> registering.registerClassRedirect("test/OldType", "test/NewType"));

        String log = LoaderApiTestSupport.captureErr(() ->
                assertTrue(ShimRegistration.register(working, transformer)));

        assertFalse(log.contains("Could not register"), log);
    }

    @Test
    @DisplayName("every entry point registers its shims through the isolating helper")
    void entryPointsUseTheHelper() throws IOException {
        for (String entryPoint : List.of("com/retromod/core/RetromodNeoForge",
                "com/retromod/core/RetromodForge", "com/retromod/core/Retromod")) {
            List<MethodInsnNode> calls = calls(entryPoint);
            assertTrue(calls.stream().anyMatch(call -> call.owner.equals(
                    "com/retromod/shim/ShimRegistration") && call.name.equals("register")),
                    entryPoint + " registers shims through ShimRegistration");
            // A direct interface call would let one throwing shim end the whole loop again.
            assertTrue(calls.stream().noneMatch(call -> call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("com/retromod/core/VersionShim")
                    && call.name.equals("registerRedirects")),
                    entryPoint + " must not call VersionShim.registerRedirects directly");
        }
    }

    private static List<MethodInsnNode> calls(String internalName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream in = ShimRegistrationTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, "class not found on the test classpath: " + internalName);
            new ClassReader(in).accept(node, 0);
        }
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    /** A shim whose registration runs {@code body}. */
    private record TestShim(String source, String target, Consumer<RetromodTransformer> body)
            implements VersionShim {
        @Override
        public String getShimName() {
            return "test shim " + source + " to " + target;
        }

        @Override
        public String getSourceVersion() {
            return source;
        }

        @Override
        public String getTargetVersion() {
            return target;
        }

        @Override
        public String getModLoaderType() {
            return "common";
        }

        @Override
        public void registerRedirects(RetromodTransformer transformer) {
            body.accept(transformer);
        }
    }
}
