/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Lets an old mod build a block or item before its registry id is known, and settles the id where the mod registers it. */
public final class DeferredIdStamper {

    /** The rebuilt class that hands out placeholder ids and replaces them at registration. */
    public static final String RUNTIME = "com/retromod/legacyapi/minecraft/core/LegacyDeferredIds";
    private static final String SETTLE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String ITEM_PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";
    private static final byte[] ITEM_PROPERTIES_BYTES = ITEM_PROPERTIES.getBytes(StandardCharsets.UTF_8);
    private static final byte[] MIXIN_BYTES = "Lorg/spongepowered/asm/mixin/Mixin;".getBytes(StandardCharsets.UTF_8);

    // Minecraft's static registration helpers, and the bridges Retromod already sends them through on 26.x hosts.
    private static final Set<String> MINECRAFT_HELPERS = Set.of(
            "net/minecraft/core/Registry",
            WorldgenTypeBridgeSynthetic.INTERNAL,
            "com/retromod/shim/forge/embedded/LateVanillaRegistration");
    // The register helper NeoForge's and Forge's registration events hand out.
    private static final Set<String> EVENT_HELPERS = Set.of(
            "net/neoforged/neoforge/registries/RegisterEvent$RegisterHelper",
            "net/minecraftforge/registries/RegisterEvent$RegisterHelper");
    private static final Set<String> NAMES = Set.of("register", "registerForHolder");
    private static final Set<String> ID_TYPES = Set.of(
            "Lnet/minecraft/resources/Identifier;", "Lnet/minecraft/resources/ResourceKey;");
    // A bare String is a full id only for Minecraft's helper, which parses it; Retromod's bridges take the id as Object.
    private static final Set<String> HELPER_ID_TYPES = Set.of("Ljava/lang/String;", "Ljava/lang/Object;");
    // Checked as a suffix, so a bridge already moved under a mod's embedded package still matches.
    private static final List<byte[]> OWNER_BYTES = java.util.stream.Stream
            .concat(MINECRAFT_HELPERS.stream(), EVENT_HELPERS.stream())
            .map(owner -> owner.getBytes(StandardCharsets.UTF_8)).toList();

    private DeferredIdStamper() {}

