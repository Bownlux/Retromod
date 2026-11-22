/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/**
 * Bridges members block, entity and item mods built for 1.21.1 call that 1.21.2 to 26.3 reshaped: entity type
 * lookups and loading, item predicates, equipment slots, container openers, boats, crafting remainders, loot tables,
 * custom data, teleports, dyed colors, rule tests and dye items. Each registers only where the host shows the change.
 */
public final class LegacyCommonMembersBridge {

    public static final String HELPER = "com/retromod/generated/LegacyCommonMembers";
    public static final String FLAG = "legacyCommonMembers";
    private static final String MC = "net/minecraft/";
    private static final String ENTITY_TYPE = MC + "world/entity/EntityType";
    private static final String ENTITY = MC + "world/entity/Entity";
    private static final String LEVEL = MC + "world/level/Level";
    private static final String SERVER_LEVEL = MC + "server/level/ServerLevel";
    private static final String TAG = MC + "nbt/CompoundTag";
    private static final String ITEM = MC + "world/item/Item";
    private static final String PREDICATE = MC + "advancements/predicates/ItemPredicate$Builder";
    private static final String OPENERS = MC + "world/level/block/entity/ContainerOpenersCounter";
    private static final String PLAYER = MC + "world/entity/player/Player";
    private static final String LIVING = MC + "world/entity/LivingEntity";
    private static final String POS = "L" + MC + "core/BlockPos;";
    private static final String STATE = "L" + MC + "world/level/block/state/BlockState;";
    static final String BOAT = MC + "world/entity/vehicle/boat/Boat";
    static final String CHEST_BOAT = MC + "world/entity/vehicle/boat/ChestBoat";
    private static final String CRAFTING = MC + "world/item/crafting/CraftingRecipe";
    private static final String BLOCK = MC + "world/level/block/Block";
    private static final String CUSTOM_DATA = MC + "world/item/component/CustomData";
    private static final String DYED = MC + "world/item/component/DyedItemColor";
    static final String RULE_TEST = MC + "world/level/levelgen/structure/templatesystem/RuleTest";
    private static final String RANDOM = "L" + MC + "util/RandomSource;";
    private static final String DYE_ITEM = MC + "world/item/DyeItem";
    private static final String DYE_COLOR = MC + "world/item/DyeColor";
    private static final String KEY = "L" + MC + "resources/ResourceKey;";
    private static final String SET = "Ljava/util/Set;";
    static final String OLD_BOAT = "(L" + ENTITY_TYPE + ";L" + LEVEL + ";)V";
    static final String NEW_BOAT = "(L" + ENTITY_TYPE + ";L" + LEVEL + ";Ljava/util/function/Supplier;)V";
    static final String OLD_RULE = "(" + STATE + RANDOM + ")Z";
    static final String BOAT_DROPS = "com/retromod/shim/common/embedded/LegacyBoatDrops";
    static final String SLOT_DESC = "()L" + MC + "world/entity/EquipmentSlot;";
    // The generated armor bases an old ArmorItem call is moved to; none of them declares getEquipmentSlot.
    static final java.util.Set<String> ARMOR_BASES = java.util.Set.of(MC + "world/item/ArmorItem", "com/retromod/generated/LegacyArmorItem",
            "com/retromod/generated/LegacyDyeableArmorItem", "com/retromod/generated/LegacyAnimalArmorItem",
            "com/retromod/generated/LegacyHorseArmorItem", "com/retromod/generated/LegacyDyeableHorseArmorItem");
    private static final String HOLDER = "java/util/concurrent/atomic/AtomicReference";
    static final String NEW_RULE = "(" + STATE + POS + RANDOM + ")Z";

    /** Which changes the host shows, read once at registration; the class repair consults them. */
    record Shape(boolean entityLookups, boolean predicates, boolean slots, boolean openers, boolean boats,
            boolean remainders, boolean lootTables, boolean customData, boolean teleports, boolean dyed,
            boolean ruleTests, boolean dyeItems) {
        boolean any() {
            return entityLookups || predicates || slots || openers || boats || remainders || lootTables || customData
                    || teleports || dyed || ruleTests || dyeItems;
        }
    }

    private static volatile Shape registered;

