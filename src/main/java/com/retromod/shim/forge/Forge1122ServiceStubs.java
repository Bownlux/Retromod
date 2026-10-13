/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyAsmData;
import com.retromod.shim.forge.embedded.LegacyAsmDataTable;
import com.retromod.shim.forge.embedded.LegacyConfigCategory;
import com.retromod.shim.forge.embedded.LegacyConfigManager;
import com.retromod.shim.forge.embedded.LegacyConfigProperty;
import com.retromod.shim.forge.embedded.LegacyConfiguration;
import com.retromod.shim.forge.embedded.LegacyFluidRegistry;
import com.retromod.shim.forge.embedded.LegacyModMetadata;
import com.retromod.shim.forge.embedded.LegacyNetworkRegistry;
import com.retromod.shim.forge.embedded.LegacyReflectionHelper;
import com.retromod.shim.forge.embedded.LegacySimpleNetworkWrapper;
import com.retromod.shim.forge.embedded.LegacyTargetPoint;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Load-time stand-ins for 1.12.2 FML services a mod touches in its {@code @Mod} class or
 * pre-init, before it registers content. Without them the first missing class
 * ({@code NetworkRegistry}, {@code Configuration}, {@code FMLLog}, {@code FluidRegistry},
 * {@code IFuelHandler}, {@code LootTableList}) fails mod construction and nothing registers.
 *
 * <p>These make the mod load; they do not make the service work. Configs return the mod's own
 * defaults and never touch the {@code .cfg} file. Networking channels and GUI
 * handlers are inert, 1.12 fluids are not registered, fuel handlers are never asked, and loot
 * table registration keeps the name without adding a table.
 */
public final class Forge1122ServiceStubs {

    private Forge1122ServiceStubs() {}

    static final String NETWORK_REGISTRY = "com/retromod/shim/forge/embedded/LegacyNetworkRegistry";
    static final String CHANNEL = "com/retromod/shim/forge/embedded/LegacySimpleNetworkWrapper";
    static final String TARGET_POINT = "com/retromod/shim/forge/embedded/LegacyTargetPoint";
    static final String FLUID_REGISTRY = "com/retromod/shim/forge/embedded/LegacyFluidRegistry";
    static final String FML_LOG = "com/retromod/generated/forge1122/FMLLog";
    static final String LOOT_TABLE_LIST = "net/minecraft/world/storage/loot/LootTableList";
    static final String ASM_DATA_TABLE = "com/retromod/shim/forge/embedded/LegacyAsmDataTable";
    static final String ASM_DATA = "com/retromod/shim/forge/embedded/LegacyAsmData";
    static final String MOD_METADATA = "com/retromod/shim/forge/embedded/LegacyModMetadata";
    static final String DATA_FIXER = "com/retromod/shim/forge/embedded/LegacyCompoundDataFixer";
    static final String MOD_FIXS = "com/retromod/shim/forge/embedded/LegacyModFixs";
    static final String FIX_TYPES = "com/retromod/generated/forge1122/FixTypes";
    static final String LEGACY_REGISTRY = "com/retromod/shim/forge/embedded/LegacyRegistryNamespaced";
    static final String COMMAND_BASE = "com/retromod/shim/forge/embedded/LegacyCommandBase";
    static final String COMMANDS = "com/retromod/shim/forge/embedded/LegacyCommands";
    static final String COMMAND_EXCEPTION = "com/retromod/shim/forge/embedded/LegacyCommandException";
    static final String SERVER_STARTING = "com/retromod/shim/forge/embedded/LegacyServerStartingEvent";
    static final String I18N = "com/retromod/shim/forge/embedded/LegacyI18n";
    static final String INTER_MOD_COMMS = "com/retromod/shim/forge/embedded/LegacyInterModComms";
    static final String ENCHANTMENT_TYPE = "com/retromod/shim/forge/embedded/LegacyEnchantmentType";
    static final String PLANKS_TYPE = "com/retromod/shim/forge/embedded/LegacyPlanksType";
    static final String CLIENT_COMMANDS = "com/retromod/shim/forge/embedded/LegacyClientCommandHandler";
    static final String BIOME_DICTIONARY = "com/retromod/shim/forge/embedded/LegacyBiomeDictionary";
    static final String BIOME_TYPE = "com/retromod/shim/forge/embedded/LegacyBiomeType";
    static final String DIMENSION_MANAGER = "com/retromod/shim/forge/embedded/LegacyDimensionManager";
    static final String WORLD_PROVIDER = "com/retromod/generated/forge1122/LegacyWorldProvider";
    static final String DIMENSION_TYPES = "com/retromod/generated/forge1122/LegacyDimensionTypes";
    static final String ITEM_SLAB = "com/retromod/generated/forge1122/LegacyItemSlab";
    static final String BIOME = "com/retromod/generated/forge1122/LegacyBiome";
    static final String BLOCK_NAMES = "com/retromod/shim/forge/embedded/LegacyBlockNames";
    static final String NAME_LOOKUPS = "com/retromod/generated/forge1122/LegacyNameLookups";

    /** Compiled stand-ins declare {@code Object} parameters; calls on them are erased to match. */
    static final String CONFIGURATION = "com/retromod/shim/forge/embedded/LegacyConfiguration";
    static final String CONFIG_CATEGORY = "com/retromod/shim/forge/embedded/LegacyConfigCategory";
    static final String CONFIG_PROPERTY = "com/retromod/shim/forge/embedded/LegacyConfigProperty";
    static final String CONFIG_MANAGER = "com/retromod/shim/forge/embedded/LegacyConfigManager";

