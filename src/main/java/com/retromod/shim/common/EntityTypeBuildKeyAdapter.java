/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tells the {@code EntityType.Builder.build(String)} bridge which id the built type is registered under. */
public final class EntityTypeBuildKeyAdapter {

    private EntityTypeBuildKeyAdapter() {}

    /** The bridge both the intermediary and the official redirect call. */
    public static final String BRIDGE = "com/retromod/polyfill/minecraft/RetroEntityTypeBuild";
    private static final Set<String> BRIDGE_METHODS = Set.of("build", "buildOfficial");
    private static final String NAME_ONLY_DESC = "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
    // 1.21.2 derives the type's translation key and loot table from the ResourceKey given to build.
    // Older versions took them from the registration and used the String only for the datafixer, and
    // old mods often pass a bare name, which alone would put the entity in Minecraft's namespace.
    // So the keyed bridge takes two more arguments: the registered id and the mod's namespace.
    static final String KEYED_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
    private static final byte[] BRIDGE_NAME = BRIDGE.getBytes(StandardCharsets.UTF_8);

    /** What one bridge call learns: a literal or captured registered id, and a namespace. */
    private record Site(MethodInsnNode call, String registeredId, AbstractInsnNode idToCapture,
                        String namespace) {}

    /** Gives the bridge calls in a class, after the redirect pass, their registered id or namespace. */
    public static byte[] apply(byte[] classBytes, StaticFieldRegistryIds jarIds) {
        // A class that never names the bridge is returned as is, without parsing it.
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, BRIDGE_NAME)) return classBytes;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        // Rewrite each method's bridge calls, using the jar's registry ids when the caller has them.
        StaticFieldRegistryIds jar = jarIds != null ? jarIds : StaticFieldRegistryIds.NONE;
        Context context = new Context(cn, jar);
        boolean changed = false;
        boolean captured = false;
        for (MethodNode m : cn.methods) {
            List<Site> sites = sites(cn.name, m, context);
            if (sites.isEmpty()) continue;
            captured |= rewrite(m, sites);
            changed = true;
        }
        if (!changed) return classBytes;
        // A captured id needs a new local, so frames are recomputed. Otherwise every frame stays
        // as compiled: the two arguments are pushed right before the call that consumes them.
        return StaticFieldIdStamper.write(classBytes, cn, captured);
    }

    /** The namespace and static-field ids around one class, worked out once and only when needed. */
    private static final class Context {
        private final ClassNode cn;
        private final StaticFieldRegistryIds jar;
        private String classNamespace;
        private boolean classNamespaceRead;
        private StaticFieldRegistryIds classIds;

        Context(ClassNode cn, StaticFieldRegistryIds jar) {
            this.cn = cn;
            this.jar = jar;
        }

        /** The namespace this class registers under, else the jar's. */
        String namespace() {
            if (!classNamespaceRead) {
                classNamespace = new StaticFieldRegistryIds.NamespaceEvidence().add(cn).namespace();
                classNamespaceRead = true;
            }
            return classNamespace != null ? classNamespace : jar.namespace();
        }

        /** An entity type field's {@code {namespace, path}}, from the jar index or, alone, this class's. */
        String[] entityTypeFieldId(FieldInsnNode put) {
            if (!jar.isEmpty()) return jar.entityTypeFieldsOf(put.owner).get(put.name);
            if (!put.owner.equals(cn.name)) return null;
            if (classIds == null) {
                classIds = StaticFieldRegistryIds.builder(Set.of(cn.name)).add(cn).build();
            }
            return classIds.entityTypeFieldsOf(put.owner).get(put.name);
        }
    }

    /** The bridge calls in one method that learn an id or a namespace. */
    private static List<Site> sites(String owner, MethodNode m, Context context) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && isNameOnlyBridgeCall(call)) calls.add(call);
        }
        if (calls.isEmpty()) return List.of();
        // The analyzer shows which instruction produced each stack value. A method it cannot
        // analyze is left alone.
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (Throwable unanalyzable) {
            return List.of();
        }
        List<Site> sites = new ArrayList<>();
        for (MethodInsnNode call : calls) {
            if (frames[m.instructions.indexOf(call)] == null) continue; // unreachable
            Site site = site(m, frames, call, context);
            // A call with neither fact stays as it was. On NeoForge and Forge the bridge still reads
            // the deferred register's key at runtime.
            if (site.registeredId() != null || site.idToCapture() != null || site.namespace() != null) {
                sites.add(site);
            }
        }
        return sites;
    }

    /** What one bridge call learns from the instruction that takes the built type. */
    private static Site site(MethodNode m, Frame<SourceValue>[] frames, MethodInsnNode call, Context context) {
        // A static field the jar registers as an entity type under a literal id gives that id.
        AbstractInsnNode consumer = nextPastCasts(call);
        if (consumer instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC) {
            String[] id = context.entityTypeFieldId(put);
            if (id != null) return new Site(call, id[0] + ":" + id[1], null, id[0]);
        }
        // So does Registry.register(entity type registry, id, type) taking the type straight away.
        // Any other registry is never used: an entity type built inside a spawn egg's registration
        // is not registered under the egg's id.
        if (consumer instanceof MethodInsnNode register && StaticFieldRegistryIds.isRegistration(register)) {
            Frame<SourceValue> atRegister = frames[m.instructions.indexOf(register)];
            int top = atRegister == null ? -1 : atRegister.getStackSize();
            if (top >= 3 && throughCasts(m, frames, single(atRegister.getStack(top - 1))) == call
                    && StaticFieldRegistryIds.readsEntityTypeRegistry(single(atRegister.getStack(top - 3)))) {
                // A literal id is pushed as a constant. Any other id is captured at runtime. The
                // namespace for a bare name is the id's own, else this class's, else the jar's.
                AbstractInsnNode idProducer = single(atRegister.getStack(top - 2));
                AbstractInsnNode idBuilder = throughCopies(m, frames, idProducer);
                String[] parts = idBuilder == null ? null : StaticFieldRegistryIds.idParts(m, frames, idBuilder);
                String namespace = parts != null && parts[0] != null ? parts[0] : context.namespace();
                if (parts != null && parts[0] != null && parts[1] != null) {
                    return new Site(call, parts[0] + ":" + parts[1], null, namespace);
                }
                return new Site(call, null, idProducer, namespace);
            }
        }
        // With no registration in sight, only the namespace is known.
        return new Site(call, null, null, context.namespace());
    }

    /** Switches each call to the keyed bridge; returns whether an id was captured into a new local. */
    private static boolean rewrite(MethodNode m, List<Site> sites) {
        Map<AbstractInsnNode, Integer> captures = new HashMap<>();
        for (Site site : sites) {
            // Push the registered id: a literal, a captured local, or null.
            InsnList arguments = new InsnList();
            if (site.registeredId() != null) {
                arguments.add(new LdcInsnNode(site.registeredId()));
            } else if (site.idToCapture() != null) {
                // Copy the id into a new local right where it is produced. The registration's
                // arguments evaluate left to right, so that is always before the build.
                int local = captures.computeIfAbsent(site.idToCapture(), producer -> {
                    int slot = m.maxLocals++;
                    InsnList copy = new InsnList();
                    copy.add(new InsnNode(Opcodes.DUP));
                    copy.add(new VarInsnNode(Opcodes.ASTORE, slot));
                    m.instructions.insert(producer, copy);
                    return slot;
                });
                arguments.add(new VarInsnNode(Opcodes.ALOAD, local));
            } else {
                arguments.add(new InsnNode(Opcodes.ACONST_NULL));
            }
            // Then push the namespace. Both go right before the call, which now takes them.
            arguments.add(site.namespace() != null ? new LdcInsnNode(site.namespace()) : new InsnNode(Opcodes.ACONST_NULL));
            m.instructions.insertBefore(site.call(), arguments);
            site.call().desc = KEYED_DESC;
        }
        m.maxStack += 2;
        return !captures.isEmpty();
    }

    private static boolean isNameOnlyBridgeCall(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && BRIDGE.equals(call.owner)
                && BRIDGE_METHODS.contains(call.name) && NAME_ONLY_DESC.equals(call.desc);
    }

    /** The next instruction that runs after {@code insn}, skipping casts of its result. */
    private static AbstractInsnNode nextPastCasts(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && (next.getOpcode() < 0 || next.getOpcode() == Opcodes.CHECKCAST)) {
            next = next.getNext();
        }
        return next;
    }

    /** The value under any casts, as the generic {@code register} call's argument often has one. */
    private static AbstractInsnNode throughCasts(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() == Opcodes.CHECKCAST) {
            Frame<SourceValue> atCast = frames[m.instructions.indexOf(insn)];
            insn = atCast == null ? null : single(atCast.getStack(atCast.getStackSize() - 1));
        }
        return insn;
    }

    /** The instruction behind a copied value, such as the Fabric id bridge's stamping {@code dup}. */
    private static AbstractInsnNode throughCopies(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() == Opcodes.DUP) {
            Frame<SourceValue> atDup = frames[m.instructions.indexOf(insn)];
            AbstractInsnNode copied = atDup == null ? null : single(atDup.getStack(atDup.getStackSize() - 1));
            // A dup of a new is left alone, because that is how the analyzer names a constructed object.
            if (copied == null || copied.getOpcode() == Opcodes.NEW) return insn;
            insn = copied;
        }
        return insn;
    }

    /** The one instruction that produced a value, or null when several paths did. */
    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }
}
