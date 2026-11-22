/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.common.LegacyScatteredMembersBridge;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 Fabric mod that names a removed member by its intermediary id reaches the bridge keyed on the old name. */
class IntermediaryBridgedNameTest {

    private static final String ROOT = "com/retromod/legacyapi/minecraft/";
    private static final String HOVER = "net/minecraft/class_2568";
    private static final String HOVER_ACTION = "net/minecraft/class_2568$class_5247";
    private static final String CAMERA = "net/minecraft/class_4184";
    private static final String PROFILE = "net/minecraft/class_9296";
    private static final String BLOCK_ENTITY_TYPE = "net/minecraft/class_2591";
    private static final String HOST_CAMERA = "net/minecraft/client/Camera";

    private RetromodTransformer transformer;
    private String savedVersion;

    @BeforeEach
    void setUp() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        IntermediaryToMojangMapper.applyTo(transformer);
        // A 26.3 host has none of the old members, so every bridge registers.
        LegacyScatteredMembersBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("HoverEvent.getValue, Camera.getLookVector, and BlockEntityType.getKey called by intermediary id reach their bridges on 26.3")
    void intermediaryCallsReachBridges() {
        List<String> refs = refs(transformer.transformClass(oldFabricCalls(), "test/bridged/OldFabricMod"));

        assertTrue(refs.stream().anyMatch(r -> r.startsWith(ROOT + "network/chat/LegacyHoverValues.getValue(")),
                "method_10891 is HoverEvent.getValue, which 26.x replaced: " + refs);
        assertTrue(refs.contains(ROOT + "client/LegacyCameraVectors.getLookVector(L" + HOST_CAMERA + ";)Lorg/joml/Vector3f;"),
                "method_19335 with its 1.21.1 Vector3f return is getLookVector, not the host's forwardVector: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(ROOT + "world/level/block/entity/LegacyBlockEntityTypes.getKey(")),
                "method_11033 is the static BlockEntityType.getKey: " + refs);
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(ROOT + "world/item/component/LegacyResolvableProfiles.properties(")),
                "comp_2412 is the ResolvableProfile.properties accessor 1.21.11 removed: " + refs);
        assertTrue(refs.stream().noneMatch(r -> r.contains("method_") || r.contains("comp_")),
                "no intermediary id may survive: " + refs);
    }

    @Test
    @DisplayName("the same id with the host's own descriptor keeps the table's live name")
    void liveShapeKeepsTableName() {
        List<String> refs = refs(transformer.transformClass(newFabricCall(), "test/bridged/NewFabricMod"));

        assertEquals(List.of(HOST_CAMERA + ".forwardVector()Lorg/joml/Vector3fc;"), refs,
                "a 1.21.11 mod's method_19335 already names the member every 26.x host has");
    }

    @Test
    @DisplayName("a field id keeps its table name for the table's type and takes the old name for the old one")
    void fieldIdChoosesByDescriptor() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/bridged/Fields", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_1802", "field_28859", "Lnet/minecraft/class_1792;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_3218", "field_28859", "Lnet/minecraft/class_5838;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        writer.visitEnd();

        List<String> fields = fieldRefs(transformer.transformClass(writer.toByteArray(), "test/bridged/Fields"));

        assertEquals(List.of("net/minecraft/world/item/Items.DEEPSLATE_BRICKS",
                "net/minecraft/server/level/ServerLevel.sleepStatus"), fields);
    }

    @Test
    @DisplayName("a 1.21.11 entity renderer's own override of the submit id is renamed to submit")
    void overrideTakesTheOldName() {
        String renderer = "net/minecraft/class_897";
        String submit = "(Lnet/minecraft/class_10017;Lnet/minecraft/class_4587;Lnet/minecraft/class_11659;"
                + "Lnet/minecraft/class_12075;)V";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/bridged/MyRenderer", null, renderer, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "method_3936", submit, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 5);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(writer.toByteArray(), "test/bridged/MyRenderer")).accept(node, 0);

        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("submit")),
                "the override must keep overriding the host's submit");
        assertTrue(node.methods.stream().noneMatch(m -> m.name.startsWith("method_")), "no id may survive");
    }

    @Test
    @DisplayName("an id the table lacks maps only for its bridged descriptor")
    void missingIdMapsForItsDescriptorOnly() {
        assertEquals("properties", transformer.intermediaryMemberName(
                "comp_2412", "()Lcom/mojang/authlib/properties/PropertyMap;", false));
        assertNull(transformer.intermediaryMemberName("comp_2412", "()Ljava/lang/String;", false),
                "another shape of the id has no known name, so it stays as it is");
    }

    @Test
    @DisplayName("a mixin selector picks the old name by its descriptor")
    void mixinSelectorUsesDescriptor() {
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);

        assertEquals("L" + HOST_CAMERA + ";getLookVector()Lorg/joml/Vector3f;",
                mixins.remapResourceSelector("L" + CAMERA + ";method_19335()Lorg/joml/Vector3f;"));
        assertEquals("L" + HOST_CAMERA + ";forwardVector()Lorg/joml/Vector3fc;",
                mixins.remapResourceSelector("L" + CAMERA + ";method_19335()Lorg/joml/Vector3fc;"));
    }

    @Test
    @DisplayName("every bridged row names an id whose table entry differs or is missing")
    void rowsNeverRepeatTheTable() {
        IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();
        assertFalse(mapper.getBridgedNames().isEmpty(), "the bridged names resource must load");
        for (IntermediaryToMojangMapper.BridgedName row : mapper.getBridgedNames()) {
            String table = row.field() ? mapper.getFieldMap().get(row.id()) : mapper.getMethodMap().get(row.id());
            assertNotEquals(row.oldName(), table, row.id() + " already maps to its old name in the main table");
            assertTrue(row.descriptor().startsWith(row.field() ? "" : "("), row.id() + " needs a method descriptor");
        }
    }

    /** Calls a 1.21.1 Fabric mod makes, with intermediary owners, names, and descriptors. */
    private static byte[] oldFabricCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/bridged/OldFabricMod", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOVER, "method_10891", "(L" + HOVER_ACTION + ";)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CAMERA, "method_19335", "()Lorg/joml/Vector3f;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BLOCK_ENTITY_TYPE, "method_11033",
                "(L" + BLOCK_ENTITY_TYPE + ";)Lnet/minecraft/class_2960;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROFILE, "comp_2412", "()Lcom/mojang/authlib/properties/PropertyMap;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The call a 1.21.11 Fabric mod makes, where the id already returns the read-only vector. */
    private static byte[] newFabricCall() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/bridged/NewFabricMod", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CAMERA, "method_19335", "()Lorg/joml/Vector3fc;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> fieldRefs(byte[] bytes) {
        assertNotNull(bytes, "the transformer must return the class");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
            }
        }
        return refs;
    }

    private static List<String> refs(byte[] bytes) {
        assertNotNull(bytes, "the transformer must return the class");
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
