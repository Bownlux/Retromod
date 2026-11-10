/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Drops an {@code addListener} lambda for an event class the running NeoForge no longer has. */
public final class NeoForgeRemovedEventListeners {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-EventBus");
    private static final String BUS = "net/neoforged/bus/api/IEventBus";
    private static final String EVENT_BASE = "net/neoforged/bus/api/Event";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";

    private NeoForgeRemovedEventListeners() {}

    /** The class without listeners for events this host removed, or the same array when it has none. */
    public static byte[] apply(byte[] classBytes, Predicate<String> providedByRetromod) {
        // Judged only where the host's event bus is visible, as the subscriber guard does, so an
        // offline transform never drops a listener.
        if (classBytes == null || !ClassResourceInspector.exists(EVENT_BASE)) return classBytes;
        return dropRemoved(classBytes, event -> !providedByRetromod.test(event) && !ClassResourceInspector.exists(event));
    }

    /** The class without listeners for the events {@code removed} names. */
    static byte[] dropRemoved(byte[] classBytes, Predicate<String> removed) {
        if (classBytes == null || new String(classBytes, StandardCharsets.ISO_8859_1).indexOf(BUS) < 0) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= dropListeners(node, method, removed);
        }
        if (!changed) return classBytes;
        // Each removal leaves the stack as the call did, so the existing frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean dropListeners(ClassNode node, MethodNode method, Predicate<String> removed) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(BUS) || !call.name.equals("addListener")
                    || !call.desc.endsWith(CONSUMER + ")V")) {
                continue;
            }
            // NeoForge reads the event from the lambda built right before the call, as its bus does,
            // or from the class an addListener(Class, Consumer) call names before that lambda.
            AbstractInsnNode built = previous(call);
            String event = lambdaEvent(built);
            if (event == null) continue;
            LdcInsnNode eventClass = call.desc.contains("Ljava/lang/Class;" + CONSUMER) ? classArgument(built) : null;
            String named = eventClass == null ? null : ((Type) eventClass.cst).getInternalName();
            boolean lambdaRemoved = hostOwned(event) && removed.test(event);
            boolean namedRemoved = named != null && hostOwned(named) && removed.test(named);
            if (!lambdaRemoved && !namedRemoved) continue;
            String shown = namedRemoved ? named : event;
            LOGGER.warn("{}.{} adds a listener for {}, which this NeoForge version no longer has, so it is dropped.",
                    node.name.replace('/', '.'), method.name, shown.replace('/', '.'));
            // Loading a removed class literal fails as soon as it runs, so the class argument becomes null too.
            if (eventClass != null) method.instructions.set(eventClass, new InsnNode(Opcodes.ACONST_NULL));
            // The lambda's captured values and the call's own arguments and bus are popped instead.
            method.instructions.insertBefore(built, pops(Type.getArgumentTypes(((InvokeDynamicInsnNode) built).desc)));
            method.instructions.set(built, new InsnNode(Opcodes.ACONST_NULL));
            InsnList callPops = pops(Type.getArgumentTypes(call.desc));
            callPops.add(new InsnNode(Opcodes.POP));
            method.instructions.insertBefore(call, callPops);
            method.instructions.remove(call);
            changed = true;
        }
        return changed;
    }

    /** Pops values of the given types, last pushed first. */
    private static InsnList pops(Type[] types) {
        InsnList list = new InsnList();
        for (int i = types.length - 1; i >= 0; i--) {
            list.add(new InsnNode(types[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        return list;
    }

    /**
     * The class literal an addListener(Class, Consumer) call passes, pushed before the lambda and
     * its captured values, or null when the straight-line code before the lambda has none.
     */
    private static LdcInsnNode classArgument(AbstractInsnNode lambda) {
        int captured = Type.getArgumentTypes(((InvokeDynamicInsnNode) lambda).desc).length;
        AbstractInsnNode current = previous(lambda);
        // Each captured value is usually one load, so the literal is at most that many steps back.
        for (int step = 0; current != null && step <= captured; step++) {
            if (current instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
                return ldc;
            }
            if (current instanceof JumpInsnNode || current instanceof LabelNode) return null;
            current = previous(current);
        }
        return null;
    }

    private static boolean hostOwned(String event) {
        return event.startsWith("net/neoforged/") || event.startsWith("net/minecraft/");
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode current = insn.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    /** The event a lambda takes, from its instantiated type, or null when the instruction builds no lambda. */
    private static String lambdaEvent(AbstractInsnNode insn) {
        if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs.length < 3
                || !indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                || !indy.desc.endsWith(")" + CONSUMER)
                || !(indy.bsmArgs[2] instanceof Type instantiated)) {
            return null;
        }
        Type[] params = instantiated.getArgumentTypes();
        return params.length == 1 && params[0].getSort() == Type.OBJECT ? params[0].getInternalName() : null;
    }
}
