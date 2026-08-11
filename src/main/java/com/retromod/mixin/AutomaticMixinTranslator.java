/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Repairs Mixin targets from exact facts in the current Minecraft JAR.
 *
 * <p>This is deliberately not a fuzzy symbol guesser. A target is changed only when its owner and
 * name identify one current declaration and the descriptor change has a handler-safe shape. The
 * first supported shape is parameter addition: the old arguments must remain an ordered
 * subsequence, the return type must stay unchanged, and no competing overload may fit. A
 * zero-capture {@code @Inject} at HEAD, TAIL, or RETURN may also follow a unique same-name method
 * through a wider parameter refactor because its handler never observes those parameters.
 *
 * <p>Namespace conversion and registered redirects run before this class. This pass therefore sees
 * final Mojang names and can check its answer against the host JAR instead of maintaining another
 * per-version method list.
 */
public final class AutomaticMixinTranslator {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin-auto");

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String MODIFY_RETURN =
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String MODIFY_EXPRESSION =
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
    private static final String WRAP_OPERATION =
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String CALLBACK_INFO =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_INFO_RETURNABLE =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

    /** A small cap keeps accidental long-descriptor subsequence matches out of auto-apply. */
    private static final int MAX_INSERTED_PARAMETERS = 3;

    private static final Set<String> SELECTOR_ONLY_AT_INJECTORS = Set.of(
            INJECT, MODIFY_RETURN, MODIFY_EXPRESSION);
    private static final Set<String> CALL_MIRRORING_INJECTORS = Set.of(
            REDIRECT, WRAP_OPERATION);
    private static final Set<String> ZERO_CAPTURE_POINTS = Set.of("HEAD", "TAIL", "RETURN");

    private final FuzzyMethodResolver targetMethods;

    public AutomaticMixinTranslator(FuzzyMethodResolver targetMethods) {
        this.targetMethods = targetMethods;
    }

    /** Whether the target Minecraft method index is ready for exact decisions. */
    public boolean isAvailable() {
        return targetMethods != null && targetMethods.isIndexed();
    }

    /**
     * Repairs one owner-qualified method selector stored outside the Mixin class, such as a
     * refmap value. Resource selectors have no handler metadata, so this only follows the safest
     * shape: one unique current method with the same name and return type whose parameters are the
     * old parameters plus at most three insertions.
     */
    public String translateResourceSelector(String selectorText) {
        if (!isAvailable() || selectorText == null) return selectorText;
        ParsedSelector selector = ParsedSelector.parse(selectorText);
        if (selector == null || selector.owner() == null) return selectorText;

        MethodChange added = resolveAddedParameters(
                selector.owner(), selector.name(), selector.descriptor(),
                targetMethods.getMethodsInHierarchy(selector.owner()));
        return added == null
                ? selectorText
                : selector.withMethod(added.name(), added.descriptor());
    }

