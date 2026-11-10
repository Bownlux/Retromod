/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinLazyStaticInitializer;
import com.retromod.shim.common.LegacyEarlyItemStackBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.forge.ForgeHostShape;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

/** Keeps NeoForge 1.21's stack-aware crafting remainder methods working where an item answers {@code getCraftingRemainder(instance)}. */
public final class LegacyCraftingRemainderBridge {

    static final String CALLS = "com/retromod/generated/LegacyCraftingRemainders";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String EXTENSION = "net/neoforged/neoforge/common/extensions/IItemExtension";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String INSTANCE = "net/minecraft/world/item/ItemInstance";
    private static final String TEMPLATE = "net/minecraft/world/item/ItemStackTemplate";
    static final String STACK_CALLS = "com/retromod/generated/LegacyStackRemainders";
    static final String FORGE_STACK = "net/minecraftforge/common/extensions/IForgeItemStack";
    static final String HAS = "hasCraftingRemainingItem";
    static final String GET = "getCraftingRemainingItem";
    static final String HAS_DESC = "(L" + STACK + ";)Z";
    static final String GET_DESC = "(L" + STACK + ";)L" + STACK + ";";
    static final String NEW_DESC = "(L" + INSTANCE + ";)L" + TEMPLATE + ";";

    private LegacyCraftingRemainderBridge() {}

    /** Registers on a host whose items answer the remainder from an item instance. */
    public static void register(RetromodTransformer t) {
        ClassNode extension = ClassResourceInspector.read(EXTENSION);
        // NeoForge 26.1 answers the remainder from an item instance; offline the target decides.
        boolean instanceRemainder = extension == null ? OfflineHostVersion.neoForgeFrom("26.1")
                : extension.methods.stream().anyMatch(m -> m.name.equals("getCraftingRemainder") && m.desc.equals(NEW_DESC))
                        && extension.methods.stream().noneMatch(m -> m.name.equals(HAS));
        if (!instanceRemainder) return;
        t.registerSyntheticClass(CALLS, generate());
        for (String owner : new String[]{ITEM, EXTENSION}) {
            String receiver = "(L" + ITEM + ";";
            t.registerMethodRedirect(owner, HAS, HAS_DESC, CALLS, "hasRemainder", receiver + HAS_DESC.substring(1), true);
            t.registerMethodRedirect(owner, GET, GET_DESC, CALLS, "remainder", receiver + GET_DESC.substring(1), true);
        }
        // An override's super call reads the vanilla remainder; the instance form would run the override again.
        t.registerSuperCallHelper(CALLS, "hasRemainder", "(L" + ITEM + ";" + HAS_DESC.substring(1), "itemHasRemainder");
        t.registerSuperCallHelper(CALLS, "remainder", "(L" + ITEM + ";" + GET_DESC.substring(1), "itemRemainder");
    }

    /** Forge 26.2 and newer: the stack's old remainder getters, which its extension answers with a template now. */
    public static void registerForge(RetromodTransformer t) {
        // Farmer's Delight reads them when a bowl of soup or a cooking pot meal is finished. The boundary
        // is checked on Forge 26.2 and 26.3 only, since no Forge 1.21.x host was available to check.
        if (!ForgeHostShape.matches(List.of(FORGE_STACK), List.of())) return;
        ClassNode stack = ClassResourceInspector.read(FORGE_STACK);
        if (stack != null && stack.methods.stream().anyMatch(m -> m.name.equals(GET) && m.desc.equals("()L" + STACK + ";"))) {
            return;
        }
        // A cooking recipe reads its result's remainder while recipes load, before the items' components are bound.
        boolean earlyStacks = MixinLazyStaticInitializer.hostBindsComponentsLate(RetromodVersion.TARGET_MC_VERSION, t.getFuzzyResolver());
        if (earlyStacks) RebuiltApiClasses.registerTree(t, LegacyEarlyItemStackBridge.HELPER);
        t.registerSyntheticClass(STACK_CALLS, generateStackCalls(earlyStacks));
        t.registerMethodRedirect(STACK, GET, "()L" + STACK + ";", STACK_CALLS, "remainder", GET_DESC, true);
        t.registerMethodRedirect(STACK, HAS, "()Z", STACK_CALLS, "hasRemainder", HAS_DESC, true);
    }