    static final Set<String> ERASED_OWNERS = Set.of(NETWORK_REGISTRY, CHANNEL, TARGET_POINT,
            FLUID_REGISTRY, CONFIGURATION, CONFIG_CATEGORY, CONFIG_PROPERTY, CONFIG_MANAGER, DATA_FIXER, MOD_FIXS, LEGACY_REGISTRY,
            COMMAND_BASE, SERVER_STARTING, I18N, INTER_MOD_COMMS, ENCHANTMENT_TYPE, CLIENT_COMMANDS, PLANKS_TYPE, COMMAND_EXCEPTION,
            BIOME_DICTIONARY, BIOME_TYPE, DIMENSION_MANAGER, COMMAND_EXCEPTION + "$WrongUsage",
            COMMAND_EXCEPTION + "$NumberInvalid", COMMAND_EXCEPTION + "$PlayerNotFound",
            COMMAND_EXCEPTION + "$SyntaxError",
            Forge1122FluidBridge.FLUID, Forge1122FluidBridge.FLUID_STACK,
            Forge1122FluidBridge.FLUID_UTIL, Forge1122WorldgenBridge.ENUM_HELPER);

    public static void register(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, NETWORK_REGISTRY, LegacyNetworkRegistry.class);
        SyntheticEmbedder.registerClassResource(t, CHANNEL, LegacySimpleNetworkWrapper.class);
        SyntheticEmbedder.registerClassResource(t, TARGET_POINT, LegacyTargetPoint.class);
        t.registerClassRedirect("net/minecraftforge/fml/common/network/NetworkRegistry", NETWORK_REGISTRY);
        t.registerClassRedirect("net/minecraftforge/fml/common/network/simpleimpl/SimpleNetworkWrapper",
                CHANNEL);
        t.registerClassRedirect("net/minecraftforge/fml/common/network/NetworkRegistry$TargetPoint",
                TARGET_POINT);

        SyntheticEmbedder.registerClassResource(t, FLUID_REGISTRY, LegacyFluidRegistry.class);
        t.registerClassRedirect("net/minecraftforge/fluids/FluidRegistry", FLUID_REGISTRY);
        Forge1122FluidBridge.register(t);

        // Config files: defaults only, but pre-init gets past its config load.
        SyntheticEmbedder.registerClassResource(t, CONFIGURATION, LegacyConfiguration.class);
        SyntheticEmbedder.registerClassResource(t, CONFIG_CATEGORY, LegacyConfigCategory.class);
        SyntheticEmbedder.registerClassResource(t, CONFIG_PROPERTY, LegacyConfigProperty.class);
        SyntheticEmbedder.registerClassResource(t, CONFIG_MANAGER, LegacyConfigManager.class);
        t.registerClassRedirect("net/minecraftforge/common/config/Configuration", CONFIGURATION);
        t.registerClassRedirect("net/minecraftforge/common/config/ConfigCategory", CONFIG_CATEGORY);
        t.registerClassRedirect("net/minecraftforge/common/config/Property", CONFIG_PROPERTY);
        t.registerClassRedirect("net/minecraftforge/common/config/ConfigManager", CONFIG_MANAGER);

        // Pre-init's annotation table and metadata. Both take and return JDK types only, so the
        // calls link without erasure. The pre-init event stand-in hands them out.
        SyntheticEmbedder.registerClassResource(t, ASM_DATA_TABLE, LegacyAsmDataTable.class);
        SyntheticEmbedder.registerClassResource(t, ASM_DATA, LegacyAsmData.class);
        SyntheticEmbedder.registerClassResource(t, MOD_METADATA, LegacyModMetadata.class);
        t.registerClassRedirect("net/minecraftforge/fml/common/discovery/ASMDataTable", ASM_DATA_TABLE);
        t.registerClassRedirect("net/minecraftforge/fml/common/discovery/ASMDataTable$ASMData", ASM_DATA);
        t.registerClassRedirect("net/minecraftforge/fml/common/ModMetadata", MOD_METADATA);

        // Both helpers take JDK types only, so their calls link without erasure.
        String reflection = "com/retromod/shim/forge/embedded/LegacyReflectionHelper";
        SyntheticEmbedder.registerClassResource(t, reflection, LegacyReflectionHelper.class);
        t.registerClassRedirect("net/minecraftforge/fml/relauncher/ReflectionHelper", reflection);
        t.registerClassRedirect("net/minecraftforge/fml/common/ObfuscationReflectionHelper", reflection);

        t.registerSyntheticClass(FML_LOG, fmlLogBytes());
        t.registerClassRedirect("net/minecraftforge/fml/common/FMLLog", FML_LOG);