    /** Adds the rebuilt class to the mods this transformer translates; hosts that build ids into blocks and items call it. */
    public static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, RUNTIME);
    }

    /** Repairs one mod class, or returns the same array when nothing matched. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, true);
    }

    /** As above; without {@code builds}, for a mod that sets its own ids, only its registration calls are touched. */
    public static byte[] apply(byte[] classBytes, boolean builds) {
        if (classBytes == null) return null;
        // A mixin's code is merged into Minecraft's class, which cannot reach a mod's embedded copy of the runtime.
        if (isMixin(classBytes)) return classBytes;
        if (!builds) return settleAtRegistrations(classBytes);
        // Runs after the id stamps, so a registration id that is known is set first and the placeholder is only a fallback.
        byte[] deferred = ConstructorIdStamper.apply(classBytes, RUNTIME, "deferBlock", "deferItem");
        return settleAtRegistrations(bindBuiltItems(deferred));
    }

    /** Hands each item Minecraft's constructor just built to {@code LegacyDeferredIds.builtItem}, which ties it to its placeholder. */
    static byte[] bindBuiltItems(byte[] classBytes) {
        if (!StaticFieldIdStamper.containsBytes(classBytes, ITEM_PROPERTIES_BYTES)) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("net/minecraft/") || SyntheticEmbedder.isRetromodClass(node.name)) return classBytes;
        // The item itself is the key, because a block item's description is its block's and names no placeholder.
        int bound = 0;
        for (MethodNode method : node.methods) {
            bound += bindInMethod(node.name, method);
        }
        if (bound == 0) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int bindInMethod(String owner, MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        List<MethodInsnNode> constructors = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && buildsItem(call)) constructors.add(call);
        }
        if (constructors.isEmpty()) return 0;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException unanalyzable) {
            return 0;
        }
        // Decide first, then insert, since inserting shifts the indices the frames are keyed on.
        List<InsnList> bindings = new ArrayList<>();
        List<MethodInsnNode> sites = new ArrayList<>();
        for (MethodInsnNode call : constructors) {
            InsnList binding = bindingFor(method, call, frames[method.instructions.indexOf(call)]);
            if (binding == null) continue;
            bindings.add(binding);
            sites.add(call);
        }
        for (int i = 0; i < sites.size(); i++) method.instructions.insert(sites.get(i), bindings.get(i));
        return sites.size();
    }

    /** Pushes the built item again after the constructor: the copy {@code new} left, or {@code this} after a super call. */
    private static InsnList bindingFor(MethodNode method, MethodInsnNode call, Frame<SourceValue> frame) {
        if (frame == null) return null;
        int receiver = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
        if (receiver < 0) return null;
        SourceValue value = frame.getStack(receiver);
        if (value == null || value.insns.size() != 1) return null;
        AbstractInsnNode producer = value.insns.iterator().next();
        InsnList binding = new InsnList();
        if (producer.getOpcode() == Opcodes.DUP && previousReal(producer) instanceof TypeInsnNode made
                && made.getOpcode() == Opcodes.NEW) {
            binding.add(new InsnNode(Opcodes.DUP));
        } else if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                && method.name.equals("<init>")) {
            binding.add(new VarInsnNode(Opcodes.ALOAD, 0));
        } else {
            return null;
        }
        binding.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "builtItem", "(Ljava/lang/Object;)V", false));
        return binding;
    }

    /** A Minecraft or Retromod-generated constructor that takes item properties, which deferItem passes through. */
    private static boolean buildsItem(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESPECIAL || !call.name.equals("<init>")) return false;
        if (!call.owner.startsWith("net/minecraft/") && !call.owner.startsWith("com/retromod/generated/")) return false;
        for (Type argument : Type.getArgumentTypes(call.desc)) {
            if (argument.getDescriptor().equals(ITEM_PROPERTIES)) return true;
        }
        return false;
    }

    private static boolean isMixin(byte[] classBytes) {
        if (!StaticFieldIdStamper.containsBytes(classBytes, MIXIN_BYTES)) return false;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE);
        } catch (RuntimeException unreadable) {
            return true;
        }
        return LegacyRenderLayerAdapter.isMixin(node);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    /** Copies the id and value of each registration call into {@code LegacyDeferredIds.settle} right before it runs. */
    static byte[] settleAtRegistrations(byte[] classBytes) {
        if (OWNER_BYTES.stream().noneMatch(owner -> StaticFieldIdStamper.containsBytes(classBytes, owner))) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("net/minecraft/") || SyntheticEmbedder.isRetromodClass(node.name)) return classBytes;
        int settled = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !isRegistration(call)) continue;
                // The id and value are the call's last two arguments, so they sit on top of the stack.
                InsnList settle = new InsnList();
                settle.add(new InsnNode(Opcodes.DUP2));
                settle.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "settle", SETTLE_DESC, false));
                method.instructions.insertBefore(call, settle);
                settled++;
            }
        }
        if (settled == 0) return classBytes;
        // The copies leave the stack as it was, so only the maximum stack grows.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A call that registers a value of type Object under an id passed as the argument before it. */
    static boolean isRegistration(MethodInsnNode call) {
        boolean minecraftHelper = endsWithAny(call.owner, MINECRAFT_HELPERS);
        if (!(minecraftHelper || endsWithAny(call.owner, EVENT_HELPERS)) || !NAMES.contains(call.name)) return false;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int count = arguments.length;
        if (count < 2 || !arguments[count - 1].getDescriptor().equals("Ljava/lang/Object;")) return false;
        String id = arguments[count - 2].getDescriptor();
        return ID_TYPES.contains(id) || (minecraftHelper && HELPER_ID_TYPES.contains(id));
    }

    private static boolean endsWithAny(String owner, Set<String> names) {
        for (String name : names) {
            if (owner.endsWith(name)) return true;
        }
        return false;
    }
}
