/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/**
 * Repairs entity and explosion members that 1.19 and 1.20 changed under older mobs.
 *
 * <p>A 1.18.2 mob called members that no longer link on a 1.20 host. {@code getControllingPassenger}
 * now returns a {@code LivingEntity}, so a mod's override with the old {@code Entity} return no
 * longer overrides it, and the server ignored the rider's steering. {@code getJumpBoostPower}
 * returns a float. {@code eyeBlockPosition()} and {@code gameEvent(GameEvent, BlockPos)} were removed,
 * {@code Explosion.getSourceMob} became {@code getIndirectSourceEntity}, {@code Explosion.toBlow}
 * became an {@code ObjectArrayList}, and the {@code flyingSpeed} field was replaced by an
 * overridable getter.
 *
 * <p>The adapter runs after the remap, where a member the host lacks keeps its Mojang name. It only
 * rewrites a member a Minecraft entity or explosion class declared, never a same-named member the
 * mod declares itself. The one exception is the passenger override, which the mod declares on
 * purpose: its return is narrowed so it overrides the host method again. A mob's own
 * {@code flyingSpeed} moves into a field of its own that an added {@code getFlyingSpeed} override
 * returns, and an old {@code double getJumpBoostPower()} override gets the float method that calls
 * it. A write to another mob's {@code flyingSpeed} is dropped.
 */
public final class LegacyEntityApi120Adapter {

    public static final String SYNTH = "com/retromod/generated/LegacyEntityApi";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String EXPLOSION = "net/minecraft/world/level/Explosion";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    private static final String GAME_EVENT = "net/minecraft/world/level/gameevent/GameEvent";
    private static final String BLOCK_TAGS = "net/minecraft/tags/BlockTags";
    private static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";

    static final String OLD_PASSENGER = "()L" + ENTITY + ";";
    static final String NEW_PASSENGER = "()L" + LIVING + ";";
    static final String EYE_BLOCK_POSITION = "()L" + BLOCK_POS + ";";
    static final String OLD_GAME_EVENT = "(L" + GAME_EVENT + ";L" + BLOCK_POS + ";)V";
    static final String OLD_TO_BLOW = "Ljava/util/List;";
    static final String NEW_TO_BLOW = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;";

    private static final int MAX_DEPTH = 16;
    private static final String FLYING_VALUE = "retromod$flyingSpeed";
    private static final String FLYING_SET = "retromod$flyingSpeedSet";
    private static final String FLYING_SETTER = "retromod$setFlyingSpeed";

