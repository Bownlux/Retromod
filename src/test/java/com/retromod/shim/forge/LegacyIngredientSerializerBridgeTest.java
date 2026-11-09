/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.retromod.shim.forge.IngredientFixtures.*;
import static com.retromod.shim.forge.LegacyIngredientSerializerBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1's ingredient serializers and AbstractIngredient on Forge 26.2 and 26.3, which use codecs. */
class LegacyIngredientSerializerBridgeTest {

    private static final String SERIALIZER_ARG = "L" + SERIALIZER + ";";
    private static final String CODEC = "com/retromod/legacyapi/forge/crafting/LegacyIngredientCodec";
    private static final String STREAM_CTOR = "(Ljava/util/stream/Stream;)V";
    // What a running Forge 1.21.11, 26.1, 26.2 or 26.3 host shows the probes: no CraftingHelper or old serializer.
    private static final Map<String, ClassNode> RUNNING_FORGE = Map.of(ForgeHostShape.ANCHOR, new ClassNode(),
            HOST_SERIALIZER, new ClassNode(), HOST_ABSTRACT, new ClassNode(), BUS_GROUP, new ClassNode());
    private static final String ID_ARG = "Lnet/minecraft/resources/Identifier;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The 26.2 shim moves the old serializer, base and value, and CraftingHelper gains register and getID")
    void registersOnForge262AndNewer(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(SERIALIZER, redirects.get(OLD_SERIALIZER));
        assertEquals(BASE, redirects.get(OLD_ABSTRACT));
        assertEquals(VALUE, redirects.get(OLD_VALUE));
        ClassNode base = node(transformer.getSyntheticClasses().get(BASE));
        assertEquals(HOST_ABSTRACT, base.superName, "the generated base extends Forge 26.2's AbstractIngredient");
        for (String rebuilt : List.of(SERIALIZER, SERIALIZERS, VALUE, CODEC)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(rebuilt), rebuilt + " is embedded");
        }
        ClassNode helper = node(transformer.getSyntheticClasses().get(CRAFTING_HELPER));
        assertTrue(declares(helper, "register", "(" + ID_ARG + SERIALIZER_ARG + ")" + SERIALIZER_ARG));
        assertTrue(declares(helper, "getID", "(" + SERIALIZER_ARG + ")" + ID_ARG));
        assertTrue(declares(helper, "register",
                "(Lnet/minecraftforge/common/crafting/conditions/IConditionSerializer;)"
                        + "Lnet/minecraftforge/common/crafting/conditions/IConditionSerializer;"),
                "the condition half stays");
    }

    @Test
    @DisplayName("A NeoForge target and a Forge 1.21.11 target keep CraftingHelper's condition half only")
    void otherTargetsAreUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_SERIALIZER));

        transformer.clearRedirectsForTesting();
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        new Forge_1_21_10_to_1_21_11().registerRedirects(transformer);
        ClassNode helper = node(transformer.getSyntheticClasses().get(CRAFTING_HELPER));
        assertFalse(declaresName(helper, "getID"), "Forge 1.21.11 gets no ingredient half");
        assertNull(transformer.getClassRedirects().get(OLD_SERIALIZER));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.21.11", "26.1"})
    @DisplayName("A running Forge 1.21.11 or 26.1 host has the same classes as 26.2 and still takes no bridge")
    void olderHostsWithTheSameClassesAreLeftAlone(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        assertFalse(LegacyIngredientSerializerBridge.applies(RUNNING_FORGE::get), "Forge " + host + " has BusGroup");
        LegacyIngredientSerializerBridge.register(transformer, RUNNING_FORGE::get);
        assertNull(transformer.getClassRedirects().get(OLD_SERIALIZER));

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(LegacyIngredientSerializerBridge.applies(RUNNING_FORGE::get));
    }

    @Test
    @DisplayName("A Forge host that still has the old serializer takes no part of the bridge")
    void hostWithTheOldSerializerIsLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Map<String, ClassNode> withOld = new HashMap<>(RUNNING_FORGE);
        withOld.put(OLD_SERIALIZER, new ClassNode());
        LegacyIngredientSerializerBridge.register(transformer, withOld::get);
        assertNull(transformer.getClassRedirects().get(OLD_SERIALIZER));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.21.11", "26.1", "26.2", "26.3"})
    @DisplayName("CraftingHelper's stand-in and what it brings are the same in game and offline")
    void standInMatchesOffline(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        ForgeNeoForgeSynthetics.registerForgeConditionStandIns(transformer, RUNNING_FORGE::get);
        Map<String, byte[]> inGame = new HashMap<>(transformer.getSyntheticClasses());
        transformer.clearRedirectsForTesting();
        ForgeNeoForgeSynthetics.registerForgeConditionStandIns(transformer, name -> null);
        Map<String, byte[]> offline = transformer.getSyntheticClasses();

        assertEquals(new TreeSet<>(offline.keySet()), new TreeSet<>(inGame.keySet()));
        assertArrayEquals(offline.get(CRAFTING_HELPER), inGame.get(CRAFTING_HELPER));
        boolean newer = RetromodVersion.compareMcVersions(host, "26.2") >= 0;
        assertEquals(newer, declaresName(node(inGame.get(CRAFTING_HELPER)), "getID"));
    }

    @Test
    @DisplayName("An old serializer implements the rebuilt one and keeps its old read and write")
    void oldSerializerImplementsTheRebuiltOne() throws Exception {
        ClassNode serializer = transformed(ITEM_SERIALIZER);
        assertEquals(List.of(SERIALIZER), serializer.interfaces);
        assertTrue(declares(serializer, "parse", "(Lcom/google/gson/JsonObject;)L" + INGREDIENT + ";"));
        assertTrue(declares(serializer, "write",
                "(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/item/crafting/Ingredient;)V"));
    }

    @Test
    @DisplayName("An old ingredient extends the generated base, which drops the old values, and has serializer()")
    void oldIngredientAnswersSerializer() throws Exception {
        ClassNode ingredient = transformed(ITEM_INGREDIENT);
        assertEquals(BASE, ingredient.superName);
        MethodInsnNode superCall = call(method(ingredient, "<init>"), INVOKESPECIAL, "<init>");
        assertEquals(BASE + ".<init>" + STREAM_CTOR, superCall.owner + "." + superCall.name + superCall.desc);
        MethodNode absorbed = method(node(transformer.getSyntheticClasses().get(BASE)), "<init>", STREAM_CTOR);
        MethodInsnNode forwarded = call(absorbed, INVOKESPECIAL, "<init>");
        assertEquals(HOST_ABSTRACT + ".<init>()V", forwarded.owner + "." + forwarded.name + forwarded.desc);

        MethodNode bridge = method(ingredient, HOST_GETTER, HOST_GETTER_DESC);
        MethodInsnNode getter = call(bridge, INVOKEVIRTUAL, GET_SERIALIZER);
        assertEquals(ITEM_INGREDIENT + "." + GET_SERIALIZER + GET_SERIALIZER_DESC,
                getter.owner + "." + getter.name + getter.desc);
    }

    @Test
    @DisplayName("An old ingredient that extends Ingredient itself answers serializer() too")
    void directIngredientAnswersSerializer() throws Exception {
        ClassNode direct = transformed(DIRECT_INGREDIENT);
        assertEquals(INGREDIENT, direct.superName);
        assertNotNull(method(direct, HOST_GETTER, HOST_GETTER_DESC));
    }

    @Test
    @DisplayName("A mixin, a class with its own serializer() and one without the old getter keep their methods")
    void otherClassesAreLeftAlone() {
        byte[] mixin = getterClass("mod/IngredientMixin", true, false);
        assertSame(mixin, LegacyIngredientSerializerBridge.apply(mixin), "a mixin's members would land on Ingredient");
        byte[] declared = getterClass("mod/OwnSerializer", false, true);
        assertSame(declared, LegacyIngredientSerializerBridge.apply(declared));
        byte[] plain = CapabilityHostFixtures.plainClass("mod/Plain", "java/lang/Object");
        assertSame(plain, LegacyIngredientSerializerBridge.apply(plain));
        assertNotSame(getterClass("mod/Bridged", false, false),
                LegacyIngredientSerializerBridge.apply(getterClass("mod/Bridged", false, false)));
    }

    @Test
    @DisplayName("Old CraftingHelper calls land on the stand-in methods with the same descriptors")
    void craftingHelperCallsReachTheStandIn() throws Exception {
        ClassNode ingredient = transformed(ITEM_INGREDIENT);
        ClassNode helper = node(transformer.getSyntheticClasses().get(CRAFTING_HELPER));
        int calls = 0;
        for (MethodNode method : ingredient.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(CRAFTING_HELPER)) {
                    assertTrue(declares(helper, call.name, call.desc), call.name + call.desc + " is on the stand-in");
                    calls++;
                }
            }
        }
        assertEquals(5, calls, "register three times and getID twice");
    }

    @Test
    @DisplayName("Each @Mod constructor of a jar that names the old serializer starts the registration listener")
    void constructorHookPlansOnlyJarsThatNameTheSerializer() throws Exception {
        Map<String, byte[]> naming = Map.of(ITEM_SERIALIZER, oldModClass(ITEM_SERIALIZER));
        ModConstructorHooks.JarPlan plan = LegacyIngredientSerializerBridge.plan(naming, Map.of());
        assertNotNull(plan);
        ModConstructorHooks.Call call = plan.callsFor("mod/Mod", "oldmod").get(0);
        assertEquals(SERIALIZERS + ".watch()V", call.owner() + "." + call.name() + call.desc());
        assertNull(LegacyIngredientSerializerBridge.plan(Map.of("mod/Plain",
                CapabilityHostFixtures.plainClass("mod/Plain", "java/lang/Object")), Map.of()));
    }

    // ---- helpers ----

    private ClassNode transformed(String name) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        return node(transformer.transformClass(oldModClass(name), name + ".class"));
    }

    /** A class with a concrete old getSerializer(), optionally a mixin or one with its own serializer(). */
    private static byte[] getterClass(String name, boolean mixin, boolean ownSerializer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, INGREDIENT, null);
        if (mixin) {
            AnnotationVisitor annotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            annotation.visitEnd();
        }
        returnNull(cw, GET_SERIALIZER, GET_SERIALIZER_DESC);
        if (ownSerializer) returnNull(cw, HOST_GETTER, HOST_GETTER_DESC);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void returnNull(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static boolean declaresName(ClassNode node, String name) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
    }

    private static MethodInsnNode call(MethodNode method, int opcode, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode && call.name.equals(name)) {
                return call;
            }
        }
        throw new AssertionError(method.name + " makes no " + name + " call");
    }
}
