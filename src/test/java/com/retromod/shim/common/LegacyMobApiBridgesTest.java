/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A pre-1.20.5 mob, as MCreator writes one, against the members later hosts removed: its tempt
 * goal, its food check, its offspring, its spawn finalizer, and the arm swing when it is milked.
 * Found on Cake Cow (Forge 1.20.1) on NeoForge 26.1.2 and 26.3, which crashed as soon as the cow
 * was spawned.
 */
class LegacyMobApiBridgesTest {

    private static final String MOB = "net/minecraft/world/entity/Mob";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String HAND = "net/minecraft/world/InteractionHand";
    private static final String TEMPT = Legacy1205MemberBridge.TEMPT_GOAL;
    private static final String INGREDIENT = Legacy1212MemberBridge.INGREDIENT;
    private static final String ITEM_STACK = Legacy1212MemberBridge.ITEM_STACK;
    private static final String MOD_MOB = "test/mod/CakeMob";
    private static final List<String> BOTH_SWING_FORMS =
            List.of(LegacySwingBridge.SWING_HAND, LegacySwingBridge.SWING_HAND_SEND);

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new TemptGoal(mob, speed, Ingredient, scare) calls the Predicate constructor")
    void temptGoalWithIngredientCallsThePredicateConstructor() {
        // As on a 26.x host, which registers the widening alongside the member redirects.
        Legacy1205MemberBridge.registerRedirects(transformer, Legacy1205MemberBridge.MEMBERS, List.of(), false,
                Legacy1205MemberBridge.HostShape.AT_26_3);
        Legacy1205MemberBridge.registerTemptGoalWidening(transformer);

        List<String> calls = calls(transformMob(), "registerGoals");

        assertTrue(calls.contains(TEMPT + ".<init>" + Legacy1205MemberBridge.TEMPT_WITH_PREDICATE), calls.toString());
        assertFalse(calls.contains(TEMPT + ".<init>" + Legacy1205MemberBridge.TEMPT_WITH_INGREDIENT), calls.toString());
    }

    @Test
    @DisplayName("Ingredient.of(ItemStack...) builds the ingredient from each stack's item")
    void ingredientOfStacksUsesTheirItems() throws Exception {
        Legacy1212MemberBridge.registerRedirects(transformer, false, true);
        assertTrue(calls(transformMob(), "isFood").contains(
                Legacy1212MemberBridge.HELPER + ".of" + Legacy1212MemberBridge.OF_STACKS));

        HostStubs stubs = new HostStubs();
        stubs.add(HostStubs.iface("net/minecraft/world/level/ItemLike"));
        stubs.add(HostStubs.plainClass("net/minecraft/world/item/Item", "java/lang/Object", "net/minecraft/world/level/ItemLike"));
        stubs.add(itemStack());
        stubs.add(recordingIngredient());
        stubs.add(Legacy1212MemberBridge.HELPER, Legacy1212MemberBridge.generateHelper(false, true));

        Class<?> itemType = stubs.load("net/minecraft/world/item/Item");
        Object wheat = itemType.getConstructor().newInstance();
        Object carrot = itemType.getConstructor().newInstance();
        Class<?> stackType = stubs.load(ITEM_STACK);
        Object stacks = java.lang.reflect.Array.newInstance(stackType, 2);
        java.lang.reflect.Array.set(stacks, 0, stackType.getConstructor(itemType).newInstance(wheat));
        java.lang.reflect.Array.set(stacks, 1, stackType.getConstructor(itemType).newInstance(carrot));

        Object ingredient = stubs.method(Legacy1212MemberBridge.HELPER, "of").invoke(null, stacks);
        Object[] items = (Object[]) ingredient.getClass().getField("items").get(ingredient);
        assertArrayEquals(new Object[] {wheat, carrot}, items, "each stack contributes its item, in order");
    }

    @Test
    @DisplayName("EntityType.create(Level) for offspring passes a spawn reason")
    void entityTypeCreateGainsASpawnReason() {
        Legacy1212MemberBridge.registerRedirects(transformer, true, false);

        assertTrue(calls(transformMob(), "getBreedOffspring").contains(
                Legacy1212MemberBridge.HELPER + ".create" + Legacy1212MemberBridge.CREATE_HELPER));
        MethodNode create = helper(Legacy1212MemberBridge.generateHelper(true, false), "create");
        assertTrue(calls(create).contains(Legacy1212MemberBridge.ENTITY_TYPE + ".create" + Legacy1212MemberBridge.CREATE_NEW));
    }

