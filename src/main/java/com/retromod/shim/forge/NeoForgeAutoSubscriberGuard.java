/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
 * It is also removed from a class with no handlers left, such as one whose only handler was
 * client-only on a server, because NeoForge 21 rejects a subscriber class with nothing to register.
 * With both kinds the instance handlers keep working through the mod's own registration, and the
 * static ones are no longer subscribed automatically, which is logged.
 */
public final class NeoForgeAutoSubscriberGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-EventBus");
    private static final String AUTO_SUBSCRIBER = "net/neoforged/fml/common/EventBusSubscriber";
    private static final String AUTO_SUBSCRIBER_DESC = "L" + AUTO_SUBSCRIBER + ";";
    private static final String SUBSCRIBE_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final String EVENT_BASE = "net/neoforged/bus/api/Event";

    private NeoForgeAutoSubscriberGuard() {}

    /** Returns the original array when the class needs no change. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, name -> false);
    }

    /**
     * Also drops handlers for an event class the running host no longer has, unless
     * {@code providedByRetromod} supplies it. NeoForge lists every method of a subscriber class,
     * which resolves each parameter type, so one handler for a removed event, such as
     * {@code WandererTradesEvent} after 26.1, failed the whole mod's subscriber registration. Such
     * a handler could never run anyway.
     */
    public static byte[] apply(byte[] classBytes, Predicate<String> providedByRetromod) {
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
        boolean dropped = dropHandlersForRemovedEvents(node, providedByRetromod);

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
        if (!instanceHandler && staticHandlers.isEmpty() && !hasOtherHandlers(node)) {
            // Forge skipped an annotated class with nothing to subscribe; NeoForge 21 rejects it.
            // That is what is left once its client-only or removed-event handlers are gone.
            remove(node.visibleAnnotations);
            remove(node.invisibleAnnotations);
            return write(node);
        }
        if (!instanceHandler) return dropped ? write(node) : classBytes;

        if (!staticHandlers.isEmpty()) {
            LOGGER.warn("{} mixes static and instance event handlers, which NeoForge cannot subscribe "
                    + "automatically. Its instance handlers still run when the mod registers them; "
                    + "its static handlers {} are not subscribed.", node.name.replace('/', '.'),
                    staticHandlers);
        }
        remove(node.visibleAnnotations);
        remove(node.invisibleAnnotations);
        return write(node);
    }

    /**
     * Handlers this guard does not subscribe but a later pass does, such as a 1.12.2 handler
     * carrying Retromod's own marker until the 1.12 bridge converts its class.
     */
    private static boolean hasOtherHandlers(ClassNode node) {
        if (node.visibleAnnotations != null
                && node.visibleAnnotations.stream().anyMatch(a -> a.desc.endsWith("/LegacyHandlers;"))) {
            return true;
        }
        for (MethodNode method : node.methods) {
            for (List<AnnotationNode> annotations : List.of(
                    method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
                    method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
                if (annotations.stream().anyMatch(a -> a.desc.endsWith("SubscribeEvent;"))) return true;
            }
        }
        return false;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0); // an annotation-only change leaves frames intact
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Only judged on a host whose event bus is visible, so an offline transform, which cannot see
     * the host, never drops a handler. Only loader and Minecraft event classes are judged; a mod's
     * own events are its business. A handler the class itself calls is kept.
     */
    static boolean dropHandlersForRemovedEvents(ClassNode node, Predicate<String> providedByRetromod) {
        if (!ClassResourceInspector.exists(EVENT_BASE)) return false;
        List<MethodNode> dropped = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!has(method.visibleAnnotations, SUBSCRIBE_DESC) && !has(method.invisibleAnnotations, SUBSCRIBE_DESC)) {
                continue;
            }
            Type[] params = Type.getArgumentTypes(method.desc);
            if (params.length != 1 || params[0].getSort() != Type.OBJECT) continue;
            String event = params[0].getInternalName();
            boolean hostOwned = event.startsWith("net/neoforged/") || event.startsWith("net/minecraftforge/")
                    || event.startsWith("net/minecraft/");
            if (!hostOwned || providedByRetromod.test(event) || ClassResourceInspector.exists(event)
                    || calledInClass(node, method)) {
                continue;
            }
            LOGGER.warn("{}.{} listens for {}, which this NeoForge version no longer has, so it is dropped.",
                    node.name.replace('/', '.'), method.name, event.replace('/', '.'));
            dropped.add(method);
        }
        node.methods.removeAll(dropped);
        return !dropped.isEmpty();
    }

    private static boolean calledInClass(ClassNode node, MethodNode target) {
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.equals(target.name) && call.desc.equals(target.desc)) {
                    return true;
                }
            }
        }
        return false;
    }


    private static boolean has(List<AnnotationNode> annotations, String desc) {
        return annotations != null && annotations.stream().anyMatch(a -> desc.equals(a.desc));
    }

    private static void remove(List<AnnotationNode> annotations) {
        if (annotations != null) annotations.removeIf(a -> AUTO_SUBSCRIBER_DESC.equals(a.desc));
    }
}
