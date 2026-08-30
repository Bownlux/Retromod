/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.mixin.MixinHandlerResignature.ParamInsert;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/**
 * Keeps a MixinExtras {@code @Local} selecting the same variable after Minecraft adds a parameter to
 * the method a handler hooks.
 *
 * <p>A {@code @Local} selects a variable in the target method, and Minecraft owns that method, so
 * the variable it wanted may sit in a different slot than when the mod was written. MixinExtras
 * hands the choice to Mixin's own {@code LocalVariableDiscriminator}, and its rules decide what can
 * be repaired here:
 *
 * <ul>
 *   <li>{@code index} is a raw slot in the target's local variable table, so it moves by the width
 *       of every parameter added at or before it.</li>
 *   <li>An index below the first argument slot is ignored by the discriminator, so it is left
 *       exactly as written. Moving it would turn an inert value into an active one and pin the
 *       capture to a variable the author never chose.</li>
 *   <li>{@code ordinal}, {@code name}, and a bare capture select among the locals whose type
 *       matches. The discriminator compares types with {@code Type.equals}, so an added parameter
 *       only disturbs them when its type is exactly the captured type.</li>
 * </ul>
 *
 * <p>It refuses a capture of the added type, because that capture becomes one of at least two
 * matches and nothing in the bytecode says which the author meant. It also refuses a capture
 * annotated on a parameter at or before the {@code CallbackInfo}, because sugar parameters follow
 * the trailer and an annotation before it is not a shape this repair understands.
 */
final class MixinLocalSlotRepair {

    static final String LOCAL_DESC = "Lcom/llamalad7/mixinextras/sugar/Local;";

    private MixinLocalSlotRepair() {}

    /** Whether {@code annotation} is a MixinExtras {@code @Local} capture. */
    static boolean isLocal(AnnotationNode annotation) {
        return annotation != null && LOCAL_DESC.equals(annotation.desc);
    }

    /**
     * The first slot the discriminator will consider. Mixin skips {@code this} on an instance
     * target, and Mixin requires a handler to match its target's staticness.
     */
    static int baseArgIndex(MethodNode handler) {
        return (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
    }

    /** Whether every {@code @Local} on {@code handler} still selects the variable it selected. */
    static boolean canRepair(MethodNode handler, List<ParamInsert> inserts) {
        if (inserts == null || inserts.isEmpty()) return false;
        Type[] args;
        try {
            args = Type.getArgumentTypes(handler.desc);
        } catch (RuntimeException e) {
            return false;
        }
        int callback = MixinHandlerResignature.callbackIndex(args);
        if (callback < 0) return false;
        int base = baseArgIndex(handler);
        return capturesSurvive(handler.visibleParameterAnnotations, args, callback, base, inserts)
                && capturesSurvive(handler.invisibleParameterAnnotations, args, callback, base, inserts);
    }

    private static boolean capturesSurvive(List<AnnotationNode>[] parameters, Type[] args,
            int callback, int base, List<ParamInsert> inserts) {
        if (parameters == null) return true;
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == null) continue;
            for (AnnotationNode annotation : parameters[i]) {
                if (!isLocal(annotation)) continue;
                // Sugar parameters follow the CallbackInfo trailer.
                if (i <= callback) return false;
                if (namesAnActiveSlot(annotation, base)) continue;
                // Selected by type, so only an added parameter of that exact type disturbs it.
                String captured = capturedType(annotation, args[i]);
                for (ParamInsert insert : inserts) {
                    if (insert.typeDescriptor().equals(captured)) return false;
                }
            }
        }
        return true;
    }

    /** Whether the capture pins a slot the discriminator actually reads. */
    private static boolean namesAnActiveSlot(AnnotationNode annotation, int base) {
        return value(annotation, "index") instanceof Integer index && index >= base;
    }

    /**
     * The type a capture selects on. An explicit {@code type} wins over the parameter's own type,
     * which is how a capture reads a variable through a different declared type.
     */
    private static String capturedType(AnnotationNode annotation, Type parameterType) {
        Object explicit = value(annotation, "type");
        if (explicit instanceof Type t && t.getSort() == Type.OBJECT) return t.getDescriptor();
        return parameterType.getDescriptor();
    }

    /**
     * Moves every pinned slot by the width the insertion added ahead of it.
     *
     * @param slots  the target local variable slot each inserted parameter occupies
     * @param widths the slot width of each inserted parameter, in the same order
     * @param base   the first slot the discriminator reads
     */
    static void shiftCapturedIndices(MethodNode handler, int[] slots, int[] widths, int base) {
        shiftCapturedIndices(handler.visibleParameterAnnotations, slots, widths, base);
        shiftCapturedIndices(handler.invisibleParameterAnnotations, slots, widths, base);
    }

    private static void shiftCapturedIndices(
            List<AnnotationNode>[] parameters, int[] slots, int[] widths, int base) {
        if (parameters == null) return;
        for (List<AnnotationNode> parameter : parameters) {
            if (parameter == null) continue;
            for (AnnotationNode annotation : parameter) {
                if (!isLocal(annotation)) continue;
                if (!namesAnActiveSlot(annotation, base)) continue;
                int index = (Integer) value(annotation, "index");
                setValue(annotation, "index", index + shiftFor(index, slots, widths));
            }
        }
    }

    private static int shiftFor(int slot, int[] slots, int[] widths) {
        int shift = 0;
        for (int i = 0; i < slots.length; i++) if (slots[i] <= slot) shift += widths[i];
        return shift;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static void setValue(AnnotationNode annotation, String name, Object newValue) {
        if (annotation.values == null) return;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, newValue);
                return;
            }
        }
    }
}
