/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every transform path reaches the runtime's single hook, and removing a call fails here. */
class LoaderApiWiringTest {

    private static final String RUNTIME = "com/retromod/loaderapi/LoaderApiRuntime";

    /** Class to the runtime methods it must call somewhere in its bytecode. */
    private static final Map<String, List<String>> CALLERS = Map.of(
            "com/retromod/core/FabricModTransformer", List.of("prepare", "translationIdentity"),
            "com/retromod/aot/AotCompiler", List.of("prepare", "translationIdentity"),
            "com/retromod/aot/FullAotCompiler", List.of("prepareForProcess"),
            "com/retromod/aot/AotCacheStamp", List.of("cacheStampSuffix"),
            "com/retromod/embedder/ApiEmbedder", List.of("prepare"),
            "com/retromod/cli/RetromodCli", List.of("configureForCli", "prepare", "translationIdentity"),
            "com/retromod/core/RetromodPreLaunch", List.of("configureForGame", "prepare",
                    "translationIdentity"),
            "com/retromod/core/Retromod", List.of("prepare"));

    @Test
    @DisplayName("Each transform path calls the hook")
    void everyPathCallsTheHook() throws IOException {
        for (Map.Entry<String, List<String>> caller : CALLERS.entrySet()) {
            List<String> called = runtimeCalls(read(caller.getKey()));
            for (String method : caller.getValue()) {
                assertTrue(called.contains(method), caller.getKey() + " no longer calls LoaderApiRuntime."
                        + method + "; that transform path would skip the loader API runtime");
            }
        }
    }

    @Test
    @DisplayName("transformClass runs the pre-transform hook before anything else")
    void transformClassRunsTheHook() throws IOException {
        ClassNode transformer = read("com/retromod/core/RetromodTransformer");
        MethodNode transformClass = transformer.methods.stream()
                .filter(method -> method.name.equals("transformClass")
                        && method.desc.equals("([BLjava/lang/String;)[B"))
                .findFirst().orElseThrow();
        // The hook field must be read before the first call, which is the phantom sweep.
        AbstractInsnNode insn = transformClass.instructions.getFirst();
        while (insn != null && !(insn instanceof FieldInsnNode) && !(insn instanceof MethodInsnNode)) {
            insn = insn.getNext();
        }
        assertTrue(insn instanceof FieldInsnNode field && field.name.equals("preTransformHook"),
                "transformClass must read the hook first, before the phantom sweep");
    }

    @Test
    @DisplayName("Quilt reaches the hook through the Fabric transformer")
    void quiltDelegatesToFabric() throws IOException {
        ClassNode quilt = read("com/retromod/core/QuiltModTransformer");
        boolean delegates = quilt.methods.stream().flatMap(method -> {
            List<MethodInsnNode> calls = new ArrayList<>();
            method.instructions.forEach(insn -> {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            });
            return calls.stream();
        }).anyMatch(call -> call.owner.equals("com/retromod/core/FabricModTransformer")
                && call.name.startsWith("transformMod"));
        assertTrue(delegates, "QuiltModTransformer must translate through FabricModTransformer");
    }

    @Test
    @DisplayName("The CLI removes its flag before any command reads arguments")
    void cliStripsTheFlagFirst() throws IOException {
        MethodNode main = read("com/retromod/cli/RetromodCli").methods.stream()
                .filter(method -> method.name.equals("main")).findFirst().orElseThrow();
        String firstCall = null;
        for (AbstractInsnNode insn : main.instructions) {
            if (insn instanceof MethodInsnNode call) {
                firstCall = call.owner + "." + call.name;
                break;
            }
        }
        assertFalse(firstCall == null);
        assertTrue(firstCall.equals("com/retromod/loaderapi/LoaderApiSetting.stripCliFlag"),
                "main must strip --loader-api-runtime first, found " + firstCall);
    }

    private static List<String> runtimeCalls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) {
                    calls.add(call.name);
                }
            }
        }
        return calls;
    }

    private static ClassNode read(String internalName) throws IOException {
        try (InputStream in = LoaderApiWiringTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
