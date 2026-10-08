/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Routes a pre-1.20 mod's reads and writes of the {@code Entity} fields that 1.20 made private
 * through their accessors.
 *
 * <p>Minecraft 1.20 made {@code level}, {@code maxUpStep}, and {@code onGround} private and added
 * a getter and setter for each. The fields kept their names, so the old access links and then
 * fails at run time with {@code IllegalAccessError: ... tried to access private field
 * Entity.f_19853_}. Isle of Berk's follow-owner goal stopped the server on its first tick this
 * way, and every dragon constructor did the same on {@code maxUpStep} (#311). The bytecode
 * usually names the mod's own entity class as the owner, so the match follows the mod's
 * superclasses instead of requiring {@code Entity} itself.
 *
 * <p>The same release renamed the {@code getLevel()} accessor to {@code level()}. A call that
 * reaches the Minecraft entity's accessor is pointed at the host's {@code level()} as well, or
 * the next tick fails with {@code NoSuchMethodError: ... getLevel()}. A 1.19 mod names it by the
 * SRG id the host still uses, which the transformer keeps. A 1.18 mod uses an older id that maps
 * only to {@code getLevel}, so this pass is what links it.
 */
public final class LegacyEntityPrivateFieldAdapter {

    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LEVEL_DESC = "Lnet/minecraft/world/level/Level;";
    static final String OLD_LEVEL_GETTER = "getLevel";

    /** One private field with the host's names for it and its accessors. */
    record Accessor(String mojangField, String desc, String fieldName, String getter,
            String setter) {
    }

    private LegacyEntityPrivateFieldAdapter() {
    }

    /** @return the repaired class, or the same array when nothing matched */
    public static byte[] apply(byte[] classBytes, RetromodTransformer transformer,
            Function<String, byte[]> modClasses) {
        List<Accessor> accessors = new ArrayList<>();
        accessors.add(accessor(transformer, "level", LEVEL_DESC, "level", "setLevel"));
        accessors.add(accessor(transformer, "maxUpStep", "F", "maxUpStep", "setMaxUpStep"));
        accessors.add(accessor(transformer, "onGround", "Z", "onGround", "setOnGround"));
        return apply(classBytes, accessors, modClasses);
    }

    private static Accessor accessor(RetromodTransformer transformer, String field, String desc,
            String getter, String setter) {
        return new Accessor(field, desc,
                transformer.remapQualifiedFieldName(ENTITY, field, desc),
                transformer.remapQualifiedMethodName(ENTITY, getter, "()" + desc),
                transformer.remapQualifiedMethodName(ENTITY, setter, "(" + desc + ")V"));
    }

    static byte[] apply(byte[] classBytes, List<Accessor> accessors,
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
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    if (renameLegacyLevelGetter(call, accessors, modClasses, node)) changes++;
                    continue;
                }
                if (!(insn instanceof FieldInsnNode field)) continue;
                Accessor accessor = matching(field, accessors);
                if (accessor == null || !isEntityOwner(field, modClasses, node)) continue;
                if (field.getOpcode() == Opcodes.GETFIELD) {
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            field.owner, accessor.getter(), "()" + accessor.desc(), false));
                    changes++;
                } else if (field.getOpcode() == Opcodes.PUTFIELD) {
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            field.owner, accessor.setter(), "(" + accessor.desc() + ")V", false));
                    changes++;
                }
            }
        }
        if (changes == 0) {
            return classBytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Points {@code getLevel()} on a Minecraft entity at the host's {@code level()}. */
    private static boolean renameLegacyLevelGetter(MethodInsnNode call, List<Accessor> accessors,
            Function<String, byte[]> modClasses, ClassNode self) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL) {
            return false;
        }
        if (!call.name.equals(OLD_LEVEL_GETTER) || !call.desc.equals("()" + LEVEL_DESC)) {
            return false;
        }
        Accessor level = null;
        for (Accessor accessor : accessors) {
            if (accessor.mojangField().equals("level") && accessor.desc().equals(LEVEL_DESC)) {
                level = accessor;
            }
        }
        if (level == null || level.getter().equals(OLD_LEVEL_GETTER)) return false;
        // A mod or library that declares its own getLevel() keeps it; only the inherited
        // Minecraft accessor was renamed.
        String base = LegacyMemberOwners.minecraftBase(call.owner, call.name, call.desc, true,
                modClasses, self);
        if (base == null || !LegacyMemberOwners.isEntity(base)) return false;
        call.name = level.getter();
        return true;
    }

    private static Accessor matching(FieldInsnNode field, List<Accessor> accessors) {
        for (Accessor accessor : accessors) {
            if (accessor.desc().equals(field.desc) && (field.name.equals(accessor.fieldName())
                    || field.name.equals(accessor.mojangField()))) {
                return accessor;
            }
        }
        return null;
    }

    /**
     * Whether the field is the one inherited from a Minecraft entity. A goal or navigation class
     * with its own {@code level} field is not an entity, and on a Mojang-named host its field
     * has the same name as the entity's.
     */
    private static boolean isEntityOwner(FieldInsnNode field, Function<String, byte[]> modClasses,
            ClassNode self) {
        String base = LegacyMemberOwners.minecraftBase(field.owner, field.name, field.desc, false,
                modClasses, self);
        return base != null && LegacyMemberOwners.isEntity(base);
    }
}
