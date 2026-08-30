/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.mixin.MixinHandlerResignature.ParamInsert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A MixinExtras {@code @Local} names a variable in the target method, so a parameter Minecraft adds
 * to that target moves the variable the capture was pointing at. Before this repair every handler
 * carrying a {@code @Local} was refused, which is how several of them reached the Mixin blocklist.
 *
 * <p>No mod in the test corpus ships a {@code @Local} capture, so these handlers are built here.
 */
class MixinLocalSlotRepairTest {

    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";

    /**
     * An {@code @Inject}-shaped handler: it captures one target parameter, then a
     * {@code CallbackInfo}, then the {@code @Local} captures.
     *
     * @param tailParams captured locals appended after the {@code CallbackInfo}
     */
    private static MethodNode handlerWithLocals(List<AnnotationNode> localsForTail, String... tailParams) {
        StringBuilder desc = new StringBuilder("(Lnet/minecraft/world/entity/Entity;").append(CI);
        for (String t : tailParams) desc.append(t);
        desc.append(")V");

        MethodNode h = new MethodNode(ACC_PRIVATE, "handler", desc.toString(), null, null);
        h.instructions.add(new VarInsnNode(ALOAD, 1));
        h.instructions.add(new InsnNode(POP));
        h.instructions.add(new InsnNode(RETURN));
        h.maxLocals = 8;
        h.maxStack = 1;

        int count = Type.getArgumentTypes(h.desc).length;
        @SuppressWarnings("unchecked")
        List<AnnotationNode>[] params = (List<AnnotationNode>[]) new List<?>[count];
        for (int i = 0; i < localsForTail.size(); i++) {
            List<AnnotationNode> one = new ArrayList<>();
            one.add(localsForTail.get(i));
            params[count - localsForTail.size() + i] = one;
        }
        h.invisibleParameterAnnotations = params;
        h.invisibleAnnotableParameterCount = count;

        AnnotationNode inject = new AnnotationNode(
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("doHurtTarget"))));
        h.visibleAnnotations = new ArrayList<>(List.of(inject));
        return h;
    }

    private static AnnotationNode local(String key, Object value) {
        AnnotationNode a = new AnnotationNode(LOCAL);
        a.values = new ArrayList<>(List.of(key, value));
        return a;
    }

    private static Object localValue(MethodNode h, int paramIndex, String key) {
        List<AnnotationNode> p = h.invisibleParameterAnnotations[paramIndex];
        AnnotationNode a = p.get(0);
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }

    private static List<ParamInsert> serverLevel() {
        return List.of(new ParamInsert(0, SERVER_LEVEL));
    }

    @Test
    @DisplayName("A raw index capture moves by the width the inserted parameter added")
    void rawIndexCaptureShifts() {
        // @Local(index = 4) on a captured int, target gains a leading ServerLevel (one slot).
        MethodNode h = handlerWithLocals(List.of(local("index", 4)), "I");

        assertTrue(MixinHandlerResignature.insertParams(h, serverLevel()),
                "a @Local capture must no longer block the repair");

        assertEquals("(" + SERVER_LEVEL + "Lnet/minecraft/world/entity/Entity;" + CI + "I)V", h.desc);
        assertEquals(5, localValue(h, 3, "index"),
                "slot 4 moved to 5 because the inserted ServerLevel occupies one slot ahead of it");
    }

    @Test
    @DisplayName("A capture of another type is kept exactly as written")
    void capturesOfAnotherTypeAreUntouched() {
        // Mixin's discriminator compares local types with Type.equals, so an added ServerLevel
        // cannot join the candidates for an int capture however it selects among them.
        MethodNode byOrdinal = handlerWithLocals(List.of(local("ordinal", 2)), "I");
        assertTrue(MixinHandlerResignature.insertParams(byOrdinal, serverLevel()));
        assertEquals(2, localValue(byOrdinal, 3, "ordinal"), "an ordinal of another type is stable");

        MethodNode byName = handlerWithLocals(
                List.of(local("name", new ArrayList<>(List.of("damage")))), "I");
        assertTrue(MixinHandlerResignature.insertParams(byName, serverLevel()));
        assertEquals(List.of("damage"), localValue(byName, 3, "name"));

        MethodNode bare = handlerWithLocals(List.of(new AnnotationNode(LOCAL)), "I");
        assertTrue(MixinHandlerResignature.insertParams(bare, serverLevel()),
                "a bare capture of an unrelated type is unaffected by the added parameter");
    }

    @Test
    @DisplayName("A capture of the added type is refused because it gains a rival")
    void captureOfTheAddedTypeIsRefused() {
        for (MethodNode h : List.of(
                handlerWithLocals(List.of(local("ordinal", 0)), SERVER_LEVEL),
                handlerWithLocals(List.of(new AnnotationNode(LOCAL)), SERVER_LEVEL))) {
            String before = h.desc;
            assertFalse(MixinHandlerResignature.insertParams(h, serverLevel()),
                    "the added parameter matches this capture's type, so the choice is ambiguous");
            assertEquals(before, h.desc);
        }
    }

    @Test
    @DisplayName("An explicit capture type decides ambiguity, not the parameter type")
    void explicitCaptureTypeDecidesAmbiguity() {
        AnnotationNode a = new AnnotationNode(LOCAL);
        a.values = new ArrayList<>(List.of("type", Type.getType(SERVER_LEVEL)));
        MethodNode h = handlerWithLocals(List.of(a), "Ljava/lang/Object;");
        String before = h.desc;

        assertFalse(MixinHandlerResignature.insertParams(h, serverLevel()),
                "the declared type() is what the discriminator matches on");
        assertEquals(before, h.desc);
    }

    @Test
    @DisplayName("An index the discriminator ignores is left alone rather than activated")
    void inertIndexIsNotActivated() {
        // On an instance target Mixin skips slot 0, so index 0 is inert and the capture really
        // selects by type. Shifting it to 1 would pin a variable the author never chose.
        MethodNode h = handlerWithLocals(List.of(local("index", 0)), "I");
        assertFalse((h.access & ACC_STATIC) != 0, "the fixture is an instance handler");

        assertTrue(MixinHandlerResignature.insertParams(h, serverLevel()));
        assertEquals(0, localValue(h, 3, "index"),
                "an inert index must survive the repair unchanged");
    }

    @Test
    @DisplayName("A capture annotated before the CallbackInfo is refused")
    void captureBeforeTheCallbackInfoIsRefused() {
        MethodNode h = handlerWithLocals(List.of(), "I");
        @SuppressWarnings("unchecked")
        List<AnnotationNode>[] params = (List<AnnotationNode>[]) new List<?>[
                Type.getArgumentTypes(h.desc).length];
        params[0] = new ArrayList<>(List.of(local("index", 4)));
        h.invisibleParameterAnnotations = params;
        h.invisibleAnnotableParameterCount = params.length;
        String before = h.desc;

        assertFalse(MixinHandlerResignature.insertParams(h, serverLevel()),
                "sugar parameters follow the trailer, so this shape is not understood");
        assertEquals(before, h.desc);
    }

    @Test
    @DisplayName("A slot capture is repaired even when it reads the inserted type")
    void slotCaptureIsExactRegardlessOfType() {
        // An index names one slot, so it stays exact whatever type the inserted parameter has.
        MethodNode h = handlerWithLocals(List.of(local("index", 3)), SERVER_LEVEL);

        assertTrue(MixinHandlerResignature.insertParams(h, serverLevel()),
                "an explicit slot does not depend on type matching");
        assertEquals(4, localValue(h, 3, "index"));
    }

    @Test
    @DisplayName("One unsafe capture makes the whole handler decline")
    void oneUnsafeCaptureDeclinesTheHandler() {
        // The first capture is a repairable slot, the second is of the added type. The handler is
        // repaired as a unit, so the ambiguous one has to stop the whole thing.
        MethodNode h = handlerWithLocals(
                List.of(local("index", 4), new AnnotationNode(LOCAL)), "I", SERVER_LEVEL);
        String before = h.desc;

        assertFalse(MixinHandlerResignature.insertParams(h, serverLevel()));
        assertEquals(before, h.desc, "a declined handler is left byte-identical");
        assertEquals(4, localValue(h, 2, "index"), "and its repairable capture is not half-moved");
    }

    @Test
    @DisplayName("A capture ahead of a non-leading insertion keeps its slot")
    void captureAheadOfANonLeadingInsertionIsUnchanged() {
        // tryGenerateStructure is registered with an insert at parameter index 9, so an insertion is
        // not always leading. Only the locals at or after the inserted slot move.
        MethodNode h = new MethodNode(ACC_PRIVATE, "handler",
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;" + CI + "I)V",
                null, null);
        h.instructions.add(new InsnNode(RETURN));
        h.maxLocals = 8;
        h.maxStack = 1;
        @SuppressWarnings("unchecked")
        List<AnnotationNode>[] params = (List<AnnotationNode>[]) new List<?>[4];
        params[3] = new ArrayList<>(List.of(local("index", 1)));
        h.invisibleParameterAnnotations = params;
        h.invisibleAnnotableParameterCount = 4;
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("tryGenerateStructure"))));
        h.visibleAnnotations = new ArrayList<>(List.of(inject));

        // Insert at captured index 1, which is target slot 2 for an instance handler.
        assertTrue(MixinHandlerResignature.insertParams(h,
                List.of(new ParamInsert(1, SERVER_LEVEL))));
        assertEquals(1, localValue(h, 4, "index"),
                "slot 1 sits ahead of the inserted slot 2, so it must not move");
    }

    @Test
    @DisplayName("A capture at or after a non-leading insertion moves")
    void captureAfterANonLeadingInsertionMoves() {
        MethodNode h = new MethodNode(ACC_PRIVATE, "handler",
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;" + CI + "I)V",
                null, null);
        h.instructions.add(new InsnNode(RETURN));
        h.maxLocals = 8;
        h.maxStack = 1;
        @SuppressWarnings("unchecked")
        List<AnnotationNode>[] params = (List<AnnotationNode>[]) new List<?>[4];
        params[3] = new ArrayList<>(List.of(local("index", 2)));
        h.invisibleParameterAnnotations = params;
        h.invisibleAnnotableParameterCount = 4;
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("tryGenerateStructure"))));
        h.visibleAnnotations = new ArrayList<>(List.of(inject));

        assertTrue(MixinHandlerResignature.insertParams(h,
                List.of(new ParamInsert(1, SERVER_LEVEL))));
        assertEquals(3, localValue(h, 4, "index"),
                "slot 2 is where the ServerLevel landed, so the old variable moved to 3");
    }

    @Test
    @DisplayName("A handler with no captures still repairs exactly as before")
    void handlerWithoutCapturesIsUnaffected() {
        MethodNode h = handlerWithLocals(List.of());
        assertTrue(MixinHandlerResignature.insertParams(h, serverLevel()));
        assertEquals("(" + SERVER_LEVEL + "Lnet/minecraft/world/entity/Entity;" + CI + ")V", h.desc);
    }
}
