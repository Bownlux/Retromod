/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Gives old command contexts, registry providers and registry access the members 1.20.5 and 1.21.2 made them answer. */
public final class LegacyRegistryProviderAdapter {

    /** Post-process flag: a pre-1.20.5 command context answers {@code lookup} through its own {@code holderLookup}. */
    public static final String CONTEXT_FLAG = "legacy-command-context";
    /** Post-process flag: the host lists registry keys through {@code listRegistryKeys}, the 1.21.2 name. */
    public static final String KEYS_FLAG = "legacy-registry-keys";
    /** Post-process flag: the host's registry access answers {@code lookup(key)}, the 1.21.2 name of {@code registry(key)}. */
    public static final String ACCESS_FLAG = "legacy-registry-access";
    /** Post-process flag: the host's command context declares {@code enabledFeatures()}, added in 1.21.2. */
    public static final String FEATURES_FLAG = "legacy-command-features";

    static final String CONTEXT = "net/minecraft/commands/CommandBuildContext";
    static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    static final String GETTER_PROVIDER = "net/minecraft/core/HolderGetter$Provider";
    static final String LOOKUP = "net/minecraft/core/HolderLookup";
    static final String REGISTRY_LOOKUP = "net/minecraft/core/HolderLookup$RegistryLookup";
    static final String ACCESS = "net/minecraft/core/RegistryAccess";
    static final String FROZEN = "net/minecraft/core/RegistryAccess$Frozen";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/core/LegacyHolderLookups";

    static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    static final String STREAM = "()Ljava/util/stream/Stream;";
    static final String BY_KEY = "(" + KEY + ")Ljava/util/Optional;";
    static final String HOLDER_LOOKUP = "(" + KEY + ")L" + LOOKUP + ";";
    static final String FILTER = "(Ljava/util/function/Predicate;)L" + LOOKUP + ";";
    static final String FEATURES = "()Lnet/minecraft/world/flag/FeatureFlagSet;";
    static final String GETTER = "()L" + GETTER_PROVIDER + ";";
    private static final String ILLEGAL_STATE = "java/lang/IllegalStateException";

    /** The release that made the command context a registry provider and moved filterElements to registry lookups. */
    static final String CONTEXT_VERSION = "1.20.5";
    /** The release that renamed the key list and registry(key), dropped asGetterLookup, and added enabledFeatures. */
    static final String KEYS_VERSION = "1.21.2";
    private static final List<String> PROVIDERS = List.of(PROVIDER, CONTEXT, ACCESS, FROZEN);

    private LegacyRegistryProviderAdapter() {}

    /** Registers the 1.20.5 changes on hosts that made them; offline, the target version decides. */
    public static void registerCommandContext(RetromodTransformer t) {
        registerCommandContext(t, ClassResourceInspector::read);
    }

    static void registerCommandContext(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean context = lacks(host.apply(CONTEXT), "holderLookup", HOLDER_LOOKUP, CONTEXT_VERSION);
        boolean filter = lacks(host.apply(LOOKUP), "filterElements", FILTER, CONTEXT_VERSION);
        if (!context && !filter) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        if (context) {
            // The provider lookup reaches an old context's own holderLookup through the bridge apply adds.
            t.registerMethodRedirect(CONTEXT, "holderLookup", HOLDER_LOOKUP, HELPER, "holderLookup",
                    "(L" + PROVIDER + ";" + KEY + ")L" + LOOKUP + ";", true);
            t.enablePostProcess(CONTEXT_FLAG);
        }
        if (filter) {
            // A call on a registry lookup typed variable names that owner with the old return type.
            for (String owner : List.of(LOOKUP, REGISTRY_LOOKUP)) {
                t.registerMethodRedirect(owner, "filterElements", FILTER, HELPER, "filterElements",
                        "(L" + LOOKUP + ";Ljava/util/function/Predicate;)L" + LOOKUP + ";", true);
            }
        }
    }

    /** Registers the 1.21.2 changes on hosts that made them; offline, the target version decides. */
    public static void registerProviderChanges(RetromodTransformer t) {
        registerProviderChanges(t, ClassResourceInspector::read);
    }

    static void registerProviderChanges(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode provider = host.apply(PROVIDER);
        if (has(provider, "listRegistryKeys", STREAM, KEYS_VERSION)) {
            // The old name now streams registry lookups, so only a mod built before the rename means the keys.
            for (String owner : PROVIDERS) {
                t.registerMethodRedirect(owner, "listRegistries", STREAM, owner, "listRegistryKeys", STREAM);
                t.limitRedirectToModsBefore(owner, "listRegistries", STREAM, KEYS_VERSION);
            }
            t.enablePostProcess(KEYS_FLAG);
        }
        if (lacks(provider, "asGetterLookup", GETTER, KEYS_VERSION)) {
            RebuiltApiClasses.registerTree(t, HELPER);
            for (String owner : List.of(PROVIDER, ACCESS, FROZEN)) {
                t.registerMethodRedirect(owner, "asGetterLookup", GETTER, HELPER, "asGetterLookup",
                        "(L" + PROVIDER + ";)L" + GETTER_PROVIDER + ";", true);
            }
        }
        ClassNode access = host.apply(ACCESS);
        if (has(access, "lookup", BY_KEY, KEYS_VERSION) && lacks(access, "registry", BY_KEY, KEYS_VERSION)) {
            t.enablePostProcess(ACCESS_FLAG);
        }
        if (has(host.apply(CONTEXT), "enabledFeatures", FEATURES, KEYS_VERSION)) {
            RebuiltApiClasses.registerTree(t, HELPER);
            t.enablePostProcess(FEATURES_FLAG);
        }
    }

