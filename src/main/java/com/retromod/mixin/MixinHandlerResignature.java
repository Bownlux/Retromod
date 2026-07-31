/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repairs Mixin handlers when Minecraft adds a parameter that the handler does not need.
 *
 * <p>For example, Minecraft 1.21.5 changed
 * {@code LivingEntity.doHurtTarget(Entity)} to
 * {@code doHurtTarget(ServerLevel, Entity)}. An older handler still captures only the entity, so
 * Mixin no longer considers it a valid prefix. This class inserts the missing parameter and moves
 * the handler's local variable slots to match.
 *
 * <p>The repair is deliberately narrow. It only handles changes listed in
 * {@link #SIGNATURE_CHANGES}, skips parameters with Mixin annotations such as {@code @Local}, and
 * lets the caller recompute stack frames. If frame computation fails, the caller keeps the
 * original class bytes.
 *
 * <p>Bare method selectors need only the handler repair. Selectors that include a descriptor also
 * receive the new target descriptor.
 */
public final class MixinHandlerResignature {

    private MixinHandlerResignature() {}

    /** A parameter and its zero-based position in the method descriptor. */
    public record ParamInsert(int paramIndex, String typeDescriptor) {}

    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_INFO_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

    /**
     * A known signature change and the old first parameter types that identify it.
     * {@code null} leaves the first parameter unrestricted.
     */
    private record SigChange(Set<String> acceptableFirstParams, List<ParamInsert> inserts) {}

    /** Known changes indexed by the bare Mojang method name. */
    private static final Map<String, SigChange> SIGNATURE_CHANGES = new HashMap<>();
    static {
        // Minecraft 1.21.5 added ServerLevel to many entity methods. These entries came from a
        // comparison of the official 1.21.1 mappings and the 26.2 jar. Methods with no old
        // parameters do not need a repair because an empty capture remains valid.
        ParamInsert serverLevel = new ParamInsert(0, "Lnet/minecraft/server/level/ServerLevel;");
        String damageSource = "Lnet/minecraft/world/damagesource/DamageSource;";
        String entity = "Lnet/minecraft/world/entity/Entity;";
        String itemStack = "Lnet/minecraft/world/item/ItemStack;";

        reg("doHurtTarget", serverLevel, entity);                              // #69
        reg("actuallyHurt", serverLevel, damageSource);
        reg("isInvulnerableTo", serverLevel, damageSource);
        reg("dropExperience", serverLevel, entity);
        reg("dropFromLootTable", serverLevel, damageSource);
        reg("triggerOnDeathMobEffects", serverLevel,
                "Lnet/minecraft/world/entity/Entity$RemovalReason;");
        reg("spawnAtLocation", serverLevel, itemStack, "Lnet/minecraft/world/level/ItemLike;");
        reg("pickUpItem", serverLevel, "Lnet/minecraft/world/entity/item/ItemEntity;",
                "Lnet/minecraft/world/entity/Mob;", "Lnet/minecraft/world/entity/monster/piglin/Piglin;");
        reg("wantsToPickUp", serverLevel, itemStack);
        reg("equipItemIfPossible", serverLevel, itemStack);
        reg("dropPreservedEquipment", serverLevel, "Ljava/util/function/Predicate;");
        reg("isPreventingPlayerRest", serverLevel, "Lnet/minecraft/world/entity/player/Player;");
        reg("tickLeash", serverLevel, entity);
        reg("playerDied", serverLevel, "Lnet/minecraft/world/entity/player/Player;");
        reg("handleAirSupply", serverLevel); // The old first parameter is a primitive.
        reg("onStopAttacking", serverLevel, "Lnet/minecraft/world/entity/animal/axolotl/Axolotl;");
        reg("hurtAndThrowTarget", serverLevel, "Lnet/minecraft/world/entity/LivingEntity;");
        reg("checkWalls", serverLevel, "Lnet/minecraft/world/phys/AABB;");
        reg("onCrystalDestroyed", serverLevel, "Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;");
        reg("onDestroyedBy", serverLevel, damageSource);

        // These MobEffect hooks came from scripts/harvest-serverlevel-prepend.py. Generic AI
        // methods are intentionally excluded because matching them by name would be too broad.
        String livingEntity = "Lnet/minecraft/world/entity/LivingEntity;";
        reg("applyEffectTick", serverLevel, livingEntity);
        reg("onMobHurt", serverLevel, livingEntity);
        reg("onMobRemoved", serverLevel, livingEntity);

        // Minecraft 26.1 added a ResourceKey after the ninth captured parameter. Placing it before
        // CallbackInfo preserves the old body and rejects handlers that captured fewer parameters.
        SIGNATURE_CHANGES.put("tryGenerateStructure", new SigChange(
                Set.of("Lnet/minecraft/world/level/levelgen/structure/StructureSet$StructureSelectionEntry;"),
                List.of(new ParamInsert(9, "Lnet/minecraft/resources/ResourceKey;"))));
    }

    /** Registers a change and the old first parameter types that identify it. */
    private static void reg(String name, ParamInsert insert, String... acceptableFirstParams) {
        SIGNATURE_CHANGES.put(name, new SigChange(Set.of(acceptableFirstParams), List.of(insert)));
    }

    /**
     * Registers a change without restricting its old first parameter.
     * Tests and external callers use this form.
     */
    public static void register(String targetMethodName, ParamInsert... inserts) {
        SIGNATURE_CHANGES.put(targetMethodName, new SigChange(null, List.of(inserts)));
    }

    /**
     * The parameter insertions for the target of {@code method}'s {@code @Inject}, or {@code null}
     * if the method is not an {@code @Inject} or its target has no known signature change.
     */
    static List<ParamInsert> injectSignatureChange(MethodNode method) {
        AnnotationNode inject = annotationOf(method, INJECT_DESC);
        if (inject == null || inject.values == null) return null;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"method".equals(inject.values.get(i))) continue;
            Object v = inject.values.get(i + 1);
            List<String> targets = new ArrayList<>();
            if (v instanceof List<?> l) { for (Object o : l) if (o instanceof String s) targets.add(s); }
            else if (v instanceof String s) targets.add(s);
            for (String t : targets) {
                SigChange sc = SIGNATURE_CHANGES.get(bareName(t));
                if (sc == null) continue;
                // Owner guard: skip a bare-name match whose captured params show the handler is
                // targeting a same-named but UNCHANGED method on another class.
                if (sc.acceptableFirstParams() != null
                        && !firstCapturedParamMatches(method, sc.acceptableFirstParams())) {
                    continue;
                }
                return sc.inserts();
            }
        }
        return null;
    }

    /**
     * Checks the first captured parameter when it has a Mojang Minecraft type.
     * Other types do not provide enough information for a safe check.
     */
    private static boolean firstCapturedParamMatches(MethodNode method, Set<String> acceptable) {
        Type[] args = Type.getArgumentTypes(method.desc);
        int cb = callbackIndex(args);
        if (cb <= 0) return true;
        Type first = args[0];
        if (first.getSort() != Type.OBJECT) return true;
        String desc = first.getDescriptor();
        if (!isMojangMcType(desc)) return true;
        return acceptable.contains(desc);
    }

    /** Returns whether a descriptor uses a Mojang Minecraft class name. */
    private static boolean isMojangMcType(String descriptor) {
        return descriptor.startsWith("Lnet/minecraft/") && !descriptor.contains("/class_");
    }

    /**
     * Returns whether an {@code @Inject} handler captures a target parameter before its callback.
     */
    private static boolean injectHandlerCapturesParams(MethodNode method) {
        if (method.desc == null) return false;
        return callbackIndex(Type.getArgumentTypes(method.desc)) > 0;
    }

    /** Finds the callback parameter in a handler descriptor. */
    static int callbackIndex(Type[] args) {
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

    /** Extracts the bare method name from a Mixin selector. */
    static String bareName(String selector) {
        String s = selector;
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren);
        int semi = s.lastIndexOf(';');
        if (semi >= 0) s = s.substring(semi + 1);
        return s;
    }

    /**
     * Adds parameters to an {@code @Inject} handler and moves its local variable slots.
     * Returns {@code false} without changing unsupported handlers.
     */
    static boolean insertParams(MethodNode handler, List<ParamInsert> inserts) {
        if (inserts == null || inserts.isEmpty() || handler.desc == null) return false;

        Type[] args = Type.getArgumentTypes(handler.desc);
        Type ret = Type.getReturnType(handler.desc);

        int cbIndex = callbackIndex(args);
        if (cbIndex < 0) return false;

        for (ParamInsert ins : inserts) {
            if (ins.paramIndex() < 0 || ins.paramIndex() > cbIndex) return false;
        }
        // A handler with no captured target parameters is already a valid prefix. Adding a
        // parameter would change its meaning.
        if (cbIndex < 1) return false;

        // Parameter annotation arrays use descriptor positions. Moving parameters without moving
        // those arrays could attach @Local, @Coerce, or @Share to the wrong parameter.
        if (hasParamAnnotations(handler.visibleParameterAnnotations, Integer.MAX_VALUE - 1)
                || hasParamAnnotations(handler.invisibleParameterAnnotations, Integer.MAX_VALUE - 1)) {
            return false;
        }

        if (!insertRawParams(handler, inserts)) return false;

        // A descriptor-qualified selector must follow the handler to the new target signature.
        rewriteInjectSelectors(handler);
        return true;
    }

    /**
     * Performs an already validated insertion. The caller must recompute stack frames afterward.
     */
    static boolean insertRawParams(MethodNode handler, List<ParamInsert> inserts) {
        if (inserts == null || inserts.isEmpty() || handler.desc == null) return false;
        Type[] args = Type.getArgumentTypes(handler.desc);
        Type ret = Type.getReturnType(handler.desc);
        for (ParamInsert ins : inserts) {
            if (ins.paramIndex() < 0 || ins.paramIndex() > args.length) return false;
        }

        boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        int[] paramSlot = new int[args.length + 1];
        paramSlot[0] = isStatic ? 0 : 1;
        for (int i = 0; i < args.length; i++) paramSlot[i + 1] = paramSlot[i] + args[i].getSize();

        List<ParamInsert> sorted = new ArrayList<>(inserts);
        sorted.sort(Comparator.comparingInt(ParamInsert::paramIndex));
        int[] insSlot = new int[sorted.size()], insWidth = new int[sorted.size()];
        for (int k = 0; k < sorted.size(); k++) {
            insSlot[k] = paramSlot[sorted.get(k).paramIndex()];
            insWidth[k] = Type.getType(sorted.get(k).typeDescriptor()).getSize();
        }

        // Existing frames describe the old slot layout, so the caller must rebuild them.
        List<AbstractInsnNode> frames = new ArrayList<>();
        for (AbstractInsnNode insn = handler.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode v) v.var += shiftFor(v.var, insSlot, insWidth);
            else if (insn instanceof IincInsnNode ii) ii.var += shiftFor(ii.var, insSlot, insWidth);
            else if (insn instanceof FrameNode) frames.add(insn);
        }
        for (AbstractInsnNode f : frames) handler.instructions.remove(f);
        if (handler.localVariables != null) {
            for (LocalVariableNode lv : handler.localVariables) lv.index += shiftFor(lv.index, insSlot, insWidth);
        }

        // Insert from the end so each earlier index remains valid.
        List<Type> newArgs = new ArrayList<>(Arrays.asList(args));
        for (int k = sorted.size() - 1; k >= 0; k--) {
            newArgs.add(sorted.get(k).paramIndex(), Type.getType(sorted.get(k).typeDescriptor()));
        }
        handler.desc = Type.getMethodDescriptor(ret, newArgs.toArray(new Type[0]));

        // These optional attributes still describe the old parameter list.
        handler.parameters = null;
        handler.signature = null;
        int totalWidth = 0;
        for (int width : insWidth) {
            totalWidth += width;
        }
        handler.maxLocals += totalWidth;
        return true;
    }

    /** Updates descriptor-qualified {@code @Inject} selectors after a handler repair. */
    private static void rewriteInjectSelectors(MethodNode handler) {
        AnnotationNode inject = annotationOf(handler, INJECT_DESC);
        if (inject == null || inject.values == null) return;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"method".equals(inject.values.get(i))) continue;
            Object v = inject.values.get(i + 1);
            if (v instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) v;
                for (int j = 0; j < l.size(); j++) {
                    if (l.get(j) instanceof String s) {
                        String r = rewriteSelectorDescriptor(s);
                        if (r != null) l.set(j, r);
                    }
                }
            } else if (v instanceof String s) {
                String r = rewriteSelectorDescriptor(s);
                if (r != null) inject.values.set(i + 1, r);
            }
        }
    }

    /**
     * Updates a selector for a known signature change.
     * Returns {@code null} when the selector cannot be identified safely.
     */
    static String rewriteSelectorDescriptor(String selector) {
        int paren = selector.indexOf('(');
        if (paren < 0) return null;
        SigChange sc = SIGNATURE_CHANGES.get(bareName(selector));
        if (sc == null) return null;
        List<ParamInsert> inserts = sc.inserts();
        String head = selector.substring(0, paren);
        // The table describes Minecraft methods. A mod can use the same method name and old
        // descriptor for its own class, but that method must not be rewritten.
        int semi = head.lastIndexOf(';');
        if (semi >= 0) {
            String owner = head.substring(0, semi);
            if (!owner.startsWith("Lnet/minecraft/")) return null;
        }
        String methodDesc = selector.substring(paren);
        Type[] args;
        Type ret;
        try {
            args = Type.getArgumentTypes(methodDesc);
            ret = Type.getReturnType(methodDesc);
        } catch (RuntimeException e) {
            return null;
        }
        // A parameter already present at an insertion point means this selector is up to date.
        for (ParamInsert ins : inserts) {
            int idx = ins.paramIndex();
            if (idx < args.length && args[idx].getDescriptor().equals(ins.typeDescriptor())) {
                return null;
            }
        }
        // The old first parameter separates this method from unrelated methods with the same name.
        Set<String> acceptable = sc.acceptableFirstParams();
        if (acceptable != null && !acceptable.isEmpty()) {
            if (args.length == 0 || !acceptable.contains(args[0].getDescriptor())) {
                return null;
            }
        }
        List<Type> newArgs = new ArrayList<>(Arrays.asList(args));
        List<ParamInsert> sorted = new ArrayList<>(inserts);
        sorted.sort(Comparator.comparingInt(ParamInsert::paramIndex));
        for (int k = sorted.size() - 1; k >= 0; k--) {
            int idx = sorted.get(k).paramIndex();
            if (idx < 0 || idx > newArgs.size()) return null;
            newArgs.add(idx, Type.getType(sorted.get(k).typeDescriptor()));
        }
        return head + Type.getMethodDescriptor(ret, newArgs.toArray(new Type[0]));
    }

    /**
     * Injectors whose handlers do not mirror the {@code @At} call parameters.
     * Their targets can be updated without changing the handler descriptor.
     */
    private static final Set<String> AT_DRIFT_SAFE = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

    /**
     * Repairs known selector changes in an injector annotation and its nested injection points.
     */
    public static boolean rewriteAnnotationDrift(MethodNode method) {
        boolean modified = false;
        for (List<AnnotationNode> anns : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) {
                if (a.desc != null && (a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                        || a.desc.startsWith("Lcom/llamalad7/mixinextras/"))) {
                    // A parameter-capturing @Inject is updated by insertParams. Updating it here
                    // could leave a new selector on a handler that insertParams later rejects.
                    boolean skipTopLevel = INJECT_DESC.equals(a.desc) && injectHandlerCapturesParams(method);
                    modified |= driftWalk(a, AT_DRIFT_SAFE.contains(a.desc), false, skipTopLevel);
                }
            }
        }
        return modified;
    }

    /** Walks an injector annotation and updates selectors that are safe for its handler shape. */
    private static boolean driftWalk(AnnotationNode a, boolean allowAtRewrite, boolean insideAt,
                                     boolean skipTopLevelSelector) {
        if (a.values == null) return false;
        boolean modified = false;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            String key = (String) a.values.get(i);
            Object v = a.values.get(i + 1);
            // A nested @At target describes a call. A top-level target describes the method that
            // contains the injection point.
            boolean selectorKey = ("method".equals(key) || "target".equals(key))
                    && (insideAt ? allowAtRewrite : !skipTopLevelSelector);
            if (selectorKey && v instanceof String s) {
                String r = rewriteSelectorDescriptor(s);
                if (r != null) { a.values.set(i + 1, r); modified = true; }
            } else if (v instanceof List<?> l) {
                List<Object> list = castList(l);
                for (int j = 0; j < list.size(); j++) {
                    Object o = list.get(j);
                    if (selectorKey && o instanceof String s) {
                        String r = rewriteSelectorDescriptor(s);
                        if (r != null) { list.set(j, r); modified = true; }
                    } else if (o instanceof AnnotationNode nested) {
                        modified |= driftWalk(nested, allowAtRewrite, insideAt || isAtNode(nested), skipTopLevelSelector);
                    }
                }
            } else if (v instanceof AnnotationNode nested) {
                modified |= driftWalk(nested, allowAtRewrite, insideAt || isAtNode(nested), skipTopLevelSelector);
            }
        }
        return modified;
    }

    private static boolean isAtNode(AnnotationNode a) {
        return "Lorg/spongepowered/asm/mixin/injection/At;".equals(a.desc);
    }

    /** Injectors whose handlers mirror the target call parameters. */
    private static final Set<String> CALL_MIRRORING = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

    /**
     * A repair found during a read-only scan.
     * The caller applies it while rebuilding frames and keeps the original bytes on failure.
     */
    interface DriftRepair {
        boolean apply();
    }

    /** A target and handler repair for {@code @Redirect} or {@code @WrapOperation}. */
    record RedirectDrift(MethodNode handler, AnnotationNode at, int valueIndex,
                         String newTarget, List<ParamInsert> handlerInserts) implements DriftRepair {

        /** Applies the handler change before exposing the new target selector. */
        @Override
        public boolean apply() {
            if (!insertRawParams(handler, handlerInserts)) return false;
            at.values.set(valueIndex, newTarget);
            return true;
        }
    }

    /**
     * An {@code @Overwrite} repair for a Minecraft method whose descriptor changed.
     * The inserted parameter remains unused by the old method body.
     */
    record OverwriteDrift(MethodNode method, List<ParamInsert> inserts) implements DriftRepair {
        @Override
        public boolean apply() {
            return insertRawParams(method, inserts);
        }
    }

    private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";

    /** Finds an {@code @Overwrite} that still has a known old descriptor. */
    static List<DriftRepair> detectOverwriteDrift(MethodNode method) {
        AnnotationNode ow = annotationOf(method, OVERWRITE_DESC);
        if (ow == null) return List.of();
        SigChange sc = SIGNATURE_CHANGES.get(method.name);
        if (sc == null) return List.of();
        Set<String> acceptable = sc.acceptableFirstParams();
        if (acceptable == null || acceptable.isEmpty()) return List.of();
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length == 0 || !acceptable.contains(args[0].getDescriptor())) return List.of();
        for (ParamInsert ins : sc.inserts()) {
            int idx = ins.paramIndex();
            if (idx < args.length && args[idx].getDescriptor().equals(ins.typeDescriptor())) return List.of();
        }
        if (hasParamAnnotations(method.visibleParameterAnnotations, Integer.MAX_VALUE - 1)
                || hasParamAnnotations(method.invisibleParameterAnnotations, Integer.MAX_VALUE - 1)) {
            return List.of();
        }
        return List.of(new OverwriteDrift(method, sc.inserts()));
    }

    /**
     * Finds {@code @Redirect} and {@code @WrapOperation} call sites with a known old descriptor.
     * Ambiguous handler shapes and annotated parameters are left alone.
     */
    static List<RedirectDrift> detectRedirectDrift(MethodNode method) {
        List<RedirectDrift> out = new ArrayList<>();
        for (List<AnnotationNode> anns : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) {
                if (!CALL_MIRRORING.contains(a.desc) || a.values == null) continue;
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    if (!"at".equals(a.values.get(i))) continue;
                    Object v = a.values.get(i + 1);
                    List<AnnotationNode> ats = new ArrayList<>();
                    if (v instanceof AnnotationNode one) ats.add(one);
                    else if (v instanceof List<?> l) { for (Object o : l) if (o instanceof AnnotationNode n) ats.add(n); }
                    // These injectors normally have one @At. Stopping after the first match also
                    // prevents an unusual annotation from inserting the same parameter twice.
                    for (AnnotationNode at : ats) {
                        RedirectDrift d = detectOneAt(method, at);
                        if (d != null) { out.add(d); break; }
                    }
                }
            }
        }
        return out;
    }

    private static RedirectDrift detectOneAt(MethodNode handler, AnnotationNode at) {
        if (at.values == null) return null;
        for (int j = 0; j + 1 < at.values.size(); j += 2) {
            if (!"target".equals(at.values.get(j)) || !(at.values.get(j + 1) instanceof String target)) continue;
            String newTarget = rewriteSelectorDescriptor(target);
            if (newTarget == null) return null;
            SigChange sc = SIGNATURE_CHANGES.get(bareName(target));
            if (sc == null) return null;
            // A virtual handler starts with the receiver. A static handler starts with call args.
            int paren = target.indexOf('(');
            String head = target.substring(0, paren);
            int semi = head.lastIndexOf(';');
            String ownerDesc = semi >= 0 ? head.substring(0, semi + 1) : null;
            Type[] oldArgs;
            try {
                oldArgs = Type.getArgumentTypes(target.substring(paren));
            } catch (RuntimeException e) {
                return null;
            }
            Type[] hArgs = Type.getArgumentTypes(handler.desc);
            int receiverOffset;
            if (ownerDesc != null && hArgs.length >= 1 + oldArgs.length
                    && hArgs[0].getDescriptor().equals(ownerDesc)
                    && argsMatch(hArgs, 1, oldArgs)) {
                receiverOffset = 1;
            } else if (hArgs.length >= oldArgs.length && argsMatch(hArgs, 0, oldArgs)) {
                receiverOffset = 0;
            } else {
                return null;
            }
            if (hasParamAnnotations(handler.visibleParameterAnnotations, Integer.MAX_VALUE - 1)
                    || hasParamAnnotations(handler.invisibleParameterAnnotations, Integer.MAX_VALUE - 1)) {
                return null;
            }
            List<ParamInsert> shifted = new ArrayList<>();
            for (ParamInsert ins : sc.inserts()) {
                shifted.add(new ParamInsert(ins.paramIndex() + receiverOffset, ins.typeDescriptor()));
            }
            return new RedirectDrift(handler, at, j + 1, newTarget, shifted);
        }
        return null;
    }

    private static boolean argsMatch(Type[] handlerArgs, int offset, Type[] callArgs) {
        for (int i = 0; i < callArgs.length; i++) {
            if (!handlerArgs[offset + i].getDescriptor().equals(callArgs[i].getDescriptor())) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(List<?> l) {
        return (List<Object>) l;
    }

    private static boolean hasParamAnnotations(List<AnnotationNode>[] arr, int uptoInclusive) {
        if (arr == null) return false;
        for (int i = 0; i <= uptoInclusive && i < arr.length; i++) if (arr[i] != null && !arr[i].isEmpty()) return true;
        return false;
    }

    /** Counts how many local variable slots an insertion moves this slot by. */
    private static int shiftFor(int slot, int[] insSlot, int[] insWidth) {
        int sh = 0;
        for (int k = 0; k < insSlot.length; k++) if (insSlot[k] <= slot) sh += insWidth[k];
        return sh;
    }
}
