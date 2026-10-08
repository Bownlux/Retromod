/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.ACC_STATIC;

/**
 * Keeps a Forge mod loadable on NeoForge when its {@code @Mod.EventBusSubscriber} class also
 * declares instance {@code @SubscribeEvent} handlers.
 *
 * <p>Forge's automatic subscription registered only a class's static handlers, so many mods put the
 * annotation on their main class and registered its instance handlers themselves with
 * {@code register(this)}. NeoForge rejects the whole class when any handler is an instance method,
 * and the mod failed at construction with "annotated with @SubscribeEvent is not static". The
 * annotation is removed from such a class. Without static handlers that is exactly what Forge did.
 * With both kinds the instance handlers keep working through the mod's own registration, and the
 * static ones are no longer subscribed automatically, which is logged.
 */
public final class NeoForgeAutoSubscriberGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-EventBus");
    private static final String AUTO_SUBSCRIBER = "net/neoforged/fml/common/EventBusSubscriber";
    private static final String AUTO_SUBSCRIBER_DESC = "L" + AUTO_SUBSCRIBER + ";";
    private static final String SUBSCRIBE_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";

    private NeoForgeAutoSubscriberGuard() {}

    /** Returns the original array when the class needs no change. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        // Cheap pre-filter: most classes never mention the annotation.
        if (new String(classBytes, StandardCharsets.ISO_8859_1).indexOf(AUTO_SUBSCRIBER) < 0) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (!has(node.visibleAnnotations, AUTO_SUBSCRIBER_DESC)
                && !has(node.invisibleAnnotations, AUTO_SUBSCRIBER_DESC)) {
            return classBytes;
        }

        List<String> staticHandlers = new ArrayList<>();
        boolean instanceHandler = false;
        for (MethodNode method : node.methods) {
            if (!has(method.visibleAnnotations, SUBSCRIBE_DESC)
                    && !has(method.invisibleAnnotations, SUBSCRIBE_DESC)) {
                continue;
            }
            if ((method.access & ACC_STATIC) != 0) {
                staticHandlers.add(method.name);
            } else {
                instanceHandler = true;
            }
        }
        if (!instanceHandler) return classBytes;

        if (!staticHandlers.isEmpty()) {
            LOGGER.warn("{} mixes static and instance event handlers, which NeoForge cannot subscribe "
                    + "automatically. Its instance handlers still run when the mod registers them; "
                    + "its static handlers {} are not subscribed.", node.name.replace('/', '.'),
                    staticHandlers);
        }
        remove(node.visibleAnnotations);
        remove(node.invisibleAnnotations);
        ClassWriter writer = new ClassWriter(0); // an annotation-only change leaves frames intact
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean has(List<AnnotationNode> annotations, String desc) {
        return annotations != null && annotations.stream().anyMatch(a -> desc.equals(a.desc));
    }

    private static void remove(List<AnnotationNode> annotations) {
        if (annotations != null) annotations.removeIf(a -> AUTO_SUBSCRIBER_DESC.equals(a.desc));
    }
}
