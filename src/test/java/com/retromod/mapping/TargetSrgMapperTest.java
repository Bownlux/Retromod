/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.MixinRefmapRemapper;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.forge.Forge_1_19_4_to_1_20;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the pre-26 Forge member namespace in issue #192. */
class TargetSrgMapperTest {

    @AfterEach
    void resetTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Forge 1.20.1 target table is owner and descriptor qualified")
    void targetTableLoads() {
        TargetSrgMapper mapper = TargetSrgMapper.forVersion("1.20.1");
        assertTrue(mapper.methodCount() > 30_000);
        assertTrue(mapper.fieldCount() > 30_000);
        assertEquals(0, TargetSrgMapper.forVersion("1.20.2").methodCount(),
                "an unsupported target must not borrow a nearby SRG namespace");
    }

    @Test
    @DisplayName("#192 old Forge members become the exact Forge 1.20.1 SRG members")
    void oldSrgBecomesTargetSrg() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.registerClassRedirect(
                "net/minecraft/world/IBlockReader", "net/minecraft/world/level/BlockGetter");
        transformer.registerClassRedirect(
                "net/minecraft/util/math/BlockPos", "net/minecraft/core/BlockPos");
        transformer.registerClassRedirect(
                "net/minecraft/block/BlockState",
                "net/minecraft/world/level/block/state/BlockState");
        transformer.registerClassRedirect(
                "net/minecraft/entity/Entity", "net/minecraft/world/entity/Entity");
        transformer.registerClassRedirect(
                "net/minecraft/world/World", "net/minecraft/world/level/Level");
        SrgToMojangMapper.getInstance().applyTo(transformer);
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);

        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(fixture(), "test/BetterPortalsShape.class"))
                .accept(output, 0);

        MethodInsnNode getBlockState = firstInstruction(output, MethodInsnNode.class);
        assertEquals("net/minecraft/world/level/BlockGetter", getBlockState.owner);
        assertEquals("m_8055_", getBlockState.name);
        assertEquals("(Lnet/minecraft/core/BlockPos;)"
                        + "Lnet/minecraft/world/level/block/state/BlockState;",
                getBlockState.desc);

        FieldInsnNode level = firstInstruction(output, FieldInsnNode.class);
        assertEquals("net/minecraft/world/entity/Entity", level.owner);
        assertEquals("f_19853_", level.name);
        assertEquals("Lnet/minecraft/world/level/Level;", level.desc);
    }

    @Test
    @DisplayName("#192 old SRG names in Mixin selectors become target SRG names")
    void oldSrgMixinSelectorBecomesTargetSrg() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.registerClassRedirect(
                "net/minecraft/block/PortalSize",
                "net/minecraft/world/level/portal/PortalShape");
        SrgToMojangMapper.getInstance().applyTo(transformer);
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);

        byte[] transformed = new MixinCompatibilityTransformer(transformer)
                .transformMixinClass(mixinFixture());
        ClassNode output = new ClassNode();
        new ClassReader(transformed).accept(output, 0);
        MethodNode handler = output.methods.stream()
                .filter(method -> method.name.equals("calculateWidth"))
                .findFirst().orElseThrow();
        AnnotationNode inject = handler.visibleAnnotations.get(0);
        int methodIndex = inject.values.indexOf("method");
        assertEquals(List.of("m_77745_()I"), inject.values.get(methodIndex + 1));
    }

    @Test
    @DisplayName("#192 Forge refmaps follow class and target SRG remaps")
    void forgeRefmapBecomesTargetSrg() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.registerClassRedirect(
                "net/minecraft/block/PortalSize",
                "net/minecraft/world/level/portal/PortalShape");
        transformer.registerClassRedirect(
                "net/minecraft/client/entity/player/ClientPlayerEntity",
                "net/minecraft/client/player/LocalPlayer");
        transformer.registerClassRedirect(
                "net/minecraft/potion/Effect", "net/minecraft/world/effect/MobEffect");
        transformer.registerClassRedirect(
                "net/minecraft/potion/EffectInstance",
                "net/minecraft/world/effect/MobEffectInstance");
        SrgToMojangMapper.getInstance().applyTo(transformer);
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);

        String refmap = """
                {"mappings":{"MixinClient":{"removeActivePotionEffect":
                "Lnet/minecraft/client/entity/player/ClientPlayerEntity;func_184596_c(Lnet/minecraft/potion/Effect;)Lnet/minecraft/potion/EffectInstance;"},
                "MixinPortal":{"func_242974_d()I":
                "Lnet/minecraft/block/PortalSize;func_242974_d()I"}}}
                """;
        String output = MixinRefmapRemapper.remapForgeSelectors(
                refmap, new MixinCompatibilityTransformer(transformer));

        assertTrue(output.contains("Lnet/minecraft/client/player/LocalPlayer;"));
        assertTrue(output.contains("Lnet/minecraft/world/level/portal/PortalShape;m_77745_()I"));
        assertTrue(output.contains("\"m_77745_()I\""));
        assertFalse(output.contains("ClientPlayerEntity"));
        assertFalse(output.contains("func_242974_d"));
    }

    @Test
    @DisplayName("#192 removed Properties.noDrops becomes the Forge 1.20.1 noLootTable member")
    void noDropsBecomesNoLootTable() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        new Forge_1_19_4_to_1_20().registerRedirects(transformer);
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);

        assertEquals("m_222994_", transformer.remapQualifiedMethodName(
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties",
                "func_222380_e",
                "()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;"));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/BetterPortalsShape", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "(Lnet/minecraft/world/IBlockReader;Lnet/minecraft/util/math/BlockPos;"
                        + "Lnet/minecraft/entity/Entity;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "net/minecraft/world/IBlockReader", "func_180495_p",
                "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;", true);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/entity/Entity",
                "field_70170_p", "Lnet/minecraft/world/World;");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mixinFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/BetterPortalsMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/block/PortalSize"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE, "calculateWidth", "()V",
                null, null);
        AnnotationVisitor inject = handler.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "func_242974_d()I");
        methods.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 1);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static <T extends AbstractInsnNode> T firstInstruction(
            ClassNode classNode, Class<T> type) {
        for (AbstractInsnNode instruction : classNode.methods.get(0).instructions) {
            if (type.isInstance(instruction)) {
                return type.cast(instruction);
            }
        }
        throw new AssertionError("Missing instruction of type " + type.getSimpleName());
    }
}
