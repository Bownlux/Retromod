/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyRegistryNbtBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.SignatureRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Keeps Forge 1.20.1 capability, item handler, energy and entity NBT code working on Forge 26.2, which passes registries. */
public final class LegacyNbtSerializableBridge {

    public static final String FLAG = "forge-legacy-nbt-serializable";

    static final String INBT = "net/minecraftforge/common/util/INBTSerializable";
    static final String CAPABILITY_SERIALIZABLE = "net/minecraftforge/common/capabilities/ICapabilitySerializable";
    static final String FORGE_ENTITY = "net/minecraftforge/common/extensions/IForgeEntity";
    static final String ITEM_HANDLER = "net/minecraftforge/items/ItemStackHandler";
    static final String ENERGY_STORAGE = "net/minecraftforge/energy/EnergyStorage";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    static final String TAG = "net/minecraft/nbt/Tag";
    static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    static final String SERIALIZE = "serializeNBT";
    static final String DESERIALIZE = "deserializeNBT";

    static final String LEGACY_NBT = RebuiltApiClasses.ROOT + "forge/common/util/LegacyNBTSerializable";
    static final String LEGACY_CAPABILITY = RebuiltApiClasses.ROOT + "forge/capabilities/LegacyCapabilitySerializable";
    static final String CONTEXT = LegacyRegistryNbtBridge.CONTEXT;

    /** The first version whose Forge mods pass registries; Forge 1.20.1 has the old shape and 1.21.1 the new one. */
    static final String FIRST_PROVIDER_SOURCE = "1.20.5";

    private static final String L_PROVIDER = "L" + PROVIDER + ";";
    private static final String L_TAG = "L" + TAG + ";";
    private static final String L_COMPOUND = "L" + COMPOUND + ";";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Map<String, String> REPLACEMENTS = Map.of(INBT, LEGACY_NBT, CAPABILITY_SERIALIZABLE, LEGACY_CAPABILITY);
    private static final String[] MARKERS = {SERIALIZE, "NBTSerializable", "CapabilitySerializable"};

    /** The host types whose old save and load a mod calls or overrides, and the tag types each took. */
    enum Family {
        HANDLER(true), ENTITY(true), ENERGY(false), SERIALIZABLE(false);

        private final boolean compound;

        Family(boolean compound) {
            this.compound = compound;
        }

        /** Whether the old method had this descriptor: the erased Tag form always, the CompoundTag form where it existed. */
        boolean accepts(String name, String desc) {
            String tag = name.equals(SERIALIZE) ? "()" + L_TAG : "(" + L_TAG + ")V";
            String compoundTag = name.equals(SERIALIZE) ? "()" + L_COMPOUND : "(" + L_COMPOUND + ")V";
            return desc.equals(tag) || (compound && desc.equals(compoundTag));
        }
    }

    private LegacyNbtSerializableBridge() {}

