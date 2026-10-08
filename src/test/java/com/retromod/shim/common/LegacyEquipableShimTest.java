/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Minecraft 1.21.2 deleted {@code Equipable}, so a 1.21.1 item implementing it failed to load and
 * took its mod's whole item registration down (#249, Sophisticated Backpacks' backpack item).
 */
class LegacyEquipableShimTest {

    private static final String EQUIPABLE = "net/minecraft/world/item/Equipable";

    @AfterEach
    void clearRedirects() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void appliesOnlyWhereTheInterfaceIsGone() {
        LegacyEquipableShim shim = new LegacyEquipableShim();
        for (String host : List.of("1.20.1", "1.21.1")) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " still ships Equipable, so items must keep the real interface");
        }
        for (String host : List.of("1.21.2", "1.21.11", "26.1.2", "26.2")) {
            assertTrue(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " has no Equipable, so the stand-in must apply");
        }
    }

    @Test
    void standInKeepsTheMembersWhoseTypesSurvived() {
        ClassNode standIn = new ClassNode();
        new ClassReader(LegacyEquipableShim.generateStandIn()).accept(standIn, 0);

        assertTrue((standIn.access & ACC_INTERFACE) != 0, "items implement it, so it is an interface");
        assertMethod(standIn, "getEquipmentSlot", "()Lnet/minecraft/world/entity/EquipmentSlot;",
                ACC_ABSTRACT);
        assertMethod(standIn, "getEquipSound", "()Lnet/minecraft/core/Holder;", 0);
        assertMethod(standIn, "get", "(Lnet/minecraft/world/item/ItemStack;)L"
                + LegacyEquipableShim.STAND_IN + ";", ACC_STATIC);
        assertTrue(standIn.methods.stream().noneMatch(m -> m.name.equals("swapWithEquipmentSlot")),
                "swapWithEquipmentSlot returns a type 1.21.2 deleted, so it must not be emitted");
    }

    @Test
    void anItemImplementingEquipableLoadsAgainstTheStandIn() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new LegacyEquipableShim().registerRedirects(transformer);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/mod/BackpackItem", null,
                "net/minecraft/world/item/Item", new String[] {EQUIPABLE});
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(),
                "test/mod/BackpackItem.class")).accept(out, 0);
        assertEquals(List.of(LegacyEquipableShim.STAND_IN), out.interfaces,
                "the deleted interface must be swapped for the stand-in");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEquipableShim.STAND_IN),
                "the stand-in must be registered for embedding");
    }

    private static void assertMethod(ClassNode owner, String name, String desc, int flag) {
        MethodNode method = owner.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
        assertNotNull(method, name + desc + " must be present");
        if (flag != 0) {
            assertTrue((method.access & flag) != 0, name + " has the wrong modifiers");
        }
    }
}
