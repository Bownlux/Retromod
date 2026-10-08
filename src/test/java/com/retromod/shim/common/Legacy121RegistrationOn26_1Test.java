/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.FabricLegacyRegistrationApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Registration code a 1.21.1 mod runs in its entry point that 26.1 reshaped, taken from
 * Sophisticated Core and Backpacks on Fabric 26.1.2 (#249): recipe serializer classes, the moved
 * vanilla serializer constants, cauldron interaction tables, {@code BlockEntityType.Builder}, and
 * Fabric API resource conditions.
 */
public class Legacy121RegistrationOn26_1Test {

    private static final String RECIPE_SERIALIZER = "net/minecraft/world/item/crafting/RecipeSerializer";
    private static final String RESOURCE_CONDITION =
            "net/fabricmc/fabric/api/resource/conditions/v1/ResourceCondition";
    private static final String CAULDRON = "net/minecraft/core/cauldron/CauldronInteraction";

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
    @DisplayName("A mod recipe serializer stops implementing the class 26.1 made of RecipeSerializer")
    void recipeSerializerInterfaceIsDropped() {
        new LegacyRecipeSerializerShim().registerRedirects(transformer);
        ClassNode out = transformedClass("test/ModSerializer",
                new String[]{RECIPE_SERIALIZER, "java/lang/Runnable"});

        assertFalse(out.interfaces.contains(RECIPE_SERIALIZER),
                "a class cannot implement RecipeSerializer on 26.1, it is a final class there");
        assertTrue(out.interfaces.contains("java/lang/Runnable"),
                "unrelated interfaces must survive the rewrite");
        assertEquals("Ljava/lang/Object;", out.signature,
                "the generic signature must drop the interface and keep the rest");
    }

    @Test
    @DisplayName("Dropping RecipeSerializer keeps the class type variables and generic superclass")
    void droppedInterfaceKeepsGenericSignature() {
        new LegacyRecipeSerializerShim().registerRedirects(transformer);
        ClassNode out = transformedClass("test/GenericSerializer", "test/Base",
                "<T:Ljava/lang/Object;>Ltest/Base<TT;>;L" + RECIPE_SERIALIZER
                        + "<TT;>;Ljava/util/function/Supplier<TT;>;",
                new String[]{RECIPE_SERIALIZER, "java/util/function/Supplier"});

        assertEquals("<T:Ljava/lang/Object;>Ltest/Base<TT;>;Ljava/util/function/Supplier<TT;>;",
                out.signature, "method signatures still name T, so the class must keep declaring it");
    }

    @Test
    @DisplayName("A rebased class still has its interfaces rewritten")
    void interfaceRewriteAppliesToRebasedClass() {
        new FabricLegacyRegistrationApiShim().registerRedirects(transformer);
        transformer.registerSuperclassRedirect("test/OldBase", "test/NewBase");
        ClassNode out = transformedClass("test/RebasedCondition", "test/OldBase",
                "Ltest/OldBase;L" + RESOURCE_CONDITION + ";", new String[]{RESOURCE_CONDITION});

        assertEquals("test/NewBase", out.superName, "the rebase itself must still apply");
        assertEquals(List.of(FabricLegacyRegistrationApiShim.LEGACY_CONDITION), out.interfaces,
                "the rebase path writes the header itself and must not skip the interface rewrite");
        assertTrue(out.signature.endsWith("L" + FabricLegacyRegistrationApiShim.LEGACY_CONDITION
                + ";") && !out.signature.contains(RESOURCE_CONDITION + ";"),
                "the signature must name the replacement interface, got: " + out.signature);
    }

    @Test
    @DisplayName("A Fabric resource condition implements the generated legacy subinterface")
    void resourceConditionGetsLegacySubinterface() {
        new FabricLegacyRegistrationApiShim().registerRedirects(transformer);
        ClassNode out = transformedClass("test/ModCondition", new String[]{RESOURCE_CONDITION});

        assertEquals(List.of(FabricLegacyRegistrationApiShim.LEGACY_CONDITION), out.interfaces,
                "the subinterface supplies the 26.1 test(RegistryInfoLookup) on top of the old one");
    }

    @Test
    @DisplayName("Vanilla serializer constants and cauldron tables read from their 26.1 owners")
    void movedConstantsAreReadFromNewOwners() {
        new LegacyRecipeSerializerShim().registerRedirects(transformer);
        new LegacyCauldronInteractionShim().registerRedirects(transformer);
        String oldMap = LegacyCauldronInteractionShim.OLD_MAP;
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitFieldInsn(GETSTATIC, RECIPE_SERIALIZER, "SHAPED_RECIPE",
                    "L" + RECIPE_SERIALIZER + ";");
            mv.visitInsn(POP);
            mv.visitFieldInsn(GETSTATIC, CAULDRON, "WATER", "L" + oldMap + ";");
            mv.visitMethodInsn(INVOKEVIRTUAL, oldMap, "map", "()Ljava/util/Map;", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        FieldInsnNode shaped = firstField(insns, "SERIALIZER");
        assertNotNull(shaped, "RecipeSerializer.SHAPED_RECIPE moved to ShapedRecipe.SERIALIZER");
        assertEquals("net/minecraft/world/item/crafting/ShapedRecipe", shaped.owner);
        FieldInsnNode water = firstField(insns, "WATER");
        assertNotNull(water);
        assertEquals(LegacyCauldronInteractionShim.TABLES, water.owner,
                "the cauldron tables moved to CauldronInteractions");
        MethodInsnNode map = firstCall(insns, LegacyCauldronInteractionShim.HELPER, "map");
        assertNotNull(map, "map() must read the dispatcher's live item map");
        assertEquals(INVOKESTATIC, map.getOpcode());
    }

    @Test
    @DisplayName("BlockEntityType.Builder.of becomes Fabric's builder through the supplier adapter")
    void blockEntityBuilderRoutesThroughFabricBuilder() {
        new FabricLegacyRegistrationApiShim().registerRedirects(transformer);
        String supplier = "net/minecraft/world/level/block/entity/BlockEntityType$BlockEntitySupplier";
        String block = "net/minecraft/world/level/block/Block";
        String ofDesc = "(L" + supplier + ";[L" + block + ";)"
                + "Lnet/minecraft/world/level/block/entity/BlockEntityType$Builder;";
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ICONST_0);
            mv.visitTypeInsn(ANEWARRAY, block);
            mv.visitMethodInsn(INVOKESTATIC,
                    "net/minecraft/world/level/block/entity/BlockEntityType$Builder", "of",
                    ofDesc, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        MethodInsnNode of = firstCall(insns, FabricLegacyRegistrationApiShim.BUILDER_HELPER, "of");
        assertNotNull(of, "Builder.of must route to the helper, 26.1 removed the vanilla builder");
        assertTrue(of.desc.startsWith("(L" + supplier + ";"),
                "the mod's supplier lambda is passed through unchanged");
    }

    @Test
    @DisplayName("The cauldron helper returns the dispatcher's own item map, so put() reaches it")
    void cauldronHelperReturnsLiveMap() throws Exception {
        Map<String, String> toStub = Map.of(LegacyCauldronInteractionShim.DISPATCHER,
                StubDispatcher.class.getName().replace('.', '/'));
        Class<?> helper = defineRemapped(LegacyCauldronInteractionShim.HELPER,
                LegacyCauldronInteractionShim.generateHelper(), toStub);
        StubDispatcher dispatcher = new StubDispatcher();

        Object map = helper.getMethod("map", Object.class).invoke(null, dispatcher);

        assertSame(dispatcher.items, map, "a copy would drop the mod's interactions");
    }

    @Test
    @DisplayName("The worldgen registration bridge adapts values bound for the recipe serializer registry")
    void registrationBridgeAdaptsRecipeSerializers() {
        ClassNode bridge = new ClassNode();
        new ClassReader(WorldgenTypeBridgeSynthetic.generate(false)).accept(bridge, 0);
        MethodNode convert = bridge.methods.stream()
                .filter(m -> m.name.equals("convert")).findFirst().orElseThrow();
        List<AbstractInsnNode> insns = new ArrayList<>();
        convert.instructions.forEach(insns::add);

        FieldInsnNode registry = firstField(insns, "RECIPE_SERIALIZER");
        assertNotNull(registry, "convert must recognize BuiltInRegistries.RECIPE_SERIALIZER");
        assertNotNull(firstCall(insns, LegacyRecipeSerializerShim.BRIDGE, "adapt"),
                "a legacy serializer must be adapted before it reaches the 26.1 registry");
    }

    @Test
    @DisplayName("Every generated class for these bridges passes ASM's structural checks")
    void generatedClassesAreWellFormed() {
        Map<String, byte[]> generated = new HashMap<>();
        generated.put("cauldron", LegacyCauldronInteractionShim.generateHelper());
        generated.put("recipe bridge", LegacyRecipeSerializerShim.generateBridge());
        generated.put("recipe codec", LegacyRecipeSerializerShim.generateSafeCodec());
        generated.put("simple serializer", LegacyRecipeSerializerShim.generateSimpleSerializer());
        generated.put("simple factory", LegacyRecipeSerializerShim.generateSimpleFactory());
        generated.put("category getter", LegacyRecipeSerializerShim.generateCategoryGetter());
        generated.put("builder helper", FabricLegacyRegistrationApiShim.generateBuilderHelper());
        generated.put("factory adapter", FabricLegacyRegistrationApiShim.generateFactoryAdapter());
        generated.put("legacy condition", FabricLegacyRegistrationApiShim.generateLegacyCondition());
        // Structure only: the data-flow check would need the Minecraft classes on the classpath.
        generated.forEach((name, bytes) -> assertDoesNotThrow(
                () -> new ClassReader(bytes).accept(
                        new CheckClassAdapter(new ClassWriter(0), false), 0),
                name + " failed structural verification"));
    }

    @Test
    @DisplayName("A recipe decoder that throws reports a recipe error instead of aborting the reload")
    void recipeCodecTrapsRuntimeExceptions() throws Exception {
        ClassNode codec = new ClassNode();
        new ClassReader(LegacyRecipeSerializerShim.generateSafeCodec()).accept(codec, 0);
        for (String name : new String[]{"decode", "encode"}) {
            MethodNode method = codec.methods.stream().filter(m -> m.name.equals(name))
                    .findFirst().orElseThrow();
            assertTrue(method.tryCatchBlocks.stream()
                            .anyMatch(b -> "java/lang/RuntimeException".equals(b.type)),
                    name + " must catch a runtime exception from the mod's codec, which is how a"
                            + " decoder that builds an ItemStack too early fails (#249)");
            new org.objectweb.asm.tree.analysis.Analyzer<>(
                    new org.objectweb.asm.tree.analysis.BasicVerifier())
                    .analyze(codec.name, method);
        }
    }

    /** Stand-in for the 26.1 CauldronInteraction$Dispatcher: a private items map. */
    public static final class StubDispatcher {
        @SuppressWarnings("unused")
        private final Map<Object, Object> items = new HashMap<>();
    }

    private Class<?> defineRemapped(String internalName, byte[] bytes, Map<String, String> mapping) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(mapping)), 0);
        byte[] remapped = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(internalName.replace('/', '.'), remapped, 0, remapped.length);
            }
        }.define();
    }

    private ClassNode transformedClass(String name, String[] interfaces) {
        return transformedClass(name, "java/lang/Object",
                "Ljava/lang/Object;L" + interfaces[0] + "<Ljava/lang/Object;>;", interfaces);
    }

    private ClassNode transformedClass(String name, String superName, String signature,
            String[] interfaces) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, signature, superName, interfaces);
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), name)).accept(node, 0);
        return node;
    }

    private List<AbstractInsnNode> transformBody(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Caller")).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("call")) m.instructions.forEach(insns::add);
        }
        return insns;
    }

    private static FieldInsnNode firstField(List<AbstractInsnNode> insns, String name) {
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof FieldInsnNode field && field.name.equals(name)) return field;
        }
        return null;
    }

    private static MethodInsnNode firstCall(List<AbstractInsnNode> insns, String owner,
            String name) {
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name)) {
                return call;
            }
        }
        return null;
    }
}