    /** Registers the bridge where the Forge host's INBTSerializable takes registries; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!ForgeHostMembers.lacksMethod(INBT, SERIALIZE, "()" + L_TAG, ForgeHostShape.FIRST_CHECKED_VERSION, host)) return;
        for (String rebuilt : List.of(LEGACY_NBT, LEGACY_CAPABILITY, CONTEXT)) RebuiltApiClasses.registerTree(t, rebuilt);
        t.enablePostProcess(FLAG);
    }

    /** Post-remap pass over one class of a mod built before Forge passed registries; the same array when nothing applies. */
    public static byte[] apply(byte[] classBytes, String sourceVersion, Function<String, byte[]> jarClasses,
            BiPredicate<String, String> inheritsFrom) {
        if (classBytes == null || !oldShapeSource(sourceVersion) || !mentionsNbt(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        Hierarchy hierarchy = new Hierarchy(node, jarClasses, inheritsFrom);
        boolean changed = rewriteCalls(node, hierarchy);
        // A mixin's header and members belong to its target, so only its calls change.
        if (!isMixin(node)) changed |= bridgeClass(node, hierarchy);
        if (!changed) return classBytes;
        // Only new methods have new branch targets, and they carry their own frame.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A mod whose version is unknown keeps the bridge, as it keeps every shim for its host. */
    static boolean oldShapeSource(String sourceVersion) {
        return sourceVersion == null || RetromodVersion.compareMcVersions(sourceVersion, FIRST_PROVIDER_SOURCE) < 0;
    }

    private static boolean mentionsNbt(byte[] classBytes) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        return Arrays.stream(MARKERS).anyMatch(constants::contains);
    }

    // ---- calls ----

    /** Each old save or load call on a host type gets the current registries in front of its arguments. */
    private static boolean rewriteCalls(ClassNode node, Hierarchy hierarchy) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !isNbtName(call.name)) continue;
                Family family = hierarchy.callFamily(call);
                if (family == null || !family.accepts(call.name, call.desc)) continue;
                boolean load = call.name.equals(DESERIALIZE);
                InsnList provider = new InsnList();
                provider.add(new LdcInsnNode(describeCall(node.name, call)));
                provider.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "provider",
                        "(Ljava/lang/String;)" + L_PROVIDER, false));
                // The load takes the registries before the tag already on the stack.
                if (load) provider.add(new InsnNode(Opcodes.SWAP));
                method.instructions.insertBefore(call, provider);
                call.desc = hostShape(call.desc);
                changed = true;
            }
        }
        return changed;
    }

    /** {@code ()R} becomes {@code (Provider)R} and {@code (T)V} becomes {@code (Provider, T)V}. */
    static String hostShape(String oldDesc) {
        return "(" + L_PROVIDER + oldDesc.substring(1);
    }

    private static String describeCall(String caller, MethodInsnNode call) {
        String owner = call.owner.substring(call.owner.lastIndexOf('/') + 1);
        return owner + "." + call.name + (call.name.equals(SERIALIZE) ? "()" : "(tag)") + " in " + caller.replace('/', '.');
    }

    private static boolean isNbtName(String name) {
        return name.equals(SERIALIZE) || name.equals(DESERIALIZE);
    }

    // ---- the class itself ----

    /** Gives an old provider the registry-taking methods, and an old handler, energy or entity override one beside it. */
    private static boolean bridgeClass(ClassNode node, Hierarchy hierarchy) {
        // A class already written for registries keeps Forge's interface.
        if (!listsOldInterface(node) || declaresHostShape(node)) return bridgeHostOverrides(node, hierarchy);
        Family inherited = hierarchy.superFamily();
        // An item handler or energy storage superclass supplies Forge's registry-taking pair itself.
        if (inherited == Family.HANDLER || inherited == Family.ENERGY) return bridgeHostOverrides(node, hierarchy);
        // Minecraft's entity packages also hold goals, inventories and attributes. A class there that has the old
        // pair from the jar is bridged like any provider; one without it relies on an entity's own pair.
        if (inherited == Family.ENTITY && !hierarchy.hasOldPair()) return bridgeHostOverrides(node, hierarchy);
        replaceInterfaces(node);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return true;
        // The bridge on the class wins over each jar's interface default, so a class that reaches two copies has one.
        addBridge(node, SERIALIZE, "(" + L_PROVIDER + ")" + L_TAG, "()" + L_TAG);
        addBridge(node, DESERIALIZE, "(" + L_PROVIDER + L_TAG + ")V", "(" + L_TAG + ")V");
        if (inherited == Family.ENTITY) bridgeHostOverrides(node, hierarchy);
        return true;
    }

    private static boolean listsOldInterface(ClassNode node) {
        return node.interfaces != null && node.interfaces.stream().anyMatch(REPLACEMENTS::containsKey);
    }

    private static boolean declaresHostShape(ClassNode node) {
        return node.methods.stream().anyMatch(m -> isNbtName(m.name) && m.desc.startsWith("(" + L_PROVIDER));
    }

    /** Forge's interfaces become Retromod's subinterfaces with the old methods, keeping their type argument. */
    private static void replaceInterfaces(ClassNode node) {
        List<String> interfaces = new ArrayList<>();
        for (String name : node.interfaces) {
            String replaced = REPLACEMENTS.getOrDefault(name, name);
            if (!interfaces.contains(replaced)) interfaces.add(replaced);
        }
        node.interfaces = interfaces;
        if (node.signature != null) {
            SignatureWriter signature = new SignatureWriter();
            new SignatureReader(node.signature).accept(new SignatureRemapper(signature, new SimpleRemapper(REPLACEMENTS)));
            node.signature = signature.toString();
        }
    }

    /** An old override in a subclass of a handler, energy storage or entity gets the method the host calls now. */
    private static boolean bridgeHostOverrides(ClassNode node, Hierarchy hierarchy) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return false;
        Family family = hierarchy.superFamily();
        if (family == null) return false;
        boolean changed = false;
        for (MethodNode method : new ArrayList<>(node.methods)) {
            // Javac's erased bridges stay as they are: the host's own erased method reaches the typed one.
            if (!isNbtName(method.name) || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE)) != 0 || !family.accepts(method.name, method.desc)) {
                continue;
            }
            changed |= addBridge(node, method.name, hostShape(method.desc), method.desc);
        }
        return changed;
    }

    /**
     * Adds {@code name(registries, args)} that makes the registries current, runs the old method on this
     * object, and puts the previous registries back, also when it throws. Returns false when the class has one.
     */
    static boolean addBridge(ClassNode node, String name, String hostDesc, String oldDesc) {
        if (declares(node, name, hostDesc)) return false;
        Type[] oldArguments = Type.getArgumentTypes(oldDesc);
        boolean returns = Type.getReturnType(oldDesc).getSort() != Type.VOID;
        int previous = 2 + oldArguments.length;
        int scratch = previous + 1;
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, name, hostDesc, null, null);
        InsnList code = bridge.instructions;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode failed = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, previous));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int i = 0; i < oldArguments.length; i++) code.add(new VarInsnNode(Opcodes.ALOAD, 2 + i));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, name, oldDesc, false));
        if (returns) code.add(new VarInsnNode(Opcodes.ASTORE, scratch));
        code.add(end);
        code.add(new VarInsnNode(Opcodes.ALOAD, previous));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "exit", "(Ljava/lang/Object;)V", false));
        if (returns) code.add(new VarInsnNode(Opcodes.ALOAD, scratch));
        code.add(new InsnNode(returns ? Opcodes.ARETURN : Opcodes.RETURN));
        // The finally part: put the registries back and rethrow.
        code.add(failed);
        if ((node.version & 0xFFFF) >= Opcodes.V1_6) {
            List<Object> locals = new ArrayList<>(List.of(node.name, PROVIDER));
            for (Type argument : oldArguments) locals.add(argument.getInternalName());
            locals.add("java/lang/Object");
            code.add(new FrameNode(Opcodes.F_FULL, locals.size(), locals.toArray(), 1, new Object[]{"java/lang/Throwable"}));
        }
        code.add(new VarInsnNode(Opcodes.ASTORE, scratch));
        code.add(new VarInsnNode(Opcodes.ALOAD, previous));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "exit", "(Ljava/lang/Object;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, scratch));
        code.add(new InsnNode(Opcodes.ATHROW));
        bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, null));
        node.methods.add(bridge);
        return true;
    }

    static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    static boolean isMixin(ClassNode node) {
        for (List<AnnotationNode> annotations : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations != null && annotations.stream().anyMatch(a -> a.desc.equals(MIXIN))) return true;
        }
        return false;
    }

    /** Which host type a call or a superclass leads to, read from the jar's own classes, then the host's. */
    static final class Hierarchy {
        private final ClassNode self;
        private final Function<String, byte[]> jarClasses;
        private final BiPredicate<String, String> inheritsFrom;

        Hierarchy(ClassNode self, Function<String, byte[]> jarClasses, BiPredicate<String, String> inheritsFrom) {
            this.self = self;
            this.jarClasses = jarClasses == null ? name -> null : jarClasses;
            this.inheritsFrom = inheritsFrom == null ? (child, ancestor) -> false : inheritsFrom;
        }

        /** The family the class's superclass belongs to, or null. */
        Family superFamily() {
            return walk(self.superName, null, null);
        }

        /** Whether the class or a superclass in the jar implements both erased old methods, which the bridges call. */
        boolean hasOldPair() {
            return jarImplements(SERIALIZE, "()" + L_TAG) && jarImplements(DESERIALIZE, "(" + L_TAG + ")V");
        }

        private boolean jarImplements(String name, String desc) {
            ClassNode current = self;
            for (int depth = 0; current != null && depth < 64; depth++) {
                for (MethodNode method : current.methods) {
                    if (method.name.equals(name) && method.desc.equals(desc)
                            && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                        return true;
                    }
                }
                current = current.superName == null ? null : read(current.superName);
            }
            return false;
        }

        /** The host family an old call reaches, or null when it reaches a mod's own method or no host type. */
        Family callFamily(MethodInsnNode call) {
            if (call.getOpcode() == Opcodes.INVOKEINTERFACE) {
                if (call.owner.equals(INBT) || call.owner.equals(CAPABILITY_SERIALIZABLE)) return Family.SERIALIZABLE;
                return call.owner.equals(FORGE_ENTITY) ? Family.ENTITY : null;
            }
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL) return null;
            return walk(call.owner, call.name, call.desc);
        }

        /**
         * Walks up from {@code start}. A mod class on the way that declares {@code name(desc)} answers the call
         * itself, so the walk gives null; a known host type gives its family.
         */
        private Family walk(String start, String name, String desc) {
            String current = start;
            for (int depth = 0; current != null && depth < 64; depth++) {
                Family family = hostFamily(current);
                if (family != null) return family;
                ClassNode node = current.equals(self.name) ? self : read(current);
                if (node == null) return outsideFamily(current);
                if (name != null && declares(node, name, desc)) return null;
                current = node.superName;
            }
            return null;
        }

        /**
         * A host class named directly. Only entities had the CompoundTag save among Minecraft's own classes, so a
         * call on a class in their packages is an entity's; a superclass there may be no entity, which hasOldPair settles.
         */
        private static Family hostFamily(String name) {
            if (name.equals(ITEM_HANDLER)) return Family.HANDLER;
            if (name.equals(ENERGY_STORAGE)) return Family.ENERGY;
            if (name.startsWith("net/minecraft/world/entity/") || name.startsWith("net/minecraft/client/player/")
                    || name.equals("net/minecraft/server/level/ServerPlayer")) {
                return Family.ENTITY;
            }
            return null;
        }

        // Another mod's base class is placed by the full hierarchy when it can be read. Minecraft's and Forge's own
        // classes are placed by name alone, so a running game and an offline transform decide alike.
        private Family outsideFamily(String name) {
            if (name.startsWith("java/") || name.startsWith("net/minecraft/") || name.startsWith("net/minecraftforge/")) {
                return null;
            }
            if (inheritsFrom.test(name, ITEM_HANDLER)) return Family.HANDLER;
            if (inheritsFrom.test(name, ENERGY_STORAGE)) return Family.ENERGY;
            return inheritsFrom.test(name, ENTITY) ? Family.ENTITY : null;
        }

        private ClassNode read(String name) {
            byte[] bytes = jarClasses.apply(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }
}
