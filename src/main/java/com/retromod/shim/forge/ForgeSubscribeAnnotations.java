/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rewrites old Forge {@code @SubscribeEvent} values into the shape EventBus 7 reads. */
public final class ForgeSubscribeAnnotations {

    /** EventBus 7's annotation, which every old {@code @SubscribeEvent} becomes on Forge 26.2 and newer. */
    public static final String SUBSCRIBE7 = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";
    public static final String FLAG = "forge-eventbus7-subscribe";
    private static final String OLD_SUBSCRIBE = "net/minecraftforge/eventbus/api/SubscribeEvent";
    private static final String OLD_PRIORITY = "Lnet/minecraftforge/eventbus/api/EventPriority;";
    // The bridge's enum stand-in, and the per-mod copies the embedder relocates under a prefix.
    private static final String STAND_IN_PRIORITY = ForgeEventBusSynthetics.PRI + ";";

    private ForgeSubscribeAnnotations() {}

    /** Turns the class pass on where the host reads EventBus 7's annotation. */
    public static void register(RetromodTransformer transformer) {
        if (hostUsesEventBus7()) transformer.enablePostProcess(FLAG);
    }

    /** Whether a Forge host has EventBus 7's annotation and lacks EventBus 6's; offline, a Forge target from 26.2. */
    static boolean hostUsesEventBus7() {
        return ForgeHostShape.matches(List.of(SUBSCRIBE7.substring(1, SUBSCRIBE7.length() - 1)), List.of(OLD_SUBSCRIBE));
    }

    /** The class with every EventBus 7 {@code @SubscribeEvent} normalized, or the same array. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !mayNeedNormalizing(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (annotations == null) continue;
                for (AnnotationNode annotation : annotations) changed |= normalize(annotation);
            }
        }
        if (!changed) return classBytes;
        // Only annotation values change, so the original frames and maxima stay valid.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A cheap text check, so a class with no old priority or receiveCanceled is never parsed. */
    private static boolean mayNeedNormalizing(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return text.contains("listener/SubscribeEvent")
                && (text.contains("EventPriority") || text.contains("receiveCanceled"));
    }

    /** Gives an EventBus 7 annotation a byte priority and no {@code receiveCanceled}; true when it changed. */
    public static boolean normalize(AnnotationNode annotation) {
        if (!SUBSCRIBE7.equals(annotation.desc) || annotation.values == null) return false;
        List<Object> values = normalizedValues(annotation.values);
        if (values.equals(annotation.values)) return false;
        annotation.values = values;
        return true;
    }

    /** A copy of an old or EventBus 7 annotation's values in EventBus 7's shape. */
    public static List<Object> normalizedValues(List<Object> values) {
        List<Object> normalized = new ArrayList<>();
        if (values == null) return normalized;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            Object name = values.get(i);
            Object value = values.get(i + 1);
            if ("receiveCanceled".equals(name)) continue;
            // An enum in EventBus 7's byte element makes priority() throw while the bus registers it.
            if ("priority".equals(name) && value instanceof String[] constant && isOldPriority(constant[0])) {
                value = priorityByte(constant[1]);
            }
            normalized.add(name);
            normalized.add(value);
        }
        return normalized;
    }

    /** The EventBus 7 priority an old or normalized annotation asks for, or NORMAL's 0 when it names none. */
    public static byte priorityOf(AnnotationNode annotation) {
        List<Object> values = annotation.values == null ? List.of() : annotation.values;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (!"priority".equals(values.get(i))) continue;
            Object value = values.get(i + 1);
            if (value instanceof Byte bits) return bits;
            if (value instanceof String[] constant && isOldPriority(constant[0])) return priorityByte(constant[1]);
        }
        return 0;
    }

    /** EventBus 7's byte for an old {@code EventPriority} constant name. */
    static byte priorityByte(String constant) {
        return switch (constant) {
            case "HIGHEST" -> 127;
            case "HIGH" -> 64;
            case "LOW" -> -64;
            case "LOWEST" -> -127;
            default -> 0;
        };
    }

    private static boolean isOldPriority(String enumDesc) {
        return OLD_PRIORITY.equals(enumDesc) || enumDesc.endsWith(STAND_IN_PRIORITY);
    }
}
