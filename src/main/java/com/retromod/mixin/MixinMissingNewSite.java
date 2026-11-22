/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Lets an injector at a constructor call fail softly where the host's target method no longer makes that call. */
final class MixinMissingNewSite {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    private MixinMissingNewSite() {}

    /**
     * True when the mixin changed. An explicit {@code require = 1} at a {@code NEW} the host no longer has is a fatal
     * {@code InjectionError}, as Supplementaries' wrap of {@code new Item(...)} in {@code Items.<clinit>} is on 26.3,
     * where items are built inside {@code registerItem}. The Fabric path already softens a selector it rewrote.
     */
    static boolean apply(ClassNode mixin, Function<String, ClassNode> hostWithCode) {
        List<String> targets = targets(mixin);
        if (targets.isEmpty()) return false;
        boolean changed = false;
        for (MethodNode handler : mixin.methods) {
            if (handler.visibleAnnotations == null) continue;
            for (AnnotationNode injector : handler.visibleAnnotations) {
                int require = explicitRequireIndex(injector);
                if (require < 0) continue;
                NewSite site = newSite(injector);
                if (site == null || !missingEverywhere(site, targets, hostWithCode)) continue;
                injector.values.set(require, 0);
                changed = true;
                LOGGER.debug("Let {}.{} fail softly: no target method still constructs {}",
                        mixin.name, handler.name, site.owner());
            }
        }
        return changed;
    }

    /** The constructor call an injector selects, the method names it searches and the constant its slice starts at. */
    record NewSite(String owner, List<String> methods, String sliceFrom) {}

    /** The site, when every {@code at} is a {@code NEW} of one class and the methods are plain names. */
    static NewSite newSite(AnnotationNode injector) {
        List<String> methods = new ArrayList<>();
        for (Object selector : list(value(injector, "method"))) {
            if (!(selector instanceof String text)) return null;
            String name = methodName(text);
            if (name == null) return null;
            methods.add(name);
        }
        String owner = null;
        for (Object at : list(value(injector, "at"))) {
            if (!(at instanceof AnnotationNode node) || !"NEW".equals(value(node, "value"))) return null;
            if (!(value(node, "target") instanceof String target)) return null;
            String built = newOwner(target);
            if (built == null || owner != null && !owner.equals(built)) return null;
            owner = built;
        }
        if (owner == null || methods.isEmpty()) return null;
        return new NewSite(owner, methods, sliceFrom(injector));
    }

    /**
     * Whether no target class's selected methods construct the site's class. It answers only on proof: the built class
     * must exist on the host and some target must declare a selected method, so a selector or target named in another
     * mapping, which a refmap resolves later, keeps the mod's require. An unreadable target counts as having the site.
     */
    private static boolean missingEverywhere(NewSite site, List<String> targets, Function<String, ClassNode> host) {
        if (host.apply(site.owner()) == null) return false;
        boolean selected = false;
        for (String target : targets) {
            ClassNode node = host.apply(target);
            if (node == null) return false;
            for (MethodNode method : node.methods) {
                if (!site.methods().contains(method.name)) continue;
                selected = true;
                if (constructs(method, site)) return false;
            }
        }
        return selected;
    }

    /** Whether the method builds the site's class after the slice's starting constant, with any constructor. */
    private static boolean constructs(MethodNode method, NewSite site) {
        if (method.instructions == null || method.instructions.size() == 0) return false;
        boolean inSlice = site.sliceFrom() == null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!inSlice && insn instanceof LdcInsnNode ldc && site.sliceFrom().equals(ldc.cst)) inSlice = true;
            // Any constructor counts, so a near match keeps the mod's own require.
            if (inSlice && insn instanceof TypeInsnNode type && type.desc.equals(site.owner())) return true;
        }
        return false;
    }

    /** The class a {@code NEW} target builds: {@code (args)Lowner;}, {@code Lowner;} or {@code owner}. */
    static String newOwner(String target) {
        if (target.startsWith("(")) {
            Type returned = Type.getReturnType(target);
            return returned.getSort() == Type.OBJECT ? returned.getInternalName() : null;
        }
        if (target.startsWith("L") && target.endsWith(";")) return target.substring(1, target.length() - 1);
        if (target.indexOf('(') < 0 && target.indexOf(';') < 0) return target.replace('.', '/');
        return null;
    }

    /** {@code name}, {@code name(desc)} or {@code Lowner;name(desc)} as a plain name; patterns and wildcards give null. */
    private static String methodName(String selector) {
        String name = selector;
        int owner = name.indexOf(';');
        if (name.startsWith("L") && owner > 0) name = name.substring(owner + 1);
        int desc = name.indexOf('(');
        if (desc >= 0) name = name.substring(0, desc);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(0, colon);
        if (name.isEmpty() || name.contains("*") || name.contains("/") || name.contains(" ")) return null;
        return name;
    }

    /** The string a {@code CONSTANT} slice start names, or null for no slice or any other shape. */
    private static String sliceFrom(AnnotationNode injector) {
        for (Object slice : list(value(injector, "slice"))) {
            if (!(slice instanceof AnnotationNode node) || !(value(node, "from") instanceof AnnotationNode from)) continue;
            if (!"CONSTANT".equals(value(from, "value"))) continue;
            for (Object arg : list(value(from, "args"))) {
                if (arg instanceof String text && text.startsWith("stringValue=")) return text.substring("stringValue=".length());
            }
        }
        return null;
    }

    /** The index of an explicit positive {@code require} value, or -1. */
    private static int explicitRequireIndex(AnnotationNode injector) {
        if (injector.values == null || value(injector, "at") == null) return -1;
        for (int i = 0; i + 1 < injector.values.size(); i += 2) {
            if ("require".equals(injector.values.get(i)) && injector.values.get(i + 1) instanceof Integer count
                    && count > 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The classes the {@code @Mixin} names, by class literal or by string. */
    private static List<String> targets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        AnnotationNode annotation = null;
        for (List<AnnotationNode> annotations : List.of(listOrEmpty(mixin.visibleAnnotations), listOrEmpty(mixin.invisibleAnnotations))) {
            for (AnnotationNode node : annotations) {
                if (MIXIN_DESC.equals(node.desc)) annotation = node;
            }
        }
        if (annotation == null) return targets;
        for (Object type : list(value(annotation, "value"))) {
            if (type instanceof Type t) targets.add(t.getInternalName());
        }
        for (Object name : list(value(annotation, "targets"))) {
            if (name instanceof String text) targets.add(text.replace('.', '/'));
        }
        return targets;
    }

    private static List<AnnotationNode> listOrEmpty(List<AnnotationNode> annotations) {
        return annotations == null ? List.of() : annotations;
    }

    private static Object value(AnnotationNode node, String key) {
        if (node.values == null) return null;
        for (int i = 0; i + 1 < node.values.size(); i += 2) {
            if (key.equals(node.values.get(i))) return node.values.get(i + 1);
        }
        return null;
    }

    private static List<?> list(Object value) {
        if (value == null) return List.of();
        return value instanceof List<?> list ? list : List.of(value);
    }
}
