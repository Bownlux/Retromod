/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Exact 26.x replacements for the small client interaction methods removed after 1.21.1.
 *
 * <p>The generated class uses the current Minecraft types directly. It is registered as a
 * synthetic so each transformed mod receives a private relocated copy when needed. Generating it
 * keeps Retromod's own compile classpath independent of Minecraft.
 */
public final class LegacyClientInteractionSynthetic {

    private LegacyClientInteractionSynthetic() {}

    public static final String INTERNAL =
            "com/retromod/shim/common/embedded/LegacyClientInteraction";

    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String GAME_MODE = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String HIT = "net/minecraft/world/phys/EntityHitResult";
    private static final String HAND = "net/minecraft/world/InteractionHand";
    private static final String RESULT = "net/minecraft/world/InteractionResult";
    private static final String SUCCESS = RESULT + "$Success";
    private static final String SWING_SOURCE = RESULT + "$SwingSource";

    private static final String DISPLAY_OLD = "(L" + COMPONENT + ";Z)V";
    private static final String DISPLAY_BRIDGE =
            "(L" + PLAYER + ";L" + COMPONENT + ";Z)V";
    private static final String INTERACT_OLD =
            "(L" + PLAYER + ";L" + ENTITY + ";L" + HAND + ";)L" + RESULT + ";";
    private static final String INTERACT_BRIDGE =
            "(L" + GAME_MODE + ";L" + PLAYER + ";L" + ENTITY + ";L" + HAND
                    + ";)L" + RESULT + ";";

    /** Registers the generated helper and the exact removed-call redirects. */
    public static void register(RetromodTransformer transformer) {
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(INTERNAL, generate());
        }

        // The old boolean chose action bar (true) or ordinary system text (false). The split
        // methods still preserve both behaviors, so keep the flag instead of guessing one path.
        for (String owner : new String[]{PLAYER, LOCAL_PLAYER}) {
            transformer.registerMethodRedirect(owner, "displayClientMessage", DISPLAY_OLD,
                    INTERNAL, "displayClientMessage", DISPLAY_BRIDGE, true);
        }

        // The generic entity interaction became the location-bearing form. EntityHitResult(entity)
        // supplies the entity origin, which is the modern representation of the old no-location
        // fallback interaction.
        transformer.registerMethodRedirect(GAME_MODE, "interact", INTERACT_OLD,
                INTERNAL, "interact", INTERACT_BRIDGE, true);

        // canHurtPlayer() is not a creative-mode test. Read the surviving player ability instead.
        transformer.registerMethodRedirect(GAME_MODE, "hasInfiniteItems", "()Z",
                INTERNAL, "hasInfiniteItems", "(L" + GAME_MODE + ";)Z", true);

        // consumesAction() is broader than the removed shouldSwing() contract. Only a client-side
        // Success requests the local hand swing on 26.x.
        transformer.registerMethodRedirect(RESULT, "shouldSwing", "()Z",
                INTERNAL, "shouldSwing", "(L" + RESULT + ";)Z", true);
    }

    public static byte[] generate() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);
        emitConstructor(writer);
        emitDisplayClientMessage(writer);
        emitInteract(writer);
        emitHasInfiniteItems(writer);
        emitShouldSwing(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitDisplayClientMessage(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "displayClientMessage", DISPLAY_BRIDGE, null, null);
        method.visitCode();
        Label system = new Label();
        method.visitVarInsn(ILOAD, 2);
        method.visitJumpInsn(IFEQ, system);
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "sendOverlayMessage",
                "(L" + COMPONENT + ";)V", false);
        method.visitInsn(RETURN);
        method.visitLabel(system);
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "sendSystemMessage",
                "(L" + COMPONENT + ";)V", false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitInteract(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "interact", INTERACT_BRIDGE, null, null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitVarInsn(ALOAD, 2);
        method.visitTypeInsn(NEW, HIT);
        method.visitInsn(DUP);
        method.visitVarInsn(ALOAD, 2);
        method.visitMethodInsn(INVOKESPECIAL, HIT, "<init>", "(L" + ENTITY + ";)V", false);
        method.visitVarInsn(ALOAD, 3);
        method.visitMethodInsn(INVOKEVIRTUAL, GAME_MODE, "interact",
                "(L" + PLAYER + ";L" + ENTITY + ";L" + HIT + ";L" + HAND
                        + ";)L" + RESULT + ";", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitHasInfiniteItems(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "hasInfiniteItems", "(L" + GAME_MODE + ";)Z", null, null);
        method.visitCode();
        Label unavailable = new Label();
        method.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance",
                "()L" + MINECRAFT + ";", false);
        method.visitInsn(DUP);
        method.visitJumpInsn(IFNULL, unavailable);
        method.visitFieldInsn(GETFIELD, MINECRAFT, "player", "L" + LOCAL_PLAYER + ";");
        method.visitInsn(DUP);
        method.visitJumpInsn(IFNULL, unavailable);
        method.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "hasInfiniteMaterials", "()Z", false);
        method.visitInsn(IRETURN);
        method.visitLabel(unavailable);
        method.visitInsn(POP);
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitShouldSwing(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "shouldSwing", "(L" + RESULT + ";)Z", null, null);
        method.visitCode();
        Label noSwing = new Label();
        method.visitVarInsn(ALOAD, 0);
        method.visitTypeInsn(INSTANCEOF, SUCCESS);
        method.visitJumpInsn(IFEQ, noSwing);
        method.visitVarInsn(ALOAD, 0);
        method.visitTypeInsn(CHECKCAST, SUCCESS);
        method.visitMethodInsn(INVOKEVIRTUAL, SUCCESS, "swingSource",
                "()L" + SWING_SOURCE + ";", false);
        method.visitFieldInsn(GETSTATIC, SWING_SOURCE, "CLIENT", "L" + SWING_SOURCE + ";");
        method.visitJumpInsn(IF_ACMPNE, noSwing);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(noSwing);
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