    /** {@code remainder(stack)} and {@code hasRemainder(stack)}, read from the stack's remainder template. */
    static byte[] generateStackCalls() {
        return generateStackCalls(false);
    }

    /** As above; with {@code earlyStacks} the remainder stack waits for item components the way an old mod's own stacks do. */
    static byte[] generateStackCalls(boolean earlyStacks) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, STACK_CALLS, null, "java/lang/Object", null);
        for (boolean asBoolean : new boolean[] {false, true}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, asBoolean ? "hasRemainder" : "remainder",
                    asBoolean ? HAS_DESC : GET_DESC, null, null);
            mv.visitCode();
            // The stack extension asks the item, so an item's own remainder override still answers.
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, STACK, "getCraftingRemainder", "()L" + TEMPLATE + ";", false);
            mv.visitVarInsn(ASTORE, 1);
            Label present = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitJumpInsn(IFNONNULL, present);
            if (asBoolean) mv.visitInsn(ICONST_0);
            else mv.visitFieldInsn(GETSTATIC, STACK, "EMPTY", "L" + STACK + ";");
            mv.visitInsn(asBoolean ? IRETURN : ARETURN);
            mv.visitLabel(present);
            mv.visitFrame(F_APPEND, 1, new Object[]{TEMPLATE}, 0, null);
            if (asBoolean) {
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IRETURN);
            } else if (earlyStacks) {
                // Only before the item's components are bound, as while recipes load, is the stack built to wait.
                Label bound = new Label();
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "item", "()Lnet/minecraft/core/Holder;", false);
                mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/core/Holder", "areComponentsBound", "()Z", true);
                mv.visitJumpInsn(IFNE, bound);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "item", "()Lnet/minecraft/core/Holder;", false);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "count", "()I", false);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "components", "()Lnet/minecraft/core/component/DataComponentPatch;", false);
                mv.visitMethodInsn(INVOKESTATIC, LegacyEarlyItemStackBridge.HELPER, "of",
                        "(Lnet/minecraft/core/Holder;ILnet/minecraft/core/component/DataComponentPatch;)L" + STACK + ";", false);
                mv.visitInsn(ARETURN);
                mv.visitLabel(bound);
                mv.visitFrame(F_SAME, 0, null, 0, null);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "create", "()L" + STACK + ";", false);
                mv.visitInsn(ARETURN);
            } else {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "create", "()L" + STACK + ";", false);
                mv.visitInsn(ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Whether {@link #register} ran, which turns the override repair on. */
    public static boolean isRegistered(Predicate<String> hasSynthetic) {
        return hasSynthetic.test(CALLS);
    }

    /** Gives an item that overrides the old methods the new one, answered by its overrides. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("CraftingRemainingItem")) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return classBytes;
        boolean has = declares(node, HAS, HAS_DESC);
        boolean get = declares(node, GET, GET_DESC);
        if ((!has && !get) || declares(node, "getCraftingRemainder", NEW_DESC)) return classBytes;
        node.methods.add(newRemainder(node.name, has, get));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code getCraftingRemainder(instance)}, answered by the class's old has and get methods. */
    private static MethodNode newRemainder(String owner, boolean has, boolean get) {
        // A stack asks the old has-method, then the get-method. Anything else, and a class with no
        // get-method, gets the vanilla remainder.
        MethodNode method = new MethodNode(ACC_PUBLIC, "getCraftingRemainder", NEW_DESC, null, null);
        LabelNode vanilla = new LabelNode();
        LabelNode none = new LabelNode();
        Object[] locals = {owner, INSTANCE, STACK};
        method.instructions.add(new VarInsnNode(ALOAD, 1));
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, CALLS, "stackOf", "(Ljava/lang/Object;)L" + STACK + ";", false));
        method.instructions.add(new VarInsnNode(ASTORE, 2));
        method.instructions.add(new VarInsnNode(ALOAD, 2));
        method.instructions.add(new JumpInsnNode(IFNULL, vanilla));
        if (has) {
            method.instructions.add(new VarInsnNode(ALOAD, 0));
            method.instructions.add(new VarInsnNode(ALOAD, 2));
            method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, owner, HAS, HAS_DESC, false));
            method.instructions.add(new JumpInsnNode(IFEQ, none));
        }
        if (get) {
            method.instructions.add(new VarInsnNode(ALOAD, 0));
            method.instructions.add(new VarInsnNode(ALOAD, 2));
            method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, owner, GET, GET_DESC, false));
            method.instructions.add(new MethodInsnNode(INVOKESTATIC, CALLS, "template", "(L" + STACK + ";)L" + TEMPLATE + ";", false));
            method.instructions.add(new InsnNode(ARETURN));
        } else {
            method.instructions.add(new JumpInsnNode(GOTO, vanilla));
        }
        method.instructions.add(none);
        method.instructions.add(new FrameNode(F_FULL, 3, locals, 0, new Object[0]));
        method.instructions.add(new InsnNode(ACONST_NULL));
        method.instructions.add(new InsnNode(ARETURN));
        method.instructions.add(vanilla);
        method.instructions.add(new FrameNode(F_FULL, 3, locals, 0, new Object[0]));
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, ITEM, "getCraftingRemainder", "()L" + TEMPLATE + ";", false));
        method.instructions.add(new InsnNode(ARETURN));
        return method;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc) && (m.access & ACC_STATIC) == 0);
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        String instanceForm = "(L" + INSTANCE + ";)L" + TEMPLATE + ";";
        // The answers through the item's own instance form, and through vanilla's for a super call.
        remainderCall(cw, "hasRemainder", "(L" + ITEM + ";L" + STACK + ";)Z", true, instanceForm, true);
        remainderCall(cw, "remainder", "(L" + ITEM + ";L" + STACK + ";)L" + STACK + ";", true, instanceForm, false);
        remainderCall(cw, "itemHasRemainder", "(L" + ITEM + ";L" + STACK + ";)Z", false, "()L" + TEMPLATE + ";", true);
        remainderCall(cw, "itemRemainder", "(L" + ITEM + ";L" + STACK + ";)L" + STACK + ";", false, "()L" + TEMPLATE + ";", false);

        MethodVisitor template = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "template", "(L" + STACK + ";)L" + TEMPLATE + ";", null, null);
        template.visitCode();
        Label stack = new Label();
        template.visitVarInsn(ALOAD, 0);
        template.visitMethodInsn(INVOKEVIRTUAL, STACK, "isEmpty", "()Z", false);
        template.visitJumpInsn(IFEQ, stack);
        template.visitInsn(ACONST_NULL);
        template.visitInsn(ARETURN);
        template.visitLabel(stack);
        template.visitFrame(F_SAME, 0, null, 0, null);
        template.visitVarInsn(ALOAD, 0);
        template.visitMethodInsn(INVOKESTATIC, TEMPLATE, "fromNonEmptyStack", "(L" + STACK + ";)L" + TEMPLATE + ";", false);
        template.visitInsn(ARETURN);
        template.visitMaxs(0, 0);
        template.visitEnd();

        MethodVisitor stackOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "stackOf", "(Ljava/lang/Object;)L" + STACK + ";", null, null);
        stackOf.visitCode();
        Label other = new Label();
        stackOf.visitVarInsn(ALOAD, 0);
        stackOf.visitTypeInsn(INSTANCEOF, STACK);
        stackOf.visitJumpInsn(IFEQ, other);
        stackOf.visitVarInsn(ALOAD, 0);
        stackOf.visitTypeInsn(CHECKCAST, STACK);
        stackOf.visitInsn(ARETURN);
        stackOf.visitLabel(other);
        stackOf.visitFrame(F_SAME, 0, null, 0, null);
        stackOf.visitInsn(ACONST_NULL);
        stackOf.visitInsn(ARETURN);
        stackOf.visitMaxs(0, 0);
        stackOf.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static R name(item, stack)}: the item's remainder template, as a yes or no or as a stack. */
    private static void remainderCall(ClassWriter cw, String name, String desc, boolean withInstance, String lookupDesc, boolean asBoolean) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        if (withInstance) mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM, "getCraftingRemainder", lookupDesc, false);
        mv.visitVarInsn(ASTORE, 2);
        Label present = new Label();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNONNULL, present);
        if (asBoolean) mv.visitInsn(ICONST_0);
        else mv.visitFieldInsn(GETSTATIC, STACK, "EMPTY", "L" + STACK + ";");
        mv.visitInsn(asBoolean ? IRETURN : ARETURN);
        mv.visitLabel(present);
        mv.visitFrame(F_APPEND, 1, new Object[]{TEMPLATE}, 0, null);
        if (asBoolean) {
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, TEMPLATE, "create", "()L" + STACK + ";", false);
            mv.visitInsn(ARETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
