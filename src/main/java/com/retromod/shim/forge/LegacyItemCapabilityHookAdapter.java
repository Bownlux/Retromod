/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Answers Forge 26.2's item capability hook, {@code getCapabilityProvider}, from an old item's {@code initCapabilities}. */
public final class LegacyItemCapabilityHookAdapter {

    public static final String FLAG = "forge-item-capability-hook";

    static final String FORGE_ITEM = "net/minecraftforge/common/extensions/IForgeItem";
    static final String INIT = "initCapabilities";
    static final String INIT_DESC = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/nbt/CompoundTag;)"
            + "Lnet/minecraftforge/common/capabilities/ICapabilityProvider;";
    static final String HOOK = "getCapabilityProvider";
    static final String HOOK_DESC = "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraftforge/common/capabilities/ICapabilityProvider;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";

    private LegacyItemCapabilityHookAdapter() {}

    /** Registers the pass where the Forge host's items have the new hook and not the old one; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!ForgeHostMembers.lacksMethod(FORGE_ITEM, INIT, INIT_DESC, ForgeHostShape.FIRST_CHECKED_VERSION, host)) return;
        // Offline the target version has answered; a running host must also have the hook that replaced it.
        ClassNode item = host.apply(FORGE_ITEM);
        if (item != null && !LegacyNbtSerializableBridge.declares(item, HOOK, HOOK_DESC)) return;
        t.enablePostProcess(FLAG);
    }

    /** Post-remap pass over one class; the same array when it neither overrides nor calls the old hook. */
    public static byte[] apply(byte[] classBytes, Function<String, byte[]> jarClasses) {
        if (classBytes == null || new String(classBytes, StandardCharsets.ISO_8859_1).indexOf(INIT) < 0) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        Function<String, byte[]> jar = jarClasses == null ? name -> null : jarClasses;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.name.equals(INIT) || !call.desc.equals(INIT_DESC)
                        || call.getOpcode() == Opcodes.INVOKESTATIC || !reachesHost(node, call.owner, jar)) {
                    continue;
                }
                // The host's hook takes no tag, and Forge 26.2 keeps no stack capability data to pass in it.
                method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                call.name = HOOK;
                call.desc = HOOK_DESC;
                changed = true;
            }
        }
        if (overridesOldHook(node) && !LegacyNbtSerializableBridge.declares(node, HOOK, HOOK_DESC)) {
            node.methods.add(hook(node.name));
            changed = true;
        }
        if (!changed) return classBytes;
        // The new method is straight-line code, and the rewritten calls add no branch targets.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** An instance override in a class. A private or static method of that shape is the mod's own helper. */
    private static boolean overridesOldHook(ClassNode node) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return false;
        boolean mixin = LegacyNbtSerializableBridge.isMixin(node);
        return node.methods.stream().anyMatch(m -> m.name.equals(INIT) && m.desc.equals(INIT_DESC)
                && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT)) == 0
                && !(mixin && namesTargetMember(m)));
    }

    /**
     * A mixin's plain initCapabilities is merged into its target as that item's own override, as Supplementaries
     * adds one to its lunch box and quiver, so it gets the hook too. A shadowed or overwritten one is the target's
     * own method, which a Minecraft item on Forge 26.2 no longer has, so a hook calling it would replace the host's.
     */
    private static boolean namesTargetMember(MethodNode method) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            if (annotations.stream().anyMatch(a -> a.desc.equals(SHADOW) || a.desc.equals(OVERWRITE))) return true;
        }
        return false;
    }

    /**
     * {@code getCapabilityProvider(stack)} runs this class's own {@code initCapabilities(stack, null)}. The call
     * is non-virtual, so a subclass with its own override gets its own hook and no body runs twice or recurses.
     */
    private static MethodNode hook(String owner) {
        MethodNode hook = new MethodNode(Opcodes.ACC_PUBLIC, HOOK, HOOK_DESC, null, null);
        hook.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        hook.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, INIT, INIT_DESC, false));
        hook.instructions.add(new InsnNode(Opcodes.ARETURN));
        return hook;
    }

    /** Whether a call to {@code owner.initCapabilities} reaches the host because no mod class on the way declares it. */
    private static boolean reachesHost(ClassNode self, String owner, Function<String, byte[]> jar) {
        String current = owner;
        for (int depth = 0; current != null && depth < 64; depth++) {
            ClassNode node = current.equals(self.name) ? self : read(current, jar);
            // Outside the jar: a Minecraft item, IForgeItem, or another mod's class, which has its own hook.
            if (node == null) return true;
            if (LegacyNbtSerializableBridge.declares(node, INIT, INIT_DESC)) return false;
            current = node.superName;
        }
        return false;
    }

    private static ClassNode read(String name, Function<String, byte[]> jar) {
        byte[] bytes = jar.apply(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }
}