    @Test
    @DisplayName("the mob's own finalizeSpawn call drops the CompoundTag 1.20.5 removed")
    void finalizeSpawnDropsTheTag() throws Exception {
        Legacy1205MemberBridge.Member member =
                Legacy1205MemberBridge.finalizeSpawn(Legacy1205MemberBridge.ENTITY_SPAWN_REASON);
        Legacy1205MemberBridge.registerRedirects(transformer, List.of(member), List.of(), false,
                Legacy1205MemberBridge.HostShape.AT_26_3);

        assertTrue(calls(transformMob(), "getBreedOffspring").contains(
                Legacy1205MemberBridge.HELPER + ".finalizeSpawn" + member.helperDesc()),
                "the call names the mod's own class, so the proven hierarchy must carry it");

        HostStubs stubs = new HostStubs();
        stubs.add(recordingMob());
        stubs.add(Legacy1205MemberBridge.HELPER,
                Legacy1205MemberBridge.generateHelper(List.of(member), Legacy1205MemberBridge.HostShape.AT_26_3));
        Object mob = stubs.load(MOB).getConstructor().newInstance();
        stubs.method(Legacy1205MemberBridge.HELPER, "finalizeSpawn")
                .invoke(null, mob, null, null, null, null, null);
        assertEquals(4, ((Object[]) mob.getClass().getField("call").get(mob)).length,
                "the host's four-argument finalizeSpawn ran");
    }

    @Test
    @DisplayName("swing(hand, send) plays the default swing animation on 26.3")
    void swingUsesTheDefaultAnimation() throws Exception {
        LegacySwingBridge.registerRedirects(transformer, BOTH_SWING_FORMS);
        List<String> calls = calls(transformMob(), "milk");
        assertTrue(calls.contains(LegacySwingBridge.HELPER + ".swing(L" + LIVING + ";L" + HAND + ";Z)V"), calls.toString());
        assertTrue(calls.contains(LegacySwingBridge.HELPER + ".swing(L" + LIVING + ";L" + HAND + ";)V"), calls.toString());

        HostStubs stubs = new HostStubs();
        stubs.add(HostStubs.plainClass(HAND, "java/lang/Object"));
        stubs.add(HostStubs.singleton(LegacySwingBridge.ANIMATION));
        stubs.add(recordingLiving());
        stubs.add(LegacySwingBridge.HELPER, LegacySwingBridge.generateHelper(BOTH_SWING_FORMS));
        Object living = stubs.load(LIVING).getConstructor().newInstance();
        Object hand = stubs.load(HAND).getConstructor().newInstance();
        Object animationDefault = stubs.load(LegacySwingBridge.ANIMATION).getField("DEFAULT").get(null);

        stubs.load(LegacySwingBridge.HELPER).getMethod("swing", stubs.load(LIVING), stubs.load(HAND), boolean.class)
                .invoke(null, living, hand, true);
        assertArrayEquals(new Object[] {hand, animationDefault, true},
                (Object[]) living.getClass().getField("call").get(living));

        stubs.load(LegacySwingBridge.HELPER).getMethod("swing", stubs.load(LIVING), stubs.load(HAND))
                .invoke(null, living, hand);
        assertEquals(false, ((Object[]) living.getClass().getField("call").get(living))[2],
                "the one-argument swing never sent the swing to the swinging player");
    }