        stubInterface(t, "net/minecraftforge/fml/common/IFuelHandler",
                "com/retromod/generated/forge1122/IFuelHandler");
        stubInterface(t, "net/minecraft/util/datafix/IFixableData",
                "com/retromod/generated/forge1122/IFixableData");
        // Command classes extend CommandBase, which is not bridged, but the @Mod class names
        // ICommand in its server-starting handler, and verifying that class loads the type.
        stubInterface(t, "net/minecraft/command/ICommand",
                "com/retromod/generated/forge1122/ICommand");
        // Proxies return the main-thread executor for packet handlers, which are inert here.
        stubInterface(t, "net/minecraft/util/IThreadListener",
                "com/retromod/generated/forge1122/IThreadListener");
        stubInterface(t, "net/minecraft/util/datafix/IFixType",
                "com/retromod/generated/forge1122/IFixType");
        // Forge's own save fixes: FMLCommonHandler.getDataFixer().init(modId, version) returns a
        // fix list that accepts registrations and never runs them, since no 1.12 save loads here.
        SyntheticEmbedder.registerClassResource(t, DATA_FIXER,
                com.retromod.shim.forge.embedded.LegacyCompoundDataFixer.class);
        SyntheticEmbedder.registerClassResource(t, MOD_FIXS, com.retromod.shim.forge.embedded.LegacyModFixs.class);
        t.registerClassRedirect("net/minecraftforge/common/util/CompoundDataFixer", DATA_FIXER);
        t.registerClassRedirect("net/minecraftforge/common/util/ModFixs", MOD_FIXS);
        for (String handler : new String[]{"net/minecraftforge/fml/common/FMLCommonHandler",
                "com/retromod/polyfill/forge/embedded/FMLCommonHandlerShim"}) {
            for (String fixer : new String[]{"net/minecraftforge/common/util/CompoundDataFixer", DATA_FIXER}) {
                t.registerMethodRedirect(handler, "getDataFixer", "()L" + fixer + ";", DATA_FIXER, "of",
                        "(Ljava/lang/Object;)L" + DATA_FIXER + ";");
            }
        }
        t.registerSyntheticClass(FIX_TYPES, fixTypesBytes());
        t.registerClassRedirect("net/minecraft/util/datafix/FixTypes", FIX_TYPES);
        registerRenamedMembers(t);
        registerStaticRegistries(t);
        registerCommands(t);
        stubInterface(t, "net/minecraft/item/IItemPropertyGetter",
                "com/retromod/generated/forge1122/IItemPropertyGetter");
        // The extended state extended IBlockState, now the BlockState class; an interface stub
        // would not be assignable where a block returns it as its state.
        t.registerClassRedirect("net/minecraftforge/common/property/IExtendedBlockState",
                "net/minecraft/world/level/block/state/BlockState");

        // Material: 1.20 removed the class, and a 1.12 mod that defines its own material
        // (`new Material(MapColor)` or a subclass) fails its registry class on that load.
        // Retromod already nulls the vanilla constants and turns super(Material) into default
        // block properties, so the stand-in only has to exist. It keeps the old name and is
        // embedded under the mod's own package like every synthetic.
        t.registerSyntheticClass(LEGACY_MATERIAL, legacyMaterialBytes(LEGACY_MATERIAL, "java/lang/Object"));
        for (String sub : new String[]{"MaterialLiquid", "MaterialLogic", "MaterialTransparent",
                "MaterialPortal"}) {
            String name = "net/minecraft/block/material/" + sub;
            t.registerSyntheticClass(name, legacyMaterialBytes(name, LEGACY_MATERIAL));
        }

