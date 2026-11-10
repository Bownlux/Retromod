/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.shim.common.ConstructorIdStamper;
import com.retromod.shim.common.HostFinalOverrideAdapter;
import com.retromod.shim.common.LegacyBlockApiAdapter;
import com.retromod.shim.common.LegacyDamageConstantBridge;
import com.retromod.shim.common.LegacyKeyCodeAdapter;
import com.retromod.shim.common.LegacyMaterialAdapter;
import com.retromod.shim.common.LegacyRecipeSerializerFlowAdapter;
import com.retromod.shim.common.LegacyRecipeSubclassAdapter;
import com.retromod.shim.common.LegacyTooltipAdapter;
import com.retromod.shim.common.LegacyVillagerBridge;
import com.retromod.shim.forge.LegacyCreativeTabClaimAdapter;
import com.retromod.shim.forge.LegacyEntityApi120Adapter;
import com.retromod.shim.forge.LegacyEntityPrivateFieldAdapter;
import com.retromod.shim.forge.LegacyRandomSourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Mods under com/retromod, as the test mods and test library are, get every class repair; Retromod's own copies do not. */
class RetromodOwnClassesTest {

    private static final String TEST_MOD = "com/retromod/testmod/forge/";
    private static final String EMBEDDED = SyntheticEmbedder.PREFIX + "testmod_0_1_0_abc/com/retromod/generated/";
    private static final String DRAGON = TEST_MOD + "Dragon";
    private static final String TAMABLE = "net/minecraft/world/entity/TamableAnimal";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String TOOLTIP_START = "(Lnet/minecraft/world/item/ItemStack;";
    private static final String TOOLTIP_END = "Ljava/util/List;Lnet/minecraft/world/item/TooltipFlag;)V";
    private static final String CONFIGURATION = "com/retromod/shim/forge/embedded/LegacyConfiguration";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        transformer.clearRedirectsForTesting();
    }

    /** One repair: the host it runs on, a class that needs it under a given name, and the repair. */
    record Repair(String name, String host, Function<String, byte[]> fixture, UnaryOperator<byte[]> repair) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Repair> repairs() {
        return Stream.of(
            new Repair("LegacyMaterialAdapter", "26.2", name -> staticMethod(name, "solid",
                    "(Lnet/minecraft/world/level/block/state/BlockState;)Z", mv -> {
                        String material = "net/minecraft/world/level/material/Material";
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState",
                                "getMaterial", "()L" + material + ";", false);
                        mv.visitMethodInsn(INVOKEVIRTUAL, material, "isSolid", "()Z", false);
                        mv.visitInsn(IRETURN);
                    }), LegacyMaterialAdapter::apply),
            new Repair("LegacyTooltipAdapter.apply", "26.2", name -> emptyOverride(name, ITEM, "appendHoverText",
                    TOOLTIP_START + "Lnet/minecraft/world/item/Item$TooltipContext;" + TOOLTIP_END),
                    LegacyTooltipAdapter::apply),
            new Repair("LegacyTooltipAdapter.applyLevelShape", "26.2", name -> emptyOverride(name, ITEM,
                    "appendHoverText", TOOLTIP_START + "Lnet/minecraft/world/level/Level;" + TOOLTIP_END),
                    bytes -> LegacyTooltipAdapter.applyLevelShape(bytes, false)),
            // Forge 1.20.1 SRG names of positionRider(Entity) and positionRider(Entity, MoveFunction).
            new Repair("LegacyPositionRiderAdapter", "26.2", name -> emptyOverride(name,
                    "net/minecraft/world/entity/Mob", "m_7332_", "(Lnet/minecraft/world/entity/Entity;)V"),
                    bytes -> invoke("com.retromod.shim.common.LegacyPositionRiderAdapter", "apply",
                            new Class<?>[]{byte[].class, String.class, String.class}, bytes, "m_7332_", "m_19956_")),
            new Repair("LegacyRecipeSubclassAdapter", "26.2", RetromodOwnClassesTest::quietRecipe,
                    LegacyRecipeSubclassAdapter::apply),
            new Repair("LegacyDamageConstantBridge", "26.2", name -> staticMethod(name, "fire",
                    "()Lnet/minecraft/world/damagesource/DamageSource;", mv -> {
                        mv.visitFieldInsn(GETSTATIC, "net/minecraft/world/damagesource/DamageSource", "IN_FIRE",
                                "Lnet/minecraft/world/damagesource/DamageSource;");
                        mv.visitInsn(ARETURN);
                    }), LegacyDamageConstantBridge::apply),
            new Repair("LegacyBlockApiAdapter", "26.2", name -> staticMethod(name, "item",
                    "(L" + BLOCK + ";L" + PROPERTIES + ";)Ljava/lang/Object;", mv -> {
                        mv.visitTypeInsn(NEW, "net/minecraft/world/item/BlockItem");
                        mv.visitInsn(DUP);
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/item/BlockItem", "<init>",
                                "(L" + BLOCK + ";L" + PROPERTIES + ";)V", false);
                        mv.visitInsn(ARETURN);
                    }), LegacyBlockApiAdapter::apply),
            new Repair("LegacyVillagerBridge", "26.2", name -> staticMethod(name, "workstation",
                    "(Lnet/minecraftforge/registries/RegistryObject;)Ljava/lang/Object;", mv -> {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/registries/RegistryObject", "get",
                                "()Ljava/lang/Object;", false);
                        mv.visitTypeInsn(CHECKCAST, "net/minecraft/world/entity/ai/village/poi/PoiType");
                        mv.visitInsn(ARETURN);
                    }), LegacyVillagerBridge::apply),
            new Repair("LegacyRecipeSerializerFlowAdapter", "26.2", name -> staticMethod(name, "make",
                    "()Lnet/minecraft/world/item/crafting/RecipeSerializer;", mv -> {
                        mv.visitTypeInsn(NEW, TEST_MOD + "MillingSerializer");
                        mv.visitInsn(DUP);
                        mv.visitMethodInsn(INVOKESPECIAL, TEST_MOD + "MillingSerializer", "<init>", "()V", false);
                        mv.visitInsn(ARETURN);
                    }), LegacyRecipeSerializerFlowAdapter::apply),
            new Repair("LegacyKeyCodeAdapter", "26.3", name -> staticMethod(name, "key",
                    "(Lcom/mojang/blaze3d/platform/InputConstants$Type;)Ljava/lang/Object;", mv -> {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitIntInsn(BIPUSH, 82);
                        mv.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/blaze3d/platform/InputConstants$Type",
                                "getOrCreate", "(I)Lcom/mojang/blaze3d/platform/InputConstants$Key;", false);
                        mv.visitInsn(ARETURN);
                    }), LegacyKeyCodeAdapter::apply),
            new Repair("LegacyEntityApi120Adapter", "1.20.1", name -> classWithMethod(name, TAMABLE, ACC_PUBLIC,
                    "getControllingPassenger", "()Lnet/minecraft/world/entity/Entity;", mv -> {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitInsn(ARETURN);
                    }), bytes -> LegacyEntityApi120Adapter.apply(bytes, RetromodTransformer.getInstance(),
                            Map.<String, byte[]>of()::get)),
            new Repair("LegacyRandomSourceAdapter", "1.20.1", name -> readerOf(name, "random", "Ljava/util/Random;"),
                    bytes -> LegacyRandomSourceAdapter.apply(bytes, RetromodTransformer.getInstance(),
                            Map.of(DRAGON, dragon())::get)),
            new Repair("LegacyEntityPrivateFieldAdapter", "1.20.1",
                    name -> readerOf(name, "level", "Lnet/minecraft/world/level/Level;"),
                    bytes -> LegacyEntityPrivateFieldAdapter.apply(bytes, RetromodTransformer.getInstance(),
                            Map.of(DRAGON, dragon())::get)),
            new Repair("LegacyCreativeTabClaimAdapter", "1.19.3", RetromodOwnClassesTest::taggedBlockItem,
                    LegacyCreativeTabClaimAdapter::apply),
            new Repair("ConstructorIdStamper", "26.2", RetromodOwnClassesTest::torch,
                    bytes -> ConstructorIdStamper.apply(bytes, "com/retromod/shim/forge/embedded/RegistryIdBridge")),
            new Repair("HostFinalOverrideAdapter", "26.2", name -> classWithMethod(name, "host/Base", ACC_PUBLIC,
                    "name", "()Ljava/lang/String;", mv -> {
                        mv.visitLdcInsn("mod");
                        mv.visitInsn(ARETURN);
                    }), bytes -> HostFinalOverrideAdapter.apply(bytes, name -> name.equals("host/Base")
                            ? new HostFinalOverrideAdapter.Shape("java/lang/Object",
                                    Map.of("name()Ljava/lang/String;", ACC_PUBLIC | ACC_FINAL), true, false)
                            : null)),
            new Repair("Forge1122ServiceStubs", "1.20.1", name -> classWithMethod(name, CONFIGURATION, ACC_PUBLIC,
                    "radius", "()Ljava/lang/String;", mv -> {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitLdcInsn("general");
                        mv.visitMethodInsn(INVOKEVIRTUAL, name, "getCategory", "(Ljava/lang/String;)Ljava/lang/Object;",
                                false);
                        mv.visitInsn(POP);
                        mv.visitInsn(ACONST_NULL);
                        mv.visitInsn(ARETURN);
                    }), RetromodOwnClassesTest::eraseInheritedConfigCalls));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repairs")
    @DisplayName("a test-mod class is repaired like any mod's, and Retromod's embedded copy is left alone")
    void testModClassesAreRepaired(Repair repair) {
        RetromodVersion.TARGET_MC_VERSION = repair.host();
        transformer.clearRedirectsForTesting();
        byte[] testMod = repair.fixture().apply(TEST_MOD + "Fixture");
        byte[] retromodCopy = repair.fixture().apply(EMBEDDED + "Fixture");

        assertFalse(Arrays.equals(testMod, repair.repair().apply(testMod)),
                repair.name() + " skipped a class under com/retromod/testmod/, so the test mods never saw it");
        assertArrayEquals(retromodCopy, repair.repair().apply(retromodCopy),
                repair.name() + " changed Retromod's own embedded class");
    }

    @Test
    @DisplayName("Retromod's own packages are its own; the test mods and the test library are mods")
    void ownPackagesDecideIt() {
        for (String own : List.of("com/retromod/core/Retromod", "com/retromod/shim/forge/embedded/LegacyConfiguration",
                "com/retromod/generated/LegacyEquipmentTier", "com/retromod/legacyapi/forge/eventbus/LegacyEvent",
                "com/retromod/polyfill/minecraft/RetroKeyMapping", "com/retromod/legacy/shim/VirtualMod",
                "com/retromod/boot/A", EMBEDDED + "Fixture")) {
            assertTrue(SyntheticEmbedder.isRetromodClass(own), own);
        }
        for (String mod : List.of("com/retromod/testmod/forge/OldShapeCases", "com/retromod/testmod121/tests/T",
                "com/retromod/testlib/forge/RetromodTestLibForge", "com/retromod/Loose", "mod/Fixture")) {
            assertFalse(SyntheticEmbedder.isRetromodClass(mod), mod);
        }
    }

    @Test
    @DisplayName("every package Retromod compiles is listed as its own, so a new one cannot pass as a mod")
    void everyCompiledPackageIsListed() throws Exception {
        Path classes = Path.of(SyntheticEmbedder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path root = classes.resolve("com/retromod");
        assertTrue(Files.isDirectory(root), "expected compiled classes under " + root);
        List<String> missing = new ArrayList<>();
        try (Stream<Path> packages = Files.list(root)) {
            packages.filter(Files::isDirectory).map(dir -> dir.getFileName().toString())
                    .filter(name -> !SyntheticEmbedder.OWN_PACKAGES.contains(name)).forEach(missing::add);
        }
        assertEquals(List.of(), missing, "add these to SyntheticEmbedder.OWN_PACKAGES");
    }

    @Test
    @DisplayName("a test-mod type counts as a mod's own for the inherited field and library field repairs")
    void testModTypesAreModOwned() throws Exception {
        Method modOwned = RetromodTransformer.class.getDeclaredMethod("isModOwnedType", String.class);
        modOwned.setAccessible(true);
        assertEquals(true, modOwned.invoke(null, TEST_MOD + "Fixture"));
        assertEquals(true, modOwned.invoke(null, "com/retromod/testlib/forge/RetromodTestLibForge"));
        assertEquals(false, modOwned.invoke(null, "com/retromod/core/RetromodErrorHandler"));
        assertEquals(false, modOwned.invoke(null, EMBEDDED + "Fixture"));
    }

    @Test
    @DisplayName("the spawn egg and armor texture scans read test-mod classes and skip Retromod's")
    void resourceScansReadTestModClasses() throws Exception {
        Method retromodClass = Class.forName("com.retromod.resources.ClassFlow")
                .getDeclaredMethod("isRetromodClass", String.class);
        retromodClass.setAccessible(true);
        assertEquals(false, retromodClass.invoke(null, TEST_MOD + "ModItems.class"));
        assertEquals(true, retromodClass.invoke(null, EMBEDDED + "LegacySpawnEgg.class"));
        assertEquals(true, retromodClass.invoke(null, "META-INF/versions/21/com/retromod/shim/X.class"));
    }

    /** {@code public <name>(...) { return; }} on a subclass of {@code superName}. */
    private static byte[] emptyOverride(String name, String superName, String method, String desc) {
        return classWithMethod(name, superName, ACC_PUBLIC, method, desc, mv -> mv.visitInsn(RETURN));
    }

    private static byte[] staticMethod(String name, String method, String desc, Consumer<MethodVisitor> body) {
        return classWithMethod(name, "java/lang/Object", ACC_PUBLIC | ACC_STATIC, method, desc, body);
    }

    private static byte[] classWithMethod(String name, String superName, int access, String method, String desc,
            Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        MethodVisitor mv = cw.visitMethod(access, method, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Object read(Dragon d) { return d.<field>; }}, a dragon field read from another class. */
    private static byte[] readerOf(String name, String field, String desc) {
        return staticMethod(name, "read", "(L" + DRAGON + ";)Ljava/lang/Object;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, DRAGON, field, desc);
            mv.visitInsn(ARETURN);
        });
    }

    private static byte[] dragon() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, DRAGON, null, TAMABLE, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A shaped recipe built with the 1.21.1 constructor that set showNotification. */
    private static byte[] quietRecipe(String name) {
        String crafting = "net/minecraft/world/item/crafting/";
        String fields = "Ljava/lang/String;L" + crafting + "CraftingBookCategory;L" + crafting
                + "ShapedRecipePattern;Lnet/minecraft/world/item/ItemStack;";
        return classWithMethod(name, crafting + "ShapedRecipe", ACC_PUBLIC, "<init>", "(" + fields + ")V", mv -> {
            for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(ALOAD, slot);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, crafting + "ShapedRecipe", "<init>", "(" + fields + "Z)V", false);
            mv.visitInsn(RETURN);
        });
    }

    /** A 1.19.2 {@code new BlockItem(block, new Properties().tab(tab))}, after the tab redirect. */
    private static byte[] taggedBlockItem(String name) {
        String tab = "net/minecraft/world/item/CreativeModeTab";
        return staticMethod(name, "block", "(L" + BLOCK + ";L" + tab + ";)L" + ITEM + ";", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/world/item/BlockItem");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitTypeInsn(NEW, PROPERTIES);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, PROPERTIES, "<init>", "()V", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "com/retromod/generated/LegacyCreativeModeTab", "tab",
                    "(L" + PROPERTIES + ";L" + tab + ";)L" + PROPERTIES + ";", false);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/item/BlockItem", "<init>",
                    "(L" + BLOCK + ";L" + PROPERTIES + ";)V", false);
            mv.visitInsn(ARETURN);
        });
    }

    /** ProjectE's torch: a Minecraft block constructor taking the mod's properties. */
    private static byte[] torch(String name) {
        String torch = "net/minecraft/world/level/block/TorchBlock";
        String properties = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
        return classWithMethod(name, torch, ACC_PUBLIC, "<init>", "(" + properties + ")V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, torch, "<init>",
                    "(Lnet/minecraft/core/particles/SimpleParticleType;" + properties + ")V", false);
            mv.visitInsn(RETURN);
        });
    }

    /** Runs the 1.12 stand-in erasure on every call, with the class as the jar in scope, as the post-remap pass does. */
    private static byte[] eraseInheritedConfigCalls(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        try (var scope = RetromodTransformer.getInstance()
                .pushJarClassBytesProvider(name -> name.equals(node.name) ? classBytes : null)) {
            Method rewrite = Class.forName("com.retromod.shim.forge.Forge1122ServiceStubs")
                    .getDeclaredMethod("rewriteCall", MethodNode.class, MethodInsnNode.class);
            rewrite.setAccessible(true);
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode call) changed |= (boolean) rewrite.invoke(null, method, call);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Calls a package-private static repair by reflection. */
    private static byte[] invoke(String owner, String method, Class<?>[] types, Object... args) {
        try {
            Method repair = Class.forName(owner).getDeclaredMethod(method, types);
            repair.setAccessible(true);
            return (byte[]) repair.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
