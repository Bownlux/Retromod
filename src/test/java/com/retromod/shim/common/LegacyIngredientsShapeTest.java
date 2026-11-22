/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt Ingredient.of(TagKey) takes the registration lookup's named set first, and the frozen registry's only after. */
class LegacyIngredientsShapeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes",
            "com/retromod/legacyapi/minecraft/world/item/crafting/LegacyIngredients.class");

    @Test
    @DisplayName("the bootstrap registration lookup is asked first, and the frozen fallback runs only on its IllegalStateException")
    void registrationLookupFirst() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(COMPILED)).accept(node, 0);
        MethodNode of = node.methods.stream().filter(m -> m.name.equals("of")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        of.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode call) calls.add(call.name); });
        int lookup = calls.indexOf("acquireBootstrapRegistrationLookup");
        assertTrue(lookup >= 0 && lookup < calls.indexOf("get"), calls.toString());
        assertEquals(1, of.tryCatchBlocks.size());
        assertEquals("java/lang/IllegalStateException", of.tryCatchBlocks.get(0).type, "only a frozen registry falls back");
    }
}
