/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodTransformer.MethodTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Codec.unit, DataResult.PartialResult and getOrThrow(boolean, Consumer) follow the host's DataFixerUpper, or offline the target version. */
class LegacyDfuRenamesTest {

    private static final String UNIT_OBJECT = "(Ljava/lang/Object;)Lcom/mojang/serialization/Codec;";
    private static final String UNIT_SUPPLIER = "(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("DFU 6 (Minecraft 1.20.1) keeps Codec.unit and PartialResult, so nothing is redirected")
    void dfu6KeepsTheOldNames() {
        LegacyDfuRenames.register(transformer, dfu6(), "26.2");

        assertNull(redirect(UNIT_OBJECT));
        assertNull(redirect(UNIT_SUPPLIER));
        assertNull(transformer.getClassRedirects().get(LegacyDfuRenames.PARTIAL_RESULT));
        assertNull(getOrThrow(), "DFU 6 still has getOrThrow(boolean, Consumer)");
    }

    @Test
    @DisplayName("DFU 10 (Minecraft 26.2) sends both Codec.unit forms to MapCodec.unitCodec and PartialResult to Error")
    void dfu10MovesBoth() {
        LegacyDfuRenames.register(transformer, dfu10(), "1.20.5");

        assertUnitCodec(UNIT_OBJECT);
        assertUnitCodec(UNIT_SUPPLIER);
        assertEquals(LegacyDfuRenames.ERROR, transformer.getClassRedirects().get(LegacyDfuRenames.PARTIAL_RESULT));
        assertGetOrThrowHelper();
    }

    @Test
    @DisplayName("Offline, getOrThrow(boolean, Consumer) goes to the helper on 1.20.5 and newer, Forge 1.20.6 included")
    void offlineGetOrThrowFromThe1205Rebuild() {
        Function<String, ClassNode> unreadable = name -> null;

        LegacyDfuRenames.register(transformer, unreadable, "1.20.4");
        assertNull(getOrThrow(), "1.20.4 still ships DFU 6");

        LegacyDfuRenames.register(transformer, unreadable, "1.20.6");
        assertGetOrThrowHelper();
    }

    @Test
    @DisplayName("Offline, Error replaces PartialResult from 1.21.1 and MapCodec.unitCodec replaces Codec.unit from 1.21.11")
    void offlineFollowsTheTargetVersion() {
        Function<String, ClassNode> unreadable = name -> null;

        LegacyDfuRenames.register(transformer, unreadable, "1.20.4");
        assertNull(redirect(UNIT_OBJECT));
        assertNull(transformer.getClassRedirects().get(LegacyDfuRenames.PARTIAL_RESULT), "1.20.4 still has PartialResult");

        LegacyDfuRenames.register(transformer, unreadable, "1.21.1");
        assertNull(redirect(UNIT_OBJECT), "1.21.1 mods still call Codec.unit, so its DFU has it");
        assertEquals(LegacyDfuRenames.ERROR, transformer.getClassRedirects().get(LegacyDfuRenames.PARTIAL_RESULT),
                "1.21.1 mods name DataResult.Error, so its DFU has no PartialResult");

        transformer.clearRedirectsForTesting();
        LegacyDfuRenames.register(transformer, unreadable, "26.3");
        assertUnitCodec(UNIT_OBJECT);
        assertUnitCodec(UNIT_SUPPLIER);
        assertEquals(LegacyDfuRenames.ERROR, transformer.getClassRedirects().get(LegacyDfuRenames.PARTIAL_RESULT));
    }

    private MethodTarget getOrThrow() {
        return transformer.getMethodRedirects().get(new MethodKey(LegacyDfuRenames.DATA_RESULT, "getOrThrow",
                LegacyDfuRenames.OLD_GET_OR_THROW));
    }

    private void assertGetOrThrowHelper() {
        MethodTarget target = getOrThrow();
        assertNotNull(target, "DataResult.getOrThrow(boolean, Consumer) must be redirected");
        assertEquals(LegacyDfuRenames.GET_OR_THROW_HELPER + ".getOrThrow"
                + "(Ljava/lang/Object;ZLjava/util/function/Consumer;)Ljava/lang/Object;", target.owner() + "." + target.name() + target.desc());
        assertTrue(target.devirtualize(), "the receiver becomes the helper's first argument");
    }

    private MethodTarget redirect(String desc) {
        return transformer.getMethodRedirects().get(new MethodKey(LegacyDfuRenames.CODEC, "unit", desc));
    }

    private void assertUnitCodec(String desc) {
        MethodTarget target = redirect(desc);
        assertNotNull(target, "Codec.unit" + desc + " must be redirected");
        assertEquals(LegacyDfuRenames.MAP_CODEC, target.owner());
        assertEquals("unitCodec", target.name());
        assertEquals(desc, target.desc());
    }

    /** DataFixerUpper 6.0.8's shape: Codec.unit, no unitCodec, DataResult.PartialResult and the old getOrThrow. */
    private static Function<String, ClassNode> dfu6() {
        Map<String, ClassNode> classes = new HashMap<>();
        classes.put(LegacyDfuRenames.CODEC, node(LegacyDfuRenames.CODEC, "unit", UNIT_OBJECT, "unit", UNIT_SUPPLIER));
        classes.put(LegacyDfuRenames.MAP_CODEC, node(LegacyDfuRenames.MAP_CODEC));
        classes.put(LegacyDfuRenames.DATA_RESULT, node(LegacyDfuRenames.DATA_RESULT,
                "getOrThrow", LegacyDfuRenames.OLD_GET_OR_THROW));
        classes.put(LegacyDfuRenames.PARTIAL_RESULT, node(LegacyDfuRenames.PARTIAL_RESULT, "message", "()Ljava/lang/String;"));
        return classes::get;
    }

    /** DataFixerUpper 10.0.21's shape: MapCodec.unitCodec, and DataResult.Error in place of PartialResult. */
    private static Function<String, ClassNode> dfu10() {
        Map<String, ClassNode> classes = new HashMap<>();
        classes.put(LegacyDfuRenames.CODEC, node(LegacyDfuRenames.CODEC));
        classes.put(LegacyDfuRenames.MAP_CODEC, node(LegacyDfuRenames.MAP_CODEC,
                "unitCodec", UNIT_OBJECT, "unitCodec", UNIT_SUPPLIER));
        classes.put(LegacyDfuRenames.DATA_RESULT, node(LegacyDfuRenames.DATA_RESULT));
        classes.put(LegacyDfuRenames.ERROR, node(LegacyDfuRenames.ERROR, "message", "()Ljava/lang/String;"));
        return classes::get;
    }

    /** A class with the given methods, as name and descriptor pairs. */
    private static ClassNode node(String name, String... methods) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (int i = 0; i < methods.length; i += 2) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methods[i], methods[i + 1], null, null));
        }
        return node;
    }
}
