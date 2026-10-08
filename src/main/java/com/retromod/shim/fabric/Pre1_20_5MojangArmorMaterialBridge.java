/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.embedded.LegacyArmorMaterialSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the pre-1.20.5 {@code ArmorMaterial} interface for Mojang-named mods, which is what a
 * Forge 1.20.1 mod becomes after its SRG names are remapped, and what a NeoForge 1.20.4 mod
 * already is.
 *
 * <p>1.20.5 turned the interface into a registered record, so a mod enum that implements it dies
 * with {@code IncompatibleClassChangeError} as soon as it loads. The fix is the same as the
 * intermediary bridge in {@link Pre1_20_5ArmorMaterialBridge}: the old interface becomes a
 * generated stand-in, vanilla constants become views of their holders, and the old
 * {@code ArmorItem} constructor builds and registers the record from the mod's old methods. This
 * class reuses that bridge's generated classes and only translates their Minecraft names from
 * intermediary to Mojang, so both namespaces keep one implementation. It lives beside that
 * bridge because the generators are package-private.
 *
 * <p>The bridge is host-gated to the 1.20.5 to 1.21.1 record. 1.21.2 reworked the record again
 * and moved armor into item components, so a later host gets nothing from this class, and an old
 * material there still fails to load.
 */
public final class Pre1_20_5MojangArmorMaterialBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String ARMOR_MATERIAL = "net/minecraft/world/item/ArmorMaterial";
    static final String ARMOR_MATERIALS = "net/minecraft/world/item/ArmorMaterials";
    static final String ARMOR_ITEM = "net/minecraft/world/item/ArmorItem";
    static final String DYEABLE_ARMOR_ITEM = "net/minecraft/world/item/DyeableArmorItem";
    static final String ARMOR_TYPE = "net/minecraft/world/item/ArmorItem$Type";
    static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    static final String HOLDER = "net/minecraft/core/Holder";

    private static final String L_TYPE = "L" + ARMOR_TYPE + ";";
    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_PROPERTIES = "L" + ITEM_PROPERTIES + ";";
    private static final String L_LEGACY = "L" + Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL + ";";
    private static final String RECORD_CTOR = "(Ljava/util/Map;IL" + HOLDER
            + ";Ljava/util/function/Supplier;Ljava/util/List;FF)V";

    /** Vanilla material constants, in the order of the intermediary bridge's field table. */
    static final String[] VANILLA_FIELDS = {"LEATHER", "CHAIN", "IRON", "GOLD", "DIAMOND", "TURTLE", "NETHERITE"};

    /** The 1.21.1 class for each intermediary class the generated code names. */
    private static final Map<String, String> CLASSES = Map.ofEntries(
            Map.entry(Pre1_20_5ArmorMaterialBridge.ARMOR_MATERIAL, ARMOR_MATERIAL),
            Map.entry(Pre1_20_5ArmorMaterialBridge.ARMOR_MATERIALS, ARMOR_MATERIALS),
            Map.entry(Pre1_20_5ArmorMaterialBridge.ARMOR_ITEM, ARMOR_ITEM),
            Map.entry(Pre1_20_5ArmorMaterialBridge.ARMOR_TYPE, ARMOR_TYPE),
            Map.entry(Pre1_20_5ArmorMaterialBridge.LAYER, ARMOR_MATERIAL + "$Layer"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.ITEM, "net/minecraft/world/item/Item"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.ITEM_PROPERTIES, ITEM_PROPERTIES),
            Map.entry(Pre1_20_5ArmorMaterialBridge.HOLDER, HOLDER),
            Map.entry(Pre1_20_5ArmorMaterialBridge.HOLDER + "$class_6883", HOLDER + "$Reference"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.REGISTRY, "net/minecraft/core/Registry"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.BUILT_IN_REGISTRIES,
                    "net/minecraft/core/registries/BuiltInRegistries"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.IDENTIFIER, "net/minecraft/resources/ResourceLocation"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.SOUND_EVENT, "net/minecraft/sounds/SoundEvent"),
            Map.entry(Pre1_20_5ArmorMaterialBridge.INGREDIENT, "net/minecraft/world/item/crafting/Ingredient"));

    /** The old interface's methods, by intermediary name, with their 1.20.1 Mojang names. */
    private static final Map<String, String> LEGACY_METHODS = Map.of(
            Pre1_20_5ArmorMaterialBridge.DURABILITY, "getDurabilityForType",
            Pre1_20_5ArmorMaterialBridge.DEFENSE, "getDefenseForType",
            Pre1_20_5ArmorMaterialBridge.ENCHANTMENT_VALUE, "getEnchantmentValue",
            Pre1_20_5ArmorMaterialBridge.EQUIP_SOUND, "getEquipSound",
            Pre1_20_5ArmorMaterialBridge.REPAIR_INGREDIENT, "getRepairIngredient",
            Pre1_20_5ArmorMaterialBridge.NAME, "getName",
            Pre1_20_5ArmorMaterialBridge.TOUGHNESS, "getToughness",
            Pre1_20_5ArmorMaterialBridge.KNOCKBACK_RESISTANCE, "getKnockbackResistance");

    /** Host members the generated code calls or reads, keyed by intermediary owner and name. */
    private static final Map<String, String> MEMBERS = buildMemberNames();

    /**
     * Method names the generated code passes to {@link LegacyArmorMaterialSupport} as strings,
     * plus the record class name the support class loads. A view reads the record, whose
     * per-type defense method is {@code getDefense}; everything else reads a mod's old material.
     */
    private static final Map<String, String> STRINGS = buildStrings(false);
    private static final Map<String, String> VIEW_STRINGS = buildStrings(true);

    private Pre1_20_5MojangArmorMaterialBridge() {}

    /** Registers only on a Mojang-named host whose ArmorMaterial is the 1.20.5 to 1.21.1 record. */
    public static void register(RetromodTransformer transformer) {
        ClassNode material = ClassResourceInspector.read(ARMOR_MATERIAL);
        ClassNode armorItem = ClassResourceInspector.read(ARMOR_ITEM);
        if (material == null || armorItem == null) {
            LOGGER.debug("Mojang-named ArmorMaterial support is not needed because the class is unavailable");
            return;
        }
        if (!isRecordShape(material, armorItem)) {
            LOGGER.debug("The host ArmorMaterial is not the 1.20.5 record shape");
            return;
        }
        registerRedirects(transformer, !ClassResourceInspector.exists(DYEABLE_ARMOR_ITEM));
        LOGGER.debug("Added Mojang-named support for the pre-1.20.5 ArmorMaterial interface");
    }

    static boolean isRecordShape(ClassNode material, ClassNode armorItem) {
        return (material.access & Opcodes.ACC_INTERFACE) == 0
                && hasMethod(material, "<init>", RECORD_CTOR)
                && hasMethod(armorItem, "<init>", "(" + L_HOLDER + L_TYPE + L_PROPERTIES + ")V");
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, boolean dyeableArmorRemoved) {
        transformer.registerSyntheticClass(Pre1_20_5ArmorMaterialBridge.SUPPORT, supportClass());
        transformer.registerSyntheticClass(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL,
                toMojang(Pre1_20_5ArmorMaterialBridge.generateLegacyInterface(), STRINGS));
        transformer.registerSyntheticClass(Pre1_20_5ArmorMaterialBridge.MATERIAL_VIEW,
                toMojang(Pre1_20_5ArmorMaterialBridge.generateView(), VIEW_STRINGS));
        transformer.registerSyntheticClass(Pre1_20_5ArmorMaterialBridge.REPAIR_SUPPLIER,
                toMojang(Pre1_20_5ArmorMaterialBridge.generateRepairSupplier(), STRINGS));
        transformer.registerSyntheticClass(Pre1_20_5ArmorMaterialBridge.MATERIALS,
                toMojang(Pre1_20_5ArmorMaterialBridge.generateMaterials(), STRINGS));
        transformer.registerSyntheticClass(Pre1_20_5ArmorMaterialBridge.LEGACY_ARMOR_ITEM,
                toMojang(Pre1_20_5ArmorMaterialBridge.generateLegacyArmorItem(), STRINGS));

        for (String field : VANILLA_FIELDS) {
            transformer.registerFieldRedirect(ARMOR_MATERIALS, field, "L" + ARMOR_MATERIALS + ";",
                    Pre1_20_5ArmorMaterialBridge.MATERIALS, field, "()" + L_LEGACY);
        }
        transformer.registerConstructorRedirect(ARMOR_ITEM, "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")V",
                Pre1_20_5ArmorMaterialBridge.MATERIALS, "armorItem",
                "(" + L_LEGACY + L_TYPE + L_PROPERTIES + ")L" + ARMOR_ITEM + ";");
        transformer.registerSuperclassRebase(ARMOR_ITEM, Pre1_20_5ArmorMaterialBridge.LEGACY_ARMOR_ITEM);
        if (dyeableArmorRemoved) {
            // Dye color became an item component, so a dyeable item loads as plain armor.
            transformer.registerClassRedirect(DYEABLE_ARMOR_ITEM, Pre1_20_5ArmorMaterialBridge.LEGACY_ARMOR_ITEM);
        }
    }

    /**
     * Renames {@code ArmorMaterial} to the generated interface in every class under
     * {@code classRoot}, when this bridge is registered and the jar was written against the old
     * interface. It runs before the class transform, because the redirects above are keyed on the
     * renamed descriptors. SRG member names are global, so renaming the owner first does not stop
     * the later SRG remap from naming the mod's methods.
     *
     * @return the number of classes rewritten
     */
    public static int renameInLegacyJar(RetromodTransformer transformer, Path classRoot) throws IOException {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        byte[] registered = synthetics.get(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL);
        // The intermediary bridge registers the same stand-in name on a Fabric host, so check
        // that this one names the Mojang record before renaming Mojang-named classes.
        if (registered == null || !isMojangStandIn(registered)) return 0;
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
        LOGGER.info("Bridged the pre-1.20.5 ArmorMaterial interface in {} classes", renamed);
        return renamed;
    }

    /**
     * Whether a class uses {@code ArmorMaterial} as the pre-1.20.5 interface: it implements it,
     * calls it as an interface, reads an {@code ArmorMaterials} enum constant, or passes a material
     * to the old {@code ArmorItem} constructor. A 1.20.5+ class builds the record and reads holder
     * constants instead, so it never matches.
     */
    static boolean usesLegacyInterface(byte[] classBytes) {
        if (!mentions(classBytes, ARMOR_MATERIAL)) return false;
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
        reader.accept(new ClassRemapper(writer, new org.objectweb.asm.commons.SimpleRemapper(
                Map.of(ARMOR_MATERIAL, Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL))), 0);
        return writer.toByteArray();
    }

    private static boolean isMojangStandIn(byte[] legacyInterface) {
        ClassNode node = new ClassNode();
        new ClassReader(legacyInterface).accept(node, ClassReader.SKIP_CODE);
        return hasMethod(node, "getDurabilityForType", "(" + L_TYPE + ")I");
    }

    /** Translates one generated class from intermediary to Mojang names. */
    static byte[] toMojang(byte[] generated, Map<String, String> strings) {
        return IntermediaryToMojangCopy.translate(generated, CLASSES, MEMBERS, strings);
    }

    /** The support class with the record's Mojang class name in place of the intermediary one. */
    static byte[] supportClass() {
        return IntermediaryToMojangCopy.translateResource(LegacyArmorMaterialSupport.class, CLASSES, MEMBERS, STRINGS);
    }

    private static Map<String, String> buildMemberNames() {
        Map<String, String> names = new HashMap<>();
        String type = Pre1_20_5ArmorMaterialBridge.ARMOR_TYPE;
        String holder = Pre1_20_5ArmorMaterialBridge.HOLDER;
        String identifier = Pre1_20_5ArmorMaterialBridge.IDENTIFIER;
        String registry = Pre1_20_5ArmorMaterialBridge.REGISTRY;
        String builtIn = Pre1_20_5ArmorMaterialBridge.BUILT_IN_REGISTRIES;
        names.put(type + ".method_56690", "getDurability");
        names.put(holder + ".comp_349", "value");
        names.put(holder + ".method_55840", "getRegisteredName");
        names.put(holder + ".method_40223", "direct");
        names.put(identifier + ".method_60654", "parse");
        names.put(identifier + ".method_12832", "getPath");
        names.put(registry + ".method_47983", "wrapAsHolder");
        names.put(registry + ".method_47985", "registerForHolder");
        names.put(Pre1_20_5ArmorMaterialBridge.ITEM_PROPERTIES + ".method_7895", "durability");
        names.put(builtIn + ".field_41172", "SOUND_EVENT");
        names.put(builtIn + ".field_48976", "ARMOR_MATERIAL");
        String[] armorTypes = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        for (int i = 0; i < armorTypes.length; i++) {
            names.put(type + "." + Pre1_20_5ArmorMaterialBridge.LEGACY_ARMOR_TYPES[i], armorTypes[i]);
        }
        for (int i = 0; i < VANILLA_FIELDS.length; i++) {
            String intermediary = Pre1_20_5ArmorMaterialBridge.VANILLA_FIELDS[i];
            names.put(Pre1_20_5ArmorMaterialBridge.ARMOR_MATERIALS + "." + intermediary, VANILLA_FIELDS[i]);
            // The generated getters are named after the field they replace.
            names.put(Pre1_20_5ArmorMaterialBridge.MATERIALS + "." + intermediary, VANILLA_FIELDS[i]);
        }
        for (Map.Entry<String, String> method : LEGACY_METHODS.entrySet()) {
            names.put(Pre1_20_5ArmorMaterialBridge.LEGACY_MATERIAL + "." + method.getKey(), method.getValue());
            names.put(Pre1_20_5ArmorMaterialBridge.MATERIAL_VIEW + "." + method.getKey(), method.getValue());
        }
        return Map.copyOf(names);
    }

    private static Map<String, String> buildStrings(boolean readsRecord) {
        Map<String, String> strings = new HashMap<>(LEGACY_METHODS);
        strings.put("comp_2299", "enchantmentValue");
        strings.put("comp_2300", "equipSound");
        strings.put("comp_2301", "repairIngredient");
        strings.put("comp_2303", "toughness");
        strings.put("comp_2304", "knockbackResistance");
        strings.put("net.minecraft.class_1741", ARMOR_MATERIAL.replace('/', '.'));
        if (readsRecord) strings.put(Pre1_20_5ArmorMaterialBridge.DEFENSE, "getDefense");
        return Map.copyOf(strings);
    }

    private static boolean mentions(byte[] classBytes, String name) {
        return new String(classBytes, StandardCharsets.ISO_8859_1).contains(name);
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
