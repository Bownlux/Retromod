/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges entity, item and level members that 1.20.5 renamed or removed, for Mojang-named mods
 * built for 1.20.4 or older (Forge 1.20.1 mods after the SRG remap).
 *
 * <ul>
 *   <li>{@code Entity.setSecondsOnFire(int)} became {@code igniteForSeconds(float)}.</li>
 *   <li>{@code Entity.setMaxUpStep(float)} was removed; step height is the
 *       {@code minecraft:step_height} attribute, so a living entity's base value is set. Other
 *       entities have no step height to set.</li>
 *   <li>{@code ItemStack.hasCustomHoverName()} is the {@code minecraft:custom_name} component.</li>
 *   <li>{@code LevelData.getXSpawn()} and its siblings are read from {@code getSpawnPos()}.</li>
 *   <li>{@code ItemStack.hurtAndBreak(int, LivingEntity, Consumer)} damages the stack in the slot
 *       that holds it, and {@code broadcastBreakEvent} plays the break effects through
 *       {@code onEquippedItemBroken}.</li>
 *   <li>{@code BlockPathTypes} became {@code PathType}, {@code BootstapContext} lost its typo,
 *       {@code AbstractGlassBlock} became {@code TransparentBlock}, and {@code Items.SCUTE} became
 *       {@code TURTLE_SCUTE}.</li>
 *   <li>{@code FriendlyByteBuf.Reader} and {@code Writer} became {@code StreamDecoder} and
 *       {@code StreamEncoder}, which the buffer's collection, map and optional methods take.</li>
 * </ul>
 * Mod entities usually call these as their own inherited members, so calls follow the proven
 * class hierarchy. Each change is registered only when the host proves it; offline, where the
 * host classes are not readable, the host version decides.
 */
