/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The 1.21.1 recipe lookups on each loader's recipe manager, read by method name like the real ones. */
class LegacyRecipeIntrospectionTest {

    private static final Object SMELTING = "smelting";
    private static final Object CRAFTING = "crafting";

    /** A recipe input; only its emptiness matters to the lookups. */
    public static class Input {
        private final boolean empty;
        public Input(boolean empty) { this.empty = empty; }
        public boolean isEmpty() { return empty; }
    }

    public static class Level {}

    /** A recipe that counts how often it was asked to match. */
    public static class Recipe {
        private final Object type;
        private final boolean matches;
        int asked;
        public Recipe(Object type, boolean matches) { this.type = type; this.matches = matches; }
        public Object getType() { return type; }
        public boolean matches(Input input, Level level) { asked++; return matches; }
    }

    /** A recipe holder, named for readable failures. */
    public static class Holder {
        private final String id;
        private final Recipe recipe;
        public Holder(String id, Recipe recipe) { this.id = id; this.recipe = recipe; }
        public Recipe value() { return recipe; }
        @Override public String toString() { return id; }
    }

    /** Forge's and vanilla's shape: the map is private, getRecipes() lists every holder. */
    public static class ForgeManager {
        private final List<Holder> holders;
        public ForgeManager(List<Holder> holders) { this.holders = holders; }
        public Collection<Holder> getRecipes() { return holders; }
    }

    /** NeoForge's recipe map, answering from its own lists so a test can tell where an answer came from. */
    public static class RecipeMapStandIn {
        private final List<Holder> ofType;
        private final List<Holder> matching;
        public RecipeMapStandIn() { this(List.of(), List.of()); }
        public RecipeMapStandIn(List<Holder> ofType, List<Holder> matching) { this.ofType = ofType; this.matching = matching; }
        public Collection<Holder> byType(Object type) { return ofType; }
        public Stream<Holder> getRecipesFor(Object type, Input input, Level level) { return matching.stream(); }
    }

    /** NeoForge's shape: a public recipeMap(), and a getRecipes() the lookups should not need. */
    public static class NeoForgeManager {
        private final RecipeMapStandIn map;
        int listed;
        public NeoForgeManager(RecipeMapStandIn map) { this.map = map; }
        public RecipeMapStandIn recipeMap() { return map; }
        public Collection<Holder> getRecipes() { listed++; return List.of(); }
    }

    @Test
    @DisplayName("Without a recipe map, every recipe of the type comes back in listed order, in a list the mod can change")
    void listsOneTypeFromGetRecipes() {
        Holder iron = holder("iron", SMELTING, true);
        Holder table = holder("table", CRAFTING, true);
        Holder gold = holder("gold", SMELTING, true);
        ForgeManager manager = new ForgeManager(List.of(iron, table, gold));

        List<?> smelting = (List<?>) LegacyRecipeIntrospection.allRecipesFor(manager, SMELTING);

        assertEquals(List.of(iron, gold), smelting);
        assertDoesNotThrow(() -> smelting.clear(), "1.21.1 mods may sort or trim the list they get back");
    }

    @Test
    @DisplayName("Without a recipe map, only matching recipes come back, and an empty input asks no recipe")
    void matchesFromGetRecipes() {
        Holder iron = holder("iron", SMELTING, true);
        Holder sand = holder("sand", SMELTING, false);
        Holder table = holder("table", CRAFTING, true);
        ForgeManager manager = new ForgeManager(List.of(iron, sand, table));

        List<?> matches = (List<?>) LegacyRecipeIntrospection.recipesFor(manager, SMELTING, new Input(false), new Level());
        assertEquals(List.of(iron), matches);
        assertDoesNotThrow(() -> matches.clear(), "the 1.21.1 lookup returned a list the mod could change");
        assertEquals(0, table.recipe.asked, "a recipe of another type is never asked");

        iron.recipe.asked = 0;
        sand.recipe.asked = 0;
        assertEquals(List.of(), LegacyRecipeIntrospection.recipesFor(manager, SMELTING, new Input(true), new Level()));
        assertEquals(0, iron.recipe.asked + sand.recipe.asked, "an empty input matches nothing, as the recipe map does");
    }

    @Test
    @DisplayName("NeoForge's recipe map answers both lookups, and the full recipe list is never read")
    void neoForgeUsesTheRecipeMap() {
        Holder iron = holder("iron", SMELTING, true);
        Holder gold = holder("gold", SMELTING, false);
        NeoForgeManager manager = new NeoForgeManager(new RecipeMapStandIn(List.of(iron, gold), List.of(gold)));

        assertEquals(List.of(iron, gold), LegacyRecipeIntrospection.allRecipesFor(manager, SMELTING));
        assertEquals(List.of(gold), LegacyRecipeIntrospection.recipesFor(manager, SMELTING, new Input(false), new Level()));
        assertEquals(0, manager.listed, "getRecipes() is only for hosts without the map");
    }

    @Test
    @DisplayName("On Fabric, the getRecipes() that returns a collection is used, not Fabric API's accessor for the map")
    void fabricPicksTheCollectionOverload() throws Exception {
        Holder iron = holder("iron", SMELTING, true);
        Holder table = holder("table", CRAFTING, true);
        Object manager = fabricManagerClass().getConstructor(List.class).newInstance(List.of(iron, table));
        long overloads = Arrays.stream(manager.getClass().getMethods()).filter(m -> m.getName().equals("getRecipes")).count();
        assertEquals(2, overloads, "the fixture carries both getRecipes() methods, as Fabric API's mixin leaves them");

        assertEquals(List.of(iron), LegacyRecipeIntrospection.allRecipesFor(manager, SMELTING));
        assertEquals(List.of(iron), LegacyRecipeIntrospection.recipesFor(manager, SMELTING, new Input(false), new Level()));
    }

    private static Holder holder(String id, Object type, boolean matches) {
        return new Holder(id, new Recipe(type, matches));
    }

    /** A subclass of the Forge shape that adds Fabric API's accessor, {@code RecipeMap getRecipes()}. */
    private static Class<?> fabricManagerClass() throws ReflectiveOperationException {
        String superName = Type.getInternalName(ForgeManager.class);
        String mapName = Type.getInternalName(RecipeMapStandIn.class);
        String name = superName.substring(0, superName.lastIndexOf('/') + 1) + "FabricShapedRecipeManager";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);

        // The constructor passes the holder list to the Forge shape.
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "(Ljava/util/List;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // Java source cannot declare a second no-argument getRecipes(), so the accessor is written as bytecode.
        MethodVisitor accessor = cw.visitMethod(ACC_PUBLIC, "getRecipes", "()L" + mapName + ";", null, null);
        accessor.visitCode();
        accessor.visitTypeInsn(NEW, mapName);
        accessor.visitInsn(DUP);
        accessor.visitMethodInsn(INVOKESPECIAL, mapName, "<init>", "()V", false);
        accessor.visitInsn(ARETURN);
        accessor.visitMaxs(0, 0);
        accessor.visitEnd();
        cw.visitEnd();
        return MethodHandles.lookup().defineClass(cw.toByteArray());
    }
}
