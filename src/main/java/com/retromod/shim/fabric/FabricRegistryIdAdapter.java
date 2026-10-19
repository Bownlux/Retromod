/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.shim.common.StaticFieldIdStamper;
import com.retromod.shim.common.StaticFieldRegistryIds;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
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

/** Makes a Fabric mod's registry id readable while its block or item is being built. */
public final class FabricRegistryIdAdapter {

    private FabricRegistryIdAdapter() {}

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String BRIDGE = FabricRegistryIdSynthetic.INTERNAL;

    /** Encoded once; this runs on every class of every Fabric jar. */
    private static final byte[] REGISTER = "register".getBytes(StandardCharsets.UTF_8);

    /** One registration call worth rewriting, and the instruction that produced its id. */
    private record Stamp(MethodInsnNode call, AbstractInsnNode idProducer) {}

    /** Repairs one class, or returns the same array when nothing matched. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, StaticFieldRegistryIds.NONE);
    }

    /** Repairs one class, also stamping the fields that the rest of the jar registers. */
    public static byte[] apply(byte[] classBytes, StaticFieldRegistryIds jarIds) {
        if (classBytes == null) return classBytes;
        // jarIds holds the ids other classes of the jar register static fields under, so a field
        // filled here and registered elsewhere is stamped too.
        StaticFieldRegistryIds jar = jarIds != null ? jarIds : StaticFieldRegistryIds.NONE;
        // Skip the parse when the class has no register call, no store into a field the jar
        // registers, and no static initializer.
        if (indexOf(classBytes, REGISTER) < 0 && !jar.mayStoreInto(classBytes)
                && !StaticFieldIdStamper.mayHaveClassInitializer(classBytes)) {
            return classBytes;
        }

        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }

