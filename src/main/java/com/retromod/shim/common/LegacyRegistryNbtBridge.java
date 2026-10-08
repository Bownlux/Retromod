/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Bridges block entity and item NBT code from before 1.20.5, when saving and loading started to
 * take a {@code HolderLookup.Provider}, for Mojang-named mods (Forge 1.20.1 mods after the SRG
 * remap).
 *
 * <p>A mod block entity overrides {@code load(CompoundTag)} and {@code saveAdditional(CompoundTag)}.
 * The host never calls those any more, so the block entity silently stops saving, and their
 * {@code super} calls fail with {@code NoSuchMethodError}. Each old override gets a modern
 * override beside it that records the provider and calls the old body, and each {@code super}
 * call that reaches Minecraft is retargeted to the modern method with that provider. The same is
 * done for {@code getUpdateTag()}, which feeds the client its block entity data.
 *
 * <p>World saved data is handled the same way: an old {@code SavedData.save(CompoundTag)} override
 * gets the provider-taking override, and the old {@code DimensionDataStorage.get} and
 * {@code computeIfAbsent}, which took the loader directly, build the {@code SavedData.Factory}
 * that replaced it.
 *
 * <p>{@code ContainerHelper}, {@code ItemStack.of}, {@code ItemStack.save} and the
 * {@code BlockEntity.saveWith...} calls get the provider the same way. Outside a save or load the
 * running server's registries are used where the host is NeoForge, and the built-in registries
 * otherwise, which cannot encode data-driven content such as enchantments.
 */