public final class Legacy1205MemberBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/Legacy1205Members";

    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String LEVEL_DATA = "net/minecraft/world/level/storage/LevelData";
    static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    static final String ATTRIBUTE_INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
    static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    static final String VEC3I = "net/minecraft/core/Vec3i";
    static final String ITEMS = "net/minecraft/world/item/Items";
    static final String HAND = "net/minecraft/world/InteractionHand";
    static final String SLOT = "net/minecraft/world/entity/EquipmentSlot";

    static final String[][] CLASS_RENAMES = {
            {"net/minecraft/world/level/pathfinder/BlockPathTypes", "net/minecraft/world/level/pathfinder/PathType"},
            {"net/minecraft/data/worldgen/BootstapContext", "net/minecraft/data/worldgen/BootstrapContext"},
            {"net/minecraft/world/level/block/AbstractGlassBlock", "net/minecraft/world/level/block/TransparentBlock"},
            {"net/minecraft/network/FriendlyByteBuf$Reader", "net/minecraft/network/codec/StreamDecoder"},
            {"net/minecraft/network/FriendlyByteBuf$Writer", "net/minecraft/network/codec/StreamEncoder"},
    };

    /**
     * The buffer reader and writer were {@code Function} and {@code BiConsumer} views. Their stream
     * codec replacements name the method {@code decode} and {@code encode}, so lambdas and calls
     * are renamed with the class.
     */
    static final String[][] METHOD_RENAMES = {
            {"net/minecraft/network/FriendlyByteBuf$Reader", "apply", "decode"},
            {"net/minecraft/network/FriendlyByteBuf$Writer", "accept", "encode"},
    };

    /** A removed member, its old descriptor, and the helper that answers for it. */
    record Member(String owner, String name, String desc) {
        String helperDesc() {
            return "(L" + owner + ";" + desc.substring(1);
        }
    }

    static final List<Member> MEMBERS = List.of(
            new Member(ENTITY, "setSecondsOnFire", "(I)V"),
            new Member(ENTITY, "setMaxUpStep", "(F)V"),
            new Member(ITEM_STACK, "hasCustomHoverName", "()Z"),
            new Member(LEVEL_DATA, "getXSpawn", "()I"),
            new Member(LEVEL_DATA, "getYSpawn", "()I"),
            new Member(LEVEL_DATA, "getZSpawn", "()I"),
            new Member(ITEM_STACK, "hurtAndBreak", "(IL" + LIVING + ";Ljava/util/function/Consumer;)V"),
            new Member(LIVING, "broadcastBreakEvent", "(L" + HAND + ";)V"),
            new Member(LIVING, "broadcastBreakEvent", "(L" + SLOT + ";)V"));

    private Legacy1205MemberBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostIsPast1205()) {
            LOGGER.debug("1.20.5 member bridge not needed: the host keeps the old members");
            return;
        }
        List<Member> members = new ArrayList<>();
        for (Member member : MEMBERS) {
            if (removedOnHost(member)) members.add(member);
        }
        List<String[]> renames = new ArrayList<>();
        for (String[] rename : CLASS_RENAMES) {
            if (renamedOnHost(rename[0], rename[1])) renames.add(rename);
        }
        boolean scute = fieldRenamedOnHost(ITEMS, "SCUTE", "TURTLE_SCUTE");
        registerRedirects(transformer, members, renames, scute);
        LOGGER.info("Bridged {} removed 1.20.5 members and {} renamed classes", members.size(), renames.size());
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<Member> members,
            List<String[]> renames, boolean scute) {
        for (String[] rename : renames) {
            transformer.registerClassRedirect(rename[0], rename[1]);
            for (String[] method : METHOD_RENAMES) {
                if (method[0].equals(rename[0])) transformer.registerInterfaceMethodRename(method[0], method[1], method[2]);
            }
        }
        if (scute) {
            transformer.registerFieldRedirect(ITEMS, "SCUTE", ITEMS, "TURTLE_SCUTE");
        }
        if (members.isEmpty()) return;
        transformer.registerSyntheticClass(HELPER, generateHelper(members));
        for (Member member : members) {
            transformer.registerInheritedMethodRedirect(member.owner(), member.name(), member.desc(),
                    HELPER, member.name(), member.helperDesc());
        }
    }

    /** The host's own Entity decides; offline, the host version does. */
    static boolean hostIsPast1205() {
        ClassNode entity = ClassResourceInspector.read(ENTITY);
        if (entity != null) return hasMethod(entity, "igniteForSeconds", "(F)V");
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
    }

    private static boolean removedOnHost(Member member) {
        ClassNode owner = ClassResourceInspector.read(member.owner());
        return owner == null || !hasMethod(owner, member.name(), member.desc());
    }

    private static boolean renamedOnHost(String oldName, String newName) {
        if (ClassResourceInspector.read(ENTITY) == null) return true;
        return !ClassResourceInspector.exists(oldName) && ClassResourceInspector.exists(newName);
    }

    private static boolean fieldRenamedOnHost(String ownerName, String oldName, String newName) {
        ClassNode owner = ClassResourceInspector.read(ownerName);
        if (owner == null) return true;
        boolean hasOld = false;
        boolean hasNew = false;
        for (FieldNode field : owner.fields) {
            hasOld |= field.name.equals(oldName);
            hasNew |= field.name.equals(newName);
        }
        return !hasOld && hasNew;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper(List<Member> members) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        for (Member member : members) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, member.name(),
                    member.helperDesc(), null, null);
            mv.visitCode();
            switch (member.name()) {
                case "setSecondsOnFire" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ILOAD, 1);
                    mv.visitInsn(Opcodes.I2F);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "igniteForSeconds", "(F)V", false);
                    mv.visitInsn(Opcodes.RETURN);
                }
                case "setMaxUpStep" -> emitStepHeight(mv);
                case "hasCustomHoverName" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(Opcodes.GETSTATIC, COMPONENTS, "CUSTOM_NAME", "L" + COMPONENT_TYPE + ";");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "has", "(L" + COMPONENT_TYPE + ";)Z", false);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case "getXSpawn", "getYSpawn", "getZSpawn" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LEVEL_DATA, "getSpawnPos", "()L" + BLOCK_POS + ";", true);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3I, "get" + member.name().charAt(3), "()I", false);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case "hurtAndBreak" -> emitHurtAndBreak(mv);
                case "broadcastBreakEvent" -> emitBreakEvent(mv, member.desc().contains(HAND));
                default -> throw new IllegalArgumentException("No helper for " + member.name());
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Damages the stack in the slot that holds it. The new method breaks the item and plays the
     * break effects itself, which is what the old callback did, so the callback is not run. A
     * stack the entity does not hold is damaged as if it were in the main hand.
     */
    private static void emitHurtAndBreak(MethodVisitor mv) {
        String slots = "[L" + SLOT + ";";
        Label loop = new Label();
        Label next = new Label();
        Label fallback = new Label();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SLOT, "values", "()" + slots, false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getItemBySlot", "(L" + SLOT + ";)L" + ITEM_STACK + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, next);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "hurtAndBreak", "(IL" + LIVING + ";L" + SLOT + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(next);
        mv.visitIincInsn(5, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SLOT, "MAINHAND", "L" + SLOT + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "hurtAndBreak", "(IL" + LIVING + ";L" + SLOT + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
    }

    /** The break effects for the item in that hand or slot, through the 1.20.5 hook. */
    private static void emitBreakEvent(MethodVisitor mv, boolean hand) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (hand) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getItemInHand", "(L" + HAND + ";)L" + ITEM_STACK + ";", false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getItemBySlot", "(L" + SLOT + ";)L" + ITEM_STACK + ";", false);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (hand) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIVING, "getSlotForHand", "(L" + HAND + ";)L" + SLOT + ";", false);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "onEquippedItemBroken",
                "(Lnet/minecraft/world/item/Item;L" + SLOT + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
    }

    /** A living entity's {@code step_height} base value; nothing else has a step height to set. */
    private static void emitStepHeight(MethodVisitor mv) {
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, LIVING);
        mv.visitJumpInsn(Opcodes.IFEQ, done);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, LIVING);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "STEP_HEIGHT", "L" + HOLDER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getAttribute",
                "(L" + HOLDER + ";)L" + ATTRIBUTE_INSTANCE + ";", false);
        mv.visitInsn(Opcodes.DUP);
        Label present = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, present);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(present);
        mv.visitVarInsn(Opcodes.FLOAD, 1);
        mv.visitInsn(Opcodes.F2D);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATTRIBUTE_INSTANCE, "setBaseValue", "(D)V", false);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.RETURN);
    }
}
