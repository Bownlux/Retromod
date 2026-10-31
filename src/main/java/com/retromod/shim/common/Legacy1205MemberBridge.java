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

/** Bridges entity, item, and level members that 1.20.5 renamed or removed. */
public final class Legacy1205MemberBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/Legacy1205Members";
    /** The helper's cached lookup of a mob's own five-argument {@code finalizeSpawn}. */
    static final String OWN_FINALIZE_SPAWN = "ownFinalizeSpawn";

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
    static final String MOB = "net/minecraft/world/entity/Mob";
    static final String MOB_SPAWN_TYPE = "net/minecraft/world/entity/MobSpawnType";
    static final String ENTITY_SPAWN_REASON = "net/minecraft/world/entity/EntitySpawnReason";
    static final String RESPAWN_DATA = "net/minecraft/world/level/storage/LevelData$RespawnData";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String RANDOM_SOURCE = "net/minecraft/util/RandomSource";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String LEVEL = "net/minecraft/world/level/Level";
    /** {@code ItemStack.hurt(amount, random, player)}, removed in 1.20.5. */
    static final String ITEM_HURT = "(IL" + RANDOM_SOURCE + ";L" + SERVER_PLAYER + ";)Z";
    /** 1.20.5 and 1.20.6: the random form, which runs a callback when the stack breaks. */
    static final String HURT_AND_BREAK_RANDOM = "(IL" + RANDOM_SOURCE + ";L" + SERVER_PLAYER + ";Ljava/lang/Runnable;)V";
    /** 1.21 and newer: the level form, which also removes the broken item and then runs the callback. */
    static final String HURT_AND_BREAK_LEVEL = "(IL" + SERVER_LEVEL + ";L" + SERVER_PLAYER
            + ";Ljava/util/function/Consumer;)V";
    /** Records whether a {@code hurtAndBreak} broke the stack: it is that call's callback. */
    static final String BREAK_FLAG = "com/retromod/generated/LegacyItemBreakFlag";
    static final String TOOLTIP_CONTEXT = "net/minecraft/world/item/Item$TooltipContext";
    static final String TEMPT_GOAL = "net/minecraft/world/entity/ai/goal/TemptGoal";
    static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    static final String TEMPT_WITH_INGREDIENT =
            "(Lnet/minecraft/world/entity/PathfinderMob;DL" + INGREDIENT + ";Z)V";
    static final String TEMPT_WITH_PREDICATE =
            "(Lnet/minecraft/world/entity/PathfinderMob;DLjava/util/function/Predicate;Z)V";

    // The buffer's collection, map, and optional methods take StreamDecoder and StreamEncoder
    // where they took FriendlyByteBuf.Reader and Writer.
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

    /** How the running host spells the members the helper calls, since the helper is embedded in the mod. */
    record HostShape(boolean spawnPosGetter, boolean staticSlotForHand, boolean brokenItemIsStack,
            String spawnReason, boolean levelDamage) {

        /** The members as 1.20.5 left them. */
        static final HostShape AT_1_20_5 = new HostShape(true, true, false, MOB_SPAWN_TYPE, false);

        /** The members as 26.3 has them. */
        static final HostShape AT_26_3 = new HostShape(false, false, true, ENTITY_SPAWN_REASON, true);

        /** The host's own classes decide; offline, the host version does. */
        static HostShape probe() {
            ClassNode levelData = ClassResourceInspector.read(LEVEL_DATA);
            ClassNode living = ClassResourceInspector.read(LIVING);
            boolean spawnPos = levelData != null
                    ? hasMethod(levelData, "getSpawnPos", "()L" + BLOCK_POS + ";")
                    : hostBefore("1.21.9");
            boolean staticSlot = living != null
                    ? hasMethod(living, "getSlotForHand", "(L" + HAND + ";)L" + SLOT + ";")
                    : hostBefore("1.21.9");
            boolean brokenStack = living != null
                    ? hasMethod(living, "onEquippedItemBroken", "(L" + ITEM_STACK + ";L" + SLOT + ";)V")
                    : !hostBefore("26.3");
            String spawnReason;
            if (ClassResourceInspector.read(MOB) != null) {
                spawnReason = ClassResourceInspector.exists(ENTITY_SPAWN_REASON) ? ENTITY_SPAWN_REASON : MOB_SPAWN_TYPE;
            } else {
                spawnReason = hostBefore("1.21.2") ? MOB_SPAWN_TYPE : ENTITY_SPAWN_REASON;
            }
            ClassNode stack = ClassResourceInspector.read(ITEM_STACK);
            boolean levelDamage = stack != null
                    ? hasMethod(stack, "hurtAndBreak", HURT_AND_BREAK_LEVEL)
                    : !hostBefore("1.21");
            return new HostShape(spawnPos, staticSlot, brokenStack, spawnReason, levelDamage);
        }
    }

    /** {@code Mob.finalizeSpawn} with the {@code CompoundTag} 1.20.5 removed. */
    static Member finalizeSpawn(String spawnReason) {
        return new Member(MOB, "finalizeSpawn", "(Lnet/minecraft/world/level/ServerLevelAccessor;"
                + "Lnet/minecraft/world/DifficultyInstance;L" + spawnReason + ";"
                + "Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)"
                + "Lnet/minecraft/world/entity/SpawnGroupData;");
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
            new Member(LIVING, "broadcastBreakEvent", "(L" + SLOT + ";)V"),
            new Member(ITEM_STACK, "hurt", ITEM_HURT),
            // A Fabric mod's hurt keeps its intermediary id, which the 26.x name table names
            // hurtAndBreak, with the old descriptor.
            new Member(ITEM_STACK, "hurtAndBreak", ITEM_HURT));

    private Legacy1205MemberBridge() {}

    public static void register(RetromodTransformer transformer) {
        // Serves Mojang-named mods built for 1.20.4 or older, such as Forge 1.20.1 mods after the
        // SRG remap. Each change is registered only when the host proves it; offline, where the
        // host classes are not readable, the host version decides.
        if (!hostIsPast1205()) {
            LOGGER.debug("1.20.5 member bridge not needed: the host keeps the old members");
            return;
        }
        HostShape shape = HostShape.probe();
        List<Member> members = new ArrayList<>();
        for (Member member : MEMBERS) {
            if (removedOnHost(member)) members.add(member);
        }
        // finalizeSpawn names the spawn reason class: MobSpawnType, or EntitySpawnReason from 1.21.2.
        Member finalizeSpawn = finalizeSpawn(shape.spawnReason());
        if (removedOnHost(finalizeSpawn)) members.add(finalizeSpawn);
        List<String[]> renames = new ArrayList<>();
        for (String[] rename : CLASS_RENAMES) {
            if (renamedOnHost(rename[0], rename[1])) renames.add(rename);
        }
        boolean scute = fieldRenamedOnHost(ITEMS, "SCUTE", "TURTLE_SCUTE");
        registerRedirects(transformer, members, renames, scute, shape);
        if (temptGoalTakesPredicate()) registerTemptGoalWidening(transformer);
        if (propertiesLostStackSize()) registerPropertiesStackSize(transformer);
        registerItemNames(transformer);
        enableLevelTooltips(transformer);
        LOGGER.info("Bridged {} removed 1.20.5 members and {} renamed classes", members.size(), renames.size());
    }

    /**
     * 1.20.5 made {@code Item.toString()} return the namespaced id where it returned the path, and
     * mods built ids from it, such as Fossils' {@code fossil:} + toString(). Only mods built before
     * 1.20.5 are rewritten, and every object but an item passes through unchanged.
     */
    static void registerItemNames(RetromodTransformer transformer) {
        String helper = "com/retromod/shim/common/embedded/LegacyItemNames";
        com.retromod.core.SyntheticEmbedder.registerClassResource(transformer, helper,
                com.retromod.shim.common.embedded.LegacyItemNames.class);
        String toString = "()Ljava/lang/String;";
        String helperDesc = "(Ljava/lang/Object;)Ljava/lang/String;";
        // Wrapping, so a mod's own super.toString() keeps calling the real method.
        for (String owner : new String[]{"java/lang/Object", ITEM}) {
            transformer.registerWrappingMethodRedirect(owner, "toString", toString, helper, "toString", helperDesc);
            transformer.limitRedirectToModsBefore(owner, "toString", toString, "1.20.5");
        }
        transformer.registerMethodRedirect("java/lang/String", "valueOf", helperDesc, helper, "valueOf", helperDesc);
        transformer.limitRedirectToModsBefore("java/lang/String", "valueOf", helperDesc, "1.20.5");
    }

    /** {@code Item.Properties.maxStackSize}, which a mod's own properties class wrote, as the stack size component. */
    static void registerPropertiesStackSize(RetromodTransformer transformer) {
        String bridge = "com/retromod/mixin/runtime/ItemComponentsBridge";
        com.retromod.core.SyntheticEmbedder.registerClassResource(transformer, bridge,
                com.retromod.mixin.runtime.ItemComponentsBridge.class);
        // Inherited: the write names the mod's subclass, such as Fossils' JavelinProperties.
        transformer.registerInheritedFieldStaticBridge(ITEM + "$Properties", "maxStackSize", "I", bridge,
                "propertiesStackSize", "(Ljava/lang/Object;)I", "setPropertiesStackSize", "(Ljava/lang/Object;I)V");
    }

    private static boolean propertiesLostStackSize() {
        ClassNode properties = ClassResourceInspector.read(ITEM + "$Properties");
        if (properties == null) return !hostBefore("1.20.5");
        return properties.fields.stream().noneMatch(field -> field.name.equals("maxStackSize"));
    }

    /** Points the old {@code TemptGoal(mob, speed, Ingredient, scare)} constructor at the predicate one. */
    static void registerTemptGoalWidening(RetromodTransformer transformer) {
        // This covers new TemptGoal(...) and a subclass's super(...). An Ingredient is a
        // Predicate<ItemStack>, so the value on the stack fits and only the descriptor changes.
        transformer.registerConvertingRedirect(TEMPT_GOAL, "<init>", TEMPT_WITH_INGREDIENT,
                TEMPT_GOAL, "<init>", TEMPT_WITH_PREDICATE, 0, 0);
    }

    /** Whether the old {@code TemptGoal} constructor call can safely be widened to the predicate one. */
    static boolean temptGoalTakesPredicate() {
        ClassNode goal = ClassResourceInspector.read(TEMPT_GOAL);
        ClassNode ingredient = ClassResourceInspector.read(INGREDIENT);
        // Offline, the version decides.
        if (goal == null || ingredient == null) return !hostBefore("1.20.5");
        // The widened call verifies only when the predicate constructor is the only one and the
        // host's Ingredient still implements Predicate.
        return hasMethod(goal, "<init>", TEMPT_WITH_PREDICATE)
                && !hasMethod(goal, "<init>", TEMPT_WITH_INGREDIENT)
                && ingredient.interfaces.contains("java/util/function/Predicate");
    }

    /** Bridges tooltip overrides that still take the {@code Level}, when the host shows the change. */
    static void enableLevelTooltips(RetromodTransformer transformer) {
        // Tooltips took the Level until 1.20.5 and a TooltipContext after it, so Minecraft never
        // calls an old override. LegacyTooltipAdapter.applyLevelShape bridges it.
        ClassNode item = ClassResourceInspector.read(ITEM);
        ClassNode context = ClassResourceInspector.read(TOOLTIP_CONTEXT);
        // Offline, the version decides and no level is passed.
        if (item == null || context == null) {
            if (!hostBefore("1.20.5")) transformer.enablePostProcess(LegacyTooltipAdapter.LEVEL_FLAG);
            return;
        }
        boolean levelShapeGone = !hasMethod(item, LegacyTooltipAdapter.APPEND, LegacyTooltipAdapter.LEVEL_DESC);
        boolean contextFromLevel = hasMethod(context, "of",
                "(Lnet/minecraft/world/level/Level;)L" + TOOLTIP_CONTEXT + ";");
        if (!levelShapeGone || !contextFromLevel) return;
        transformer.enablePostProcess(LegacyTooltipAdapter.LEVEL_FLAG);
        // Hand the old method the context's level when the host's context can return it.
        if (hasMethod(context, "level", "()Lnet/minecraft/world/level/Level;")) {
            transformer.enablePostProcess(LegacyTooltipAdapter.CONTEXT_LEVEL_FLAG);
        }
    }

    private static boolean hostBefore(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<Member> members,
            List<String[]> renames, boolean scute, HostShape shape) {
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
        transformer.registerSyntheticClass(HELPER, generateHelper(members, shape));
        // The hurt helper also needs the callback class that records a break.
        if (members.stream().anyMatch(member -> member.desc().equals(ITEM_HURT))) {
            transformer.registerSyntheticClass(BREAK_FLAG, generateBreakFlag());
        }
        // Mod entities usually call these as their own inherited members, so calls follow the
        // proven class hierarchy.
        for (Member member : members) {
            transformer.registerInheritedMethodRedirect(member.owner(), member.name(), member.desc(),
                    HELPER, member.name(), member.helperDesc());
            // A mob that declares the old finalizeSpawn keeps it: a call to it runs that override.
            if (member.name().equals("finalizeSpawn")) {
                OwnOverrideCall.register(transformer, HELPER, member.name(), member.helperDesc());
            }
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

    static byte[] generateHelper(List<Member> members, HostShape shape) {
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
            // Matched by descriptor, because a Fabric mod's hurt arrives named hurtAndBreak.
            if (member.desc().equals(ITEM_HURT)) {
                emitItemHurt(mv, shape);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                continue;
            }
            switch (member.name()) {
                // setSecondsOnFire(int) became igniteForSeconds(float).
                case "setSecondsOnFire" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ILOAD, 1);
                    mv.visitInsn(Opcodes.I2F);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "igniteForSeconds", "(F)V", false);
                    mv.visitInsn(Opcodes.RETURN);
                }
                case "setMaxUpStep" -> emitStepHeight(mv);
                // A custom hover name is the minecraft:custom_name component now.
                case "hasCustomHoverName" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitFieldInsn(Opcodes.GETSTATIC, COMPONENTS, "CUSTOM_NAME", "L" + COMPONENT_TYPE + ";");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "has", "(L" + COMPONENT_TYPE + ";)Z", false);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case "getXSpawn", "getYSpawn", "getZSpawn" -> {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    emitSpawnPos(mv, shape);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3I, "get" + member.name().charAt(3), "()I", false);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case "hurtAndBreak" -> emitHurtAndBreak(mv);
                case "broadcastBreakEvent" -> emitBreakEvent(mv, member.desc().contains(HAND), shape);
                case "finalizeSpawn" -> emitFinalizeSpawn(cw, mv, member);
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

    /** Emits {@code hurt(stack, amount, random, player)}, which returns whether the stack broke. */
    private static void emitItemHurt(MethodVisitor mv, HostShape shape) {
        // Locals: 0 stack, 1 amount, 2 random, 3 player, 4 the break flag, 5 the new damage.
        // A stack that cannot be damaged never breaks.
        Label damageable = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "isDamageableItem", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, damageable);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(damageable);

        if (shape.levelDamage()) {
            // The level form needs the server level for Unbreaking, which only a player supplies,
            // so a call without a player damages the stack by the full amount.
            Label withPlayer = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitJumpInsn(Opcodes.IFNONNULL, withPlayer);
            emitPlainDamage(mv);
            mv.visitLabel(withPlayer);
        }

        // The host's hurtAndBreak does the damage, so Unbreaking and the durability trigger apply
        // as they do for vanilla tools. The break flag is its callback. From 1.21 it takes the
        // server level and a consumer, and spares a creative player's stack.
        mv.visitTypeInsn(Opcodes.NEW, BREAK_FLAG);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BREAK_FLAG, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        if (shape.levelDamage()) {
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, SERVER_LEVEL);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "hurtAndBreak", HURT_AND_BREAK_LEVEL, false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "hurtAndBreak", HURT_AND_BREAK_RANDOM, false);
        }
        // Answer whether it broke, and leave the broken stack for the caller to remove.
        Label kept = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.GETFIELD, BREAK_FLAG, "broken", "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, kept);
        if (shape.levelDamage()) {
            // The level form removed the broken item, so it is put back for the caller to remove.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "grow", "(I)V", false);
        }
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(kept);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
    }

    /** {@code damage = stack.getDamageValue() + amount; stack.setDamageValue(damage); return damage >= max}. */
    private static void emitPlainDamage(MethodVisitor mv) {
        Label broken = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getDamageValue", "()I", false);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "setDamageValue", "(I)V", false);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getMaxDamage", "()I", false);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, broken);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(broken);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
    }

    /** Generates the {@code hurtAndBreak} callback that records whether the stack broke. */
    static byte[] generateBreakFlag() {
        // It is a Runnable for the random form and a Consumer for the level form.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, BREAK_FLAG, null,
                "java/lang/Object", new String[] {"java/lang/Runnable", "java/util/function/Consumer"});
        cw.visitField(Opcodes.ACC_PUBLIC, "broken", "Z", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // run and accept both set broken.
        for (String[] callback : new String[][] {{"run", "()V"}, {"accept", "(Ljava/lang/Object;)V"}}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, callback[0], callback[1], null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, BREAK_FLAG, "broken", "Z");
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The level's spawn position, read the way the host stores it. */
    private static void emitSpawnPos(MethodVisitor mv, HostShape shape) {
        if (shape.spawnPosGetter()) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LEVEL_DATA, "getSpawnPos", "()L" + BLOCK_POS + ";", true);
            return;
        }
        // 1.21.9 replaced getSpawnPos() with getRespawnData().pos().
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LEVEL_DATA, "getRespawnData", "()L" + RESPAWN_DATA + ";", true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESPAWN_DATA, "pos", "()L" + BLOCK_POS + ";", false);
    }

    /** Plays the break effects for that hand or slot through the hook that replaced the old event. */
    private static void emitBreakEvent(MethodVisitor mv, boolean hand, HostShape shape) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (shape.brokenItemIsStack()) {
            // On 26.3 the hook takes the broken stack and also stops its attribute and enchantment
            // effects. The old event only played the effects, and its stack is usually still
            // equipped, so the hook gets ItemStack.EMPTY: the effects play and nothing worn stops.
            mv.visitFieldInsn(Opcodes.GETSTATIC, ITEM_STACK, "EMPTY", "L" + ITEM_STACK + ";");
        } else {
            // Older hosts take the Item in that hand or slot.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            if (hand) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getItemInHand", "(L" + HAND + ";)L" + ITEM_STACK + ";", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getItemBySlot", "(L" + SLOT + ";)L" + ITEM_STACK + ";", false);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false);
        }
        // Newer hosts replaced the static getSlotForHand(hand) with InteractionHand.asEquipmentSlot().
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (hand && shape.staticSlotForHand()) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIVING, "getSlotForHand", "(L" + HAND + ";)L" + SLOT + ";", false);
        } else if (hand) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HAND, "asEquipmentSlot", "()L" + SLOT + ";", false);
        }
        String broken = shape.brokenItemIsStack() ? ITEM_STACK : "net/minecraft/world/item/Item";
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "onEquippedItemBroken",
                "(L" + broken + ";L" + SLOT + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
    }

    /** Emits the {@code finalizeSpawn} helper and its {@code super$} twin. */
    private static void emitFinalizeSpawn(ClassWriter cw, MethodVisitor mv, Member member) {
        // Run the mob's own five-argument override when its class declares one, as virtual
        // dispatch did on the mod's version, and the host's four-argument form otherwise.
        OwnOverrideCall.declareLookup(cw, OWN_FINALIZE_SPAWN);
        OwnOverrideCall.emitOwnCall(mv, HELPER, OWN_FINALIZE_SPAWN, member.name(), member.helperDesc(),
                OwnOverrideCall.ownArguments(member.helperDesc()));
        emitHostFinalizeSpawn(mv, member);

        // The super$ twin answers the override's own super call with the host's form only.
        MethodVisitor superCall = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                OwnOverrideCall.superName(member.name()), member.helperDesc(), null, null);
        superCall.visitCode();
        emitHostFinalizeSpawn(superCall, member);
        superCall.visitMaxs(0, 0);
        superCall.visitEnd();
    }

    /** Calls the four-argument {@code finalizeSpawn}; the dropped tag has no successor. */
    private static void emitHostFinalizeSpawn(MethodVisitor mv, Member member) {
        for (int local = 0; local <= 4; local++) {
            mv.visitVarInsn(Opcodes.ALOAD, local);
        }
        String withoutTag = member.desc().replace("Lnet/minecraft/nbt/CompoundTag;)", ")");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOB, "finalizeSpawn", withoutTag, false);
        mv.visitInsn(Opcodes.ARETURN);
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
