/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Moves an old Forge listener on an event NeoForge made abstract onto each concrete event below it. */
public final class NeoForgeAbstractEventListeners {

    /** Turns the pass on; ForgeEventApiShim enables it wherever a Forge mod moves to NeoForge. */
    public static final String FLAG = "neoforge-abstract-event-listeners";

    static final String TABLE = "/retromod/neoforge-abstract-events.tsv";
    static final String SUBSCRIBE = "Lnet/neoforged/bus/api/SubscribeEvent;";
    static final String EVENT = "net/neoforged/bus/api/Event";
    static final String BUS = "net/neoforged/bus/api/IEventBus";
    static final String HELPER = RebuiltApiClasses.ROOT + "neoforge/event/LegacyAbstractListeners";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";
    private static final String PRIORITY = "Lnet/neoforged/bus/api/EventPriority;";

    // Forge posted these itself, once per stage or name tag. NeoForge 26.x split them by stage and by
    // the two steps of drawing a tag, and dropped the getters old listeners read, so they keep their own bridges.
    static final Set<String> SPLIT_BY_NEOFORGE = Set.of(
            "net/neoforged/neoforge/client/event/RenderLevelStageEvent",
            "net/neoforged/neoforge/client/event/RenderNameTagEvent");

    // The addListener shapes that take only a lambda, whose event type the bus reads from the lambda itself.
    private static final Set<String> LAMBDA_SHAPES = Set.of(
            "(" + CONSUMER + ")V", "(" + PRIORITY + CONSUMER + ")V",
            "(Z" + CONSUMER + ")V", "(" + PRIORITY + "Z" + CONSUMER + ")V");

    private static volatile Map<String, Map<String, List<String>>> sections;
    private static final Map<String, List<String>> HOST_CHILDREN = new java.util.concurrent.ConcurrentHashMap<>();

    private NeoForgeAbstractEventListeners() {}

    /** Registers the helper an old addListener call moves to. */
    public static void register(RetromodTransformer transformer) {
        transformer.enablePostProcess(FLAG);
        RebuiltApiClasses.registerTree(transformer, HELPER);
    }

    /** The class with each listener on an abstract event moved to that event's children, or the same bytes. */
    public static byte[] apply(byte[] classBytes) {
        // Reading the host's classes is slow, so each event's children are read once per target.
        String target = RetromodVersion.TARGET_MC_VERSION;
        return apply(classBytes, event -> event == null ? List.of() : HOST_CHILDREN.computeIfAbsent(target + " " + event,
                key -> childrenOf(event, ClassResourceInspector::read, target)));
    }

    static byte[] apply(byte[] classBytes, Function<String, ClassNode> host, String target) {
        return apply(classBytes, event -> childrenOf(event, host, target));
    }

