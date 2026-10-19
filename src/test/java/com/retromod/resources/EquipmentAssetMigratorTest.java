/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Worn armor renders from equipment assets since 1.21.2. Armor whose textures an old Forge mod
 * chose in {@code getArmorTexture} gets one from those textures, and a 1.21.2 asset moves to the
 * folder 1.21.4 reads.
 */
class EquipmentAssetMigratorTest {

    private static final String MATERIAL = "Lnet/minecraft/item/ItemArmor$ArmorMaterial;";
    private static final String SLOT = "Lnet/minecraft/inventory/EntityEquipmentSlot;";
    private static final String TEXTURE_DESC = "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/Entity;"
            + SLOT + "Ljava/lang/String;)Ljava/lang/String;";

    private static MapResourceTree run(String source, String target, boolean armorBridge,
                                       Map<String, byte[]> files) throws IOException {
        MapResourceTree tree = new MapResourceTree(files);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("rubymod"))
                .equipmentAssets(armorBridge).build();
        new ModResourceMigrations(List.of(new EquipmentAssetMigrator())).run(tree, context);
        return tree;
    }

    /** A 1.12 mod whose ruby armor picks its texture per slot in code. */
    private static Map<String, byte[]> rubyArmorMod(byte[] armorClass) {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/rubymod/lang/en_us.lang", "item.ruby_helmet.name=Ruby Helmet",
                "assets/rubymod/textures/armor/ruby_1.png", "outer layer",
                "assets/rubymod/textures/armor/ruby_2.png", "inner layer"));
        mod.put("rubymod/RubyMaterials.class", materials("rubymod/RubyMaterials", "RUBY", "rubymod:ruby"));
        mod.put("rubymod/RubyArmor.class", armorClass);
        return mod;
    }

    @ParameterizedTest(name = "on {0}")
    @CsvSource({"1.21.5", "26.3"})
    @DisplayName("Textures returned per slot become the humanoid and leggings layers of the material's asset")
    void slotTestDecidesTheLayer(String target) throws IOException {
        MapResourceTree tree = run("1.12.2", target, true, rubyArmorMod(
                armorWithSlotTest("rubymod/RubyArmor", "rubymod/RubyMaterials", "RUBY",
                        "rubymod:textures/armor/ruby_1.png", "rubymod:textures/armor/ruby_2.png")));

        String asset = text(tree.read("assets/rubymod/equipment/ruby.json"));
        assertTrue(asset.contains("\"humanoid\": [{\"texture\": \"rubymod:ruby\"}]"), asset);
        assertTrue(asset.contains("\"humanoid_leggings\": [{\"texture\": \"rubymod:ruby\"}]"), asset);
        assertEquals("outer layer", text(tree.read("assets/rubymod/textures/entity/equipment/humanoid/ruby.png")));
        assertEquals("inner layer",
                text(tree.read("assets/rubymod/textures/entity/equipment/humanoid_leggings/ruby.png")));
        assertTrue(tree.exists("assets/rubymod/textures/armor/ruby_1.png"), "the original stays");
    }

    @Test
    @DisplayName("A texture name joined from constants and the slot's layer number gives both layers")
    void concatenatedLayerNumber() throws IOException {
        MapResourceTree tree = run("1.12.2", "26.3", true, rubyArmorMod(
                armorWithLayerNumber("rubymod/RubyArmor", "rubymod/RubyMaterials", "RUBY")));

        assertEquals("outer layer", text(tree.read("assets/rubymod/textures/entity/equipment/humanoid/ruby.png")));
        assertEquals("inner layer",
                text(tree.read("assets/rubymod/textures/entity/equipment/humanoid_leggings/ruby.png")));
        assertTrue(tree.exists("assets/rubymod/equipment/ruby.json"));
    }

    @Test
    @DisplayName("A texture the method builds at run time gives no asset")
    void computedTextureIsSkipped() throws IOException {
        MapResourceTree tree = run("1.12.2", "26.3", true, rubyArmorMod(
                armorWithComputedTexture("rubymod/RubyArmor", "rubymod/RubyMaterials", "RUBY")));

        assertFalse(tree.exists("assets/rubymod/equipment/ruby.json"));
    }

    @Test
    @DisplayName("Armor drawn with its own model gives no asset, since its texture fits that model, not the humanoid one")
    void armorWithItsOwnModelIsSkipped() throws IOException {
        MapResourceTree tree = run("1.12.2", "26.3", true, rubyArmorMod(withOwnModel(
                armorWithSlotTest("rubymod/RubyArmor", "rubymod/RubyMaterials", "RUBY",
                        "rubymod:textures/armor/ruby_1.png", "rubymod:textures/armor/ruby_2.png"))));

        assertFalse(tree.exists("assets/rubymod/equipment/ruby.json"));
    }

    @Test
    @DisplayName("A material named after a vanilla one never gets an asset in the minecraft namespace")
    void sharedNamespaceMaterialIsSkipped() throws IOException {
        Map<String, byte[]> mod = rubyArmorMod(armorWithSlotTest("rubymod/RubyArmor", "rubymod/RubyMaterials",
                "RUBY", "rubymod:textures/armor/ruby_1.png", "rubymod:textures/armor/ruby_2.png"));
        mod.put("rubymod/RubyMaterials.class", materials("rubymod/RubyMaterials", "RUBY", "iron"));

        MapResourceTree tree = run("1.12.2", "26.3", true, mod);

        assertFalse(tree.exists("assets/minecraft/equipment/iron.json"));
        assertFalse(tree.exists("assets/minecraft/textures/entity/equipment/humanoid/iron.png"));
    }

    @Test
    @DisplayName("A material named in another mod's namespace gets no asset there, so that mod's own asset is not shadowed")
    void foreignNamespaceMaterialIsSkipped() throws IOException {
        Map<String, byte[]> mod = rubyArmorMod(armorWithSlotTest("rubymod/RubyArmor", "rubymod/RubyMaterials",
                "RUBY", "rubymod:textures/armor/ruby_1.png", "rubymod:textures/armor/ruby_2.png"));
        mod.put("rubymod/RubyMaterials.class", materials("rubymod/RubyMaterials", "RUBY", "othermod:ruby"));

        MapResourceTree tree = run("1.12.2", "26.3", true, mod);

        assertEquals(mod.keySet(), tree.entries().keySet(), "nothing is written for an addon's parent mod");
    }

    @Test
    @DisplayName("Without the armor bridge the item is not armor on the host, so no asset is written")
    void olderHostWithoutBridgeIsLeftAlone() throws IOException {
        Map<String, byte[]> mod = rubyArmorMod(armorWithSlotTest("rubymod/RubyArmor", "rubymod/RubyMaterials",
                "RUBY", "rubymod:textures/armor/ruby_1.png", "rubymod:textures/armor/ruby_2.png"));

        MapResourceTree tree = run("1.12.2", "1.21.1", false, mod);

        assertEquals(mod.keySet(), tree.entries().keySet());
    }

    private static final String DIAMONDLIKE = "{\"layers\":{\"humanoid\":[{\"texture\":\"rubymod:ruby\"}],"
            + "\"humanoid_leggings\":[{\"texture\":\"rubymod:ruby\"}]}}";

    @ParameterizedTest(name = "{0} on {1}: copied {2}")
    @CsvSource({"1.21.2, 1.21.4, true", "1.21.3, 26.3, true", "1.21.2, 1.21.3, false", "1.20.1, 26.3, false"})
    @DisplayName("A 1.21.2 or 1.21.3 equipment asset is copied to the folder 1.21.4 reads")
    void movedEquipmentFolder(String source, String target, boolean copied) throws IOException {
        MapResourceTree tree = run(source, target, false, entries(
                "assets/rubymod/models/equipment/ruby.json", DIAMONDLIKE));

        assertEquals(copied, tree.exists("assets/rubymod/equipment/ruby.json"));
        assertTrue(tree.exists("assets/rubymod/models/equipment/ruby.json"), "the original stays");
    }

    @Test
    @DisplayName("A model that merely lives in models/equipment is not mistaken for an equipment asset")
    void blockModelsInEquipmentFolderStay() throws IOException {
        MapResourceTree tree = run(null, "26.3", false, entries(
                "assets/rubymod/models/equipment/ruby_sword.json",
                "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"rubymod:equipment/ruby_sword\"}}"));

        assertFalse(tree.exists("assets/rubymod/equipment/ruby_sword.json"));
    }

    /** {@code RUBY = EnumHelper.addArmorMaterial("RUBY", texture, 20, null, 10, null, 0f)}. */
    static byte[] materials(String owner, String field, String texture) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, MATERIAL, null, null).visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(field);
        clinit.visitLdcInsn(texture);
        clinit.visitIntInsn(Opcodes.BIPUSH, 20);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitIntInsn(Opcodes.BIPUSH, 10);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitInsn(Opcodes.FCONST_0);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/common/util/EnumHelper", "addArmorMaterial",
                "(Ljava/lang/String;Ljava/lang/String;I[IILnet/minecraft/util/SoundEvent;F)" + MATERIAL, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, MATERIAL);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** An armor item built from a constant material: {@code super(RubyMaterials.RUBY, 0, slot)}. */
    private static ClassWriter armorClass(String owner, String materials, String field) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "net/minecraft/item/ItemArmor", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + SLOT + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETSTATIC, materials, field, MATERIAL);
        init.visitInsn(Opcodes.ICONST_0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/item/ItemArmor", "<init>",
                "(" + MATERIAL + "I" + SLOT + ")V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        return writer;
    }

    /** {@code return slot == EntityEquipmentSlot.LEGS ? inner : outer;} */
    static byte[] armorWithSlotTest(String owner, String materials, String field, String outer, String inner) {
        ClassWriter writer = armorClass(owner, materials, field);
        MethodVisitor texture = writer.visitMethod(Opcodes.ACC_PUBLIC, "getArmorTexture", TEXTURE_DESC, null, null);
        texture.visitCode();
        Label legs = new Label();
        texture.visitVarInsn(Opcodes.ALOAD, 3);
        texture.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/inventory/EntityEquipmentSlot", "LEGS", SLOT);
        texture.visitJumpInsn(Opcodes.IF_ACMPEQ, legs);
        texture.visitLdcInsn(outer);
        texture.visitInsn(Opcodes.ARETURN);
        texture.visitLabel(legs);
        texture.visitLdcInsn(inner);
        texture.visitInsn(Opcodes.ARETURN);
        texture.visitMaxs(0, 0);
        texture.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The same armor class with {@code getArmorModel(entity, stack, slot, defaultModel)}, as 1.12 Forge let it. */
    static byte[] withOwnModel(byte[] armorClass) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(armorClass).accept(node, 0);
        String biped = "Lnet/minecraft/client/model/ModelBiped;";
        org.objectweb.asm.tree.MethodNode model = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC,
                "getArmorModel", "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;" + SLOT
                        + biped + ")" + biped, null, null);
        model.visitCode();
        model.visitVarInsn(Opcodes.ALOAD, 4);
        model.visitInsn(Opcodes.ARETURN);
        model.visitMaxs(1, 5);
        model.visitEnd();
        node.methods.add(model);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code return "rubymod:textures/armor/ruby_" + (slot == EntityEquipmentSlot.LEGS ? 2 : 1) + ".png";} */
    static byte[] armorWithLayerNumber(String owner, String materials, String field) {
        ClassWriter writer = armorClass(owner, materials, field);
        MethodVisitor texture = writer.visitMethod(Opcodes.ACC_PUBLIC, "getArmorTexture", TEXTURE_DESC, null, null);
        String append = "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
        texture.visitCode();
        texture.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        texture.visitInsn(Opcodes.DUP);
        texture.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        texture.visitLdcInsn("rubymod:textures/armor/ruby_");
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", append, false);
        Label outer = new Label();
        Label joined = new Label();
        texture.visitVarInsn(Opcodes.ALOAD, 3);
        texture.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/inventory/EntityEquipmentSlot", "LEGS", SLOT);
        texture.visitJumpInsn(Opcodes.IF_ACMPNE, outer);
        texture.visitInsn(Opcodes.ICONST_2);
        texture.visitJumpInsn(Opcodes.GOTO, joined);
        texture.visitLabel(outer);
        texture.visitInsn(Opcodes.ICONST_1);
        texture.visitLabel(joined);
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(I)Ljava/lang/StringBuilder;", false);
        texture.visitLdcInsn(".png");
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", append, false);
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        texture.visitInsn(Opcodes.ARETURN);
        texture.visitMaxs(0, 0);
        texture.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code return "rubymod:textures/armor/" + type + ".png";}, which no scan can resolve. */
    static byte[] armorWithComputedTexture(String owner, String materials, String field) {
        ClassWriter writer = armorClass(owner, materials, field);
        MethodVisitor texture = writer.visitMethod(Opcodes.ACC_PUBLIC, "getArmorTexture", TEXTURE_DESC, null, null);
        texture.visitCode();
        texture.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        texture.visitInsn(Opcodes.DUP);
        texture.visitLdcInsn("rubymod:textures/armor/");
        texture.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        texture.visitVarInsn(Opcodes.ALOAD, 4);
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        texture.visitLdcInsn(".png");
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        texture.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        texture.visitInsn(Opcodes.ARETURN);
        texture.visitMaxs(0, 0);
        texture.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
