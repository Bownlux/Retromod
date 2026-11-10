/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #372: Fossils and Archaeology 9.3.4.0 (Forge 1.20.1) reads its machine recipes with the 1.20.1
 * serializer API, and NeoForge 1.21.1 stopped loading data with {@code AbstractMethodError}
 * because its serializers have no {@code codec()}.
 */
class LegacyRecipeApiBridgeTest {

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String ID = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String BUF = "Lnet/minecraft/network/FriendlyByteBuf;";
    private static final String RECIPE = "L" + CRAFTING + "Recipe;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String CONTAINER = "Lnet/minecraft/world/Container;";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String GSON = String.join("/", "com", "google", "gson") + "/";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old serializer gains the codec and stream codec 1.20.5 asks for")
    void serializerGainsCodecs() {
        RetromodTransformer transformer = bridged("1.21.1");
        ClassNode serializer = transform(transformer, serializer(), "mod/EggSerializer");

        assertCalls(serializer, "codec", "()Lcom/mojang/serialization/MapCodec;", LegacyRecipeApiBridge.SUPPORT + ".codec");
        assertCalls(serializer, "streamCodec", "()Lnet/minecraft/network/codec/StreamCodec;",
                LegacyRecipeApiBridge.STREAM + ".<init>");
    }

    @Test
    @DisplayName("A mod class under com/retromod, like the test mod's, is repaired too")
    void modClassesUnderRetromodAreRepaired() {
        RetromodTransformer transformer = bridged("1.21.1");
        byte[] serializer = serializer().clone();
        ClassNode node = new ClassNode();
        new ClassReader(serializer).accept(node, 0);
        node.name = "com/retromod/testmod/forge/EggSerializer";
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);

        ClassNode repaired = transform(transformer, writer.toByteArray(), node.name);

        assertNotNull(method(repaired, "codec", "()Lcom/mojang/serialization/MapCodec;"));
    }

    @Test
    @DisplayName("An old recipe answers the 1.21 input and provider methods through its own")
    void recipeGainsInputMethods() {
        RetromodTransformer transformer = bridged("1.21.1");
        ClassNode recipe = transform(transformer, recipe(), "mod/EggRecipe");

        String input = "L" + CRAFTING + "RecipeInput;";
        String provider = "Lnet/minecraft/core/HolderLookup$Provider;";
        assertCalls(recipe, "matches", "(" + input + LEVEL + ")Z", "mod/EggRecipe.matches");
        assertCalls(recipe, "assemble", "(" + input + provider + ")" + STACK, LegacyRecipeApiBridge.CALLS + ".container");
        assertCalls(recipe, "assemble", "(" + input + provider + ")" + STACK, LegacyRecipeApiBridge.CALLS + ".registryAccess");
        assertCalls(recipe, "getResultItem", "(" + provider + ")" + STACK, "mod/EggRecipe.getResultItem");
    }

    @Test
    @DisplayName("On 1.20.5 and 1.20.6 a recipe keeps reading containers and only the provider changes")
    void containerHostsOnlyChangeTheProvider() {
        RetromodTransformer transformer = bridged("1.20.6");
        ClassNode recipe = transform(transformer, recipe(), "mod/EggRecipe");

        String provider = "Lnet/minecraft/core/HolderLookup$Provider;";
        assertNull(method(recipe, "matches", "(L" + CRAFTING + "RecipeInput;" + LEVEL + ")Z"));
        assertCalls(recipe, "assemble", "(" + CONTAINER + provider + ")" + STACK, LegacyRecipeApiBridge.CALLS + ".registryAccess");
    }

    @Test
    @DisplayName("Lookups and removed helpers go to the adapters; a mod built for 1.20.2 keeps the host lookups")
    void lookupsAreLimitedToOldMods() {
        RetromodTransformer transformer = bridged("1.21.1");
        transformer.setModSourceVersion("1.20.1");
        List<String> old = calls(transform(transformer, machine(), "mod/Machine"));
        assertTrue(old.contains(LegacyRecipeApiBridge.CALLS + ".getRecipeFor"), old.toString());
        assertTrue(old.contains(LegacyRecipeApiBridge.CALLS + ".getAllRecipesFor"), old.toString());
        assertTrue(old.contains(LegacyRecipeApiBridge.CALLS + ".ingredientFromNetwork"), old.toString());
        assertTrue(old.contains(LegacyRecipeApiBridge.CALLS + ".readItem"), old.toString());
        assertTrue(old.contains(LegacyRecipeApiBridge.CALLS + ".getId"), old.toString());

        transformer.setModSourceVersion("1.20.4");
        List<String> newer = calls(transform(transformer, machine(), "mod/Machine"));
        assertTrue(newer.contains(LegacyRecipeApiBridge.MANAGER + ".getAllRecipesFor"), newer.toString());
    }

    @Test
    @DisplayName("On 26.2 an old serializer, which no longer claims the interface, still gains its codecs")
    void serializerWithoutInterfaceGainsCodecs() {
        RetromodTransformer transformer = bridged("26.2");
        ClassNode serializer = transform(transformer, serializer(null, IDENTIFIER), "mod/EggSerializer");

        assertCalls(serializer, "codec", "()Lcom/mojang/serialization/MapCodec;", LegacyRecipeApiBridge.SUPPORT + ".codec");
        assertNotNull(method(serializer, "streamCodec", "()Lnet/minecraft/network/codec/StreamCodec;"));
        ClassNode other = transform(transformer, plainClassWithFromJson(), "mod/Parser");
        assertNull(method(other, "codec", "()Lcom/mojang/serialization/MapCodec;"), "a class with only a JSON reader is not a serializer");
    }

    @Test
    @DisplayName("On 26.2 an old recipe answers 26.2's Recipe: input methods without registries, and the recipe book questions")
    void recipeAnswersRecipeBook() {
        RetromodTransformer transformer = bridged("26.2");
        ClassNode recipe = transform(transformer, recipeWithGroup(), "mod/EggRecipe");

        String input = "L" + CRAFTING + "RecipeInput;";
        assertCalls(recipe, "matches", "(" + input + LEVEL + ")Z", "mod/EggRecipe.matches");
        assertCalls(recipe, "assemble", "(" + input + ")" + STACK, LegacyRecipeApiBridge.CALLS + ".registryAccess");
        assertNull(method(recipe, "assemble", "(" + input + "Lnet/minecraft/core/HolderLookup$Provider;)" + STACK));
        assertNotNull(method(recipe, "placementInfo", "()L" + CRAFTING + "PlacementInfo;"));
        assertNotNull(method(recipe, "recipeBookCategory", "()L" + CRAFTING + "RecipeBookCategory;"));
        assertNotNull(method(recipe, "showNotification", "()Z"));
        assertCalls(recipe, "group", "()Ljava/lang/String;", "mod/EggRecipe.getGroup");
    }

    @Test
    @DisplayName("On 1.21.11 an old recipe's assemble still takes the registries")
    void recipeOnOneTwentyOneEleven() {
        RetromodTransformer transformer = bridged("1.21.11");
        ClassNode recipe = transform(transformer, recipeWithGroup(), "mod/EggRecipe");

        String input = "L" + CRAFTING + "RecipeInput;";
        assertNotNull(method(recipe, "assemble", "(" + input + "Lnet/minecraft/core/HolderLookup$Provider;)" + STACK));
        assertNotNull(method(recipe, "placementInfo", "()L" + CRAFTING + "PlacementInfo;"));
    }

    @Test
    @DisplayName("On 26.2 an old mod's lookups, its level's manager, the cached check, the empty ingredient and Minecraft's serializers are bridged")
    void recipeBookLookups() {
        RetromodTransformer transformer = bridged("26.2");
        // The 1.21.1 lookups bridge registers on the same hosts, and the old lookups continue from its helpers.
        LegacyRecipeIntrospectionBridge.register(transformer);
        transformer.setModSourceVersion("1.20.1");
        List<String> old = calls(transform(transformer, recipeBookMachine(), "mod/Pot"));

        for (String call : List.of(".getRecipeFor", ".getRecipesFor", ".getAllRecipesFor", ".byKey", ".cachedRecipeFor",
                ".recipeManager", ".emptyIngredient", ".ingredientIsEmpty", ".serializerFromJson")) {
            assertTrue(old.contains(LegacyRecipeApiBridge.CALLS + call), call + " in " + old);
        }
        assertTrue(old.contains(CRAFTING + "Recipe.group"), old.toString());
    }

    @Test
    @DisplayName("The recipe API is bridged from 1.20.5 on, in each host's shape")
    void hostRange() {
        assertNull(LegacyRecipeApiBridge.offlineShape("1.20.4"));
        assertFalse(LegacyRecipeApiBridge.offlineShape("1.20.5").inputs());
        assertTrue(LegacyRecipeApiBridge.offlineShape("1.21.1").inputs());
        assertFalse(LegacyRecipeApiBridge.offlineShape("1.21.1").recipeBook());
        LegacyRecipeApiBridge.HostShape early = LegacyRecipeApiBridge.offlineShape("1.21.2");
        assertTrue(early.recipeBook() && early.assembleProvider() && early.id().endsWith("ResourceLocation"));
        assertTrue(LegacyRecipeApiBridge.offlineShape("1.21.11").id().endsWith("Identifier"));
        LegacyRecipeApiBridge.HostShape current = LegacyRecipeApiBridge.offlineShape("26.3");
        assertTrue(current.recipeBook() && !current.assembleProvider() && current.id().endsWith("Identifier"));
        assertTrue(LegacyRecipeApiBridge.isRegistered(bridged("26.1").getSyntheticClasses()::containsKey));
    }

    @Test
    @DisplayName("The generated classes verify")
    void generatedClassesVerify() {
        for (byte[] generated : List.of(LegacyRecipeApiBridge.generateCalls(new LegacyRecipeApiBridge.HostShape(true)),
                LegacyRecipeApiBridge.generateCalls(new LegacyRecipeApiBridge.HostShape(false)),
                LegacyRecipeApiBridge.generateCalls(LegacyRecipeApiBridge.offlineShape("1.21.4")),
                LegacyRecipeApiBridge.generateCalls(LegacyRecipeApiBridge.offlineShape("26.3")),
                LegacyRecipeApiBridge.generateCalls(LegacyRecipeApiBridge.offlineShape("26.3"), true),
                LegacyRecipeApiBridge.generateInput(), LegacyRecipeApiBridge.generateStreamCodec())) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    @Test
    @DisplayName("From 26.1 a serializer's result stacks wait for the item components a world binds after the recipes")
    void resultStacksWaitForComponents() {
        RetromodTransformer transformer = bridged("26.3");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEarlyItemStackBridge.HELPER), "the early stack helper travels");
        ClassNode calls = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(LegacyRecipeApiBridge.CALLS)).accept(calls, 0);
        for (MethodNode method : calls.methods) {
            if (!method.name.equals("craftingItemStack") && !method.name.equals("itemStackFromJson")) continue;
            assertTrue(owners(method).contains(LegacyEarlyItemStackBridge.HELPER + ".of"), method.name + method.desc);
        }
        ClassNode older = new ClassNode();
        new ClassReader(bridged("1.21.4").getSyntheticClasses().get(LegacyRecipeApiBridge.CALLS)).accept(older, 0);
        assertTrue(older.methods.stream().filter(m -> m.name.equals("craftingItemStack"))
                .noneMatch(m -> owners(m).contains(LegacyEarlyItemStackBridge.HELPER + ".of")),
                "a host that binds components with its items builds the stack directly");
    }

    @Test
    @DisplayName("On NeoForge a Forge mod's shaped recipe size calls read the vanilla getters; Forge keeps its own")
    void forgeShapedSizeOnNeoForge() {
        boolean saved = McReflect.isForceNeoForge();
        try {
            McReflect.setForceNeoForge(true);
            RetromodTransformer transformer = bridged("26.3");
            transformer.setModSourceVersion("1.20.1");
            List<String> neo = calls(transform(transformer, sizeReader(), "mod/Size"));
            assertTrue(neo.contains(CRAFTING + "ShapedRecipe.getWidth") && neo.contains(CRAFTING + "ShapedRecipe.getHeight"), neo.toString());

            McReflect.setForceNeoForge(false);
            transformer = bridged("26.3");
            transformer.setModSourceVersion("1.20.1");
            List<String> forge = calls(transform(transformer, sizeReader(), "mod/Size"));
            assertTrue(forge.contains(CRAFTING + "ShapedRecipe.getRecipeWidth"), "Forge's shaped recipe still has it: " + forge);
        } finally {
            McReflect.setForceNeoForge(saved);
        }
    }

    /** {@code recipe.getRecipeWidth() * recipe.getRecipeHeight()}, as a Forge 1.20.1 recipe wrapper reads its pattern. */
    private static byte[] sizeReader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Size", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "area", "(L" + CRAFTING + "ShapedRecipe;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "ShapedRecipe", "getRecipeWidth", "()I", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "ShapedRecipe", "getRecipeHeight", "()I", false);
        mv.visitInsn(IMUL);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Each method call in {@code method}, as {@code owner.name}. */
    private static List<String> owners(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static RetromodTransformer bridged(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyRecipeApiBridge.register(transformer);
        return transformer;
    }

    /** {@code class EggSerializer implements RecipeSerializer} with the 1.20.1 JSON and network readers. */
    private static byte[] serializer() {
        return serializer(CRAFTING + "RecipeSerializer", ID);
    }

    /** The same serializer, claiming {@code iface} when it is not null, with ids of class {@code id}. */
    private static byte[] serializer(String iface, String id) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/EggSerializer", null, "java/lang/Object", iface == null ? null : new String[]{iface});
        constructor(cw, "java/lang/Object");
        stub(cw, "fromJson", "(" + id + "L" + GSON + "JsonObject;)" + RECIPE);
        stub(cw, "fromNetwork", "(" + id + BUF + ")" + RECIPE);
        stub(cw, "toNetwork", "(" + BUF + RECIPE + ")V");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that reads JSON with the same descriptor but is no serializer. */
    private static byte[] plainClassWithFromJson() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Parser", null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        stub(cw, "fromJson", "(" + IDENTIFIER + "L" + GSON + "JsonObject;)" + RECIPE);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.20.1 recipe, with its own group. */
    private static byte[] recipeWithGroup() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "mod/EggRecipe", null, "java/lang/Object", new String[]{CRAFTING + "Recipe"});
        constructor(cw, "java/lang/Object");
        stub(cw, "matches", "(" + CONTAINER + LEVEL + ")Z");
        stub(cw, "assemble", "(" + CONTAINER + "Lnet/minecraft/core/RegistryAccess;)" + STACK);
        stub(cw, "getGroup", "()Ljava/lang/String;");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Farmer's Delight's cooking pot: its lookups, its level's manager, a cached check, an empty ingredient, and a vanilla serializer read. */
    private static byte[] recipeBookMachine() {
        String manager = CRAFTING + "RecipeManager";
        String check = CRAFTING + "RecipeManager$CachedCheck";
        String type = "L" + CRAFTING + "RecipeType;";
        String level = "net/minecraft/world/level/Level";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Pot", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tick",
                "(L" + level + ";" + type + CONTAINER + "L" + check + ";L" + CRAFTING + "RecipeSerializer;L" + GSON + "JsonObject;" + RECIPE + ")V",
                null, null);
        mv.visitCode();
        // level.getRecipeManager().getRecipeFor(type, container, level), getRecipesFor, getAllRecipesFor, byKey
        String[][] lookups = {
            {"getRecipeFor", "(" + type + CONTAINER + LEVEL + ")Ljava/util/Optional;"},
            {"getRecipesFor", "(" + type + CONTAINER + LEVEL + ")Ljava/util/List;"},
        };
        for (String[] lookup : lookups) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, level, "getRecipeManager", "()L" + manager + ";", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, manager, lookup[0], lookup[1], false);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, level, "getRecipeManager", "()L" + manager + ";", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, manager, "getAllRecipesFor", "(" + type + ")Ljava/util/List;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, level, "getRecipeManager", "()L" + manager + ";", false);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, manager, "byKey", "(" + IDENTIFIER + ")Ljava/util/Optional;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, check, "getRecipeFor", "(" + CONTAINER + LEVEL + ")Ljava/util/Optional;", true);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, CRAFTING + "Ingredient", "EMPTY", "L" + CRAFTING + "Ingredient;");
        mv.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "Ingredient", "isEmpty", "()Z", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, CRAFTING + "RecipeSerializer", "fromJson", "(" + IDENTIFIER + "L" + GSON + "JsonObject;)" + RECIPE, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitMethodInsn(INVOKEINTERFACE, CRAFTING + "Recipe", "getGroup", "()Ljava/lang/String;", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class EggRecipe implements Recipe} with the 1.20.1 container and registry access methods. */
    private static byte[] recipe() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "mod/EggRecipe", null, "java/lang/Object", new String[]{CRAFTING + "Recipe"});
        constructor(cw, "java/lang/Object");
        stub(cw, "matches", "(" + CONTAINER + LEVEL + ")Z");
        stub(cw, "assemble", "(" + CONTAINER + "Lnet/minecraft/core/RegistryAccess;)" + STACK);
        stub(cw, "getResultItem", "(Lnet/minecraft/core/RegistryAccess;)" + STACK);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A machine that looks recipes up, reads an ingredient and a stack from a buffer, and asks a recipe for its id. */
    private static byte[] machine() {
        String manager = CRAFTING + "RecipeManager";
        String type = "L" + CRAFTING + "RecipeType;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Machine", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tick",
                "(L" + manager + ";" + type + CONTAINER + LEVEL + BUF + RECIPE + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, manager, "getRecipeFor", "(" + type + CONTAINER + LEVEL + ")Ljava/util/Optional;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, manager, "getAllRecipesFor", "(" + type + ")Ljava/util/List;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKESTATIC, CRAFTING + "Ingredient", "fromNetwork", "(" + BUF + ")L" + CRAFTING + "Ingredient;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf", "readItem", "()" + STACK, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, CRAFTING + "Recipe", "getId", "()" + ID, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    /** A method body that throws, since only the shape matters here. */
    private static void stub(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode transform(RetromodTransformer transformer, byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name)).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static void assertCalls(ClassNode node, String name, String desc, String call) {
        MethodNode method = method(node, name, desc);
        assertNotNull(method, node.name + " has no " + name + desc);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode invoke) calls.add(invoke.owner + "." + invoke.name);
        }
        assertTrue(calls.contains(call), name + desc + " should call " + call + ", calls " + calls);
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }
}
