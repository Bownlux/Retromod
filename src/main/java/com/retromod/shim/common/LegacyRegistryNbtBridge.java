/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;
import java.util.function.Function;

/** Bridges block entity, item and world data NBT code from before 1.20.5, when saving began to take registries, for Mojang-named mods. */
public final class LegacyRegistryNbtBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public static final String HELPER = "com/retromod/generated/LegacyRegistryNbt";
    /** The registries of the save or load in progress, a rebuilt class every registry NBT bridge shares. */
    public static final String CONTEXT = RebuiltApiClasses.ROOT + "minecraft/core/LegacyRegistryContext";
    /** The calls that took no registries, rebuilt for a host that saves through ValueOutput and ValueInput. */
    public static final String MEMBERS = RebuiltApiClasses.ROOT + "minecraft/core/LegacyNbtMembers";

    static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    static final String TAG = "net/minecraft/nbt/Tag";
    static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    static final String CONTAINER_HELPER = "net/minecraft/world/ContainerHelper";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String NON_NULL_LIST = "net/minecraft/core/NonNullList";
    static final String REGISTRY_ACCESS = "net/minecraft/core/RegistryAccess";
    static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String SERVER_HOOKS = "net/neoforged/neoforge/server/ServerLifecycleHooks";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String SAVED_DATA = "net/minecraft/world/level/saveddata/SavedData";
    static final String FACTORY = SAVED_DATA + "$Factory";
    static final String STORAGE = "net/minecraft/world/level/storage/DimensionDataStorage";
    static final String DATA_PACKET = "net/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket";
    static final String RANDOMIZABLE = "net/minecraft/world/level/block/entity/RandomizableContainerBlockEntity";
    static final String RANDOMIZABLE_CONTAINER = "net/minecraft/world/RandomizableContainer";

    private static final String L_COMPOUND = "L" + COMPOUND + ";";
    private static final String L_PROVIDER = "L" + PROVIDER + ";";
    private static final String L_LIST = "L" + NON_NULL_LIST + ";";

    static final String OLD_LOAD = "(" + L_COMPOUND + ")V";
    static final String NEW_NBT = "(" + L_COMPOUND + L_PROVIDER + ")V";
    static final String OLD_UPDATE_TAG = "()" + L_COMPOUND;
    static final String NEW_UPDATE_TAG = "(" + L_PROVIDER + ")" + L_COMPOUND;
    static final String OLD_DATA_SAVE = "(" + L_COMPOUND + ")" + L_COMPOUND;
    static final String NEW_DATA_SAVE = "(" + L_COMPOUND + L_PROVIDER + ")" + L_COMPOUND;
    static final String OLD_FILE_SAVE = "(Ljava/io/File;)V";
    static final String NEW_FILE_SAVE = "(Ljava/io/File;" + L_PROVIDER + ")V";
    static final String FACTORY_INIT = "(Ljava/util/function/Supplier;Ljava/util/function/BiFunction;)V";
    static final String OLD_GET = "(Ljava/util/function/Function;Ljava/lang/String;)L" + SAVED_DATA + ";";
    static final String OLD_COMPUTE =
            "(Ljava/util/function/Function;Ljava/util/function/Supplier;Ljava/lang/String;)L" + SAVED_DATA + ";";
    static final String OLD_PACKET = "(L" + BLOCK_ENTITY + ";Ljava/util/function/Function;)L" + DATA_PACKET + ";";

    private LegacyRegistryNbtBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (hostTakesProvider()) {
            registerRedirects(transformer, ClassResourceInspector.exists(SERVER_HOOKS));
            if (hostHasProviderFactory()) registerSavedDataRedirects(transformer);
            LOGGER.debug(RetromodLog.DETAIL, "Bridged pre-1.20.5 block entity and item NBT calls onto HolderLookup.Provider");
            return;
        }
        // From 1.21.6 the overrides this pass gives a provider become ValueIO overrides in LegacyValueIoOverrideAdapter.
        if (LegacyValueIoOverrideAdapter.hostSavesThroughValueIo(LegacyValueIoOverrideAdapter.MOJANG)) {
            registerValueIoRedirects(transformer, ClassResourceInspector.exists(SERVER_HOOKS));
            LOGGER.debug(RetromodLog.DETAIL, "Bridged pre-1.20.5 block entity and item NBT calls onto the ValueIO saves");
            return;
        }
        LOGGER.debug("Registry NBT bridge not needed: the host keeps the old block entity NBT methods");
    }

    /** Registration for a host that saves through ValueOutput and ValueInput, without asking whether it does. */
    static void registerValueIoRedirects(RetromodTransformer transformer, boolean serverHooks) {
        RebuiltApiClasses.registerTree(transformer, CONTEXT);
        RebuiltApiClasses.registerTree(transformer, MEMBERS);
        transformer.registerSyntheticClass(HELPER, generateHelper(serverHooks, false));
        // The provider forms of these calls are gone too, so they go to rebuilt members that read the current save.
        String list = L_COMPOUND + L_LIST;
        transformer.registerMethodRedirect(CONTAINER_HELPER, "loadAllItems", "(" + list + ")V",
                MEMBERS, "loadAllItems", "(" + list + ")V");
        transformer.registerMethodRedirect(CONTAINER_HELPER, "saveAllItems", "(" + list + ")" + L_COMPOUND,
                MEMBERS, "saveAllItems", "(" + list + ")" + L_COMPOUND);
        transformer.registerMethodRedirect(CONTAINER_HELPER, "saveAllItems", "(" + list + "Z)" + L_COMPOUND,
                MEMBERS, "saveAllItems", "(" + list + "Z)" + L_COMPOUND);
        transformer.registerMethodRedirect(ITEM_STACK, "of", "(" + L_COMPOUND + ")L" + ITEM_STACK + ";",
                MEMBERS, "of", "(" + L_COMPOUND + ")L" + ITEM_STACK + ";");
        transformer.registerMethodRedirect(ITEM_STACK, "save", "(" + L_COMPOUND + ")" + L_COMPOUND,
                MEMBERS, "save", "(L" + ITEM_STACK + ";" + L_COMPOUND + ")" + L_COMPOUND);
        for (String save : new String[]{"saveWithoutMetadata", "saveWithFullMetadata", "saveWithId"}) {
            transformer.registerInheritedMethodRedirect(BLOCK_ENTITY, save, "()" + L_COMPOUND,
                    MEMBERS, save, "(L" + BLOCK_ENTITY + ";)" + L_COMPOUND);
        }
        // Forge's ItemStack was serializable to a tag; the item codec writes it now.
        transformer.registerMethodRedirect(ITEM_STACK, "serializeNBT", "()" + L_COMPOUND,
                MEMBERS, "serializeNBT", "(L" + ITEM_STACK + ";)" + L_COMPOUND);
        // A container read and wrote its loot table to the tag; the host now does it through ValueIO.
        for (String name : new String[]{"tryLoadLootTable", "trySaveLootTable"}) {
            transformer.registerInheritedMethodRedirect(RANDOMIZABLE, name, "(" + L_COMPOUND + ")Z",
                    MEMBERS, name, "(L" + RANDOMIZABLE_CONTAINER + ";" + L_COMPOUND + ")Z");
        }
        // A method reference to the old getUpdateTag; plain calls and super calls are rewritten in apply.
        transformer.registerWrappingMethodRedirect(BLOCK_ENTITY, "getUpdateTag", OLD_UPDATE_TAG,
                MEMBERS, "getUpdateTag", "(L" + BLOCK_ENTITY + ";)" + L_COMPOUND);
        transformer.registerMethodRedirect(DATA_PACKET, "create", OLD_PACKET, MEMBERS, "dataPacket", OLD_PACKET);
        registerSavedDataTypeRedirects(transformer);
    }

    /** 1.20.1 world data on a host that looks it up by {@code SavedDataType}, through Retromod's factory stand-in. */
    static void registerSavedDataTypeRedirects(RetromodTransformer transformer) {
        // The stand-in needs SavedDataStorage, which 26.1 named; offline, the version decides.
        boolean running = ClassResourceInspector.read(BLOCK_ENTITY) != null;
        boolean typed = running ? ClassResourceInspector.read(LegacySavedDataFactoryShim.STORAGE) != null
                && ClassResourceInspector.read(FACTORY) == null
                : RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        if (!typed) return;
        if (!transformer.getSyntheticClasses().containsKey(LegacySavedDataFactoryShim.FACTORY)) {
            LegacySavedDataFactoryShim.register(transformer);
        }
        // A direct save of any world data runs the data's own old method, the only save it has.
        transformer.registerMethodRedirect(SAVED_DATA, "save", OLD_DATA_SAVE, MEMBERS, "dataSave",
                "(Ljava/lang/Object;" + OLD_DATA_SAVE.substring(1), true);
        // Both storage names, whichever the class moves applied before the redirect is looked up.
        for (String storage : new String[]{STORAGE, LegacySavedDataFactoryShim.STORAGE}) {
            transformer.registerMethodRedirect(storage, "computeIfAbsent", OLD_COMPUTE, HELPER, "dataComputeIfAbsent",
                    "(L" + LegacySavedDataFactoryShim.STORAGE + ";" + OLD_COMPUTE.substring(1));
        }
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, boolean serverHooks) {
        RebuiltApiClasses.registerTree(transformer, CONTEXT);
        transformer.registerSyntheticClass(HELPER, generateHelper(serverHooks));
        String list = L_COMPOUND + L_LIST;
        transformer.registerMethodRedirect(CONTAINER_HELPER, "loadAllItems", "(" + list + ")V",
                HELPER, "loadAllItems", "(" + list + ")V");
        transformer.registerMethodRedirect(CONTAINER_HELPER, "saveAllItems", "(" + list + ")" + L_COMPOUND,
                HELPER, "saveAllItems", "(" + list + ")" + L_COMPOUND);
        transformer.registerMethodRedirect(CONTAINER_HELPER, "saveAllItems", "(" + list + "Z)" + L_COMPOUND,
                HELPER, "saveAllItems", "(" + list + "Z)" + L_COMPOUND);
        transformer.registerMethodRedirect(ITEM_STACK, "of", "(" + L_COMPOUND + ")L" + ITEM_STACK + ";",
                HELPER, "of", "(" + L_COMPOUND + ")L" + ITEM_STACK + ";");
        transformer.registerMethodRedirect(ITEM_STACK, "save", "(" + L_COMPOUND + ")" + L_COMPOUND,
                HELPER, "save", "(L" + ITEM_STACK + ";" + L_COMPOUND + ")" + L_COMPOUND);
        for (String save : new String[]{"saveWithoutMetadata", "saveWithFullMetadata", "saveWithId"}) {
            transformer.registerInheritedMethodRedirect(BLOCK_ENTITY, save, "()" + L_COMPOUND,
                    HELPER, save, "(L" + BLOCK_ENTITY + ";)" + L_COMPOUND);
        }
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerSavedDataRedirects(RetromodTransformer transformer) {
        // The old lookups took the loader directly; they now build the SavedData.Factory that replaced it.
        String storage = "L" + STORAGE + ";";
        transformer.registerMethodRedirect(STORAGE, "get", OLD_GET,
                HELPER, "dataGet", "(" + storage + OLD_GET.substring(1));
        transformer.registerMethodRedirect(STORAGE, "computeIfAbsent", OLD_COMPUTE,
                HELPER, "dataComputeIfAbsent", "(" + storage + OLD_COMPUTE.substring(1));
        // A direct save of any world data, such as Create's copy of a mod's data, passes the current registries.
        transformer.registerMethodRedirect(SAVED_DATA, "save", OLD_DATA_SAVE,
                HELPER, "dataSave", "(L" + SAVED_DATA + ";" + OLD_DATA_SAVE.substring(1), true);
    }

    private static volatile String fileSaveProbedFor;
    private static volatile boolean fileSaveWithProvider;

    /** Whether the host's world data saves to a file through {@code save(File, Provider)}; offline, as the factory does. */
    static boolean hostSavesFileWithProvider() {
        String host = String.valueOf(RetromodVersion.TARGET_MC_VERSION);
        if (!host.equals(fileSaveProbedFor)) {
            ClassNode data = ClassResourceInspector.read(SAVED_DATA);
            fileSaveWithProvider = data != null ? hasMethod(data, "save", NEW_FILE_SAVE) : hostHasProviderFactory();
            fileSaveProbedFor = host;
        }
        return fileSaveWithProvider;
    }

    /** The provider-taking {@code SavedData.Factory} of 1.20.5 to 1.21.4; offline, the version decides. */
    static boolean hostHasProviderFactory() {
        ClassNode factory = ClassResourceInspector.read(FACTORY);
        if (factory != null) return hasMethod(factory, "<init>", FACTORY_INIT);
        String host = RetromodVersion.TARGET_MC_VERSION;
        return RetromodVersion.compareMcVersions(host, "1.20.5") >= 0
                && RetromodVersion.compareMcVersions(host, "1.21.5") < 0;
    }

    /**
     * The host's own BlockEntity decides. Offline, the host version does, up to 1.21.6, which
     * moved block entity data to {@code ValueInput} and {@code ValueOutput}.
     */
    static boolean hostTakesProvider() {
        ClassNode blockEntity = ClassResourceInspector.read(BLOCK_ENTITY);
        if (blockEntity != null) {
            return hasMethod(blockEntity, "loadAdditional", NEW_NBT) && !hasMethod(blockEntity, "load", OLD_LOAD);
        }
        String host = RetromodVersion.TARGET_MC_VERSION;
        return RetromodVersion.compareMcVersions(host, "1.20.5") >= 0
                && RetromodVersion.compareMcVersions(host, "1.21.6") < 0;
    }

    /**
     * Post-remap pass over one class. The host never calls an old {@code load(CompoundTag)} or {@code saveAdditional(CompoundTag)}
     * again, so each gets a provider-taking override that runs it, and each super call that reaches Minecraft gets the
     * provider. A ValueIO host then turns those overrides into ValueIO ones in LegacyValueIoOverrideAdapter.
     */
    public static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom,
            Function<String, byte[]> modClasses) {
        return apply(classBytes, inheritsFrom, modClasses, false, null);
    }

    /**
     * The same pass. On a ValueIO host the tag a load hands Minecraft first goes through the rebuilt members, and their
     * item reads learn the data version of {@code sourceVersion}, the Minecraft version the mod was built for.
     */
    public static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom,
            Function<String, byte[]> modClasses, boolean valueIoHost, String sourceVersion) {
        if (classBytes == null) return null;
        int dataVersion = dataVersionOf(sourceVersion);
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean blockEntity = (hasMethod(node, "load", OLD_LOAD) || hasMethod(node, "saveAdditional", OLD_LOAD)
                || hasMethod(node, "getUpdateTag", OLD_UPDATE_TAG)) && inheritsFrom.test(node.name, BLOCK_ENTITY);
        boolean savedData = hasMethod(node, "save", OLD_DATA_SAVE) && inheritsFrom.test(node.name, SAVED_DATA);
        // Lootr's world data saves itself to a file in a folder it creates first; the host saves through the provider form.
        boolean savedFile = !valueIoHost && hasMethod(node, "save", OLD_FILE_SAVE) && hostSavesFileWithProvider()
                && inheritsFrom.test(node.name, SAVED_DATA);
        boolean updateTagCalls = callsOldUpdateTag(node, inheritsFrom);
        String text = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        boolean itemReads = valueIoHost && text.contains(MEMBERS);
        // A Forge item handler's load reads stacks a 1.20.1 world saved, on any host this pass runs on.
        boolean handlerReads = text.contains(com.retromod.shim.forge.ForgeTransferApiMoves.HANDLERS);
        if (!blockEntity && !savedData && !savedFile && !updateTagCalls && !itemReads && !handlerReads) return classBytes;

        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        String modern = modernOf(method, desc);
                        if (blockEntity && opcode == Opcodes.INVOKESPECIAL && modern != null
                                && reachesMinecraft(owner, method, desc, modClasses)) {
                            // The parent reads 1.20.1 stacks and JSON names only once they are in today's shapes.
                            if (valueIoHost && method.equals("load")) {
                                super.visitLdcInsn(dataVersion);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, MEMBERS, "forHost",
                                        "(" + L_COMPOUND + "I)" + L_COMPOUND, false);
                            }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "provider", "()" + L_PROVIDER, false);
                            super.visitMethodInsn(opcode, owner, method.equals("load") ? "loadAdditional" : method,
                                    modern, itf);
                            return;
                        }
                        // A super call to the old file save reaches the host's provider form with the current registries.
                        if (savedFile && opcode == Opcodes.INVOKESPECIAL && method.equals("save")
                                && desc.equals(OLD_FILE_SAVE) && reachesMinecraft(owner, method, desc, modClasses)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "provider", "()" + L_PROVIDER, false);
                            super.visitMethodInsn(opcode, owner, method, NEW_FILE_SAVE, itf);
                            return;
                        }
                        // A plain call to the old getUpdateTag passes registries to the host's form, which runs
                        // the receiver's old override through its bridge when it has one.
                        if (updateTagCalls && isOldUpdateTagCall(opcode, owner, method, desc, inheritsFrom)
                                && reachesMinecraft(owner, method, desc, modClasses)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "provider", "()" + L_PROVIDER, false);
                            super.visitMethodInsn(opcode, owner, method, NEW_UPDATE_TAG, itf);
                            return;
                        }
                        // The rebuilt item reads take the mod's own data version, which only this pass knows.
                        String versioned = itemReads && opcode == Opcodes.INVOKESTATIC && owner.equals(MEMBERS)
                                ? VERSIONED_READS.get(method + desc) : null;
                        if (versioned == null && handlerReads && opcode == Opcodes.INVOKESTATIC
                                && owner.equals(com.retromod.shim.forge.ForgeTransferApiMoves.HANDLERS)) {
                            versioned = HANDLER_READS.get(method + desc);
                        }
                        if (versioned != null) {
                            super.visitLdcInsn(dataVersion);
                            super.visitMethodInsn(opcode, owner, method, versioned, itf);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (blockEntity) {
                    addBridge(this, node, "load", OLD_LOAD, "loadAdditional", NEW_NBT);
                    addBridge(this, node, "saveAdditional", OLD_LOAD, "saveAdditional", NEW_NBT);
                    addBridge(this, node, "getUpdateTag", OLD_UPDATE_TAG, "getUpdateTag", NEW_UPDATE_TAG);
                }
                if (savedData) addBridge(this, node, "save", OLD_DATA_SAVE, "save", NEW_DATA_SAVE);
                // Public, as the host's file save it overrides is.
                if (savedFile) addBridge(this, node, "save", OLD_FILE_SAVE, "save", NEW_FILE_SAVE, true);
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    // The rebuilt reads that convert old stacks, and the same reads with the data version added last.
    private static final java.util.Map<String, String> VERSIONED_READS = java.util.Map.of(
            "of(" + L_COMPOUND + ")L" + ITEM_STACK + ";", "(" + L_COMPOUND + "I)L" + ITEM_STACK + ";",
            "loadAllItems(" + L_COMPOUND + L_LIST + ")V", "(" + L_COMPOUND + L_LIST + "I)V");

    // The Forge handler load, and the same load with the data version added last.
    private static final java.util.Map<String, String> HANDLER_READS = java.util.Map.of(
            "deserializeNBT(Ljava/lang/Object;" + L_COMPOUND + ")V", "(Ljava/lang/Object;" + L_COMPOUND + "I)V");

    // The data version each release saved with, from the Minecraft Wiki's data version table.
    private static final java.util.NavigableMap<String, Integer> DATA_VERSIONS = new java.util.TreeMap<>(
            RetromodVersion::compareMcVersions);

    static {
        String[][] releases = {{"1.9", "169"}, {"1.9.4", "184"}, {"1.10", "510"}, {"1.10.2", "512"}, {"1.11", "819"},
                {"1.11.2", "922"}, {"1.12", "1139"}, {"1.12.2", "1343"}, {"1.13", "1519"}, {"1.13.2", "1631"},
                {"1.14", "1952"}, {"1.14.4", "1976"}, {"1.15", "2225"}, {"1.15.2", "2230"}, {"1.16", "2566"},
                {"1.16.5", "2586"}, {"1.17", "2724"}, {"1.17.1", "2730"}, {"1.18", "2860"}, {"1.18.2", "2975"},
                {"1.19", "3105"}, {"1.19.2", "3120"}, {"1.19.3", "3218"}, {"1.19.4", "3337"}, {"1.20", "3463"},
                {"1.20.1", "3465"}, {"1.20.2", "3578"}, {"1.20.3", "3698"}, {"1.20.4", "3700"}, {"1.20.5", "3837"},
                {"1.20.6", "3839"}, {"1.21", "3953"}, {"1.21.1", "3955"}, {"1.21.2", "4080"}, {"1.21.3", "4082"},
                {"1.21.4", "4189"}, {"1.21.5", "4325"}};
        for (String[] release : releases) DATA_VERSIONS.put(release[0], Integer.parseInt(release[1]));
    }

    /**
     * The data version a mod built for {@code sourceVersion} saved with: the release's, or the closest earlier one
     * listed, 99 before 1.9, and 1.20.1's when the version is unknown. Every version from 1.21.5 on reads as 1.21.5's,
     * which is all the rebuilt reads tell apart.
     */
    static int dataVersionOf(String sourceVersion) {
        if (sourceVersion == null) return 3465;
        java.util.Map.Entry<String, Integer> release = DATA_VERSIONS.floorEntry(sourceVersion);
        return release == null ? 99 : release.getValue();
    }

    /** Whether the class calls the old {@code getUpdateTag()} on a block entity, other than through super. */
    private static boolean callsOldUpdateTag(ClassNode node, BiPredicate<String, String> inheritsFrom) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && isOldUpdateTagCall(call.getOpcode(),
                        call.owner, call.name, call.desc, inheritsFrom)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isOldUpdateTagCall(int opcode, String owner, String method, String desc,
            BiPredicate<String, String> inheritsFrom) {
        return opcode == Opcodes.INVOKEVIRTUAL && method.equals("getUpdateTag") && desc.equals(OLD_UPDATE_TAG)
                && inheritsFrom.test(owner, BLOCK_ENTITY);
    }

    /** The modern descriptor for a super call to a removed NBT method, or null. */
    private static String modernOf(String method, String desc) {
        if ((method.equals("load") || method.equals("saveAdditional")) && desc.equals(OLD_LOAD)) {
            return NEW_NBT;
        }
        if (method.equals("getUpdateTag") && desc.equals(OLD_UPDATE_TAG)) return NEW_UPDATE_TAG;
        return null;
    }

    /**
     * True when a super call is answered by Minecraft rather than a mod class: no mod class from
     * the owner upward declares the old method. {@code modClasses} answers for the jar and the
     * libraries it builds on, so a parent in another mod is read rather than taken for Minecraft.
     */
    static boolean reachesMinecraft(String owner, String method, String desc,
            Function<String, byte[]> modClasses) {
        String current = owner;
        while (current != null) {
            // Minecraft's own classes lost the old method; any other class has to be read to know.
            if (current.startsWith("net/minecraft/") || current.startsWith("java/")) return true;
            byte[] bytes = modClasses.apply(current);
            // A mod parent that cannot be read may declare the old method, so the call is left as it is.
            if (bytes == null) return false;
            ClassNode parent = new ClassNode();
            new ClassReader(bytes).accept(parent, ClassReader.SKIP_CODE);
            if (hasMethod(parent, method, desc)) return false;
            current = parent.superName;
        }
        return true;
    }

    /**
     * Adds {@code modern(args, provider)} that records the provider, runs the old override, and puts the
     * previous provider back, also when the override throws.
     */
    private static void addBridge(ClassVisitor cv, ClassNode node, String oldName, String oldDesc,
            String newName, String newDesc) {
        addBridge(cv, node, oldName, oldDesc, newName, newDesc, false);
    }

    private static void addBridge(ClassVisitor cv, ClassNode node, String oldName, String oldDesc,
            String newName, String newDesc, boolean isPublic) {
        if (!overridesOld(node, oldName, oldDesc) || hasMethod(node, newName, newDesc)) return;
        boolean returns = !oldDesc.endsWith(")V");
        MethodVisitor mv = cv.visitMethod(returns || isPublic ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED, newName,
                newDesc, null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        mv.visitTryCatchBlock(start, end, failed, null);
        // Every old parameter is a reference; the provider follows them.
        org.objectweb.asm.Type[] oldParams = org.objectweb.asm.Type.getArgumentTypes(oldDesc);
        int providerSlot = oldParams.length + 1;
        int previous = providerSlot + 1;
        mv.visitVarInsn(Opcodes.ALOAD, providerSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, previous);
        mv.visitLabel(start);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 1; i <= oldParams.length; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, oldName, oldDesc, false);
        mv.visitLabel(end);
        mv.visitVarInsn(Opcodes.ALOAD, previous);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(returns ? Opcodes.ARETURN : Opcodes.RETURN);
        // Every mod shares the provider through one context, so a failed save or load must not leave it current.
        mv.visitLabel(failed);
        if ((node.version & 0xFFFF) >= Opcodes.V1_6) {
            Object[] locals = new Object[oldParams.length + 3];
            locals[0] = node.name;
            for (int i = 0; i < oldParams.length; i++) locals[i + 1] = oldParams[i].getInternalName();
            locals[providerSlot] = PROVIDER;
            locals[previous] = "java/lang/Object";
            mv.visitFrame(Opcodes.F_FULL, locals.length, locals, 1, new Object[]{"java/lang/Throwable"});
        }
        mv.visitVarInsn(Opcodes.ASTORE, previous + 1);
        mv.visitVarInsn(Opcodes.ALOAD, previous);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, previous + 1);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * An instance override of the old method. A private or static method with the same shape is
     * the mod's own helper, which the host never called, so it gets no modern override.
     */
    private static boolean overridesOld(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)
                    && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateHelper(boolean serverHooks) {
        return generateHelper(serverHooks, true);
    }

    /** The helper; {@code providerHost} adds the calls that reach a 1.20.5 to 1.21.5 host's provider forms. */
    static byte[] generateHelper(boolean serverHooks, boolean providerHost) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", new String[]{"java/util/function/BiFunction"});
        // A save or load can nest another (a container saving its items), so enter returns the
        // provider it replaced and exit puts it back. The shared context keeps them, so the other
        // registry NBT bridges see the same save.
        MethodVisitor enter = begin(cw, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;");
        enter.visitVarInsn(Opcodes.ALOAD, 0);
        enter.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;", false);
        enter.visitInsn(Opcodes.ARETURN);
        end(enter);

        MethodVisitor exit = begin(cw, "exit", "(Ljava/lang/Object;)V");
        exit.visitVarInsn(Opcodes.ALOAD, 0);
        exit.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "exit", "(Ljava/lang/Object;)V", false);
        exit.visitInsn(Opcodes.RETURN);
        end(exit);

        emitProvider(cw, serverHooks, providerHost);
        emitLoaderWrapper(cw);
        if (providerHost) {
            emitProviderCalls(cw);
            emitSavedDataFactoryLookups(cw);
        } else {
            // Newer hosts get the other calls from the rebuilt members, which read the current save's registries.
            emitSavedDataTypeLookup(cw);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The provider of the save or load in progress, else the server's, else the built-in one. On a ValueIO host the shared
     * context also finds Forge's server or the client world before the built-in registries.
     */
    private static void emitProvider(ClassWriter cw, boolean serverHooks, boolean providerHost) {
        MethodVisitor mv = begin(cw, "provider", "()" + L_PROVIDER);
        Label none = new Label();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "current", "()" + L_PROVIDER, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.POP);
        if (serverHooks) {
            Label noServer = new Label();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_HOOKS, "getCurrentServer", "()L" + SERVER + ";", false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, noServer);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SERVER, "registryAccess", "()L" + REGISTRY_ACCESS + "$Frozen;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(noServer);
            mv.visitInsn(Opcodes.POP);
        }
        if (!providerHost) {
            Label noWorld = new Label();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "available", "()" + L_PROVIDER, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, noWorld);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(noWorld);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "REGISTRY", "L" + REGISTRY + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_ACCESS, "fromRegistryOfRegistries",
                "(L" + REGISTRY + ";)L" + REGISTRY_ACCESS + "$Frozen;", true);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    private static void emitProviderCalls(ClassWriter cw) {
        String list = L_COMPOUND + L_LIST;

        // data.save(tag) with the registries of the save in progress, through the data's own provider form.
        MethodVisitor dataSave = begin(cw, "dataSave", "(L" + SAVED_DATA + ";" + OLD_DATA_SAVE.substring(1));
        dataSave.visitVarInsn(Opcodes.ALOAD, 0);
        dataSave.visitVarInsn(Opcodes.ALOAD, 1);
        provider(dataSave);
        dataSave.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SAVED_DATA, "save", NEW_DATA_SAVE, false);
        dataSave.visitInsn(Opcodes.ARETURN);
        end(dataSave);

        MethodVisitor load = begin(cw, "loadAllItems", "(" + list + ")V");
        load.visitVarInsn(Opcodes.ALOAD, 0);
        load.visitVarInsn(Opcodes.ALOAD, 1);
        provider(load);
        load.visitMethodInsn(Opcodes.INVOKESTATIC, CONTAINER_HELPER, "loadAllItems", "(" + list + L_PROVIDER + ")V", false);
        load.visitInsn(Opcodes.RETURN);
        end(load);

        MethodVisitor save = begin(cw, "saveAllItems", "(" + list + ")" + L_COMPOUND);
        save.visitVarInsn(Opcodes.ALOAD, 0);
        save.visitVarInsn(Opcodes.ALOAD, 1);
        provider(save);
        save.visitMethodInsn(Opcodes.INVOKESTATIC, CONTAINER_HELPER, "saveAllItems",
                "(" + list + L_PROVIDER + ")" + L_COMPOUND, false);
        save.visitInsn(Opcodes.ARETURN);
        end(save);

        MethodVisitor saveEmpty = begin(cw, "saveAllItems", "(" + list + "Z)" + L_COMPOUND);
        saveEmpty.visitVarInsn(Opcodes.ALOAD, 0);
        saveEmpty.visitVarInsn(Opcodes.ALOAD, 1);
        saveEmpty.visitVarInsn(Opcodes.ILOAD, 2);
        provider(saveEmpty);
        saveEmpty.visitMethodInsn(Opcodes.INVOKESTATIC, CONTAINER_HELPER, "saveAllItems",
                "(" + list + "Z" + L_PROVIDER + ")" + L_COMPOUND, false);
        saveEmpty.visitInsn(Opcodes.ARETURN);
        end(saveEmpty);

        MethodVisitor of = begin(cw, "of", "(" + L_COMPOUND + ")L" + ITEM_STACK + ";");
        provider(of);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitMethodInsn(Opcodes.INVOKESTATIC, ITEM_STACK, "parseOptional",
                "(" + L_PROVIDER + L_COMPOUND + ")L" + ITEM_STACK + ";", false);
        of.visitInsn(Opcodes.ARETURN);
        end(of);

        // An empty stack had no NBT to save before; the new save refuses it, so it writes nothing.
        MethodVisitor stackSave = begin(cw, "save", "(L" + ITEM_STACK + ";" + L_COMPOUND + ")" + L_COMPOUND);
        Label empty = new Label();
        stackSave.visitVarInsn(Opcodes.ALOAD, 0);
        stackSave.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        stackSave.visitJumpInsn(Opcodes.IFNE, empty);
        stackSave.visitVarInsn(Opcodes.ALOAD, 0);
        provider(stackSave);
        stackSave.visitVarInsn(Opcodes.ALOAD, 1);
        stackSave.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "save",
                "(" + L_PROVIDER + "L" + TAG + ";)L" + TAG + ";", false);
        stackSave.visitTypeInsn(Opcodes.CHECKCAST, COMPOUND);
        stackSave.visitInsn(Opcodes.ARETURN);
        stackSave.visitLabel(empty);
        stackSave.visitVarInsn(Opcodes.ALOAD, 1);
        stackSave.visitInsn(Opcodes.ARETURN);
        end(stackSave);

        for (String name : new String[]{"saveWithoutMetadata", "saveWithFullMetadata", "saveWithId"}) {
            MethodVisitor mv = begin(cw, name, "(L" + BLOCK_ENTITY + ";)" + L_COMPOUND);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            provider(mv);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLOCK_ENTITY, name, "(" + L_PROVIDER + ")" + L_COMPOUND, false);
            mv.visitInsn(Opcodes.ARETURN);
            end(mv);
        }
    }

    /**
     * An instance wraps an old {@code Function<CompoundTag, T>} loader as the factory's
     * {@code BiFunction<CompoundTag, Provider, T>}, recording the provider while it runs.
     */
    private static void emitLoaderWrapper(ClassWriter cw) {
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "loader", "Ljava/util/function/Function;", null, null)
                .visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/util/function/Function;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, HELPER, "loader", "Ljava/util/function/Function;");
        init.visitInsn(Opcodes.RETURN);
        end(init);

        MethodVisitor apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        apply.visitTryCatchBlock(start, end, failed, null);
        apply.visitVarInsn(Opcodes.ALOAD, 2);
        apply.visitTypeInsn(Opcodes.CHECKCAST, PROVIDER);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;", false);
        apply.visitVarInsn(Opcodes.ASTORE, 3);
        apply.visitLabel(start);
        apply.visitVarInsn(Opcodes.ALOAD, 0);
        apply.visitFieldInsn(Opcodes.GETFIELD, HELPER, "loader", "Ljava/util/function/Function;");
        apply.visitVarInsn(Opcodes.ALOAD, 1);
        apply.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        apply.visitLabel(end);
        apply.visitVarInsn(Opcodes.ALOAD, 3);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
        apply.visitInsn(Opcodes.ARETURN);
        // A loader that throws still puts the previous provider back before the failure goes on.
        apply.visitLabel(failed);
        apply.visitVarInsn(Opcodes.ASTORE, 4);
        apply.visitVarInsn(Opcodes.ALOAD, 3);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
        apply.visitVarInsn(Opcodes.ALOAD, 4);
        apply.visitInsn(Opcodes.ATHROW);
        end(apply);
    }

    /** The old lookups on a 1.20.5 to 1.21.4 host, which build the {@code SavedData.Factory} that replaced them. */
    private static void emitSavedDataFactoryLookups(ClassWriter cw) {
        String storage = "L" + STORAGE + ";";
        MethodVisitor get = begin(cw, "dataGet", "(" + storage + OLD_GET.substring(1));
        get.visitVarInsn(Opcodes.ALOAD, 0);
        newFactory(get, -1, 1);
        get.visitVarInsn(Opcodes.ALOAD, 2);
        get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STORAGE, "get",
                "(L" + FACTORY + ";Ljava/lang/String;)L" + SAVED_DATA + ";", false);
        get.visitInsn(Opcodes.ARETURN);
        end(get);

        MethodVisitor compute = begin(cw, "dataComputeIfAbsent", "(" + storage + OLD_COMPUTE.substring(1));
        compute.visitVarInsn(Opcodes.ALOAD, 0);
        newFactory(compute, 2, 1);
        compute.visitVarInsn(Opcodes.ALOAD, 3);
        compute.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STORAGE, "computeIfAbsent",
                "(L" + FACTORY + ";Ljava/lang/String;)L" + SAVED_DATA + ";", false);
        compute.visitInsn(Opcodes.ARETURN);
        end(compute);
    }

    /**
     * {@code computeIfAbsent(loader, constructor, name)} where world data is looked up by {@code SavedDataType}: Retromod's
     * factory stand-in names the type after the old string, and the wrapped loader runs with the storage's registries.
     */
    private static void emitSavedDataTypeLookup(ClassWriter cw) {
        String storage = LegacySavedDataFactoryShim.STORAGE;
        String factory = LegacySavedDataFactoryShim.FACTORY;
        MethodVisitor compute = begin(cw, "dataComputeIfAbsent", "(L" + storage + ";" + OLD_COMPUTE.substring(1));
        compute.visitVarInsn(Opcodes.ALOAD, 0);
        compute.visitTypeInsn(Opcodes.NEW, factory);
        compute.visitInsn(Opcodes.DUP);
        compute.visitVarInsn(Opcodes.ALOAD, 2);
        compute.visitTypeInsn(Opcodes.NEW, HELPER);
        compute.visitInsn(Opcodes.DUP);
        compute.visitVarInsn(Opcodes.ALOAD, 1);
        compute.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER, "<init>", "(Ljava/util/function/Function;)V", false);
        // 1.20.1 world data had no data fixer type.
        compute.visitInsn(Opcodes.ACONST_NULL);
        compute.visitMethodInsn(Opcodes.INVOKESPECIAL, factory, "<init>",
                "(Ljava/util/function/Supplier;Ljava/util/function/BiFunction;"
                        + "Lnet/minecraft/util/datafix/DataFixTypes;)V", false);
        compute.visitVarInsn(Opcodes.ALOAD, 3);
        compute.visitMethodInsn(Opcodes.INVOKESTATIC, factory, "computeIfAbsent",
                "(L" + storage + ";L" + factory + ";Ljava/lang/String;)L" + SAVED_DATA + ";", false);
        compute.visitInsn(Opcodes.ARETURN);
        end(compute);
    }

    /**
     * {@code new SavedData.Factory(constructor, new LegacyRegistryNbt(loader))}. A plain lookup
     * never creates the data, so it passes no constructor ({@code constructorSlot} below zero).
     */
    private static void newFactory(MethodVisitor mv, int constructorSlot, int loaderSlot) {
        mv.visitTypeInsn(Opcodes.NEW, FACTORY);
        mv.visitInsn(Opcodes.DUP);
        if (constructorSlot < 0) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, constructorSlot);
        }
        mv.visitTypeInsn(Opcodes.NEW, HELPER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, loaderSlot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER, "<init>", "(Ljava/util/function/Function;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FACTORY, "<init>", FACTORY_INIT, false);
    }

    private static void provider(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "provider", "()" + L_PROVIDER, false);
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
