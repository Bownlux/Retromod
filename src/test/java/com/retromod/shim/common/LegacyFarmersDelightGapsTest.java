/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Farmer's Delight 1.21.1's recipe serializers and wild crop features on a 26.3 host. */
class LegacyFarmersDelightGapsTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a recipe serializer's read of Ingredient.LIST_CODEC_NONEMPTY goes to the rebuilt codec, only where the host lacks it")
    void ingredientListCodecs() {
        transformer.clearRedirectsForTesting();
        LegacyIngredientCodecsBridge.register(transformer, host(Map.of(LegacyIngredientCodecsBridge.INGREDIENT,
                withFields(LegacyIngredientCodecsBridge.INGREDIENT, "CODEC"))));
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(readsField(), "mod/CookingPotSerializer.class")).accept(out, 0);
        MethodInsnNode call = firstCall(out);
        assertEquals(LegacyIngredientCodecsBridge.REBUILT, call.owner);
        assertEquals("listNonEmpty", call.name);

        transformer.clearRedirectsForTesting();
        LegacyIngredientCodecsBridge.register(transformer, host(Map.of(LegacyIngredientCodecsBridge.INGREDIENT,
                withFields(LegacyIngredientCodecsBridge.INGREDIENT, "CODEC", "LIST_CODEC_NONEMPTY"))));
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyIngredientCodecsBridge.REBUILT),
                "a host that still has the field keeps it");
    }

    @Test
    @DisplayName("RandomPatchConfiguration becomes the rebuilt class on 26.3, implementing the rebuilt configuration")
    void randomPatchConfiguration() {
        LegacyRandomPatchBridge.register(transformer, host(Map.of(LegacyRandomPatchBridge.FEATURE_CONFIGURATION,
                withFields(LegacyRandomPatchBridge.FEATURE_CONFIGURATION))), false);
        assertNull(transformer.getClassRedirects().get(LegacyRandomPatchBridge.OLD),
                "26.1 and 26.2 are LegacyRandomPatchApi's, which keeps the host's interface");

        transformer.clearRedirectsForTesting();
        LegacyRandomPatchBridge.register(transformer, host(Map.of()), true);
        assertEquals(LegacyRandomPatchBridge.REBUILT, transformer.getClassRedirects().get(LegacyRandomPatchBridge.OLD));
        assertTrue(implementsInterface(LegacyFeatureBridge.CONFIGURATION), "26.3 takes the rebuilt old feature model's configuration");

        transformer.clearRedirectsForTesting();
        LegacyRandomPatchBridge.register(transformer, host(Map.of(
                LegacyRandomPatchBridge.FEATURE_CONFIGURATION, withFields(LegacyRandomPatchBridge.FEATURE_CONFIGURATION),
                LegacyRandomPatchBridge.OLD, withFields(LegacyRandomPatchBridge.OLD))), false);
        assertNull(transformer.getClassRedirects().get(LegacyRandomPatchBridge.OLD), "a host that still has the class keeps it");
    }

    private boolean implementsInterface(String iface) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(LegacyRandomPatchBridge.REBUILT)).accept(node, 0);
        return node.interfaces.contains(iface);
    }

    @Test
    @DisplayName("ModifyDefaultComponentsEvent's patch-consumer calls go to the rebuilt helper where the event takes initializers")
    void defaultComponentCalls() {
        String event = "net/neoforged/neoforge/event/ModifyDefaultComponentsEvent";
        ClassNode host = withFields(event);
        host.methods.add(new org.objectweb.asm.tree.MethodNode(ACC_PUBLIC, "modify",
                "(Lnet/minecraft/world/level/ItemLike;L" + event + "$Initializer;)V", null, null));
        com.retromod.shim.neoforge.LegacyDefaultComponentsBridgeAccess.register(transformer, host(Map.of(event, host)));
        assertTrue(transformer.getSyntheticClasses().containsKey(
                com.retromod.shim.common.RebuiltApiClasses.ROOT + "neoforge/event/LegacyDefaultComponents"));

        transformer.clearRedirectsForTesting();
        com.retromod.shim.neoforge.LegacyDefaultComponentsBridgeAccess.register(transformer, host(Map.of(event, withFields(event))));
        assertFalse(transformer.getSyntheticClasses().containsKey(
                com.retromod.shim.common.RebuiltApiClasses.ROOT + "neoforge/event/LegacyDefaultComponents"),
                "a host that still takes the old consumer keeps it");
    }

    private static Function<String, ClassNode> host(Map<String, ClassNode> classes) {
        return classes::get;
    }

    private static ClassNode withFields(String name, String... fields) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (String field : fields) {
            node.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, field, "Lcom/mojang/serialization/Codec;", null, null));
        }
        return node;
    }

    /** {@code static Object codec() { return Ingredient.LIST_CODEC_NONEMPTY; }} */
    private static byte[] readsField() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/CookingPotSerializer", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "codec", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, LegacyIngredientCodecsBridge.INGREDIENT, "LIST_CODEC_NONEMPTY", "Lcom/mojang/serialization/Codec;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode firstCall(ClassNode node) {
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call");
    }
}