public final class LegacyRegistryNbtBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public static final String HELPER = "com/retromod/generated/LegacyRegistryNbt";

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

    private static final String L_COMPOUND = "L" + COMPOUND + ";";
    private static final String L_PROVIDER = "L" + PROVIDER + ";";
    private static final String L_LIST = "L" + NON_NULL_LIST + ";";

    static final String OLD_LOAD = "(" + L_COMPOUND + ")V";
    static final String NEW_NBT = "(" + L_COMPOUND + L_PROVIDER + ")V";
    static final String OLD_UPDATE_TAG = "()" + L_COMPOUND;
    static final String NEW_UPDATE_TAG = "(" + L_PROVIDER + ")" + L_COMPOUND;
    static final String OLD_DATA_SAVE = "(" + L_COMPOUND + ")" + L_COMPOUND;
    static final String NEW_DATA_SAVE = "(" + L_COMPOUND + L_PROVIDER + ")" + L_COMPOUND;
    static final String FACTORY_INIT = "(Ljava/util/function/Supplier;Ljava/util/function/BiFunction;)V";
    static final String OLD_GET = "(Ljava/util/function/Function;Ljava/lang/String;)L" + SAVED_DATA + ";";
    static final String OLD_COMPUTE =
            "(Ljava/util/function/Function;Ljava/util/function/Supplier;Ljava/lang/String;)L" + SAVED_DATA + ";";

    private LegacyRegistryNbtBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostTakesProvider()) {
            LOGGER.debug("Registry NBT bridge not needed: the host keeps the old block entity NBT methods");
            return;
        }
        registerRedirects(transformer, ClassResourceInspector.exists(SERVER_HOOKS));
        if (hostHasProviderFactory()) registerSavedDataRedirects(transformer);
        LOGGER.info("Bridged pre-1.20.5 block entity and item NBT calls onto HolderLookup.Provider");
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, boolean serverHooks) {
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
        String storage = "L" + STORAGE + ";";
        transformer.registerMethodRedirect(STORAGE, "get", OLD_GET,
                HELPER, "dataGet", "(" + storage + OLD_GET.substring(1));
        transformer.registerMethodRedirect(STORAGE, "computeIfAbsent", OLD_COMPUTE,
                HELPER, "dataComputeIfAbsent", "(" + storage + OLD_COMPUTE.substring(1));
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

    /** Post-remap pass over one class: bridges the old overrides and their super calls. */
    public static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom,
            Function<String, byte[]> modClasses) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean blockEntity = (hasMethod(node, "load", OLD_LOAD) || hasMethod(node, "saveAdditional", OLD_LOAD)
                || hasMethod(node, "getUpdateTag", OLD_UPDATE_TAG)) && inheritsFrom.test(node.name, BLOCK_ENTITY);
        boolean savedData = hasMethod(node, "save", OLD_DATA_SAVE) && inheritsFrom.test(node.name, SAVED_DATA);
        if (!blockEntity && !savedData) return classBytes;

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
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "provider", "()" + L_PROVIDER, false);
                            super.visitMethodInsn(opcode, owner, method.equals("load") ? "loadAdditional" : method,
                                    modern, itf);
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
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
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
     * the owner upward declares the old method.
     */
    private static boolean reachesMinecraft(String owner, String method, String desc,
            Function<String, byte[]> modClasses) {
        String current = owner;
        while (current != null) {
            byte[] bytes = modClasses.apply(current);
            if (bytes == null) return true;
            ClassNode parent = new ClassNode();
            new ClassReader(bytes).accept(parent, ClassReader.SKIP_CODE);
            if (hasMethod(parent, method, desc)) return false;
            current = parent.superName;
        }
        return true;
    }

    /** Adds {@code modern(args, provider)} that records the provider and runs the old override. */
    private static void addBridge(ClassVisitor cv, ClassNode node, String oldName, String oldDesc,
            String newName, String newDesc) {
        if (!overridesOld(node, oldName, oldDesc) || hasMethod(node, newName, newDesc)) return;
        boolean returns = !oldDesc.endsWith(")V");
        MethodVisitor mv = cv.visitMethod(returns ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED, newName, newDesc,
                null, null);
        mv.visitCode();
        // Every old parameter is a reference; the provider follows them.
        int oldParams = org.objectweb.asm.Type.getArgumentTypes(oldDesc).length;
        int providerSlot = oldParams + 1;
        mv.visitVarInsn(Opcodes.ALOAD, providerSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, providerSlot + 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 1; i <= oldParams; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, oldName, oldDesc, false);
        mv.visitVarInsn(Opcodes.ALOAD, providerSlot + 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(returns ? Opcodes.ARETURN : Opcodes.RETURN);
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
        // provider it replaced and exit puts it back.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CURRENT",
                "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/ThreadLocal");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        clinit.visitInsn(Opcodes.RETURN);
        end(clinit);

        MethodVisitor enter = begin(cw, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;");
        enter.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        enter.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        enter.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        enter.visitVarInsn(Opcodes.ALOAD, 0);
        enter.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        enter.visitInsn(Opcodes.ARETURN);
        end(enter);

        MethodVisitor exit = begin(cw, "exit", "(Ljava/lang/Object;)V");
        exit.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        exit.visitVarInsn(Opcodes.ALOAD, 0);
        exit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        exit.visitInsn(Opcodes.RETURN);
        end(exit);

        emitProvider(cw, serverHooks);
        emitProviderCalls(cw);
        emitSavedDataLoader(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The provider of the save or load in progress, else the server's, else the built-in one. */
    private static void emitProvider(ClassWriter cw, boolean serverHooks) {
        MethodVisitor mv = begin(cw, "provider", "()" + L_PROVIDER);
        Label none = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER, "CURRENT", "Ljava/lang/ThreadLocal;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PROVIDER);
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
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "REGISTRY", "L" + REGISTRY + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY_ACCESS, "fromRegistryOfRegistries",
                "(L" + REGISTRY + ";)L" + REGISTRY_ACCESS + "$Frozen;", true);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    private static void emitProviderCalls(ClassWriter cw) {
        String list = L_COMPOUND + L_LIST;

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
    private static void emitSavedDataLoader(ClassWriter cw) {
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
        apply.visitVarInsn(Opcodes.ALOAD, 2);
        apply.visitTypeInsn(Opcodes.CHECKCAST, PROVIDER);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "enter", "(" + L_PROVIDER + ")Ljava/lang/Object;", false);
        apply.visitVarInsn(Opcodes.ASTORE, 3);
        apply.visitVarInsn(Opcodes.ALOAD, 0);
        apply.visitFieldInsn(Opcodes.GETFIELD, HELPER, "loader", "Ljava/util/function/Function;");
        apply.visitVarInsn(Opcodes.ALOAD, 1);
        apply.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        apply.visitVarInsn(Opcodes.ALOAD, 3);
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
        apply.visitInsn(Opcodes.ARETURN);
        end(apply);

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
