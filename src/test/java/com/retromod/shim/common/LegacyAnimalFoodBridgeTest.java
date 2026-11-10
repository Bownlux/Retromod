/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Farmer's Delight reads and replaces the animal food fields that 1.20.5 made item tags and 26.3 made a component. */
class LegacyAnimalFoodBridgeTest {

    private static final String MOD_CLASS = "test/food/CommonSetup";
    private static final String ENTITY = "net/minecraft/world/entity/";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String HELPER = LegacyAnimalFoodBridge.HELPER;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 26.2 the old reads and writes, by SRG id or moved owner, reach the helper and FOOD_POINTS stays a field")
    void readsAndWritesReachTheHelper() {
        LegacyAnimalFoodBridge.register(transformer, host262(), "26.2");

        List<AbstractInsnNode> code = code(transform(registerItemSetAdditions()));

        assertCall(code, "chickenFood", LegacyAnimalFoodBridge.GETTER_DESC);
        assertCast(code, "chickenFood", "net/minecraft/world/item/crafting/Ingredient");
        assertCall(code, "setChickenFood", LegacyAnimalFoodBridge.SETTER_DESC);
        assertCall(code, "parrotFood", LegacyAnimalFoodBridge.GETTER_DESC);
        assertCast(code, "parrotFood", "java/util/Set");
        assertCall(code, "villagerWantedItems", LegacyAnimalFoodBridge.GETTER_DESC);
        assertCall(code, "setVillagerWantedItems", LegacyAnimalFoodBridge.SETTER_DESC);
        assertEquals(2, fieldAccesses(code, "FOOD_POINTS"), "26.2 still has Villager.FOOD_POINTS, which the access transformer opens");
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper travels with the mod");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyAnimalFoodBridge.ITEM_SET));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyAnimalFoodBridge.PACK_SOURCE));
    }

    @Test
    @DisplayName("on 26.3, where FOOD_POINTS became the villager_food component, its read and write reach the helper too")
    void newestHostBridgesVillagerFood() {
        LegacyAnimalFoodBridge.register(transformer, host263(), "26.3");

        List<AbstractInsnNode> code = code(transform(registerItemSetAdditions()));

        assertCall(code, "villagerFoodPoints", LegacyAnimalFoodBridge.GETTER_DESC);
        assertCast(code, "villagerFoodPoints", "java/util/Map");
        assertCall(code, "setVillagerFoodPoints", LegacyAnimalFoodBridge.SETTER_DESC);
        assertEquals(0, fieldAccesses(code, "FOOD_POINTS"));
    }

    @Test
    @DisplayName("a host that still declares the fields keeps every access as it was")
    void hostWithTheFieldsIsLeftAlone() {
        Map<String, ClassNode> classes = new HashMap<>();
        classes.put(ENTITY + "animal/Chicken", owner(ENTITY + "animal/Chicken", "FOOD_ITEMS"));
        classes.put(ENTITY + "animal/Pig", owner(ENTITY + "animal/Pig", "FOOD_ITEMS"));
        classes.put(ENTITY + "animal/Cat", owner(ENTITY + "animal/Cat", "TEMPT_INGREDIENT"));
        classes.put(ENTITY + "animal/Parrot", owner(ENTITY + "animal/Parrot", "TAME_FOOD"));
        classes.put(ENTITY + "npc/Villager", owner(ENTITY + "npc/Villager", "WANTED_ITEMS", "FOOD_POINTS"));

        for (LegacyAnimalFoodBridge.RemovedField field : LegacyAnimalFoodBridge.FIELDS) {
            assertFalse(LegacyAnimalFoodBridge.hostDropped(field, classes::get, "1.20.4"), field.name());
        }
    }

    @Test
    @DisplayName("with no host classes to read, the target version decides each field")
    void offlineTargetDecides() {
        Function<String, ClassNode> none = name -> null;
        assertEquals(0, dropped(none, "1.20.4"));
        assertEquals(4, dropped(none, "1.21.1"), "the four animal fields went in 1.20.5");
        assertEquals(5, dropped(none, "1.21.2"), "WANTED_ITEMS went in 1.21.2");
        assertEquals(5, dropped(none, "26.2"));
        assertEquals(6, dropped(none, "26.3"), "FOOD_POINTS went in 26.3");
    }

    @ParameterizedTest(name = "{0} 1.21.1")
    @ValueSource(strings = {"neoforge", "forge"})
    @DisplayName("a 1.21.1 mod's Villager.WANTED_ITEMS reaches the helper through its own chain on a 26.2 host")
    void villagerItemsFrom121ModsOn262(String loader) {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        McReflect.setForceForge(loader.equals("forge"));
        ShimRegistry registry = new ShimRegistry();
        ServiceLoader.load(VersionShim.class).forEach(registry::register);
        List<VersionShim> chain = registry.findShimChain(loader, "1.21.1", "26.2");
        assertTrue(chain.stream().anyMatch(shim -> shim.getSourceVersion().equals("1.21.1")),
                "the chain starts at 1.21.1: " + chain);
        for (VersionShim shim : chain) shim.registerRedirects(transformer);
        transformer.setModSourceVersion("1.21.1");

        // Farmer's Delight for NeoForge 1.21.1: WANTED_ITEMS read into a new set, then the set written back.
        List<AbstractInsnNode> code = code(transform(villagerItems()));

        assertCall(code, "villagerWantedItems", LegacyAnimalFoodBridge.GETTER_DESC);
        assertCall(code, "setVillagerWantedItems", LegacyAnimalFoodBridge.SETTER_DESC);
        assertEquals(0, fieldAccesses(code, "WANTED_ITEMS"), "no access is left to fail with NoSuchFieldError");
    }