    /** Whether any flag this adapter reads is on. */
    public static boolean enabled(Predicate<String> flags) {
        return flags.test(CONTEXT_FLAG) || flags.test(KEYS_FLAG) || flags.test(ACCESS_FLAG) || flags.test(FEATURES_FLAG);
    }

    /** Post-remap pass over a class that implements a provider interface itself; {@code modVersion} is null when unknown. */
    public static byte[] apply(byte[] classBytes, Predicate<String> flags, String modVersion) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 || !implementsAny(List.of(reader.getInterfaces()))) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // A mixin's members are merged into its target, which is the host's own provider at worst.
        if (OldRenderShapes.isMixin(node)) return classBytes;
        boolean changed = false;
        boolean old = builtBeforeKeys(node, modVersion);
        // An old key list would answer the host's new listRegistries with keys where it wants registry lookups.
        if (flags.test(KEYS_FLAG) && old) changed |= rename(node, "listRegistries", "listRegistryKeys", STREAM);
        // registry(key) is the abstract lookup(key) of a registry access built before 1.21.2.
        if (flags.test(ACCESS_FLAG) && old && (node.interfaces.contains(ACCESS) || node.interfaces.contains(FROZEN))) {
            changed |= rename(node, "registry", "lookup", BY_KEY);
        }
        if (node.interfaces.contains(CONTEXT)) changed |= bridgeContext(node, flags, old);
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Adds what an old command context lacks: the provider lookup, a key list, and its features. */
    private static boolean bridgeContext(ClassNode node, Predicate<String> flags, boolean old) {
        boolean changed = false;
        // Before 1.20.5 a context answered holderLookup(key) and could not list its registries.
        if (flags.test(CONTEXT_FLAG) && declaresConcrete(node, "holderLookup", HOLDER_LOOKUP)
                && !declares(node, "lookup", BY_KEY)) {
            node.methods.add(lookupBridge(node.name));
            String keyList = flags.test(KEYS_FLAG) ? "listRegistryKeys" : "listRegistries";
            if (!declares(node, keyList, STREAM)) node.methods.add(helperCall(keyList, STREAM, "registryKeys"));
            changed = true;
        }
        // Before 1.21.2 a context had no enabledFeatures, which the host now asks every context for.
        if (flags.test(FEATURES_FLAG) && (old || changed) && !declares(node, "enabledFeatures", FEATURES)) {
            node.methods.add(helperCall("enabledFeatures", FEATURES, "enabledFeatures"));
            changed = true;
        }
        return changed;
    }

    /** {@code lookup(key)}, which hands what the class's own {@code holderLookup(key)} finds to the helper. */
    private static MethodNode lookupBridge(String owner) {
        MethodNode bridge = bridge("lookup", BY_KEY);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode missing = new LabelNode();
        // The old holderLookup throws for a registry the context lacks, where the host's lookup answers empty.
        bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, missing, ILLEGAL_STATE));
        bridge.instructions.add(start);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "holderLookup", HOLDER_LOOKUP, false));
        bridge.instructions.add(end);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "lookup",
                "(L" + LOOKUP + ";" + KEY + ")Ljava/util/Optional;", false));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        // The class is written with its own frames, so the handler's frame is stated here.
        bridge.instructions.add(missing);
        bridge.instructions.add(new FrameNode(Opcodes.F_NEW, 2,
                new Object[] {owner, Type.getType(KEY).getInternalName()}, 1, new Object[] {ILLEGAL_STATE}));
        bridge.instructions.add(new InsnNode(Opcodes.POP));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                "()Ljava/util/Optional;", false));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        return bridge;
    }

    /** {@code name(desc)}, answered by the helper's no-argument {@code helperName}. */
    private static MethodNode helperCall(String name, String desc, String helperName) {
        MethodNode bridge = bridge(name, desc);
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, helperName, desc, false));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        return bridge;
    }

    private static MethodNode bridge(String name, String desc) {
        return new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE, name, desc, null, null);
    }

    /** Renames a concrete instance method the host renamed, with the class's own calls to it, unless the new name is taken. */
    static boolean rename(ClassNode node, String oldName, String newName, String desc) {
        if (!declaresConcrete(node, oldName, desc) || declares(node, newName, desc)) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(oldName) && method.desc.equals(desc)) method.name = newName;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.equals(oldName) && call.desc.equals(desc)) {
                    call.name = newName;
                }
            }
        }
        return true;
    }

    /** Whether the class was built before 1.21.2; without a version, only a concrete class counts, since it must answer every abstract member. */
    static boolean builtBeforeKeys(ClassNode node, String modVersion) {
        if (modVersion == null) return (node.access & Opcodes.ACC_ABSTRACT) == 0;
        return RetromodVersion.compareMcVersions(modVersion, KEYS_VERSION) < 0;
    }

    private static boolean implementsAny(List<String> interfaces) {
        for (String provider : PROVIDERS) {
            if (interfaces.contains(provider)) return true;
        }
        return false;
    }

    /** Whether the host class lacks the method; offline, whether the target is at least {@code since}. */
    static boolean lacks(ClassNode hostClass, String name, String desc, String since) {
        if (hostClass == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        return !declares(hostClass, name, desc);
    }

    /** Whether the host class declares the method; offline, whether the target is at least {@code since}. */
    static boolean has(ClassNode hostClass, String name, String desc, String since) {
        if (hostClass == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        return declares(hostClass, name, desc);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean declaresConcrete(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                return true;
            }
        }
        return false;
    }
}
