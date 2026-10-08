/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;

/**
 * Keeps an old item's attribute modifier override working on NeoForge 1.21.
 *
 * <p>Before 1.20.5 an item described its attributes by overriding
 * {@code getDefaultAttributeModifiers(EquipmentSlot)}, or Forge's stack-aware
 * {@code getAttributeModifiers(EquipmentSlot, ItemStack)}, and returning a {@code Multimap}. Both
 * methods are gone: attributes are the {@code minecraft:attribute_modifiers} component, and
 * NeoForge asks {@code getDefaultAttributeModifiers(ItemStack)} when a stack has none. An old
 * override is never called, so a weapon or charm loses its bonuses without any error.
 *
 * <p>An item class with such an override gets a {@code getDefaultAttributeModifiers(ItemStack)}
 * that asks the old method for each of the six pre-1.20.5 slots and collects the answers. The old
 * method's own {@code super} call, which named the removed Minecraft method, reads the parent's
 * attributes from its component instead. Other calls of the removed method on a Minecraft owner
 * read the stack's attributes through NeoForge, so they still reach a mod item's override.
 *
 * <p>The keys are converted both ways: a bare {@code Attribute}, as the attribute bridge returns
 * for an old constant, is wrapped in its registry holder, and the old method sees bare values. A
 * stack that already carries the component, such as a sword built through the tool bridge, keeps
 * the component's attributes, because NeoForge only asks the item when the component is empty.
 * A class that already declares the new method keeps it.
 */
public final class LegacyItemAttributeOverrideAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyItemAttributes";

    static final String ITEM = "net/minecraft/world/item/Item";
    static final String STACK = "net/minecraft/world/item/ItemStack";
    static final String SLOT = "net/minecraft/world/entity/EquipmentSlot";
    static final String SLOT_GROUP = "net/minecraft/world/entity/EquipmentSlotGroup";
    static final String MODIFIERS = "net/minecraft/world/item/component/ItemAttributeModifiers";
    static final String MODIFIERS_BUILDER = MODIFIERS + "$Builder";
    static final String MODIFIERS_ENTRY = MODIFIERS + "$Entry";
    static final String MODIFIER = "net/minecraft/world/entity/ai/attributes/AttributeModifier";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
    static final String COMPONENT_MAP = "net/minecraft/core/component/DataComponentMap";
    static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    static final String EXTENSION = "net/neoforged/neoforge/common/extensions/IItemExtension";
    static final String MULTIMAP = "com/google/common/collect/Multimap";

    static final String OLD_DEFAULTS = "getDefaultAttributeModifiers";
    static final String OLD_DEFAULTS_DESC = "(L" + SLOT + ";)L" + MULTIMAP + ";";
    static final String OLD_STACK_AWARE = "getAttributeModifiers";
    static final String OLD_STACK_AWARE_DESC = "(L" + SLOT + ";L" + STACK + ";)L" + MULTIMAP + ";";
    static final String NEW_DESC = "(L" + STACK + ";)L" + MODIFIERS + ";";

    /** The slots that existed before 1.20.5. BODY is newer, so no old override can answer it. */
    static final String[] LEGACY_SLOTS = {"MAINHAND", "OFFHAND", "FEET", "LEGS", "CHEST", "HEAD"};

    private LegacyItemAttributeOverrideAdapter() {}

    /** Registers the helper when the host has NeoForge's stack hook and no old method. */
    public static void register(RetromodTransformer transformer) {
        if (!hostNeedsRepair()) return;
        transformer.registerSyntheticClass(HELPER, generateHelper());
        LOGGER.info("Bridged pre-1.20.5 item attribute modifier overrides onto NeoForge's stack hook");
    }

    /** Whether {@link #register} added the helper, which is what turns the class repair on. */
    public static boolean isRegistered(java.util.function.Predicate<String> hasSynthetic) {
        return hasSynthetic.test(HELPER);
    }

    /**
     * The host's own classes decide. Offline, where they are not readable, a NeoForge target of
     * 1.21 or 1.21.1 does, which is the shape the helper was checked against.
     */
    static boolean hostNeedsRepair() {
        ClassNode item = ClassResourceInspector.read(ITEM);
        ClassNode extension = ClassResourceInspector.read(EXTENSION);
        if (item == null && extension == null) {
            return offlineNeedsRepair(McReflect.isForceNeoForge(), RetromodVersion.TARGET_MC_VERSION);
        }
        return item != null && extension != null
                && !hasMethod(item, OLD_DEFAULTS, OLD_DEFAULTS_DESC)
                && hasMethod(extension, OLD_DEFAULTS, NEW_DESC)
                && ClassResourceInspector.exists(MODIFIERS_BUILDER);
    }

    static boolean offlineNeedsRepair(boolean neoForgeTarget, String targetVersion) {
        return neoForgeTarget && RetromodVersion.compareMcVersions(targetVersion, "1.21") >= 0
                && RetromodVersion.compareMcVersions(targetVersion, "1.21.2") < 0;
    }

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("AttributeModifiers")) {
            return classBytes;
        }
        return apply(classBytes, RetromodTransformer::currentJarClassInheritsFrom);
    }

    static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) {
            return classBytes;
        }
        boolean changed = retargetRemovedCalls(node);
        MethodNode stackAware = findOverride(node, OLD_STACK_AWARE, OLD_STACK_AWARE_DESC);
        MethodNode defaults = findOverride(node, OLD_DEFAULTS, OLD_DEFAULTS_DESC);
        boolean isItem = node.superName != null && inheritsFrom.test(node.superName, ITEM);
        if (isItem && (stackAware != null || defaults != null) && findOverride(node, OLD_DEFAULTS, NEW_DESC) == null) {
            node.methods.add(stackHook(node, stackAware != null));
            changed = true;
            LOGGER.debug("Moved the attribute modifier override of {} onto the stack hook", node.name);
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Calls of the removed methods on a Minecraft owner. A {@code super} call reads the parent's
     * component, since dispatching would come back to the override that made it. Any other call
     * reads the stack, which reaches a mod item's override through NeoForge.
     */
    private static boolean retargetRemovedCalls(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.startsWith("net/minecraft/")
                        || call.getOpcode() == Opcodes.INVOKESTATIC) {
                    continue;
                }
                boolean defaults = call.name.equals(OLD_DEFAULTS) && call.desc.equals(OLD_DEFAULTS_DESC);
                boolean stackAware = call.name.equals(OLD_STACK_AWARE) && call.desc.equals(OLD_STACK_AWARE_DESC);
                if (!defaults && !stackAware) continue;
                boolean superCall = call.getOpcode() == Opcodes.INVOKESPECIAL;
                String helperName = superCall ? "componentModifiers" : "stackModifiers";
                String helperDesc = "(L" + ITEM + ";L" + SLOT + ";" + (stackAware ? "L" + STACK + ";" : "")
                        + ")L" + MULTIMAP + ";";
                if (stackAware && superCall) {
                    // The parent's component does not depend on the stack.
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                    helperDesc = "(L" + ITEM + ";L" + SLOT + ";)L" + MULTIMAP + ";";
                }
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                        helperName, helperDesc, false));
                changed = true;
            }
        }
        return changed;
    }

    private static MethodNode findOverride(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                return method;
            }
        }
        return null;
    }

    /** {@code getDefaultAttributeModifiers(stack)}: the old answer for every old slot, collected. */
    private static MethodNode stackHook(ClassNode node, boolean stackAware) {
        MethodNode hook = new MethodNode(Opcodes.ACC_PUBLIC, OLD_DEFAULTS, NEW_DESC, null, null);
        InsnList code = hook.instructions;
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MODIFIERS, "builder", "()L" + MODIFIERS_BUILDER + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        for (String slot : LEGACY_SLOTS) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETSTATIC, SLOT, slot, "L" + SLOT + ";"));
            if (stackAware) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, OLD_STACK_AWARE,
                        OLD_STACK_AWARE_DESC, false));
            } else {
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, OLD_DEFAULTS,
                        OLD_DEFAULTS_DESC, false));
            }
            code.add(new FieldInsnNode(Opcodes.GETSTATIC, SLOT, slot, "L" + SLOT + ";"));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "addAll",
                    "(L" + MODIFIERS_BUILDER + ";L" + MULTIMAP + ";L" + SLOT + ";)V", false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MODIFIERS_BUILDER, "build", "()L" + MODIFIERS + ";", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        return hook;
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        emitAddAll(cw);
        emitToMultimap(cw);
        emitComponentModifiers(cw);
        emitStackModifiers(cw, false);
        emitStackModifiers(cw, true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code addAll(builder, multimap, slot)}: each entry under the slot's group, keys as holders. */
    private static void emitAddAll(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addAll",
                "(L" + MODIFIERS_BUILDER + ";L" + MULTIMAP + ";L" + SLOT + ";)V", null, null);
        mv.visitCode();
        Label done = new Label();
        Label loop = new Label();
        Label wrap = new Label();
        Label add = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, done);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SLOT_GROUP, "bySlot", "(L" + SLOT + ";)L" + SLOT_GROUP + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, MULTIMAP, "entries", "()Ljava/util/Collection;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, HOLDER);
        mv.visitJumpInsn(Opcodes.IFEQ, wrap);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        mv.visitVarInsn(Opcodes.ASTORE, 7);
        mv.visitJumpInsn(Opcodes.GOTO, add);
        mv.visitLabel(wrap);
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES, "ATTRIBUTE", "L" + REGISTRY + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, REGISTRY, "wrapAsHolder",
                "(Ljava/lang/Object;)L" + HOLDER + ";", true);
        mv.visitVarInsn(Opcodes.ASTORE, 7);
        mv.visitLabel(add);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MODIFIER);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIERS_BUILDER, "add",
                "(L" + HOLDER + ";L" + MODIFIER + ";L" + SLOT_GROUP + ";)L" + MODIFIERS_BUILDER + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code toMultimap(modifiers, slot)}: the entries that apply to the slot, keyed by bare attribute. */
    private static void emitToMultimap(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "toMultimap",
                "(L" + MODIFIERS + ";L" + SLOT + ";)L" + MULTIMAP + ";", null, null);
        mv.visitCode();
        Label loop = new Label();
        Label done = new Label();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/LinkedHashMultimap", "create",
                "()Lcom/google/common/collect/LinkedHashMultimap;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNULL, done);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIERS, "modifiers", "()Ljava/util/List;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MODIFIERS_ENTRY);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIERS_ENTRY, "slot", "()L" + SLOT_GROUP + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SLOT_GROUP, "test", "(L" + SLOT + ";)Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, loop);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIERS_ENTRY, "attribute", "()L" + HOLDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIERS_ENTRY, "modifier", "()L" + MODIFIER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, MULTIMAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code componentModifiers(item, slot)}: what the item's own component gives the slot. */
    private static void emitComponentModifiers(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "componentModifiers",
                "(L" + ITEM + ";L" + SLOT + ";)L" + MULTIMAP + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "components", "()L" + COMPONENT_MAP + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, COMPONENTS, "ATTRIBUTE_MODIFIERS", "L" + COMPONENT_TYPE + ";");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COMPONENT_MAP, "get",
                "(L" + COMPONENT_TYPE + ";)Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MODIFIERS);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "toMultimap",
                "(L" + MODIFIERS + ";L" + SLOT + ";)L" + MULTIMAP + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code stackModifiers(item, slot[, stack])}: what NeoForge reports for the stack, which is the
     * component when the stack has one and otherwise the item's answer.
     */
    private static void emitStackModifiers(ClassWriter cw, boolean withStack) {
        String desc = "(L" + ITEM + ";L" + SLOT + ";" + (withStack ? "L" + STACK + ";" : "") + ")L" + MULTIMAP + ";";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stackModifiers", desc, null, null);
        mv.visitCode();
        if (withStack) {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
        } else {
            mv.visitTypeInsn(Opcodes.NEW, STACK);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STACK, "<init>", "(Lnet/minecraft/world/level/ItemLike;)V", false);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STACK, "getAttributeModifiers", "()L" + MODIFIERS + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "toMultimap",
                "(L" + MODIFIERS + ";L" + SLOT + ";)L" + MULTIMAP + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
