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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Regression coverage for the 1.20.1 content APIs that Palladium and Alien Evolution call while
 * registering content on a Fabric 1.21.1 host (#262). Minecraft is not on the test classpath, so
 * each case transforms a small fixture shaped like the mod's bytecode and inspects the result.
 */
class Pre1_20_5ContentApiBridgesTest {

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
    @DisplayName("A legacy ParticleType subclass is rebased onto the generated codec base")
    void legacyParticleTypeSubclassIsRebased() {
        Pre1_20_5ParticleTypeBridge.registerRedirects(transformer);
        String oldCtor = "(ZLnet/minecraft/class_2394$class_2395;)V";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/FireParticleType", null,
                "net/minecraft/class_2396", null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", oldCtor, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ILOAD, 1);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_2396", "<init>", oldCtor, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(3, 3);
        ctor.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray(), "test/FireParticleType");

        assertEquals(Pre1_20_5ParticleTypeBridge.LEGACY_TYPE, out.superName,
                "the subclass must extend the generated base that supplies the 1.20.5 codecs");
        MethodInsnNode superCall = findCall(out, "<init>");
        assertEquals(Pre1_20_5ParticleTypeBridge.LEGACY_TYPE, superCall.owner);
        assertEquals("(ZL" + Pre1_20_5ParticleTypeBridge.DESERIALIZER + ";)V", superCall.desc,
                "the removed Deserializer type must be replaced by the generated interface");
    }

    @Test
    @DisplayName("The generated particle base keeps both codec signatures and a pass-through constructor")
    void generatedParticleBaseHasBothCodecShapes() {
        ClassNode base = read(Pre1_20_5ParticleTypeBridge.generateLegacyParticleType());

        assertTrue(hasMethod(base, "method_29138", "()Lcom/mojang/serialization/Codec;"),
                "the mod's old Codec codec() must remain overridable");
        assertTrue(hasMethod(base, "method_29138", "()Lcom/mojang/serialization/MapCodec;"),
                "the host's abstract MapCodec codec() must be implemented");
        assertTrue(hasMethod(base, "method_56179", "()Lnet/minecraft/class_9139;"),
                "the host's abstract streamCodec() must be implemented");
        assertTrue(hasMethod(base, "<init>", "(Z)V"),
                "a 1.20.5+ subclass calling super(boolean) must still link");
        assertTrue(base.interfaces.contains("net/minecraft/class_9139"),
                "the base serves as its own stream codec");
        assertWellFormed(Pre1_20_5ParticleTypeBridge.generateLegacyParticleType());
    }

    @Test
    @DisplayName("getDeserializer() and new SimpleParticleType(boolean) are redirected")
    void particleCallSitesAreRedirected() {
        Pre1_20_5ParticleTypeBridge.registerRedirects(transformer);
        Pre1_20_5ParticleTypeBridge.registerSimpleTypeRedirect(transformer);

        ClassNode out = transform(staticMethod("test/Particles", "use", "(Lnet/minecraft/class_2396;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_2396", "method_10298",
                    "()Lnet/minecraft/class_2394$class_2395;", false);
            mv.visitInsn(POP);
            mv.visitTypeInsn(NEW, "net/minecraft/class_2400");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_2400", "<init>", "(Z)V", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        }), "test/Particles");

        assertTrue(calls(out).stream().anyMatch(c -> c.getOpcode() == INVOKESTATIC
                        && c.owner.equals(Pre1_20_5ParticleTypeBridge.DESERIALIZER_VIEW) && c.name.equals("of")),
                "getDeserializer() must become the generated view factory");
        assertTrue(calls(out).stream().anyMatch(c -> c.getOpcode() == INVOKESTATIC
                        && c.owner.equals(Pre1_20_5ParticleTypeBridge.LEGACY_SIMPLE_TYPE)),
                "the now-protected SimpleParticleType constructor must go through the public subclass");
    }

    @Test
    @DisplayName("Old DropExperienceBlock and AmethystClusterBlock constructors reach the new shapes")
    void oldBlockConstructorsAreBridged() {
        Pre1_20_3BlockConstructorBridge.registerRedirects(transformer, true, true);

        ClassNode out = transform(staticMethod("test/Blocks", "create",
                "(Lnet/minecraft/class_4970$class_2251;)V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/class_2431");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_2431", "<init>",
                    Pre1_20_3BlockConstructorBridge.DROP_OLD_PROPERTIES, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        }), "test/Blocks");

        MethodInsnNode factory = calls(out).stream()
                .filter(c -> c.owner.equals(Pre1_20_3BlockConstructorBridge.FACTORY)).findFirst()
                .orElseThrow(() -> new AssertionError("new DropExperienceBlock(Properties) was not redirected"));
        assertEquals("dropExperienceBlock", factory.name);

        ClassNode cluster = read(Pre1_20_3BlockConstructorBridge.generateAmethystClusterBase());
        assertTrue(hasMethod(cluster, "<init>", Pre1_20_3BlockConstructorBridge.CLUSTER_OLD),
                "a subclass calling super(int, int, Properties) needs a matching constructor");
        assertTrue(hasMethod(cluster, "<init>", Pre1_20_3BlockConstructorBridge.CLUSTER_MODERN),
                "a 1.20.3+ subclass calling super(float, float, Properties) must still link");
        assertWellFormed(Pre1_20_3BlockConstructorBridge.generateFactory());
    }

    @Test
    @DisplayName("Old FolderRepositorySource and Pack factory calls are redirected")
    void oldPackCallsAreRedirected() {
        Pre1_20_5PackApiBridge.registerRedirects(transformer, true, true);

        ClassNode out = transform(staticMethod("test/Packs", "create", "()V", mv -> {
            mv.visitTypeInsn(NEW, Pre1_20_5PackApiBridge.FOLDER_SOURCE);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, Pre1_20_5PackApiBridge.FOLDER_SOURCE, "<init>",
                    Pre1_20_5PackApiBridge.OLD_CTOR, false);
            mv.visitInsn(POP);
            for (int i = 0; i < 7; i++) mv.visitInsn(i == 2 ? ICONST_0 : ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, Pre1_20_5PackApiBridge.PACK,
                    Pre1_20_5PackApiBridge.READ_META_AND_CREATE, Pre1_20_5PackApiBridge.OLD_READ_META, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        }), "test/Packs");

        long factoryCalls = calls(out).stream()
                .filter(c -> c.owner.equals(Pre1_20_5PackApiBridge.FACTORY)).count();
        assertEquals(2, factoryCalls, "both the constructor and readMetaAndCreate must use the factory");
        assertWellFormed(Pre1_20_5PackApiBridge.generateFactory());
    }

    @Test
    @DisplayName("Attribute and MobEffect calls accept either a bare value or a holder")
    void registryHolderCallsAreAdapted() {
        Pre1_20_5RegistryHolderBridge.registerRedirects(transformer,
                Pre1_20_5RegistryHolderBridge.OVERLOADS, Pre1_20_5RegistryHolderBridge.CONSTRUCTORS,
                true, true);

        ClassNode out = transform(staticMethod("test/Effects", "apply",
                "(Lnet/minecraft/class_1309;)V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/class_1293");
            mv.visitInsn(DUP);
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/class_1294", "field_5907", "Lnet/minecraft/class_1291;");
            mv.visitIntInsn(BIPUSH, 20);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_1293", "<init>",
                    "(Lnet/minecraft/class_1291;II)V", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/class_5134", "field_23716", "Lnet/minecraft/class_1320;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_1309", "method_26825",
                    "(Lnet/minecraft/class_1320;)D", false);
            mv.visitInsn(POP2);
            mv.visitInsn(RETURN);
        }), "test/Effects");

        assertTrue(instructions(out).stream().noneMatch(FieldInsnNode.class::isInstance),
                "a read of a constant that is now a holder must go through the unwrapping getter");
        List<String> adapters = calls(out).stream()
                .filter(c -> c.owner.equals(Pre1_20_5RegistryHolderBridge.HELPER)).map(c -> c.name).toList();
        assertTrue(adapters.contains("class_1294$field_5907"),
                "MobEffects.REGENERATION must be read as the bare effect: " + adapters);
        assertTrue(adapters.contains("new$class_1293"), "MobEffectInstance needs the holder factory: " + adapters);
        assertTrue(adapters.contains("class_1309$method_26825"),
                "getAttributeValue(Attribute) needs the holder adapter: " + adapters);
        assertWellFormed(Pre1_20_5RegistryHolderBridge.generateHelper(
                Pre1_20_5RegistryHolderBridge.OVERLOADS, Pre1_20_5RegistryHolderBridge.CONSTRUCTORS));
    }

    @Test
    @DisplayName("A mod ArmorMaterial implementation and its armor item use the generated bridge")
    void legacyArmorMaterialIsBridged() {
        Pre1_20_5ArmorMaterialBridge.registerRedirects(transformer);

        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/SimpleArmorMaterial", null,
                "java/lang/Object", new String[] {"net/minecraft/class_1741"});
        material.visitEnd();
        ClassNode materialOut = transform(
                Pre1_20_5ArmorMaterialBridge.renameLegacyInterface(material.toByteArray()),
                "test/SimpleArmorMaterial");
        assertEquals(List.of(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL), materialOut.interfaces,
                "the old interface became a record, so implementers need the generated interface");

        String typeDesc = "Lnet/minecraft/class_1738$class_8051;";
        String propsDesc = "Lnet/minecraft/class_1792$class_1793;";
        byte[] items = staticMethod("test/Items", "create", "(" + typeDesc + propsDesc + ")V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/class_1738");
            mv.visitInsn(DUP);
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/class_1740", "field_7892", "Lnet/minecraft/class_1740;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_1738", "<init>",
                    "(Lnet/minecraft/class_1741;" + typeDesc + propsDesc + ")V", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });
        assertTrue(Pre1_20_5ArmorMaterialBridge.usesLegacyInterface(items),
                "reading an ArmorMaterials enum constant marks the jar as written against the interface");
        ClassNode out = transform(Pre1_20_5ArmorMaterialBridge.renameLegacyInterface(items), "test/Items");

        List<String> bridged = calls(out).stream()
                .filter(c -> c.owner.equals(Pre1_20_5ArmorMaterialBridge.MATERIALS)).map(c -> c.name).toList();
        assertTrue(bridged.contains("field_7892"), "vanilla IRON must become a legacy view: " + bridged);
        assertTrue(bridged.contains("armorItem"), "new ArmorItem(material, ...) must use the factory: " + bridged);

        assertWellFormed(Pre1_20_5ArmorMaterialBridge.generateMaterials());
        assertWellFormed(Pre1_20_5ArmorMaterialBridge.generateView());
        assertWellFormed(Pre1_20_5ArmorMaterialBridge.generateLegacyArmorItem());
    }

    @Test
    @DisplayName("A 1.20.5+ class that builds the ArmorMaterial record keeps the record")
    void modernArmorMaterialRecordIsLeftAlone() {
        Pre1_20_5ArmorMaterialBridge.registerRedirects(transformer);

        byte[] modern = modernArmorMaterialUser();
        assertFalse(Pre1_20_5ArmorMaterialBridge.usesLegacyInterface(modern),
                "constructing the record and reading its accessors is the 1.20.5+ shape");
        ClassNode out = transform(modern, "test/ModernMaterials");
        assertTrue(instructions(out).stream().anyMatch(insn -> insn.getOpcode() == NEW
                        && ((org.objectweb.asm.tree.TypeInsnNode) insn).desc.equals("net/minecraft/class_1741")),
                "new ArmorMaterial(...) must still build the host record, not the generated interface");
        assertTrue(calls(out).stream().anyMatch(c -> c.owner.equals("net/minecraft/class_1741")
                        && c.getOpcode() == INVOKEVIRTUAL),
                "record accessor calls must keep their owner");
    }

    @Test
    @DisplayName("A legacy jar is renamed as a whole, and a 1.20.5+ jar is not touched")
    void armorMaterialRenameIsDecidedPerJar(@TempDir Path root) throws Exception {
        Pre1_20_5ArmorMaterialBridge.registerRedirects(transformer);

        Path legacyJar = Files.createDirectories(root.resolve("legacy"));
        ClassWriter material = new ClassWriter(0);
        material.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModMaterial", null,
                "java/lang/Object", new String[] {"net/minecraft/class_1741"});
        material.visitEnd();
        Files.write(legacyJar.resolve("ModMaterial.class"), material.toByteArray());
        // Only names the type in a field, so on its own it gives no sign of the old interface.
        ClassWriter holder = new ClassWriter(0);
        holder.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModMaterials", null, "java/lang/Object", null);
        holder.visitField(ACC_PUBLIC | ACC_STATIC, "STEEL", "Lnet/minecraft/class_1741;", null, null).visitEnd();
        holder.visitEnd();
        Files.write(legacyJar.resolve("ModMaterials.class"), holder.toByteArray());

        assertEquals(2, Pre1_20_5ArmorMaterialBridge.renameInLegacyJar(transformer, legacyJar),
                "every class in a legacy jar must move to the interface, or the field and its readers disagree");
        ClassNode renamedHolder = read(Files.readAllBytes(legacyJar.resolve("ModMaterials.class")));
        assertEquals("L" + Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL + ";", renamedHolder.fields.get(0).desc);

        Path modernJar = Files.createDirectories(root.resolve("modern"));
        byte[] modern = modernArmorMaterialUser();
        Files.write(modernJar.resolve("ModernMaterials.class"), modern);
        assertEquals(0, Pre1_20_5ArmorMaterialBridge.renameInLegacyJar(transformer, modernJar),
                "a jar built against the record must keep it");
        assertArrayEquals(modern, Files.readAllBytes(modernJar.resolve("ModernMaterials.class")));
    }

    @Test
    @DisplayName("The generated armor base does not switch on the Mojang-named armor bridge")
    void armorItemBaseNameIsDistinct() {
        assertNotEquals(com.retromod.shim.common.LegacyArmorBridge.ARMOR_ITEM_BASE,
                Pre1_20_5ArmorMaterialBridge.LEGACY_ARMOR_ITEM,
                "sharing the name runs LegacyArmorBridge's material adapter over every class");
    }

    /** A 1.21 mod registering its material: {@code new ArmorMaterial(...)} then {@code defense()}. */
    private static byte[] modernArmorMaterialUser() {
        String recordCtor = "(Ljava/util/Map;ILnet/minecraft/class_6880;Ljava/util/function/Supplier;"
                + "Ljava/util/List;FF)V";
        return staticMethod("test/ModernMaterials", "create", "()V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/class_1741");
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(FCONST_0);
            mv.visitInsn(FCONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_1741", "<init>", recordCtor, false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_1741", "comp_2298", "()Ljava/util/Map;", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });
    }

    @Test
    @DisplayName("The five-argument ModNioResourcePack.create gains the modBundled flag")
    void modNioResourcePackCreateIsBridged() {
        FabricModNioResourcePackBridge.registerRedirects(transformer);

        ClassNode out = transform(staticMethod("test/AddonPacks", "open", "()V", mv -> {
            for (int i = 0; i < 5; i++) mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, FabricModNioResourcePackBridge.PACK, "create",
                    FabricModNioResourcePackBridge.OLD_DESC, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        }), "test/AddonPacks");

        assertTrue(calls(out).stream().anyMatch(c -> c.owner.equals(FabricModNioResourcePackBridge.FACTORY)),
                "the old create call must go through the generated factory");
        MethodInsnNode delegate = findCall(read(FabricModNioResourcePackBridge.generateFactory()), "create");
        assertEquals(FabricModNioResourcePackBridge.NEW_DESC, delegate.desc);
    }

    private ClassNode transform(byte[] input, String name) {
        return read(transformer.transformClass(input, name));
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

    private static void assertWellFormed(byte[] bytes) {
        // Data-flow checks would need Minecraft on the classpath, so only the structure is checked.
        assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
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

    private static MethodInsnNode findCall(ClassNode node, String name) {
        return calls(node).stream().filter(c -> c.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no call to " + name + " in " + node.name));
    }
}
