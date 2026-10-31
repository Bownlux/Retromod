/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #366: ProjectE extends NeoForge's {@code ItemStackHandler} and reads {@code Capabilities.ItemHandler},
 * which NeoForge 26.3 removed with the rest of its item, fluid, and energy handler APIs.
 */
class LegacyTransferApiTest {

    private static final String OLD = "net/neoforged/neoforge/";
    private static final String NEW = LegacyTransferApi.REBUILT;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A handler subclass, the old capability, and its lookup reach the rebuilt API")
    void oldHandlerCodeMovesToTheRebuiltApi() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyTransferApi.registerCapabilities(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(machineHandler(), "mod/MachineHandler.class")).accept(node, 0);

        assertEquals(NEW + "items/ItemStackHandler", node.superName);
        List<String> owners = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) owners.add(call.owner + "." + call.name);
                if (insn instanceof FieldInsnNode field) owners.add(field.owner + "." + field.name);
            }
        }
        assertTrue(owners.contains(NEW + "items/ItemStackHandler.<init>"), owners.toString());
        assertTrue(owners.contains(NEW + "capabilities/Capabilities$ItemHandler.BLOCK"), owners.toString());
        assertTrue(owners.contains(LegacyTransferApi.CALLS + ".levelCapability"), owners.toString());
    }

    @Test
    @DisplayName("Every rebuilt class is in Retromod's jar and reachable from what the redirects name")
    void everyRebuiltClassIsRegistered() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyTransferApi.registerCapabilities(transformer);

        Set<String> registered = new TreeSet<>();
        for (String name : transformer.getSyntheticClasses().keySet()) {
            if (name.startsWith(NEW)) registered.add(name);
        }
        assertEquals(builtClasses(), registered, "a rebuilt class is missing from the registration, or the build");
        for (String path : LegacyTransferApi.CLASSES) {
            assertTrue(registered.contains(NEW + path), path + " is listed but was not built");
        }
    }

    @Test
    @DisplayName("Rebuilt classes name no stub-only type")
    void rebuiltClassesNameOnlyRealPackages() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyTransferApi.registerCapabilities(transformer);
        for (var entry : transformer.getSyntheticClasses().entrySet()) {
            if (!entry.getKey().startsWith(NEW)) continue;
            for (String referenced : RebuiltApiClasses.referencedClasses(entry.getValue())) {
                assertTrue(transformer.getSyntheticClasses().containsKey(referenced),
                        entry.getKey() + " names " + referenced + ", which is not registered");
            }
        }
    }

    // Rebuilt NeoForge classes that other bridges register: the event members and the loot modifier base.
    private static final List<String> OTHER_BRIDGES = List.of("event/", "common/");

    /** The rebuilt transfer classes the build copied next to Retromod's own. */
    private static Set<String> builtClasses() throws Exception {
        URL root = LegacyTransferApiTest.class.getClassLoader().getResource(NEW);
        assertNotNull(root, "the rebuilt classes were not copied into the build output");
        Path base = Path.of(root.toURI());
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(base)) {
            files.filter(file -> file.toString().endsWith(".class")).forEach(file -> {
                String relative = base.relativize(file).toString().replace('\\', '/');
                if (OTHER_BRIDGES.stream().anyMatch(relative::startsWith)) return;
                names.add(NEW + relative.substring(0, relative.length() - ".class".length()));
            });
        }
        return names;
    }

    /** A 1.21.1 machine's handler: it extends ItemStackHandler and looks up the old capability. */
    private static byte[] machineHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/MachineHandler", null, OLD + "items/ItemStackHandler", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitInsn(ICONST_3);
        init.visitMethodInsn(INVOKESPECIAL, OLD + "items/ItemStackHandler", "<init>", "(I)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        String cap = "L" + OLD + "capabilities/BlockCapability;";
        MethodVisitor find = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "find",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/lang/Object;", null, null);
        find.visitCode();
        find.visitVarInsn(ALOAD, 0);
        find.visitFieldInsn(GETSTATIC, OLD + "capabilities/Capabilities$ItemHandler", "BLOCK", cap);
        find.visitVarInsn(ALOAD, 1);
        find.visitInsn(ACONST_NULL);
        find.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/Level", "getCapability",
                "(" + cap + "Lnet/minecraft/core/BlockPos;Ljava/lang/Object;)Ljava/lang/Object;", false);
        find.visitInsn(ARETURN);
        find.visitMaxs(0, 0);
        find.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
