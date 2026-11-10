/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.util.RetromodLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Drops the targets a mixin can no longer apply to because the host changed them between class and interface. */
final class MixinTargetKindRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    /** What the host says a target is. */
    enum Kind { CLASS, INTERFACE, UNKNOWN }

    private MixinTargetKindRepair() {}

    /** Removes each target of the wrong kind, and disables the mixin when none is left. True when the class changed. */
    static boolean apply(ClassNode mixin, Function<String, Kind> hostKind) {
        AnnotationNode annotation = mixinAnnotation(mixin);
        if (annotation == null || annotation.values == null) return false;
        boolean interfaceMixin = (mixin.access & Opcodes.ACC_INTERFACE) != 0;
        // Mixin accepts an accessor interface on either kind of target.
        if (interfaceMixin && accessorsOnly(mixin)) return false;
        Kind refused = interfaceMixin ? Kind.CLASS : Kind.INTERFACE;

        List<String> removed = new ArrayList<>();
        int kept = 0;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            Object key = annotation.values.get(i);
            if (!("value".equals(key) || "targets".equals(key)) || !(annotation.values.get(i + 1) instanceof List<?> targets)) {
                continue;
            }
            List<Object> remaining = new ArrayList<>();
            for (Object target : targets) {
                String name = targetName(target);
                if (name != null && hostKind.apply(name) == refused) {
                    removed.add(name);
                } else {
                    remaining.add(target);
                }
            }
            annotation.values.set(i + 1, remaining);
            kept += remaining.size();
        }
        if (removed.isEmpty()) return false;

        String kind = refused == Kind.INTERFACE ? "an interface" : "a class";
        String mixinKind = interfaceMixin ? "an interface mixin" : "a class mixin";
        if (kept == 0) {
            // A placeholder target keeps the class a valid mixin that Mixin finds nothing to apply to.
            MixinCompatibilityTransformer.neutralizeMixin(mixin);
            LOGGER.debug(RetromodLog.DETAIL, "Disabled Mixin {}: {} is now {} on this Minecraft version, which {} "
                    + "cannot change. Its changes to the game are not applied.", mixin.name, removed, kind, mixinKind);
        } else {
            LOGGER.debug(RetromodLog.DETAIL, "Removed {} from the targets of Mixin {}: now {}, which {} cannot change. "
                    + "Its other targets still apply.", removed, mixin.name, kind, mixinKind);
        }
        return true;
    }

    /** The internal name a {@code value} or {@code targets} entry names. */
    private static String targetName(Object target) {
        if (target instanceof Type type && type.getSort() == Type.OBJECT) return type.getInternalName();
        if (target instanceof String text && !text.isEmpty()) return text.replace('.', '/');
        return null;
    }

    private static AnnotationNode mixinAnnotation(ClassNode mixin) {
        for (List<AnnotationNode> annotations : List.of(
                mixin.invisibleAnnotations != null ? mixin.invisibleAnnotations : List.<AnnotationNode>of(),
                mixin.visibleAnnotations != null ? mixin.visibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN_DESC.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    /** Whether every method carries {@code @Accessor} or {@code @Invoker}, which makes Mixin treat the interface as an accessor. */
    private static boolean accessorsOnly(ClassNode mixin) {
        for (MethodNode method : mixin.methods) {
            if (!annotated(method.invisibleAnnotations) && !annotated(method.visibleAnnotations)) return false;
        }
        return true;
    }

    private static boolean annotated(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (ACCESSOR_DESC.equals(annotation.desc) || INVOKER_DESC.equals(annotation.desc)) return true;
        }
        return false;
    }
}
