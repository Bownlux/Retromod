/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Pins the tick-event holder rename reported by Pathmind on Fabric 26.2 (#208). */
class FabricTickEventFieldRenameTest {

    private static final String CLIENT =
            "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents";
    private static final String SERVER =
            "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents";
    private static final String EVENT_DESC = "Lnet/fabricmc/fabric/api/event/Event;";

    @AfterEach
    void restore() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static byte[] oldTickEventReads() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/OldTickEvents", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "register", "()V", null, null);
        method.visitCode();
        for (String owner : new String[]{CLIENT, SERVER}) {
            for (String field : new String[]{"START_WORLD_TICK", "END_WORLD_TICK"}) {
                method.visitFieldInsn(Opcodes.GETSTATIC, owner, field, EVENT_DESC);
                method.visitInsn(Opcodes.POP);
            }
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("#208: Fabric world-tick event fields follow the World to Level rename")
    void worldTickFieldsBecomeLevelTickFields() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        byte[] output = transformer.transformClass(oldTickEventReads(), "test/OldTickEvents");
        assertNotNull(output, "the old holder fields should make the class transformable");

        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        Map<String, String> fields = new LinkedHashMap<>();
        node.methods.stream().filter(method -> method.name.equals("register")).findFirst().orElseThrow()
                .instructions.forEach(instruction -> {
                    if (instruction instanceof FieldInsnNode field) {
                        fields.put(field.owner + "." + field.name, field.desc);
                    }
                });

        assertEquals(Map.of(
                CLIENT + ".START_LEVEL_TICK", EVENT_DESC,
                CLIENT + ".END_LEVEL_TICK", EVENT_DESC,
                SERVER + ".START_LEVEL_TICK", EVENT_DESC,
                SERVER + ".END_LEVEL_TICK", EVENT_DESC), fields);
    }
}