    private LegacyCommonMembersBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (t.getSyntheticClasses().containsKey(HELPER)) return;
        Shape shape = shape(host);
        if (!shape.any()) return;
        registered = shape;
        t.registerSyntheticClass(HELPER, generate(shape));
        t.enablePostProcess(FLAG);
        String optional = "Ljava/util/Optional;";
        if (shape.entityLookups()) {
            t.registerMethodRedirect(ENTITY_TYPE, "byString", "(Ljava/lang/String;)" + optional, HELPER, "entityTypeByString",
                    "(Ljava/lang/String;)" + optional);
            t.registerMethodRedirect(ENTITY_TYPE, "create", "(L" + TAG + ";L" + LEVEL + ";)" + optional, HELPER, "loadEntity",
                    "(L" + TAG + ";L" + LEVEL + ";)" + optional);
        }
        if (shape.predicates()) {
            t.registerMethodRedirect(PREDICATE, "of", "(L" + MC + "tags/TagKey;)L" + PREDICATE + ";", HELPER, "itemPredicateOfTag",
                    "(L" + PREDICATE + ";L" + MC + "tags/TagKey;)L" + PREDICATE + ";", true);
            t.registerMethodRedirect(PREDICATE, "of", "([L" + MC + "world/level/ItemLike;)L" + PREDICATE + ";", HELPER,
                    "itemPredicateOfItems", "(L" + PREDICATE + ";[L" + MC + "world/level/ItemLike;)L" + PREDICATE + ";", true);
        }
        if (shape.slots()) {
            t.registerInheritedMethodRedirect(ITEM, "getEquipmentSlot", "()L" + MC + "world/entity/EquipmentSlot;", HELPER,
                    "equipmentSlot", "(L" + ITEM + ";)L" + MC + "world/entity/EquipmentSlot;");
            // Old armor calls name ArmorItem, which the transform moves onto Retromod's generated armor bases; the class
            // repair below sends those calls to the helper too.
        }
        if (shape.boats()) {
            SyntheticEmbedder.registerClassResource(t, BOAT_DROPS, com.retromod.shim.common.embedded.LegacyBoatDrops.class);
        }
        if (shape.openers()) {
            String args = "L" + LEVEL + ";" + POS + STATE;
            t.registerMethodRedirect(OPENERS, "incrementOpeners", "(L" + PLAYER + ";" + args + ")V", HELPER, "incrementOpeners",
                    "(L" + OPENERS + ";L" + PLAYER + ";" + args + ")V", true);
            t.registerMethodRedirect(OPENERS, "decrementOpeners", "(L" + PLAYER + ";" + args + ")V", OPENERS, "decrementOpeners",
                    "(L" + LIVING + ";" + args + ")V");
        }
        if (shape.remainders()) {
            t.registerInheritedMethodRedirect(CRAFTING, "getRemainingItems",
                    "(L" + MC + "world/item/crafting/RecipeInput;)L" + MC + "core/NonNullList;", HELPER, "remainingItems",
                    "(L" + CRAFTING + ";L" + MC + "world/item/crafting/RecipeInput;)L" + MC + "core/NonNullList;");
        }
        if (shape.lootTables()) {
            // getLootTable lives on BlockBehaviour, so a call naming either type, or a mod block, takes the helper.
            for (String owner : new String[] {BLOCK, MC + "world/level/block/state/BlockBehaviour"}) {
                t.registerInheritedMethodRedirect(owner, "getLootTable", "()" + KEY, HELPER, "lootTable",
                        "(L" + MC + "world/level/block/state/BlockBehaviour;)" + KEY);
            }
        }
        if (shape.customData()) {
            t.registerMethodRedirect(CUSTOM_DATA, "getUnsafe", "()L" + TAG + ";", CUSTOM_DATA, "copyTag", "()L" + TAG + ";");
        }
        if (shape.teleports()) {
            t.registerInheritedMethodRedirect(ENTITY, "teleportTo", "(L" + SERVER_LEVEL + ";DDD" + SET + "FF)Z", HELPER, "teleport",
                    "(L" + ENTITY + ";L" + SERVER_LEVEL + ";DDD" + SET + "FF)Z");
            t.registerInheritedMethodRedirect(MC + "server/level/ServerPlayer", "teleportTo", "(L" + SERVER_LEVEL + ";DDDFF)V", HELPER,
                    "teleportPlayer", "(L" + ENTITY + ";L" + SERVER_LEVEL + ";DDDFF)V");
        }
        if (shape.dyed()) {
            t.registerConstructorRedirect(DYED, "(IZ)V", HELPER, "dyedColor", "(IZ)L" + DYED + ";");
        }
        if (shape.ruleTests()) {
            t.registerMethodRedirect(RULE_TEST, "test", OLD_RULE, HELPER, "ruleTest", "(L" + RULE_TEST + ";" + OLD_RULE.substring(1), true);
        }
        if (shape.dyeItems()) {
            t.registerMethodRedirect(DYE_ITEM, "byColor", "(L" + DYE_COLOR + ";)L" + DYE_ITEM + ";", HELPER, "dyeItem",
                    "(L" + DYE_COLOR + ";)L" + DYE_ITEM + ";");
        }
    }

    /** Each change, read from the host; an unreadable class leaves its rule off. */
    static Shape shape(Function<String, ClassNode> host) {
        ClassNode entityType = host.apply(ENTITY_TYPE);
        ClassNode predicate = host.apply(PREDICATE);
        ClassNode item = host.apply(ITEM);
        ClassNode openers = host.apply(OPENERS);
        ClassNode boat = host.apply(BOAT);
        ClassNode crafting = host.apply(CRAFTING);
        ClassNode block = host.apply(MC + "world/level/block/state/BlockBehaviour");
        ClassNode customData = host.apply(CUSTOM_DATA);
        ClassNode entity = host.apply(ENTITY);
        ClassNode dyed = host.apply(DYED);
        ClassNode rule = host.apply(RULE_TEST);
        ClassNode dyeItem = host.apply(DYE_ITEM);
        return new Shape(
                entityType != null && !named(entityType, "byString") && named(entityType, "by"),
                predicate != null && has(predicate, "of", "(L" + MC + "core/HolderGetter;L" + MC + "tags/TagKey;)L" + PREDICATE + ";"),
                item != null && !named(item, "getEquipmentSlot"),
                openers != null && !has(openers, "incrementOpeners", "(L" + PLAYER + ";L" + LEVEL + ";" + POS + STATE + ")V")
                        && has(openers, "incrementOpeners", "(L" + LIVING + ";L" + LEVEL + ";" + POS + STATE + "D)V"),
                boat != null && !has(boat, "<init>", OLD_BOAT) && has(boat, "<init>", NEW_BOAT),
                crafting != null && named(crafting, "getRemainingItems")
                        && !has(crafting, "getRemainingItems", "(L" + MC + "world/item/crafting/RecipeInput;)L" + MC + "core/NonNullList;"),
                block != null && has(block, "getLootTable", "()Ljava/util/Optional;"),
                customData != null && !named(customData, "getUnsafe") && named(customData, "copyTag"),
                entity != null && has(entity, "teleportTo", "(L" + SERVER_LEVEL + ";DDD" + SET + "FFZ)Z")
                        && !has(entity, "teleportTo", "(L" + SERVER_LEVEL + ";DDD" + SET + "FF)Z"),
                dyed != null && !has(dyed, "<init>", "(IZ)V") && has(dyed, "<init>", "(I)V"),
                rule != null && has(rule, "test", NEW_RULE) && !has(rule, "test", OLD_RULE),
                dyeItem != null && !named(dyeItem, "byColor"));
    }

    private static boolean has(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean named(ClassNode node, String name) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    /**
     * Repairs what a call redirect cannot: an old boat's two-argument constructor, now given the oak boat as its drop,
     * and an old rule test's override, now reached through the current test.
     */
    public static byte[] apply(byte[] classBytes) {
        Shape shape = registered;
        if (classBytes == null || shape == null || !(shape.boats() || shape.ruleTests() || shape.slots())) return classBytes;
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        // A rule test that extends another mod's rule test names RuleTest nowhere, only the old test's descriptor.
        if (!text.contains(BOAT) && !text.contains(RULE_TEST) && !text.contains(OLD_RULE) && !text.contains("getEquipmentSlot")) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (SyntheticEmbedder.isRetromodClass(node.name) || node.name.startsWith(MC)) return classBytes;
        int changed = 0;
        for (MethodNode method : node.methods) {
            boolean superCallSeen = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (shape.slots() && insn instanceof MethodInsnNode slot && slot.getOpcode() == INVOKEVIRTUAL
                        && slot.name.equals("getEquipmentSlot") && slot.desc.equals(SLOT_DESC) && ARMOR_BASES.contains(slot.owner)) {
                    // The armor's slot is in its equippable component now; the receiver stays on the stack as the argument.
                    method.instructions.set(slot, new MethodInsnNode(INVOKESTATIC, HELPER, "equipmentSlot", "(L" + ITEM + ";)" + SLOT_DESC.substring(2), false));
                    changed++;
                    continue;
                }
                if (shape.boats() && insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL
                        && call.name.equals("<init>") && call.desc.equals(OLD_BOAT)
                        && (call.owner.equals(BOAT) || call.owner.equals(CHEST_BOAT))) {
                    // The boat's drop was a method an old subclass overrode; the constructor now takes it up front.
                    boolean superCall = method.name.equals("<init>") && call.owner.equals(node.superName) && !superCallSeen;
                    superCallSeen |= superCall;
                    if (superCall) {
                        passOwnDrop(method, call);
                    } else {
                        String item = call.owner.equals(BOAT) ? "oakBoat" : "oakChestBoat";
                        method.instructions.insertBefore(call, new MethodInsnNode(INVOKESTATIC, HELPER, item, "()Ljava/util/function/Supplier;", false));
                    }
                    call.desc = NEW_BOAT;
                    changed++;
                }
            }
        }
        // An old rule test override is reached through the current test, which now names the position too; a mod's rule
        // test may extend another mod rule test.
        if (shape.ruleTests() && node.superName != null && (RULE_TEST.equals(node.superName)
                || (!node.superName.startsWith(MC) && RetromodTransformer.currentJarClassInheritsFrom(node.superName, RULE_TEST)))
                && node.methods.stream().anyMatch(m -> m.name.equals("test") && m.desc.equals(OLD_RULE) && (m.access & ACC_ABSTRACT) == 0)
                && node.methods.stream().noneMatch(m -> m.name.equals("test") && m.desc.equals(NEW_RULE))) {
            MethodNode bridge = new MethodNode(ACC_PUBLIC | ACC_SYNTHETIC, "test", NEW_RULE, null, null);
            bridge.visitCode();
            bridge.visitVarInsn(ALOAD, 0);
            bridge.visitVarInsn(ALOAD, 1);
            bridge.visitVarInsn(ALOAD, 3);
            bridge.visitMethodInsn(INVOKEVIRTUAL, node.name, "test", OLD_RULE, false);
            bridge.visitInsn(IRETURN);
            bridge.visitMaxs(0, 0);
            bridge.visitEnd();
            node.methods.add(bridge);
            changed++;
        }
        if (changed == 0) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * A subclass's super call gets a supplier that asks the boat's own drop item, whose override the host's final
     * getDropItem renamed. The boat does not exist until the super call returns, so a holder is filled right after it.
     */
    private static void passOwnDrop(MethodNode method, MethodInsnNode call) {
        int holder = method.maxLocals++;
        org.objectweb.asm.tree.InsnList before = new org.objectweb.asm.tree.InsnList();
        before.add(new org.objectweb.asm.tree.TypeInsnNode(NEW, HOLDER));
        before.add(new org.objectweb.asm.tree.InsnNode(DUP));
        before.add(new MethodInsnNode(INVOKESPECIAL, HOLDER, "<init>", "()V", false));
        before.add(new org.objectweb.asm.tree.VarInsnNode(ASTORE, holder));
        before.add(new org.objectweb.asm.tree.VarInsnNode(ALOAD, holder));
        before.add(new org.objectweb.asm.tree.LdcInsnNode(call.owner.equals(BOAT) ? "OAK_BOAT" : "OAK_CHEST_BOAT"));
        before.add(new MethodInsnNode(INVOKESTATIC, BOAT_DROPS, "of", "(L" + HOLDER + ";Ljava/lang/String;)Ljava/util/function/Supplier;", false));
        method.instructions.insertBefore(call, before);
        org.objectweb.asm.tree.InsnList after = new org.objectweb.asm.tree.InsnList();
        after.add(new org.objectweb.asm.tree.VarInsnNode(ALOAD, holder));
        after.add(new org.objectweb.asm.tree.VarInsnNode(ALOAD, 0));
        after.add(new MethodInsnNode(INVOKEVIRTUAL, HOLDER, "set", "(Ljava/lang/Object;)V", false));
        method.instructions.insert(call, after);
    }

    static byte[] generate(Shape shape) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        String registries = MC + "core/registries/BuiltInRegistries";
        String registry = MC + "core/Registry";
        String identifier = MC + "resources/Identifier";
        if (shape.entityLookups()) {
            // byString(name): the entity type registry's entry for the parsed id, if any.
            MethodVisitor m = start(cw, "entityTypeByString", "(Ljava/lang/String;)Ljava/util/Optional;");
            Label bad = new Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKESTATIC, identifier, "tryParse", "(Ljava/lang/String;)L" + identifier + ";", false);
            m.visitVarInsn(ASTORE, 1);
            m.visitVarInsn(ALOAD, 1);
            m.visitJumpInsn(IFNULL, bad);
            m.visitFieldInsn(GETSTATIC, registries, "ENTITY_TYPE", "L" + MC + "core/DefaultedRegistry;");
            m.visitVarInsn(ALOAD, 1);
            m.visitMethodInsn(INVOKEINTERFACE, registry, "getOptional", "(L" + identifier + ";)Ljava/util/Optional;", true);
            m.visitInsn(ARETURN);
            m.visitLabel(bad);
            m.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
            m.visitInsn(ARETURN);
            end(m);
            // create(tag, level): the tag read as the level's value input, loaded as a saved entity.
            MethodVisitor load = start(cw, "loadEntity", "(L" + TAG + ";L" + LEVEL + ";)Ljava/util/Optional;");
            String input = MC + "world/level/storage/ValueInput";
            load.visitFieldInsn(GETSTATIC, MC + "util/ProblemReporter", "DISCARDING", "L" + MC + "util/ProblemReporter;");
            load.visitVarInsn(ALOAD, 1);
            load.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "registryAccess", "()L" + MC + "core/RegistryAccess;", false);
            load.visitVarInsn(ALOAD, 0);
            load.visitMethodInsn(INVOKESTATIC, MC + "world/level/storage/TagValueInput", "create",
                    "(L" + MC + "util/ProblemReporter;L" + MC + "core/HolderLookup$Provider;L" + TAG + ";)L" + input + ";", false);
            load.visitVarInsn(ASTORE, 2);
            // The saved id names the type; data without a known type loads nothing, as before.
            Label unknown = new Label();
            load.visitVarInsn(ALOAD, 2);
            load.visitMethodInsn(INVOKESTATIC, ENTITY_TYPE, "by", "(L" + input + ";)Ljava/util/Optional;", false);
            load.visitInsn(DUP);
            load.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isEmpty", "()Z", false);
            load.visitJumpInsn(IFNE, unknown);
            load.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
            load.visitTypeInsn(CHECKCAST, ENTITY_TYPE);
            load.visitVarInsn(ALOAD, 2);
            load.visitVarInsn(ALOAD, 1);
            load.visitFieldInsn(GETSTATIC, MC + "world/entity/EntitySpawnReason", "LOAD", "L" + MC + "world/entity/EntitySpawnReason;");
            load.visitMethodInsn(INVOKESTATIC, ENTITY_TYPE, "create",
                    "(L" + ENTITY_TYPE + ";L" + input + ";L" + LEVEL + ";L" + MC + "world/entity/EntitySpawnReason;)Ljava/util/Optional;", false);
            load.visitInsn(ARETURN);
            load.visitLabel(unknown);
            load.visitInsn(ARETURN);
            end(load);
        }
        if (shape.predicates()) {
            // of(tag) and of(items) now name the item lookup; the built-in item registry is it.
            String getter = "L" + MC + "core/HolderGetter;";
            MethodVisitor tag = start(cw, "itemPredicateOfTag", "(L" + PREDICATE + ";L" + MC + "tags/TagKey;)L" + PREDICATE + ";");
            tag.visitVarInsn(ALOAD, 0);
            tag.visitFieldInsn(GETSTATIC, registries, "ITEM", "L" + MC + "core/DefaultedRegistry;");
            tag.visitVarInsn(ALOAD, 1);
            tag.visitMethodInsn(INVOKEVIRTUAL, PREDICATE, "of", "(" + getter + "L" + MC + "tags/TagKey;)L" + PREDICATE + ";", false);
            tag.visitInsn(ARETURN);
            end(tag);
            MethodVisitor items = start(cw, "itemPredicateOfItems", "(L" + PREDICATE + ";[L" + MC + "world/level/ItemLike;)L" + PREDICATE + ";");
            items.visitVarInsn(ALOAD, 0);
            items.visitFieldInsn(GETSTATIC, registries, "ITEM", "L" + MC + "core/DefaultedRegistry;");
            items.visitVarInsn(ALOAD, 1);
            items.visitMethodInsn(INVOKEVIRTUAL, PREDICATE, "of", "(" + getter + "[L" + MC + "world/level/ItemLike;)L" + PREDICATE + ";", false);
            items.visitInsn(ARETURN);
            end(items);
        }
        if (shape.slots()) {
            // The item's equippable component names its slot; an item without one goes in the main hand.
            String slot = MC + "world/entity/EquipmentSlot";
            String equippable = MC + "world/item/equipment/Equippable";
            MethodVisitor m = start(cw, "equipmentSlot", "(L" + ITEM + ";)L" + slot + ";");
            Label none = new Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, ITEM, "components", "()L" + MC + "core/component/DataComponentMap;", false);
            m.visitFieldInsn(GETSTATIC, MC + "core/component/DataComponents", "EQUIPPABLE", "L" + MC + "core/component/DataComponentType;");
            m.visitMethodInsn(INVOKEINTERFACE, MC + "core/component/DataComponentMap", "get",
                    "(L" + MC + "core/component/DataComponentType;)Ljava/lang/Object;", true);
            m.visitInsn(DUP);
            m.visitJumpInsn(IFNULL, none);
            m.visitTypeInsn(CHECKCAST, equippable);
            m.visitMethodInsn(INVOKEVIRTUAL, equippable, "slot", "()L" + slot + ";", false);
            m.visitInsn(ARETURN);
            m.visitLabel(none);
            m.visitInsn(POP);
            m.visitFieldInsn(GETSTATIC, slot, "MAINHAND", "L" + slot + ";");
            m.visitInsn(ARETURN);
            end(m);
        }
        if (shape.openers()) {
            // The counter now takes how far the opener can reach; a player's block reach is what it measures.
            String args = "L" + LEVEL + ";" + POS + STATE;
            MethodVisitor m = start(cw, "incrementOpeners", "(L" + OPENERS + ";L" + PLAYER + ";" + args + ")V");
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitVarInsn(ALOAD, 2);
            m.visitVarInsn(ALOAD, 3);
            m.visitVarInsn(ALOAD, 4);
            m.visitVarInsn(ALOAD, 1);
            m.visitFieldInsn(GETSTATIC, MC + "world/entity/ai/attributes/Attributes", "BLOCK_INTERACTION_RANGE", "L" + MC + "core/Holder;");
            m.visitMethodInsn(INVOKEVIRTUAL, LIVING, "getAttributeValue", "(L" + MC + "core/Holder;)D", false);
            m.visitMethodInsn(INVOKEVIRTUAL, OPENERS, "incrementOpeners", "(L" + LIVING + ";" + args + "D)V", false);
            m.visitInsn(RETURN);
            end(m);
        }
        if (shape.boats()) {
            for (String[] boat : new String[][] {{"oakBoat", "OAK_BOAT"}, {"oakChestBoat", "OAK_CHEST_BOAT"}}) {
                MethodVisitor m = start(cw, boat[0], "()Ljava/util/function/Supplier;");
                m.visitFieldInsn(GETSTATIC, MC + "world/item/Items", boat[1], "L" + ITEM + ";");
                m.visitMethodInsn(INVOKESTATIC, "com/google/common/base/Suppliers", "ofInstance",
                        "(Ljava/lang/Object;)Lcom/google/common/base/Supplier;", false);
                m.visitInsn(ARETURN);
                end(m);
            }
        }
        if (shape.remainders()) {
            String input = MC + "world/item/crafting/CraftingInput";
            MethodVisitor m = start(cw, "remainingItems", "(L" + CRAFTING + ";L" + MC + "world/item/crafting/RecipeInput;)L" + MC + "core/NonNullList;");
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitTypeInsn(CHECKCAST, input);
            m.visitMethodInsn(INVOKEINTERFACE, CRAFTING, "getRemainingItems", "(L" + input + ";)L" + MC + "core/NonNullList;", true);
            m.visitInsn(ARETURN);
            end(m);
        }
        if (shape.lootTables()) {
            // A block without a loot table answered the empty table's key, which callers looked up as usual.
            String behaviour = MC + "world/level/block/state/BlockBehaviour";
            MethodVisitor m = start(cw, "lootTable", "(L" + behaviour + ";)" + KEY);
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, behaviour, "getLootTable", "()Ljava/util/Optional;", false);
            m.visitFieldInsn(GETSTATIC, MC + "core/registries/Registries", "LOOT_TABLE", KEY);
            m.visitLdcInsn("empty");
            m.visitMethodInsn(INVOKESTATIC, identifier, "withDefaultNamespace", "(Ljava/lang/String;)L" + identifier + ";", false);
            m.visitMethodInsn(INVOKESTATIC, MC + "resources/ResourceKey", "create", "(" + KEY + "L" + identifier + ";)" + KEY, false);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            m.visitTypeInsn(CHECKCAST, MC + "resources/ResourceKey");
            m.visitInsn(ARETURN);
            end(m);
        }
        if (shape.teleports()) {
            // The current teleport also says whether to move the camera along, which the old one always did.
            String args = "L" + SERVER_LEVEL + ";DDD" + SET + "FF";
            MethodVisitor m = start(cw, "teleport", "(L" + ENTITY + ";" + args + ")Z");
            loadTeleport(m, false);
            m.visitInsn(IRETURN);
            end(m);
            MethodVisitor player = start(cw, "teleportPlayer", "(L" + ENTITY + ";L" + SERVER_LEVEL + ";DDDFF)V");
            loadTeleport(player, true);
            player.visitInsn(POP);
            player.visitInsn(RETURN);
            end(player);
        }
        if (shape.dyed()) {
            // Whether the color shows in the tooltip moved to the tooltip display component.
            MethodVisitor m = start(cw, "dyedColor", "(IZ)L" + DYED + ";");
            m.visitTypeInsn(NEW, DYED);
            m.visitInsn(DUP);
            m.visitVarInsn(ILOAD, 0);
            m.visitMethodInsn(INVOKESPECIAL, DYED, "<init>", "(I)V", false);
            m.visitInsn(ARETURN);
            end(m);
        }
        if (shape.ruleTests()) {
            // An old caller has no position; the origin stands in, which position-free rule tests ignore.
            MethodVisitor m = start(cw, "ruleTest", "(L" + RULE_TEST + ";" + OLD_RULE.substring(1));
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitFieldInsn(GETSTATIC, MC + "core/BlockPos", "ZERO", POS);
            m.visitVarInsn(ALOAD, 2);
            m.visitMethodInsn(INVOKEVIRTUAL, RULE_TEST, "test", NEW_RULE, false);
            m.visitInsn(IRETURN);
            end(m);
        }
        if (shape.dyeItems()) {
            // The dye item of a color is the registered "<color>_dye" item.
            MethodVisitor m = start(cw, "dyeItem", "(L" + DYE_COLOR + ";)L" + DYE_ITEM + ";");
            Label notDye = new Label();
            m.visitFieldInsn(GETSTATIC, registries, "ITEM", "L" + MC + "core/DefaultedRegistry;");
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, DYE_COLOR, "getName", "()Ljava/lang/String;", false);
            m.visitLdcInsn("_dye");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
            m.visitMethodInsn(INVOKESTATIC, identifier, "withDefaultNamespace", "(Ljava/lang/String;)L" + identifier + ";", false);
            m.visitMethodInsn(INVOKEINTERFACE, registry, "getValue", "(L" + identifier + ";)Ljava/lang/Object;", true);
            m.visitInsn(DUP);
            m.visitTypeInsn(INSTANCEOF, DYE_ITEM);
            m.visitJumpInsn(IFEQ, notDye);
            m.visitTypeInsn(CHECKCAST, DYE_ITEM);
            m.visitInsn(ARETURN);
            m.visitLabel(notDye);
            m.visitInsn(POP);
            m.visitInsn(ACONST_NULL);
            m.visitInsn(ARETURN);
            end(m);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes {@code entity.teleportTo(level, x, y, z, relatives, yRot, xRot, true)}; the player form has no relatives. */
    private static void loadTeleport(MethodVisitor m, boolean player) {
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(DLOAD, 2);
        m.visitVarInsn(DLOAD, 4);
        m.visitVarInsn(DLOAD, 6);
        int next = 8;
        if (player) {
            m.visitMethodInsn(INVOKESTATIC, "java/util/Set", "of", "()Ljava/util/Set;", true);
        } else {
            m.visitVarInsn(ALOAD, next++);
        }
        m.visitVarInsn(FLOAD, next);
        m.visitVarInsn(FLOAD, next + 1);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "teleportTo", "(L" + SERVER_LEVEL + ";DDD" + SET + "FFZ)Z", false);
    }

    private static MethodVisitor start(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        return m;
    }

    private static void end(MethodVisitor m) {
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
