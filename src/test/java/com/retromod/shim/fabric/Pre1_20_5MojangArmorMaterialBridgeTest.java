/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The Mojang-named ArmorMaterial bridge, as a Forge 1.20.1 enum material (Scorched Guns, Fossils
 * and Archeology) meets the 1.20.5 to 1.21.1 record on a NeoForge host. Minecraft is not on the
 * test classpath, so each case transforms a fixture shaped like the mod's bytecode after its SRG
 * remap.
 */
class Pre1_20_5MojangArmorMaterialBridgeTest {

    private static final String MATERIAL = "net/minecraft/world/item/ArmorMaterial";
    private static final String MATERIALS = "net/minecraft/world/item/ArmorMaterials";
    private static final String ARMOR_ITEM = "net/minecraft/world/item/ArmorItem";
    private static final String TYPE_DESC = "Lnet/minecraft/world/item/ArmorItem$Type;";
    private static final String PROPS_DESC = "Lnet/minecraft/world/item/Item$Properties;";
    private static final Pattern INTERMEDIARY = Pattern.compile("(class|method|field|comp)_\\d+");

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
    @DisplayName("The generated classes name only Mojang members on the host")
    void generatedClassesUseMojangNames() {
        Pre1_20_5MojangArmorMaterialBridge.registerRedirects(transformer, true);

        for (String name : List.of(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL,
                Pre1_20_5ArmorMaterialBridge.MATERIAL_VIEW, Pre1_20_5ArmorMaterialBridge.REPAIR_SUPPLIER,
                Pre1_20_5ArmorMaterialBridge.MATERIALS, Pre1_20_5ArmorMaterialBridge.LEGACY_ARMOR_ITEM,
                Pre1_20_5ArmorMaterialBridge.SUPPORT)) {
            byte[] bytes = transformer.getSyntheticClasses().get(name);
            assertNotNull(bytes, name + " must be registered");
            String constants = new String(bytes, StandardCharsets.ISO_8859_1);
            assertFalse(INTERMEDIARY.matcher(constants).find(),
                    name + " still names an intermediary member, which a NeoForge host does not have");
            assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                    new CheckClassAdapter(new ClassWriter(0), false), 0), name + " must stay well formed");
        }

        ClassNode legacy = read(transformer.getSyntheticClasses().get(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL));
        assertTrue(hasMethod(legacy, "getDurabilityForType", "(" + TYPE_DESC + ")I"),
                "the stand-in must declare the 1.20.1 method the mod enum implements");
        assertTrue(hasMethod(legacy, "getKnockbackResistance", "()F"));

        ClassNode materials = read(transformer.getSyntheticClasses().get(Pre1_20_5ArmorMaterialBridge.MATERIALS));
        assertTrue(hasMethod(materials, "IRON", "()L" + Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL + ";"),
                "vanilla IRON needs a getter the field redirect can call");
        assertTrue(strings(materials).contains("getDefenseForType"),
                "building the record asks the mod material for its old per-type defense");
        assertTrue(calls(materials).stream().anyMatch(c -> c.owner.equals("net/minecraft/core/Registry")
                        && c.name.equals("registerForHolder")),
                "a mod material is registered into the host's armor material registry");

        ClassNode view = read(transformer.getSyntheticClasses().get(Pre1_20_5ArmorMaterialBridge.MATERIAL_VIEW));
        assertTrue(strings(view).contains("getDefense"),
                "a vanilla view reads the record, whose per-type method is getDefense");
        assertFalse(strings(view).contains("getDefenseForType"));

        ClassNode support = read(transformer.getSyntheticClasses().get(Pre1_20_5ArmorMaterialBridge.SUPPORT));
        assertTrue(strings(support).contains("net.minecraft.world.item.ArmorMaterial"),
                "the support class must load the record by its Mojang name");
    }

    @Test
    @DisplayName("A Forge enum material and its armor items move onto the generated bridge")
    void forgeEnumMaterialIsBridged(@TempDir Path jar) throws Exception {
        Pre1_20_5MojangArmorMaterialBridge.registerRedirects(transformer, true);

        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ENUM | ACC_FINAL, "test/ModArmorMaterials",
                null, "java/lang/Enum", new String[] {MATERIAL});
        material.visitEnd();
        Files.write(jar.resolve("ModArmorMaterials.class"), material.toByteArray());
        Files.write(jar.resolve("ModItems.class"), staticMethod("test/ModItems", "create",
                "(" + TYPE_DESC + PROPS_DESC + ")V", mv -> {
                    mv.visitTypeInsn(NEW, ARMOR_ITEM);
                    mv.visitInsn(DUP);
                    mv.visitFieldInsn(GETSTATIC, MATERIALS, "IRON", "L" + MATERIALS + ";");
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKESPECIAL, ARMOR_ITEM, "<init>",
                            "(L" + MATERIAL + ";" + TYPE_DESC + PROPS_DESC + ")V", false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                }));

        assertEquals(2, Pre1_20_5MojangArmorMaterialBridge.renameInLegacyJar(transformer, jar));

        ClassNode materialOut = transform(Files.readAllBytes(jar.resolve("ModArmorMaterials.class")),
                "test/ModArmorMaterials");
        assertEquals(List.of(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL), materialOut.interfaces,
                "the record cannot be implemented, so the enum must implement the generated interface");

        ClassNode itemsOut = transform(Files.readAllBytes(jar.resolve("ModItems.class")), "test/ModItems");
        List<String> bridged = calls(itemsOut).stream()
                .filter(c -> c.owner.equals(Pre1_20_5ArmorMaterialBridge.MATERIALS)).map(c -> c.name).toList();
        assertTrue(bridged.contains("IRON"), "vanilla IRON must become a legacy view: " + bridged);
        assertTrue(bridged.contains("armorItem"), "new ArmorItem(material, ...) must use the factory: " + bridged);
    }

    @Test
    @DisplayName("A 1.21 mod that builds the record is left alone")
    void modernRecordUserIsLeftAlone(@TempDir Path jar) throws Exception {
        Pre1_20_5MojangArmorMaterialBridge.registerRedirects(transformer, true);
        byte[] modern = staticMethod("test/ModernMaterials", "create", "()V", mv -> {
            mv.visitTypeInsn(NEW, MATERIAL);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(FCONST_0);
            mv.visitInsn(FCONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, MATERIAL, "<init>", "(Ljava/util/Map;ILnet/minecraft/core/Holder;"
                    + "Ljava/util/function/Supplier;Ljava/util/List;FF)V", false);
            mv.visitFieldInsn(GETSTATIC, MATERIALS, "IRON", "Lnet/minecraft/core/Holder;");
            mv.visitInsn(POP2);
            mv.visitInsn(RETURN);
        });
        Files.write(jar.resolve("ModernMaterials.class"), modern);

        assertFalse(Pre1_20_5MojangArmorMaterialBridge.usesLegacyInterface(modern));
        assertEquals(0, Pre1_20_5MojangArmorMaterialBridge.renameInLegacyJar(transformer, jar));
        assertArrayEquals(modern, Files.readAllBytes(jar.resolve("ModernMaterials.class")));
    }

    @Test
    @DisplayName("The intermediary bridge's stand-in never renames Mojang-named classes")
    void intermediaryStandInDoesNotTriggerTheMojangRename(@TempDir Path jar) throws Exception {
        Pre1_20_5ArmorMaterialBridge.registerRedirects(transformer);
        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModMaterial", null, "java/lang/Object",
                new String[] {MATERIAL});
        material.visitEnd();
        Files.write(jar.resolve("ModMaterial.class"), material.toByteArray());

        assertEquals(0, Pre1_20_5MojangArmorMaterialBridge.renameInLegacyJar(transformer, jar),
                "a Fabric host registers the intermediary stand-in, whose methods a Mojang class cannot satisfy");
    }

    @Test
    @DisplayName("Only the 1.20.5 to 1.21.1 record shape turns the bridge on")
    void hostShapeGate() {
        ClassNode armorItem = classWith(ACC_PUBLIC, ARMOR_ITEM, "<init>",
                "(Lnet/minecraft/core/Holder;" + TYPE_DESC + PROPS_DESC + ")V");
        ClassNode record = classWith(ACC_PUBLIC | ACC_FINAL, MATERIAL, "<init>",
                "(Ljava/util/Map;ILnet/minecraft/core/Holder;Ljava/util/function/Supplier;Ljava/util/List;FF)V");
        assertTrue(Pre1_20_5MojangArmorMaterialBridge.isRecordShape(record, armorItem));

        ClassNode oldInterface = classWith(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, MATERIAL,
                "getDurabilityForType", "(" + TYPE_DESC + ")I");
        assertFalse(Pre1_20_5MojangArmorMaterialBridge.isRecordShape(oldInterface, armorItem),
                "a 1.20.4 host still has the interface, so its mods need nothing");

        // 1.21.2 record: durability, defense by ArmorType, enchantment value, sound, toughness,
        // knockback resistance, repair tag, model id.
        ClassNode reworked = classWith(ACC_PUBLIC | ACC_FINAL, MATERIAL, "<init>",
                "(ILjava/util/Map;ILnet/minecraft/core/Holder;FFLnet/minecraft/tags/TagKey;"
                        + "Lnet/minecraft/resources/ResourceLocation;)V");
        assertFalse(Pre1_20_5MojangArmorMaterialBridge.isRecordShape(reworked, armorItem),
                "the 1.21.2 record has no layers or holder constructor, so the generated calls would not link");
    }

    private ClassNode transform(byte[] input, String name) {
        return read(transformer.transformClass(input, name));
    }

    private static ClassNode classWith(int access, String name, String method, String desc) {
        ClassNode node = new ClassNode();
        node.access = access;
        node.name = name;
        node.methods.add(new MethodNode(ACC_PUBLIC, method, desc, null, null));
        return node;
    }

    private static byte[] staticMethod(String owner, String name, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, owner, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static List<AbstractInsnNode> instructions(ClassNode node) {
        List<AbstractInsnNode> all = new ArrayList<>();
        for (MethodNode method : node.methods) {
            method.instructions.forEach(all::add);
        }
        return all;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        return instructions(node).stream()
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    private static List<Object> strings(ClassNode node) {
        return instructions(node).stream()
                .filter(LdcInsnNode.class::isInstance).map(insn -> ((LdcInsnNode) insn).cst).toList();
    }
}
