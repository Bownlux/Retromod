/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Disables a class mixin on each target that no longer has a game member it shadows, so the target still loads. */
public final class MixinShadowGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String DEFAULT_PREFIX = "shadow$";
    private static final String RETROMOD_TYPE = "Lcom/retromod/";

    // JDK classes read once for their members and supertypes, since the host index holds only the game jar.
    private static final Map<String, Optional<ClassNode>> JDK_CLASSES = new ConcurrentHashMap<>();

    // The mod being translated and how many of its mixins the gate disabled, for one summary line per mod.
    private static volatile String currentMod;
    private static final AtomicInteger disabledInMod = new AtomicInteger();

    private MixinShadowGate() {}

    /** Starts counting for a new mod, reporting the one before it if a path did not. */
    public static void startMod(String modName) {
        finishMod();
        currentMod = modName;
    }

    /** Writes the summary line for the mod being translated, when the gate disabled any of its mixins. */
    public static void finishMod() {
        int disabled = disabledInMod.getAndSet(0);
        if (disabled > 0) {
            LOGGER.info("Disabled {} Mixin(s), or some of their targets, in {} that use a game field or method this Minecraft version no longer "
                    + "has. Their changes to the game are not applied. Set log_level to DEBUG for the list.",
                    disabled, currentMod == null ? "this mod" : currentMod);
        }
        currentMod = null;
    }

    /**
     * True when the mixin changed. It runs after every other mixin repair, so a shadow still missing here would
     * make Mixin throw while the target class loads, which takes the target down with it.
     */
    static boolean apply(ClassNode mixin, FuzzyMethodResolver host) {
        if (host == null || !host.isIndexed() || (mixin.access & Opcodes.ACC_INTERFACE) != 0) return false;
        AnnotationNode annotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN_DESC);
        // A mixin that keeps its names as written, or adds an interface to its target, is outside what the index proves.
        if (annotation == null || Boolean.FALSE.equals(value(annotation, "remap")) || addsInterface(mixin, annotation, host)) {
            return false;
        }
        List<String> failing = new ArrayList<>();
        for (String target : hostTargets(annotation, host)) {
            String missing = missingShadow(mixin, target, host);
            if (missing == null) continue;
            failing.add(target);
            LOGGER.debug(RetromodLog.DETAIL, "Mixin {} no longer applies to {}, which lacks {} that it shadows.",
                    mixin.name, target, missing);
        }
        if (failing.isEmpty()) return false;
        // Only the failing targets go; the mixin is pointed at a placeholder when none remain.
        if (removeTargets(annotation, failing) == 0 && !MixinCompatibilityTransformer.neutralizeMixin(mixin)) return false;
        disabledInMod.incrementAndGet();
        return true;
    }

    /** The first shadowed field or method the target lacks, as {@code name desc}, or null. */
    private static String missingShadow(ClassNode mixin, String target, FuzzyMethodResolver host) {
        List<String> ancestry = ancestry(target, host);
        // A target whose ancestry leaves the game jar and the JDK may inherit the member from a loader or library class.
        if (ancestry == null) return null;
        for (FieldNode field : mixin.fields) {
            if (lacks(mixin, field.name, field.desc, field.visibleAnnotations, field.invisibleAnnotations, ancestry, host, true)) {
                return field.name + " " + field.desc;
            }
        }
        for (MethodNode method : mixin.methods) {
            if (lacks(mixin, method.name, method.desc, method.visibleAnnotations, method.invisibleAnnotations, ancestry, host, false)) {
                return method.name + method.desc;
            }
        }
        return null;
    }

    /** Whether the member is a remapped {@code @Shadow} that no class in the ancestry declares. */
    private static boolean lacks(ClassNode mixin, String name, String desc, List<AnnotationNode> visible,
            List<AnnotationNode> invisible, List<String> ancestry, FuzzyMethodResolver host, boolean field) {
        AnnotationNode shadow = annotation(visible, invisible, SHADOW_DESC);
        if (shadow == null || Boolean.FALSE.equals(value(shadow, "remap"))) return false;
        List<String> names = names(shadow, name);
        if (!desc.contains(RETROMOD_TYPE)) {
            return names.stream().noneMatch(candidate -> declares(ancestry, host, candidate, desc, field));
        }
        // A Retromod stand-in in the type means an earlier pass rewrote it, so only the name can be judged.
        if (names.stream().noneMatch(candidate -> declares(ancestry, host, candidate, null, field))) return true;
        LOGGER.warn("Mixin {} shadows {} {}, whose type names a Retromod class that the game does not have. "
                + "Retromod left this Mixin as it is; please report it with the mod.", mixin.name, name, desc);
        return false;
    }

    /** Whether the target or a known ancestor declares the member; a null {@code desc} matches any descriptor. */
    private static boolean declares(List<String> ancestry, FuzzyMethodResolver host, String name, String desc, boolean field) {
        String target = ancestry.get(0);
        boolean indexed = desc == null
                ? (field ? host.hasFieldName(target, name) : host.hasMethodName(target, name))
                : (field ? host.hasField(target, name, desc) : host.hasMethod(target, name, desc));
        if (indexed) return true;
        for (String type : ancestry) {
            ClassNode jdk = jdkClass(type);
            if (jdk == null) continue;
            if (field ? jdk.fields.stream().anyMatch(f -> f.name.equals(name) && (desc == null || f.desc.equals(desc)))
                    : jdk.methods.stream().anyMatch(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))) {
                return true;
            }
        }
        return false;
    }

    /** The target and every ancestor, when each is in the indexed game jar or the JDK; null otherwise. */
    private static List<String> ancestry(String target, FuzzyMethodResolver host) {
        List<String> ancestry = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>(List.of(target));
        while (!work.isEmpty()) {
            String type = work.poll();
            if (!seen.add(type)) continue;
            ancestry.add(type);
            if (host.hasClass(type)) {
                work.addAll(host.directSupertypes(type));
                continue;
            }
            ClassNode jdk = jdkClass(type);
            if (jdk == null) return null;
            if (jdk.superName != null) work.add(jdk.superName);
            work.addAll(jdk.interfaces);
        }
        return ancestry;
    }

    /** A JDK class's outline, or null for any other class. */
    private static ClassNode jdkClass(String type) {
        if (!type.startsWith("java/") && !type.startsWith("javax/")) return null;
        return JDK_CLASSES.computeIfAbsent(type, name -> {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                if (in == null) return Optional.empty();
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return Optional.of(node);
            } catch (IOException | RuntimeException unreadable) {
                // A class file newer than the ASM in use throws; the ancestry is then unknown and the mixin is not judged.
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Whether the mixin implements an interface one of its host targets does not, which Mixin then adds to it. */
    private static boolean addsInterface(ClassNode mixin, AnnotationNode annotation, FuzzyMethodResolver host) {
        if (mixin.interfaces == null || mixin.interfaces.isEmpty()) return false;
        for (String target : hostTargets(annotation, host)) {
            for (String added : mixin.interfaces) {
                if (!host.isSubtypeOf(target, added)) return true;
            }
        }
        return false;
    }

    /** The names a shadow may match: its own without the prefix, and its aliases. */
    private static List<String> names(AnnotationNode shadow, String declared) {
        String prefix = value(shadow, "prefix") instanceof String text ? text : DEFAULT_PREFIX;
        List<String> names = new ArrayList<>();
        names.add(declared.startsWith(prefix) ? declared.substring(prefix.length()) : declared);
        if (value(shadow, "aliases") instanceof List<?> aliases) {
            for (Object alias : aliases) if (alias instanceof String text) names.add(text);
        }
        return names;
    }

    /** The mixin's targets the host owns and indexes; a mod's own class or an unknown one is never judged. */
    private static List<String> hostTargets(AnnotationNode annotation, FuzzyMethodResolver host) {
        List<String> targets = new ArrayList<>();
        for (String key : List.of("value", "targets")) {
            if (!(value(annotation, key) instanceof List<?> list)) continue;
            for (Object target : list) {
                String name = targetName(target);
                if (name != null && (name.startsWith("net/minecraft/") || name.startsWith("com/mojang/")) && host.hasClass(name)) {
                    targets.add(name);
                }
            }
        }
        return targets;
    }

    /** Removes the failing targets from {@code value} and {@code targets}, and returns how many targets remain. */
    private static int removeTargets(AnnotationNode annotation, List<String> failing) {
        int remaining = 0;
        for (String key : List.of("value", "targets")) {
            if (!(value(annotation, key) instanceof List<?> list)) continue;
            List<Object> kept = new ArrayList<>();
            for (Object target : list) if (!failing.contains(targetName(target))) kept.add(target);
            setValue(annotation, key, kept);
            remaining += kept.size();
        }
        return remaining;
    }

    private static String targetName(Object target) {
        return target instanceof Type type ? type.getInternalName()
                : target instanceof String text ? text.replace('.', '/') : null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static void setValue(AnnotationNode annotation, String key, Object newValue) {
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) annotation.values.set(i + 1, newValue);
        }
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        for (List<AnnotationNode> annotations : List.of(visible == null ? List.<AnnotationNode>of() : visible,
                invisible == null ? List.<AnnotationNode>of() : invisible)) {
            for (AnnotationNode annotation : annotations) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }
}