    /**
     * Applies every safe automatic repair in one Mixin class.
     *
     * <p>Selector-only changes form the fallback. Coupled handler changes are published only after
     * all insertions succeed and ASM can rebuild the class frames.
     */
    public byte[] translate(byte[] classBytes) {
        if (!isAvailable() || classBytes == null) return classBytes;

        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        if (mixinRemapDisabled(classNode)) return classBytes;
        Set<String> mixinOwners = mixinOwners(classNode);
        if (mixinOwners.isEmpty()) return classBytes;

        boolean selectorChanged = false;
        int selectorRepairs = 0;
        List<PendingRepair> pending = new ArrayList<>();

        for (MethodNode method : classNode.methods) {
            MethodScan scan = scanMethod(method, mixinOwners);
            selectorChanged |= scan.selectorChanged();
            selectorRepairs += scan.selectorRepairs();
            // One handler cannot safely accept two independently inferred layouts.
            if (scan.pending().size() == 1) {
                pending.add(scan.pending().get(0));
            } else if (scan.pending().size() > 1) {
                LOGGER.debug("Declined automatic Mixin repair for {}.{}{}: multiple handler layouts",
                        classNode.name, method.name, method.desc);
            }
        }

        if (!selectorChanged && pending.isEmpty()) return classBytes;

        ClassWriter fallbackWriter = new ClassWriter(0);
        classNode.accept(fallbackWriter);
        byte[] selectorOnlyFallback = fallbackWriter.toByteArray();
        if (pending.isEmpty()) {
            LOGGER.info("Automatically translated {} Mixin selector(s) in {}",
                    selectorRepairs, classNode.name);
            return selectorOnlyFallback;
        }

        try {
            int handlerRepairs = 0;
            for (PendingRepair repair : pending) {
                if (!repair.apply()) {
                    LOGGER.debug("Declined coupled Mixin handler repair in {}", classNode.name);
                    return selectorOnlyFallback;
                }
                handlerRepairs++;
            }
            ClassWriter writer = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            classNode.accept(writer);
            LOGGER.info("Automatically translated {} Mixin selector(s) and {} handler(s) in {}",
                    selectorRepairs, handlerRepairs, classNode.name);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Could not verify automatic Mixin repair in {} ({}). "
                    + "Keeping selector-only changes.", classNode.name, t.toString());
            return selectorOnlyFallback;
        }
    }

    private MethodScan scanMethod(MethodNode method, Set<String> mixinOwners) {
        MutableScan scan = new MutableScan();
        for (List<AnnotationNode> annotations : annotationLists(method)) {
            for (AnnotationNode annotation : annotations) {
                if (annotation == null || annotation.desc == null || remapDisabled(annotation)) {
                    continue;
                }
                if (isInjector(annotation.desc)) {
                    scanInjector(method, annotation, mixinOwners, scan);
                } else if (OVERWRITE.equals(annotation.desc)) {
                    PendingRepair repair = overwriteRepair(method, mixinOwners);
                    if (repair != null) scan.pending.add(repair);
                } else if (INVOKER.equals(annotation.desc)) {
                    if (repairInvoker(method, annotation, mixinOwners)) {
                        scan.selectorChanged = true;
                        scan.selectorRepairs++;
                    }
                }
            }
        }
        return new MethodScan(scan.selectorChanged, scan.selectorRepairs, List.copyOf(scan.pending));
    }

    private void scanInjector(MethodNode handler, AnnotationNode injector,
            Set<String> mixinOwners, MutableScan scan) {
        if (injector.values == null) return;

        for (int i = 0; i + 1 < injector.values.size(); i += 2) {
            if (!"method".equals(injector.values.get(i))) continue;
            Object value = injector.values.get(i + 1);
            if (value instanceof String selector) {
                SelectorDecision decision = outerSelectorDecision(
                        handler, injector, mixinOwners, selector);
                applyDecision(handler, injector.values, i + 1, decision, scan);
            } else if (value instanceof List<?> selectors) {
                @SuppressWarnings("unchecked")
                List<Object> mutable = (List<Object>) selectors;
                for (int j = 0; j < mutable.size(); j++) {
                    if (!(mutable.get(j) instanceof String selector)) continue;
                    SelectorDecision decision = outerSelectorDecision(
                            handler, injector, mixinOwners, selector);
                    applyDecision(handler, mutable, j, decision, scan);
                }
            }
        }

        for (AnnotationNode at : collectAtAnnotations(injector)) {
            if (at.values == null || remapDisabled(at)) continue;
            for (int i = 0; i + 1 < at.values.size(); i += 2) {
                if (!"target".equals(at.values.get(i))
                        || !(at.values.get(i + 1) instanceof String selector)) {
                    continue;
                }
                SelectorDecision decision = atSelectorDecision(handler, injector.desc, selector);
                applyDecision(handler, at.values, i + 1, decision, scan);
            }
        }
    }

    private SelectorDecision outerSelectorDecision(MethodNode handler, AnnotationNode injector,
            Set<String> mixinOwners, String selectorText) {
        ParsedSelector selector = ParsedSelector.parse(selectorText);
        String owner = selector != null && selector.owner() != null
                ? selector.owner() : soleOwner(mixinOwners);
        if (owner == null) return null;

        if (selector == null) {
            if (INJECT.equals(injector.desc) && isBareMethodName(selectorText)) {
                List<MixinHandlerResignature.ParamInsert> inserts =
                        inferBareInjectInserts(owner, selectorText, handler);
                if (inserts != null && !inserts.isEmpty()) {
                    return new SelectorDecision(null, inserts);
                }
            }
            return null;
        }

        // Some older Mixin versions accepted a nonempty prefix of the target arguments. Current
        // Mixin requires every target argument once a handler captures any of them. The selector
        // itself is already exact here, so the indexed host method proves both the missing suffix
        // and its order. A callback-only handler remains the supported zero-capture form.
        if (INJECT.equals(injector.desc)
                && targetMethods.hasMethod(owner, selector.name(), selector.descriptor())) {
            List<MixinHandlerResignature.ParamInsert> inserts =
                    exactTargetTrailingInserts(handler, selector.descriptor());
            if (inserts != null && !inserts.isEmpty()) {
                return new SelectorDecision(null, inserts);
            }
        }

        MethodChange added = resolveAddedParameters(owner, selector.name(), selector.descriptor(),
                targetMethods.getDeclaredMethods(owner));
        if (added != null) {
            String replacement = selector.withMethod(added.name(), added.descriptor());
            if (INJECT.equals(injector.desc)) {
                List<MixinHandlerResignature.ParamInsert> handlerInserts =
                        injectHandlerInserts(handler, selector.descriptor(), added);
                if (handlerInserts == null) return null;
                return new SelectorDecision(replacement, handlerInserts);
            }
            if (MODIFY_RETURN.equals(injector.desc) || MODIFY_EXPRESSION.equals(injector.desc)) {
                return new SelectorDecision(replacement, List.of());
            }
            return null;
        }

        // A zero-capture callback does not depend on the target parameters. Restrict arbitrary
        // descriptor drift to injection points whose position does not depend on a call target,
        // local layout, slice, or ordinal.
        if (INJECT.equals(injector.desc)
                && zeroCaptureInjectIsIndependent(handler, injector, selector.descriptor())) {
            MethodChange replacement = resolveUniqueSameNameDescriptor(
                    owner, selector.name(), selector.descriptor());
            if (replacement != null) {
                return new SelectorDecision(
                        selector.withMethod(replacement.name(), replacement.descriptor()), List.of());
            }
        }
        return null;
    }

    private SelectorDecision atSelectorDecision(MethodNode handler, String injectorDesc,
            String selectorText) {
        ParsedSelector selector = ParsedSelector.parse(selectorText);
        if (selector == null || selector.owner() == null) return null;

        MethodChange added = resolveAddedParameters(
                selector.owner(), selector.name(), selector.descriptor(),
                targetMethods.getMethodsInHierarchy(selector.owner()));
        if (added == null) return null;
        String replacement = selector.withMethod(added.name(), added.descriptor());

        if (SELECTOR_ONLY_AT_INJECTORS.contains(injectorDesc)) {
            return new SelectorDecision(replacement, List.of());
        }
        if (!CALL_MIRRORING_INJECTORS.contains(injectorDesc)) return null;

        List<MixinHandlerResignature.ParamInsert> shifted =
                callMirroringHandlerInserts(handler, selector, added.inserts());
        return shifted == null ? null : new SelectorDecision(replacement, shifted);
    }

    private PendingRepair overwriteRepair(MethodNode method, Set<String> mixinOwners) {
        String owner = soleOwner(mixinOwners);
        if (owner == null || method.name.startsWith("<")) return null;
        MethodChange change = resolveAddedParameters(owner, method.name, method.desc,
                targetMethods.getDeclaredMethods(owner));
        if (change == null || staticness(method.access) != staticness(change.access())) return null;
        if (MixinHandlerResignature.hasUnsafeParamAnnotations(method)) return null;
        return new HandlerOnlyRepair(method, change.inserts());
    }

    /**
     * Repairs an explicit or convention-named invoker when one declared target keeps the exact
     * descriptor and a meaningful name token. This covers moves such as findSlot to getHoveredSlot
     * without treating every unique ()V method as a rename.
     */
    private boolean repairInvoker(MethodNode method, AnnotationNode invoker, Set<String> mixinOwners) {
        String owner = soleOwner(mixinOwners);
        if (owner == null) return false;
        String oldName = annotationString(invoker, "value");
        if (oldName == null || oldName.isBlank()) oldName = inferredInvokerName(method.name);
        if (oldName == null || oldName.startsWith("<")
                || targetMethods.hasMethod(owner, oldName, method.desc)) {
            return false;
        }

        List<FuzzyMethodResolver.MethodInfo> matches = new ArrayList<>();
        for (FuzzyMethodResolver.MethodInfo candidate : targetMethods.getDeclaredMethods(owner)) {
            if (!candidate.descriptor().equals(method.desc)) continue;
            if (staticness(candidate.access()) != staticness(method.access)) continue;
            if (!sharesSemanticToken(oldName, candidate.name())) continue;
            matches.add(candidate);
        }
        if (matches.size() != 1) return false;
        putAnnotationString(invoker, "value", matches.get(0).name());
        return true;
    }

    private MethodChange resolveAddedParameters(String owner, String name, String oldDesc,
            List<FuzzyMethodResolver.MethodInfo> candidates) {
        if (targetMethods.hasMethod(owner, name, oldDesc)) return null;
        List<MethodChange> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FuzzyMethodResolver.MethodInfo candidate : candidates) {
            if (!candidate.name().equals(name)) continue;
            String key = candidate.name() + candidate.descriptor();
            if (!seen.add(key)) continue;
            List<MixinHandlerResignature.ParamInsert> inserts =
                    inferPureInsertions(oldDesc, candidate.descriptor());
            if (inserts == null || inserts.isEmpty()
                    || inserts.size() > MAX_INSERTED_PARAMETERS) {
                continue;
            }
            matches.add(new MethodChange(candidate.name(), candidate.descriptor(),
                    candidate.access(), inserts));
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private MethodChange resolveUniqueSameNameDescriptor(String owner, String name, String oldDesc) {
        if (targetMethods.hasMethod(owner, name, oldDesc)) return null;
        String oldReturn = returnDescriptor(oldDesc);
        if (oldReturn == null) return null;
        Map<String, FuzzyMethodResolver.MethodInfo> unique = new LinkedHashMap<>();
        for (FuzzyMethodResolver.MethodInfo candidate : targetMethods.getDeclaredMethods(owner)) {
            if (!candidate.name().equals(name)
                    || !oldReturn.equals(returnDescriptor(candidate.descriptor()))) {
                continue;
            }
            unique.putIfAbsent(candidate.name() + candidate.descriptor(), candidate);
        }
        if (unique.size() != 1) return null;
        FuzzyMethodResolver.MethodInfo candidate = unique.values().iterator().next();
        return new MethodChange(candidate.name(), candidate.descriptor(),
                candidate.access(), List.of());
    }

    private List<MixinHandlerResignature.ParamInsert> injectHandlerInserts(
            MethodNode handler, String oldTargetDesc, MethodChange change) {
        Type[] handlerArgs = safeArgumentTypes(handler.desc);
        Type[] oldArgs = safeArgumentTypes(oldTargetDesc);
        if (handlerArgs == null || oldArgs == null) return null;
        int callback = MixinHandlerResignature.callbackIndex(handlerArgs);
        if (callback < 0 || callback != handlerArgs.length - 1) return null;
        if (!callbackMatchesReturn(handlerArgs[callback], returnDescriptor(change.descriptor()))) {
            return null;
        }
        if (callback == 0) return List.of();
        if (callback != oldArgs.length) return null;
        for (int i = 0; i < callback; i++) {
            if (!handlerArgs[i].equals(oldArgs[i])) return null;
        }
        if (MixinHandlerResignature.hasUnsafeParamAnnotations(handler)) return null;
        return change.inserts();
    }

    /**
     * Completes a handler that captures a nonempty prefix of an exact target's parameters.
     * Missing middle parameters are not inferred here because skipping one can change the meaning
     * of every later local. Only a trailing suffix has the same behavior as the old handler body.
     */
    private List<MixinHandlerResignature.ParamInsert> exactTargetTrailingInserts(
            MethodNode handler, String targetDesc) {
        Type[] handlerArgs = safeArgumentTypes(handler.desc);
        Type[] targetArgs = safeArgumentTypes(targetDesc);
        if (handlerArgs == null || targetArgs == null) return null;
        int callback = MixinHandlerResignature.callbackIndex(handlerArgs);
        if (callback <= 0 || callback != handlerArgs.length - 1
                || callback >= targetArgs.length) {
            return null;
        }
        if (!callbackMatchesReturn(handlerArgs[callback], returnDescriptor(targetDesc))
                || MixinHandlerResignature.hasUnsafeParamAnnotations(handler)) {
            return null;
        }
        for (int i = 0; i < callback; i++) {
            if (!handlerArgs[i].equals(targetArgs[i])) return null;
        }

        int missing = targetArgs.length - callback;
        if (missing > MAX_INSERTED_PARAMETERS) return null;
        List<MixinHandlerResignature.ParamInsert> inserts = new ArrayList<>(missing);
        for (int i = callback; i < targetArgs.length; i++) {
            inserts.add(new MixinHandlerResignature.ParamInsert(
                    callback, targetArgs[i].getDescriptor()));
        }
        return inserts;
    }

    private List<MixinHandlerResignature.ParamInsert> inferBareInjectInserts(
            String owner, String name, MethodNode handler) {
        Type[] handlerArgs = safeArgumentTypes(handler.desc);
        if (handlerArgs == null) return null;
        int callback = MixinHandlerResignature.callbackIndex(handlerArgs);
        if (callback <= 0 || callback != handlerArgs.length - 1
                || MixinHandlerResignature.hasUnsafeParamAnnotations(handler)) {
            return null;
        }
        List<Type> captured = Arrays.asList(Arrays.copyOf(handlerArgs, callback));
        List<List<MixinHandlerResignature.ParamInsert>> matches = new ArrayList<>();
        boolean alreadyValid = false;
        Set<String> seen = new HashSet<>();
        for (FuzzyMethodResolver.MethodInfo candidate : targetMethods.getDeclaredMethods(owner)) {
            if (!candidate.name().equals(name)
                    || !callbackMatchesReturn(handlerArgs[callback],
                            returnDescriptor(candidate.descriptor()))) {
                continue;
            }
            if (!seen.add(candidate.name() + candidate.descriptor())) continue;
            Type[] targetArgs = safeArgumentTypes(candidate.descriptor());
            if (targetArgs == null) continue;
            if (captured.equals(Arrays.asList(targetArgs))) {
                alreadyValid = true;
                continue;
            }
            List<MixinHandlerResignature.ParamInsert> inserts =
                    inferCompleteInsertions(captured, Arrays.asList(targetArgs));
            if (inserts != null && !inserts.isEmpty()
                    && inserts.size() <= MAX_INSERTED_PARAMETERS) {
                matches.add(inserts);
            }
        }
        return !alreadyValid && matches.size() == 1 ? matches.get(0) : null;
    }

    private List<MixinHandlerResignature.ParamInsert> callMirroringHandlerInserts(
            MethodNode handler, ParsedSelector selector,
            List<MixinHandlerResignature.ParamInsert> targetInserts) {
        if (MixinHandlerResignature.hasUnsafeParamAnnotations(handler)) return null;
        Type[] handlerArgs = safeArgumentTypes(handler.desc);
        Type[] oldArgs = safeArgumentTypes(selector.descriptor());
        if (handlerArgs == null || oldArgs == null) return null;

        int receiverOffset;
        Type ownerType = Type.getObjectType(selector.owner());
        if (handlerArgs.length >= oldArgs.length + 1
                && handlerArgs[0].equals(ownerType)
                && argsMatch(handlerArgs, 1, oldArgs)) {
            receiverOffset = 1;
        } else if (handlerArgs.length >= oldArgs.length && argsMatch(handlerArgs, 0, oldArgs)) {
            receiverOffset = 0;
        } else {
            return null;
        }

        List<MixinHandlerResignature.ParamInsert> shifted = new ArrayList<>();
        for (MixinHandlerResignature.ParamInsert insert : targetInserts) {
            shifted.add(new MixinHandlerResignature.ParamInsert(
                    insert.paramIndex() + receiverOffset, insert.typeDescriptor()));
        }
        return shifted;
    }

    private boolean zeroCaptureInjectIsIndependent(
            MethodNode handler, AnnotationNode inject, String oldTargetDesc) {
        Type[] args = safeArgumentTypes(handler.desc);
        if (args == null || MixinHandlerResignature.callbackIndex(args) != 0 || args.length != 1) {
            return false;
        }
        if (!callbackMatchesReturn(args[0], returnDescriptor(oldTargetDesc))) return false;
        if (annotationValue(inject, "slice") != null || annotationValue(inject, "locals") != null) {
            return false;
        }
        List<AnnotationNode> ats = collectAtAnnotations(inject);
        if (ats.isEmpty()) return false;
        for (AnnotationNode at : ats) {
            String value = annotationString(at, "value");
            if (value == null || !ZERO_CAPTURE_POINTS.contains(value.toUpperCase(Locale.ROOT))) {
                return false;
            }
            if (annotationValue(at, "target") != null || annotationValue(at, "ordinal") != null) {
                return false;
            }
        }
        return true;
    }

    private static List<MixinHandlerResignature.ParamInsert> inferPureInsertions(
            String oldDesc, String newDesc) {
        Type[] oldArgs = safeArgumentTypes(oldDesc);
        Type[] newArgs = safeArgumentTypes(newDesc);
        if (oldArgs == null || newArgs == null || newArgs.length <= oldArgs.length
                || !Type.getReturnType(oldDesc).equals(Type.getReturnType(newDesc))) {
            return null;
        }
        return inferCompleteInsertions(Arrays.asList(oldArgs), Arrays.asList(newArgs));
    }

    private static List<MixinHandlerResignature.ParamInsert> inferCompleteInsertions(
            List<Type> oldArgs, List<Type> newArgs) {
        if (oldArgs.isEmpty()) {
            List<MixinHandlerResignature.ParamInsert> inserts = new ArrayList<>();
            for (Type type : newArgs) {
                inserts.add(new MixinHandlerResignature.ParamInsert(0, type.getDescriptor()));
            }
            return inserts;
        }
        List<int[]> alignments = uniqueAlignments(oldArgs, newArgs, true);
        if (alignments.size() != 1) return null;
        return insertionsFromAlignment(oldArgs.size(), newArgs, alignments.get(0), true);
    }

    /** Finds at most two alignments. Two is enough to prove ambiguity. */
    private static List<int[]> uniqueAlignments(
            List<Type> oldArgs, List<Type> newArgs, boolean allowTrailing) {
        List<int[]> out = new ArrayList<>(2);
        int[] positions = new int[oldArgs.size()];
        collectAlignments(oldArgs, newArgs, 0, 0, positions, out, allowTrailing);
        return out;
    }

    private static void collectAlignments(List<Type> oldArgs, List<Type> newArgs,
            int oldIndex, int newIndex, int[] positions, List<int[]> out,
            boolean allowTrailing) {
        if (out.size() >= 2) return;
        if (oldIndex == oldArgs.size()) {
            if (allowTrailing || newIndex == newArgs.size()) {
                out.add(Arrays.copyOf(positions, positions.length));
            }
            return;
        }
        int remainingOld = oldArgs.size() - oldIndex;
        for (int i = newIndex; i <= newArgs.size() - remainingOld; i++) {
            if (!oldArgs.get(oldIndex).equals(newArgs.get(i))) continue;
            positions[oldIndex] = i;
            collectAlignments(oldArgs, newArgs, oldIndex + 1, i + 1,
                    positions, out, allowTrailing);
            if (out.size() >= 2) return;
        }
    }

    private static List<MixinHandlerResignature.ParamInsert> insertionsFromAlignment(
            int oldSize, List<Type> newArgs, int[] positions, boolean includeTrailing) {
        List<MixinHandlerResignature.ParamInsert> inserts = new ArrayList<>();
        int oldIndex = 0;
        int nextMatch = positions.length == 0 ? newArgs.size() : positions[0];
        for (int newIndex = 0; newIndex < newArgs.size(); newIndex++) {
            if (oldIndex < positions.length && newIndex == nextMatch) {
                oldIndex++;
                nextMatch = oldIndex < positions.length ? positions[oldIndex] : Integer.MAX_VALUE;
            } else if (includeTrailing || oldIndex < oldSize) {
                inserts.add(new MixinHandlerResignature.ParamInsert(
                        oldIndex, newArgs.get(newIndex).getDescriptor()));
            }
        }
        return inserts;
    }

    private static void applyDecision(MethodNode handler, List<Object> values, int valueIndex,
            SelectorDecision decision, MutableScan scan) {
        if (decision == null) return;
        if (decision.handlerInserts().isEmpty()) {
            if (decision.replacement() != null
                    && !decision.replacement().equals(values.get(valueIndex))) {
                values.set(valueIndex, decision.replacement());
                scan.selectorChanged = true;
                scan.selectorRepairs++;
            }
            return;
        }
        scan.pending.add(new CoupledRepair(
                handler, values, valueIndex, decision.replacement(), decision.handlerInserts()));
        if (decision.replacement() != null) scan.selectorRepairs++;
    }

    private static Set<String> mixinOwners(ClassNode classNode) {
        Set<String> owners = new LinkedHashSet<>();
        for (List<AnnotationNode> annotations : annotationLists(classNode)) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    String key = String.valueOf(annotation.values.get(i));
                    Object value = annotation.values.get(i + 1);
                    if ("value".equals(key) && value instanceof List<?> types) {
                        for (Object entry : types) {
                            if (entry instanceof Type type) owners.add(type.getInternalName());
                        }
                    } else if ("targets".equals(key) && value instanceof List<?> names) {
                        for (Object entry : names) {
                            if (entry instanceof String name) owners.add(name.replace('.', '/'));
                        }
                    }
                }
            }
        }
        return owners;
    }

    private static boolean mixinRemapDisabled(ClassNode classNode) {
        for (List<AnnotationNode> annotations : annotationLists(classNode)) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN.equals(annotation.desc) && remapDisabled(annotation)) return true;
            }
        }
        return false;
    }

    private static List<AnnotationNode> collectAtAnnotations(AnnotationNode injector) {
        List<AnnotationNode> out = new ArrayList<>();
        collectAtAnnotations(injector, out, false);
        return out;
    }

    private static void collectAtAnnotations(
            AnnotationNode node, List<AnnotationNode> out, boolean includeNode) {
        if (node == null) return;
        if (includeNode && AT.equals(node.desc)) out.add(node);
        if (node.values == null) return;
        for (int i = 1; i < node.values.size(); i += 2) {
            Object value = node.values.get(i);
            if (value instanceof AnnotationNode nested) {
                collectAtAnnotations(nested, out, true);
            } else if (value instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof AnnotationNode nested) {
                        collectAtAnnotations(nested, out, true);
                    }
                }
            }
        }
    }

    private static boolean callbackMatchesReturn(Type callback, String returnDesc) {
        if (callback == null || callback.getSort() != Type.OBJECT || returnDesc == null) return false;
        String internal = callback.getInternalName();
        return "V".equals(returnDesc)
                ? CALLBACK_INFO.equals(internal)
                : CALLBACK_INFO_RETURNABLE.equals(internal);
    }

    private static boolean argsMatch(Type[] actual, int offset, Type[] expected) {
        if (actual.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (!actual[offset + i].equals(expected[i])) return false;
        }
        return true;
    }

    private static Type[] safeArgumentTypes(String descriptor) {
        try {
            return descriptor == null ? null : Type.getArgumentTypes(descriptor);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String returnDescriptor(String descriptor) {
        try {
            return descriptor == null ? null : Type.getReturnType(descriptor).getDescriptor();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int staticness(int access) {
        return access >= 0 && (access & Opcodes.ACC_STATIC) != 0 ? 1 : 0;
    }

    private static boolean isInjector(String desc) {
        return desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                || desc.startsWith("Lcom/llamalad7/mixinextras/injector/");
    }

    private static boolean remapDisabled(AnnotationNode annotation) {
        return Boolean.FALSE.equals(annotationValue(annotation, "remap"));
    }

    private static boolean isBareMethodName(String value) {
        return value != null && !value.isEmpty() && value.indexOf('(') < 0
                && value.indexOf(';') < 0 && value.indexOf('*') < 0
                && !value.startsWith("<");
    }

    private static String soleOwner(Set<String> owners) {
        return owners.size() == 1 ? owners.iterator().next() : null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static String annotationString(AnnotationNode annotation, String key) {
        Object value = annotationValue(annotation, key);
        return value instanceof String string ? string : null;
    }

    private static void putAnnotationString(AnnotationNode annotation, String key, String value) {
        if (annotation.values == null) annotation.values = new ArrayList<>();
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, value);
                return;
            }
        }
        annotation.values.add(key);
        annotation.values.add(value);
    }

    private static String inferredInvokerName(String methodName) {
        for (String prefix : List.of("invoke", "call")) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String suffix = methodName.substring(prefix.length());
                return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
            }
        }
        return null;
    }

    private static boolean sharesSemanticToken(String a, String b) {
        Set<String> left = semanticTokens(a);
        for (String token : semanticTokens(b)) {
            if (token.length() >= 4 && left.contains(token)) return true;
        }
        return false;
    }

    private static Set<String> semanticTokens(String name) {
        if (name == null) return Set.of();
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ').toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();
        for (String token : spaced.split("\\s+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private static List<List<AnnotationNode>> annotationLists(MethodNode method) {
        return List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.of());
    }

    private static List<List<AnnotationNode>> annotationLists(ClassNode classNode) {
        return List.of(
                classNode.visibleAnnotations != null ? classNode.visibleAnnotations : List.of(),
                classNode.invisibleAnnotations != null ? classNode.invisibleAnnotations : List.of());
    }

    private interface PendingRepair {
        boolean apply();
    }

    private record CoupledRepair(MethodNode handler, List<Object> values, int valueIndex,
            String replacement, List<MixinHandlerResignature.ParamInsert> inserts)
            implements PendingRepair {
        @Override
        public boolean apply() {
            if (!MixinHandlerResignature.insertRawParams(handler, inserts)) return false;
            if (replacement != null) values.set(valueIndex, replacement);
            return true;
        }
    }

    private record HandlerOnlyRepair(MethodNode handler,
            List<MixinHandlerResignature.ParamInsert> inserts) implements PendingRepair {
        @Override
        public boolean apply() {
            return MixinHandlerResignature.insertRawParams(handler, inserts);
        }
    }

    private record SelectorDecision(String replacement,
            List<MixinHandlerResignature.ParamInsert> handlerInserts) {}

    private record MethodChange(String name, String descriptor, int access,
            List<MixinHandlerResignature.ParamInsert> inserts) {}

    private record MethodScan(boolean selectorChanged, int selectorRepairs,
            List<PendingRepair> pending) {}

    private static final class MutableScan {
        boolean selectorChanged;
        int selectorRepairs;
        final List<PendingRepair> pending = new ArrayList<>();
    }

    private record ParsedSelector(String original, String owner, String name, String descriptor) {
        static ParsedSelector parse(String selector) {
            if (selector == null || selector.indexOf('*') >= 0) return null;
            int paren = selector.indexOf('(');
            if (paren <= 0) return null;
            String head = selector.substring(0, paren);
            String desc = selector.substring(paren);
            try {
                Type.getMethodType(desc);
            } catch (RuntimeException e) {
                return null;
            }
            String owner = null;
            String name = head;
            if (head.startsWith("L")) {
                int semi = head.lastIndexOf(';');
                if (semi <= 1 || semi == head.length() - 1) return null;
                owner = head.substring(1, semi);
                name = head.substring(semi + 1);
            }
            if (name.isEmpty() || name.startsWith("<")) return null;
            return new ParsedSelector(selector, owner, name, desc);
        }

        String withMethod(String newName, String newDescriptor) {
            return owner == null
                    ? newName + newDescriptor
                    : "L" + owner + ";" + newName + newDescriptor;
        }
    }
}