        // LootTableList.ENTITIES_* constants and register(name): 1.14 made loot tables data, so
        // the constants read as null and register(name) is dropped by the post-remap pass.
        t.registerStaticFieldNuller(LOOT_TABLE_LIST);
    }

    /**
     * Post-remap rewrites for these stand-ins. Returns true when {@code mi} was changed or
     * removed.
     */
    static boolean rewriteCall(MethodNode m, MethodInsnNode mi) {
        if (ERASED_OWNERS.contains(mi.owner) || inheritedFromConfigStandIn(mi)) {
            return eraseToStandIn(m, mi);
        }
        if (Forge1122FluidBridge.rewriteCall(m, mi)) return true;
        if (mi.owner.equals(GAME_REGISTRY) && mi.getOpcode() == INVOKESTATIC) {
            return eraseToPolyfill(m, mi, false);
        }
        if (mi.owner.equals(ORE_DICTIONARY) && mi.getOpcode() == INVOKESTATIC) {
            return eraseToPolyfill(m, mi, true);
        }
        if (mi.getOpcode() == INVOKESTATIC && mi.owner.equals(LOOT_TABLE_LIST)
                && mi.desc.equals("(Lnet/minecraft/resources/ResourceLocation;)"
                        + "Lnet/minecraft/resources/ResourceLocation;")) {
            m.instructions.remove(mi); // register(name) returned its argument
            return true;
        }
        return false;
    }

    /**
     * A mod's own config class that extends {@code Configuration}, or command that extends
     * {@code CommandBase}, calls the inherited methods with itself as the owner, so those calls need
     * the same erasure. The jar's bytes still name the
     * 1.12 parent, so both spellings are checked.
     */
    private static boolean inheritedFromConfigStandIn(MethodInsnNode mi) {
        String owner = mi.owner;
        if (mi.name.equals("<init>") || owner.startsWith("net/minecraft") || owner.startsWith("com/retromod/")
                || owner.startsWith("java/")) {
            return false;
        }
        boolean extendsStandIn = false;
        for (String parent : new String[]{CONFIGURATION, "net/minecraftforge/common/config/Configuration",
                COMMAND_BASE, "net/minecraft/command/CommandBase"}) {
            extendsStandIn |= RetromodTransformer.currentJarClassInheritsFrom(owner, parent);
        }
        if (!extendsStandIn) return false;
        // A method the mod's own class declares keeps its own descriptor.
        for (String current = owner; current != null && !current.startsWith("net/minecraftforge/")
                && !current.startsWith("com/retromod/"); ) {
            byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(current);
            if (bytes == null) return false;
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
            for (MethodNode declared : node.methods) {
                if (declared.name.equals(mi.name) && declared.desc.equals(mi.desc)) return false;
            }
            current = node.superName;
        }
        return true;
    }

    /**
     * Reference parameters become {@code Object}, and a reference return that is not another
     * stand-in becomes {@code Object} plus a cast back, matching what the compiled class declares.
     */
    static boolean eraseToStandIn(MethodNode m, MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        StringBuilder desc = new StringBuilder("(");
        for (Type arg : args) desc.append(isReference(arg) ? "Ljava/lang/Object;" : arg.getDescriptor());
        desc.append(')');
        boolean castBack = isReference(ret) && !ret.getDescriptor().equals("Ljava/lang/Object;")
                && !ERASED_OWNERS.contains(ret.getInternalName());
        desc.append(castBack ? "Ljava/lang/Object;" : ret.getDescriptor());
        if (desc.toString().equals(mi.desc)) return false;
        mi.desc = desc.toString();
        if (castBack) m.instructions.insert(mi, new TypeInsnNode(CHECKCAST, ret.getInternalName()));
        return true;
    }

    static final String GAME_REGISTRY = "com/retromod/polyfill/forge/embedded/GameRegistryShim";
    static final String ORE_DICTIONARY = "com/retromod/polyfill/forge/embedded/OreDictionaryShim";

    /**
     * {@code GameRegistry} and {@code OreDictionary} statics take Minecraft types
     * ({@code registerTileEntity(Class, ResourceLocation)}, {@code registerOre(String, Item)}),
     * and the polyfills declare {@code Object} for them. Both keep {@code Class} and the varargs
     * {@code Object[]}; the ore dictionary also keeps its {@code String} ore names, while
     * {@code GameRegistry} declares its names as {@code Object}.
     */
    private static boolean eraseToPolyfill(MethodNode m, MethodInsnNode mi, boolean keepStrings) {
        StringBuilder desc = new StringBuilder("(");
        for (Type arg : Type.getArgumentTypes(mi.desc)) {
            String d = arg.getDescriptor();
            boolean keep = !isReference(arg) || d.equals("Ljava/lang/Class;")
                    || d.equals("[Ljava/lang/Object;")
                    || (keepStrings && d.equals("Ljava/lang/String;"));
            desc.append(keep ? d : "Ljava/lang/Object;");
        }
        // OreDictionary.getOres returned a NonNullList; the polyfill returns one typed as a List.
        String ret = Type.getReturnType(mi.desc).getDescriptor();
        boolean nonNullList = ret.equals("L" + NON_NULL_LIST + ";");
        desc.append(')').append(nonNullList ? "Ljava/util/List;" : ret);
        if (desc.toString().equals(mi.desc)) return false;
        mi.desc = desc.toString();
        if (nonNullList) m.instructions.insert(mi, new TypeInsnNode(CHECKCAST, NON_NULL_LIST));
        return true;
    }

    private static final String NON_NULL_LIST = "net/minecraft/core/NonNullList";

    private static boolean isReference(Type t) {
        return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
    }

    static final String LEGACY_MATERIAL = "net/minecraft/block/material/Material";

    /** A material class with the 1.12 {@code (MapColor)} constructor, holding the color only. */
    static byte[] legacyMaterialBytes(String name, String superName) {
        String mapColor = "Lnet/minecraft/world/level/material/MapColor;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        if (superName.equals("java/lang/Object")) {
            cw.visitField(ACC_PROTECTED | ACC_FINAL, "legacyColor", mapColor, null, null).visitEnd();
        }
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + mapColor + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        if (superName.equals("java/lang/Object")) {
            c.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            c.visitVarInsn(ALOAD, 0);
            c.visitVarInsn(ALOAD, 1);
            c.visitFieldInsn(PUTFIELD, name, "legacyColor", mapColor);
        } else {
            c.visitVarInsn(ALOAD, 1);
            c.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "(" + mapColor + ")V", false);
        }
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static void stubInterface(RetromodTransformer t, String removed, String stub) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, stub, null, "java/lang/Object", null);
        cw.visitEnd();
        t.registerSyntheticClass(stub, cw.toByteArray());
        t.registerClassRedirect(removed, stub);
    }

    static final String LEGACY_FOOD = "com/retromod/generated/forge1122/LegacyFood";
    private static final String DYE_COLOR = "net/minecraft/world/item/DyeColor";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String EFFECT = "net/minecraft/world/effect/MobEffectInstance";

    /**
     * 1.12 members whose MCP name never made it into Mojang's, so the SRG table cannot name them.
     * {@code EnumDyeColor.byMetadata} and {@code getMetadata} are {@code byId} and {@code getId}.
     * {@code ItemFood.setPotionEffect} chained an eating effect onto the item; current food takes
     * its effects from components set when the item is built, so the call keeps the item and drops
     * the effect.
     */
    static void registerRenamedMembers(RetromodTransformer t) {
        t.registerMethodRedirect(DYE_COLOR, "func_176764_b", "(I)L" + DYE_COLOR + ";", DYE_COLOR, "byId",
                "(I)L" + DYE_COLOR + ";");
        t.registerMethodRedirect(DYE_COLOR, "func_176765_a", "()I", DYE_COLOR, "getId", "()I");
        String setEffect = "(L" + EFFECT + ";F)L" + ITEM + ";";
        t.registerSyntheticClass(LEGACY_FOOD, legacyFoodBytes(setEffect));
        // A mod's food class still names 1.12's ItemFood as its parent when the hierarchy is read.
        for (String owner : new String[]{ITEM, "net/minecraft/item/ItemFood"}) {
            t.registerInheritedMethodRedirect(owner, "func_185070_a", setEffect, LEGACY_FOOD, "setPotionEffect",
                    "(L" + ITEM + ";L" + EFFECT + ";F)L" + ITEM + ";");
        }
    }

    /** {@code static Item setPotionEffect(Item item, MobEffectInstance effect, float chance)}: the item. */
    static byte[] legacyFoodBytes(String setEffect) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, LEGACY_FOOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setPotionEffect",
                "(L" + ITEM + ";L" + EFFECT + ";F)L" + ITEM + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 1.12 dimensions and biomes: a {@code WorldProvider} subclass registered under a number, and
     * {@code Biome} subclasses. 1.16 made both data, so registration is accepted and dropped and
     * the mod loads without its dimension and biomes. {@code WorldProvider} references already become {@code DimensionType}, which is
     * final, so only the provider subclasses move onto a stand-in base.
     */
    static void registerDimensions(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, DIMENSION_MANAGER,
                com.retromod.shim.forge.embedded.LegacyDimensionManager.class);
        t.registerClassRedirect("net/minecraftforge/common/DimensionManager", DIMENSION_MANAGER);
        t.registerGeneratedLegacySubclassBase("net/minecraft/world/WorldProvider",
                "net/minecraft/world/level/dimension/DimensionType", WORLD_PROVIDER, "java/lang/Object", "()V");
        // Biome subclasses: 1.16 made Biome final and data-driven, so a 1.12 biome class loads on a
        // stand-in base and its biome is not added.
        t.registerGeneratedLegacySubclassBase("net/minecraft/world/biome/Biome",
                "net/minecraft/world/level/biome/Biome", BIOME, "java/lang/Object", "()V");

        String type = "Lnet/minecraft/world/level/dimension/DimensionType;";
        String register = "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Class;Z)" + type;
        t.registerSyntheticClass(DIMENSION_TYPES, nullFactory(DIMENSION_TYPES, "register", register));
        t.registerMethodRedirect("net/minecraft/world/level/dimension/DimensionType", "register", register,
                DIMENSION_TYPES, "register", register);
    }

    /**
     * Smaller 1.12 surfaces that big content mods share: name lookups, an always-edible food flag,
     * interfaces whose systems were rebuilt, and the old event package.
     */
    static void registerLookupsAndMoves(RetromodTransformer t) {
        String block = "net/minecraft/world/level/block/Block";
        String item = "net/minecraft/world/item/Item";
        SyntheticEmbedder.registerClassResource(t, BLOCK_NAMES, com.retromod.shim.forge.embedded.LegacyBlockNames.class);
        t.registerSyntheticClass(NAME_LOOKUPS, nameLookupsBytes(block, item));
        for (String name : new String[] {"func_149684_b", "getBlockFromName"}) {
            t.registerMethodRedirect(block, name, "(Ljava/lang/String;)L" + block + ";",
                    NAME_LOOKUPS, "block", "(Ljava/lang/String;)L" + block + ";");
        }
        for (String name : new String[] {"func_111206_d", "getByNameOrId"}) {
            t.registerMethodRedirect(item, name, "(Ljava/lang/String;)L" + item + ";",
                    NAME_LOOKUPS, "item", "(Ljava/lang/String;)L" + item + ";");
        }
        // ItemFood.setAlwaysEdible(): eating rules moved into the food component, built elsewhere.
        // Keyed on the 1.12 owners too: the hierarchy proof reads the jar's own, unmapped parents.
        for (String owner : new String[] {item, "net/minecraft/item/Item", "net/minecraft/item/ItemFood"}) {
            for (String name : new String[] {"func_77848_i", "setAlwaysEdible"}) {
                t.registerInheritedMethodRedirect(owner, name, "()L" + item + ";",
                        NAME_LOOKUPS, "same", "(L" + item + ";)L" + item + ";");
            }
        }

        // Rebuilt systems a 1.12 class still implements: tile entity providers became EntityBlock
        // with a different factory, and world event listeners became game events. The class loads;
        // its tile entity and listener callbacks are not called.
        stubInterface(t, "net/minecraft/block/ITileEntityProvider", "com/retromod/generated/forge1122/ITileEntityProvider");
        stubInterface(t, "net/minecraft/world/IWorldEventListener", "com/retromod/generated/forge1122/IWorldEventListener");

        // 1.13 moved FML's game events into Forge's event packages, which later bridges carry on.
        String oldEvents = "net/minecraftforge/fml/common/gameevent/";
        String tick = "net/minecraftforge/event/TickEvent";
        t.registerClassRedirect(oldEvents + "TickEvent", tick);
        for (String nested : new String[] {"Phase", "Type", "PlayerTickEvent", "ClientTickEvent",
                "ServerTickEvent", "RenderTickEvent"}) {
            t.registerClassRedirect(oldEvents + "TickEvent$" + nested, tick + "$" + nested);
        }
        t.registerClassRedirect(oldEvents + "TickEvent$WorldTickEvent", tick + "$LevelTickEvent");
        String player = "net/minecraftforge/event/entity/player/PlayerEvent";
        t.registerClassRedirect(oldEvents + "PlayerEvent", player);
        for (String nested : new String[] {"PlayerLoggedInEvent", "PlayerLoggedOutEvent", "PlayerRespawnEvent",
                "PlayerChangedDimensionEvent", "ItemPickupEvent", "ItemCraftedEvent", "ItemSmeltedEvent"}) {
            t.registerClassRedirect(oldEvents + "PlayerEvent$" + nested, player + "$" + nested);
        }
    }

    /** Typed forwarders onto {@code LegacyBlockNames}, plus {@code same(item)}. */
    private static byte[] nameLookupsBytes(String block, String item) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NAME_LOOKUPS, null,
                "java/lang/Object", null);
        for (String[] lookup : new String[][] {{"block", block}, {"item", item}}) {
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, lookup[0],
                    "(Ljava/lang/String;)L" + lookup[1] + ";", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, BLOCK_NAMES, lookup[0], "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, lookup[1]);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        org.objectweb.asm.MethodVisitor same = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "same",
                "(L" + item + ";)L" + item + ";", null, null);
        same.visitCode();
        same.visitVarInsn(ALOAD, 0);
        same.visitInsn(ARETURN);
        same.visitMaxs(0, 0);
        same.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code LegacyItemSlab extends BlockItem}: the old constructors, with default properties. */
    private static byte[] itemSlabBytes() {
        String blockItem = "net/minecraft/world/item/BlockItem";
        String block = "Lnet/minecraft/world/level/block/Block;";
        String properties = "net/minecraft/world/item/Item$Properties";
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, ACC_PUBLIC | ACC_SUPER, ITEM_SLAB, null, blockItem, null);
        String slab = "Lnet/minecraft/world/level/block/SlabBlock;";
        for (String desc : new String[] {"(" + block + block + block + ")V", "(" + block + slab + slab + ")V"}) {
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitTypeInsn(NEW, properties);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, properties, "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESPECIAL, blockItem, "<init>", "(" + block + "L" + properties + ";)V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with one static method of this descriptor that returns null. */
    private static byte[] nullFactory(String internalName, String method, String descriptor) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, method, descriptor, null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 1.12's static {@code REGISTRY} fields, read through {@code LegacyRegistryNamespaced}. Each is
     * keyed by its MCP name, which the SRG table supplies, and by its SRG name in case it does not.
     */
    static void registerStaticRegistries(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, LEGACY_REGISTRY,
                com.retromod.shim.forge.embedded.LegacyRegistryNamespaced.class);
        String[] oldTypes = {"net/minecraft/util/registry/RegistryNamespaced",
                "net/minecraft/util/registry/RegistryNamespacedDefaultedByKey", "net/minecraft/util/registry/RegistrySimple"};
        for (String old : oldTypes) t.registerClassRedirect(old, LEGACY_REGISTRY);
        String[][] fields = {
            {"net/minecraft/world/level/block/Block", "field_149771_c", "blocks"},
            {"net/minecraft/world/item/Item", "field_150901_e", "items"},
            {"net/minecraft/sounds/SoundEvent", "field_187505_a", "soundEvents"},
            {"net/minecraft/world/level/biome/Biome", "field_185377_q", "biomes"},
            {"net/minecraft/world/effect/MobEffect", "field_188414_b", "mobEffects"},
            {"net/minecraft/world/item/alchemy/Potion", "field_185176_a", "potions"},
        };
        for (String[] field : fields) {
            for (String name : new String[]{"REGISTRY", field[1]}) {
                for (String type : new String[]{oldTypes[0], oldTypes[1], LEGACY_REGISTRY}) {
                    t.registerFieldRedirect(field[0], name, "L" + type + ";", LEGACY_REGISTRY, field[2],
                            "()L" + LEGACY_REGISTRY + ";");
                }
            }
        }
    }

    /**
     * 1.12 commands, run on Brigadier by {@code LegacyCommands}. {@code ICommandSender} becomes
     * {@code CommandSource}, which players and the server implement as they implemented the old
     * interface, and its old methods are answered from that source. {@code CommandBase}, its
     * exceptions, and {@code FMLServerStartingEvent} get stand-ins.
     */
    static void registerCommands(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, COMMAND_BASE, com.retromod.shim.forge.embedded.LegacyCommandBase.class);
        SyntheticEmbedder.registerClassResource(t, COMMANDS, com.retromod.shim.forge.embedded.LegacyCommands.class);
        SyntheticEmbedder.registerClassResource(t, SERVER_STARTING,
                com.retromod.shim.forge.embedded.LegacyServerStartingEvent.class);
        SyntheticEmbedder.registerClassResource(t, COMMAND_EXCEPTION,
                com.retromod.shim.forge.embedded.LegacyCommandException.class);
        String[][] exceptions = {{"WrongUsageException", "WrongUsage"}, {"NumberInvalidException", "NumberInvalid"},
                {"PlayerNotFoundException", "PlayerNotFound"}, {"SyntaxErrorException", "SyntaxError"}};
        t.registerClassRedirect("net/minecraft/command/CommandException", COMMAND_EXCEPTION);
        for (String[] exception : exceptions) {
            String nested = COMMAND_EXCEPTION + "$" + exception[1];
            SyntheticEmbedder.registerClassResource(t, nested, nestedClass(exception[1]));
            t.registerClassRedirect("net/minecraft/command/" + exception[0], nested);
        }
        t.registerClassRedirect("net/minecraft/command/CommandBase", COMMAND_BASE);
        t.registerClassRedirect("net/minecraft/command/ICommandSender", "net/minecraft/commands/CommandSource");
        t.registerClassRedirect("net/minecraftforge/fml/common/event/FMLServerStartingEvent", SERVER_STARTING);
        SyntheticEmbedder.registerClassResource(t, I18N, com.retromod.shim.forge.embedded.LegacyI18n.class);
        t.registerClassRedirect("net/minecraft/client/resources/I18n", I18N);
        t.registerClassRedirect("net/minecraft/util/text/translation/I18n", I18N);
        SyntheticEmbedder.registerClassResource(t, CLIENT_COMMANDS,
                com.retromod.shim.forge.embedded.LegacyClientCommandHandler.class);
        t.registerClassRedirect("net/minecraftforge/client/ClientCommandHandler", CLIENT_COMMANDS);

        // Systems 1.12 init code touched that later versions removed outright.
        SyntheticEmbedder.registerClassResource(t, INTER_MOD_COMMS,
                com.retromod.shim.forge.embedded.LegacyInterModComms.class);
        t.registerClassRedirect("net/minecraftforge/fml/common/event/FMLInterModComms", INTER_MOD_COMMS);
        SyntheticEmbedder.registerClassResource(t, ENCHANTMENT_TYPE,
                com.retromod.shim.forge.embedded.LegacyEnchantmentType.class);
        t.registerClassRedirect("net/minecraft/enchantment/EnumEnchantmentType", ENCHANTMENT_TYPE);
        SyntheticEmbedder.registerClassResource(t, PLANKS_TYPE, com.retromod.shim.forge.embedded.LegacyPlanksType.class);
        t.registerClassRedirect("net/minecraft/block/BlockPlanks$EnumType", PLANKS_TYPE);
        // Biome types became biome tags; the stand-in reads the tags that replaced each type.
        SyntheticEmbedder.registerClassResource(t, BIOME_DICTIONARY,
                com.retromod.shim.forge.embedded.LegacyBiomeDictionary.class);
        SyntheticEmbedder.registerClassResource(t, BIOME_TYPE, com.retromod.shim.forge.embedded.LegacyBiomeType.class);
        t.registerClassRedirect("net/minecraftforge/common/BiomeDictionary", BIOME_DICTIONARY);
        t.registerClassRedirect("net/minecraftforge/common/BiomeDictionary$Type", BIOME_TYPE);
        registerDimensions(t);
        // ItemSlab(block, half, double): 1.13 made a slab one block with a type property, placed by
        // a plain block item.
        t.registerSyntheticClass(ITEM_SLAB, itemSlabBytes());
        t.registerClassRedirect("net/minecraft/item/ItemSlab", ITEM_SLAB);
        registerLookupsAndMoves(t);
        stubInterface(t, "net/minecraftforge/common/capabilities/Capability$IStorage",
                "com/retromod/generated/forge1122/CapabilityStorage");
        // Client types that 1.12 blocks and items named in their own method signatures. A dedicated
        // server cannot load them, and reflecting over such a block then failed, so its registry
        // name was never read. The tooltip flag has a common home now; the color handlers become
        // inert interfaces, so those colors do not apply.
        t.registerClassRedirect("net/minecraft/client/util/ITooltipFlag", "net/minecraft/world/item/TooltipFlag");
        stubInterface(t, "net/minecraft/client/renderer/color/IBlockColor",
                "com/retromod/generated/forge1122/IBlockColor");
        stubInterface(t, "net/minecraft/client/renderer/color/IItemColor",
                "com/retromod/generated/forge1122/IItemColor");
        stubInterface(t, "net/minecraft/client/renderer/block/statemap/IStateMapper",
                "com/retromod/generated/forge1122/IStateMapper");
        SyntheticEmbedder.registerClassResource(t, "com/retromod/shim/forge/embedded/LegacyRenderLayer",
                com.retromod.shim.forge.embedded.LegacyRenderLayer.class);
        t.registerClassRedirect("net/minecraft/util/BlockRenderLayer", "com/retromod/shim/forge/embedded/LegacyRenderLayer");

        String source = "net/minecraft/commands/CommandSource";
        String component = "Lnet/minecraft/network/chat/Component;";
        String[][] senderMethods = {
            {"sendMessage", "(" + component + ")V", "sendMessage", "(Ljava/lang/Object;Ljava/lang/Object;)V"},
            {"func_145747_a", "(" + component + ")V", "sendMessage", "(Ljava/lang/Object;Ljava/lang/Object;)V"},
            {"getName", "()Ljava/lang/String;", "senderName", "(Ljava/lang/Object;)Ljava/lang/String;"},
            {"func_70005_c_", "()Ljava/lang/String;", "senderName", "(Ljava/lang/Object;)Ljava/lang/String;"},
            {"func_174793_f", "()Lnet/minecraft/world/entity/Entity;", "senderEntity", "(Ljava/lang/Object;)Ljava/lang/Object;"},
            {"func_184102_h", "()Lnet/minecraft/server/MinecraftServer;", "senderServer", "(Ljava/lang/Object;)Ljava/lang/Object;"},
            {"func_130014_f_", "()Lnet/minecraft/world/level/Level;", "senderLevel", "(Ljava/lang/Object;)Ljava/lang/Object;"},
            {"func_70003_b", "(ILjava/lang/String;)Z", "canUseCommand", "(Ljava/lang/Object;ILjava/lang/Object;)Z"},
        };
        for (String[] method : senderMethods) {
            t.registerMethodRedirect(source, method[0], method[1], COMMANDS, method[2], method[3]);
        }

        // new TextComponentString(text) and new TextComponentTranslation(key, args): both classes
        // became the Component interface, which has factories instead of constructors.
        String chat = "net/minecraft/network/chat/Component";
        t.registerConstructorRedirect(chat, "(Ljava/lang/String;)V", chat, "literal",
                "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", true);
        t.registerConstructorRedirect(chat, "(Ljava/lang/String;[Ljava/lang/Object;)V", chat, "translatable",
                "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;", true);
    }

    private static Class<?> nestedClass(String simpleName) {
        for (Class<?> nested : com.retromod.shim.forge.embedded.LegacyCommandException.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) return nested;
        }
        throw new IllegalStateException(simpleName);
    }

    /** The 1.12 fix type constants, each an instance of the {@code IFixType} stub. */
    static byte[] fixTypesBytes() {
        String[] constants = {"LEVEL", "PLAYER", "CHUNK", "BLOCK_ENTITY", "ENTITY", "ITEM_INSTANCE", "OPTIONS",
                "STRUCTURE"};
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FIX_TYPES, null, "java/lang/Object",
                new String[]{"com/retromod/generated/forge1122/IFixType"});
        for (String constant : constants) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, constant, "L" + FIX_TYPES + ";", null, null).visitEnd();
        }
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (String constant : constants) {
            clinit.visitTypeInsn(NEW, FIX_TYPES);
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, FIX_TYPES, "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, FIX_TYPES, constant, "L" + FIX_TYPES + ";");
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String LOGGER = "org/apache/logging/log4j/Logger";
    private static final String LEVEL = "org/apache/logging/log4j/Level";

    /**
     * {@code FMLLog}: a log4j {@code log} field and the 1.12 helpers, which took
     * {@code String.format} patterns. Log4j is on every loader's runtime classpath.
     */
    static byte[] fmlLogBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FML_LOG, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "log", "L" + LOGGER + ";", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("FML");
        clinit.visitMethodInsn(INVOKESTATIC, "org/apache/logging/log4j/LogManager", "getLogger",
                "(Ljava/lang/String;)L" + LOGGER + ";", false);
        clinit.visitFieldInsn(PUTSTATIC, FML_LOG, "log", "L" + LOGGER + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getLogger", "()L" + LOGGER + ";",
                null, null);
        get.visitCode();
        get.visitFieldInsn(GETSTATIC, FML_LOG, "log", "L" + LOGGER + ";");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        String[][] levels = {{"info", "INFO"}, {"warning", "WARN"}, {"severe", "ERROR"},
                {"bigWarning", "WARN"}, {"fine", "DEBUG"}, {"finer", "TRACE"}, {"finest", "TRACE"}};
        for (String[] level : levels) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_VARARGS, level[0],
                    "(Ljava/lang/String;[Ljava/lang/Object;)V", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, LEVEL, level[1], "L" + LEVEL + ";");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            emitLog(mv);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // log(Level, String, Object...) and log(String targetLog, Level, String, Object...)
        MethodVisitor log = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_VARARGS, "log",
                "(L" + LEVEL + ";Ljava/lang/String;[Ljava/lang/Object;)V", null, null);
        log.visitCode();
        log.visitVarInsn(ALOAD, 0);
        log.visitVarInsn(ALOAD, 1);
        log.visitVarInsn(ALOAD, 2);
        emitLog(log);
        log.visitInsn(RETURN);
        log.visitMaxs(0, 0);
        log.visitEnd();
        MethodVisitor target = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_VARARGS, "log",
                "(Ljava/lang/String;L" + LEVEL + ";Ljava/lang/String;[Ljava/lang/Object;)V", null, null);
        target.visitCode();
        target.visitVarInsn(ALOAD, 1);
        target.visitVarInsn(ALOAD, 2);
        target.visitVarInsn(ALOAD, 3);
        emitLog(target);
        target.visitInsn(RETURN);
        target.visitMaxs(0, 0);
        target.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Stack {@code [Level, format, args]} to {@code log.log(level, String.format(format, args))}. */
    private static void emitLog(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "format",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitFieldInsn(GETSTATIC, FML_LOG, "log", "L" + LOGGER + ";");
        mv.visitInsn(DUP_X2);
        mv.visitInsn(POP);
        mv.visitMethodInsn(INVOKEINTERFACE, LOGGER, "log", "(L" + LEVEL + ";Ljava/lang/String;)V", true);
    }
}
