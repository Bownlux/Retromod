/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generated stand-in for a deleted base class has one job: let a subclass's original
 * {@code super(...)} call link against a base that still exists. These cases pin the argument
 * forwarding, because loading the arguments from the wrong local slots produces a class that
 * verifies and then misbehaves, which is far worse than one that fails to link.
 */
class LegacyBaseSynthesizerTest {

    private static final String GENERATED = "com/retromod/generated/LegacyBaseUnderTest";
    private static final String MODERN = "test/legacy/ModernBase";
    private static final String PROPS = "Ltest/legacy/Properties;";
    private static final String MODERN_CTOR = "(" + PROPS + ")V";

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static MethodNode ctor(ClassNode cn, String desc) {
        Optional<MethodNode> found = cn.methods.stream()
                .filter(m -> m.name.equals("<init>") && m.desc.equals(desc)).findFirst();
        assertTrue(found.isPresent(), "expected a constructor " + desc + " on " + cn.name);
        return found.get();
    }

    private static MethodInsnNode superCallOf(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("<init>")) return call;
        }
        return fail("constructor has no super() call");
    }

    private static List<Integer> loadedSlots(MethodNode method) {
        return method.instructions.getFirst() == null ? List.of()
                : java.util.stream.StreamSupport
                        .stream(method.instructions.spliterator(), false)
                        .filter(VarInsnNode.class::isInstance)
                        .map(insn -> ((VarInsnNode) insn).var)
                        .toList();
    }

    @Test
    @DisplayName("extends the surviving base and keeps the legacy constructor descriptor")
    void keepsLegacyDescriptor() {
        String legacy = "(I" + PROPS + "I)V";
        ClassNode cn = read(LegacyBaseSynthesizer.generate(
                GENERATED, MODERN, MODERN_CTOR, List.of(legacy)));

        assertEquals(MODERN, cn.superName);
        assertEquals(MODERN, superCallOf(ctor(cn, legacy)).owner,
                "the legacy constructor must forward to the surviving base");
    }

    @Test
    @DisplayName("forwards from the argument's real slot, not its argument index")
    void forwardsFromTheCorrectSlot() {
        // A long occupies two local slots. Loading by argument index instead of slot would read
        // the second half of the long and hand the base a garbage reference.
        String legacy = "(J" + PROPS + ")V";
        ClassNode cn = read(LegacyBaseSynthesizer.generate(
                GENERATED, MODERN, MODERN_CTOR, List.of(legacy)));

        MethodNode generated = ctor(cn, legacy);
        assertEquals(List.of(0, 3), loadedSlots(generated),
                "this at 0, then the properties argument at slot 3 (a long occupies 1 and 2)");
        assertEquals(Opcodes.ALOAD,
                ((VarInsnNode) generated.instructions.get(1)).getOpcode(),
                "the forwarded argument is a reference");
    }

    @Test
    @DisplayName("always declares the modern constructor, even when it was not listed")
    void alwaysDeclaresTheModernConstructor() {
        ClassNode cn = read(LegacyBaseSynthesizer.generate(
                GENERATED, MODERN, MODERN_CTOR, List.of("(I" + PROPS + ")V")));
        assertDoesNotThrow(() -> ctor(cn, MODERN_CTOR),
                "a subclass built after the base reached its modern shape calls it directly");
    }

    @Test
    @DisplayName("refuses a legacy constructor that cannot supply the modern arguments")
    void refusesAnUnsatisfiableConstructor() {
        // No properties argument anywhere, so there is nothing to forward. Generating a base that
        // passed null instead would let the subclass link and then fail somewhere unrelated.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LegacyBaseSynthesizer.generate(
                        GENERATED, MODERN, MODERN_CTOR, List.of("(IFI)V")));
        assertTrue(error.getMessage().contains("do not supply the modern ones"), error.getMessage());
    }

    @Test
    @DisplayName("refuses a legacy constructor that reorders the modern arguments")
    void refusesReorderedArguments() {
        String twoArgCtor = "(" + PROPS + "Ltest/legacy/Sound;)V";
        assertThrows(IllegalArgumentException.class,
                () -> LegacyBaseSynthesizer.generate(GENERATED, MODERN, twoArgCtor,
                        List.of("(Ltest/legacy/Sound;" + PROPS + ")V")),
                "matching is left to right; a reordered list must be refused, not guessed at");
    }
}
