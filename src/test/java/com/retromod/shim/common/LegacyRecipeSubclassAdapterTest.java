/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #249: Sophisticated Backpacks wraps vanilla recipes in subclasses written against the 1.21.x
 * recipe classes. On 26.1 every one of its 28 recipes failed to decode with
 * {@code NoSuchFieldError: ShapedRecipe.result} or {@code NoSuchMethodError: CustomRecipe(CraftingBookCategory)}.
 */
class LegacyRecipeSubclassAdapterTest {

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String SHAPED = CRAFTING + "ShapedRecipe";
    private static final String SMITHING = CRAFTING + "SmithingTransformRecipe";
    private static final String CUSTOM = CRAFTING + "CustomRecipe";
    private static final String CRAFTING_INPUT = CRAFTING + "CraftingInput";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String LEGACY_ASSEMBLE = "(L" + CRAFTING_INPUT + ";" + PROVIDER + ")" + STACK;
    private static final String CURRENT_ASSEMBLE = "(L" + CRAFTING_INPUT + ";)" + STACK;

    @Test
    @DisplayName("a shaped recipe wrapper copies its vanilla recipe through the 26.1 constructor")
    void shapedWrapperUsesCurrentConstructor() {
        ClassNode fixed = adapt(shapedWrapper());
        MethodNode ctor = method(fixed, "<init>", "(L" + SHAPED + ";)V");

        MethodInsnNode superCall = calls(ctor).stream()
                .filter(c -> c.owner.equals(SHAPED) && c.name.equals("<init>")).findFirst()
                .orElseThrow();
        assertEquals("(L" + CRAFTING + "Recipe$CommonInfo;L" + CRAFTING
                        + "CraftingRecipe$CraftingBookInfo;L" + CRAFTING
                        + "ShapedRecipePattern;Lnet/minecraft/world/item/ItemStackTemplate;)V",
                superCall.desc, "super(...) must link against the 26.1 ShapedRecipe constructor");
        assertTrue(calls(ctor).stream().anyMatch(c -> c.owner.equals(
                LegacyRecipeSubclassAdapter.INTERNALS) && c.name.equals("resultTemplate")),
                "a copied result passes through as a template");
        assertTrue(calls(ctor).stream().noneMatch(c -> c.name.equals("fromNonEmptyStack")
                        || c.name.equals("result")),
                "recipes decode before item components are bound, so no ItemStack may be built");
        assertTrue(fields(ctor).stream().noneMatch(f -> f.name.equals("result")),
                "no reference to the retyped result field may remain");
        assertTrue(fields(ctor).stream().anyMatch(f -> f.name.equals("pattern")),
                "pattern kept its type, so the mod's access widener still covers it");
        verify(fixed);
    }

    @Test
    @DisplayName("the shaped constructor that set showNotification keeps its setting")
    void shapedNotificationConstructorKeepsItsFlag() {
        String pattern = "L" + CRAFTING + "ShapedRecipePattern;";
        String category = "L" + CRAFTING + "CraftingBookCategory;";
        String oldDesc = "(Ljava/lang/String;" + category + pattern + STACK + "Z)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/QuietRecipe", null, SHAPED, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;" + category + pattern + STACK + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitVarInsn(Opcodes.ALOAD, 3);
        ctor.visitVarInsn(Opcodes.ALOAD, 4);
        ctor.visitInsn(Opcodes.ICONST_0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, SHAPED, "<init>", oldDesc, false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode fixed = adapt(cw.toByteArray());
        MethodNode init = fixed.methods.stream().filter(m -> m.name.equals("<init>")).findFirst()
                .orElseThrow();
        assertTrue(calls(init).stream().anyMatch(c -> c.owner.equals(SHAPED)
                        && c.desc.startsWith("(L" + CRAFTING + "Recipe$CommonInfo;")),
                "super(...) must link against the 26.1 ShapedRecipe constructor");
        MethodInsnNode commonInfo = calls(init).stream()
                .filter(c -> c.owner.equals(CRAFTING + "Recipe$CommonInfo")).findFirst().orElseThrow();
        assertEquals(Opcodes.ILOAD, commonInfo.getPrevious().getOpcode(),
                "CommonInfo takes the mod's own showNotification, not the default");
        verify(fixed);
    }

    @Test
    @DisplayName("an old assemble override gets the 26.1 entry point, and its super call drops the provider")
    void legacyAssembleOverrideIsReachable() {
        ClassNode fixed = adapt(shapedWrapper());

        MethodNode current = method(fixed, "assemble", CURRENT_ASSEMBLE);
        assertTrue(calls(current).stream().anyMatch(c -> c.desc.equals(LEGACY_ASSEMBLE)),
                "26.1 calls assemble(input); it must reach the mod's override");
        assertTrue(calls(current).stream().anyMatch(c -> c.owner.equals(
                        LegacyRecipeSubclassAdapter.INTERNALS) && c.name.equals("registries")),
                "the override receives the running game's registries, not null");
        for (AbstractInsnNode insn : current.instructions) {
            assertNotEquals(Opcodes.ACONST_NULL, insn.getOpcode(),
                    "no null registry provider is passed to the mod's override");
        }
        MethodNode legacy = method(fixed, "assemble", LEGACY_ASSEMBLE);
        MethodInsnNode superAssemble = calls(legacy).stream()
                .filter(c -> c.owner.equals(SHAPED)).findFirst().orElseThrow();
        assertEquals(Opcodes.INVOKESPECIAL, superAssemble.getOpcode());
        assertEquals(CURRENT_ASSEMBLE, superAssemble.desc,
                "super.assemble must call the surviving vanilla method, not recurse");
        assertNotNull(find(fixed, "assemble", "(L" + CRAFTING + "RecipeInput;)" + STACK),
                "vanilla calls the erased interface method");
        verify(fixed);
    }

    @Test
    @DisplayName("the registry lookup answers null, not an exception, when no game is running")
    void registryLookupWithoutAGame() {
        assertNull(com.retromod.shim.common.embedded.LegacyRecipeInternals.registries(),
                "a unit test has no server or client, so there are no registries to hand over");
    }

    @Test
    @DisplayName("a custom recipe keeps constructing without a category")
    void customRecipeDropsCategory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/DyeRecipe", null, CUSTOM, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(L" + CRAFTING + "CraftingBookCategory;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, CUSTOM, "<init>",
                "(L" + CRAFTING + "CraftingBookCategory;)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        emitLegacyAssemble(cw, "mod/DyeRecipe", null);
        cw.visitEnd();

        ClassNode fixed = adapt(cw.toByteArray());
        assertTrue(calls(method(fixed, "<init>", "(L" + CRAFTING + "CraftingBookCategory;)V"))
                .stream().anyMatch(c -> c.owner.equals(CUSTOM) && c.desc.equals("()V")));
        assertNotNull(find(fixed, "assemble", CURRENT_ASSEMBLE),
                "CustomRecipe has no assemble(input) of its own, so the class needs one");
        verify(fixed);
    }

