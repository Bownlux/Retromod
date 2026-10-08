/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.fabric.embedded.LegacyArmorMaterialSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bridges the pre-1.20.5 {@code ArmorMaterial} interface for intermediary-namespace Fabric mods on
 * 1.20.5 to 1.21.1 hosts.
 *
 * <p>1.20.5 turned {@code ArmorMaterial} ({@code class_1741}) from an interface into a registered
 * record, turned the {@code ArmorMaterials} enum constants into holders, moved durability onto
 * {@code Item.Properties}, and made {@code ArmorItem} take a {@code Holder<ArmorMaterial>}. An
 * older mod that implements the interface dies with {@code IncompatibleClassChangeError} when its
 * material class loads, and code that passes a vanilla material fails verification.
 *
 * <p>The old interface is renamed to a generated stand-in with the old method names. The rename is
 * decided per jar by {@link #renameInLegacyJar}, not registered as a class redirect: the record kept
 * the interface's name, so a redirect would also rewrite a 1.20.5+ mod's {@code new ArmorMaterial}
 * into an interface instantiation. Vanilla
 * constants become views of their holders that answer the old methods. {@code new ArmorItem} and
 * {@code super(...)} calls convert a legacy material to a holder: a view returns its own holder, and
 * a mod material is built into the modern record once and registered, or wrapped as a direct holder
 * when the registry is already frozen. Its old per-type durability is applied to the item
 * properties, as the old constructor did.
 *
 * <p>{@code DyeableArmorItem} was deleted in the same release, since dye color became an item
 * component. A mod item extending it is moved onto the same generated armor base, so it loads as
 * plain armor. Its dye color methods are not bridged.
 *
 * <p>{@code RecordItem} was deleted in 1.21, when jukebox songs became data. A mod item extending
 * it is moved onto a generated item that keeps the old constructor and the static {@code BY_NAME}
 * map, so the mod loads. The disc does not play.
 *
 * <p>The redirect is host-gated to the 1.20.5 record shape, because 1.21.2 reworked armor materials
 * again. A 1.20.5 to 1.21 mod transformed on such a host that uses the record directly is not
 * supported. {@code ArmorItem.getMaterial()} still returns a holder, so mod code calling it fails.
 */
public final class Pre1_20_5ArmorMaterialBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String ARMOR_MATERIAL = "net/minecraft/class_1741";
    static final String ARMOR_MATERIALS = "net/minecraft/class_1740";
    static final String ARMOR_ITEM = "net/minecraft/class_1738";
    static final String DYEABLE_ARMOR_ITEM = "net/minecraft/class_4057";
    static final String RECORD_ITEM = "net/minecraft/class_1813";
    static final String LEGACY_RECORD_ITEM = "com/retromod/generated/LegacyRecordItem";
    static final String ITEM = "net/minecraft/class_1792";
    static final String RECORD_BY_NAME = "field_8901";
    static final String ARMOR_TYPE = "net/minecraft/class_1738$class_8051";
    static final String LAYER = "net/minecraft/class_1741$class_9196";
    static final String ITEM_PROPERTIES = "net/minecraft/class_1792$class_1793";
    static final String HOLDER = "net/minecraft/class_6880";
    static final String REGISTRY = "net/minecraft/class_2378";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/class_7923";
    static final String IDENTIFIER = "net/minecraft/class_2960";
    static final String SOUND_EVENT = "net/minecraft/class_3414";
    static final String INGREDIENT = "net/minecraft/class_1856";

    static final String LEGACY_MATERIAL = "com/retromod/generated/LegacyArmorMaterial";
    static final String MATERIAL_VIEW = "com/retromod/generated/LegacyArmorMaterialView";
    static final String REPAIR_SUPPLIER = "com/retromod/generated/LegacyArmorRepairSupplier";
    static final String MATERIALS = "com/retromod/generated/LegacyArmorMaterials";
    // Not LegacyArmorItem: that name is LegacyArmorBridge's Mojang-named base, and its presence
    // switches on that bridge's material adapter for every class.
    static final String LEGACY_ARMOR_ITEM = "com/retromod/generated/LegacyHolderArmorItem";
    static final String SUPPORT = "com/retromod/shim/fabric/embedded/LegacyArmorMaterialSupport";

    private static final String L_TYPE = "L" + ARMOR_TYPE + ";";
    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_LEGACY = "L" + LEGACY_MATERIAL + ";";
    private static final String L_PROPERTIES = "L" + ITEM_PROPERTIES + ";";
    private static final String NEW_RECORD_DESC = "(Ljava/lang/Object;Ljava/util/Map;ILjava/lang/Object;"
            + "Ljava/util/function/Supplier;Ljava/util/List;FF)Ljava/lang/Object;";
    private static final String RECORD_CTOR = "(Ljava/util/Map;IL" + HOLDER
            + ";Ljava/util/function/Supplier;Ljava/util/List;FF)V";

    /** Old {@code ArmorMaterial} methods by intermediary name, in the 1.20.1 interface order. */
    static final String DURABILITY = "method_48402";
    static final String DEFENSE = "method_48403";
    static final String ENCHANTMENT_VALUE = "method_7699";
    static final String EQUIP_SOUND = "method_7698";
    static final String REPAIR_INGREDIENT = "method_7695";
    static final String NAME = "method_7694";
    static final String TOUGHNESS = "method_7700";
    static final String KNOCKBACK_RESISTANCE = "method_24355";

    /**
     * Vanilla materials with their 1.20.1 durability multipliers, which moved off the material in
     * 1.20.5. Order: leather, chainmail, iron, gold, diamond, turtle, netherite.
     */
    static final String[] VANILLA_FIELDS = {
        "field_7897", "field_7887", "field_7892", "field_7895", "field_7889", "field_7890", "field_21977",
    };
    static final int[] VANILLA_DURABILITY_MULTIPLIERS = {5, 15, 15, 7, 33, 25, 37};

    /** {@code ArmorItem.Type} HELMET, CHESTPLATE, LEGGINGS, BOOTS: the types before BODY. */
    static final String[] LEGACY_ARMOR_TYPES = {"field_41934", "field_41935", "field_41936", "field_41937"};

    private Pre1_20_5ArmorMaterialBridge() {}

    /** Registers only on a host whose ArmorMaterial is the 1.20.5 to 1.21.1 record. */
    public static void register(RetromodTransformer transformer) {
        ClassNode material = ClassResourceInspector.read(ARMOR_MATERIAL);
        ClassNode armorItem = ClassResourceInspector.read(ARMOR_ITEM);
        if (material == null || armorItem == null) {
            LOGGER.debug("ArmorMaterial support is not needed because class_1741 is unavailable");
            return;
        }
        boolean recordShape = (material.access & Opcodes.ACC_INTERFACE) == 0
                && hasMethod(material, "<init>", RECORD_CTOR)
                && hasMethod(armorItem, "<init>", "(" + L_HOLDER + L_TYPE + L_PROPERTIES + ")V");
        if (!recordShape) {
            LOGGER.debug("The host ArmorMaterial is not the 1.20.5 record shape");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for the pre-1.20.5 ArmorMaterial interface");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyArmorMaterialSupport.class);
        transformer.registerSyntheticClass(LEGACY_MATERIAL, generateLegacyInterface());
        transformer.registerSyntheticClass(MATERIAL_VIEW, generateView());
        transformer.registerSyntheticClass(REPAIR_SUPPLIER, generateRepairSupplier());
        transformer.registerSyntheticClass(MATERIALS, generateMaterials());
        transformer.registerSyntheticClass(LEGACY_ARMOR_ITEM, generateLegacyArmorItem());

        for (String field : VANILLA_FIELDS) {
            transformer.registerFieldRedirect(ARMOR_MATERIALS, field, "L" + ARMOR_MATERIALS + ";",
                    MATERIALS, field, "()" + L_LEGACY);
        }
        String oldItemCtor = "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")V";
        transformer.registerConstructorRedirect(ARMOR_ITEM, oldItemCtor,
                MATERIALS, "armorItem", "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")L" + ARMOR_ITEM + ";");
        transformer.registerSuperclassRebase(ARMOR_ITEM, LEGACY_ARMOR_ITEM);
        if (!ClassResourceInspector.exists(DYEABLE_ARMOR_ITEM)) {
            transformer.registerClassRedirect(DYEABLE_ARMOR_ITEM, LEGACY_ARMOR_ITEM);
        }
        if (!ClassResourceInspector.exists(RECORD_ITEM)) {
            transformer.registerSyntheticClass(LEGACY_RECORD_ITEM, generateLegacyRecordItem());
            transformer.registerClassRedirect(RECORD_ITEM, LEGACY_RECORD_ITEM);
        }
    }

    /**
     * Renames {@code ArmorMaterial} to the generated interface in every class under
     * {@code classRoot}, when this bridge is registered and the jar was written against the old
     * interface. It must run before the class transform, because the member redirects above are
     * keyed on the renamed descriptors. The whole jar gets one answer, so a field typed as the
     * material and the code reading it always agree.
     *
     * @return the number of classes rewritten
     */
    public static int renameInLegacyJar(RetromodTransformer transformer, Path classRoot) throws IOException {
        if (!transformer.getSyntheticClasses().containsKey(LEGACY_MATERIAL)) return 0;
        List<Path> classFiles;
        try (var stream = Files.walk(classRoot)) {
            classFiles = stream.filter(path -> path.toString().endsWith(".class")).toList();
        }
        boolean legacy = false;
        for (Path classFile : classFiles) {
            if (usesLegacyInterface(Files.readAllBytes(classFile))) {
                legacy = true;
                break;
            }
        }
        if (!legacy) return 0;
        int renamed = 0;
        for (Path classFile : classFiles) {
            byte[] original = Files.readAllBytes(classFile);
            byte[] updated = renameLegacyInterface(original);
            if (updated != original) {
                Files.write(classFile, updated);
                renamed++;
            }
        }
        LOGGER.debug("Moved {} classes onto the pre-1.20.5 ArmorMaterial interface", renamed);
        return renamed;
    }

    /**
     * Whether a class uses {@code ArmorMaterial} as the pre-1.20.5 interface: it implements it,
     * calls it as an interface, reads an {@code ArmorMaterials} enum constant, or passes a material
     * to the old {@code ArmorItem} constructor. A 1.20.5+ class constructs the record and reads its
     * accessors instead, and none of these shapes link against the record.
     */
    static boolean usesLegacyInterface(byte[] classBytes) {
        if (!mentions(classBytes, "net/minecraft/class_174")) return false;
        boolean[] legacy = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                if (interfaces != null && List.of(interfaces).contains(ARMOR_MATERIAL)) legacy[0] = true;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                            boolean isInterface) {
                        if (owner.equals(ARMOR_MATERIAL) && opcode == Opcodes.INVOKEINTERFACE) {
                            legacy[0] = true;
                        }
                        if (owner.equals(ARMOR_ITEM) && method.equals("<init>")
                                && desc.startsWith("(L" + ARMOR_MATERIAL + ";")) {
                            legacy[0] = true;
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        if (owner.equals(ARMOR_MATERIALS) && desc.equals("L" + ARMOR_MATERIALS + ";")) {
                            legacy[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return legacy[0];
    }

    /** Renames {@code ArmorMaterial} to the generated interface, or returns the input unchanged. */
    static byte[] renameLegacyInterface(byte[] classBytes) {
        if (!mentions(classBytes, ARMOR_MATERIAL)) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        // The single-map constructor is the one older loader-provided ASM releases also have.
        reader.accept(new ClassRemapper(writer,
                new SimpleRemapper(java.util.Map.of(ARMOR_MATERIAL, LEGACY_MATERIAL))), 0);
        return writer.toByteArray();
    }

    /**
     * A cheap constant-pool check before parsing. The detection prefix {@code class_174} covers
     * both ArmorMaterial ({@code class_1741}) and ArmorMaterials ({@code class_1740}).
     */
    private static boolean mentions(byte[] classBytes, String name) {
        return new String(classBytes, StandardCharsets.ISO_8859_1).contains(name);
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when these classes are generated.
                return "java/lang/Object";
            }
        };
    }

    private static final String[][] LEGACY_METHODS = {
        {DURABILITY, "(" + L_TYPE + ")I"},
        {DEFENSE, "(" + L_TYPE + ")I"},
        {ENCHANTMENT_VALUE, "()I"},
        {EQUIP_SOUND, "()L" + SOUND_EVENT + ";"},
        {REPAIR_INGREDIENT, "()L" + INGREDIENT + ";"},
        {NAME, "()Ljava/lang/String;"},
        {TOUGHNESS, "()F"},
        {KNOCKBACK_RESISTANCE, "()F"},
    };

    static byte[] generateLegacyInterface() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                LEGACY_MATERIAL, null, "java/lang/Object", null);
        for (String[] method : LEGACY_METHODS) {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method[0], method[1], null, null)
                    .visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A vanilla material's holder answering the old interface. */
    static byte[] generateView() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                MATERIAL_VIEW, null, "java/lang/Object", new String[] {LEGACY_MATERIAL});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "holder", L_HOLDER, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "durabilityMultiplier", "I", null, null)
                .visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + L_HOLDER + "I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, MATERIAL_VIEW, "holder", L_HOLDER);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, MATERIAL_VIEW, "durabilityMultiplier", "I");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor durability = cw.visitMethod(Opcodes.ACC_PUBLIC, DURABILITY, "(" + L_TYPE + ")I", null, null);
        durability.visitCode();
        durability.visitVarInsn(Opcodes.ALOAD, 1);
        durability.visitVarInsn(Opcodes.ALOAD, 0);
        durability.visitFieldInsn(Opcodes.GETFIELD, MATERIAL_VIEW, "durabilityMultiplier", "I");
        durability.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARMOR_TYPE, "method_56690", "(I)I", false);
        durability.visitInsn(Opcodes.IRETURN);
        durability.visitMaxs(0, 0);
        durability.visitEnd();

        MethodVisitor defense = beginRecordRead(cw, DEFENSE, "(" + L_TYPE + ")I");
        defense.visitVarInsn(Opcodes.ALOAD, 1);
        readInt(defense, "method_48403", true);
        endRead(defense, Opcodes.IRETURN);

        MethodVisitor enchantment = beginRecordRead(cw, ENCHANTMENT_VALUE, "()I");
        readInt(enchantment, "comp_2299", false);
        endRead(enchantment, Opcodes.IRETURN);

        MethodVisitor sound = beginRecordRead(cw, EQUIP_SOUND, "()L" + SOUND_EVENT + ";");
        readObject(sound, "comp_2300");
        sound.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        sound.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "comp_349", "()Ljava/lang/Object;", true);
        sound.visitTypeInsn(Opcodes.CHECKCAST, SOUND_EVENT);
        endRead(sound, Opcodes.ARETURN);

        MethodVisitor repair = beginRecordRead(cw, REPAIR_INGREDIENT, "()L" + INGREDIENT + ";");
        readObject(repair, "comp_2301");
        repair.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Supplier");
        repair.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
                "()Ljava/lang/Object;", true);
        repair.visitTypeInsn(Opcodes.CHECKCAST, INGREDIENT);
        endRead(repair, Opcodes.ARETURN);

        // The old name was the bare path, such as "iron".
        MethodVisitor name = cw.visitMethod(Opcodes.ACC_PUBLIC, NAME, "()Ljava/lang/String;", null, null);
        name.visitCode();
        name.visitVarInsn(Opcodes.ALOAD, 0);
        name.visitFieldInsn(Opcodes.GETFIELD, MATERIAL_VIEW, "holder", L_HOLDER);
        name.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "method_55840", "()Ljava/lang/String;", true);
        name.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "method_60654",
                "(Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        name.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IDENTIFIER, "method_12832", "()Ljava/lang/String;", false);
        name.visitInsn(Opcodes.ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();

        MethodVisitor toughness = beginRecordRead(cw, TOUGHNESS, "()F");
        readFloat(toughness, "comp_2303");
        endRead(toughness, Opcodes.FRETURN);

        MethodVisitor knockback = beginRecordRead(cw, KNOCKBACK_RESISTANCE, "()F");
        readFloat(knockback, "comp_2304");
        endRead(knockback, Opcodes.FRETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor beginRecordRead(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, MATERIAL_VIEW, "holder", L_HOLDER);
        // The record is read reflectively, so this view never links against the record type that
        // shares its name with the interface the mod was compiled against.
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "comp_349", "()Ljava/lang/Object;", true);
        return mv;
    }

    private static void endRead(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Lazily asks the mod material for its repair ingredient, as the old interface did. */
    static byte[] generateRepairSupplier() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                REPAIR_SUPPLIER, null, "java/lang/Object", new String[] {"java/util/function/Supplier"});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "material", "Ljava/lang/Object;", null, null)
                .visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, REPAIR_SUPPLIER, "material", "Ljava/lang/Object;");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, REPAIR_SUPPLIER, "material", "Ljava/lang/Object;");
        readObject(get, REPAIR_INGREDIENT);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Vanilla constant getters, the legacy-to-holder conversion, and the ArmorItem factory. */
    static byte[] generateMaterials() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                MATERIALS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "HOLDERS",
                "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/IdentityHashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                "(Ljava/util/Map;)Ljava/util/Map;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, MATERIALS, "HOLDERS", "Ljava/util/Map;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        for (int i = 0; i < VANILLA_FIELDS.length; i++) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    VANILLA_FIELDS[i], "()" + L_LEGACY, null, null);
            mv.visitCode();
            mv.visitTypeInsn(Opcodes.NEW, MATERIAL_VIEW);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETSTATIC, ARMOR_MATERIALS, VANILLA_FIELDS[i], L_HOLDER);
            mv.visitIntInsn(Opcodes.BIPUSH, VANILLA_DURABILITY_MULTIPLIERS[i]);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MATERIAL_VIEW, "<init>", "(" + L_HOLDER + "I)V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        emitToHolder(cw);
        emitCreateHolder(cw);
        emitWithDurability(cw);

        MethodVisitor factory = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "armorItem",
                "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")L" + ARMOR_ITEM + ";", null, null);
        factory.visitCode();
        factory.visitTypeInsn(Opcodes.NEW, ARMOR_ITEM);
        factory.visitInsn(Opcodes.DUP);
        factory.visitVarInsn(Opcodes.ALOAD, 0);
        factory.visitMethodInsn(Opcodes.INVOKESTATIC, MATERIALS, "toHolder", "(" + L_LEGACY + ")" + L_HOLDER, false);
        factory.visitVarInsn(Opcodes.ALOAD, 1);
        factory.visitVarInsn(Opcodes.ALOAD, 0);
        factory.visitVarInsn(Opcodes.ALOAD, 1);
        factory.visitVarInsn(Opcodes.ALOAD, 2);
        factory.visitMethodInsn(Opcodes.INVOKESTATIC, MATERIALS, "withDurability",
                "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")" + L_PROPERTIES, false);
        factory.visitMethodInsn(Opcodes.INVOKESPECIAL, ARMOR_ITEM, "<init>",
                "(" + L_HOLDER + L_TYPE + L_PROPERTIES + ")V", false);
        factory.visitInsn(Opcodes.ARETURN);
        factory.visitMaxs(0, 0);
        factory.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code toHolder(material)}: a view's holder, else a cached holder built from the mod's values. */
    private static void emitToHolder(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "toHolder",
                "(" + L_LEGACY + ")" + L_HOLDER, null, null);
        mv.visitCode();
        Label notView = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "viewHolder",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, notView);
        mv.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(notView);
        mv.visitInsn(Opcodes.POP);
        Label create = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, MATERIALS, "HOLDERS", "Ljava/util/Map;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, create);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(create);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MATERIALS, "createHolder", "(" + L_LEGACY + ")" + L_HOLDER, false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, MATERIALS, "HOLDERS", "Ljava/util/Map;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Builds the modern record from the mod's old methods and registers it under its old name.
     * Registration fails once the registry is frozen, such as for an addon pack read at server
     * start, and the record is then served as a direct holder.
     */
    private static void emitCreateHolder(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createHolder",
                "(" + L_LEGACY + ")" + L_HOLDER, null, null);
        mv.visitCode();
        // defense = new EnumMap(Type.class), filled for the four types the old interface knew.
        // BODY arrived with 1.20.5, and an old material's per-type table has no entry for it.
        mv.visitTypeInsn(Opcodes.NEW, "java/util/EnumMap");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(Type.getObjectType(ARMOR_TYPE));
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/EnumMap", "<init>", "(Ljava/lang/Class;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        for (String typeField : LEGACY_ARMOR_TYPES) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETSTATIC, ARMOR_TYPE, typeField, L_TYPE);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETSTATIC, ARMOR_TYPE, typeField, L_TYPE);
            readInt(mv, DEFENSE, true);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/EnumMap", "put",
                    "(Ljava/lang/Enum;Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.POP);
        }

        // id = ResourceLocation.parse(m.getName())
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        readObject(mv, NAME);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "method_60654",
                "(Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);

        // record = new ArmorMaterial(defense, enchant, soundHolder, repair, List.of(new Layer(id)), tough, kb)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        readInt(mv, ENCHANTMENT_VALUE, false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES, "field_41172", "L" + REGISTRY + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        readObject(mv, EQUIP_SOUND);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, REGISTRY, "method_47983",
                "(Ljava/lang/Object;)" + L_HOLDER, true);
        mv.visitTypeInsn(Opcodes.NEW, REPAIR_SUPPLIER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, REPAIR_SUPPLIER, "<init>", "(Ljava/lang/Object;)V", false);
        mv.visitTypeInsn(Opcodes.NEW, LAYER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LAYER, "<init>", "(L" + IDENTIFIER + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of",
                "(Ljava/lang/Object;)Ljava/util/List;", true);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        readFloat(mv, TOUGHNESS);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        readFloat(mv, KNOCKBACK_RESISTANCE);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "newRecord", NEW_RECORD_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label frozen = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, frozen, "java/lang/RuntimeException");
        mv.visitLabel(tryStart);
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN_REGISTRIES, "field_48976", "L" + REGISTRY + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "method_47985",
                "(L" + REGISTRY + ";L" + IDENTIFIER + ";Ljava/lang/Object;)L" + HOLDER + "$class_6883;", true);
        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(frozen);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER, "method_40223", "(Ljava/lang/Object;)" + L_HOLDER, true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code props.durability(material.getDurabilityForType(type))}, which the old constructor applied. */
    private static void emitWithDurability(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withDurability",
                "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")" + L_PROPERTIES, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        readInt(mv, DURABILITY, true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_PROPERTIES, "method_7895", "(I)" + L_PROPERTIES, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Reads one old method through {@link LegacyArmorMaterialSupport}. The material, and the type
     * argument when {@code withArgument} is set, are already on the stack.
     */
    private static void readInt(MethodVisitor mv, String name, boolean withArgument) {
        if (withArgument) {
            mv.visitLdcInsn(name);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "intValue",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)I", false);
        } else {
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "intValue",
                    "(Ljava/lang/Object;Ljava/lang/String;)I", false);
        }
    }

    private static void readFloat(MethodVisitor mv, String name) {
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "floatValue",
                "(Ljava/lang/Object;Ljava/lang/String;)F", false);
    }

    private static void readObject(MethodVisitor mv, String name) {
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "value",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
    }

    /** Plain item base keeping RecordItem's old constructor and its static BY_NAME map. */
    static byte[] generateLegacyRecordItem() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LEGACY_RECORD_ITEM, null, ITEM, null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, RECORD_BY_NAME,
                "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, LEGACY_RECORD_ITEM, RECORD_BY_NAME, "Ljava/util/Map;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        // RecordItem(int comparatorOutput, SoundEvent sound, Properties properties, int lengthInSeconds)
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(IL" + SOUND_EVENT + ";" + L_PROPERTIES + "I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 3);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM, "<init>", "(" + L_PROPERTIES + ")V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Base for mod armor items, accepting the old {@code super(material, type, properties)}. */
    static byte[] generateLegacyArmorItem() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LEGACY_ARMOR_ITEM, null, ARMOR_ITEM, null);

        MethodVisitor legacy = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")V", null, null);
        legacy.visitCode();
        legacy.visitVarInsn(Opcodes.ALOAD, 0);
        legacy.visitVarInsn(Opcodes.ALOAD, 1);
        legacy.visitMethodInsn(Opcodes.INVOKESTATIC, MATERIALS, "toHolder", "(" + L_LEGACY + ")" + L_HOLDER, false);
        legacy.visitVarInsn(Opcodes.ALOAD, 2);
        legacy.visitVarInsn(Opcodes.ALOAD, 1);
        legacy.visitVarInsn(Opcodes.ALOAD, 2);
        legacy.visitVarInsn(Opcodes.ALOAD, 3);
        legacy.visitMethodInsn(Opcodes.INVOKESTATIC, MATERIALS, "withDurability",
                "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")" + L_PROPERTIES, false);
        legacy.visitMethodInsn(Opcodes.INVOKESPECIAL, ARMOR_ITEM, "<init>",
                "(" + L_HOLDER + L_TYPE + L_PROPERTIES + ")V", false);
        legacy.visitInsn(Opcodes.RETURN);
        legacy.visitMaxs(0, 0);
        legacy.visitEnd();

        MethodVisitor modern = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(" + L_HOLDER + L_TYPE + L_PROPERTIES + ")V", null, null);
        modern.visitCode();
        modern.visitVarInsn(Opcodes.ALOAD, 0);
        modern.visitVarInsn(Opcodes.ALOAD, 1);
        modern.visitVarInsn(Opcodes.ALOAD, 2);
        modern.visitVarInsn(Opcodes.ALOAD, 3);
        modern.visitMethodInsn(Opcodes.INVOKESPECIAL, ARMOR_ITEM, "<init>",
                "(" + L_HOLDER + L_TYPE + L_PROPERTIES + ")V", false);
        modern.visitInsn(Opcodes.RETURN);
        modern.visitMaxs(0, 0);
        modern.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
