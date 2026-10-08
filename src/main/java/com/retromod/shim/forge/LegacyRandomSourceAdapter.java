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

import java.util.function.Function;

/**
 * Retypes a 1.18.2 mod's reads of Minecraft's random sources and wraps them as
 * {@code java.util.Random}.
 *
 * <p>Minecraft 1.19 replaced {@code java.util.Random} with {@code RandomSource} for
 * {@code Entity.random}, {@code Level.random}, and the matching {@code getRandom()} methods. The
 * mod's read names a member that no longer exists ({@code NoSuchFieldError: ... random}), so the
 * first tick of an Isle of Berk dragon stopped the server (#311). The read now uses the real member
 * and passes the result through {@code LegacyRandomView}, so the rest of the mod's code still sees
 * a {@code Random}.
 *
 * <p>Methods the mod overrides or calls with a {@code Random} argument, such as a block's
 * {@code tick}, are not covered.
 */
public final class LegacyRandomSourceAdapter {

    public static final String VIEW = "com/retromod/shim/forge/embedded/LegacyRandomView";
    static final String RANDOM = "Ljava/util/Random;";
    static final String RANDOM_SOURCE = "Lnet/minecraft/util/RandomSource;";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String EXPLOSION = "net/minecraft/world/level/Explosion";
    static final String LEVEL_ACCESSOR = "net/minecraft/world/level/LevelAccessor";
    static final String LOOT_CONTEXT = "net/minecraft/world/level/storage/loot/LootContext";

    private LegacyRandomSourceAdapter() {
    }

    /**
     * @param modClasses the jar's own classes, used to tell a mod's own {@code random} field from
     *                   the inherited Minecraft one
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
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field && isRandomFieldRead(field)) {
                    String declaring = fieldDeclaringOwner(LegacyMemberOwners.minecraftBase(
                            field.owner, "random", RANDOM, false, modClasses, node));
                    if (declaring == null) continue;
                    field.name = transformer.remapQualifiedFieldName(declaring, "random",
                            RANDOM_SOURCE);
                    field.desc = RANDOM_SOURCE;
                    method.instructions.insert(field, wrapCall());
                    changes++;
                } else if (insn instanceof MethodInsnNode call && "getRandom".equals(call.name)
                        && ("()" + RANDOM).equals(call.desc)) {
                    String declaring = methodDeclaringOwner(LegacyMemberOwners.minecraftBase(
                            call.owner, "getRandom", "()" + RANDOM, true, modClasses, node));
                    if (declaring == null) continue;
                    String newDesc = "()" + RANDOM_SOURCE;
                    call.name = transformer.remapQualifiedMethodName(declaring, "getRandom",
                            newDesc);
                    call.desc = newDesc;
                    method.instructions.insert(call, wrapCall());
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

    private static boolean isRandomFieldRead(FieldInsnNode field) {
        return field.getOpcode() == Opcodes.GETFIELD && "random".equals(field.name)
                && RANDOM.equals(field.desc);
    }

    /** The class declaring the 1.19 {@code random} field, or null for any other owner. */
    private static String fieldDeclaringOwner(String minecraftBase) {
        if (minecraftBase == null) {
            return null;
        }
        if (LegacyMemberOwners.isLevel(minecraftBase)) {
            return LEVEL;
        }
        // Mods subclass Explosion and read its random through an access transformer.
        if (minecraftBase.equals(EXPLOSION)
                || Boolean.TRUE.equals(LegacyMemberOwners.hostExtends(minecraftBase, EXPLOSION))) {
            return EXPLOSION;
        }
        return LegacyMemberOwners.isEntity(minecraftBase) ? ENTITY : null;
    }

    /** The class declaring the 1.19 {@code getRandom()}, or null for any other owner. */
    private static String methodDeclaringOwner(String minecraftBase) {
        if (minecraftBase == null) {
            return null;
        }
        if (minecraftBase.startsWith("net/minecraft/world/level/storage/loot/")) {
            return LOOT_CONTEXT;
        }
        // 1.18.2 declared it on the LevelAccessor interface, which mods call through its
        // subinterfaces and through Level, ServerLevel, or ClientLevel.
        if (minecraftBase.startsWith("net/minecraft/world/level/")
                || LegacyMemberOwners.isLevel(minecraftBase)) {
            return LEVEL_ACCESSOR;
        }
        // Entity has the field, but getRandom() is declared on LivingEntity.
        return LegacyMemberOwners.isEntity(minecraftBase) ? LIVING_ENTITY : null;
    }

    private static MethodInsnNode wrapCall() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, VIEW, "wrap",
                "(Ljava/lang/Object;)" + RANDOM, false);
    }
}