    @Test
    @DisplayName("a smithing wrapper reads the optional ingredients through their accessors")
    void smithingWrapperUsesAccessors() {
        String ingredient = "L" + CRAFTING + "Ingredient;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/SmithingWrapper", null, SMITHING, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + SMITHING + ";)V",
                null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        for (String field : new String[]{"template", "base", "addition"}) {
            ctor.visitVarInsn(Opcodes.ALOAD, 1);
            ctor.visitFieldInsn(Opcodes.GETFIELD, SMITHING, field, ingredient);
        }
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.GETFIELD, SMITHING, "result", STACK);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, SMITHING, "<init>",
                "(" + ingredient + ingredient + ingredient + STACK + ")V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode fixed = adapt(cw.toByteArray());
        MethodNode init = method(fixed, "<init>", "(L" + SMITHING + ";)V");
        assertTrue(fields(init).isEmpty(), "every smithing field read is replaced");
        List<String> names = calls(init).stream().map(c -> c.name).toList();
        assertTrue(names.containsAll(List.of("templateIngredient", "baseIngredient",
                "additionIngredient", "ofNullable", "resultTemplate")), names.toString());
        verify(fixed);
    }

    @Test
    @DisplayName("a result read that does not feed a constructor still yields an ItemStack")
    void standaloneResultReadBuildsStack() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/ResultReader", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "output",
                "(L" + SHAPED + ";)" + STACK, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, SHAPED, "result", STACK);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        MethodNode output = method(adapt(cw.toByteArray()), "output", "(L" + SHAPED + ";)" + STACK);
        assertTrue(calls(output).stream().anyMatch(c -> c.name.equals("result")),
                "a plain read must still return a stack");
    }

    @Test
    @DisplayName("a class written for 26.1 is left untouched")
    void currentRecipeIsUntouched() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/ModernRecipe", null, CUSTOM, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, CUSTOM, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        byte[] modern = cw.toByteArray();
        assertSame(modern, LegacyRecipeSubclassAdapter.apply(modern));
    }

    private static byte[] shapedWrapper() {
        String pattern = "L" + CRAFTING + "ShapedRecipePattern;";
        String category = "L" + CRAFTING + "CraftingBookCategory;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/UpgradeRecipe", null, SHAPED, null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + SHAPED + ";)V",
                null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SHAPED, "group", "()Ljava/lang/String;", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SHAPED, "category", "()" + category, false);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.GETFIELD, SHAPED, "pattern", pattern);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.GETFIELD, SHAPED, "result", STACK);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, SHAPED, "<init>",
                "(Ljava/lang/String;" + category + pattern + STACK + ")V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        emitLegacyAssemble(cw, "mod/UpgradeRecipe", SHAPED);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code assemble(input, registries)}, calling the vanilla super method when there is one. */
    private static void emitLegacyAssemble(ClassWriter cw, String owner, String vanillaSuper) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "assemble", LEGACY_ASSEMBLE, null, null);
        m.visitCode();
        if (vanillaSuper != null) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitVarInsn(Opcodes.ALOAD, 2);
            m.visitMethodInsn(Opcodes.INVOKESPECIAL, vanillaSuper, "assemble", LEGACY_ASSEMBLE,
                    false);
        } else {
            m.visitInsn(Opcodes.ACONST_NULL);
        }
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static ClassNode adapt(byte[] original) {
        byte[] adapted = LegacyRecipeSubclassAdapter.apply(original);
        assertNotSame(original, adapted, "the legacy recipe class should have been repaired");
        ClassNode node = new ClassNode();
        new ClassReader(adapted).accept(node, 0);
        return node;
    }

    /** Runs the bytecode verifier over every method, so a bad stack shape fails the test. */
    private static void verify(ClassNode node) {
        for (MethodNode m : node.methods) {
            try {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
            } catch (Exception e) {
                fail(node.name + "." + m.name + m.desc + " does not verify: " + e.getMessage());
            }
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        MethodNode m = find(node, name, desc);
        assertNotNull(m, "missing " + name + desc);
        return m;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }

    private static List<MethodInsnNode> calls(MethodNode m) {
        List<MethodInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call) out.add(call);
        }
        return out;
    }

    private static List<FieldInsnNode> fields(MethodNode m) {
        List<FieldInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof FieldInsnNode field) out.add(field);
        }
        return out;
    }
}