    private LegacyEntityApi120Adapter() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());
        // 1.20 replaced the plant tag with the tag of blocks a growing tree may overwrite, which
        // holds the same grass, ferns, vines and flowers plus leaves.
        transformer.registerFieldRedirect(BLOCK_TAGS, "REPLACEABLE_PLANTS", TAG_KEY,
                BLOCK_TAGS, "REPLACEABLE_BY_TREES", TAG_KEY);
    }

    /** True once the bridge is registered, so the call-site pass runs only where it can link. */
    public static boolean isRegistered(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(SYNTH);
    }

    /**
     * @param modClasses the jar's own classes, used to walk a mod class up to Minecraft
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes, RetromodTransformer transformer,
            Function<String, byte[]> modClasses) {
        if (classBytes == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("com/retromod/") || node.name.startsWith("net/minecraft/")) {
            return classBytes;
        }
        int changes = 0;
        boolean entityClass = isEntity(ancestor(node.superName, modClasses, node));
        // A mod that already narrowed its override to LivingEntity also carries javac's Entity
        // bridge. Its real method overrides the host one, and narrowing the bridge would declare
        // the same method twice, which fails with ClassFormatError.
        boolean declaresNewPassenger = declaresNewPassenger(node, transformer);
        String flyingGetter = transformer.remapQualifiedMethodName(LIVING, "getFlyingSpeed", "()F");
        boolean keepsFlyingSpeed = entityClass && !declares(node, flyingGetter, "getFlyingSpeed", "()F");
        boolean usesFlyingSpeed = false;
        for (MethodNode method : new ArrayList<>(node.methods)) {
            if (entityClass && !declaresNewPassenger && isLegacyPassengerOverride(method)) {
                narrowPassengerOverride(method, transformer);
                changes++;
            }
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    if (rewriteCall(method, call, transformer, modClasses, node)) changes++;
                } else if (insn instanceof FieldInsnNode field) {
                    if (keepsFlyingSpeed && isOwnFlyingSpeed(field, node, modClasses)) {
                        method.instructions.set(field, field.getOpcode() == PUTFIELD
                                ? new MethodInsnNode(INVOKEVIRTUAL, node.name, FLYING_SETTER, "(F)V", false)
                                : new MethodInsnNode(INVOKEVIRTUAL, node.name, flyingGetter, "()F", false));
                        usesFlyingSpeed = true;
                        changes++;
                    } else if (rewriteField(method, field, transformer, modClasses, node)) {
                        changes++;
                    }
                }
            }
        }
        if (usesFlyingSpeed) {
            addFlyingSpeedOverride(node, flyingGetter);
        }
        if (entityClass && addJumpBoostBridge(node, transformer)) {
            changes++;
        }
        if (changes == 0) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewriteCall(MethodNode method, MethodInsnNode call,
            RetromodTransformer transformer, Function<String, byte[]> modClasses, ClassNode self) {
        if (call.getOpcode() != INVOKEVIRTUAL && call.getOpcode() != INVOKESPECIAL) {
            return false;
        }
        if (call.name.equals("getControllingPassenger") && call.desc.equals(OLD_PASSENGER)) {
            // The mod's own override is narrowed too, so every call takes the new descriptor.
            if (!isEntity(ancestor(call.owner, modClasses, self))) return false;
            call.name = transformer.remapQualifiedMethodName(ENTITY, call.name, NEW_PASSENGER);
            call.desc = NEW_PASSENGER;
            return true;
        }
        if (call.name.equals("getJumpBoostPower") && call.desc.equals("()D")) {
            if (!inheritsEntityMember(call.owner, call.name, call.desc, modClasses, self)) return false;
            call.name = transformer.remapQualifiedMethodName(LIVING, call.name, "()F");
            call.desc = "()F";
            method.instructions.insert(call, new InsnNode(F2D));
            return true;
        }
        if (call.getOpcode() == INVOKEVIRTUAL
                && (call.name.equals("eyeBlockPosition") && call.desc.equals(EYE_BLOCK_POSITION)
                    || call.name.equals("gameEvent") && call.desc.equals(OLD_GAME_EVENT))) {
            if (!inheritsEntityMember(call.owner, call.name, call.desc, modClasses, self)) return false;
            method.instructions.set(call, new MethodInsnNode(INVOKESTATIC, SYNTH, call.name,
                    "(L" + ENTITY + ";" + call.desc.substring(1), false));
            return true;
        }
        if (call.name.equals("getSourceMob") && call.desc.equals("()L" + LIVING + ";")) {
            if (!isExplosion(LegacyMemberOwners.minecraftBase(call.owner, call.name, call.desc,
                    true, modClasses, self))) return false;
            call.name = transformer.remapQualifiedMethodName(EXPLOSION, "getIndirectSourceEntity",
                    call.desc);
            return true;
        }
        return false;
    }

    private static boolean rewriteField(MethodNode method, FieldInsnNode field,
            RetromodTransformer transformer, Function<String, byte[]> modClasses, ClassNode self) {
        if (field.getOpcode() == GETFIELD && field.name.equals("toBlow") && field.desc.equals(OLD_TO_BLOW)) {
            if (!isExplosion(LegacyMemberOwners.minecraftBase(field.owner, field.name, field.desc,
                    false, modClasses, self))) return false;
            // The new list type is still a List, so every later use of the value links as before.
            field.name = transformer.remapQualifiedFieldName(EXPLOSION, "toBlow", NEW_TO_BLOW);
            field.desc = NEW_TO_BLOW;
            return true;
        }
        if (field.getOpcode() == PUTFIELD && field.name.equals("flyingSpeed") && field.desc.equals("F")) {
            String base = LegacyMemberOwners.minecraftBase(field.owner, field.name, field.desc,
                    false, modClasses, self);
            if (base == null || !LegacyMemberOwners.isEntity(base)) return false;
            method.instructions.set(field, new InsnNode(POP2));
            return true;
        }
        return false;
    }

    private static boolean declares(ClassNode node, String hostName, String mojangName, String desc) {
        for (MethodNode method : node.methods) {
            if (method.desc.equals(desc) && (method.name.equals(hostName) || method.name.equals(mojangName))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A read or write of the mob's own {@code flyingSpeed}, which 1.19.4 replaced with a getter. A
     * {@code flyingSpeed} field the mod declares itself, on the mob or a base of its own, stays a field.
     */
    private static boolean isOwnFlyingSpeed(FieldInsnNode field, ClassNode self,
            Function<String, byte[]> modClasses) {
        if ((field.getOpcode() != PUTFIELD && field.getOpcode() != GETFIELD)
                || !field.name.equals("flyingSpeed") || !field.desc.equals("F")
                || !field.owner.equals(self.name)) {
            return false;
        }
        String base = LegacyMemberOwners.minecraftBase(field.owner, field.name, field.desc,
                false, modClasses, self);
        return base != null && LegacyMemberOwners.isEntity(base);
    }

    /**
     * Keeps the speed a mob writes, and returns it from the host's getter until then the host's
     * own value. A 1.18 mob sets the speed in {@code travel} before the host reads it there.
     */
    private static void addFlyingSpeedOverride(ClassNode node, String flyingGetter) {
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_SYNTHETIC, FLYING_VALUE, "F", null, null));
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_SYNTHETIC, FLYING_SET, "Z", null, null));

        MethodNode setter = new MethodNode(ACC_PUBLIC | ACC_SYNTHETIC, FLYING_SETTER, "(F)V", null, null);
        setter.instructions.add(new VarInsnNode(ALOAD, 0));
        setter.instructions.add(new VarInsnNode(FLOAD, 1));
        setter.instructions.add(new FieldInsnNode(PUTFIELD, node.name, FLYING_VALUE, "F"));
        setter.instructions.add(new VarInsnNode(ALOAD, 0));
        setter.instructions.add(new InsnNode(ICONST_1));
        setter.instructions.add(new FieldInsnNode(PUTFIELD, node.name, FLYING_SET, "Z"));
        setter.instructions.add(new InsnNode(RETURN));
        setter.maxStack = 2;
        setter.maxLocals = 2;
        node.methods.add(setter);

        MethodNode getter = new MethodNode(ACC_PUBLIC, flyingGetter, "()F", null, null);
        LabelNode inherited = new LabelNode();
        getter.instructions.add(new VarInsnNode(ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(GETFIELD, node.name, FLYING_SET, "Z"));
        getter.instructions.add(new JumpInsnNode(IFEQ, inherited));
        getter.instructions.add(new VarInsnNode(ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(GETFIELD, node.name, FLYING_VALUE, "F"));
        getter.instructions.add(new InsnNode(FRETURN));
        getter.instructions.add(inherited);
        getter.instructions.add(new FrameNode(F_SAME, 0, null, 0, null));
        getter.instructions.add(new VarInsnNode(ALOAD, 0));
        getter.instructions.add(new MethodInsnNode(INVOKESPECIAL, node.superName, flyingGetter, "()F", false));
        getter.instructions.add(new InsnNode(FRETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
        node.methods.add(getter);
    }

    /** An old {@code double getJumpBoostPower()} override gets the 1.20 float method that calls it. */
    private static boolean addJumpBoostBridge(ClassNode node, RetromodTransformer transformer) {
        String hostName = transformer.remapQualifiedMethodName(LIVING, "getJumpBoostPower", "()F");
        if (declares(node, hostName, "getJumpBoostPower", "()F")) return false;
        MethodNode old = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("getJumpBoostPower") && method.desc.equals("()D")
                    && (method.access & (ACC_STATIC | ACC_ABSTRACT)) == 0) {
                old = method;
            }
        }
        if (old == null) return false;
        MethodNode bridge = new MethodNode(ACC_PUBLIC | ACC_SYNTHETIC, hostName, "()F", null, null);
        bridge.instructions.add(new VarInsnNode(ALOAD, 0));
        bridge.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, node.name, old.name, "()D", false));
        bridge.instructions.add(new InsnNode(D2F));
        bridge.instructions.add(new InsnNode(FRETURN));
        bridge.maxStack = 2;
        bridge.maxLocals = 1;
        node.methods.add(bridge);
        return true;
    }

    private static boolean declaresNewPassenger(ClassNode node, RetromodTransformer transformer) {
        String hostName = transformer.remapQualifiedMethodName(ENTITY, "getControllingPassenger", NEW_PASSENGER);
        for (MethodNode method : node.methods) {
            if (method.desc.equals(NEW_PASSENGER)
                    && (method.name.equals(hostName) || method.name.equals("getControllingPassenger"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegacyPassengerOverride(MethodNode method) {
        return (method.access & ACC_STATIC) == 0 && method.name.equals("getControllingPassenger")
                && method.desc.equals(OLD_PASSENGER);
    }

    /** Narrows the override's return; a passenger that is not living was never a controller. */
    private static void narrowPassengerOverride(MethodNode method, RetromodTransformer transformer) {
        method.name = transformer.remapQualifiedMethodName(ENTITY, method.name, NEW_PASSENGER);
        method.desc = NEW_PASSENGER;
        method.signature = null;
        if (method.instructions == null) return;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == ARETURN) {
                method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC, SYNTH,
                        "asLiving", "(L" + ENTITY + ";)L" + LIVING + ";", false));
            }
        }
    }

    private static boolean inheritsEntityMember(String owner, String name, String desc,
            Function<String, byte[]> modClasses, ClassNode self) {
        String base = LegacyMemberOwners.minecraftBase(owner, name, desc, true, modClasses, self);
        return base != null && LegacyMemberOwners.isEntity(base);
    }

    private static boolean isEntity(String minecraftClass) {
        return minecraftClass != null && LegacyMemberOwners.isEntity(minecraftClass);
    }

    private static boolean isExplosion(String minecraftClass) {
        if (minecraftClass == null) return false;
        Boolean known = LegacyMemberOwners.hostExtends(minecraftClass, EXPLOSION);
        return known != null ? known : minecraftClass.equals(EXPLOSION);
    }

    /** The first Minecraft class above {@code owner}, whatever the mod declares on the way. */
    static String ancestor(String owner, Function<String, byte[]> modClasses, ClassNode self) {
        String current = owner;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current.startsWith("net/minecraft/")) {
                return current;
            }
            if (current.equals(self.name)) {
                current = self.superName;
                continue;
            }
            byte[] bytes = modClasses == null ? null : modClasses.apply(current);
            if (bytes == null) {
                return null;
            }
            try {
                current = new ClassReader(bytes).getSuperName();
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        String entity = "L" + ENTITY + ";";

        MethodVisitor asLiving = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "asLiving",
                "(" + entity + ")L" + LIVING + ";", null, null);
        asLiving.visitCode();
        Label notLiving = new Label();
        asLiving.visitVarInsn(ALOAD, 0);
        asLiving.visitTypeInsn(INSTANCEOF, LIVING);
        asLiving.visitJumpInsn(IFEQ, notLiving);
        asLiving.visitVarInsn(ALOAD, 0);
        asLiving.visitTypeInsn(CHECKCAST, LIVING);
        asLiving.visitInsn(ARETURN);
        asLiving.visitLabel(notLiving);
        asLiving.visitInsn(ACONST_NULL);
        asLiving.visitInsn(ARETURN);
        asLiving.visitMaxs(0, 0);
        asLiving.visitEnd();

        // 1.18.2 floored the eye position into a block position, as BlockPos.containing does.
        MethodVisitor eye = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "eyeBlockPosition",
                "(" + entity + ")L" + BLOCK_POS + ";", null, null);
        eye.visitCode();
        eye.visitVarInsn(ALOAD, 0);
        eye.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getEyePosition", "()Lnet/minecraft/world/phys/Vec3;", false);
        eye.visitMethodInsn(INVOKESTATIC, BLOCK_POS, "containing",
                "(Lnet/minecraft/core/Position;)L" + BLOCK_POS + ";", false);
        eye.visitInsn(ARETURN);
        eye.visitMaxs(0, 0);
        eye.visitEnd();

        // 1.18.2's Entity.gameEvent(event, pos) posted the event at pos with itself as the source.
        MethodVisitor event = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "gameEvent",
                "(" + entity + "L" + GAME_EVENT + ";L" + BLOCK_POS + ";)V", null, null);
        event.visitCode();
        event.visitVarInsn(ALOAD, 0);
        event.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "level", "()Lnet/minecraft/world/level/Level;", false);
        event.visitVarInsn(ALOAD, 0);
        event.visitVarInsn(ALOAD, 1);
        event.visitVarInsn(ALOAD, 2);
        event.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/world/level/LevelAccessor", "gameEvent",
                "(" + entity + "L" + GAME_EVENT + ";L" + BLOCK_POS + ";)V", true);
        event.visitInsn(RETURN);
        event.visitMaxs(0, 0);
        event.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
