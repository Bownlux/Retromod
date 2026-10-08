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

    /** Compiled stand-ins declare {@code Object} parameters; calls on them are erased to match. */
    static final String CONFIGURATION = "com/retromod/shim/forge/embedded/LegacyConfiguration";
    static final String CONFIG_CATEGORY = "com/retromod/shim/forge/embedded/LegacyConfigCategory";
    static final String CONFIG_PROPERTY = "com/retromod/shim/forge/embedded/LegacyConfigProperty";
    static final String CONFIG_MANAGER = "com/retromod/shim/forge/embedded/LegacyConfigManager";

    static final Set<String> ERASED_OWNERS = Set.of(NETWORK_REGISTRY, CHANNEL, TARGET_POINT,
            FLUID_REGISTRY, CONFIGURATION, CONFIG_CATEGORY, CONFIG_PROPERTY, CONFIG_MANAGER,
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
        if (ERASED_OWNERS.contains(mi.owner)) {
            return eraseToStandIn(m, mi);
        }
        if (Forge1122FluidBridge.rewriteCall(m, mi)) return true;
        if (mi.owner.equals(GAME_REGISTRY) && mi.getOpcode() == INVOKESTATIC) {
            return eraseToPolyfill(mi, false);
        }
        if (mi.owner.equals(ORE_DICTIONARY) && mi.getOpcode() == INVOKESTATIC) {
            return eraseToPolyfill(mi, true);
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
    private static boolean eraseToPolyfill(MethodInsnNode mi, boolean keepStrings) {
        StringBuilder desc = new StringBuilder("(");
        for (Type arg : Type.getArgumentTypes(mi.desc)) {
            String d = arg.getDescriptor();
            boolean keep = !isReference(arg) || d.equals("Ljava/lang/Class;")
                    || d.equals("[Ljava/lang/Object;")
                    || (keepStrings && d.equals("Ljava/lang/String;"));
            desc.append(keep ? d : "Ljava/lang/Object;");
        }
        desc.append(')').append(Type.getReturnType(mi.desc).getDescriptor());
        if (desc.toString().equals(mi.desc)) return false;
        mi.desc = desc.toString();
        return true;
    }

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
