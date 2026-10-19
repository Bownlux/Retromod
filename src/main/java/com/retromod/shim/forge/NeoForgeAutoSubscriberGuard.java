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
 *
 * <p>NeoForge also resolves the signature of every method a subscriber class declares, where Forge
 * only read its public ones. A subscriber for both sides with a private client helper, such as
 * {@code getMovementInput(Player, Minecraft)}, then failed on a dedicated server with "Attempted to
 * load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER". Such a helper's
 * client types become {@code Object} in its signature, cast back where it starts and where the
 * class calls it, so nothing loads a client class until the helper runs.
 */
public final class NeoForgeAutoSubscriberGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-EventBus");
    private static final String AUTO_SUBSCRIBER = "net/neoforged/fml/common/EventBusSubscriber";
    private static final String AUTO_SUBSCRIBER_DESC = "L" + AUTO_SUBSCRIBER + ";";
    private static final String SUBSCRIBE_DESC = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final String EVENT_BASE = "net/neoforged/bus/api/Event";
    private static final String DIST_DESC = "Lnet/neoforged/api/distmarker/Dist;";
    private static final String[] CLIENT_PACKAGES = {
            "net/minecraft/client/", "com/mojang/blaze3d/", "net/neoforged/neoforge/client/"};

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
        byte[] erased = eraseClientHelperSignatures(classBytes, node);
        if (erased != classBytes) {
            classBytes = erased;
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        }
        // classBytes now holds any erased signatures, so returning it unchanged keeps them.
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

    /**
     * Erases client types from the private helpers of a subscriber that runs on both sides. A class
     * with nest members is left alone: they may call the helper, and only this class is visible here.
     */
    static byte[] eraseClientHelperSignatures(byte[] classBytes, ClassNode node) {
        if (clientOnly(node) || node.nestMembers != null || node.nestHostClass != null) return classBytes;
        boolean any = false;
        for (MethodNode method : node.methods) {
            if (isClientHelper(method)) any = true;
        }
        if (!any) return classBytes;

        // Expanded frames, so each frame keeps the cast type of a parameter after its new signature.
        ClassNode expanded = new ClassNode();
        new ClassReader(classBytes).accept(expanded, ClassReader.EXPAND_FRAMES);
        java.util.Map<String, String> renamed = new java.util.HashMap<>();
        java.util.Set<String> lambdaTargets = handleTargets(expanded, false);
        java.util.Set<String> constantHandles = handleTargets(expanded, true);
        for (MethodNode method : expanded.methods) {
            if (!isClientHelper(method)) continue;
            String erased = eraseClientTypes(method.desc);
            String key = method.name + method.desc;
            // A lambda can widen an argument but not narrow a result, and a handle constant's
            // callers name the exact type, so those helpers keep their signature.
            boolean resultErased = !Type.getReturnType(erased).equals(Type.getReturnType(method.desc));
            if (declares(expanded, method.name, erased) || constantHandles.contains(key)
                    || (resultErased && lambdaTargets.contains(key))) {
                continue;
            }
            castParameters(method, erased);
            renamed.put(method.name + method.desc, erased);
            LOGGER.debug("Erased client types from {}.{}{} so a server can list its methods",
                    expanded.name, method.name, method.desc);
            method.desc = erased;
        }
        if (renamed.isEmpty()) return classBytes;
        retargetCalls(expanded, renamed);
        ClassWriter writer = new ClassWriter(0);
        expanded.accept(writer);
        return writer.toByteArray();
    }

    /** {@code @EventBusSubscriber(value = Dist.CLIENT)}, which NeoForge never loads on a server. */
    private static boolean clientOnly(ClassNode node) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!AUTO_SUBSCRIBER_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!"value".equals(annotation.values.get(i))
                            || !(annotation.values.get(i + 1) instanceof List<?> dists)) {
                        continue;
                    }
                    return !dists.isEmpty() && dists.stream().allMatch(dist -> dist instanceof String[] value
                            && DIST_DESC.equals(value[0]) && "CLIENT".equals(value[1]));
                }
            }
        }
        return false;
    }

    private static boolean isClientHelper(MethodNode method) {
        return (method.access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0
                && !has(method.visibleAnnotations, SUBSCRIBE_DESC) && !has(method.invisibleAnnotations, SUBSCRIBE_DESC)
                && !eraseClientTypes(method.desc).equals(method.desc);
    }

    static String eraseClientTypes(String desc) {
        Type[] params = Type.getArgumentTypes(desc);
        for (int i = 0; i < params.length; i++) params[i] = erase(params[i]);
        return Type.getMethodDescriptor(erase(Type.getReturnType(desc)), params);
    }

    private static Type erase(Type type) {
        if (type.getSort() != Type.OBJECT) return type;
        for (String client : CLIENT_PACKAGES) {
            if (type.getInternalName().startsWith(client)) return Type.getType(Object.class);
        }
        return type;
    }

    /** Casts each erased parameter back at the start, so the body and its frames see the old type. */
    private static void castParameters(MethodNode method, String erasedDesc) {
        Type[] original = Type.getArgumentTypes(method.desc);
        Type[] erased = Type.getArgumentTypes(erasedDesc);
        org.objectweb.asm.tree.InsnList casts = new org.objectweb.asm.tree.InsnList();
        int slot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < original.length; i++) {
            if (!original[i].equals(erased[i])) {
                casts.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, slot));
                casts.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST,
                        original[i].getInternalName()));
                casts.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ASTORE, slot));
            }
            slot += original[i].getSize();
        }
        method.instructions.insert(casts);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    /** Points this class's calls and method handles at the erased helpers, casting erased results. */
    private static void retargetCalls(ClassNode node, java.util.Map<String, String> renamed) {
        for (MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    String erased = renamed.get(call.name + call.desc);
                    if (erased == null) continue;
                    Type returned = Type.getReturnType(call.desc);
                    call.desc = erased;
                    if (!returned.equals(Type.getReturnType(erased))) {
                        method.instructions.insert(call, new org.objectweb.asm.tree.TypeInsnNode(
                                org.objectweb.asm.Opcodes.CHECKCAST, returned.getInternalName()));
                    }
                } else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        if (indy.bsmArgs[i] instanceof org.objectweb.asm.Handle handle
                                && handle.getOwner().equals(node.name)
                                && renamed.containsKey(handle.getName() + handle.getDesc())) {
                            indy.bsmArgs[i] = new org.objectweb.asm.Handle(handle.getTag(), handle.getOwner(),
                                    handle.getName(), renamed.get(handle.getName() + handle.getDesc()),
                                    handle.isInterface());
                        }
                    }
                }
            }
        }
    }

    /** The {@code name + desc} of this class's methods named by a lambda, or by a handle constant. */
    private static java.util.Set<String> handleTargets(ClassNode node, boolean constants) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                Object[] values = !constants && insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy
                        ? indy.bsmArgs
                        : constants && insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                                ? new Object[] {ldc.cst} : new Object[0];
                for (Object value : values) {
                    if (value instanceof org.objectweb.asm.Handle handle && handle.getOwner().equals(node.name)) {
                        targets.add(handle.getName() + handle.getDesc());
                    }
                }
            }
        }
        return targets;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
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
