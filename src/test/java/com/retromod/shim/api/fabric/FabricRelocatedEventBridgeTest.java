/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.api.fabric.embedded.ItemGroupEventsBridge;
import com.retromod.shim.api.fabric.embedded.ServerWorldEventsBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for listener arrays whose SAM interfaces are relocated per mod. */
class FabricRelocatedEventBridgeTest {

    private static final String ITEM_HOLDER =
            "com/retromod/generated/legacyitemgroup/ItemGroupEvents";
    private static final String MODIFY_ENTRIES =
            "com/retromod/generated/legacyitemgroup/ModifyEntries";
    private static final String MODIFY_ENTRIES_ALL =
            "com/retromod/generated/legacyitemgroup/ModifyEntriesAll";
    private static final String WORLD_HOLDER =
            "com/retromod/generated/legacylifecycle/ServerWorldEvents";
    private static final String WORLD_LOAD =
            "com/retromod/generated/legacylifecycle/ServerWorldLoad";
    private static final String WORLD_UNLOAD =
            "com/retromod/generated/legacylifecycle/ServerWorldUnload";

    @Test
    @DisplayName("relocated event holders pass their relocated SAM Class literals to bridges")
    void relocatedHoldersKeepListenerArrayTypeAligned(@TempDir Path dir) throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            transformer.registerSyntheticClass(ITEM_HOLDER, FabricItemGroupEventsShim.generateHolder());
            transformer.registerSyntheticClass(MODIFY_ENTRIES,
                    FabricItemGroupEventsShim.generateModifyEntries());
            transformer.registerSyntheticClass(MODIFY_ENTRIES_ALL,
                    FabricItemGroupEventsShim.generateModifyEntriesAll());
            transformer.registerSyntheticClass(WORLD_HOLDER,
                    FabricServerWorldEventsShim.generateHolder());
            transformer.registerSyntheticClass(WORLD_LOAD,
                    FabricServerWorldEventsShim.generateLoad());
            transformer.registerSyntheticClass(WORLD_UNLOAD,
                    FabricServerWorldEventsShim.generateUnload());

            writeClass(dir, "fixture/UsesBridges", holderReferences());

            assertEquals(6, SyntheticEmbedder.embed(dir, "bridge-fixture.jar", transformer),
                    "both holders and all four SAM interfaces must follow the mod relocation");

            String base = SyntheticEmbedder.embeddedBase("bridge-fixture.jar");
            ClassNode itemHolder = read(dir.resolve(base + ITEM_HOLDER + ".class"));
            assertClassLiteralCall(itemHolder, "modifyEntriesEvent", "modifyEntriesEvent",
                    "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;",
                    base + MODIFY_ENTRIES);
            assertClassLiteralCall(itemHolder, "<clinit>", "installModifyAll",
                    "(Ljava/lang/Class;)Ljava/lang/Object;", base + MODIFY_ENTRIES_ALL);

            ClassNode worldHolder = read(dir.resolve(base + WORLD_HOLDER + ".class"));
            assertClassLiteralCall(worldHolder, "<clinit>", "installLoad",
                    "(Ljava/lang/Class;)Ljava/lang/Object;", base + WORLD_LOAD);
            assertClassLiteralCall(worldHolder, "<clinit>", "installUnload",
                    "(Ljava/lang/Class;)Ljava/lang/Object;", base + WORLD_UNLOAD);

            assertNotNull(ItemGroupEventsBridge.class.getMethod(
                    "modifyEntriesEvent", Object.class, Class.class));
            assertNotNull(ItemGroupEventsBridge.class.getMethod("installModifyAll", Class.class));
            assertNotNull(ServerWorldEventsBridge.class.getMethod("installLoad", Class.class));
            assertNotNull(ServerWorldEventsBridge.class.getMethod("installUnload", Class.class));
            assertNotNull(ItemGroupEventsBridge.class.getMethod(
                    "modifyEntriesEvent", Object.class),
                    "jars transformed by the earlier snapshot.6 bridge must keep linking");
            assertNotNull(ItemGroupEventsBridge.class.getMethod("installModifyAll"),
                    "jars transformed by the earlier snapshot.6 bridge must keep linking");
            assertNotNull(ServerWorldEventsBridge.class.getMethod("installLoad"),
                    "jars transformed by the earlier snapshot.6 bridge must keep linking");
            assertNotNull(ServerWorldEventsBridge.class.getMethod("installUnload"),
                    "jars transformed by the earlier snapshot.6 bridge must keep linking");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    private static void assertClassLiteralCall(
            ClassNode holder, String methodName, String callName, String callDesc,
            String expectedSam) {
        MethodNode method = holder.methods.stream()
                .filter(candidate -> candidate.name.equals(methodName))
                .findFirst()
                .orElseThrow();
        MethodInsnNode call = java.util.Arrays.stream(method.instructions.toArray())
                .filter(instruction -> instruction instanceof MethodInsnNode)
                .map(instruction -> (MethodInsnNode) instruction)
                .filter(instruction -> instruction.name.equals(callName))
                .findFirst()
                .orElse(null);
        assertNotNull(call, "holder must call " + callName);
        assertEquals(callDesc, call.desc, "bridge call must accept the SAM Class token");
        assertTrue(call.getPrevious() instanceof LdcInsnNode,
                "the instruction before " + callName + " must load the SAM Class literal");
        Object literal = ((LdcInsnNode) call.getPrevious()).cst;
        assertEquals(Type.getObjectType(expectedSam), literal,
                "SyntheticEmbedder must relocate the holder's SAM Class literal");
    }

    private static byte[] holderReferences() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/UsesBridges", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "itemEvents", "L" + ITEM_HOLDER + ";",
                null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "worldEvents", "L" + WORLD_HOLDER + ";",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeClass(Path dir, String internalName, byte[] bytes) throws Exception {
        Path file = dir.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static ClassNode read(Path file) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        return node;
    }
}
