/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.*;

/** Updates Mixin annotations and handlers after their Minecraft targets change. */
public final class MixinCompatibilityTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    // Mixin annotation descriptors
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG_DESC = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String MODIFY_VAR_DESC = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String MUTABLE_DESC = "Lorg/spongepowered/asm/mixin/Mutable;";
    private static final Pattern ACCESSOR_NAME_PATTERN = Pattern.compile(
            "^(get|is|set)(([A-Z])(.*?))(_\\$md.*)?$");
    private static final String AMBIGUOUS_RENAME = "\0AMBIGUOUS";
    /** MixinExtras keeps injector annotations under this package. */
    private static final String MIXINEXTRAS_INJECTOR_PREFIX = "Lcom/llamalad7/mixinextras/injector/";
    
    private final RetromodTransformer transformer;

    // Old target reference to new target reference.
    private final Map<String, String> methodTargetRedirects = new HashMap<>();

    // Same-owner Mojang renames used when a bare mixin selector omits its owner.
    private final Map<String, Map<String, String>> ownerScopedRenames = new HashMap<>();

    // Fabric transforms mixin classes in parallel, so this per-class scratch state cannot be shared.
    private final ThreadLocal<Set<String>> currentTargetOwners =
            ThreadLocal.withInitial(HashSet::new);

    public MixinCompatibilityTransformer(RetromodTransformer transformer) {
        this.transformer = transformer;
        buildMixinRedirects();
    }
    
    // Convert bytecode redirects into the string format used by Mixin.
    private void buildMixinRedirects() {
        for (var entry : transformer.getMethodRedirects().entrySet()) {
            var key = entry.getKey();
            var target = entry.getValue();

            String oldRef = key.name();
            String newRef = target.name();

            // An owner or name change is safe to apply to a fully qualified target.
            // Descriptor-only bridges may refer to a real overload and stay untouched.
            String oldFull = "L" + key.owner() + ";" + key.name() + key.desc();
            String newFull = "L" + target.owner() + ";" + target.name() + target.desc();
            boolean ownerOrNameChanged = !key.owner().equals(target.owner()) || !oldRef.equals(newRef);
            if (ownerOrNameChanged) {
                methodTargetRedirects.put(oldFull, newFull);
            }

            // Bare selectors can use same-owner renames when the target class is known.
            if (!oldRef.equals(newRef) && key.owner().equals(target.owner())) {
                ownerScopedRenames.computeIfAbsent(key.owner(), k -> new HashMap<>())
                        .merge(oldRef, newRef, (a, b) -> a.equals(b) ? a : AMBIGUOUS_RENAME);
            }

            // Owner-free names are safe only when the mapping name is globally unique.
            if (!oldRef.equals(newRef) && isGloballyUniqueName(oldRef)) {
                methodTargetRedirects.put(oldRef, newRef);
            }
        }

        LOGGER.debug("Prepared {} Mixin target redirects", methodTargetRedirects.size());
    }

    /** A globally-unique obfuscated name ({@code method_XXXX} / SRG {@code m_NNNNN_}), safe as an owner-agnostic redirect key. */
    private static boolean isGloballyUniqueName(String name) {
        return name.startsWith("method_")
                || (name.startsWith("m_") && name.endsWith("_") && name.length() > 3);
    }
    
    /** Updates one Mixin class and returns the original bytes when nothing changed. */
    public byte[] transformMixinClass(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean modified = false;

        if (!isMixinClass(classNode)) {
            return classBytes;
        }

        currentTargetOwners.get().clear();

        LOGGER.debug("Transforming Mixin class: {}", classNode.name);

        // A field redirect may move a static field to a different class. Move a matching
        // accessor mixin before the ordinary annotation pass records its target owner.
        modified |= transformAccessorOwnerMove(classNode);

        // Some mixins add state outside their handlers, so the whole mixin must
        // be skipped instead of deleting only its injection methods.
        if (MixinBlocklist.isFullStrip(classNode.name)) {
            if (neutralizeMixin(classNode)) {
                ClassWriter w = new ClassWriter(0);
                classNode.accept(w);
                LOGGER.info("Disabled incompatible Mixin {}. Its feature will be unavailable.",
                    classNode.name);
                return w.toByteArray();
            }
        }

        // A removed target cannot accept a mixin, but the rest of the mod may still work.
        String removedTarget = mixinTargetsRemovedClass(classNode);
        if (removedTarget != null && neutralizeMixin(classNode)) {
            ClassWriter w = new ClassWriter(0);
            classNode.accept(w);
            LOGGER.info("Disabled Mixin {} because target {} no longer exists. "
                    + "Its feature will be unavailable.",
                    classNode.name, removedTarget);
            return w.toByteArray();
        }

        // Curated handlers with incompatible local layouts are safer to remove.
        Set<String> blockedMethods = MixinBlocklist.methodsToStrip(classNode.name);
        if (blockedMethods != null) {
            int before = classNode.methods.size();
            if (blockedMethods.isEmpty()) {
                // Keep constructors and helpers when only injector handlers are blocked.
                classNode.methods.removeIf(MixinCompatibilityTransformer::hasInjectorAnnotation);
            } else {
                classNode.methods.removeIf(m -> blockedMethods.contains(m.name));
            }
            int removed = before - classNode.methods.size();
            if (removed > 0) {
                modified = true;
                LOGGER.info("Removed {} incompatible handler(s) from {}. "
                        + "The related feature will be unavailable.", removed, classNode.name);
            }
        }

        // @Mixin can be stored in either annotation list.
        for (List<AnnotationNode> annotations : List.of(
                classNode.visibleAnnotations != null ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN_DESC.equals(annotation.desc)) {
                    modified |= transformMixinAnnotation(annotation);
                }
            }
        }

        // Parameter repairs only apply after the 1.21.5 method refactor.
        boolean refactorHost = has1215Refactor();
        List<MethodNode> resignTargets = new ArrayList<>();
        List<List<MixinHandlerResignature.ParamInsert>> resignInserts = new ArrayList<>();
        List<MixinHandlerResignature.DriftRepair> driftRepairs = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            modified |= transformMethodAnnotations(method);
            if (!refactorHost) continue;
            // Repair annotation selectors before changing the matching handler.
            modified |= MixinHandlerResignature.rewriteAnnotationDrift(method);
            List<MixinHandlerResignature.ParamInsert> ins = MixinHandlerResignature.injectSignatureChange(method);
            if (ins != null) { resignTargets.add(method); resignInserts.add(ins); }
            // Apply bytecode repairs later, during guarded frame recomputation.
            driftRepairs.addAll(MixinHandlerResignature.detectRedirectDrift(method));
            driftRepairs.addAll(MixinHandlerResignature.detectOverwriteDrift(method));
        }

        List<ShadowFieldRename> shadowFieldRenames = new ArrayList<>();
        for (FieldNode field : classNode.fields) {
            String oldName = field.name;
            boolean fieldModified = transformFieldAnnotations(field);
            modified |= fieldModified;
            if (fieldModified && !oldName.equals(field.name)) {
                shadowFieldRenames.add(new ShadowFieldRename(oldName, field.name, field.desc));
            }
        }
        if (!shadowFieldRenames.isEmpty()) {
            rewriteShadowFieldReferences(classNode, shadowFieldRenames);
        }

        if (!modified && resignTargets.isEmpty() && driftRepairs.isEmpty()) {
            return classBytes;
        }

        // Keep a valid annotation-only fallback in case frame recomputation fails.
        ClassWriter annWriter = new ClassWriter(0);
        classNode.accept(annWriter);
        byte[] annotationOnly = annWriter.toByteArray();
        return reemitWithResignatures(classNode, resignTargets, resignInserts, driftRepairs, annotationOnly);
    }

    /** Applies handler repairs and falls back to valid annotation-only bytes on failure. */
    private byte[] reemitWithResignatures(ClassNode classNode, List<MethodNode> targets,
            List<List<MixinHandlerResignature.ParamInsert>> inserts,
            List<MixinHandlerResignature.DriftRepair> driftRepairs, byte[] fallbackBytes) {
        if (targets.isEmpty() && driftRepairs.isEmpty()) return fallbackBytes;
        try {
            int applied = 0;
            for (int i = 0; i < targets.size(); i++) {
                if (MixinHandlerResignature.insertParams(targets.get(i), inserts.get(i))) applied++;
            }
            // A call-site selector and its matching handler must change together.
            for (MixinHandlerResignature.DriftRepair d : driftRepairs) {
                if (d.apply()) applied++;
            }
            if (applied == 0) return fallbackBytes;
            ClassWriter cw = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            classNode.accept(cw);
            byte[] out = cw.toByteArray();
            LOGGER.info("Repaired {} changed Mixin handler(s) or call site(s) in {}",
                    applied, classNode.name);
            return out;
        } catch (Throwable t) {
            LOGGER.debug("Could not verify repaired handlers in {} ({}). Keeping the safe fallback.",
                    classNode.name, t.toString());
            return fallbackBytes;
        }
    }

    /** Returns whether the host includes the 1.21.5 entity and ValueIO refactor. */
    private boolean has1215Refactor() {
        return com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.5") >= 0;
    }

    /**
     * Adapts save-data handlers from {@code CompoundTag} to ValueIO after name
     * remapping. A handler that cannot be repaired is removed instead of
     * leaving a class that fails during Mixin application.
     */
    public byte[] adaptValueIoHandlers(byte[] classBytes) {
        if (!has1215Refactor()) return classBytes;
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        if (!isMixinClass(classNode)) return classBytes;

        List<MixinValueIoAdapter.Target> targets = MixinValueIoAdapter.collect(classNode);
        // A stale CompoundTag capture cannot receive the host's ValueIO argument.
        List<MethodNode> unrepairable = MixinValueIoAdapter.collectUnrepairable(classNode, targets);
        if (targets.isEmpty() && unrepairable.isEmpty()) return classBytes;

        if (!targets.isEmpty()) {
            // Register the bridge before adapted bytecode can refer to it.
            MixinValueIoAdapter.ensureBridgeRegistered(transformer);
        }

        // Include descriptors so an unrelated overload cannot be removed.
        java.util.Set<String> targetKeys = new java.util.HashSet<>();
        for (MixinValueIoAdapter.Target t : targets) targetKeys.add(t.originalName + t.handler.desc);
        try {
            int applied = MixinValueIoAdapter.apply(classNode, targets);
            if (!unrepairable.isEmpty()) {
                // Identity keeps newly generated adapters with the same name intact.
                classNode.methods.removeIf(unrepairable::contains);
                LOGGER.info("Removed {} save-data handler(s) from {} because they cannot use ValueIO",
                        unrepairable.size(), classNode.name);
            }
            if (applied == 0 && unrepairable.isEmpty()) return classBytes;
            ClassWriter cw = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            classNode.accept(cw);
            byte[] out = cw.toByteArray();
            if (applied > 0) {
                LOGGER.info("Adapted {} save-data Mixin handler(s) in {} for ValueIO",
                        applied, classNode.name);
            }
            return out;
        } catch (Throwable t) {
            LOGGER.debug("Could not verify the ValueIO repair in {} ({}). Removing the affected handlers.",
                    classNode.name, t.toString());
            // Start from the original bytes so no partial adapter survives.
            java.util.Set<String> allKeys = new java.util.HashSet<>(targetKeys);
            for (MethodNode m : unrepairable) allKeys.add(m.name + m.desc);
            return MixinValueIoAdapter.stripTargetsFrom(classBytes, allKeys);
        }
    }

    /** Runs the same Mixin pipeline for Forge, NeoForge, and offline transforms. */
    public byte[] stripBlocklistedHandlers(byte[] classBytes) {
        return transformMixinClass(classBytes);
    }

    /**
     * Runs repairs that require final, post-remap Minecraft names.
     *
     * <p>The automatic translator first checks exact declarations in the current Minecraft JAR.
     * Curated legacy member bridges then handle refactors that cannot be inferred from signatures
     * alone. The ValueIO adapter runs last because it also matches final Mojang descriptors.
     * Keeping this as one entry point prevents runtime, CLI, AOT, and nested-jar paths from quietly
     * gaining different Mixin coverage.
     */
    public byte[] applyPostRemapRepairs(byte[] classBytes) {
        return applyPostRemapRepairs(classBytes, MixinRefmapRepairIndex.empty());
    }

    /** Runs post-remap repairs with exact relationships collected from this archive's refmaps. */
    public byte[] applyPostRemapRepairs(
            byte[] classBytes, MixinRefmapRepairIndex refmapRepairs) {
        AutomaticMixinTranslator automatic =
                new AutomaticMixinTranslator(transformer.getFuzzyResolver());
        byte[] translated = automatic.translate(classBytes, refmapRepairs);
        byte[] bridged = applyLegacyMemberBridges(translated);
        return adaptValueIoHandlers(bridged);
    }

    /**
     * Rebuilds Mixin members whose old descriptor no longer resolves: a removed shadow becomes
     * mixin-owned state, and a member the host still offers under a different descriptor becomes
     * a mixin-owned bridge overload.
     *
     * <p>These repairs match Mojang descriptors, so they run after the main remap. A Fabric mod
     * still carries intermediary names when {@link #transformMixinClass} runs, so matching there
     * found nothing and left the mod to fail on the descriptor it was built against.
     */
    public byte[] applyLegacyMemberBridges(byte[] classBytes) {
        if (!has1215Refactor()) return classBytes;
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        if (!isMixinClass(classNode)) return classBytes;

        boolean modified = MixinLegacyMemberBridge.apply(classNode);
        modified |= MixinCommandParserBridge.apply(classNode, transformer.getFuzzyResolver());
        if (MixinShadowFieldDemotion.handles(classNode.name)) {
            modified |= MixinShadowFieldDemotion.apply(classNode);
        }
        if (!modified) return classBytes;

        // The repairs carry their own stack maps, so the class is written back as-is.
        ClassWriter cw = new ClassWriter(0);
        classNode.accept(cw);
        LOGGER.info("Repaired legacy Mixin member(s) in {} for their current descriptors",
                classNode.name);
        return cw.toByteArray();
    }

    /** Points a Mixin at an absent placeholder so the framework skips it cleanly. */
    private boolean neutralizeMixin(ClassNode classNode) {
        AnnotationNode mixinAnn = null;
        for (List<AnnotationNode> anns : List.of(
                classNode.visibleAnnotations != null ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) {
                if (MIXIN_DESC.equals(a.desc)) { mixinAnn = a; break; }
            }
            if (mixinAnn != null) break;
        }
        if (mixinAnn == null) return false;

        if (mixinAnn.values == null) mixinAnn.values = new ArrayList<>();
        // Remove both supported forms of the original target list.
        for (int i = mixinAnn.values.size() - 2; i >= 0; i -= 2) {
            Object k = mixinAnn.values.get(i);
            if ("value".equals(k) || "targets".equals(k)) {
                mixinAnn.values.remove(i + 1);
                mixinAnn.values.remove(i);
            }
        }
        // The Retromod namespace makes an accidental real target very unlikely.
        String simple = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
        List<String> placeholder = new ArrayList<>();
        placeholder.add("retromod/stripped/" + simple);
        mixinAnn.values.add("targets");
        mixinAnn.values.add(placeholder);
        return true;
    }

    /** Whether the class carries a {@code @Mixin} annotation (visible or invisible). */
    private boolean isMixinClass(ClassNode classNode) {
        if (classNode.visibleAnnotations != null) {
            for (AnnotationNode annotation : classNode.visibleAnnotations) {
                if (MIXIN_DESC.equals(annotation.desc)) return true;
            }
        }
        if (classNode.invisibleAnnotations != null) {
            for (AnnotationNode annotation : classNode.invisibleAnnotations) {
                if (MIXIN_DESC.equals(annotation.desc)) return true;
            }
        }
        return false;
    }

    /** Whether the class is an interface mixin (@Accessor/@Invoker interfaces). */
    private boolean isInterfaceMixin(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0 && isMixinClass(classNode);
    }

    /**
     * Detect a mixin whose {@code @Mixin} target is a Minecraft class removed (not renamed)
     * on the host. Applying it makes the framework throw {@code ClassMetadataNotFoundException}
     * mid-transform and crash the game at bootstrap, often with a misleading vanilla-class
     * stacktrace that names no mod. #79: Spelunkery targets {@code LootDataManager}, deleted
     * in the 1.21 loot-data refactor.
     *
     * <p>Automatic complement to the curated {@link MixinBlocklist}; the mod loads with that
     * mixin inert. Fires only when:
     * <ul>
     *   <li>the target is a {@code net/minecraft/} class (mod/library targets resolve later
     *       via JiJ/companion mods);</li>
     *   <li>after resolving through class redirects + the intermediary map, the class still
     *       doesn't exist on the host ({@code initialize=false} probe, #14);</li>
     *   <li>MC is resolvable through the probe at all (else bail, don't strip everything).</li>
     * </ul>
     *
     * @return the removed target's internal name to neutralize, or {@code null} if every
     *         target resolves.
     */
    private String mixinTargetsRemovedClass(ClassNode classNode) {
        return mixinTargetsRemovedClass(classNode,
                com.retromod.core.EnvironmentDetector::hostClassExists);
    }

    /**
     * Testable core of {@link #mixinTargetsRemovedClass(ClassNode)}: the host-class probe
     * is injected so a unit test can simulate a runtime where a vanilla class is present or
     * absent (the test JVM has no Minecraft on its classpath).
     *
     * @param hostHasClass predicate: does this binary class name resolve on the host?
     */
    String mixinTargetsRemovedClass(ClassNode classNode, java.util.function.Predicate<String> hostHasClass) {
        // If MC itself isn't resolvable through the probe, every "absent" is a false
        // negative; don't strip. Keyed on a class present on every supported host (Blocks,
        // or its intermediary alias pre-26.1).
        if (!hostHasClass.test("net.minecraft.world.level.block.Blocks")
                && !hostHasClass.test("net.minecraft.class_2246")) {
            return null;
        }

        AnnotationNode mixinAnn = null;
        for (List<AnnotationNode> anns : List.of(
                classNode.visibleAnnotations != null ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) {
                if (MIXIN_DESC.equals(a.desc)) { mixinAnn = a; break; }
            }
            if (mixinAnn != null) break;
        }
        if (mixinAnn == null || mixinAnn.values == null) return null;

        List<String> targets = new ArrayList<>();
        for (int i = 0; i + 1 < mixinAnn.values.size(); i += 2) {
            Object key = mixinAnn.values.get(i);
            Object val = mixinAnn.values.get(i + 1);
            if ("value".equals(key) && val instanceof List<?> classes) {
                // Class[] targets: each entry is an ASM Type
                for (Object t : classes) {
                    if (t instanceof Type type && type.getSort() == Type.OBJECT) {
                        targets.add(type.getInternalName());
                    }
                }
            } else if ("targets".equals(key) && val instanceof List<?> strings) {
                // String[] targets: '.'-separated fully-qualified names
                for (Object s : strings) {
                    if (s instanceof String str && !str.isEmpty()) {
                        targets.add(str.replace('.', '/'));
                    }
                }
            }
        }

        for (String target : targets) {
            if (!target.startsWith("net/minecraft/")) {
                continue; // only judge vanilla classes; mod/library targets resolve elsewhere
            }
            // Resolve through whatever Retromod would rewrite this to (a class redirect or
            // the intermediary->Mojang map); the host probe is the authority, redirects just
            // give the better name to ask.
            String resolved = transformer.getClassRedirects().getOrDefault(target, target);
            boolean resolvedExists = hostHasClass.test(resolved.replace('/', '.'));
            boolean origExists = resolved.equals(target) ? resolvedExists
                    : hostHasClass.test(target.replace('/', '.'));
            if (!resolvedExists && !origExists) {
                return target; // removed: neutralize this mixin
            }
        }
        return null;
    }

    /**
     * Whether a method carries an injector annotation: a SpongePowered injection
     * ({@code @Inject}/{@code @Redirect}/{@code @ModifyArg}/{@code @ModifyVariable}/...),
     * {@code @Overwrite}, or any MixinExtras annotation. Used by whole-class blocklist
     * entries to drop every handler while keeping constructors, {@code @Shadow}/
     * {@code @Accessor} members, and plain helpers.
     */
    private static boolean hasInjectorAnnotation(MethodNode m) {
        return injectorPresent(m.visibleAnnotations) || injectorPresent(m.invisibleAnnotations);
    }

    private static boolean injectorPresent(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) {
            if (a.desc == null) continue;
            if (a.desc.contains("spongepowered/asm/mixin/injection/")
                    || a.desc.contains("llamalad7/mixinextras/")
                    || OVERWRITE_DESC.equals(a.desc)) {
                return true;
            }
        }
        return false;
    }

    private int countParameterSlots(String desc) {
        int slots = 0;
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'D' || c == 'J') {
                slots += 2;
                i++;
            } else if (c == 'L') {
                slots++;
                int end = desc.indexOf(';', i);
                if (end < 0) break; // malformed (no ';'): indexOf+1 would reset i and spin forever
                i = end + 1;
            } else if (c == '[') {
                i++;
                while (i < desc.length() && desc.charAt(i) == '[') i++; // array dimensions
                if (i < desc.length() && desc.charAt(i) == 'L') {
                    int end = desc.indexOf(';', i);
                    if (end < 0) break; // malformed
                    i = end + 1;
                } else {
                    i++; // primitive array
                }
                slots++;
            } else {
                slots++;
                i++;
            }
        }
        return slots;
    }

    /**
     * Transform @Mixin annotation targets.
     */
    private boolean transformMixinAnnotation(AnnotationNode annotation) {
        boolean modified = false;

        if (annotation.values == null) return false;

        for (int i = 0; i < annotation.values.size(); i += 2) {
            String key = (String) annotation.values.get(i);
            Object value = annotation.values.get(i + 1);

            if ("targets".equals(key) && value instanceof List<?> targets) {
                // string targets: @Mixin(targets = {"net.minecraft.class_310"})
                List<String> newTargets = new ArrayList<>();
                for (Object target : targets) {
                    if (target instanceof String s) {
                        String redirected = redirectClassName(s);
                        newTargets.add(redirected);
                        currentTargetOwners.get().add(redirected.replace('.', '/'));
                        if (!s.equals(redirected)) {
                            modified = true;
                            LOGGER.debug("Redirected Mixin target: {} -> {}", s, redirected);
                        }
                    } else {
                        newTargets.add(target.toString());
                    }
                }
                annotation.values.set(i + 1, newTargets);
            } else if ("value".equals(key) && value instanceof List<?> values) {
                // type targets: @Mixin(value = {class_310.class}); ASM stores them as Type
                List<Object> newValues = new ArrayList<>();
                for (Object v : values) {
                    if (v instanceof org.objectweb.asm.Type type) {
                        String internal = type.getInternalName();
                        String redirected = transformer.getClassRedirects()
                            .getOrDefault(internal, internal);
                        currentTargetOwners.get().add(redirected);
                        if (!internal.equals(redirected)) {
                            newValues.add(org.objectweb.asm.Type.getObjectType(redirected));
                            modified = true;
                            LOGGER.debug("Redirected Mixin value target: {} -> {}", internal, redirected);
                        } else {
                            newValues.add(v);
                        }
                    } else {
                        newValues.add(v);
                    }
                }
                annotation.values.set(i + 1, newValues);
            }
        }

        return modified;
    }
    
    /**
     * Transform method annotations (@Inject, @Redirect, etc).
     */
    private boolean transformMethodAnnotations(MethodNode method) {
        boolean modified = false;

        // mixin annotations can be visible or invisible
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                String desc = annotation.desc;

                if (desc != null && desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                        && !desc.contains("/callback/")) {
                    // Family-prefix dispatch, mirroring the MixinExtras branch below. The former
                    // explicit whitelist silently skipped @ModifyArgs (plural) and
                    // @ModifyConstant, leaving their selectors unremapped (#157, nekomasfixed's
                    // LabelCommandRendererCommandMixin + the wildfire
                    // NetherFortressGeneratorMixin). Non-selector members of the package
                    // (@Surrogate/@Group/@Coerce) pass through untouched: the walker only
                    // rewrites method/at/target/slice keys and adds nothing unless it rewrote
                    // something.
                    modified |= transformInjectionAnnotation(annotation, false);
                } else if (desc != null && desc.startsWith(MIXINEXTRAS_INJECTOR_PREFIX)) {
                    // MixinExtras injectors (@WrapOperation, @ModifyExpressionValue,
                    // @ModifyReturnValue, ...) carry the same method=/at=/target= selectors as
                    // core injectors but were never dispatched, so a renamed target failed with
                    // "Scanned 0 target(s)" (#50 Revamped Phantoms). Same remap; require=0 is
                    // added only when a selector was actually rewritten, so an untouched
                    // MixinExtras mixin stays byte-identical.
                    modified |= transformInjectionAnnotation(annotation, true);
                } else if (SHADOW_DESC.equals(desc) || OVERWRITE_DESC.equals(desc)) {
                    modified |= transformShadowAnnotation(annotation, method);
                } else if (ACCESSOR_DESC.equals(desc) || INVOKER_DESC.equals(desc)) {
                    modified |= transformAccessorAnnotation(annotation, method);
                }
            }
        }

        return modified;
    }
    
    /**
     * Transform @Inject, @Redirect, @ModifyArg, @ModifyVariable annotations.
     */
    private boolean transformInjectionAnnotation(AnnotationNode annotation, boolean mixinExtras) {
        boolean modified = false;

        if (annotation.values == null) {
            annotation.values = new ArrayList<>();
        }

        boolean hasRequire = false;

        for (int i = 0; i < annotation.values.size(); i += 2) {
            String key = (String) annotation.values.get(i);
            Object value = annotation.values.get(i + 1);

            if ("require".equals(key)) {
                hasRequire = true;
            }

            if ("method".equals(key)) {
                if (value instanceof List<?> methods) {
                    List<String> newMethods = new ArrayList<>();
                    for (Object m : methods) {
                        if (m instanceof String s) {
                            String redirected = redirectMethodTarget(s);
                            newMethods.add(redirected);
                            if (!s.equals(redirected)) {
                                modified = true;
                                LOGGER.debug("Redirected @Inject method: {} -> {}", s, redirected);
                            }
                        }
                    }
                    annotation.values.set(i + 1, newMethods);
                } else if (value instanceof String s) {
                    String redirected = redirectMethodTarget(s);
                    if (!s.equals(redirected)) {
                        annotation.values.set(i + 1, redirected);
                        modified = true;
                    }
                }
            }

            if ("at".equals(key)) {
                if (value instanceof AnnotationNode at) {
                    modified |= transformAtAnnotation(at);
                } else if (value instanceof List<?> ats) {
                    for (Object at : ats) {
                        if (at instanceof AnnotationNode atNode) {
                            modified |= transformAtAnnotation(atNode);
                        }
                    }
                }
            }

            // @Slice(from=@At(...), to=@At(...)) carries @At nodes the "at" branch never sees;
            // their target strings (often FIELD selectors) survived unremapped and made the whole
            // injection fail once everything else resolved (#157, nekomasfixed's BlocksMixin
            // slice-to Lclass_2246;field_46283:Lclass_2248;).
            if ("slice".equals(key)) {
                List<Object> slices = value instanceof List<?> l
                        ? new ArrayList<>(l)
                        : List.of(value);
                for (Object sl : slices) {
                    if (!(sl instanceof AnnotationNode sliceNode) || sliceNode.values == null) {
                        continue;
                    }
                    for (int j = 0; j < sliceNode.values.size(); j += 2) {
                        String sk = (String) sliceNode.values.get(j);
                        if (("from".equals(sk) || "to".equals(sk))
                                && sliceNode.values.get(j + 1) instanceof AnnotationNode sliceAt) {
                            modified |= transformAtAnnotation(sliceAt);
                        }
                    }
                }
            }

            // "target" appears on MixinExtras annotations (@ModifyExpressionValue) and some
            // @Inject variants, same shape as @At's target. Left stale, the processor refuses
            // the injection with "specifies a target class 'X', which is not supported"
            // (CustomHUD 4.1.3 on 26.1.2).
            if ("target".equals(key)) {
                if (value instanceof List<?> targets) {
                    List<String> newTargets = new ArrayList<>();
                    boolean changed = false;
                    for (Object t : targets) {
                        if (t instanceof String s) {
                            String redirected = redirectMethodTarget(s);
                            newTargets.add(redirected);
                            if (!s.equals(redirected)) {
                                changed = true;
                                LOGGER.debug("Redirected @ModifyExpressionValue/@Inject target: {} -> {}", s, redirected);
                            }
                        } else {
                            // non-string entry: leave the original list alone
                            newTargets = null;
                            break;
                        }
                    }
                    if (newTargets != null && changed) {
                        annotation.values.set(i + 1, newTargets);
                        modified = true;
                    }
                } else if (value instanceof String s) {
                    String redirected = redirectMethodTarget(s);
                    if (!s.equals(redirected)) {
                        annotation.values.set(i + 1, redirected);
                        modified = true;
                        LOGGER.debug("Redirected @ModifyExpressionValue/@Inject target: {} -> {}", s, redirected);
                    }
                }
            }

            // Downgrade CAPTURE_FAILHARD to CAPTURE_FAILSOFT. FAILHARD is a fatal
            // BootstrapMethodError at MC load when the injection site's locals don't match
            // the mixin's expected shape, which happens whenever MC changes locals across
            // versions (architectury MixinFallingBlockEntity, after FallingBlockEntity.tick()
            // gained a ServerLevel local on 26.1). FAILSOFT skips the injection with a
            // warning instead, so only that feature dies and MC still boots. Enum values are
            // stored as a String[]: [0] descriptor, [1] constant name.
            if ("locals".equals(key) && value instanceof String[] enumValue
                    && enumValue.length == 2
                    && "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;".equals(enumValue[0])
                    && "CAPTURE_FAILHARD".equals(enumValue[1])) {
                // Allocate a new String[]: ASM can share the array across AnnotationNode
                // instances when classnodes are reused, and an in-place edit would hide the
                // CAPTURE_FAILHARD from a later pass.
                annotation.values.set(i + 1, new String[]{enumValue[0], "CAPTURE_FAILSOFT"});
                modified = true;
                LOGGER.debug("Downgraded CAPTURE_FAILHARD to CAPTURE_FAILSOFT in mixin annotation");
            }
        }

        // require=0 soft-fails an injection whose target no longer exists. Core injectors get
        // it unconditionally; MixinExtras injectors only when a selector was rewritten above,
        // so a mixin we did not touch stays byte-identical.
        if (!hasRequire && (!mixinExtras || modified)) {
            annotation.values.add("require");
            annotation.values.add(0);
            modified = true;
        }

        return modified;
    }
    
    /**
     * Transform @At annotation targets.
     */
    private boolean transformAtAnnotation(AnnotationNode annotation) {
        boolean modified = false;
        
        if (annotation.values == null) return false;
        
        for (int i = 0; i < annotation.values.size(); i += 2) {
            String key = (String) annotation.values.get(i);
            Object value = annotation.values.get(i + 1);
            
            // "target" is the method/field reference
            if ("target".equals(key) && value instanceof String s) {
                String redirected = redirectMethodTarget(s);
                if (!s.equals(redirected)) {
                    annotation.values.set(i + 1, redirected);
                    modified = true;
                    LOGGER.debug("Redirected @At target: {} -> {}", s, redirected);
                }
            }
        }
        
        return modified;
    }
    
    /**
     * Transform @Shadow and @Overwrite annotations.
     */
    private boolean transformShadowAnnotation(AnnotationNode annotation, MethodNode method) {
        // for @Shadow the method name itself is the target
        String oldName = method.name;
        String newName = remapMethodName(oldName);

        if (!newName.equals(oldName)) {
            method.name = newName;
            LOGGER.debug("Renamed @Shadow method: {} -> {}", oldName, newName);
            return true;
        }

        return false;
    }
    
    /**
     * Transform @Accessor and @Invoker annotations.
     */
    private boolean transformAccessorAnnotation(AnnotationNode annotation, MethodNode method) {
        boolean modified = false;
        boolean isInvoker = INVOKER_DESC.equals(annotation.desc);
        String accessorFieldDesc = isInvoker ? null : accessorFieldDescriptor(method);

        boolean hasExplicitValue = false;
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                String key = (String) annotation.values.get(i);
                Object value = annotation.values.get(i + 1);

                if ("value".equals(key) && value instanceof String s) {
                    hasExplicitValue = true;
                    String redirected = remapMethodName(s);
                    if (!isInvoker && redirected.equals(s)) {
                        redirected = remapFieldName(s, accessorFieldDesc);
                    }
                    if (!s.equals(redirected)) {
                        annotation.values.set(i + 1, redirected);
                        modified = true;
                        LOGGER.debug("Redirected @Accessor/Invoker: {} -> {}", s, redirected);
                    }
                }
            }
        }

        // no explicit value: derive the target from the method name
        if (!hasExplicitValue) {
            String methodName = method.name;
            String target = null;

            if (isInvoker && methodName.startsWith("invoke")) {
                // invokeFindSlot -> findSlot
                target = Character.toLowerCase(methodName.charAt(6)) + methodName.substring(7);
            } else if (!isInvoker) {
                target = inferredAccessorFieldName(method);
            }

            if (target != null) {
                String redirected = isInvoker
                        ? remapMethodName(target)
                        : remapFieldName(target, accessorFieldDesc);
                if (!redirected.equals(target)) {
                    if (annotation.values == null) {
                        annotation.values = new ArrayList<>();
                    }
                    annotation.values.add("value");
                    annotation.values.add(redirected);
                    modified = true;
                    LOGGER.debug("Added @{} value: {} -> {} (from method {})",
                            isInvoker ? "Invoker" : "Accessor", target, redirected, methodName);
                }
            }
        }

        return modified;
    }

    private static String accessorFieldDescriptor(MethodNode method) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(method.desc);
        org.objectweb.asm.Type result = org.objectweb.asm.Type.getReturnType(method.desc);
        if (args.length == 0 && result.getSort() != org.objectweb.asm.Type.VOID) {
            return result.getDescriptor();
        }
        if (args.length == 1 && result.getSort() == org.objectweb.asm.Type.VOID) {
            return args[0].getDescriptor();
        }
        return null;
    }

    /** Moves a single-target accessor mixin when its registered field moved to another owner. */
    private boolean transformAccessorOwnerMove(ClassNode classNode) {
        if ((classNode.access & Opcodes.ACC_INTERFACE) == 0
                || !classNode.fields.isEmpty()
                || !classNode.interfaces.isEmpty()
                || classNode.methods.isEmpty()) {
            return false;
        }

        AnnotationNode mixin = findMixinAnnotation(classNode);
        String owner = singleMixinTarget(mixin);
        if (owner == null) return false;

        List<AnnotationNode> accessors = new ArrayList<>();
        List<MethodNode> accessorMethods = new ArrayList<>();
        RetromodTransformer.FieldTarget move = null;
        for (MethodNode method : classNode.methods) {
            AnnotationNode accessor = singleMethodAnnotation(method, ACCESSOR_DESC);
            if (accessor == null || hasUnsafeMixinMethodAnnotation(method)) return false;

            String fieldName = annotationString(accessor, "value");
            if (fieldName == null || fieldName.isEmpty()) {
                fieldName = inferredAccessorFieldName(method);
            }
            String fieldDesc = accessorFieldDescriptor(method);
            if (fieldName == null || fieldDesc == null) return false;

            RetromodTransformer.FieldTarget candidate = transformer.getFieldRedirects().get(
                    new RetromodTransformer.FieldKey(owner, fieldName));
            if (candidate == null
                    || candidate.owner().equals(owner)
                    || !fieldDesc.equals(candidate.oldDesc())
                    || !fieldDesc.equals(candidate.newDesc())) {
                return false;
            }
            if (move != null && !move.equals(candidate)) return false;

            move = candidate;
            accessors.add(accessor);
            accessorMethods.add(method);
        }
        if (move == null) return false;

        replaceMixinTarget(mixin, move.owner());
        for (int i = 0; i < accessors.size(); i++) {
            AnnotationNode accessor = accessors.get(i);
            setAnnotationValue(accessor, "value", move.name());
            setAnnotationValue(accessor, "remap", false);
            MethodNode method = accessorMethods.get(i);
            if (isAccessorSetter(method)
                    && transformer.isMutableAccessorDestination(
                        move.owner(), move.name(), move.newDesc())) {
                addVisibleAnnotation(method, MUTABLE_DESC);
            }
        }
        LOGGER.info("Moved accessor mixin {} target {} -> {} for field {}",
                classNode.name, owner, move.owner(), move.name());
        return true;
    }

    private static AnnotationNode singleMethodAnnotation(MethodNode method, String descriptor) {
        AnnotationNode found = null;
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (!descriptor.equals(annotation.desc)) continue;
                if (found != null) return null;
                found = annotation;
            }
        }
        return found;
    }

    private static boolean hasUnsafeMixinMethodAnnotation(MethodNode method) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if ((annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/")
                        && !ACCESSOR_DESC.equals(annotation.desc)
                        && !MUTABLE_DESC.equals(annotation.desc))
                        || annotation.desc.startsWith(MIXINEXTRAS_INJECTOR_PREFIX)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String inferredAccessorFieldName(MethodNode method) {
        Matcher matcher = ACCESSOR_NAME_PATTERN.matcher(method.name);
        if (!matcher.matches()) return null;

        String prefix = matcher.group(1);
        if (isAccessorSetter(method) ? !"set".equals(prefix)
                : accessorFieldDescriptor(method) == null || "set".equals(prefix)) {
            return null;
        }

        String namePart = matcher.group(2);
        String firstCharacter = matcher.group(3);
        String remainder = matcher.group(4);
        boolean allUpperCase = namePart.toUpperCase(Locale.ROOT).equals(namePart);
        return (allUpperCase ? firstCharacter : firstCharacter.toLowerCase(Locale.ROOT))
                + remainder;
    }

    private static boolean isAccessorSetter(MethodNode method) {
        return org.objectweb.asm.Type.getArgumentTypes(method.desc).length == 1
                && org.objectweb.asm.Type.getReturnType(method.desc).getSort()
                    == org.objectweb.asm.Type.VOID;
    }

    private static void addVisibleAnnotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null
                        ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null
                        ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            if (annotations.stream().anyMatch(a -> descriptor.equals(a.desc))) {
                return;
            }
        }
        if (method.visibleAnnotations == null) {
            method.visibleAnnotations = new ArrayList<>();
        }
        method.visibleAnnotations.add(new AnnotationNode(descriptor));
    }

    private static AnnotationNode findMixinAnnotation(ClassNode classNode) {
        for (List<AnnotationNode> annotations : List.of(
                classNode.visibleAnnotations != null ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN_DESC.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static String singleMixinTarget(AnnotationNode mixin) {
        if (mixin == null || mixin.values == null) return null;
        List<String> declaredTargets = new ArrayList<>();
        for (int i = 0; i < mixin.values.size(); i += 2) {
            Object key = mixin.values.get(i);
            if (!"value".equals(key) && !"targets".equals(key)) continue;
            Object value = mixin.values.get(i + 1);
            if (!(value instanceof List<?> targets)) return null;
            for (Object target : targets) {
                if (target instanceof org.objectweb.asm.Type type) {
                    declaredTargets.add(type.getInternalName());
                } else if (target instanceof String name) {
                    declaredTargets.add(name.replace('.', '/'));
                } else {
                    return null;
                }
            }
        }
        return declaredTargets.size() == 1 ? declaredTargets.get(0) : null;
    }

    private static void replaceMixinTarget(AnnotationNode mixin, String newOwner) {
        for (int i = 0; i < mixin.values.size(); i += 2) {
            Object key = mixin.values.get(i);
            if (!"value".equals(key) && !"targets".equals(key)) continue;
            Object value = mixin.values.get(i + 1);
            if (!(value instanceof List<?> targets) || targets.size() != 1) continue;
            Object old = targets.get(0);
            if (old instanceof org.objectweb.asm.Type) {
                mixin.values.set(i + 1, new ArrayList<>(List.of(
                        org.objectweb.asm.Type.getObjectType(newOwner))));
            } else if (old instanceof String name) {
                String replacement = name.contains(".") ? newOwner.replace('/', '.') : newOwner;
                mixin.values.set(i + 1, new ArrayList<>(List.of(replacement)));
            }
            return;
        }
    }

    private static String annotationString(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static void setAnnotationValue(AnnotationNode annotation, String key, Object value) {
        if (annotation.values == null) annotation.values = new ArrayList<>();
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, value);
                return;
            }
        }
        annotation.values.add(key);
        annotation.values.add(value);
    }
    
    /**
     * Transform field annotations.
     */
    private boolean transformFieldAnnotations(FieldNode field) {
        boolean modified = false;
        for (List<AnnotationNode> annotations : List.of(
                field.visibleAnnotations != null ? field.visibleAnnotations : List.<AnnotationNode>of(),
                field.invisibleAnnotations != null ? field.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (SHADOW_DESC.equals(annotation.desc)) {
                    String oldName = field.name;
                    String newName = remapFieldName(oldName, field.desc);
                    if (!newName.equals(oldName)) {
                        field.name = newName;
                        LOGGER.debug("Renamed @Shadow field: {} -> {}", oldName, newName);
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    /** A renamed shadow is still referenced through the mixin class until Mixin merges it. */
    private static void rewriteShadowFieldReferences(
            ClassNode classNode, List<ShadowFieldRename> renames) {
        for (MethodNode method : classNode.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.getFirst();
                    insn != null; insn = insn.getNext()) {
                if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode fieldInsn)
                        || !classNode.name.equals(fieldInsn.owner)) {
                    continue;
                }
                for (ShadowFieldRename rename : renames) {
                    if (rename.oldName().equals(fieldInsn.name)
                            && rename.descriptor().equals(fieldInsn.desc)) {
                        fieldInsn.name = rename.newName();
                        break;
                    }
                }
            }
        }
    }

    private record ShadowFieldRename(String oldName, String newName, String descriptor) {}
    
    /**
     * Redirect a class name if needed.
     */
    private String redirectClassName(String className) {
        String internal = className.replace('.', '/');
        String redirected = transformer.getClassRedirects().getOrDefault(internal, internal);
        // preserve the input's separator style
        return className.contains(".") ? redirected.replace('/', '.') : redirected;
    }
    
    /**
     * Redirect a method target reference.
     * Handles various Mixin target formats:
     * - "methodName" (simple)
     * - "methodName(Largs;)Lreturn;" (with descriptor)
     * - "Lowner;methodName(Largs;)Lreturn;" (full reference)
     */
    private String redirectMethodTarget(String target) {
        String direct = methodTargetRedirects.get(target);
        if (direct != null) {
            return remapDescriptorClasses(direct);
        }

        // owner-qualified form: [Lowner;]methodName[(descriptor)] or the FIELD form
        // [Lowner;]fieldName:Ltype; (a @At(value="FIELD") / @Redirect field target)
        if (target.startsWith("L") && target.contains(";")) {
            int semiIdx = target.indexOf(';');
            String owner = target.substring(1, semiIdx);
            String rest = target.substring(semiIdx + 1);

            String newOwner = transformer.getClassRedirects().getOrDefault(owner, owner);

            int descIdx = rest.indexOf('(');
            // FIELD selector: "fieldName:Ltype;" (a ':' with no method descriptor). This was
            // previously parsed as a METHOD name, so the whole "field_46283:Lnet/minecraft/
            // class_2248;" tail survived unmapped while the method selector in the same mixin
            // was rewritten (#157, nekomasfixed's BlocksMixin).
            int colonIdx = rest.indexOf(':');
            if (descIdx < 0 && colonIdx >= 0) {
                String fieldName = rest.substring(0, colonIdx);
                String fieldDesc = remapDescriptorClasses(rest.substring(colonIdx + 1));
                String newField = transformer.remapQualifiedFieldName(
                        newOwner, fieldName, fieldDesc);
                return "L" + newOwner + ";" + newField + ":" + fieldDesc;
            }
            String methodName = descIdx >= 0 ? rest.substring(0, descIdx) : rest;
            String desc = descIdx >= 0 ? rest.substring(descIdx) : "";

            desc = remapDescriptorClasses(desc);
            String newMethod = transformer.remapQualifiedMethodName(
                    newOwner, remapMethodName(methodName), desc);

            return "L" + newOwner + ";" + newMethod + desc;
        }

        // name with descriptor, no owner
        int descIdx = target.indexOf('(');
        if (descIdx >= 0) {
            String methodName = target.substring(0, descIdx);
            String desc = target.substring(descIdx);

            // never rename constructors/static initializers
            desc = remapDescriptorClasses(desc);
            String newMethod = methodName.startsWith("<")
                ? methodName
                : remapBareMethodName(methodName, desc);
            return newMethod + desc;
        }

        // bare name (never rename constructors)
        if (target.startsWith("<")) return target;
        return remapBareMethodName(target);
    }

    /** Remap an owner-qualified selector stored in a refmap resource. */
    public String remapResourceSelector(String target) {
        return remapResourceSelectorWithRepair(target).selector();
    }

    /** A resource selector after redirects, with an optional exact host-signature repair. */
    public record ResourceSelectorResult(String selector,
            AutomaticMixinTranslator.ResourceSelectorRepair repair) {}

    /** Remaps one refmap value while retaining the proof needed for its handler class. */
    public ResourceSelectorResult remapResourceSelectorWithRepair(String target) {
        String remapped = redirectMethodTarget(target);
        AutomaticMixinTranslator automatic =
                new AutomaticMixinTranslator(transformer.getFuzzyResolver());
        AutomaticMixinTranslator.ResourceSelectorRepair repair =
                automatic.planResourceSelector(remapped).orElse(null);
        return new ResourceSelectorResult(
                repair != null ? repair.replacement() : remapped, repair);
    }

    /**
     * Remaps a selector without an owner. Ordinary redirects take priority; same-owner Mojang
     * renames are safe only when the mixin declares one target.
     */
    private String remapBareMethodName(String name) {
        return remapBareMethodName(name, "");
    }

    private String remapBareMethodName(String name, String descriptor) {
        String r = remapMethodName(name);
        Set<String> targetOwners = currentTargetOwners.get();
        if (targetOwners.size() == 1 && !descriptor.isEmpty()) {
            String qualified = transformer.remapQualifiedMethodName(
                    targetOwners.iterator().next(), r, descriptor);
            if (!qualified.equals(r)) return qualified;
        }
        if (!r.equals(name)) return r;
        return scopedRename(name).orElse(name);
    }

    /**
     * Finds a same-owner rename for a single-target mixin.
     */
    private Optional<String> scopedRename(String name) {
        Set<String> targetOwners = currentTargetOwners.get();
        if (targetOwners.size() != 1) {
            return Optional.empty();
        }
        Map<String, String> renames = ownerScopedRenames.get(targetOwners.iterator().next());
        if (renames == null) {
            return Optional.empty();
        }
        String renamed = renames.get(name);
        if (renamed == null || AMBIGUOUS_RENAME.equals(renamed)) {
            return Optional.empty();
        }
        return Optional.of(renamed);
    }

    /**
     * Remap a method name using shim redirects first, then intermediary→Mojang mappings.
     */
    private String remapMethodName(String methodName) {
        String redirected = methodTargetRedirects.get(methodName);
        if (redirected != null) return redirected;

        if (methodName.startsWith("method_")) {
            Map<String, String> intermediaryMethods = transformer.getIntermediaryMethodNames();
            String mojang = intermediaryMethods.get(methodName);
            if (mojang != null) return mojang;
        }

        return methodName;
    }

    /**
     * Remap a field name using intermediary→Mojang mappings and shim field redirects.
     */
    private String remapFieldName(String fieldName) {
        return remapFieldName(fieldName, null);
    }

    private String remapFieldName(String fieldName, String descriptor) {
        // A bare @Shadow or @Accessor name belongs to the mixin target. Resolve it with that
        // owner so an unrelated field with the same short name cannot leak into the mixin.
        Set<String> targetOwners = currentTargetOwners.get();
        if (targetOwners.size() == 1) {
            String owner = targetOwners.iterator().next();
            return transformer.remapQualifiedFieldName(owner, fieldName, descriptor);
        }

        // Multiple-target mixins do not provide enough ownership information for a field
        // redirect, but an intermediary short name is still globally identifiable here.
        if (fieldName.startsWith("field_")) {
            String mojang = transformer.getIntermediaryFieldNames().get(fieldName);
            if (mojang != null) return mojang;
        }
        return fieldName;
    }

    /**
     * Remap intermediary class references within a descriptor string.
     * E.g. "(Lnet/minecraft/class_542;)V" → "(Lnet/minecraft/client/main/GameConfig;)V"
     */
    private String remapDescriptorClasses(String descriptor) {
        if (descriptor == null || descriptor.indexOf('L') < 0) return descriptor;

        Map<String, String> classRedirects = transformer.getClassRedirects();
        StringBuilder result = new StringBuilder(descriptor.length());
        int i = 0;
        while (i < descriptor.length()) {
            if (descriptor.charAt(i) == 'L') {
                int semi = descriptor.indexOf(';', i);
                if (semi > 0) {
                    String className = descriptor.substring(i + 1, semi);
                    String remapped = classRedirects.getOrDefault(className, className);
                    result.append('L').append(remapped).append(';');
                    i = semi + 1;
                } else {
                    result.append(descriptor.charAt(i));
                    i++;
                }
            } else {
                result.append(descriptor.charAt(i));
                i++;
            }
        }
        return result.toString();
    }
    
    /**
     * Transform a refmap.json file (dev names to obfuscated names). Refmap format varies
     * by Mixin version; not yet implemented, returns the input unchanged.
     */
    public String transformRefmap(String refmapJson) {
        return refmapJson;
    }
    
    /**
     * Transform a Mixin config JSON file.
     * Strips mixin entries that reference classes with broken targets
     * (removed methods, removed inner classes, etc) that would crash
     * the mixin system during application.
     *
     * @param configJson the mixin config JSON string
     * @param classDataLookup a function to get class bytes by internal name (package/Class),
     *                        or null if class analysis is not available
     * @return the transformed JSON with broken mixins stripped
     */
    public String transformMixinConfig(String configJson, Map<String, byte[]> classDataLookup) {
        if (classDataLookup == null || classDataLookup.isEmpty()) {
            return configJson;
        }

        String packagePrefix = extractJsonString(configJson, "package");
        if (packagePrefix == null) {
            return configJson;
        }

        String packagePath = packagePrefix.replace('.', '/');

        configJson = stripBrokenMixinEntries(configJson, "mixins", packagePath, classDataLookup);
        configJson = stripBrokenMixinEntries(configJson, "client", packagePath, classDataLookup);
        configJson = stripBrokenMixinEntries(configJson, "server", packagePath, classDataLookup);

        return configJson;
    }

    /**
     * Convenience overload for when no class data is available.
     */
    public String transformMixinConfig(String configJson) {
        return configJson;
    }

    /**
     * Process the named mixin array per class: (1) relocate annotation targets via redirect
     * maps, (2) partial-strip methods that reference removed APIs, (3) full-strip only when
     * every handler is broken or the class itself can't load.
     */
    private String stripBrokenMixinEntries(String json, String arrayKey, String packagePath,
                                            Map<String, byte[]> classDataLookup) {
        // "client": ["entry1", "entry2", ...]
        Pattern arrayPattern = Pattern.compile(
            "\"" + arrayKey + "\"\\s*:\\s*\\[([^\\]]*)]",
            Pattern.DOTALL
        );

        Matcher matcher = arrayPattern.matcher(json);
        if (!matcher.find()) {
            return json;
        }

        String arrayContent = matcher.group(1);

        Pattern entryPattern = Pattern.compile("\"([^\"]+)\"");
        Matcher entryMatcher = entryPattern.matcher(arrayContent);

        List<String> validEntries = new ArrayList<>();
        List<String> removedEntries = new ArrayList<>();
        int relocated = 0;
        int partiallyStripped = 0;

        while (entryMatcher.find()) {
            String mixinClassName = entryMatcher.group(1);
            String fullClassPath = packagePath + "/" + mixinClassName.replace('.', '/');

            byte[] classData = classDataLookup.get(fullClassPath + ".class");
            if (classData == null) {
                classData = classDataLookup.get(fullClassPath);
            }

            if (classData == null) {
                // no class data: keep it and let the mixin system handle it
                validEntries.add(mixinClassName);
                continue;
            }

            // phase 1: relocate (rewrite targets via redirect maps)
            byte[] relocatedData = relocateMixinClass(classData, fullClassPath);
            boolean wasRelocated = (relocatedData != classData);

            // phase 2: partial strip (remove individual broken methods)
            PartialStripResult stripResult = partialStripMixin(
                wasRelocated ? relocatedData : classData, fullClassPath);

            if (stripResult.allBroken && !stripResult.isAccessorMixin) {
                // phase 3: full strip. Never strip accessor/invoker interfaces: that causes
                // IllegalClassLoadError when code references the mixin directly. With
                // required=false they fail to apply instead.
                removedEntries.add(mixinClassName);
                LOGGER.warn("Fully stripping mixin '{}' (all targets removed/broken)", mixinClassName);
            } else {
                validEntries.add(mixinClassName);

                // write the updated class back to the lookup so it lands in the JAR
                byte[] finalData = stripResult.modifiedData != null ? stripResult.modifiedData :
                                   (wasRelocated ? relocatedData : classData);
                classDataLookup.put(fullClassPath + ".class", finalData);

                if (wasRelocated) {
                    relocated++;
                    LOGGER.info("Relocated mixin '{}' targets to new API names", mixinClassName);
                }
                if (stripResult.strippedMethods > 0) {
                    partiallyStripped++;
                    LOGGER.info("Partially stripped mixin '{}': removed {} broken method(s), kept {} working",
                        mixinClassName, stripResult.strippedMethods, stripResult.keptMethods);
                }
            }
        }

        if (removedEntries.isEmpty() && relocated == 0 && partiallyStripped == 0) {
            return json;
        }

        if (relocated > 0) {
            LOGGER.info("Relocated {} mixin(s) in '{}' array to use updated targets", relocated, arrayKey);
        }
        if (partiallyStripped > 0) {
            LOGGER.info("Partially stripped {} mixin(s) in '{}' array (removed broken methods, kept working ones)",
                partiallyStripped, arrayKey);
        }
        if (!removedEntries.isEmpty()) {
            LOGGER.info("Fully stripped {} mixin(s) from '{}' array: {}", removedEntries.size(), arrayKey, removedEntries);
        }

        StringBuilder newArray = new StringBuilder("\"" + arrayKey + "\": [");
        for (int i = 0; i < validEntries.size(); i++) {
            if (i > 0) newArray.append(",");
            newArray.append("\n    \"").append(validEntries.get(i)).append("\"");
        }
        if (!validEntries.isEmpty()) {
            newArray.append("\n  ");
        }
        newArray.append("]");

        return json.substring(0, matcher.start()) + newArray + json.substring(matcher.end());
    }

    /**
     * Result of partial mixin stripping.
     */
    private record PartialStripResult(
        boolean allBroken,      // true if ALL mixin methods are broken → full strip
        byte[] modifiedData,    // modified class bytes (null if no changes)
        int strippedMethods,    // number of methods removed
        int keptMethods,        // number of methods preserved
        boolean isAccessorMixin // true if mixin is an interface (@Accessor/@Invoker only)
    ) {
        PartialStripResult(boolean allBroken, byte[] modifiedData, int strippedMethods, int keptMethods) {
            this(allBroken, modifiedData, strippedMethods, keptMethods, false);
        }
    }

    /**
     * Relocate a mixin class by rewriting its annotation targets via the redirect maps
     * (method renames, class renames, descriptor updates). Returns the original data when
     * no relocation was needed.
     */
    private byte[] relocateMixinClass(byte[] classData, String className) {
        try {
            byte[] result = transformMixinClass(classData);
            if (result != classData) {
                LOGGER.debug("Relocated mixin targets in {}", className);
            }
            return result;
        } catch (Exception e) {
            LOGGER.debug("Failed to relocate mixin {}: {}", className, e.getMessage());
            return classData;
        }
    }

    /**
     * Remove individual broken methods from a mixin while keeping working ones, so the mod
     * keeps the handlers that don't reference removed APIs.
     */
    private PartialStripResult partialStripMixin(byte[] classData, String className) {
        try {
            ClassReader reader = new ClassReader(classData);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            if (!isMixinClass(classNode)) {
                return new PartialStripResult(false, null, 0, classNode.methods.size());
            }

            List<MethodNode> brokenMethods = new ArrayList<>();
            List<MethodNode> workingMethods = new ArrayList<>();

            for (MethodNode method : classNode.methods) {
                if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
                    workingMethods.add(method);
                    continue;
                }

                if (isMethodBroken(method, classNode)) {
                    brokenMethods.add(method);
                } else {
                    workingMethods.add(method);
                }
            }

            if (brokenMethods.isEmpty()) {
                // nothing broken: check class-level issues (superclass, @Shadow fields)
                if (hasClassLevelBreakage(classNode)) {
                    boolean isAccessor = (classNode.access & Opcodes.ACC_INTERFACE) != 0;
                    return new PartialStripResult(true, null, 0, 0, isAccessor);
                }
                return new PartialStripResult(false, null, 0, workingMethods.size());
            }

            long workingHandlers = workingMethods.stream()
                .filter(m -> !m.name.equals("<init>") && !m.name.equals("<clinit>"))
                .count();

            if (workingHandlers == 0) {
                // all handlers broken: full strip (interface accessor mixins flagged separately)
                boolean isAccessor = (classNode.access & Opcodes.ACC_INTERFACE) != 0;
                return new PartialStripResult(true, null, brokenMethods.size(), 0, isAccessor);
            }

            for (MethodNode broken : brokenMethods) {
                classNode.methods.remove(broken);
                LOGGER.debug("Stripped broken method '{}' from mixin {}", broken.name, className);
            }

            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            byte[] modifiedData = writer.toByteArray();

            return new PartialStripResult(false, modifiedData, brokenMethods.size(), (int) workingHandlers);

        } catch (Exception e) {
            LOGGER.warn("Failed to partial-strip mixin {}: {}", className, e.getMessage());
            return new PartialStripResult(false, null, 0, 0);
        }
    }

    /** Whether a mixin method references a removed/unresolved method, field, or class. */
    private boolean isMethodBroken(MethodNode method, ClassNode classNode) {
        // bytecode references to removed methods/fields/classes
        if (method.instructions != null) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode methodInsn) {
                    String ref = methodInsn.owner + "." + methodInsn.name;
                    if (isKnownRemovedMethod(ref)) return true;
                } else if (insn instanceof FieldInsnNode fieldInsn) {
                    String ref = fieldInsn.owner + "." + fieldInsn.name;
                    if (isKnownRemovedField(ref)) return true;
                } else if (insn instanceof TypeInsnNode typeInsn) {
                    if (isKnownRemovedClass(typeInsn.desc)) return true;
                }
            }
        }

        // mixin annotation targets (visible and invisible)
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode ann : annotations) {
                if (INJECT_DESC.equals(ann.desc) || REDIRECT_DESC.equals(ann.desc) ||
                    MODIFY_ARG_DESC.equals(ann.desc) || MODIFY_VAR_DESC.equals(ann.desc)) {
                    List<String> targets = extractAnnotationMethodTargets(ann);
                    for (String target : targets) {
                        if (isKnownRemovedMethod("." + target)) return true;
                        // intermediary name not in our mapping
                        if (hasUnresolvedIntermediaryName(target)) return true;
                    }
                    List<String> atTargets = extractAtTargets(ann);
                    for (String atTarget : atTargets) {
                        if (isKnownRemovedMethod(atTarget.replace(";", "."))) return true;
                        if (hasUnresolvedIntermediaryName(atTarget)) return true;
                    }
                }
                if (OVERWRITE_DESC.equals(ann.desc)) {
                    if (isKnownRemovedMethod("." + method.name)) return true;
                }
                if (SHADOW_DESC.equals(ann.desc)) {
                    if (method.name.startsWith("method_") || method.name.startsWith("field_")) {
                        return true;
                    }
                }
                if (ACCESSOR_DESC.equals(ann.desc) || INVOKER_DESC.equals(ann.desc)) {
                    if (ann.values != null) {
                        for (int ai = 0; ai < ann.values.size(); ai += 2) {
                            if ("value".equals(ann.values.get(ai)) && ann.values.get(ai + 1) instanceof String val) {
                                if (val.startsWith("method_") || val.startsWith("field_")) {
                                    return true;
                                }
                            }
                        }
                    }
                    // return type referencing a removed class
                    if (method.desc != null && method.desc.contains("class_")) {
                        return true;
                    }
                    // a polyfill type in the descriptor means the original type changed,
                    // so the accessor won't match the target field's new type
                    if (method.desc != null && method.desc.contains("com/retromod/polyfill/")) {
                        return true;
                    }
                }
            }
        }

        // return type referencing a removed class
        if (method.desc != null) {
            int retIdx = method.desc.lastIndexOf(')');
            if (retIdx >= 0) {
                String retType = method.desc.substring(retIdx + 1);
                if (retType.startsWith("L") && retType.endsWith(";")) {
                    String retClass = retType.substring(1, retType.length() - 1);
                    if (isKnownRemovedClass(retClass)) return true;
                }
            }
        }

        return false;
    }

    /** Class-level breakage affecting the whole mixin (removed superclass, @Shadow on removed targets). */
    private boolean hasClassLevelBreakage(ClassNode classNode) {
        if (classNode.superName != null && isKnownRemovedClass(classNode.superName)) {
            return true;
        }

        // @Shadow fields referencing removed types (visible and invisible)
        for (FieldNode field : classNode.fields) {
            for (List<AnnotationNode> annotations : List.of(
                    field.visibleAnnotations != null ? field.visibleAnnotations : List.<AnnotationNode>of(),
                    field.invisibleAnnotations != null ? field.invisibleAnnotations : List.<AnnotationNode>of())) {
                for (AnnotationNode ann : annotations) {
                    if (SHADOW_DESC.equals(ann.desc)) {
                        if (isKnownRemovedField("." + field.name)) return true;
                        if (field.name.startsWith("field_")) return true; // unresolved intermediary
                        if (field.desc != null && field.desc.startsWith("L") && field.desc.endsWith(";")) {
                            String fieldType = field.desc.substring(1, field.desc.length() - 1);
                            if (isKnownRemovedClass(fieldType)) return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Check if a mixin class has broken references that would crash the mixin system.
     * Analyzes the class bytecode for references to removed methods, fields, or inner classes.
     * Also checks mixin annotation targets (@Inject method=, @Redirect target=, @Overwrite, @Shadow)
     * against known-removed and known-renamed APIs.
     */
    private boolean isMixinBroken(byte[] classData, String className) {
        try {
            ClassReader reader = new ClassReader(classData);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            if (!isMixinClass(classNode)) {
                return false;
            }

            Set<String> referencedClasses = new HashSet<>();
            Set<String> referencedMethods = new HashSet<>();
            Set<String> referencedFields = new HashSet<>();

            // scan bytecode for class/method/field references
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;

                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode methodInsn) {
                        referencedMethods.add(methodInsn.owner + "." + methodInsn.name + methodInsn.desc);
                        referencedClasses.add(methodInsn.owner);
                    } else if (insn instanceof FieldInsnNode fieldInsn) {
                        referencedFields.add(fieldInsn.owner + "." + fieldInsn.name);
                        referencedClasses.add(fieldInsn.owner);
                    } else if (insn instanceof TypeInsnNode typeInsn) {
                        referencedClasses.add(typeInsn.desc);
                    }
                }
            }

            // @Shadow field types
            for (FieldNode field : classNode.fields) {
                if (field.desc != null && field.desc.startsWith("L") && field.desc.endsWith(";")) {
                    String refClass = field.desc.substring(1, field.desc.length() - 1);
                    referencedClasses.add(refClass);
                }
            }

            // method return types
            for (MethodNode method : classNode.methods) {
                if (method.desc != null) {
                    int retIdx = method.desc.lastIndexOf(')');
                    if (retIdx >= 0) {
                        String retType = method.desc.substring(retIdx + 1);
                        if (retType.startsWith("L") && retType.endsWith(";")) {
                            String retClass = retType.substring(1, retType.length() - 1);
                            referencedClasses.add(retClass);
                        }
                    }
                }
            }

            // mixin annotation targets: @Inject(method=), @Redirect(target=),
            // @Overwrite/@Shadow with old names

            for (MethodNode method : classNode.methods) {
                if (method.visibleAnnotations != null) {
                    for (AnnotationNode ann : method.visibleAnnotations) {
                        if (OVERWRITE_DESC.equals(ann.desc)) {
                            // for @Overwrite the method name is the target
                            if (isKnownRemovedMethod("." + method.name)) {
                                LOGGER.debug("Mixin {} has @Overwrite on removed method: {}", className, method.name);
                                return true;
                            }
                        }
                    }
                }
            }

            for (MethodNode method : classNode.methods) {
                if (method.visibleAnnotations == null) continue;
                for (AnnotationNode ann : method.visibleAnnotations) {
                    if (INJECT_DESC.equals(ann.desc) || REDIRECT_DESC.equals(ann.desc) ||
                        MODIFY_ARG_DESC.equals(ann.desc) || MODIFY_VAR_DESC.equals(ann.desc)) {
                        List<String> targets = extractAnnotationMethodTargets(ann);
                        for (String target : targets) {
                            if (isKnownRemovedMethod("." + target)) {
                                LOGGER.debug("Mixin {} @Inject/@Redirect targets removed method: {}", className, target);
                                return true;
                            }
                        }
                        List<String> atTargets = extractAtTargets(ann);
                        for (String atTarget : atTargets) {
                            if (isKnownRemovedMethod(atTarget.replace(";", "."))) {
                                LOGGER.debug("Mixin {} @At targets removed method: {}", className, atTarget);
                                return true;
                            }
                        }
                    }
                }
            }

            // @Shadow fields by name
            for (FieldNode field : classNode.fields) {
                if (field.visibleAnnotations != null) {
                    for (AnnotationNode ann : field.visibleAnnotations) {
                        if (SHADOW_DESC.equals(ann.desc)) {
                            if (isKnownRemovedField("." + field.name)) {
                                LOGGER.debug("Mixin {} has @Shadow on removed field: {}", className, field.name);
                                return true;
                            }
                        }
                    }
                }
            }

            // referenced classes/methods/fields against the known-removed registries
            for (String refClass : referencedClasses) {
                if (isKnownRemovedClass(refClass)) {
                    LOGGER.debug("Mixin {} references removed class: {}", className, refClass);
                    return true;
                }
            }

            for (String refMethod : referencedMethods) {
                if (isKnownRemovedMethod(refMethod)) {
                    LOGGER.debug("Mixin {} references removed method: {}", className, refMethod);
                    return true;
                }
            }

            for (String refField : referencedFields) {
                if (isKnownRemovedField(refField)) {
                    LOGGER.debug("Mixin {} references removed field: {}", className, refField);
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            LOGGER.warn("Failed to analyze mixin class {}: {}", className, e.getMessage());
            return false;
        }
    }

    /**
     * Extract method target strings from @Inject/@Redirect annotations.
     */
    private List<String> extractAnnotationMethodTargets(AnnotationNode annotation) {
        List<String> targets = new ArrayList<>();
        if (annotation.values == null) return targets;

        for (int i = 0; i < annotation.values.size(); i += 2) {
            String key = (String) annotation.values.get(i);
            Object value = annotation.values.get(i + 1);

            if ("method".equals(key)) {
                if (value instanceof List<?> methods) {
                    for (Object m : methods) {
                        if (m instanceof String s) {
                            // Strip descriptor if present: "methodName(Largs;)V" -> "methodName"
                            int descIdx = s.indexOf('(');
                            targets.add(descIdx >= 0 ? s.substring(0, descIdx) : s);
                        }
                    }
                } else if (value instanceof String s) {
                    int descIdx = s.indexOf('(');
                    targets.add(descIdx >= 0 ? s.substring(0, descIdx) : s);
                }
            }
        }
        return targets;
    }

    /**
     * Extract @At target strings from an injection annotation.
     */
    private List<String> extractAtTargets(AnnotationNode annotation) {
        List<String> targets = new ArrayList<>();
        if (annotation.values == null) return targets;

        for (int i = 0; i < annotation.values.size(); i += 2) {
            String key = (String) annotation.values.get(i);
            Object value = annotation.values.get(i + 1);

            if ("at".equals(key)) {
                if (value instanceof AnnotationNode at) {
                    String target = extractAtTarget(at);
                    if (target != null) targets.add(target);
                } else if (value instanceof List<?> ats) {
                    for (Object at : ats) {
                        if (at instanceof AnnotationNode atNode) {
                            String target = extractAtTarget(atNode);
                            if (target != null) targets.add(target);
                        }
                    }
                }
            }
        }
        return targets;
    }

    /**
     * Extract the target string from a single @At annotation.
     */
    private String extractAtTarget(AnnotationNode at) {
        if (at.values == null) return null;
        for (int i = 0; i < at.values.size(); i += 2) {
            String key = (String) at.values.get(i);
            if ("target".equals(key) && at.values.get(i + 1) instanceof String s) {
                return s;
            }
        }
        return null;
    }

    // classes/methods removed between MC versions; referencing them crashes mixin application
    private static final Set<String> KNOWN_REMOVED_CLASSES = new HashSet<>();
    private static final Set<String> KNOWN_REMOVED_METHODS = new HashSet<>();
    private static final Set<String> KNOWN_REMOVED_FIELDS = new HashSet<>();

    static {
        // BufferBuilder inner class BuiltBuffer: removed in rendering rewrite
        KNOWN_REMOVED_CLASSES.add("net/minecraft/class_287$class_7433");

        // SimpleOptionsSubScreen: removed
        KNOWN_REMOVED_CLASSES.add("net/minecraft/class_5500");

        // Various removed screen/GUI classes
        KNOWN_REMOVED_CLASSES.add("net/minecraft/class_442"); // SocialInteractionsScreen in some versions
    }

    static {
        // MinecraftClient.scheduledTasks Queue: removed
        KNOWN_REMOVED_FIELDS.add("net/minecraft/class_310.field_17404");

        // BufferBuilder.building flag: removed in rendering rewrite
        KNOWN_REMOVED_FIELDS.add("net/minecraft/class_287.field_1556");

        // Mouse.cursorLocked: removed
        KNOWN_REMOVED_FIELDS.add("net/minecraft/class_315.field_1866");
    }

    static {
        // BufferBuilder.end() / build(): removed in rendering rewrite
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_287.method_1326");

        // MinecraftClient removed methods
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_310.method_18858");
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_310.method_16901");

        // MinecraftClient.getFramerateLimit(): removed (Dynamic FPS crash)
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_310.method_16009");

        // ReentrantThreadExecutor.send(): removed
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_4093.method_18858");
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_4093.method_16901");

        // Player.addAdditionalSaveData: removed (Carry On crash)
        KNOWN_REMOVED_METHODS.add("net/minecraft/class_1657.method_5652");

        // Note: method_569 (writeToFile), method_5647 (writeNbt), method_38244 (createNbt)
        // still exist with changed signatures. Redirect them instead of removing them.
    }

    /** Register a known-removed class (called during polyfill registration). */
    public static void registerRemovedClass(String internalName) {
        KNOWN_REMOVED_CLASSES.add(internalName);
    }

    /** Register a known-removed method (called during shim registration). */
    public static void registerRemovedMethod(String ownerAndName) {
        KNOWN_REMOVED_METHODS.add(ownerAndName);
    }

    private boolean isKnownRemovedClass(String internalName) {
        return KNOWN_REMOVED_CLASSES.contains(internalName);
    }

    private boolean isKnownRemovedField(String refField) {
        for (String removed : KNOWN_REMOVED_FIELDS) {
            if (refField.startsWith(removed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isKnownRemovedMethod(String refMethod) {
        // refMethod is "owner.nameDesc"; the entries are "owner.name" prefixes
        for (String removed : KNOWN_REMOVED_METHODS) {
            if (refMethod.startsWith(removed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a method target still contains an intermediary name. A surviving
     * method_XXXX/class_XXXX means the target was removed from Minecraft and the
     * reference is broken.
     */
    private boolean hasUnresolvedIntermediaryName(String target) {
        if (target == null) return false;
        if (target.contains("class_")) return true;
        int descIdx = target.indexOf('(');
        String methodPart;
        if (target.contains(";") && target.startsWith("L")) {
            // full reference: Lowner;methodName(desc)
            int semiIdx = target.indexOf(';');
            methodPart = descIdx >= 0 ? target.substring(semiIdx + 1, descIdx) : target.substring(semiIdx + 1);
        } else {
            methodPart = descIdx >= 0 ? target.substring(0, descIdx) : target;
        }
        return methodPart.startsWith("method_") || methodPart.startsWith("field_");
    }

    /**
     * Extract a string value from JSON by key name.
     */
    private String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
