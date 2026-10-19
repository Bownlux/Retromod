/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/** Keeps the old {@code LivingEntity.swing} calls and {@code swinging} reads working on 26.3. */
public final class LegacySwingBridge {

    static final String HELPER = "com/retromod/generated/LegacySwing";
    /** The helper's cached lookups of an entity's own {@code swing(hand)} and {@code swing(hand, send)}. */
    static final String OWN_SWING = "ownSwing";
    static final String OWN_SWING_SEND = "ownSwingSend";

    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String HAND = "net/minecraft/world/InteractionHand";
    static final String ANIMATION = "net/minecraft/world/item/component/SwingAnimation";

    // 26.3 replaced swing(hand) and swing(hand, sendToSwingingEntity) with
    // swing(hand, SwingAnimation, boolean).
    static final String SWING_HAND = "(L" + HAND + ";)V";
    static final String SWING_HAND_SEND = "(L" + HAND + ";Z)V";
    static final String SWING_ANIMATED = "(L" + HAND + ";L" + ANIMATION + ";Z)Z";
    static final String SWINGING = "swinging";
    static final String IS_SWINGING = "isSwinging";

    private LegacySwingBridge() {}

    /** Bridges each old form, and the flag, only on a host that lost it. */
    public static void register(RetromodTransformer transformer) {
        List<String> removed = removedForms();
        if (!removed.isEmpty()) registerRedirects(transformer, removed);
        if (lostSwingingFlag()) registerSwingingRead(transformer);
    }

    /** Reads of {@code swinging}, on {@code LivingEntity} or any subclass, ask {@code isSwinging()}. */
    static void registerSwingingRead(RetromodTransformer transformer) {
        // isSwinging() is true from the start of a swing until its duration has passed, the same
        // span the old flag covered. Mods read it through a player or their own entity, so the class
        // hierarchy is followed. A write is left as written: a swing now needs its hand and
        // animation, and the host has no public way to start or stop one from a flag.
        transformer.registerInheritedFieldAccessorRedirect(LIVING, SWINGING, "Z",
                IS_SWINGING, "()Z", null, null);
    }

    /** Whether the host lost the {@code swinging} flag, read from its own LivingEntity. */
    static boolean lostSwingingFlag() {
        ClassNode living = ClassResourceInspector.read(LIVING);
        // Offline the host classes are not readable, so the host version decides.
        if (living == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        }
        return lostSwingingFlag(living);
    }

    /** True when the host has {@code isSwinging()} and no longer has the {@code swinging} field. */
    static boolean lostSwingingFlag(ClassNode living) {
        boolean hasFlag = false;
        for (FieldNode field : living.fields) {
            if (field.name.equals(SWINGING) && field.desc.equals("Z")) hasFlag = true;
        }
        boolean hasQuery = false;
        for (MethodNode method : living.methods) {
            if (method.name.equals(IS_SWINGING) && method.desc.equals("()Z")) hasQuery = true;
        }
        return !hasFlag && hasQuery;
    }

    /** Unconditional registration for transform-shape tests; {@code removed} lists old descriptors. */
    static void registerRedirects(RetromodTransformer transformer, List<String> removed) {
        transformer.registerSyntheticClass(HELPER, generateHelper(removed));
        for (String desc : removed) {
            // Mods call swing on a player or on their own entity, so the class hierarchy is followed.
            transformer.registerInheritedMethodRedirect(LIVING, "swing", desc,
                    HELPER, "swing", helperDesc(desc));
            // An override's own super.swing goes to the helper's super$ twin, which does not
            // dispatch to the override again.
            OwnOverrideCall.register(transformer, HELPER, "swing", helperDesc(desc));
        }
    }

    /** The old swing forms the host lost, read from its own LivingEntity. */
    static List<String> removedForms() {
        ClassNode living = ClassResourceInspector.read(LIVING);
        ClassNode animation = ClassResourceInspector.read(ANIMATION);
        // Offline the host classes are not readable, so the host version decides.
        if (living == null || animation == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0
                    ? List.of(SWING_HAND, SWING_HAND_SEND) : List.of();
        }
        return removedForms(living, animation);
    }

