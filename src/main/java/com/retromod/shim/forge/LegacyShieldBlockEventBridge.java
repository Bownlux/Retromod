/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Runs Forge 1.20.1's ShieldBlockEvent listeners on NeoForge's LivingShieldBlockEvent, with Forge's meaning for each member. */
public final class LegacyShieldBlockEventBridge {

    static final String FORGE_EVENT = "net/minecraftforge/event/entity/living/ShieldBlockEvent";
    static final String NEO_EVENT = "net/neoforged/neoforge/event/entity/living/LivingShieldBlockEvent";
    public static final String HELPER = "com/retromod/generated/LegacyShieldBlockMembers";
    static final String CANCELLABLE = "net/neoforged/bus/api/ICancellableEvent";
    static final String CONTAINER = "net/neoforged/neoforge/common/damagesource/DamageContainer";
    static final String OLD_CONSTRUCTOR = "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)";
    /** NeoForge mods from 1.20.2 on cancel with NeoForge's meaning, which stops later listeners and nothing else. */
    static final String FIRST_NEOFORGE_MOD = "1.20.2";

    /** The host event's shape: the type of its shield damage, whether it can still be cancelled, and whether its constructor takes the blocked amount. */
    record Shape(char damageType, boolean cancellable, boolean amountInConstructor) {}

    private LegacyShieldBlockEventBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        Shape shape = shapeOf(host.apply(NEO_EVENT));
        if (shape == null) return;
        t.registerClassRedirect(FORGE_EVENT, NEO_EVENT);
        t.registerSyntheticClass(HELPER, generateHelper(shape));
        String event = "(L" + NEO_EVENT + ";";
        // A mod that posts the event itself builds it from the entity, the damage source and the blocked amount.
        t.registerConstructorRedirect(FORGE_EVENT, OLD_CONSTRUCTOR + "V", HELPER, "create", OLD_CONSTRUCTOR + "L" + NEO_EVENT + ";");
        // The Forge name is remapped to NeoForge's before the call is matched; both are listed for the offline paths.
        for (String owner : List.of(FORGE_EVENT, NEO_EVENT)) {
            t.registerMethodRedirect(owner, "shieldTakesDamage", "()Z", HELPER, "shieldTakesDamage", event + ")Z", true);
            t.registerMethodRedirect(owner, "setShieldTakesDamage", "(Z)V", HELPER, "setShieldTakesDamage", event + "Z)V", true);
            // A Forge cancel meant the shield did not block; NeoForge reads only setBlocked.
            t.registerMethodRedirect(owner, "setCanceled", "(Z)V", HELPER, "setCanceled", event + "Z)V", true);
            t.registerMethodRedirect(owner, "isCanceled", "()Z", HELPER, "isCanceled", event + ")Z", true);
            t.limitRedirectToModsBefore(owner, "setCanceled", "(Z)V", FIRST_NEOFORGE_MOD);
            t.limitRedirectToModsBefore(owner, "isCanceled", "()Z", FIRST_NEOFORGE_MOD);
        }
    }

    /** The host's shape, or null when the host has no LivingShieldBlockEvent. Unreadable, the checked versions decide. */
    static Shape shapeOf(ClassNode event) {
        if (event == null) return offlineShape(RetromodVersion.TARGET_MC_VERSION);
        Character damageType = null;
        for (var method : event.methods) {
            if (method.name.equals("shieldDamage") && method.desc.equals("()F")) damageType = 'F';
            if (method.name.equals("shieldDamage") && method.desc.equals("()I")) damageType = 'I';
        }
        if (damageType == null) return null;
        boolean amountInConstructor = event.methods.stream().anyMatch(m -> m.name.equals("<init>")
                && m.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;L" + CONTAINER + ";FZ)V"));
        return new Shape(damageType, event.interfaces.contains(CANCELLABLE), amountInConstructor);
    }

    /** NeoForge 1.21.1 has a float and cancellable event; 1.21.11 and 26.x an int one. The versions between are unchecked. */
    static Shape offlineShape(String target) {
        if (target == null) return null;
        if (RetromodVersion.compareMcVersions(target, "1.21.11") >= 0) return new Shape('I', false, true);
        if (RetromodVersion.compareMcVersions(target, "1.21") >= 0
                && RetromodVersion.compareMcVersions(target, "1.21.1") <= 0) {
            return new Shape('F', true, false);
        }
        return null;
    }

    /** The helper class, written for the host's shield damage type. */
    static byte[] generateHelper(Shape shape) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        String event = "(L" + NEO_EVENT + ";";
        String damage = String.valueOf(shape.damageType());

        // create: Forge's constructor, as a blocked hit of that amount from that source.
        MethodVisitor create = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                OLD_CONSTRUCTOR + "L" + NEO_EVENT + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, NEO_EVENT);
        create.visitInsn(Opcodes.DUP);
        create.visitVarInsn(Opcodes.ALOAD, 0);
        create.visitTypeInsn(Opcodes.NEW, CONTAINER);
        create.visitInsn(Opcodes.DUP);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitVarInsn(Opcodes.FLOAD, 2);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTAINER, "<init>", "(Lnet/minecraft/world/damagesource/DamageSource;F)V", false);
        if (shape.amountInConstructor()) create.visitVarInsn(Opcodes.FLOAD, 2);
        create.visitInsn(Opcodes.ICONST_1);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, NEO_EVENT, "<init>", "(Lnet/minecraft/world/entity/LivingEntity;L" + CONTAINER
                + ";" + (shape.amountInConstructor() ? "F" : "") + "Z)V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        // shieldTakesDamage: whether the shield is still set to lose durability.
        MethodVisitor takes = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "shieldTakesDamage", event + ")Z", null, null);
        takes.visitCode();
        pushShieldDamage(takes, damage);
        Label none = new Label();
        takes.visitJumpInsn(Opcodes.IFEQ, none);
        takes.visitInsn(Opcodes.ICONST_1);
        takes.visitInsn(Opcodes.IRETURN);
        takes.visitLabel(none);
        takes.visitInsn(Opcodes.ICONST_0);
        takes.visitInsn(Opcodes.IRETURN);
        takes.visitMaxs(0, 0);
        takes.visitEnd();

        // setShieldTakesDamage: false sets no damage; true restores the host's own amount when none is set.
        MethodVisitor set = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setShieldTakesDamage", event + "Z)V", null, null);
        set.visitCode();
        Label restore = new Label();
        set.visitVarInsn(Opcodes.ILOAD, 1);
        set.visitJumpInsn(Opcodes.IFNE, restore);
        setShieldDamage(set, damage, false);
        set.visitInsn(Opcodes.RETURN);
        set.visitLabel(restore);
        Label keep = new Label();
        pushShieldDamage(set, damage);
        set.visitJumpInsn(Opcodes.IFNE, keep);
        setShieldDamage(set, damage, true);
        set.visitLabel(keep);
        set.visitInsn(Opcodes.RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        // setCanceled: the shield blocks unless cancelled; a host event that can still be cancelled is cancelled too.
        MethodVisitor cancel = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setCanceled", event + "Z)V", null, null);
        cancel.visitCode();
        cancel.visitVarInsn(Opcodes.ALOAD, 0);
        cancel.visitVarInsn(Opcodes.ILOAD, 1);
        cancel.visitInsn(Opcodes.ICONST_1);
        cancel.visitInsn(Opcodes.IXOR);
        cancel.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "setBlocked", "(Z)V", false);
        if (shape.cancellable()) {
            cancel.visitVarInsn(Opcodes.ALOAD, 0);
            cancel.visitVarInsn(Opcodes.ILOAD, 1);
            cancel.visitMethodInsn(Opcodes.INVOKEINTERFACE, CANCELLABLE, "setCanceled", "(Z)V", true);
        }
        cancel.visitInsn(Opcodes.RETURN);
        cancel.visitMaxs(0, 0);
        cancel.visitEnd();

        // isCanceled: Forge's cancel was the shield not blocking.
        MethodVisitor canceled = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isCanceled", event + ")Z", null, null);
        canceled.visitCode();
        canceled.visitVarInsn(Opcodes.ALOAD, 0);
        canceled.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "getBlocked", "()Z", false);
        canceled.visitInsn(Opcodes.ICONST_1);
        canceled.visitInsn(Opcodes.IXOR);
        canceled.visitInsn(Opcodes.IRETURN);
        canceled.visitMaxs(0, 0);
        canceled.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes the event's shield damage as an int that is zero exactly when the shield takes none. */
    private static void pushShieldDamage(MethodVisitor mv, String damage) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "shieldDamage", "()" + damage, false);
        if (damage.equals("F")) {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.FCMPL);
        }
    }

    /** Sets the shield damage to none, or to the host's default: the blocked damage as a float, the game's amount (-1) as an int. */
    private static void setShieldDamage(MethodVisitor mv, String damage, boolean hostDefault) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (damage.equals("F") && hostDefault) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "getBlockedDamage", "()F", false);
        } else if (damage.equals("F")) {
            mv.visitInsn(Opcodes.FCONST_0);
        } else {
            mv.visitInsn(hostDefault ? Opcodes.ICONST_M1 : Opcodes.ICONST_0);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "setShieldDamage", "(" + damage + ")V", false);
    }

    /** Makes each migrated listener skip a hit the shield did not block, since Forge posted the event only for a block. */
    public static byte[] guardListeners(byte[] classBytes, String modSourceVersion) {
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!constants.contains(NEO_EVENT)) return classBytes;
        // NeoForge mods from 1.20.2 expect every hurt, so only a known Forge-era mod, or code that calls a Forge-only member, is guarded.
        boolean forgeEraMod = modSourceVersion != null
                && RetromodVersion.compareMcVersions(modSourceVersion, FIRST_NEOFORGE_MOD) < 0;
        if (!forgeEraMod && !constants.contains(HELPER)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (isShieldListener(method) && !startsWithGuard(method) && (forgeEraMod || callsHelper(method))) {
                guard(node, method);
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Any concrete {@code void (LivingShieldBlockEvent)} method, since Forge mods also listen through method references and lambdas. */
    private static boolean isShieldListener(MethodNode method) {
        Type[] parameters = Type.getArgumentTypes(method.desc);
        return !method.name.startsWith("<") && parameters.length == 1 && parameters[0].getSort() == Type.OBJECT
                && parameters[0].getInternalName().equals(NEO_EVENT)
                && Type.getReturnType(method.desc) == Type.VOID_TYPE
                && (method.access & Opcodes.ACC_ABSTRACT) == 0;
    }

    private static boolean callsHelper(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HELPER)) return true;
        }
        return false;
    }

    private static boolean startsWithGuard(MethodNode method) {
        AbstractInsnNode first = firstInstruction(method.instructions.getFirst());
        AbstractInsnNode second = first == null ? null : firstInstruction(first.getNext());
        return second instanceof MethodInsnNode call && call.owner.equals(NEO_EVENT) && call.name.equals("getOriginalBlock");
    }

    private static AbstractInsnNode firstInstruction(AbstractInsnNode from) {
        for (AbstractInsnNode insn = from; insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private static void guard(ClassNode owner, MethodNode method) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        LabelNode blocked = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, isStatic ? 0 : 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NEO_EVENT, "getOriginalBlock", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, blocked));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(blocked);
        // Code after the return needs a frame; a method that already starts at a jump target has one.
        if ((owner.version & 0xFFFF) >= Opcodes.V1_6 && !startsWithFrame(method)) {
            List<Object> locals = new ArrayList<>();
            if (!isStatic) locals.add(owner.name);
            locals.add(NEO_EVENT);
            guard.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
        }
        method.instructions.insert(guard);
    }

    private static boolean startsWithFrame(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode) return true;
            if (insn.getOpcode() >= 0) return false;
        }
        return false;
    }
}
