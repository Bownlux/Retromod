/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bridges arrow, mob effect and death loot members that 1.20.5 to 1.21.2 reshaped, for Mojang-named mods. */
public final class Legacy1205GameplayBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public static final String HELPER = "com/retromod/generated/Legacy1205Gameplay";
    static final String ARROW_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/entity/projectile/LegacyArrowMembers";
    static final String EFFECT_HOLDERS = RebuiltApiClasses.ROOT + "minecraft/world/effect/LegacyEffectHolders";
    static final String ENCHANTMENT_SUPPORT = "com/retromod/shim/common/embedded/LegacyEnchantmentSupport";

    static final String ARROW_1205 = "net/minecraft/world/entity/projectile/AbstractArrow";
    static final String ARROW_261 = "net/minecraft/world/entity/projectile/arrow/AbstractArrow";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String ITEMS = "net/minecraft/world/item/Items";
    static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    static final String ARROW_ITEM = "net/minecraft/world/item/ArrowItem";
    static final String MOB_EFFECT = "net/minecraft/world/effect/MobEffect";
    static final String EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    static final String ATTRIBUTE_MAP = "net/minecraft/world/entity/ai/attributes/AttributeMap";
    static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    static final String EFFECT_PACKET = "net/minecraft/network/protocol/game/ClientboundUpdateMobEffectPacket";
    static final String AABB = "net/minecraft/world/phys/AABB";
    static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String TOOLTIP_FLAG = "net/minecraft/world/item/TooltipFlag";
    static final String TOOLTIP_CONTEXT = "net/minecraft/world/item/Item$TooltipContext";
    static final String ITEM_ATTRIBUTE_MODIFIERS = "net/minecraft/world/item/component/ItemAttributeModifiers";
    static final String SHARED_CONSTANTS = "net/minecraft/SharedConstants";
    static final String STRING_UTIL = "net/minecraft/util/StringUtil";
    static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    static final String NBT_UTILS = "net/minecraft/nbt/NbtUtils";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
    static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    static final String SCOREBOARD = "net/minecraft/world/scores/Scoreboard";
    static final String SERVER_SCOREBOARD = "net/minecraft/server/ServerScoreboard";
    static final String SCORE = "net/minecraft/world/scores/Score";
    static final String SCORE_ACCESS = "net/minecraft/world/scores/ScoreAccess";
    static final String SCORE_HOLDER = "net/minecraft/world/scores/ScoreHolder";
    static final String OBJECTIVE = "net/minecraft/world/scores/Objective";
    /** A score that reads and writes through the scoreboard's access, so a change still reaches the players. */
    public static final String LEGACY_SCORE = "com/retromod/generated/LegacyScore";
    static final String OLD_PLAYER_SCORE = "(Ljava/lang/String;L" + OBJECTIVE + ";)L" + SCORE + ";";
    static final String OLD_TOOLTIP_LINES = "(L" + PLAYER + ";L" + TOOLTIP_FLAG + ";)Ljava/util/List;";

    static final String OLD_EFFECT_PACKET = "(IL" + EFFECT_INSTANCE + ";)V";
    static final String NEW_EFFECT_PACKET = "(IL" + EFFECT_INSTANCE + ";Z)V";
    static final String SAME_STACKS = "(L" + ITEM_STACK + ";L" + ITEM_STACK + ";)Z";
    /** The old arrow constructors, for the abstract arrow and for the two vanilla arrows a mod builds directly. */
    static final String OLD_ARROW_AT = "(L" + ENTITY_TYPE + ";DDDL" + LEVEL + ";)V";
    static final String OLD_ARROW_FROM = "(L" + ENTITY_TYPE + ";L" + LIVING + ";L" + LEVEL + ";)V";
    static final String OLD_PLAIN_ARROW_AT = "(L" + LEVEL + ";DDD)V";
    static final String OLD_PLAIN_ARROW_FROM = "(L" + LEVEL + ";L" + LIVING + ";)V";

    /** The plain arrows 1.21 also gave a pickup stack and a weapon, with the item each picks up as. */
    static final String[][] PLAIN_ARROWS = {
            {"Arrow", "ARROW"}, {"SpectralArrow", "SPECTRAL_ARROW"}};

    /** How the host spells the reshaped members, read from its classes at run time and from its version offline. */
    record Shape(String arrow, boolean pickupDefault, int arrowStacks, boolean knockbackGone, boolean pierceHidden,
            boolean arrowItemWeapon, boolean effectTickBoolean, boolean effectTickLevel, String instantName,
            boolean effectMapOnly, boolean deathLootLevel, boolean sameComponents, boolean effectPacketBlend,
            boolean newTagApi) {

        /** 1.21.1, the host the shapes were first written for. */
        static final Shape AT_1_21_1 = new Shape(ARROW_1205, true, 2, true, true, true, true, false,
                "applyInstantenousEffect", true, true, true, true, false);

        /** 26.3, where the arrow moved and the effect tick took the server level. */
        static final Shape AT_26_3 = new Shape(ARROW_261, true, 2, true, true, true, true, true,
                "applyInstantaneousEffect", true, true, true, true, true);

        static Shape probe() {
            ClassNode arrowNode = ClassResourceInspector.read(ARROW_261);
            if (arrowNode == null) arrowNode = ClassResourceInspector.read(ARROW_1205);
            String arrow = arrowNode != null ? arrowNode.name : from("26.1") ? ARROW_261 : ARROW_1205;
            ClassNode effect = ClassResourceInspector.read(MOB_EFFECT);
            ClassNode living = ClassResourceInspector.read(LIVING);
            ClassNode arrowItem = ClassResourceInspector.read(ARROW_ITEM);
            ClassNode stack = ClassResourceInspector.read(ITEM_STACK);
            ClassNode packet = ClassResourceInspector.read(EFFECT_PACKET);
            ClassNode tag = ClassResourceInspector.read(COMPOUND_TAG);
            String lArrow = "L" + arrow + ";";
            String oldCreate = "(L" + LEVEL + ";L" + ITEM_STACK + ";L" + LIVING + ";)" + lArrow;
            return new Shape(arrow,
                    arrowNode != null ? isAbstract(arrowNode, "getDefaultPickupItem", "()L" + ITEM_STACK + ";") : from("1.20.5"),
                    arrowNode != null ? appendedStacks(arrowNode) : from("1.21") ? 2 : from("1.20.5") ? 1 : 0,
                    arrowNode != null ? !has(arrowNode, "setKnockback", "(I)V") : from("1.21"),
                    arrowNode != null ? isPrivate(arrowNode, "setPierceLevel", "(B)V") : from("1.21"),
                    arrowItem != null ? !has(arrowItem, "createArrow", oldCreate) : from("1.21"),
                    effect != null ? has(effect, "applyEffectTick", "(L" + LIVING + ";I)Z")
                            || has(effect, "applyEffectTick", "(L" + SERVER_LEVEL + ";L" + LIVING + ";I)Z") : from("1.20.5"),
                    effect != null ? has(effect, "applyEffectTick", "(L" + SERVER_LEVEL + ";L" + LIVING + ";I)Z") : from("1.21.2"),
                    effect != null ? (hasNamed(effect, "applyInstantaneousEffect") ? "applyInstantaneousEffect" : "applyInstantenousEffect")
                            : from("26.2") ? "applyInstantaneousEffect" : "applyInstantenousEffect",
                    effect != null ? has(effect, "addAttributeModifiers", "(L" + ATTRIBUTE_MAP + ";I)V") : from("1.20.5"),
                    living != null ? has(living, "dropCustomDeathLoot",
                            "(L" + SERVER_LEVEL + ";L" + DAMAGE_SOURCE + ";Z)V") : from("1.21"),
                    stack != null ? has(stack, "isSameItemSameComponents", SAME_STACKS)
                            && !has(stack, "isSameItemSameTags", SAME_STACKS) : from("1.20.5"),
                    packet != null ? has(packet, "<init>", NEW_EFFECT_PACKET) && !has(packet, "<init>", OLD_EFFECT_PACKET)
                            : from("1.20.5"),
                    tag != null ? !has(tag, "remove", "(Ljava/lang/String;)V") : from("1.21.5"));
        }

        /** How many stacks the host's arrow constructors take after the old arguments: 2 from 1.21, 1 before. */
        private static int appendedStacks(ClassNode arrowNode) {
            String stack = "L" + ITEM_STACK + ";";
            if (has(arrowNode, "<init>", OLD_ARROW_AT)) return 0;
            if (has(arrowNode, "<init>", OLD_ARROW_AT.replace(")V", stack + stack + ")V"))) return 2;
            return has(arrowNode, "<init>", OLD_ARROW_AT.replace(")V", stack + ")V")) ? 1 : 0;
        }
    }

    private Legacy1205GameplayBridge() {}

    public static void register(RetromodTransformer transformer) {
        Shape shape = Shape.probe();
        registerRedirects(transformer, shape);
        if (transformer.firstRegistrationNotice(Legacy1205GameplayBridge.class.getName())) {
            LOGGER.debug(RetromodLog.DETAIL, "Bridged reshaped 1.20.5 to 1.21.2 arrow, effect and death loot members");
        }
    }

    /** Unconditional registration for one host shape, which transform-shape tests call directly. */
    static void registerRedirects(RetromodTransformer transformer, Shape shape) {
        RebuiltApiClasses.registerTree(transformer, ARROW_MEMBERS);
        RebuiltApiClasses.registerTree(transformer, EFFECT_HOLDERS);
        // The death loot bridge reads the killer's looting level by id, which every host can answer.
        SyntheticEmbedder.registerClassResource(transformer, ENCHANTMENT_SUPPORT,
                com.retromod.shim.common.embedded.LegacyEnchantmentSupport.class);
        transformer.registerSyntheticClass(HELPER, generateHelper(shape));

        // 1.20.5 renamed the stack comparison when stack tags became components; it compares the same data.
        if (shape.sameComponents()) {
            transformer.registerMethodRedirect(ITEM_STACK, "isSameItemSameTags", SAME_STACKS,
                    ITEM_STACK, "isSameItemSameComponents", SAME_STACKS);
        }
        // 1.20.5 gave the effect packet a blend flag; a resend, which is what old mods send, does not blend.
        if (shape.effectPacketBlend()) {
            transformer.registerConstructorRedirect(EFFECT_PACKET, OLD_EFFECT_PACKET, HELPER, "updateMobEffectPacket",
                    "(IL" + EFFECT_INSTANCE + ";)L" + EFFECT_PACKET + ";");
        }
        registerArrowConstructors(transformer, shape);
        registerSmallMembers(transformer);
        // Both arrow names, whichever the class moves applied before the redirect is looked up.
        for (String arrow : new String[]{ARROW_1205, ARROW_261}) {
            if (shape.knockbackGone()) {
                transformer.registerInheritedMethodRedirect(arrow, "setKnockback", "(I)V",
                        ARROW_MEMBERS, "setKnockback", "(Ljava/lang/Object;I)V");
            }
            if (shape.pierceHidden()) {
                transformer.registerInheritedMethodRedirect(arrow, "setPierceLevel", "(B)V",
                        ARROW_MEMBERS, "setPierceLevel", "(Ljava/lang/Object;B)V");
            }
        }
    }

    /** Single calls and fields 1.20.5 moved or reshaped, each registered when the host shows the change. */
    static void registerSmallMembers(RetromodTransformer transformer) {
        String pos = "L" + BLOCK_POS + ";";
        // The block-corner box constructor became a factory that also covers the far blocks, so a helper keeps the old corners.
        ClassNode box = ClassResourceInspector.read(AABB);
        if (box != null ? !has(box, "<init>", "(" + pos + pos + ")V") : from("1.20.5")) {
            transformer.registerConstructorRedirect(AABB, "(" + pos + pos + ")V", HELPER, "blockBox",
                    "(" + pos + pos + ")L" + AABB + ";");
        }
        // Tooltips take a context; the player's level makes one, as the old call had no other.
        ClassNode stack = ClassResourceInspector.read(ITEM_STACK);
        if (stack != null ? !has(stack, "getTooltipLines", OLD_TOOLTIP_LINES) : from("1.20.5")) {
            transformer.registerMethodRedirect(ITEM_STACK, "getTooltipLines", OLD_TOOLTIP_LINES, HELPER, "tooltipLines",
                    "(L" + ITEM_STACK + ";" + OLD_TOOLTIP_LINES.substring(1), true);
        }
        // The attribute number format moved to the item's attribute modifiers component.
        if (stack != null ? stack.fields.stream().noneMatch(field -> field.name.equals("ATTRIBUTE_MODIFIER_FORMAT"))
                : from("1.20.5")) {
            transformer.registerFieldRedirect(ITEM_STACK, "ATTRIBUTE_MODIFIER_FORMAT", ITEM_ATTRIBUTE_MODIFIERS,
                    "ATTRIBUTE_MODIFIER_FORMAT");
        }
        registerChatText(transformer);
        registerOldPositionTags(transformer);
        // The custom name is a component now; the tag element calls go through the item component polyfill.
        if (stack != null ? !has(stack, "setHoverName", "(L" + COMPONENT + ";)L" + ITEM_STACK + ";") : from("1.20.5")) {
            transformer.registerMethodRedirect(ITEM_STACK, "setHoverName", "(L" + COMPONENT + ";)L" + ITEM_STACK + ";",
                    HELPER, "setHoverName", "(L" + ITEM_STACK + ";L" + COMPONENT + ";)L" + ITEM_STACK + ";", true);
        }
        ClassNode board = ClassResourceInspector.read(SCOREBOARD);
        if (board != null ? !has(board, "getOrCreatePlayerScore", OLD_PLAYER_SCORE) : from("1.20.3")) {
            registerScores(transformer);
        }
    }

    /**
     * A player's score by name, which 1.20.3 turned into a {@code ScoreAccess} for a score holder. The old call gets a
     * score whose value reads and writes through that access, so a change is still sent to the players.
     */
    private static void registerScores(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(LEGACY_SCORE, generateLegacyScore());
        for (String owner : new String[]{SCOREBOARD, SERVER_SCOREBOARD}) {
            transformer.registerMethodRedirect(owner, "getOrCreatePlayerScore", OLD_PLAYER_SCORE, HELPER, "playerScore",
                    "(L" + SCOREBOARD + ";" + OLD_PLAYER_SCORE.substring(1), true);
        }
        // The old getter and setter became value() and value(int), which the score above answers through its access.
        transformer.registerMethodRedirect(SCORE, "getScore", "()I", SCORE, "value", "()I");
        transformer.registerMethodRedirect(SCORE, "setScore", "(I)V", SCORE, "value", "(I)V");
    }

    /** {@code class LegacyScore extends Score} whose value is the scoreboard's, through the access it wraps. */
    static byte[] generateLegacyScore() {
        String lAccess = "L" + SCORE_ACCESS + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LEGACY_SCORE, null, SCORE, null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "access", lAccess, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + lAccess + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, SCORE, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, LEGACY_SCORE, "access", lAccess);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, LEGACY_SCORE, "access", lAccess);
        get.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCORE_ACCESS, "get", "()I", true);
        get.visitInsn(Opcodes.IRETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor set = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(I)V", null, null);
        set.visitCode();
        set.visitVarInsn(Opcodes.ALOAD, 0);
        set.visitFieldInsn(Opcodes.GETFIELD, LEGACY_SCORE, "access", lAccess);
        set.visitVarInsn(Opcodes.ILOAD, 1);
        set.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCORE_ACCESS, "set", "(I)V", true);
        set.visitInsn(Opcodes.RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code SharedConstants}' chat text checks, which moved to {@code StringUtil}; 26.x widened the character to a code point. */
    private static void registerChatText(RetromodTransformer transformer) {
        ClassNode shared = ClassResourceInspector.read(SHARED_CONSTANTS);
        ClassNode strings = ClassResourceInspector.read(STRING_UTIL);
        if (shared != null ? has(shared, "isAllowedChatCharacter", "(C)Z") : !from("1.20.5")) return;
        boolean codePoint = strings != null ? has(strings, "isAllowedChatCharacter", "(I)Z") : from("26.1");
        // A char is already an int on the stack, so only the descriptor changes.
        transformer.registerMethodRedirect(SHARED_CONSTANTS, "isAllowedChatCharacter", "(C)Z",
                STRING_UTIL, "isAllowedChatCharacter", codePoint ? "(I)Z" : "(C)Z");
        transformer.registerMethodRedirect(SHARED_CONSTANTS, "filterText", "(Ljava/lang/String;)Ljava/lang/String;",
                STRING_UTIL, "filterText", "(Ljava/lang/String;)Ljava/lang/String;");
    }

    /**
     * {@code NbtUtils.readBlockPos(tag)} and {@code writeBlockPos(pos)} in their 1.20.1 X, Y and Z form, on a 1.20.5 to
     * 1.21.4 host. From 1.21.5 the rebuilt NbtUtils members answer them, through the shims of that release.
     */
    private static void registerOldPositionTags(RetromodTransformer transformer) {
        ClassNode tag = ClassResourceInspector.read(COMPOUND_TAG);
        boolean beforeTypedGetters = tag != null ? has(tag, "getInt", "(Ljava/lang/String;)I") : !from("1.21.5");
        if (!beforeTypedGetters || !from("1.20.5")) return;
        String pos = "L" + BLOCK_POS + ";";
        String compound = "L" + COMPOUND_TAG + ";";
        transformer.registerMethodRedirect(NBT_UTILS, "readBlockPos", "(" + compound + ")" + pos,
                HELPER, "readBlockPos", "(" + compound + ")" + pos);
        transformer.registerMethodRedirect(NBT_UTILS, "writeBlockPos", "(" + pos + ")" + compound,
                HELPER, "writeBlockPos", "(" + pos + ")" + compound);
    }

    /** The old arrow constructors take the stacks the host added: a pickup stack of the arrow's item and no weapon. */
    private static void registerArrowConstructors(RetromodTransformer transformer, Shape shape) {
        if (shape.arrowStacks() == 0) return;
        String stacks = ("L" + ITEM_STACK + ";").repeat(shape.arrowStacks());
        for (String arrow : new String[]{ARROW_1205, ARROW_261}) {
            for (String old : new String[]{OLD_ARROW_AT, OLD_ARROW_FROM}) {
                transformer.registerSuperConstructorAdapter(arrow, old, old.replace(")V", stacks + ")V"),
                        mv -> pushArrowStacks(mv, "ARROW", shape.arrowStacks()));
            }
        }
        for (String[] plain : PLAIN_ARROWS) {
            for (String pkg : new String[]{"net/minecraft/world/entity/projectile/", "net/minecraft/world/entity/projectile/arrow/"}) {
                for (String old : new String[]{OLD_PLAIN_ARROW_AT, OLD_PLAIN_ARROW_FROM}) {
                    // Covers new Arrow(level, shooter) as well as a mod arrow's super call.
                    transformer.registerSuperConstructorAdapter(pkg + plain[0], old, old.replace(")V", stacks + ")V"),
                            mv -> pushArrowStacks(mv, plain[1], shape.arrowStacks()));
                }
            }
        }
    }

    /** Pushes {@code new ItemStack(Items.<item>)}, then the weapon when the host takes one. */
    static void pushArrowStacks(MethodVisitor mv, String item, int stacks) {
        // The host saves the pickup stack with the arrow, and an empty stack cannot be saved, so it is never empty.
        mv.visitTypeInsn(Opcodes.NEW, ITEM_STACK);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ITEMS, item, "L" + ITEM + ";");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM_STACK, "<init>", "(L" + ITEM_LIKE + ";)V", false);
        // The bow firing through an old arrow item, so its enchantments apply; otherwise none, because old code that
        // builds an arrow itself applies its own bow enchantments, which would then count twice.
        if (stacks > 1) pushWeapon(mv);
    }

    /** Pushes the weapon an old arrow item is firing with, or null. */
    static void pushWeapon(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARROW_MEMBERS, "weapon", "()L" + ITEM_STACK + ";", false);
    }

    private static boolean from(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }

    static boolean has(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasNamed(ClassNode owner, String name) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name));
    }

    private static boolean isAbstract(ClassNode owner, String name, String desc) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc)
                && (method.access & Opcodes.ACC_ABSTRACT) != 0);
    }

    private static boolean isPrivate(ClassNode owner, String name, String desc) {
        return owner.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc)
                && (method.access & Opcodes.ACC_PRIVATE) != 0);
    }

    // ---- the generated helper ----------------------------------------------------------------

    static byte[] generateHelper(Shape shape) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        emitEffectPacket(cw);
        emitPickupOrArrow(cw);
        emitKnockback(cw);
        emitEffectTick(cw, shape);
        emitEffectInstant(cw, shape);
        if (shape.effectTickLevel()) emitEffectTickKeeps(cw);
        emitEffectAttributes(cw);
        emitBlockBox(cw);
        emitTooltipLines(cw);
        // 1.21.5 rebuilt the tag getters along with remove, and from there the rebuilt NbtUtils members answer instead.
        if (!shape.newTagApi()) emitPositionTags(cw);
        emitHoverName(cw);
        emitPlayerScore(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code new ClientboundUpdateMobEffectPacket(id, effect, false)}. */
    private static void emitEffectPacket(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "updateMobEffectPacket", "(IL" + EFFECT_INSTANCE + ";)L" + EFFECT_PACKET + ";");
        mv.visitTypeInsn(Opcodes.NEW, EFFECT_PACKET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, EFFECT_PACKET, "<init>", NEW_EFFECT_PACKET, false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** The stack an old arrow picks up as, or a plain arrow when it has none, since an empty one cannot be saved. */
    private static void emitPickupOrArrow(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "pickupOrArrow", "(Ljava/lang/Object;)L" + ITEM_STACK + ";");
        Label fallback = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, ITEM_STACK);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ITEM_STACK);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ITEM_STACK);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(fallback);
        pushArrowStacks(mv, "ARROW", 1);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /**
     * Pushes {@code target} away from the arrow's flight by the knockback an old mod set on it, as 1.20.1 did on a
     * hit: strength times 0.6 along the horizontal motion, less the target's knockback resistance, and 0.1 up.
     */
    private static void emitKnockback(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "legacyKnockback", "(Ljava/lang/Object;L" + LIVING + ";)V");
        Label none = new Label();
        String lVec = "L" + VEC3 + ";";
        // Locals: 0 arrow, 1 target, 2 strength, 3 resistance factor (two slots), 5 push.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARROW_MEMBERS, "knockback", "(Ljava/lang/Object;)I", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitJumpInsn(Opcodes.IFLE, none);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "KNOCKBACK_RESISTANCE", "L" + HOLDER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "getAttributeValue", "(L" + HOLDER + ";)D", false);
        mv.visitInsn(Opcodes.DSUB);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        mv.visitVarInsn(Opcodes.DSTORE, 3);
        // push = motion.multiply(1, 0, 1).normalize().scale(strength * 0.6 * factor)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ENTITY);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getDeltaMovement", "()" + lVec, false);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3, "multiply", "(DDD)" + lVec, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3, "normalize", "()" + lVec, false);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(0.6);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitVarInsn(Opcodes.DLOAD, 3);
        mv.visitInsn(Opcodes.DMUL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3, "scale", "(D)" + lVec, false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VEC3, "lengthSqr", "()D", false);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFLE, none);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitFieldInsn(Opcodes.GETFIELD, VEC3, "x", "D");
        mv.visitLdcInsn(0.1);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitFieldInsn(Opcodes.GETFIELD, VEC3, "z", "D");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "push", "(DDD)V", false);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.RETURN);
        end(mv);
    }

    /** {@code effect.applyEffectTick(entity, amplifier)} through the host's form, which may also want the server level. */
    private static void emitEffectTick(ClassWriter cw, Shape shape) {
        MethodVisitor mv = begin(cw, "effectTick", "(L" + MOB_EFFECT + ";L" + LIVING + ";I)V");
        if (!shape.effectTickBoolean()) {
            mv.visitInsn(Opcodes.RETURN);
            end(mv);
            return;
        }
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (shape.effectTickLevel()) {
            // The level form only runs on the server, as effects only tick there.
            pushServerLevelOrSkip(mv, 1, done);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        String desc = shape.effectTickLevel() ? "(L" + SERVER_LEVEL + ";L" + LIVING + ";I)Z" : "(L" + LIVING + ";I)Z";
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOB_EFFECT, "applyEffectTick", desc, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        end(mv);
    }

    /** {@code effect.applyEffectTick(entity, amplifier)} of 1.20.5, whose answer says whether the effect stays, in the level form. */
    private static void emitEffectTickKeeps(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "effectTickKeeps", "(L" + MOB_EFFECT + ";L" + LIVING + ";I)Z");
        Label client = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        pushServerLevelOrSkip(mv, 1, client);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOB_EFFECT, "applyEffectTick",
                "(L" + SERVER_LEVEL + ";L" + LIVING + ";I)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        // Effects do not tick on a client, so the effect stays.
        mv.visitLabel(client);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        end(mv);
    }

    /** {@code effect.applyInstantenousEffect(source, indirect, target, amplifier, health)} in the host's form. */
    private static void emitEffectInstant(ClassWriter cw, Shape shape) {
        String oldDesc = "(L" + ENTITY + ";L" + ENTITY + ";L" + LIVING + ";ID)V";
        MethodVisitor mv = begin(cw, "effectInstant", "(L" + MOB_EFFECT + ";" + oldDesc.substring(1));
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (shape.effectTickLevel()) pushServerLevelOrSkip(mv, 3, done);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.DLOAD, 5);
        String desc = shape.effectTickLevel() ? "(L" + SERVER_LEVEL + ";" + oldDesc.substring(1) : oldDesc;
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOB_EFFECT, shape.instantName(), desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(done);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        end(mv);
    }

    /** Pushes the server level of the entity in {@code local}, or jumps to {@code skip} on a client. */
    private static void pushServerLevelOrSkip(MethodVisitor mv, int local, Label skip) {
        mv.visitVarInsn(Opcodes.ALOAD, local);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SERVER_LEVEL);
        Label server = new Label();
        mv.visitJumpInsn(Opcodes.IFNE, server);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, skip);
        mv.visitLabel(server);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SERVER_LEVEL);
    }

    /** {@code new AABB(from, to)} as 1.20.1 built it: the two positions are the box's corners. */
    private static void emitBlockBox(ClassWriter cw) {
        String pos = "L" + BLOCK_POS + ";";
        MethodVisitor mv = begin(cw, "blockBox", "(" + pos + pos + ")L" + AABB + ";");
        mv.visitTypeInsn(Opcodes.NEW, AABB);
        mv.visitInsn(Opcodes.DUP);
        for (int corner = 0; corner < 2; corner++) {
            for (String axis : new String[]{"getX", "getY", "getZ"}) {
                mv.visitVarInsn(Opcodes.ALOAD, corner);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/Vec3i", axis, "()I", false);
                mv.visitInsn(Opcodes.I2D);
            }
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, AABB, "<init>", "(DDDDDD)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** {@code stack.getTooltipLines(player, flag)} with the context the player's level gives, or the empty one. */
    private static void emitTooltipLines(ClassWriter cw) {
        String lContext = "L" + TOOLTIP_CONTEXT + ";";
        MethodVisitor mv = begin(cw, "tooltipLines", "(L" + ITEM_STACK + ";" + OLD_TOOLTIP_LINES.substring(1));
        Label noPlayer = new Label();
        Label call = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, noPlayer);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOOLTIP_CONTEXT, "of", "(L" + LEVEL + ";)" + lContext, true);
        mv.visitJumpInsn(Opcodes.GOTO, call);
        mv.visitLabel(noPlayer);
        mv.visitFieldInsn(Opcodes.GETSTATIC, TOOLTIP_CONTEXT, "EMPTY", lContext);
        mv.visitLabel(call);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getTooltipLines",
                "(" + lContext + "L" + PLAYER + ";L" + TOOLTIP_FLAG + ";)Ljava/util/List;", false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** The X, Y and Z position compound of 1.20.1, read and written with a 1.20.5 to 1.21.4 host's getters. */
    private static void emitPositionTags(ClassWriter cw) {
        String pos = "L" + BLOCK_POS + ";";
        String compound = "L" + COMPOUND_TAG + ";";
        MethodVisitor read = begin(cw, "readBlockPos", "(" + compound + ")" + pos);
        read.visitTypeInsn(Opcodes.NEW, BLOCK_POS);
        read.visitInsn(Opcodes.DUP);
        for (String axis : new String[]{"X", "Y", "Z"}) {
            read.visitVarInsn(Opcodes.ALOAD, 0);
            read.visitLdcInsn(axis);
            read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPOUND_TAG, "getInt", "(Ljava/lang/String;)I", false);
        }
        read.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_POS, "<init>", "(III)V", false);
        read.visitInsn(Opcodes.ARETURN);
        end(read);

        MethodVisitor write = begin(cw, "writeBlockPos", "(" + pos + ")" + compound);
        write.visitTypeInsn(Opcodes.NEW, COMPOUND_TAG);
        write.visitInsn(Opcodes.DUP);
        write.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPOUND_TAG, "<init>", "()V", false);
        write.visitVarInsn(Opcodes.ASTORE, 1);
        for (String axis : new String[]{"X", "Y", "Z"}) {
            write.visitVarInsn(Opcodes.ALOAD, 1);
            write.visitLdcInsn(axis);
            write.visitVarInsn(Opcodes.ALOAD, 0);
            write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/core/Vec3i", "get" + axis, "()I", false);
            write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPOUND_TAG, "putInt", "(Ljava/lang/String;I)V", false);
        }
        write.visitVarInsn(Opcodes.ALOAD, 1);
        write.visitInsn(Opcodes.ARETURN);
        end(write);
    }

    /** {@code new LegacyScore(board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective))}. */
    private static void emitPlayerScore(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "playerScore", "(L" + SCOREBOARD + ";" + OLD_PLAYER_SCORE.substring(1));
        mv.visitTypeInsn(Opcodes.NEW, LEGACY_SCORE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SCORE_HOLDER, "forNameOnly", "(Ljava/lang/String;)L" + SCORE_HOLDER + ";", true);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SCOREBOARD, "getOrCreatePlayerScore",
                "(L" + SCORE_HOLDER + ";L" + OBJECTIVE + ";)L" + SCORE_ACCESS + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LEGACY_SCORE, "<init>", "(L" + SCORE_ACCESS + ";)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** {@code stack.setHoverName(name)}: the custom name component, which a null name removes. */
    private static void emitHoverName(ClassWriter cw) {
        String stack = "L" + ITEM_STACK + ";";
        String type = "L" + COMPONENT_TYPE + ";";
        MethodVisitor name = begin(cw, "setHoverName", "(" + stack + "L" + COMPONENT + ";)" + stack);
        Label clear = new Label();
        name.visitVarInsn(Opcodes.ALOAD, 1);
        name.visitJumpInsn(Opcodes.IFNULL, clear);
        name.visitVarInsn(Opcodes.ALOAD, 0);
        name.visitFieldInsn(Opcodes.GETSTATIC, COMPONENTS, "CUSTOM_NAME", type);
        name.visitVarInsn(Opcodes.ALOAD, 1);
        name.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "set", "(" + type + "Ljava/lang/Object;)Ljava/lang/Object;", false);
        name.visitInsn(Opcodes.POP);
        name.visitVarInsn(Opcodes.ALOAD, 0);
        name.visitInsn(Opcodes.ARETURN);
        name.visitLabel(clear);
        name.visitVarInsn(Opcodes.ALOAD, 0);
        name.visitFieldInsn(Opcodes.GETSTATIC, COMPONENTS, "CUSTOM_NAME", type);
        name.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "remove", "(" + type + ")Ljava/lang/Object;", false);
        name.visitInsn(Opcodes.POP);
        name.visitVarInsn(Opcodes.ALOAD, 0);
        name.visitInsn(Opcodes.ARETURN);
        end(name);
    }

    /** The old attribute calls on any effect: the entity they name is remembered, then the host's form runs. */
    private static void emitEffectAttributes(ClassWriter cw) {
        String old = "(L" + MOB_EFFECT + ";L" + LIVING + ";L" + ATTRIBUTE_MAP + ";I)V";
        for (String name : new String[]{"effectAdd", "effectRemove"}) {
            MethodVisitor mv = begin(cw, name, old);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "remember", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            // The amplifier the call names is the one an old removal override is handed.
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "applied", "(Ljava/lang/Object;Ljava/lang/Object;I)V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            if (name.equals("effectAdd")) {
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOB_EFFECT, "addAttributeModifiers", "(L" + ATTRIBUTE_MAP + ";I)V", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOB_EFFECT, "removeAttributeModifiers", "(L" + ATTRIBUTE_MAP + ";)V", false);
            }
            mv.visitInsn(Opcodes.RETURN);
            end(mv);
        }
    }
}
