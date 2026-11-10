/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.SyntheticEmbedder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sets the registry id on the thread while the object a RegisterEvent helper registers is built. */
public final class ConsumerRegistrationIdStamper {

    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final byte[] HELPER = StaticFieldIdStamper.utf8("RegisterEvent$RegisterHelper");
    /** The bridge methods that set or put back the thread's id; a window must not contain one already. */
    private static final Set<String> SLOT_METHODS = Set.of("enter", "leave",
            StaticFieldIdStamper.ISOLATE, StaticFieldIdStamper.RESTORE);

    private ConsumerRegistrationIdStamper() {}

    /**
     * Where the id is set and put back, and what it is: a proven literal, a local the method
     * filled earlier, or (both null) the id already on the operand stack.
     */
    private record Window(AbstractInsnNode first, AbstractInsnNode last, String[] literal, int idLocal) {}

    /** Stamps each construction a helper registers in the same method under an id known before it runs. */
    public static byte[] apply(byte[] classBytes, StaticFieldRegistryIds jarIds, String bridge) {
        // Only a class that names the helper can register through it.
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, HELPER)) return classBytes;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        // Mixin merges a mixin's code into Minecraft's, and Retromod's own helpers build nothing.
        if (StaticFieldIdStamper.isMixin(cn) || SyntheticEmbedder.isRetromodClass(cn.name)) return classBytes;
        StaticFieldRegistryIds jar = jarIds != null ? jarIds : StaticFieldRegistryIds.NONE;
        int stamped = 0;
        for (MethodNode m : cn.methods) {
            // A constructor is left alone: a handler there could not hold an object still being built.
            // So an item a constructor registers takes the id of the window it is built in, as anything
            // built inside a deferred register's supplier does.
            if (!"<init>".equals(m.name)) stamped += stamp(cn.name, m, jar, bridge);
        }
        return stamped == 0 ? classBytes : StaticFieldIdStamper.write(classBytes, cn, true);
    }

    /** Stamps one method's helper registrations and counts them. */
    static int stamp(String owner, MethodNode m, StaticFieldRegistryIds jar, String bridge) {
        if (m.instructions == null || !callsHelper(m)) return 0;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (Throwable unanalyzable) {
            return 0;
        }
        List<Window> windows = new ArrayList<>();
        // Every registration counts the stores its object came from, accepted or not, so a branch that
        // registers under an unproven id never lends the other branch's id to the object.
        Map<AbstractInsnNode, Integer> registrations = new HashMap<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !registers(call)
                    || frames[m.instructions.indexOf(call)] == null) {
                continue;
            }
            for (AbstractInsnNode store : originStores(m, frames, call)) registrations.merge(store, 1, Integer::sum);
            if (!StaticFieldRegistryIds.isHelperRegistration(call)) continue;
            Window window = window(m, frames, call, jar);
            if (window != null && selfContained(m, frames, window, bridge)) windows.add(window);
        }
        // An object stored inside a window and registered more than once could take either id, so it gets neither.
        windows.removeIf(window -> registrationsInside(m, window, registrations) != (isStore(window.last()) ? 1 : 0));
        // Outer windows go first, so each nested one puts its handler ahead of the one around it.
        windows.sort(Comparator.comparingInt(window -> m.instructions.indexOf(window.first())));
        for (Window window : windows) emit(m, window, bridge);
        return windows.size();
    }

    /** The stores the registered object of {@code call} came from, through local copies. */
    private static Set<AbstractInsnNode> originStores(MethodNode m, Frame<SourceValue>[] frames, MethodInsnNode call) {
        Frame<SourceValue> atCall = frames[m.instructions.indexOf(call)];
        Set<AbstractInsnNode> stores = new HashSet<>();
        Deque<AbstractInsnNode> values = new ArrayDeque<>(atCall.getStack(atCall.getStackSize() - 1).insns);
        while (!values.isEmpty() && stores.size() < 64) {
            AbstractInsnNode value = values.pop();
            if (value.getOpcode() == Opcodes.CHECKCAST || value.getOpcode() == Opcodes.DUP) {
                Frame<SourceValue> at = frames[m.instructions.indexOf(value)];
                if (at != null) values.addAll(at.getStack(at.getStackSize() - 1).insns);
            } else if (value instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                // A load reads every store that reaches it; each store's value may be another local's copy.
                Frame<SourceValue> at = frames[m.instructions.indexOf(load)];
                if (at == null) continue;
                for (AbstractInsnNode store : at.getLocal(load.var).insns) {
                    Frame<SourceValue> atStore = frames[m.instructions.indexOf(store)];
                    if (isStore(store) && stores.add(store) && atStore != null) {
                        values.addAll(atStore.getStack(atStore.getStackSize() - 1).insns);
                    }
                }
            }
        }
        return stores;
    }

    /** How many registrations take an object stored inside the window. */
    private static int registrationsInside(MethodNode m, Window window, Map<AbstractInsnNode, Integer> registrations) {
        int count = 0;
        for (AbstractInsnNode insn = window.first(); insn != null; insn = insn.getNext()) {
            count += registrations.getOrDefault(insn, 0);
            if (insn == window.last()) break;
        }
        return count;
    }

    private static boolean isStore(AbstractInsnNode insn) {
        return insn.getOpcode() == Opcodes.ASTORE;
    }

    /** Any helper registration, the name-only one included, with the object as its last argument. */
    private static boolean registers(MethodInsnNode call) {
        return "register".equals(call.name) && (call.owner.endsWith("RegisterEvent$RegisterHelper")
                || call.owner.endsWith("/LegacyRegisterHelper"));
    }

    private static boolean callsHelper(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && StaticFieldRegistryIds.isHelperRegistration(call)) return true;
        }
        return false;
    }

    /** The window around what one registration builds, or null when its id is not known in time. */
    private static Window window(MethodNode m, Frame<SourceValue>[] frames, MethodInsnNode call,
                                 StaticFieldRegistryIds jar) {
        Frame<SourceValue> atCall = frames[m.instructions.indexOf(call)];
        int top = atCall.getStackSize();
        AbstractInsnNode idProducer = single(atCall.getStack(top - 2));
        AbstractInsnNode object = throughCasts(m, frames, single(atCall.getStack(top - 1)));
        if (idProducer == null || object == null) return null;
        String[] literal = jar.provenId(m, frames, idProducer);
        // Only an id, not a key, can be handed to the bridge as it is.
        boolean plainId = IDENTIFIER.equals(Type.getArgumentTypes(call.desc)[0].getInternalName());

        // An object kept in a local: the statement that stores it, as in Item knife = new KnifeItem(...).
        if (object instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            Frame<SourceValue> atLoad = frames[m.instructions.indexOf(load)];
            // A parameter, or a local filled on several paths, is not built here.
            if (!(single(atLoad.getLocal(load.var)) instanceof VarInsnNode store)
                    || store.getOpcode() != Opcodes.ASTORE) {
                return null;
            }
            int storeAt = m.instructions.indexOf(store);
            Frame<SourceValue> atStore = frames[storeAt];
            if (atStore == null || !builds(m, frames, single(atStore.getStack(atStore.getStackSize() - 1)))) {
                return null;
            }
            AbstractInsnNode first = StaticFieldIdStamper.statementStart(m, frames, storeAt);
            if (first == null) return null;
            if (literal != null) return new Window(first, store, literal, -1);
            // An id the method stored before the statement, as a registrar looping over its entries does.
            int idLocal = plainId ? storedBefore(m, frames, idProducer, first) : -1;
            return idLocal >= 0 ? new Window(first, store, null, idLocal) : null;
        }

        // An object built inside the call: Java has evaluated the id first, so the window starts right after it.
        if (!builds(m, frames, object)) return null;
        AbstractInsnNode first = nextReal(valueEnd(m, frames, idProducer));
        AbstractInsnNode last = previousReal(call);
        if (first == null || last == null || first == call) return null;
        Frame<SourceValue> atFirst = frames[m.instructions.indexOf(first)];
        if (atFirst == null || atFirst.getStackSize() != top - 1 || single(atFirst.getStack(top - 2)) != idProducer) {
            return null;
        }
        return literal != null || plainId ? new Window(first, last, literal, -1) : null;
    }

    /** Whether a value comes from building something: a constructor or a call, never a plain read. */
    private static boolean builds(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode producer) {
        AbstractInsnNode value = throughCasts(m, frames, producer);
        if (value instanceof MethodInsnNode call) {
            // An entity type's build call gets its exact id from EntityTypeBuildKeyAdapter instead.
            return !call.owner.endsWith(EntityTypeBuildKeyAdapter.BRIDGE);
        }
        if (value == null || value.getOpcode() != Opcodes.DUP) return false;
        Frame<SourceValue> atDup = frames[m.instructions.indexOf(value)];
        return atDup != null && single(atDup.getStack(atDup.getStackSize() - 1)) instanceof TypeInsnNode created
                && created.getOpcode() == Opcodes.NEW;
    }

    /** The local an id was stored in before {@code first}, on a straight path to it, or -1. */
    private static int storedBefore(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode idProducer,
                                    AbstractInsnNode first) {
        if (!(idProducer instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return -1;
        Frame<SourceValue> atLoad = frames[m.instructions.indexOf(load)];
        if (!(single(atLoad.getLocal(load.var)) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) {
            return -1;
        }
        int storeAt = m.instructions.indexOf(store);
        int firstAt = m.instructions.indexOf(first);
        Frame<SourceValue> atFirst = frames[firstAt];
        // The statement must see that same store, with no branch or jump target in between.
        if (storeAt >= firstAt || atFirst == null || single(atFirst.getLocal(load.var)) != store) return -1;
        for (int i = storeAt + 1; i < firstAt; i++) {
            if (branches(m.instructions.get(i)) || isJumpTarget(m, m.instructions.get(i))) return -1;
        }
        return load.var;
    }

    /** The last instruction of the expression that produced {@code producer}'s value. */
    private static AbstractInsnNode valueEnd(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode producer) {
        // new Identifier(...) leaves its value from the new or its dup; the expression ends at the constructor call.
        AbstractInsnNode copy = producer.getOpcode() == Opcodes.NEW ? nextReal(producer) : producer;
        if (copy == null || copy.getOpcode() != Opcodes.DUP) return producer;
        Frame<SourceValue> atDup = frames[m.instructions.indexOf(copy)];
        if (atDup == null || !(single(atDup.getStack(atDup.getStackSize() - 1)) instanceof TypeInsnNode created)
                || created.getOpcode() != Opcodes.NEW) {
            return producer;
        }
        for (AbstractInsnNode insn = copy.getNext(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
                    || !"<init>".equals(init.name) || !init.owner.equals(created.desc)) {
                continue;
            }
            Frame<SourceValue> atInit = frames[m.instructions.indexOf(init)];
            int receiver = atInit == null ? -1 : atInit.getStackSize() - Type.getArgumentTypes(init.desc).length - 1;
            if (receiver >= 0 && single(atInit.getStack(receiver)) == copy) return init;
        }
        return producer;
    }

    /** Whether the window is one straight piece: nothing jumps, returns or throws out, and no handler starts inside. */
    private static boolean selfContained(MethodNode m, Frame<SourceValue>[] frames, Window window, String bridge) {
        int from = m.instructions.indexOf(window.first());
        int to = m.instructions.indexOf(window.last());
        if (from > to) return false;
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            boolean inside = i >= from && i <= to;
            if (inside && (frames[i] == null || leavesMethod(insn) || setsSlot(insn, bridge))) return false;
            // A jump from outside may land just before the start, where the enter call goes.
            for (LabelNode target : targets(insn)) {
                int at = m.instructions.indexOf(target);
                if (inside != (at > from && at <= to)) return false;
            }
        }
        // The mod's own handlers must enclose the window or stay clear of it.
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            for (LabelNode label : new LabelNode[]{block.start, block.end, block.handler}) {
                int at = m.instructions.indexOf(label);
                if (at > from && at <= to) return false;
            }
        }
        return true;
    }

    /** Wraps the window in bridge.enter(id) and bridge.leave(previous), restoring the id on an exception too. */
    private static void emit(MethodNode m, Window window, String bridge) {
        int previous = m.maxLocals++;
        InsnList enter = new InsnList();
        if (window.literal() != null) {
            enter.add(new LdcInsnNode(window.literal()[0]));
            enter.add(new LdcInsnNode(window.literal()[1]));
            enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false));
        } else if (window.idLocal() >= 0) {
            enter.add(new VarInsnNode(Opcodes.ALOAD, window.idLocal()));
        } else {
            // The id is on top of the stack and stays there for the registration.
            enter.add(new InsnNode(Opcodes.DUP));
        }
        enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "enter", StaticFieldIdStamper.ENTER_DESC, false));
        enter.add(new VarInsnNode(Opcodes.ASTORE, previous));
        LabelNode start = new LabelNode();
        enter.add(start);
        m.instructions.insertBefore(window.first(), enter);

        // After the object is built the previous id comes back. A throw restores it too and is
        // rethrown right here, inside any handler of the mod's that encloses the window.
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode after = new LabelNode();
        InsnList leave = new InsnList();
        leave.add(end);
        leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
        leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", StaticFieldIdStamper.LEAVE_DESC, false));
        leave.add(new JumpInsnNode(Opcodes.GOTO, after));
        leave.add(handler);
        leave.add(new VarInsnNode(Opcodes.ALOAD, previous));
        leave.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "leave", StaticFieldIdStamper.LEAVE_DESC, false));
        leave.add(new InsnNode(Opcodes.ATHROW));
        leave.add(after);
        m.instructions.insert(window.last(), leave);
        m.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, null));
    }

    /** A return or throw would skip leave; a subroutine jump cannot be followed. */
    private static boolean leavesMethod(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW
                || op == Opcodes.JSR || op == Opcodes.RET;
    }

    /** A static field's stamp or an initializer's isolation already inside would interleave with this window. */
    private static boolean setsSlot(AbstractInsnNode insn, String bridge) {
        return insn instanceof MethodInsnNode call && call.owner.endsWith(bridge) && SLOT_METHODS.contains(call.name);
    }

    /** The labels an instruction can jump to. */
    private static List<LabelNode> targets(AbstractInsnNode insn) {
        List<LabelNode> targets = new ArrayList<>();
        if (insn instanceof JumpInsnNode jump) {
            targets.add(jump.label);
        } else if (insn instanceof TableSwitchInsnNode table) {
            targets.add(table.dflt);
            targets.addAll(table.labels);
        } else if (insn instanceof LookupSwitchInsnNode lookup) {
            targets.add(lookup.dflt);
            targets.addAll(lookup.labels);
        }
        return targets;
    }

    private static boolean branches(AbstractInsnNode insn) {
        return !targets(insn).isEmpty() || insn.getOpcode() == Opcodes.ATHROW
                || (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN);
    }

    private static boolean isJumpTarget(MethodNode m, AbstractInsnNode insn) {
        if (!(insn instanceof LabelNode label)) return false;
        for (AbstractInsnNode other : m.instructions) {
            if (targets(other).contains(label)) return true;
        }
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            if (block.handler == label) return true;
        }
        return false;
    }

    /** The value under any casts, which a generic {@code register} argument often has. */
    private static AbstractInsnNode throughCasts(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() == Opcodes.CHECKCAST) {
            Frame<SourceValue> atCast = frames[m.instructions.indexOf(insn)];
            insn = atCast == null ? null : single(atCast.getStack(atCast.getStackSize() - 1));
        }
        return insn;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn == null ? null : insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    /** The one instruction that produced a value, or null when several paths did. */
    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }
}