    private static byte[] apply(byte[] classBytes, Function<String, List<String>> childrenOf) {
        if (classBytes == null || !mayListen(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return classBytes;
        Set<String> taken = new HashSet<>();
        for (MethodNode method : node.methods) taken.add(method.name + method.desc);
        List<MethodNode> added = new ArrayList<>();
        boolean changed = false;
        for (MethodNode method : List.copyOf(node.methods)) {
            AnnotationNode subscription = subscription(method);
            if (subscription != null) {
                List<String> children = childrenOf.apply(eventOf(method));
                if (!children.isEmpty()) {
                    added.addAll(siblings(node, method, subscription, children, taken));
                    method.visibleAnnotations.remove(subscription);
                    changed = true;
                }
            }
            changed |= moveLambdaListeners(method, childrenOf);
        }
        if (!changed) return classBytes;
        node.methods.addAll(added);
        // Each sibling is straight-line code and the originals keep their frames, so none is computed here.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A cheap text check, so a class with no NeoForge listener is never parsed. */
    private static boolean mayListen(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return text.contains("bus/api/SubscribeEvent") || text.contains(BUS);
    }

    private static AnnotationNode subscription(MethodNode method) {
        if (method.visibleAnnotations == null || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return null;
        }
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals(SUBSCRIBE)) return annotation;
        }
        return null;
    }

    /** The one event a listener takes, or null when it does not have NeoForge's listener shape. */
    private static String eventOf(MethodNode method) {
        Type[] params = Type.getArgumentTypes(method.desc);
        if (params.length != 1 || params[0].getSort() != Type.OBJECT) return null;
        return params[0].getInternalName();
    }

    /** One listener per child, each carrying the original's annotation and calling it with the child event. */
    private static List<MethodNode> siblings(ClassNode owner, MethodNode method, AnnotationNode subscription,
            List<String> children, Set<String> taken) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type returns = Type.getReturnType(method.desc);
        List<MethodNode> siblings = new ArrayList<>();
        for (String child : children) {
            String desc = Type.getMethodDescriptor(returns, Type.getObjectType(child));
            String name = uniqueName(method.name + "$retromod$" + simpleName(child), desc, taken);
            int access = method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_PROTECTED);
            MethodNode sibling = new MethodNode(access, name, desc, null, null);
            sibling.visibleAnnotations = new ArrayList<>(List.of(subscription));
            InsnList body = sibling.instructions;
            if (!isStatic) body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1));
            int call = isStatic ? Opcodes.INVOKESTATIC
                    : (method.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
            body.add(new MethodInsnNode(call, owner.name, method.name, method.desc, false));
            body.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
            siblings.add(sibling);
        }
        return siblings;
    }

    private static String uniqueName(String base, String desc, Set<String> taken) {
        String name = base;
        for (int i = 2; !taken.add(name + desc); i++) name = base + i;
        return name;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '_');
    }

    /** Rewrites each {@code addListener(lambda)} whose lambda takes an abstract event to add it per child; true when one moved. */
    private static boolean moveLambdaListeners(MethodNode method, Function<String, List<String>> childrenOf) {
        boolean moved = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(BUS) || !call.name.equals("addListener")
                    || !LAMBDA_SHAPES.contains(call.desc)) {
                continue;
            }
            String event = lambdaEvent(previous(insn));
            List<String> children = childrenOf.apply(event);
            if (children.isEmpty()) continue;
            // The helper takes the same arguments with the bus first and the children last.
            method.instructions.insertBefore(call, new LdcInsnNode(String.join(",", children)));
            String helperDesc = "(L" + BUS + ";" + call.desc.substring(1, call.desc.length() - 2) + "Ljava/lang/String;)V";
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "addListener", helperDesc, false));
            moved = true;
        }
        return moved;
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode current = insn.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    /** The event a lambda built right before the call takes, from its instantiated type, or null. */
    private static String lambdaEvent(AbstractInsnNode insn) {
        if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs.length < 3
                || !indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                || !(indy.bsmArgs[2] instanceof Type instantiated)) {
            return null;
        }
        Type[] params = instantiated.getArgumentTypes();
        return params.length == 1 && params[0].getSort() == Type.OBJECT ? params[0].getInternalName() : null;
    }

    /** The concrete events below an abstract one, as the host has them; empty when the event is not abstract there. */
    static List<String> childrenOf(String event, Function<String, ClassNode> host, String target) {
        if (event == null || SPLIT_BY_NEOFORGE.contains(event)) return List.of();
        Map<String, List<String>> section = section(target);
        List<String> listed = section == null ? null : section.get(event);
        if (listed == null) return List.of();
        // Offline the table answers; a host that can be read confirms the parent and each child.
        if (host.apply(EVENT) == null) return listed;
        ClassNode parent = host.apply(event);
        if (parent == null || (parent.access & Opcodes.ACC_ABSTRACT) == 0) return List.of();
        List<String> children = new ArrayList<>();
        for (String child : listed) {
            ClassNode node = host.apply(child);
            if (node != null && (node.access & Opcodes.ACC_ABSTRACT) == 0) children.add(child);
        }
        return children;
    }

    /** The newest section at or below the target, or null below the first NeoForge read. */
    static Map<String, List<String>> section(String target) {
        Map<String, Map<String, List<String>>> all = sections;
        if (all == null) sections = all = read();
        Map<String, List<String>> best = null;
        String bestVersion = null;
        for (Map.Entry<String, Map<String, List<String>>> entry : all.entrySet()) {
            String version = entry.getKey();
            if (RetromodVersion.compareMcVersions(version, target) > 0) continue;
            if (bestVersion == null || RetromodVersion.compareMcVersions(version, bestVersion) > 0) {
                bestVersion = version;
                best = entry.getValue();
            }
        }
        return best;
    }

    private static Map<String, Map<String, List<String>>> read() {
        Map<String, Map<String, List<String>>> all = new TreeMap<>();
        try (InputStream in = NeoForgeAbstractEventListeners.class.getResourceAsStream(TABLE)) {
            if (in == null) return all;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t");
                if (columns.length != 3) continue;
                all.computeIfAbsent(columns[0], v -> new LinkedHashMap<>()).put(columns[1], List.of(columns[2].split(",")));
            }
        } catch (IOException unreadable) {
            return all;
        }
        return all;
    }
}