        // Found before any stamping, because stamping inserts instructions into these helpers.
        Map<String, Helper> helpers = registrationHelpers(cn);
        int stamped = 0;
        Map<String, String[]> ownIds = ownFieldIds(cn.name, registeredStaticFields(cn, helpers), jar);
        // Each method gets every kind of stamp: around stores into registered static fields,
        // around helper and register calls, and on Properties handed to a constructor.
        for (MethodNode m : cn.methods) {
            boolean clinit = m.name.equals("<clinit>");
            stamped += StaticFieldIdStamper.stamp(cn.name, m,
                    put -> fieldId(put, cn.name, clinit, ownIds, jar), BRIDGE);
            stamped += stampHelperCalls(cn.name, m, helpers);
            stamped += stampMethod(cn.name, m);
            stamped += stampConstructorArguments(m);
        }
        // After the stamps, so the class's own stamped statements run inside the isolated region.
        boolean isolated = StaticFieldIdStamper.isolateClassInitializer(cn, BRIDGE);
        if (stamped == 0 && !isolated) return classBytes;
        // Frames are recomputed only when a stamp was added.
        return StaticFieldIdStamper.write(classBytes, cn, stamped > 0);
    }

    /** This class's static fields with an id, from its own registrations and from the rest of the jar. */
    private static Map<String, String[]> ownFieldIds(String owner, Map<String, String[]> registeredHere,
                                                     StaticFieldRegistryIds jarIds) {
        Map<String, String[]> ids = new HashMap<>(registeredHere);
        Set<String> conflicting = new java.util.HashSet<>();
        jarIds.fieldsOf(owner).forEach((field, id) -> {
            String[] previous = ids.putIfAbsent(field, id);
            if (previous != null && !java.util.Arrays.equals(previous, id)) conflicting.add(field);
        });
        // A field the two sources give different ids is left out.
        conflicting.forEach(ids::remove);
        return ids;
    }

    /** The id to stamp around a static field store, or null to leave the store alone. */
    private static String[] fieldId(org.objectweb.asm.tree.FieldInsnNode put, String owner, boolean clinit,
                                    Map<String, String[]> ownIds, StaticFieldRegistryIds jarIds) {
        // A field the jar index knows is stamped wherever it is filled, as when a loader class
        // builds an item straight into the common class's field.
        if (!put.owner.equals(owner)) return jarIds.fieldsOf(put.owner).get(put.name);
        // The class's own fields keep the long-standing rule of being stamped in its static
        // initializer, unless the jar index knows them too.
        return clinit || jarIds.fieldsOf(owner).containsKey(put.name) ? ownIds.get(put.name) : null;
    }

    private static final String BLOCK_PROPS =
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String ITEM_PROPS = "net/minecraft/world/item/Item$Properties";

    /**
     * Stamps the id onto a {@code Properties} being handed to a constructor.
     *
     * <p>Only when it is the LAST argument, so it is on top of the stack and the call can be spliced
     * in without disturbing anything under it. That covers the shapes mods actually write, such as
     * {@code new Block(props)} and {@code new StairBlock(state, props)}.
     */
    private static int stampConstructorArguments(MethodNode m) {
        // Stamping where the Properties is created is not enough. A mod may build one Properties
        // into a shared static field before any registration runs and hand it to several blocks,
        // as Hearth and Home does. Right before the constructor is always inside the registration.
        List<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) continue;
            if (call.desc.endsWith("L" + BLOCK_PROPS + ";)V")
                    || call.desc.endsWith("L" + ITEM_PROPS + ";)V")) {
                targets.add(call);
            }
        }
        // The stamp takes and returns the same Properties, so it leaves the stack as it was.
        for (MethodInsnNode call : targets) {
            boolean block = call.desc.endsWith("L" + BLOCK_PROPS + ";)V");
            String type = block ? BLOCK_PROPS : ITEM_PROPS;
            m.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                    block ? "stampBlock" : "stampItem", "(L" + type + ";)L" + type + ";", false));
        }
        return targets.size();
    }

    private static int stampMethod(String owner, MethodNode m) {
        if (m.instructions == null || m.instructions.size() == 0) return 0;

        // A mod builds the object inside Registry.register(registry, id, object), so the block fails
        // in its own constructor before any Retromod code runs. Arguments run left to right, though,
        // so the id is already on the stack when the object starts.
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && isRegistration(call)) calls.add(call);
        }
        if (calls.isEmpty()) return 0;

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (Throwable unanalyzable) {
            return 0; // leave every call exactly as it was
        }

        // Decide first, mutate second: inserting shifts indices the frames are keyed on.
        List<Stamp> stamps = new ArrayList<>();
        for (MethodInsnNode call : calls) {
            int idx = m.instructions.indexOf(call);
            if (idx < 0 || idx >= frames.length) continue;
            Frame<SourceValue> f = frames[idx];
            if (f == null) continue; // unreachable
            // Stack at the call is [registry, id, object]; the id sits one below the top.
            int idSlot = f.getStackSize() - 2;
            if (idSlot < 0) continue;
            SourceValue id = f.getStack(idSlot);
            // Only an id with one producer. A merged one came from several branches, and a copy
            // would stamp whichever the analyzer saw rather than the one actually used.
            if (id == null || id.insns.size() != 1) continue;
            stamps.add(new Stamp(call, id.insns.iterator().next()));
        }
        if (stamps.isEmpty()) return 0;

        // Copy the id into the bridge's thread local right after it is made, and clear it once
        // the registration returns.
        for (Stamp s : stamps) {
            m.instructions.insert(s.idProducer(), new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BRIDGE, "push", "(L" + IDENTIFIER + ";)V", false));
            m.instructions.insert(s.idProducer(), new InsnNode(Opcodes.DUP));
            m.instructions.insert(s.call(), new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BRIDGE, "pop", "()V", false));
        }
        return stamps.size();
    }

    /** A static helper in the same class that registers under {@code namespace:<its String argument>}. */
    private record Helper(String namespace, int nameArg) {}

    /**
     * Finds the mod's own registration helpers, such as {@code registerBlock(String name, Block block)}
     * or {@code register(Block block, String name)}, whose id is built from a namespace constant and
     * one of their {@code String} parameters.
     *
     * <p>The block is built at the helper's call site, before the helper runs, so the helper's own
     * {@code Registry.register} stamp comes too late for it.
     */
    private static Map<String, Helper> registrationHelpers(ClassNode cn) {
        Map<String, Helper> helpers = new HashMap<>();
        for (MethodNode m : cn.methods) {
            if ((m.access & Opcodes.ACC_STATIC) == 0 || m.instructions == null) continue;
            List<MethodInsnNode> calls = new ArrayList<>();
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && isRegistration(call)) calls.add(call);
            }
            if (calls.isEmpty()) continue;
            Frame<SourceValue>[] frames;
            try {
                frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, m);
            } catch (Throwable unanalyzable) {
                continue;
            }
            for (MethodInsnNode call : calls) {
                Helper helper = helperFor(m, frames, call);
                if (helper != null) {
                    helpers.put(m.name + m.desc, helper);
                    break;
                }
            }
        }
        return helpers;
    }

    private static Helper helperFor(MethodNode m, Frame<SourceValue>[] frames, MethodInsnNode register) {
        Frame<SourceValue> atRegister = frames[m.instructions.indexOf(register)];
        if (atRegister == null) return null;
        AbstractInsnNode id = throughLocal(m, frames,
                single(atRegister.getStack(atRegister.getStackSize() - 2)));
        if (!(id instanceof MethodInsnNode factory) || factory.getOpcode() != Opcodes.INVOKESTATIC
                || !factory.desc.equals("(Ljava/lang/String;Ljava/lang/String;)L" + factory.owner + ";")
                || !(IDENTIFIER.equals(factory.owner) || RESOURCE_LOCATION.equals(factory.owner))) {
            return null;
        }
        // The namespace has to be a constant.
        Frame<SourceValue> atFactory = frames[m.instructions.indexOf(factory)];
        int top = atFactory.getStackSize();
        if (!(single(atFactory.getStack(top - 2)) instanceof LdcInsnNode ns) || !(ns.cst instanceof String namespace)) {
            return null;
        }
        // The path has to be one of the helper's own String parameters.
        int nameArg = StaticFieldRegistryIds.parameterRead(m, frames, single(atFactory.getStack(top - 1)),
                "Ljava/lang/String;");
        return nameArg < 0 ? null : new Helper(namespace, nameArg);
    }

    /** Follows {@code aload n} back to the value its only {@code astore n} saved. */
    private static AbstractInsnNode throughLocal(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        if (!(insn instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return insn;
        Frame<SourceValue> atLoad = frames[m.instructions.indexOf(load)];
        if (atLoad == null || !(single(atLoad.getLocal(load.var)) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) {
            return insn;
        }
        Frame<SourceValue> atStore = frames[m.instructions.indexOf(store)];
        return atStore == null ? insn : single(atStore.getStack(atStore.getStackSize() - 1));
    }

    /** Static fields of this class that some method registers, keyed to {@code {namespace, path}}. */
    private static Map<String, String[]> registeredStaticFields(ClassNode cn, Map<String, Helper> helpers) {
        // Most older mods build every block and item into a static field when the class loads and
        // register the fields later, as in registerItem(AMETHYST_SWORD, "amethyst_sword"). The id is
        // never on the stack while the item is built, so it is read off the registration and stamped
        // where the field is filled. A field another class registers comes from StaticFieldRegistryIds.
        Map<String, String[]> ids = new HashMap<>();
        Set<String> conflicting = new java.util.HashSet<>();
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) continue;
            Frame<SourceValue>[] frames = null;
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                boolean helperCall = call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(cn.name)
                        && helpers.containsKey(call.name + call.desc);
                if (!helperCall && !isRegistration(call)) continue;
                if (frames == null) {
                    try {
                        frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, m);
                    } catch (Throwable unanalyzable) {
                        break;
                    }
                }
                Frame<SourceValue> f = frames[m.instructions.indexOf(call)];
                if (f == null) continue;
                int argCount = Type.getArgumentTypes(call.desc).length;
                int base = f.getStackSize() - argCount;
                // Only a literal id counts, read from the helper's name argument or from
                // Registry.register(ITEM, new Identifier("mod", "sword"), SWORD).
                String[] id = null;
                String field = null;
                if (helperCall) {
                    Helper helper = helpers.get(call.name + call.desc);
                    if (single(f.getStack(base + helper.nameArg())) instanceof LdcInsnNode name
                            && name.cst instanceof String path) {
                        id = new String[]{helper.namespace(), path};
                    }
                    for (int arg = 0; arg < argCount && field == null; arg++) {
                        field = ownStaticField(cn.name, single(f.getStack(base + arg)));
                    }
                } else {
                    id = literalId(m, frames, throughLocal(m, frames, single(f.getStack(f.getStackSize() - 2))));
                    field = ownStaticField(cn.name, single(f.getStack(f.getStackSize() - 1)));
                }
                if (id == null || field == null) continue;
                String[] previous = ids.putIfAbsent(field, id);
                if (previous != null && !java.util.Arrays.equals(previous, id)) conflicting.add(field);
            }
        }
        // A field registered under two different ids is skipped.
        conflicting.forEach(ids::remove);
        return ids;
    }

    private static String ownStaticField(String owner, AbstractInsnNode insn) {
        return insn instanceof org.objectweb.asm.tree.FieldInsnNode get && get.getOpcode() == Opcodes.GETSTATIC
                && get.owner.equals(owner) ? get.name : null;
    }

    /** {@code {namespace, path}} for {@code Identifier.fromNamespaceAndPath("ns", "path")}, else null. */
    private static String[] literalId(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode producer) {
        if (!(producer instanceof MethodInsnNode factory) || factory.getOpcode() != Opcodes.INVOKESTATIC
                || !factory.desc.equals("(Ljava/lang/String;Ljava/lang/String;)L" + factory.owner + ";")
                || !(IDENTIFIER.equals(factory.owner) || RESOURCE_LOCATION.equals(factory.owner))) {
            return null;
        }
        Frame<SourceValue> f = frames[m.instructions.indexOf(factory)];
        int top = f.getStackSize();
        return single(f.getStack(top - 2)) instanceof LdcInsnNode ns && ns.cst instanceof String namespace
                && single(f.getStack(top - 1)) instanceof LdcInsnNode path && path.cst instanceof String value
                ? new String[]{namespace, value} : null;
    }

    /** The {@code new} behind a value, which the analyzer attributes to the {@code dup} after it. */
    private static AbstractInsnNode createdBy(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        if (insn != null && insn.getOpcode() == Opcodes.DUP) {
            Frame<SourceValue> atDup = frames[m.instructions.indexOf(insn)];
            insn = atDup == null ? null : single(atDup.getStack(atDup.getStackSize() - 1));
        }
        return insn != null && insn.getOpcode() == Opcodes.NEW ? insn : null;
    }

    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }

    /**
     * At each call to a registration helper whose name is a literal and whose block or item is
     * built right there with {@code new}, stamps {@code namespace:name} before that {@code new} and
     * clears it when the helper returns.
     */
    private static int stampHelperCalls(String owner, MethodNode m, Map<String, Helper> helpers) {
        if (helpers.isEmpty() || m.instructions == null || m.instructions.size() == 0) return 0;
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(owner) && helpers.containsKey(call.name + call.desc)) {
                calls.add(call);
            }
        }
        if (calls.isEmpty()) return 0;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
        } catch (Throwable unanalyzable) {
            return 0;
        }
        record HelperStamp(MethodInsnNode call, AbstractInsnNode firstNew, String namespace, String path) {}
        List<HelperStamp> stamps = new ArrayList<>();
        for (MethodInsnNode call : calls) {
            Frame<SourceValue> f = frames[m.instructions.indexOf(call)];
            if (f == null) continue;
            Helper helper = helpers.get(call.name + call.desc);
            int argCount = Type.getArgumentTypes(call.desc).length;
            int base = f.getStackSize() - argCount;
            if (!(single(f.getStack(base + helper.nameArg())) instanceof LdcInsnNode name)
                    || !(name.cst instanceof String path)) {
                continue;
            }
            AbstractInsnNode firstNew = null;
            for (int arg = 0; arg < argCount; arg++) {
                if (createdBy(m, frames, single(f.getStack(base + arg))) instanceof TypeInsnNode created
                        && (firstNew == null || m.instructions.indexOf(created) < m.instructions.indexOf(firstNew))) {
                    firstNew = created;
                }
            }
            if (firstNew != null) stamps.add(new HelperStamp(call, firstNew, helper.namespace(), path));
        }
        for (HelperStamp stamp : stamps) {
            InsnList push = new InsnList();
            push.add(new LdcInsnNode(stamp.namespace()));
            push.add(new LdcInsnNode(stamp.path()));
            push.add(new MethodInsnNode(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false));
            push.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "push", "(L" + IDENTIFIER + ";)V", false));
            m.instructions.insertBefore(stamp.firstNew(), push);
            m.instructions.insert(stamp.call(), new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "pop", "()V", false));
        }
        return stamps.size();
    }

    /**
     * Whether this is a registration whose second argument is the entry's id.
     *
     * <p>The {@code ResourceKey} overload is deliberately not matched: its key is already complete,
     * so a mod using it does not hit the missing-id failure in the first place.
     */
    private static boolean isRegistration(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC || !"register".equals(call.name)) return false;
        boolean knownOwner = REGISTRY.equals(call.owner) || call.owner.endsWith("/WorldgenTypeBridge");
        if (!knownOwner) return false;
        return call.desc.contains(IDENTIFIER) || call.desc.contains(RESOURCE_LOCATION);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