    /** The old forms the host no longer has, when it has the animated form and its default animation. */
    static List<String> removedForms(ClassNode living, ClassNode animation) {
        List<String> removed = new ArrayList<>();
        if (!hasMethod(living, SWING_ANIMATED) || !hasDefault(animation)) return removed;
        // A host that kept one old form keeps its own method for that form.
        for (String desc : List.of(SWING_HAND, SWING_HAND_SEND)) {
            if (!hasMethod(living, desc)) removed.add(desc);
        }
        return removed;
    }

    /** The helper takes the entity first: {@code (LivingEntity, hand[, send])V}. */
    private static String helperDesc(String oldDesc) {
        return "(L" + LIVING + ";" + oldDesc.substring(1);
    }

    private static boolean hasMethod(ClassNode owner, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("swing") && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasDefault(ClassNode animation) {
        for (FieldNode field : animation.fields) {
            if (field.name.equals("DEFAULT") && field.desc.equals("L" + ANIMATION + ";")) return true;
        }
        return false;
    }

    /** Builds the helper class with a {@code swing} and a {@code super$swing} for each removed form. */
    static byte[] generateHelper(List<String> removed) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        OwnOverrideCall.declareLookup(cw, OWN_SWING);
        OwnOverrideCall.declareLookup(cw, OWN_SWING_SEND);
        for (String desc : removed) {
            // An entity's own override of an old swing keeps running when a mod calls it, even
            // through a LivingEntity reference: the helper asks the entity's class first, as
            // virtual dispatch did. The host itself no longer calls either form.
            boolean sendArgument = desc.equals(SWING_HAND_SEND);
            MethodVisitor call = begin(cw, "swing", helperDesc(desc));
            if (!sendArgument) {
                OwnOverrideCall.emitOwnCall(call, HELPER, OWN_SWING, "swing", helperDesc(desc),
                        OwnOverrideCall.ownArguments(helperDesc(desc)));
            }
            emitOwnSwingSend(call, sendArgument);
            emitSwing(call, sendArgument);

            // The super$ twin skips the override that called it. The old swing(hand) still reached
            // the entity's swing(hand, false), so its twin asks for that one.
            MethodVisitor superCall = begin(cw, OwnOverrideCall.superName("swing"), helperDesc(desc));
            if (!sendArgument) emitOwnSwingSend(superCall, false);
            emitSwing(superCall, sendArgument);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    /** Calls the entity's own {@code swing(hand, send)}, when its class declares one. */
    private static void emitOwnSwingSend(MethodVisitor mv, boolean sendArgument) {
        // The old one-argument swing(hand) called swing(hand, false) on the entity, so an override
        // of the two-argument form also answers it.
        OwnOverrideCall.emitOwnCall(mv, HELPER, OWN_SWING_SEND, "swing", helperDesc(SWING_HAND_SEND), call -> {
            call.visitVarInsn(Opcodes.ALOAD, 0);
            call.visitVarInsn(Opcodes.ALOAD, 1);
            if (sendArgument) {
                call.visitVarInsn(Opcodes.ILOAD, 2);
            } else {
                call.visitInsn(Opcodes.ICONST_0);
            }
        });
    }

    /** Emits {@code entity.swing(hand, SwingAnimation.DEFAULT, send)}, result dropped. */
    private static void emitSwing(MethodVisitor mv, boolean sendArgument) {
        // The old calls played the default arm swing, and the one-argument form did not send the
        // swing to the swinging player.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ANIMATION, "DEFAULT", "L" + ANIMATION + ";");
        if (sendArgument) {
            mv.visitVarInsn(Opcodes.ILOAD, 2);
        } else {
            mv.visitInsn(Opcodes.ICONST_0);
        }
        // The new method reports whether the swing started. The old ones returned nothing.
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "swing", SWING_ANIMATED, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
