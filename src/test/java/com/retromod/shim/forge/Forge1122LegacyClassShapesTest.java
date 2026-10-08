/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import com.retromod.shim.forge.embedded.LegacyReflectionHelper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 1.12.2 class shapes outside the registry bridge: custom potions, FML {@code @Optional},
 * custom materials and the reflection helpers.
 */
class Forge1122LegacyClassShapesTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
    }

    private static void bridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
    }

    private static ClassNode rewritten(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(Forge1122EventBridge.rewriteLegacyCalls(bytes)).accept(cn, 0);
        return cn;
    }

    /** {@code class Curse extends Potion { Curse() { super(true, 0x123456); setPotionName(..); } }} */
    private static byte[] potion() {
        return potion("net/minecraft/world/entity/ai/attributes/Attributes", "MOVEMENT_SPEED");
    }

    /** The same potion, with the attribute read from {@code attributeOwner.attributeField}. */
    private static byte[] potion(String attributeOwner, String attributeField) {
        String effect = "net/minecraft/world/effect/MobEffect";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Curse", null, effect, null);
        var ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitInsn(ICONST_1);
        ctor.visitLdcInsn(0x123456);
        ctor.visitMethodInsn(INVOKESPECIAL, effect, "<init>", "(ZI)V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitLdcInsn("potion.curse");
        ctor.visitMethodInsn(INVOKEVIRTUAL, "test/Curse", "func_76390_b",
                "(Ljava/lang/String;)L" + effect + ";", false);
        ctor.visitInsn(POP);
        // registerPotionAttributeModifier(SharedMonsterAttributes.MOVEMENT_SPEED, uuid, 0.06, 2)
        String attribute = "Lnet/minecraft/world/entity/ai/attributes/Attribute;";
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitFieldInsn(GETSTATIC, attributeOwner, attributeField, attribute);
        ctor.visitLdcInsn("7BB42B36-75AC-448D-9BE2-B318E42D6898");
        ctor.visitLdcInsn(0.06D);
        ctor.visitInsn(ICONST_2);
        ctor.visitMethodInsn(INVOKEVIRTUAL, "test/Curse", "func_111184_a",
                "(" + attribute + "Ljava/lang/String;DI)L" + effect + ";", false);
        ctor.visitInsn(POP);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void potionSuperCallGetsAHostEffectCategory() {
        bridged();
        ClassNode cn = rewritten(potion());
        MethodNode ctor = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();

        MethodInsnNode superCall = null;
        boolean category = false;
        boolean modifier = false;
        String expectedAttribute = com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0
                ? "Lnet/minecraft/core/Holder;" : "Lnet/minecraft/world/entity/ai/attributes/Attribute;";
        for (AbstractInsnNode in : ctor.instructions.toArray()) {
            if (in instanceof org.objectweb.asm.tree.FieldInsnNode read) {
                assertEquals(expectedAttribute, read.desc,
                        "the attribute constant must be read with the host's type");
            }
            if (!(in instanceof MethodInsnNode mi)) continue;
            assertNotEquals("func_76390_b", mi.name, "setPotionName has no host equivalent and is dropped");
            assertNotEquals("func_111184_a", mi.name, "the 1.12 attribute modifier call has no host shape");
            if (mi.name.equals("effectAttribute")) modifier = true;
            if (mi.name.equals("effectCategory")) category = true;
            if (mi.name.equals("<init>")) superCall = mi;
        }
        assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(cn.name, ctor),
                "the rewritten calls must leave a balanced stack");
        assertTrue(modifier, "the speed modifier must reach the host's addAttributeModifier");
        assertTrue(category, "the 1.12 isBadEffect flag must become a MobEffectCategory");
        assertNotNull(superCall);
        assertEquals("(Lnet/minecraft/world/effect/MobEffectCategory;I)V", superCall.desc);
    }

    @Test
    void modOwnedAttributeIsNotRetypedOnHolderHosts() {
        String previous = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        try {
            bridged();
            ClassNode cn = rewritten(potion("test/ModAttributes", "VOID_RESISTANCE"));
            MethodNode ctor = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
            for (AbstractInsnNode in : ctor.instructions.toArray()) {
                if (in instanceof org.objectweb.asm.tree.FieldInsnNode read) {
                    assertEquals("Lnet/minecraft/world/entity/ai/attributes/Attribute;", read.desc,
                            "a mod's own attribute field keeps its declared type");
                }
                if (in instanceof MethodInsnNode mi) {
                    assertNotEquals("effectAttribute", mi.name,
                            "a mod attribute cannot be passed as a host holder, so the call is dropped");
                    assertNotEquals("func_111184_a", mi.name);
                }
            }
            assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(cn.name, ctor),
                    "dropping the call must leave a balanced stack");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previous;
        }
    }

    /** {@code class Snack extends ItemFood { Snack() { super(4, false); } }} after the remap. */
    private static byte[] foodItem(String superDesc) {
        String item = "net/minecraft/world/item/Item";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Snack", null, item, null);
        var ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitInsn(ICONST_4);
        if (superDesc.equals("(IFZ)V")) ctor.visitLdcInsn(0.3F);
        ctor.visitInsn(ICONST_0);
        ctor.visitMethodInsn(INVOKESPECIAL, item, "<init>", superDesc, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void foodItemsBuildTheirHostFoodProperties() {
        bridged();
        for (String legacy : List.of("(IFZ)V", "(IZ)V")) {
            ClassNode cn = rewritten(foodItem(legacy));
            MethodNode ctor = cn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
            List<String> calls = java.util.Arrays.stream(ctor.instructions.toArray())
                    .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                    .map(mi -> mi.name + mi.desc).toList();
            assertEquals(List.of("foodItemProperties(IFZ)Ljava/lang/Object;",
                            "<init>(Lnet/minecraft/world/item/Item$Properties;)V"), calls,
                    "ItemFood" + legacy + " must become Item(Properties) with food values");
            assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(cn.name, ctor),
                    "the food values must be consumed exactly");
        }
    }

    private static final String OPTIONAL = "Lnet/minecraftforge/fml/common/Optional$";

    /**
     * A day handler that implements Morpheus's API and Tough As Nails hooks, both optional, plus a
     * method marked optional on Forge itself, which is always present.
     */
    private static byte[] optionalIntegrations() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/DayHandler", null, "java/lang/Object",
                new String[]{"net/quetzi/morpheus/api/INewDayHandler", "toughasnails/api/IHeat",
                        "java/lang/Runnable"});
        AnnotationVisitor single = cw.visitAnnotation(OPTIONAL + "Interface;", true);
        single.visit("iface", "net.quetzi.morpheus.api.INewDayHandler");
        single.visit("modid", "morpheus");
        single.visit("striprefs", true);
        single.visitEnd();
        AnnotationVisitor list = cw.visitAnnotation(OPTIONAL + "InterfaceList;", true);
        AnnotationVisitor entries = list.visitArray("value");
        AnnotationVisitor heat = entries.visitAnnotation(null, OPTIONAL + "Interface;");
        heat.visit("iface", "toughasnails.api.IHeat");
        heat.visit("modid", "toughasnails");
        heat.visitEnd();
        entries.visitEnd();
        list.visitEnd();
        method(cw, "startNewDay", "toughasnails");
        method(cw, "run", null);
        method(cw, "forgeHook", "forge");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void method(ClassWriter cw, String name, String optionalModId) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()V", null, null);
        if (optionalModId != null) {
            AnnotationVisitor a = mv.visitAnnotation(OPTIONAL + "Method;", true);
            a.visit("modid", optionalModId);
            a.visitEnd();
        }
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @Test
    void optionalInterfacesAndMethodsAreStrippedLikeFmlDid() {
        bridged();
        ClassNode cn = rewritten(optionalIntegrations());
        assertEquals(List.of("java/lang/Runnable"), cn.interfaces,
                "optional interfaces from other mods' 1.12 APIs would fail the class load");
        List<String> methods = cn.methods.stream().map(m -> m.name).toList();
        assertFalse(methods.contains("startNewDay"));
        assertTrue(methods.contains("run"), "unmarked methods stay");
        assertTrue(methods.contains("forgeHook"), "Forge and Minecraft always count as present");
    }

    @Test
    void classesWithoutLegacyMarkersPassThroughUnchanged() {
        bridged();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Modern", null, "java/lang/Object",
                new String[]{"java/lang/Runnable"});
        method(cw, "run", null);
        cw.visitEnd();
        byte[] modern = cw.toByteArray();
        assertSame(modern, Forge1122EventBridge.rewriteLegacyCalls(modern));
    }

    @Test
    void customMaterialsExtendALoadableStandIn() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122ServiceStubs.register(t);
        String material = Forge1122ServiceStubs.LEGACY_MATERIAL;
        assertTrue(t.getSyntheticClasses().containsKey(material),
                "a mod's own Material subclass needs a superclass that exists");

        ClassNode standIn = new ClassNode();
        new ClassReader(t.getSyntheticClasses().get(material)).accept(standIn, 0);
        assertTrue(standIn.methods.stream().anyMatch(m -> m.name.equals("<init>")
                && m.desc.equals("(Lnet/minecraft/world/level/material/MapColor;)V")),
                "the 1.12 constructor took the (remapped) map color");

        ClassNode liquid = new ClassNode();
        new ClassReader(t.getSyntheticClasses().get("net/minecraft/block/material/MaterialLiquid"))
                .accept(liquid, 0);
        assertEquals(material, liquid.superName);
    }

    static class Holder {
        private int secret = 7;
    }

    @Test
    void reflectionLookupsFindModMembersAndReturnNullForMissingOnes() {
        assertNotNull(LegacyReflectionHelper.findField(Holder.class, "field_123_a", "secret"),
                "the first candidate name that exists wins");
        assertEquals(7, LegacyReflectionHelper.getPrivateValue(Holder.class, new Holder(), "secret"));
        assertNull(LegacyReflectionHelper.findMethod(Holder.class, "onFoodEaten", "func_77849_c"),
                "a 1.12-only name must not fail the static initializer that caches it");
        assertThrows(IllegalStateException.class,
                () -> LegacyReflectionHelper.getPrivateValue(Holder.class, new Holder(), "field_999_x"));
    }

    @Test
    void singleNameObfuscationHelperCallsLink() throws Exception {
        // The exact descriptors AbyssalCraft 1.12.2 calls; a varargs String[] form would not link.
        assertEquals(java.lang.reflect.Field.class, LegacyReflectionHelper.class
                .getMethod("findField", Class.class, String.class).getReturnType());
        assertNotNull(LegacyReflectionHelper.class.getMethod("setPrivateValue",
                Class.class, Object.class, Object.class, String.class));
        assertNotNull(LegacyReflectionHelper.class.getMethod("getPrivateValue",
                Class.class, Object.class, String.class));

        Holder holder = new Holder();
        LegacyReflectionHelper.setPrivateValue(Holder.class, holder, 9, "secret");
        assertEquals(9, LegacyReflectionHelper.getPrivateValue(Holder.class, holder, "secret"));
        assertNull(LegacyReflectionHelper.findField(Holder.class, "field_70170_p"));
    }

    @Test
    void baseStateReadMatchesTheGenericHostDescriptor() {
        bridged();
        String definition = "net/minecraft/world/level/block/state/StateDefinition";
        String state = "net/minecraft/world/level/block/state/BlockState";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Mirror", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "base", "(L" + definition + ";)L" + state + ";",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, definition, "any", "()L" + state + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = rewritten(cw.toByteArray());
        MethodNode base = cn.methods.stream().filter(m -> m.name.equals("base")).findFirst().orElseThrow();
        MethodInsnNode any = null;
        for (AbstractInsnNode in : base.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi) any = mi;
        }
        assertNotNull(any);
        assertEquals(Forge1122EventBridge.EVENTS, any.owner,
                "StateDefinition.any() erases to StateHolder and runs as m_61090_ on Forge 1.20.1, "
                        + "so neither the remapped descriptor nor the Mojang name links everywhere");
        assertEquals("baseState", any.name);
        assertEquals(INVOKESTATIC, any.getOpcode());
        assertEquals(CHECKCAST, any.getNext().getOpcode(), "the result is cast back to BlockState");
    }
}
