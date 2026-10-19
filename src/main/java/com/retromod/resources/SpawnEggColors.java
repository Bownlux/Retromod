/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Reads the colors an old mod gave its spawn eggs from the constructor calls in its classes. */
final class SpawnEggColors {

    /** How far a color is followed back through helper methods and lambdas that pass it on. */
    private static final int MAX_FORWARDING = 4;

    private SpawnEggColors() {}

    /** Egg colors by {@code namespace:path} for {@code eggIds}: base, then spots, as opaque ARGB. */
    static Map<String, int[]> scan(Collection<String> classNames, Function<String, byte[]> read,
                                   Set<String> eggIds) {
        Map<String, int[]> colors = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        if (eggIds.isEmpty()) return colors;
        // Before 1.21.5 an egg item took two colors that tinted the shared egg template. They lived
        // only in code, so read every class that names an egg type.
        for (String name : classNames) {
            if (!name.endsWith(".class") || ClassFlow.isRetromodClass(name)) continue;
            byte[] bytes = read.apply(name);
            if (bytes == null || !mentionsSpawnEgg(bytes)) continue;
            ClassFlow flow = ClassFlow.parse(bytes);
            if (flow == null) continue;
            for (Found found : colorsIn(flow)) {
                // The statement that builds the egg names it, in its registration string or in
                // the field that holds it. Two different pairs for one egg make it a conflict.
                String id = itemFor(flow, found.site(), eggIds);
                if (id == null) continue;
                int[] previous = colors.putIfAbsent(id, found.colors());
                if (previous != null && !java.util.Arrays.equals(previous, found.colors())) {
                    conflicting.add(id);
                }
            }
        }
        // A conflicting egg keeps the neutral look rather than a guess.
        conflicting.forEach(colors::remove);
        return colors;
    }

    /** Whether a class names a spawn egg type: vanilla's in Mojang or intermediary names, or a loader's. */
    static boolean isSpawnEggClass(String owner) {
        String simple = owner.substring(owner.lastIndexOf('/') + 1);
        simple = simple.substring(simple.lastIndexOf('$') + 1);
        // Covers Forge's ForgeSpawnEggItem and NeoForge's DeferredSpawnEggItem. class_1826 is the
        // intermediary name of vanilla's SpawnEggItem.
        return simple.endsWith("SpawnEggItem") || simple.equals("class_1826");
    }

    private static boolean isConstructor(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>");
    }

    /** Retromod's stand-in for the removed {@code SpawnEggItem(type, base, spots, properties)}. */
    private static boolean isLegacyEggFactory(MethodInsnNode call) {
        // The class pass has already put this call in place of the old constructor by the time
        // this reads the class.
        String bridge = com.retromod.shim.common.LegacySpawnEggBridge.INTERNAL;
        return call.getOpcode() == Opcodes.INVOKESTATIC && call.name.equals("create")
                && call.owner.endsWith(bridge.substring(bridge.lastIndexOf('/')));
    }

    /** A quick text check, before parsing, that the class names an egg type at all. */
    private static boolean mentionsSpawnEgg(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        return text.contains("SpawnEggItem") || text.contains("class_1826");
    }

    private record Found(ClassFlow.Site site, int[] colors) {}

    /** Every egg constructor call with two constant colors, followed through helpers that forward them. */
    private static Set<Found> colorsIn(ClassFlow flow) {
        Set<Found> found = new LinkedHashSet<>();
        for (MethodNode method : flow.node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !isSpawnEggClass(call.owner)
                        || !(isConstructor(call) || isLegacyEggFactory(call))) {
                    continue;
                }
                // For example new SpawnEggItem(type, 0x3B6B2D, 0x9C2B2B, ...). Loader egg items
                // take a type supplier in place of the type.
                int first = firstIntPair(call.desc);
                if (first < 0) continue;
                follow(flow, new ClassFlow.Site(method, call, 0), first, first + 1, 0, found);
            }
        }
        return found;
    }

    /** Follows the color arguments at {@code primary} and {@code secondary} of the call at {@code site}. */
    private static void follow(ClassFlow flow, ClassFlow.Site site, int primary, int secondary,
                               int depth, Set<Found> found) {
        SourceValue base = flow.argument(site.method(), site.insn(), primary + site.offset());
        SourceValue spots = flow.argument(site.method(), site.insn(), secondary + site.offset());
        Integer baseColor = ClassFlow.intConstant(base);
        Integer spotColor = ClassFlow.intConstant(spots);
        // Two constants make a color pair.
        if (baseColor != null && spotColor != null) {
            found.add(new Found(site, new int[] {opaque(baseColor), opaque(spotColor)}));
            return;
        }
        // Two parameters send the search to the callers of the method holding the call, and to
        // the places that use it as a lambda.
        int baseParameter = ClassFlow.parameter(site.method(), base);
        int spotParameter = ClassFlow.parameter(site.method(), spots);
        if (baseParameter < 0 || spotParameter < 0 || depth >= MAX_FORWARDING) return;
        for (ClassFlow.Site caller : flow.callsTo(site.method())) {
            follow(flow, caller, baseParameter, spotParameter, depth + 1, found);
        }
        for (ClassFlow.Site lambda : flow.lambdasOf(site.method())) {
            follow(flow, lambda, baseParameter, spotParameter, depth + 1, found);
        }
    }

    /** The one egg item the statement holding the colors names, or null. */
    private static String itemFor(ClassFlow flow, ClassFlow.Site site, Set<String> eggIds) {
        Set<String> ids = matching(flow.statementNames(site.method(), site.insn()), eggIds);
        // A lambda that only builds the item is named by the statement that registers the lambda.
        if (ids.isEmpty() && ClassFlow.isLambda(site.method())) {
            for (ClassFlow.Site registration : flow.lambdasOf(site.method())) {
                ids.addAll(matching(flow.statementNames(registration.method(), registration.insn()), eggIds));
            }
        }
        // Only an exact single match counts, so a wrong guess gives the neutral egg rather than
        // another mob's colors.
        return ids.size() == 1 ? ids.iterator().next() : null;
    }

    /** The egg ids a statement names, by full id or by path alone. */
    private static Set<String> matching(Set<String> names, Set<String> eggIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String name : names) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            for (String id : eggIds) {
                if (id.equals(lower) || id.substring(id.indexOf(':') + 1).equals(lower)) ids.add(id);
            }
        }
        return ids;
    }

    /** The first of two adjacent int parameters, where every egg constructor took its colors. */
    private static int firstIntPair(String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i + 1 < arguments.length; i++) {
            if (arguments[i] == Type.INT_TYPE && arguments[i + 1] == Type.INT_TYPE) return i;
        }
        return -1;
    }

    /** Old egg colors were RGB, and some mods wrote them with an alpha byte. The tint wants opaque ARGB. */
    private static int opaque(int color) {
        return 0xFF000000 | (color & 0xFFFFFF);
    }
}