    @Test
    @DisplayName("the 1.20.5 helper reads the spawn and break hooks the way 26.3 spells them")
    void memberHelperFollowsTheNewestHost() {
        byte[] newest = Legacy1205MemberBridge.generateHelper(Legacy1205MemberBridge.MEMBERS,
                Legacy1205MemberBridge.HostShape.AT_26_3);
        List<String> spawn = calls(helper(newest, "getXSpawn"));
        assertTrue(spawn.contains(Legacy1205MemberBridge.LEVEL_DATA + ".getRespawnData()L"
                + Legacy1205MemberBridge.RESPAWN_DATA + ";"), spawn.toString());
        assertFalse(spawn.toString().contains("getSpawnPos"), "26.3 has no getSpawnPos");

        List<String> breakEvent = new ArrayList<>();
        for (MethodNode method : node(newest).methods) {
            if (method.name.equals("broadcastBreakEvent")) breakEvent.addAll(calls(method));
        }
        String slot = "Lnet/minecraft/world/entity/EquipmentSlot;";
        assertTrue(breakEvent.contains(HAND + ".asEquipmentSlot()" + slot), breakEvent.toString());
        assertTrue(breakEvent.contains(LIVING + ".onEquippedItemBroken(L" + ITEM_STACK + ";" + slot + ")V"),
                breakEvent.toString());
        assertFalse(breakEvent.toString().contains("getSlotForHand"), "26.3 has no getSlotForHand");

        List<String> oldSpawn = calls(helper(Legacy1205MemberBridge.generateHelper(Legacy1205MemberBridge.MEMBERS,
                Legacy1205MemberBridge.HostShape.AT_1_20_5), "getXSpawn"));
        assertTrue(oldSpawn.toString().contains("getSpawnPos"), "a 1.20.5 host still reads getSpawnPos");
    }

    @Test
    @DisplayName("every generated helper passes the stack verifier")
    void generatedHelpersAreStructurallyValid() throws Exception {
        List<Legacy1205MemberBridge.Member> members = new ArrayList<>(Legacy1205MemberBridge.MEMBERS);
        members.add(Legacy1205MemberBridge.finalizeSpawn(Legacy1205MemberBridge.ENTITY_SPAWN_REASON));
        verify(Legacy1205MemberBridge.generateHelper(members, Legacy1205MemberBridge.HostShape.AT_26_3));
        verify(Legacy1205MemberBridge.generateHelper(members, Legacy1205MemberBridge.HostShape.AT_1_20_5));
        verify(Legacy1212MemberBridge.generateHelper(true, true));
        verify(LegacySwingBridge.generateHelper(BOTH_SWING_FORMS));
        verify(LegacyTemptRangeBridge.generateHelper());
        verify(LegacyTemptRangeBridge.generateFabricHelper());
        verify(LegacyTemptRangeBridge.generateArchitecturyHelper());
    }

    // --- the mod under test -------------------------------------------------------------------

    /** The calls Cake Cow's entity and its milking procedure make, in their 1.20.1 shapes. */
    private byte[] transformMob() {
        byte[] mob = cakeMob();
        try (var scope = transformer.pushJarClassBytesProvider(Map.of(MOD_MOB, mob)::get)) {
            return transformer.transformClass(mob, MOD_MOB + ".class");
        }
    }

