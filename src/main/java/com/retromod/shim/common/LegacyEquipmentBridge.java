/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.LegacyBaseSynthesizer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyEquipmentSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rebuilds armor and tools from mods written before they became item components. */
public final class LegacyEquipmentBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    // 1.21.2 turned ArmorMaterial and Tier into records, so an old material enum that implements
    // them fails with IncompatibleClassChangeError at load. In a jar written against the old
    // interfaces they are renamed, before the remap, to these generated ones with the old methods.
    static final String ARMOR_MATERIAL = "com/retromod/generated/LegacyEquipmentArmorMaterial";
    static final String TIER = "com/retromod/generated/LegacyEquipmentTier";
    static final String ARMOR_VIEW = "com/retromod/generated/LegacyEquipmentArmorView";
    static final String TIER_VIEW = "com/retromod/generated/LegacyEquipmentTierView";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyEquipmentSupport";
    private static final String HANDLE = "retromod$handle";

    private static final String ARMOR_TYPE = "net/minecraft/world/item/equipment/ArmorType";
    private static final String SOUND = "net/minecraft/sounds/SoundEvent";
    private static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";
    private static final String RECORD_ARMOR = "net/minecraft/world/item/equipment/ArmorMaterial";
    private static final String RECORD_ARMORS = "net/minecraft/world/item/equipment/ArmorMaterials";
    private static final String RECORD_TOOL = "net/minecraft/world/item/ToolMaterial";
    private static final String PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";

    /** Old interface names: intermediary for Fabric jars, Mojang for Forge and NeoForge jars. */
    private static final Set<String> OLD_ARMOR = Set.of("net/minecraft/class_1741", "net/minecraft/world/item/ArmorMaterial");
    private static final Set<String> OLD_TIER = Set.of("net/minecraft/class_1832", "net/minecraft/world/item/Tier");
    private static final Set<String> OLD_ARMOR_ENUM = Set.of("net/minecraft/class_1740", "net/minecraft/world/item/ArmorMaterials");
    private static final Set<String> OLD_TIER_ENUM = Set.of("net/minecraft/class_1834", "net/minecraft/world/item/Tiers");
    private static final Set<String> OLD_ARMOR_ITEM = Set.of("net/minecraft/class_1738", "net/minecraft/world/item/ArmorItem");

    /** Old interface methods, in Mojang names, which is what the remap makes of the mod's calls. */
    static final Map<String, String> ARMOR_METHODS = ordered(
            "getDurabilityForType", "(L" + ARMOR_TYPE + ";)I",
            "getDefenseForType", "(L" + ARMOR_TYPE + ";)I",
            "getEnchantmentValue", "()I",
            "getEquipSound", "()L" + SOUND + ";",
            "getRepairIngredient", "()L" + INGREDIENT + ";",
            "getName", "()Ljava/lang/String;",
            "getToughness", "()F",
            "getKnockbackResistance", "()F");
    static final Map<String, String> TIER_METHODS = ordered(
            "getUses", "()I",
            "getSpeed", "()F",
            "getAttackDamageBonus", "()F",
            "getLevel", "()I",
            "getEnchantmentValue", "()I",
            "getRepairIngredient", "()L" + INGREDIENT + ";",
            "getIncorrectBlocksForDrops", "()L" + TAG_KEY + ";");

    /** Old vanilla constant names, including their 1.20.1 spellings, and the current field each reads. */
    static final Map<String, String> ARMOR_CONSTANTS = ordered(
            "LEATHER", "LEATHER", "CHAIN", "CHAINMAIL", "CHAINMAIL", "CHAINMAIL", "IRON", "IRON",
            "GOLD", "GOLD", "DIAMOND", "DIAMOND", "TURTLE", "TURTLE_SCUTE", "TURTLE_SCUTE", "TURTLE_SCUTE",
            "NETHERITE", "NETHERITE");
    static final Map<String, String> TIER_CONSTANTS = ordered(
            "WOOD", "WOOD", "STONE", "STONE", "IRON", "IRON", "DIAMOND", "DIAMOND", "GOLD", "GOLD",
            "NETHERITE", "NETHERITE");

    /** Generated base, adapter key: the bases {@link RemovedItemBaseBridge} and the 26.3 shim register. */
    private static final Map<String, String> BASES = ordered(
            "com/retromod/generated/LegacyArmorItem", "armor",
            "com/retromod/generated/LegacyHorseArmorItem", "horse",
            "com/retromod/generated/LegacyAnimalArmorItem", "horse",
            "com/retromod/generated/LegacySwordItem", "sword",
            "com/retromod/generated/LegacyPickaxeItem", "pickaxe",
            "com/retromod/generated/LegacyDiggerItem", "digger",
            "com/retromod/generated/LegacyAxeItem", "axe",
            "com/retromod/generated/LegacyShovelItem", "shovel",
            "com/retromod/generated/LegacyHoeItem", "hoe",
            "com/retromod/generated/LegacyDyeableArmorItem", "armor",
            "com/retromod/generated/LegacyDyeableHorseArmorItem", "horse");

    static final String DYEABLE = "com/retromod/generated/LegacyDyeableLeatherItem";
    private static final String STACK = "net/minecraft/world/item/ItemStack";

    /** Classes of jars written against the old interfaces, whose vanilla constants become views. */
    private static final Set<String> LEGACY_CLASSES = ConcurrentHashMap.newKeySet();

    private LegacyEquipmentBridge() {}

    /**
     * Registers on a host that replaced the classes with components, and attaches the adapter to
     * every equipment base already registered. Safe to call again after more bases register.
     */
    public static void register(RetromodTransformer transformer) {
        if (!hostUsesComponents()) return;
        if (!transformer.getSyntheticClasses().containsKey(ARMOR_MATERIAL)) {
            SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyEquipmentSupport.class);
            transformer.registerSyntheticClass(ARMOR_MATERIAL, generateInterface(ARMOR_MATERIAL, ARMOR_METHODS));
            transformer.registerSyntheticClass(TIER, generateInterface(TIER, TIER_METHODS));
            transformer.registerSyntheticClass(ARMOR_VIEW,
                    generateView(ARMOR_VIEW, ARMOR_MATERIAL, ARMOR_METHODS, RECORD_ARMORS, RECORD_ARMOR, ARMOR_CONSTANTS));
            transformer.registerSyntheticClass(TIER_VIEW,
                    generateView(TIER_VIEW, TIER, TIER_METHODS, RECORD_TOOL, RECORD_TOOL, TIER_CONSTANTS));
        }
        registerSurvivingToolConstructors(transformer);
        registerDyeableBases(transformer);
        // 1.21.5 deleted ArmorItem, SwordItem and the other tool classes, so an old item on a plain
        // Item lost its protection, damage and mining speed. Each generated base passes its old
        // constructor arguments to LegacyEquipmentSupport, which builds the current record once and
        // applies the matching component. A 1.20.5 to 1.21.1 tool that set its damage through
        // createAttributes keeps that attribute but gets no tool component. Dyeable armor loses
        // its color layer.
        BASES.forEach((base, key) -> {
            if (transformer.getSyntheticClasses().containsKey(base)) {
                String[] interfaces = base.contains("Dyeable") ? new String[]{DYEABLE} : new String[0];
                transformer.attachLegacyBaseAdapter(base,
                        new LegacyBaseSynthesizer.ArgumentAdapter(SUPPORT, "adapt", key, PROPERTIES, interfaces));
            }
        });
    }

    /** Whether the host needed this bridge and it is registered on {@code transformer}. */
    public static boolean isRegistered(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(ARMOR_MATERIAL);
    }

    /**
     * {@code DyeableArmorItem} and {@code DyeableHorseArmorItem} went in 1.20.5, when dye color
     * became the {@code dyed_color} component, and {@code DyeableLeatherItem} with them. Their items
     * are rebased like the other armor, and the old interface becomes one whose color methods read
     * and write that component. Registered here rather than with the other removed bases, because
     * from 1.20.5 to 1.21.1 the armor material bridge owns these names.
     */
    private static void registerDyeableBases(RetromodTransformer transformer) {
        if (transformer.getSyntheticClasses().containsKey(DYEABLE)) return;
        transformer.registerSyntheticClass(DYEABLE, generateDyeableInterface());
        transformer.registerClassRedirect("net/minecraft/world/item/DyeableLeatherItem", DYEABLE);
        for (String removed : new String[]{"DyeableArmorItem", "DyeableHorseArmorItem"}) {
            transformer.registerGeneratedLegacyBase("net/minecraft/world/item/" + removed,
                    "com/retromod/generated/Legacy" + removed, "net/minecraft/world/item/Item",
                    "(" + PROPERTIES + ")V");
        }
    }

    /** The old dyeable interface, with each color method answered by the support class. */
    static byte[] generateDyeableInterface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, DYEABLE, null,
                "java/lang/Object", null);
        String[][] methods = {
            {"getColor", "(L" + STACK + ";)I", "dyeColor", "(Ljava/lang/Object;)I"},
            {"hasCustomColor", "(L" + STACK + ";)Z", "hasDyeColor", "(Ljava/lang/Object;)Z"},
            {"clearColor", "(L" + STACK + ";)V", "clearDyeColor", "(Ljava/lang/Object;)V"},
            {"setColor", "(L" + STACK + ";I)V", "setDyeColor", "(Ljava/lang/Object;I)V"},
        };
        for (String[] method : methods) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method[0], method[1], null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            if (method[0].equals("setColor")) mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, method[2], method[3], false);
            Type returns = Type.getReturnType(method[1]);
            mv.visitInsn(returns.getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    static final String TOOLS = "com/retromod/generated/LegacyEquipmentTools";
    private static final String TOOL_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    /** Removed base, old second argument: the hoe took its damage as an int before 1.21.2. */
    private static final String[][] SURVIVING_TOOLS = {
        {"AxeItem", "F", "axe"}, {"ShovelItem", "F", "shovel"}, {"HoeItem", "I", "hoe"}};

    /**
     * 26.1 and 26.2 still have {@code AxeItem}, {@code ShovelItem}, and {@code HoeItem}, with
     * constructors that take the {@code ToolMaterial} record. In a jar renamed onto the old tier,
     * {@code new AxeItem(tier, ...)} and a subclass's {@code super(tier, ...)} are converted to the
     * record there, so the class keeps doing its own work, such as stripping logs. Registered only
     * on such a host, because 26.3 removed the classes and the generated bases take over.
     */
    private static void registerSurvivingToolConstructors(RetromodTransformer transformer) {
        boolean survives = ClassResourceInspector.read("net/minecraft/world/item/Item$Properties") != null
                ? ClassResourceInspector.exists("net/minecraft/world/item/AxeItem")
                : RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") < 0;
        if (!survives || transformer.getSyntheticClasses().containsKey(TOOLS)) return;
        transformer.registerSyntheticClass(TOOLS, generateTools());
        for (String[] tool : SURVIVING_TOOLS) {
            String owner = "net/minecraft/world/item/" + tool[0];
            String oldDesc = "(L" + TIER + ";" + tool[1] + "FL" + TOOL_PROPERTIES + ";)V";
            String newDesc = "(L" + RECORD_TOOL + ";FFL" + TOOL_PROPERTIES + ";)V";
            transformer.registerConstructorRedirect(owner, oldDesc, TOOLS, tool[2],
                    "(L" + TIER + ";" + tool[1] + "FL" + TOOL_PROPERTIES + ";)L" + owner + ";");
            boolean intDamage = tool[1].equals("I");
            transformer.registerSuperConstructorAdapter(owner, oldDesc, newDesc, mv -> {
                // [tier, damage, speed, properties]: set the last three aside, convert the tier,
                // and put them back. A constructor's high locals are free before super() runs.
                mv.visitVarInsn(Opcodes.ASTORE, 252);
                mv.visitVarInsn(Opcodes.FSTORE, 251);
                mv.visitVarInsn(intDamage ? Opcodes.ISTORE : Opcodes.FSTORE, 250);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOOLS, "material", "(L" + TIER + ";)L" + RECORD_TOOL + ";", false);
                mv.visitVarInsn(intDamage ? Opcodes.ILOAD : Opcodes.FLOAD, 250);
                if (intDamage) mv.visitInsn(Opcodes.I2F);
                mv.visitVarInsn(Opcodes.FLOAD, 251);
                mv.visitVarInsn(Opcodes.ALOAD, 252);
            });
        }
    }

    /** {@code material(tier)} and one factory per surviving tool, for direct {@code new} calls. */
    static byte[] generateTools() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, TOOLS, null,
                "java/lang/Object", null);
        MethodVisitor material = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "material",
                "(L" + TIER + ";)L" + RECORD_TOOL + ";", null, null);
        material.visitCode();
        material.visitVarInsn(Opcodes.ALOAD, 0);
        material.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "toolMaterial", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        material.visitTypeInsn(Opcodes.CHECKCAST, RECORD_TOOL);
        material.visitInsn(Opcodes.ARETURN);
        material.visitMaxs(0, 0);
        material.visitEnd();
        for (String[] tool : SURVIVING_TOOLS) {
            String owner = "net/minecraft/world/item/" + tool[0];
            boolean intDamage = tool[1].equals("I");
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, tool[2],
                    "(L" + TIER + ";" + tool[1] + "FL" + TOOL_PROPERTIES + ";)L" + owner + ";", null, null);
            mv.visitCode();
            mv.visitTypeInsn(Opcodes.NEW, owner);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TOOLS, "material", "(L" + TIER + ";)L" + RECORD_TOOL + ";", false);
            mv.visitVarInsn(intDamage ? Opcodes.ILOAD : Opcodes.FLOAD, 1);
            if (intDamage) mv.visitInsn(Opcodes.I2F);
            mv.visitVarInsn(Opcodes.FLOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>",
                    "(L" + RECORD_TOOL + ";FFL" + TOOL_PROPERTIES + ";)V", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Whether the host builds armor and tools from item components instead of the old classes. */
    static boolean hostUsesComponents() {
        // ArmorItem gone and Item.Properties.humanoidArmor present, read in Mojang names: 26.x on
        // every loader, and NeoForge or Forge from 1.21.5.
        ClassNode properties = ClassResourceInspector.read("net/minecraft/world/item/Item$Properties");
        if (properties == null) {
            // Offline, or a Fabric host before 26.1 whose runtime names are not Mojang's.
            return RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION);
        }
        return properties.methods.stream().anyMatch(m -> m.name.equals("humanoidArmor"))
                && !ClassResourceInspector.exists("net/minecraft/world/item/ArmorItem");
    }

    /** Renames the old interfaces in a jar written against them and returns how many classes changed. */
    public static int renameInLegacyJar(RetromodTransformer transformer, Path root) throws IOException {
        if (!isRegistered(transformer)) return 0;
        List<Path> classFiles;
        try (var stream = Files.walk(root)) {
            classFiles = stream.filter(p -> p.toString().endsWith(".class")).toList();
        }
        Map<Path, byte[]> bytes = new HashMap<>();
        for (Path file : classFiles) bytes.put(file, Files.readAllBytes(file));
        Map<String, String> renames = legacyRenames(transformer, bytes.values());
        if (renames.isEmpty()) return 0;
        int renamed = 0;
        for (Map.Entry<Path, byte[]> entry : bytes.entrySet()) {
            byte[] updated = rename(entry.getValue(), renames);
            if (updated != entry.getValue()) {
                Files.write(entry.getKey(), updated);
                renamed++;
            }
        }
        LOGGER.debug("Moved {} classes onto the old armor and tool material interfaces", renamed);
        return renamed;
    }

    /**
     * Decides, for one jar's classes, which old interfaces to rename, and remembers the jar's class
     * names for {@link #apply}. The whole jar gets one answer, so a field typed as the material and
     * the code reading it always agree. Empty when the bridge is not registered or nothing is old.
     */
    public static Map<String, String> legacyRenames(RetromodTransformer transformer, Iterable<byte[]> classes) {
        LegacyFeatureBridge.markJar(transformer, classes);
        if (!isRegistered(transformer)) return Map.of();
        boolean armor = false;
        boolean tier = false;
        for (byte[] classBytes : classes) {
            Usage usage = usage(classBytes);
            armor |= usage.armor();
            tier |= usage.tier();
        }
        if (!armor && !tier) return Map.of();
        Map<String, String> renames = new HashMap<>();
        if (armor) OLD_ARMOR.forEach(name -> renames.put(name, ARMOR_MATERIAL));
        if (tier) OLD_TIER.forEach(name -> renames.put(name, TIER));
        for (byte[] classBytes : classes) LEGACY_CLASSES.add(new ClassReader(classBytes).getClassName());
        return Map.copyOf(renames);
    }

    /** One class with the old interfaces renamed, or the same array when it names none of them. */
    public static byte[] rename(byte[] classBytes, Map<String, String> renames) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (renames.isEmpty() || renames.keySet().stream().noneMatch(text::contains)) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, new SimpleRemapper(renames)), 0);
        return writer.toByteArray();
    }

    record Usage(boolean armor, boolean tier) {}

    /**
     * Whether a class uses the old armor material or tier as an interface: implements it, calls it
     * as one, reads an old vanilla enum constant, or passes a material to the old armor constructor.
     * A 1.21.2 or newer class builds the records and calls their accessors instead.
     */
    static Usage usage(byte[] classBytes) {
        boolean[] found = new boolean[2];
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                for (String iface : interfaces == null ? new String[0] : interfaces) {
                    if (OLD_ARMOR.contains(iface)) found[0] = true;
                    if (OLD_TIER.contains(iface)) found[1] = true;
                }
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKEINTERFACE && OLD_ARMOR.contains(owner)) found[0] = true;
                        if (opcode == Opcodes.INVOKEINTERFACE && OLD_TIER.contains(owner)) found[1] = true;
                        if (OLD_ARMOR_ITEM.contains(owner) && method.equals("<init>")
                                && OLD_ARMOR.stream().anyMatch(old -> desc.startsWith("(L" + old + ";"))) {
                            found[0] = true;
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        if (OLD_ARMOR_ENUM.contains(owner) && desc.equals("L" + owner + ";")) found[0] = true;
                        if (OLD_TIER_ENUM.contains(owner) && desc.equals("L" + owner + ";")) found[1] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Usage(found[0], found[1]);
    }

    /**
     * In a legacy jar's class, after the remap, turns a read of a vanilla material that the old
     * enums named into the matching view, so it fits where the old interface is expected.
     */
    public static byte[] apply(byte[] classBytes, String className) {
        if (classBytes == null || !LEGACY_CLASSES.contains(className)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
                String view = null;
                String type = null;
                if (field.owner.equals(RECORD_ARMORS) && field.desc.equals("L" + RECORD_ARMORS + ";")
                        && ARMOR_CONSTANTS.containsKey(field.name)) {
                    view = ARMOR_VIEW;
                    type = ARMOR_MATERIAL;
                } else if (field.owner.equals(RECORD_TOOL) && field.desc.equals("L" + RECORD_TOOL + ";")
                        && TIER_CONSTANTS.containsKey(field.name)) {
                    view = TIER_VIEW;
                    type = TIER;
                }
                if (view == null) continue;
                method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, view, field.name,
                        "()L" + type + ";", false));
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    static byte[] generateInterface(String name, Map<String, String> methods) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                name, null, "java/lang/Object", null);
        methods.forEach((method, desc) ->
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, desc, null, null).visitEnd());
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A view of a vanilla record that answers the old interface through
     * {@link LegacyEquipmentSupport#view}. One cached instance per constant, so an old
     * {@code material == ArmorMaterials.IRON} comparison still holds.
     */
    static byte[] generateView(String name, String iface, Map<String, String> methods, String constantOwner,
                               String recordType, Map<String, String> constants) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", new String[]{iface});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, HANDLE, "Ljava/lang/Object;", null, null).visitEnd();
        Set<String> targets = new java.util.LinkedHashSet<>(constants.values());
        for (String target : targets) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "view$" + target,
                    "L" + name + ";", null, null).visitEnd();
        }

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, HANDLE, "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (String target : targets) {
            clinit.visitTypeInsn(Opcodes.NEW, name);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitFieldInsn(Opcodes.GETSTATIC, constantOwner, target, "L" + recordType + ";");
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(Ljava/lang/Object;)V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "view$" + target, "L" + name + ";");
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        constants.forEach((old, target) -> {
            MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, old, "()L" + iface + ";",
                    null, null);
            get.visitCode();
            get.visitFieldInsn(Opcodes.GETSTATIC, name, "view$" + target, "L" + name + ";");
            get.visitInsn(Opcodes.ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        });

        methods.forEach((method, desc) -> {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, name, HANDLE, "Ljava/lang/Object;");
            mv.visitLdcInsn(method);
            if (Type.getArgumentTypes(desc).length == 1) mv.visitVarInsn(Opcodes.ALOAD, 1);
            else mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "view",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            Type returns = Type.getReturnType(desc);
            switch (returns.getSort()) {
                case Type.INT -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                    mv.visitInsn(Opcodes.IRETURN);
                }
                case Type.FLOAT -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                    mv.visitInsn(Opcodes.FRETURN);
                }
                default -> {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, returns.getInternalName());
                    mv.visitInsn(Opcodes.ARETURN);
                }
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final Pattern OLD_ARMOR_TEXTURE =
            Pattern.compile("assets/([^/]+)/textures/models/armor/(.+)_layer_([12])\\.png");
    private static final Pattern OLD_ARMOR_OVERLAY =
            Pattern.compile("assets/([^/]+)/textures/models/armor/(.+)_layer_([12])_overlay\\.png");
    private static final Pattern OLD_HORSE_TEXTURE =
            Pattern.compile("assets/([^/]+)/textures/entity/horse/armor/horse_armor_(.+)\\.png");

    /** The entries to add to a jar so its old armor textures get the equipment assets 1.21.2 renders from. */
    public static Map<String, byte[]> armorAssetEntries(java.util.Collection<String> entries,
                                                       java.util.function.Function<String, byte[]> read) {
        // The shared resource migration writes these on every transform path, through the
        // overload that names the host.
        return armorAssetEntries(entries, read, RetromodVersion.TARGET_MC_VERSION);
    }

    /** The same entries for {@code targetMcVersion}, which decides the vanilla assets not to shadow. */
    public static Map<String, byte[]> armorAssetEntries(java.util.Collection<String> entries,
                                                       java.util.function.Function<String, byte[]> read,
                                                       String targetMcVersion) {
        return armorAssetEntries(entries, read, targetMcVersion, Map.of());
    }

    /** The same, plus assets for armor that chose its textures in code, as Forge's getArmorTexture did. */
    public static Map<String, byte[]> armorAssetEntries(java.util.Collection<String> entries,
                                                       java.util.function.Function<String, byte[]> read,
                                                       String targetMcVersion,
                                                       Map<String, Map<String, String>> chosenLayers) {
        Map<String, Map<String, String>> layersByAsset = new LinkedHashMap<>();
        Map<String, Map<String, String>> overlaysByAsset = new LinkedHashMap<>();
        // textures/models/armor/<name>_layer_1.png and _layer_2.png become the humanoid and
        // humanoid_leggings layers, and textures/entity/horse/armor/horse_armor_<name>.png becomes
        // the horse_body layer, all described by equipment/<name>.json.
        for (String entry : entries) {
            Matcher armor = OLD_ARMOR_TEXTURE.matcher(entry);
            Matcher overlay = OLD_ARMOR_OVERLAY.matcher(entry);
            Matcher horse = OLD_HORSE_TEXTURE.matcher(entry);
            if (armor.matches()) {
                layersByAsset.computeIfAbsent(armor.group(1) + ":" + armor.group(2), k -> new LinkedHashMap<>())
                        .put(armor.group(3).equals("1") ? "humanoid" : "humanoid_leggings", entry);
            } else if (overlay.matches()) {
                overlaysByAsset.computeIfAbsent(overlay.group(1) + ":" + overlay.group(2), k -> new LinkedHashMap<>())
                        .put(overlay.group(3).equals("1") ? "humanoid" : "humanoid_leggings", entry);
            } else if (horse.matches()) {
                layersByAsset.computeIfAbsent(horse.group(1) + ":" + horse.group(2), k -> new LinkedHashMap<>())
                        .put("horse_body", entry);
            }
        }
        // chosenLayers maps an asset id (namespace:name) to the texture entry for each layer type.
        // An asset the old textures/models/armor/ files already describe keeps those, since the
        // material's own textures are what every piece without such code showed.
        chosenLayers.forEach((asset, layers) -> layersByAsset.putIfAbsent(asset, new LinkedHashMap<>(layers)));
        Set<String> existing = new java.util.HashSet<>(entries);
        Map<String, byte[]> added = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> asset : layersByAsset.entrySet()) {
            String namespace = asset.getKey().substring(0, asset.getKey().indexOf(':'));
            String name = asset.getKey().substring(namespace.length() + 1);
            String json = "assets/" + namespace + "/equipment/" + name + ".json";
            // An entry the jar already has is never replaced.
            if (existing.contains(json)) continue;
            Map<String, String> overlays = overlaysByAsset.getOrDefault(asset.getKey(), Map.of());
            StringBuilder layers = new StringBuilder();
            for (Map.Entry<String, String> layer : asset.getValue().entrySet()) {
                String folder = "assets/" + namespace + "/textures/entity/equipment/" + layer.getKey() + "/";
                byte[] bytes = read.apply(layer.getValue());
                if (bytes == null) continue;
                if (!existing.contains(folder + name + ".png")) added.put(folder + name + ".png", bytes);
                String texture = "{\"texture\": \"" + namespace + ":" + name + "\"";
                String overlayEntry = overlays.get(layer.getKey());
                byte[] overlayBytes = overlayEntry == null ? null : read.apply(overlayEntry);
                StringBuilder value = new StringBuilder("[");
                if (overlayBytes != null) {
                    // An overlay meant the base layer was tinted with the item's dye, as leather is.
                    value.append(texture).append(", \"dyeable\": {\"color_when_undyed\": ").append(UNDYED_ARGB)
                            .append("}}, {\"texture\": \"").append(namespace).append(':').append(name)
                            .append("_overlay\"}");
                    if (!existing.contains(folder + name + "_overlay.png")) {
                        added.put(folder + name + "_overlay.png", overlayBytes);
                    }
                } else {
                    value.append(texture).append('}');
                }
                value.append(']');
                if (layers.length() > 0) layers.append(",\n");
                layers.append("    \"").append(layer.getKey()).append("\": ").append(value);
            }
            if (layers.length() == 0) continue;
            // Never shadow a vanilla asset the host ships. A resource asset replaces vanilla's
            // instead of merging, so a mod retexturing iron armor would drop the layers vanilla
            // gives iron for horses, babies and trims. The layer textures added above still apply
            // through vanilla's own asset, as the old texture overrode vanilla's.
            if (namespace.equals("minecraft") && hostShipsEquipmentAsset(name, targetMcVersion)) continue;
            added.put(json, ("{\n  \"layers\": {\n" + layers + "\n  }\n}\n").getBytes(StandardCharsets.UTF_8));
        }
        byte[] dyeableTag = dyeableTag(entries, existing, read);
        if (dyeableTag != null) added.put(DYEABLE_TAG, dyeableTag);
        return added;
    }

    /** Each vanilla equipment asset id, with the first version whose client jar ships it. */
    private static final Map<String, String> VANILLA_EQUIPMENT_ASSETS = vanillaEquipmentAssets();

    private static Map<String, String> vanillaEquipmentAssets() {
        // Read from assets/minecraft/equipment/ in the 1.21.4, 1.21.5, 1.21.6, 1.21.8, 1.21.9,
        // 1.21.10, 1.21.11, 26.1 and 26.3 client jars. Only the ids; nothing of vanilla's is copied.
        Map<String, String> since = new java.util.HashMap<>();
        for (String id : List.of("leather", "chainmail", "iron", "gold", "diamond", "netherite",
                "turtle_scute", "armadillo_scute", "elytra", "trader_llama")) {
            since.put(id, "1.21.4");
        }
        since.put("saddle", "1.21.5");
        // Copper is vanilla only from 1.21.9, so an old minecraft:copper armor still needs its own
        // asset on 1.21.5 to 1.21.8.
        since.put("copper", "1.21.9");
        since.put("trader_llama_baby", "26.1");
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red",
                "black")) {
            since.put(color + "_carpet", "1.21.4");
            since.put(color + "_harness", "1.21.6");
        }
        return Map.copyOf(since);
    }

    /** Whether {@code targetMcVersion} ships the vanilla equipment asset {@code id}. */
    static boolean hostShipsEquipmentAsset(String id, String targetMcVersion) {
        String since = VANILLA_EQUIPMENT_ASSETS.get(id);
        return since != null && targetMcVersion != null
                && RetromodVersion.compareMcVersions(targetMcVersion, since) >= 0;
    }

    /** Leather's undyed color as an ARGB int, which the equipment asset expects. */
    private static final int UNDYED_ARGB = 0xFFA06540;
    private static final String DYEABLE_TAG = "data/minecraft/tags/item/dyeable.json";
    private static final Pattern ITEM_MODEL = Pattern.compile("assets/([^/]+)/models/item/([^/]+)\\.json");

    /**
     * Dyeing in a crafting grid needs the item in {@code #minecraft:dyeable}, which old dyeable
     * armor never declared. An item model whose {@code layer1} is an {@code _overlay} texture is the
     * shape every dyeable armor item used, so those items are added. Each entry is optional, so a
     * guess that names no item does nothing.
     */
    private static byte[] dyeableTag(java.util.Collection<String> entries, Set<String> existing,
                                     java.util.function.Function<String, byte[]> read) {
        if (existing.contains(DYEABLE_TAG)) return null;
        List<String> ids = new java.util.ArrayList<>();
        for (String entry : entries) {
            Matcher model = ITEM_MODEL.matcher(entry);
            if (!model.matches() || model.group(1).equals("minecraft")) continue;
            byte[] bytes = read.apply(entry);
            if (bytes == null) continue;
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (json.matches("(?s).*\"layer1\"\\s*:\\s*\"[^\"]*_overlay\".*")) {
                ids.add(model.group(1) + ":" + model.group(2));
            }
        }
        if (ids.isEmpty()) return null;
        StringBuilder values = new StringBuilder();
        for (String id : ids) {
            if (values.length() > 0) values.append(",\n");
            values.append("    {\"id\": \"").append(id).append("\", \"required\": false}");
        }
        return ("{\n  \"replace\": false,\n  \"values\": [\n" + values + "\n  ]\n}\n").getBytes(StandardCharsets.UTF_8);
    }


    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