    // ---- fixtures ----

    /** The 1.21.1 shape: Mojang names on the pre-26.1 owner. */
    private static byte[] villagerItems() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerItemSetAdditions", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, ENTITY + "npc/Villager", "WANTED_ITEMS", "Ljava/util/Set;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableSet", "copyOf",
                "(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableSet;", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, ENTITY + "npc/Villager", "WANTED_ITEMS", "Ljava/util/Set;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static int dropped(Function<String, ClassNode> host, String target) {
        int count = 0;
        for (LegacyAnimalFoodBridge.RemovedField field : LegacyAnimalFoodBridge.FIELDS) {
            if (LegacyAnimalFoodBridge.hostDropped(field, host, target)) count++;
        }
        return count;
    }

    /** 26.2: moved entity classes without the old fields, the item tags, and Villager.FOOD_POINTS still there. */
    private static Function<String, ClassNode> host262() {
        Map<String, ClassNode> classes = movedOwners();
        classes.put(ENTITY + "npc/villager/Villager", owner(ENTITY + "npc/villager/Villager", "FOOD_POINTS"));
        return classes::get;
    }

    /** 26.3: FOOD_POINTS is gone and VillagerFood exists. */
    private static Function<String, ClassNode> host263() {
        Map<String, ClassNode> classes = movedOwners();
        classes.put("net/minecraft/world/food/VillagerFood", owner("net/minecraft/world/food/VillagerFood"));
        return classes::get;
    }

    private static Map<String, ClassNode> movedOwners() {
        Map<String, ClassNode> classes = new HashMap<>();
        for (String moved : List.of("animal/chicken/Chicken", "animal/pig/Pig", "animal/feline/Cat",
                "animal/parrot/Parrot", "npc/villager/Villager")) {
            classes.put(ENTITY + moved, owner(ENTITY + moved, "BREEDING_FOOD_THRESHOLD"));
        }
        classes.put("net/minecraft/tags/ItemTags", owner("net/minecraft/tags/ItemTags", "CHICKEN_FOOD"));
        return classes;
    }

    private static ClassNode owner(String name, String... fields) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (String field : fields) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Ljava/lang/Object;", null, null));
        }
        return node;
    }

    /** Farmer's Delight's CommonSetup.registerItemSetAdditions in 1.20.1 names, cut to one access of each kind. */
    private static byte[] registerItemSetAdditions() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerItemSetAdditions", "()V", null, null);
        mv.visitCode();
        // Chicken.FOOD_ITEMS = compound(Chicken.FOOD_ITEMS, seeds), by its SRG id on the 1.20.1 owner.
        mv.visitFieldInsn(Opcodes.GETSTATIC, ENTITY + "animal/Chicken", "f_28233_", INGREDIENT);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, ENTITY + "animal/Chicken", "f_28233_", INGREDIENT);
        // Collections.addAll(Parrot.TAME_FOOD, ...), by Mojang name on the 26.x owner.
        mv.visitFieldInsn(Opcodes.GETSTATIC, ENTITY + "animal/parrot/Parrot", "TAME_FOOD", "Ljava/util/Set;");
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ENTITY + "npc/villager/Villager", "WANTED_ITEMS", "Ljava/util/Set;");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, ENTITY + "npc/villager/Villager", "WANTED_ITEMS", "Ljava/util/Set;");
        mv.visitFieldInsn(Opcodes.GETSTATIC, ENTITY + "npc/villager/Villager", "FOOD_POINTS", "Ljava/util/Map;");
        mv.visitFieldInsn(Opcodes.PUTSTATIC, ENTITY + "npc/villager/Villager", "FOOD_POINTS", "Ljava/util/Map;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_CLASS)).accept(node, 0);
        return node;
    }

    private static List<AbstractInsnNode> code(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("registerItemSetAdditions")) {
                List<AbstractInsnNode> code = new ArrayList<>();
                for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
                return code;
            }
        }
        throw new AssertionError("registerItemSetAdditions is missing");
    }

    private static void assertCall(List<AbstractInsnNode> code, String name, String desc) {
        assertTrue(indexOfCall(code, name) >= 0, "no call to " + name + " in " + describe(code));
        MethodInsnNode call = (MethodInsnNode) code.get(indexOfCall(code, name));
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(desc, call.desc);
    }

    /** The getter returns Object, so the read is cast back to the field's type for the code that follows. */
    private static void assertCast(List<AbstractInsnNode> code, String getter, String type) {
        AbstractInsnNode next = code.get(indexOfCall(code, getter) + 1);
        assertEquals(Opcodes.CHECKCAST, next.getOpcode(), "a cast follows " + getter);
        assertEquals(type, ((TypeInsnNode) next).desc);
    }

    private static int indexOfCall(List<AbstractInsnNode> code, String name) {
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call && call.owner.equals(HELPER) && call.name.equals(name)) return i;
        }
        return -1;
    }

    private static int fieldAccesses(List<AbstractInsnNode> code, String name) {
        int count = 0;
        for (AbstractInsnNode insn : code) if (insn instanceof FieldInsnNode field && field.name.equals(name)) count++;
        return count;
    }

    private static String describe(List<AbstractInsnNode> code) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
            else if (insn instanceof FieldInsnNode field) out.add(field.owner + "." + field.name);
            else out.add(String.valueOf(insn.getOpcode()));
        }
        return out.toString();
    }
}