    private static byte[] cakeMob() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD_MOB, null, MOB, null);

        MethodVisitor goals = cw.visitMethod(ACC_PUBLIC, "registerGoals", "()V", null, null);
        goals.visitCode();
        goals.visitTypeInsn(NEW, TEMPT);
        goals.visitInsn(DUP);
        goals.visitInsn(ACONST_NULL);
        goals.visitInsn(DCONST_1);
        goals.visitInsn(ACONST_NULL);
        goals.visitTypeInsn(CHECKCAST, INGREDIENT);
        goals.visitInsn(ICONST_0);
        goals.visitMethodInsn(INVOKESPECIAL, TEMPT, "<init>", Legacy1205MemberBridge.TEMPT_WITH_INGREDIENT, false);
        goals.visitInsn(POP);
        goals.visitInsn(RETURN);
        goals.visitMaxs(0, 0);
        goals.visitEnd();

        MethodVisitor food = cw.visitMethod(ACC_PUBLIC, "isFood", "(L" + ITEM_STACK + ";)Z", null, null);
        food.visitCode();
        food.visitInsn(ICONST_1);
        food.visitTypeInsn(ANEWARRAY, ITEM_STACK);
        food.visitMethodInsn(INVOKESTATIC, INGREDIENT, "of", Legacy1212MemberBridge.OF_STACKS, false);
        food.visitVarInsn(ALOAD, 1);
        food.visitMethodInsn(INVOKEVIRTUAL, INGREDIENT, "test", "(L" + ITEM_STACK + ";)Z", false);
        food.visitInsn(IRETURN);
        food.visitMaxs(0, 0);
        food.visitEnd();

        MethodVisitor offspring = cw.visitMethod(ACC_PUBLIC, "getBreedOffspring",
                "(L" + Legacy1212MemberBridge.ENTITY_TYPE + ";L" + Legacy1212MemberBridge.LEVEL + ";)Ljava/lang/Object;",
                null, null);
        offspring.visitCode();
        offspring.visitVarInsn(ALOAD, 1);
        offspring.visitVarInsn(ALOAD, 2);
        offspring.visitMethodInsn(INVOKEVIRTUAL, Legacy1212MemberBridge.ENTITY_TYPE, "create",
                Legacy1212MemberBridge.CREATE_OLD, false);
        offspring.visitInsn(POP);
        offspring.visitVarInsn(ALOAD, 0);
        for (int i = 0; i < 5; i++) offspring.visitInsn(ACONST_NULL);
        offspring.visitMethodInsn(INVOKEVIRTUAL, MOD_MOB, "finalizeSpawn",
                Legacy1205MemberBridge.finalizeSpawn(Legacy1205MemberBridge.ENTITY_SPAWN_REASON).desc(), false);
        offspring.visitInsn(ARETURN);
        offspring.visitMaxs(0, 0);
        offspring.visitEnd();

        MethodVisitor milk = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "milk", "(L" + LIVING + ";L" + HAND + ";)V",
                null, null);
        milk.visitCode();
        milk.visitVarInsn(ALOAD, 0);
        milk.visitVarInsn(ALOAD, 1);
        milk.visitInsn(ICONST_1);
        milk.visitMethodInsn(INVOKEVIRTUAL, LIVING, "swing", "(L" + HAND + ";Z)V", false);
        milk.visitVarInsn(ALOAD, 0);
        milk.visitVarInsn(ALOAD, 1);
        milk.visitMethodInsn(INVOKEVIRTUAL, LIVING, "swing", "(L" + HAND + ";)V", false);
        milk.visitInsn(RETURN);
        milk.visitMaxs(0, 0);
        milk.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // --- stand-ins for the host classes the helpers call ---------------------------------------

    /** {@code ItemStack(Item)} that hands its item back from {@code getItem()}. */
    private static byte[] itemStack() {
        String item = "Lnet/minecraft/world/item/Item;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ITEM_STACK, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "item", item, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + item + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, ITEM_STACK, "item", item);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getItem", "()" + item, null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, ITEM_STACK, "item", item);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code Ingredient.of(ItemLike...)} that keeps the array it was given in {@code items}. */
    private static byte[] recordingIngredient() {
        String items = "[Lnet/minecraft/world/level/ItemLike;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, INGREDIENT, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "items", items, null, null).visitEnd();
        HostStubs.defaultConstructor(cw, "java/lang/Object");
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_VARARGS, "of",
                Legacy1212MemberBridge.OF_ITEMS, null, null);
        of.visitCode();
        of.visitTypeInsn(NEW, INGREDIENT);
        of.visitInsn(DUP);
        of.visitMethodInsn(INVOKESPECIAL, INGREDIENT, "<init>", "()V", false);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitFieldInsn(PUTFIELD, INGREDIENT, "items", items);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code Mob} whose four-argument {@code finalizeSpawn} records its arguments in {@code call}. */
    private static byte[] recordingMob() {
        String desc = Legacy1205MemberBridge.finalizeSpawn(Legacy1205MemberBridge.ENTITY_SPAWN_REASON).desc()
                .replace("Lnet/minecraft/nbt/CompoundTag;)", ")");
        return HostStubs.recorder(MOB, "finalizeSpawn", desc);
    }

    /** {@code LivingEntity} whose 26.3 {@code swing} records its arguments in {@code call}. */
    private static byte[] recordingLiving() {
        return HostStubs.recorder(LIVING, "swing", LegacySwingBridge.SWING_ANIMATED);
    }

    // --- bytecode inspection -------------------------------------------------------------------

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static MethodNode helper(byte[] bytes, String name) {
        return node(bytes).methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        return calls(helper(bytes, methodName));
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
