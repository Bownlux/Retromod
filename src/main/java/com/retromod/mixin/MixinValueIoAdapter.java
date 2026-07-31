/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.3.0 track A / Phase 4 (#48): adapt a mixin {@code @Inject} save-data handler across the
 * {@code CompoundTag -> ValueOutput / ValueInput} refactor (1.21.5 / 26.x). A 1.21.1 mod injecting
 * into {@code Entity.addAdditionalSaveData}/{@code readAdditionalSaveData} (or the BlockEntity
 * {@code saveAdditional}/{@code loadAdditional}) captures a {@code CompoundTag}; on 26.x those
 * methods pass a {@code ValueOutput}/{@code ValueInput}, so Mixin rejects the handler with
 * {@code InvalidInjectionException: Invalid descriptor: expected ValueOutput, found CompoundTag}.
 *
 * <p>This is not a rename (the ValueIO API differs from CompoundTag), so the handler can't be
 * re-signatured like {@link MixinHandlerResignature}. Instead we keep the mod's handler body
 * <b>unchanged</b> and wrap it:
 * <ol>
 *   <li>rename the original {@code H(CompoundTag, CallbackInfo, ...)} to a private helper
 *       {@code H$retromod$vio(...)} (stripped of its injector annotation);</li>
 *   <li>synthesize a new {@code H(ValueOutput|ValueInput, CallbackInfo, ...)} carrying the original
 *       {@code @Inject}, which converts the ValueIO param to a real {@code CompoundTag} through
 *       {@link com.retromod.mixin.runtime.ValueIoBridge} and calls the helper.</li>
 * </ol>
 * Because the body runs against a genuine {@code CompoundTag} (the live backing tag on write, the
 * source tag on read), all of its logic (compounds/lists/UUID/contains/...) is preserved exactly:
 * no per-instruction rewrite that could silently corrupt saves.
 *
 * <p><b>Safety:</b> the caller re-emits with {@code COMPUTE_FRAMES}; if that fails, it strips the
 * identified handlers instead (via {@link #stripTargetsFrom}) - a soft-fail identical to the current
 * blocklist behavior, never leaving the broken original in place. Gated to 26.1+ (the only host
 * where these params are ValueIO). Declines conservatively (see {@link #collect}).
 */
public final class MixinValueIoAdapter {

    private MixinValueIoAdapter() {}

    static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    static final String VALUE_OUTPUT = "net/minecraft/world/level/storage/ValueOutput";
    static final String VALUE_INPUT = "net/minecraft/world/level/storage/ValueInput";
    static final String BRIDGE = "com/retromod/mixin/runtime/ValueIoBridge";

    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_INFO_RETURNABLE =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String HELPER_SUFFIX = "$retromod$vio";

    /** Save-data target (bare Mojang name) -> {@code true} for write (ValueOutput), {@code false} for read (ValueInput). */
    private static final Map<String, Boolean> SAVE_TARGETS = Map.of(
            "addAdditionalSaveData", Boolean.TRUE,     // Entity write
            "readAdditionalSaveData", Boolean.FALSE,   // Entity read
            "saveAdditional", Boolean.TRUE,            // BlockEntity write
            "loadAdditional", Boolean.FALSE);          // BlockEntity read

    /** An identified adaptable handler and its direction. */
    static final class Target {
        final MethodNode handler;
        final boolean write;
        final String originalName;
        Target(MethodNode handler, boolean write) {
            this.handler = handler;
            this.write = write;
            this.originalName = handler.name;
        }
    }

    /**
     * Identify {@code @Inject} handlers that capture a lone {@code CompoundTag} for a save-data
     * target. Declines unless the shape is exactly {@code (CompoundTag, CallbackInfo[, locals...])}
     * with no annotation on the CompoundTag param.
     */
    static List<Target> collect(ClassNode cn) {
        List<Target> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            AnnotationNode inject = annotationOf(m, INJECT_DESC);
            if (inject == null) continue;
            Set<Boolean> dirs = targetDirections(inject);
            // Decline a handler whose selectors span BOTH a write (ValueOutput) and a read
            // (ValueInput) save method: one CompoundTag param can't be re-typed to two different
            // ValueIO types, so adapting to either would leave the other target's injection with a
            // descriptor mismatch (InvalidInjectionException -> the exact #48 cascade). A conflicting
            // handler falls through to collectUnrepairable and is stripped (soft-fail) instead.
            if (dirs.size() != 1) continue;
            boolean write = dirs.iterator().next();
            if (Type.getReturnType(m.desc).getSort() != Type.VOID) continue;  // @Inject handlers are void
            Type[] args = Type.getArgumentTypes(m.desc);
            int cb = callbackIndex(args);
            if (cb != 1) continue;                                   // exactly one captured target param
            if (args[0].getSort() != Type.OBJECT
                    || !COMPOUND_TAG.equals(args[0].getInternalName())) continue;
            if (hasParamAnnotation(m, 0)) continue;                  // don't touch an annotated capture
            out.add(new Target(m, write));
        }
        return out;
    }

    /**
     * Repair-or-strip complement to {@link #collect}: {@code @Inject} handlers that target a
     * save-data method AND capture a {@code CompoundTag} somewhere but do not fit the strict
     * adaptable shape (extra captured params, an annotated capture, a non-void return). On a
     * 1.21.5+ host these are DEFINITIONALLY broken (the target passes ValueIO, not CompoundTag), so
     * Mixin rejects them with {@code InvalidInjectionException} and NeoForge cascades into the
     * "broken mod state" (#48). The caller strips them: the same soft-fail the curated blocklist
     * used to provide, now automatic, which is what lets those blocklist entries retire.
     *
     * <p>Returns the handler {@code MethodNode}s (not names) so the caller can strip them by identity
     * after {@link #apply} has synthesized the adapters, which reuse the adaptable handlers' names: a
     * name-based strip could otherwise delete a fresh adapter that happens to share a name with an
     * overloaded unrepairable handler.
     */
    static List<MethodNode> collectUnrepairable(ClassNode cn, List<Target> adaptable) {
        List<MethodNode> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            AnnotationNode inject = annotationOf(m, INJECT_DESC);
            if (inject == null) continue;
            if (targetDirections(inject).isEmpty()) continue;
            // Only a CompoundTag captured as a TARGET param (before the CallbackInfo trailer) is
            // stale on a ValueIO host; a CompoundTag @Local captured AFTER the CallbackInfo belongs
            // to a handler already written for the modern API and must not be stripped.
            Type[] args = Type.getArgumentTypes(m.desc);
            int cbLimit = callbackIndex(args);
            if (cbLimit < 0) cbLimit = args.length;
            boolean capturesTag = false;
            for (int i = 0; i < cbLimit; i++) {
                if (args[i].getSort() == Type.OBJECT && COMPOUND_TAG.equals(args[i].getInternalName())) {
                    capturesTag = true;
                    break;
                }
            }
            if (!capturesTag) continue;                              // captures nothing stale: fine as-is
            boolean isAdaptable = false;
            for (Target t : adaptable) {
                if (t.handler == m) { isAdaptable = true; break; }
            }
            if (!isAdaptable) out.add(m);
        }
        return out;
    }

    /**
     * Rename each target's body to a private helper and synthesize the ValueIO-param handler that
     * bridges to it. Mutates {@code cn}. Returns the number of handlers adapted.
     */
    static int apply(ClassNode cn, List<Target> targets) {
        List<MethodNode> synthesized = new ArrayList<>();
        int applied = 0;
        for (Target t : targets) {
            MethodNode h = t.handler;
            String vio = t.write ? VALUE_OUTPUT : VALUE_INPUT;
            String bridgeMethod = t.write ? "outputTag" : "inputTag";

            Type[] args = Type.getArgumentTypes(h.desc);
            Type ret = Type.getReturnType(h.desc);
            Type[] newArgs = args.clone();
            newArgs[0] = Type.getObjectType(vio);                    // CompoundTag -> ValueOutput/ValueInput
            String newDesc = Type.getMethodDescriptor(ret, newArgs);
            String helperName = t.originalName + HELPER_SUFFIX;
            String helperDesc = h.desc;                              // helper keeps the CompoundTag signature

            DetachedInject di = detachInject(h);                     // remove @Inject from the (soon-to-be) helper
            if (di == null) continue;                                // shouldn't happen (collect verified it)
            rewriteSelectorDesc(di.annotation, vio);                 // desc-qualified method= : CompoundTag -> vio

            boolean isStatic = (h.access & Opcodes.ACC_STATIC) != 0;
            List<AnnotationNode>[] visPA = h.visibleParameterAnnotations;
            List<AnnotationNode>[] invPA = h.invisibleParameterAnnotations;

            // Original method becomes the plain helper (body + CompoundTag desc unchanged).
            h.name = helperName;

            MethodNode nh = new MethodNode(Opcodes.ASM9, h.access, t.originalName, newDesc, null,
                    h.exceptions == null ? null : h.exceptions.toArray(new String[0]));
            if (di.visible) {
                if (nh.visibleAnnotations == null) nh.visibleAnnotations = new ArrayList<>();
                nh.visibleAnnotations.add(di.annotation);
            } else {
                if (nh.invisibleAnnotations == null) nh.invisibleAnnotations = new ArrayList<>();
                nh.invisibleAnnotations.add(di.annotation);
            }
            nh.visibleParameterAnnotations = visPA;                  // @Local etc. on forwarded locals stay aligned
            nh.invisibleParameterAnnotations = invPA;

            InsnList il = nh.instructions;
            int base = isStatic ? 0 : 1;
            if (!isStatic) il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, base));            // the ValueOutput/ValueInput param
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, bridgeMethod,
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, COMPOUND_TAG));
            int slot = base + 1;                                     // ValueIO param occupies one slot
            for (int i = 1; i < newArgs.length; i++) {
                il.add(new VarInsnNode(newArgs[i].getOpcode(Opcodes.ILOAD), slot));
                slot += newArgs[i].getSize();
            }
            il.add(new MethodInsnNode(invokeOpcode(h.access, isStatic), cn.name, helperName, helperDesc, false));
            il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));

            synthesized.add(nh);
            applied++;
        }
        cn.methods.addAll(synthesized);
        return applied;
    }

    /**
     * Ensure {@link com.retromod.mixin.runtime.ValueIoBridge} is registered as a synthetic so the
     * Forge/NeoForge per-mod embedder can relocate a JPMS-safe copy into any mod whose adapted
     * handler references it. Called from the adapter's own transform path (not from a loader entry
     * point), so it can never be forgotten on a loader: a Forge host would otherwise ship the
     * inserted {@code INVOKESTATIC ValueIoBridge} unembedded and crash with
     * {@code NoClassDefFoundError} at save/load (converting the intended soft-fail into a hard
     * crash). Idempotent; best-effort (on Fabric there is no embedder and the direct reference to
     * Retromod's own class resolves anyway).
     */
    static void ensureBridgeRegistered(com.retromod.core.RetromodTransformer transformer) {
        if (transformer == null || transformer.getSyntheticClasses().containsKey(BRIDGE)) return;
        try (java.io.InputStream in = MixinValueIoAdapter.class.getClassLoader()
                .getResourceAsStream(BRIDGE + ".class")) {
            if (in != null) transformer.registerSyntheticClass(BRIDGE, in.readAllBytes());
        } catch (Throwable ignored) {
            // best-effort: the resource is Retromod's own class, so a miss means a corrupt jar
        }
    }

    /**
     * Safe fallback: from the pre-adaptation class bytes, strip the identified handlers so their
     * broken injection never fires (a soft-fail, equivalent to the blocklist strip). Used only when
     * frame recomputation of the adapted class fails.
     */
    static byte[] stripTargetsFrom(byte[] annotationOnlyBytes, Set<String> nameDescKeys) {
        if (nameDescKeys.isEmpty()) return annotationOnlyBytes;
        ClassNode cn = new ClassNode();
        new ClassReader(annotationOnlyBytes).accept(cn, 0);
        // Match on name+descriptor, not name alone: an unrelated overloaded @Inject sharing a name
        // with a stripped save-data handler must not be collaterally deleted.
        cn.methods.removeIf(m -> nameDescKeys.contains(m.name + m.desc) && annotationOf(m, INJECT_DESC) != null);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ---- helpers ----

    private static int invokeOpcode(int access, boolean isStatic) {
        if (isStatic) return Opcodes.INVOKESTATIC;
        // A private instance helper is invoked with INVOKESPECIAL; a visible one could be overridden,
        // so use INVOKEVIRTUAL (mixin handlers are conventionally private, so INVOKESPECIAL is the norm).
        return (access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
    }

    /**
     * The distinct save-data directions ({@code TRUE} write / {@code FALSE} read) this
     * {@code @Inject}'s {@code method=} selectors resolve to. Empty if it targets no save-data
     * method; size 1 for a normal handler; size 2 for a (broken-on-ValueIO) handler shared across a
     * write and a read target.
     */
    private static Set<Boolean> targetDirections(AnnotationNode inject) {
        Set<Boolean> dirs = new HashSet<>();
        if (inject.values == null) return dirs;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"method".equals(inject.values.get(i))) continue;
            Object v = inject.values.get(i + 1);
            List<String> selectors = new ArrayList<>();
            if (v instanceof List<?> l) { for (Object o : l) if (o instanceof String s) selectors.add(s); }
            else if (v instanceof String s) selectors.add(s);
            for (String sel : selectors) {
                Boolean w = SAVE_TARGETS.get(MixinHandlerResignature.bareName(sel));
                if (w != null) dirs.add(w);
            }
        }
        return dirs;
    }

    private static int callbackIndex(Type[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].getSort() == Type.OBJECT) {
                String in = args[i].getInternalName();
                if (CALLBACK_INFO.equals(in) || CALLBACK_INFO_RETURNABLE.equals(in)) return i;
            }
        }
        return -1;
    }

    private static AnnotationNode annotationOf(MethodNode m, String desc) {
        for (List<AnnotationNode> anns : List.of(
                m.visibleAnnotations != null ? m.visibleAnnotations : List.<AnnotationNode>of(),
                m.invisibleAnnotations != null ? m.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) if (desc.equals(a.desc)) return a;
        }
        return null;
    }

    private static boolean hasParamAnnotation(MethodNode m, int index) {
        return nonEmptyAt(m.visibleParameterAnnotations, index) || nonEmptyAt(m.invisibleParameterAnnotations, index);
    }

    private static boolean nonEmptyAt(List<AnnotationNode>[] arr, int i) {
        return arr != null && i < arr.length && arr[i] != null && !arr[i].isEmpty();
    }

    /** An @Inject removed from a method, remembering which annotation list it came from. */
    private static final class DetachedInject {
        final AnnotationNode annotation;
        final boolean visible;
        DetachedInject(AnnotationNode annotation, boolean visible) {
            this.annotation = annotation;
            this.visible = visible;
        }
    }

    private static DetachedInject detachInject(MethodNode m) {
        if (m.visibleAnnotations != null) {
            for (int i = 0; i < m.visibleAnnotations.size(); i++) {
                if (INJECT_DESC.equals(m.visibleAnnotations.get(i).desc)) {
                    return new DetachedInject(m.visibleAnnotations.remove(i), true);
                }
            }
        }
        if (m.invisibleAnnotations != null) {
            for (int i = 0; i < m.invisibleAnnotations.size(); i++) {
                if (INJECT_DESC.equals(m.invisibleAnnotations.get(i).desc)) {
                    return new DetachedInject(m.invisibleAnnotations.remove(i), false);
                }
            }
        }
        return null;
    }

    /** Rewrite a desc-qualified {@code method=} selector's {@code CompoundTag} param to the ValueIO type. */
    private static void rewriteSelectorDesc(AnnotationNode inject, String vioInternalName) {
        if (inject.values == null) return;
        String from = "L" + COMPOUND_TAG + ";";
        String to = "L" + vioInternalName + ";";
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"method".equals(inject.values.get(i))) continue;
            Object v = inject.values.get(i + 1);
            if (v instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) v;
                for (int j = 0; j < l.size(); j++) {
                    if (l.get(j) instanceof String s && s.indexOf('(') >= 0 && s.contains(from)) {
                        l.set(j, s.replace(from, to));
                    }
                }
            } else if (v instanceof String s && s.indexOf('(') >= 0 && s.contains(from)) {
                inject.values.set(i + 1, s.replace(from, to));
            }
        }
    }
}
