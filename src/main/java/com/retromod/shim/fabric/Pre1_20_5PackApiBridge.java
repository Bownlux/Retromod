/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges pack APIs that mods with their own pack folders call, for intermediary-namespace Fabric
 * mods on pre-26.1 hosts. Each change is probed separately.
 *
 * <p>1.20.5 replaced {@code Pack.readMetaAndCreate(String id, Component title, boolean required,
 * ResourcesSupplier, PackType, Position, PackSource)} with a form that takes a
 * {@code PackLocationInfo} and a {@code PackSelectionConfig}. The factory builds both from the old
 * arguments, with no known-pack entry and an unfixed position, which is what the old pack had.
 *
 * <p>Newer hosts require a {@code DirectoryValidator} as a fourth argument, which vets symbolic
 * links inside the pack folder. Mods that scan their own pack folder, such as Palladium's addon
 * packs, died with {@code NoSuchMethodError} while loading. The old constructor did not check
 * links at all, so the factory passes a validator that accepts every path. That keeps the old
 * behavior for the mod's own folder and does not loosen vanilla's checks on its folders.
 *
 * <p>1.20.5 also replaced {@code Pack.ResourcesSupplier.open(String)} with
 * {@code openPrimary(PackLocationInfo)} and {@code openFull(PackLocationInfo, Metadata)}. A mod
 * lambda still implements {@code open}, so the first pack scan threw
 * {@code AbstractMethodError}. Such lambdas are built as a {@code Function} instead and wrapped
 * in a generated supplier that answers both new methods with the pack id. Packs written before
 * 1.20.2 carry no overlays, so opening the full pack the same way as the primary one loses nothing.
 *
 * <p>The same release made {@code AbstractPackResources} take a {@code PackLocationInfo} instead
 * of {@code (String name, boolean builtin)}. Subclasses are rebased onto a generated base that
 * accepts both constructors and builds the location from the name, so a mod's own pack class
 * links. The pack is titled with its id and marked built-in or default as the flag said.
 */
public final class Pre1_20_5PackApiBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String FOLDER_SOURCE = "net/minecraft/class_3279";
    static final String PACK_TYPE = "net/minecraft/class_3264";
    static final String PACK_SOURCE = "net/minecraft/class_5352";
    static final String DIRECTORY_VALIDATOR = "net/minecraft/class_8580";
    static final String FACTORY = "com/retromod/generated/LegacyPackSources";
    static final String PACK = "net/minecraft/class_3288";
    static final String PACK_LOCATION_INFO = "net/minecraft/class_9224";
    static final String PACK_SELECTION_CONFIG = "net/minecraft/class_9225";
    static final String READ_META_AND_CREATE = "method_45275";
    static final String OLD_READ_META = "(Ljava/lang/String;Lnet/minecraft/class_2561;"
            + "ZLnet/minecraft/class_3288$class_7680;L" + PACK_TYPE + ";"
            + "Lnet/minecraft/class_3288$class_3289;L" + PACK_SOURCE + ";)L" + PACK + ";";
    static final String NEW_READ_META = "(L" + PACK_LOCATION_INFO + ";Lnet/minecraft/class_3288$class_7680;L"
            + PACK_TYPE + ";L" + PACK_SELECTION_CONFIG + ";)L" + PACK + ";";

    static final String RESOURCES_SUPPLIER = "net/minecraft/class_3288$class_7680";
    static final String PACK_RESOURCES = "net/minecraft/class_3262";
    static final String SUPPLIER_ADAPTER = "com/retromod/generated/LegacyPackSupplier";
    static final String OLD_SUPPLIER_NAME = "open";
    static final String OLD_SUPPLIER_DESC = "(Ljava/lang/String;)L" + PACK_RESOURCES + ";";
    static final String OPEN_PRIMARY = "method_52424";
    static final String OPEN_FULL = "method_52425";
    static final String WRAP_SUPPLIER = "supplier";
    static final String WRAP_SUPPLIER_DESC = "(Ljava/util/function/Function;)L" + RESOURCES_SUPPLIER + ";";
    private static final String OPEN_PRIMARY_DESC = "(L" + PACK_LOCATION_INFO + ";)L" + PACK_RESOURCES + ";";
    private static final String OPEN_FULL_DESC = "(L" + PACK_LOCATION_INFO
            + ";Lnet/minecraft/class_3288$class_7679;)L" + PACK_RESOURCES + ";";
    private static final String PACK_ID = "comp_2329";

    static final String PACK_RESOURCES_BASE = "net/minecraft/class_3255";
    static final String LEGACY_PACK_RESOURCES = "com/retromod/generated/LegacyAbstractPackResources";
    static final String OLD_RESOURCES_CTOR = "(Ljava/lang/String;Z)V";
    static final String MODERN_RESOURCES_CTOR = "(L" + PACK_LOCATION_INFO + ";)V";
    private static final String COMPONENT = "net/minecraft/class_2561";
    private static final String COMPONENT_LITERAL = "method_43470";
    private static final String PACK_SOURCE_DEFAULT = "field_25347";
    private static final String PACK_SOURCE_BUILT_IN = "field_25348";

    private static final String OLD_PARAMS =
            "Ljava/nio/file/Path;L" + PACK_TYPE + ";L" + PACK_SOURCE + ";";
    static final String OLD_CTOR = "(" + OLD_PARAMS + ")V";
    static final String MODERN_CTOR = "(" + OLD_PARAMS + "L" + DIRECTORY_VALIDATOR + ";)V";

    private Pre1_20_5PackApiBridge() {}

    /** Registers each factory only when the host has the new shape and not the old one. */
    public static void register(RetromodTransformer transformer) {
        ClassNode source = ClassResourceInspector.read(FOLDER_SOURCE);
        ClassNode pack = ClassResourceInspector.read(PACK);
        boolean folderSource = source != null && hasMethod(source, "<init>", MODERN_CTOR)
                && !hasMethod(source, "<init>", OLD_CTOR);
        boolean readMeta = pack != null && hasMethod(pack, READ_META_AND_CREATE, NEW_READ_META)
                && !hasMethod(pack, READ_META_AND_CREATE, OLD_READ_META);
        ClassNode resourcesBase = ClassResourceInspector.read(PACK_RESOURCES_BASE);
        boolean resourcesCtor = resourcesBase != null
                && hasMethod(resourcesBase, "<init>", MODERN_RESOURCES_CTOR)
                && !hasMethod(resourcesBase, "<init>", OLD_RESOURCES_CTOR);
        ClassNode supplier = ClassResourceInspector.read(RESOURCES_SUPPLIER);
        boolean supplierLambdas = supplier != null && hasMethod(supplier, OPEN_PRIMARY, OPEN_PRIMARY_DESC)
                && !hasMethod(supplier, OLD_SUPPLIER_NAME, OLD_SUPPLIER_DESC);
        if (!folderSource && !readMeta && !supplierLambdas && !resourcesCtor) {
            LOGGER.debug("The host still supports the old pack constructors");
            return;
        }
        registerRedirects(transformer, folderSource, readMeta);
        if (supplierLambdas) registerSupplierAdapter(transformer);
        if (resourcesCtor) registerPackResourcesBase(transformer);
        LOGGER.debug("Added support for old FolderRepositorySource, Pack or ResourcesSupplier calls");
    }

    /** Rebases {@code AbstractPackResources} subclasses so their old {@code super(name, builtin)} links. */
    public static void registerPackResourcesBase(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(LEGACY_PACK_RESOURCES, generatePackResourcesBase());
        transformer.registerSuperclassRebase(PACK_RESOURCES_BASE, LEGACY_PACK_RESOURCES);
    }

    /** Rebuilds {@code ResourcesSupplier} lambdas written against the old {@code open(String)}. */
    public static void registerSupplierAdapter(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        transformer.registerSyntheticClass(SUPPLIER_ADAPTER, generateSupplierAdapter());
        transformer.registerLambdaAdapter(RESOURCES_SUPPLIER, OLD_SUPPLIER_NAME, OLD_SUPPLIER_DESC,
                new RetromodTransformer.LambdaAdapter("java/util/function/Function", "apply",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        FACTORY, WRAP_SUPPLIER, WRAP_SUPPLIER_DESC));
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer, boolean folderSource,
            boolean readMeta) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        if (folderSource) {
            transformer.registerConstructorRedirect(FOLDER_SOURCE, OLD_CTOR,
                    FACTORY, "create", "(" + OLD_PARAMS + ")L" + FOLDER_SOURCE + ";");
        }
        if (readMeta) {
            transformer.registerMethodRedirect(PACK, READ_META_AND_CREATE, OLD_READ_META,
                    FACTORY, READ_META_AND_CREATE, OLD_READ_META);
        }
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FACTORY, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(" + OLD_PARAMS + ")L" + FOLDER_SOURCE + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, FOLDER_SOURCE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        // new DirectoryValidator(FileSystems.getDefault().getPathMatcher("glob:**"))
        mv.visitTypeInsn(Opcodes.NEW, DIRECTORY_VALIDATOR);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/file/FileSystems", "getDefault",
                "()Ljava/nio/file/FileSystem;", false);
        mv.visitLdcInsn("glob:**");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/file/FileSystem", "getPathMatcher",
                "(Ljava/lang/String;)Ljava/nio/file/PathMatcher;", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DIRECTORY_VALIDATOR, "<init>",
                "(Ljava/nio/file/PathMatcher;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FOLDER_SOURCE, "<init>", MODERN_CTOR, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // readMetaAndCreate(new PackLocationInfo(id, title, source, Optional.empty()), resources,
        //         type, new PackSelectionConfig(required, position, false))
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                READ_META_AND_CREATE, OLD_READ_META, null, null);
        read.visitCode();
        read.visitTypeInsn(Opcodes.NEW, PACK_LOCATION_INFO);
        read.visitInsn(Opcodes.DUP);
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitVarInsn(Opcodes.ALOAD, 1);
        read.visitVarInsn(Opcodes.ALOAD, 6);
        read.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                "()Ljava/util/Optional;", false);
        read.visitMethodInsn(Opcodes.INVOKESPECIAL, PACK_LOCATION_INFO, "<init>",
                "(Ljava/lang/String;Lnet/minecraft/class_2561;L" + PACK_SOURCE + ";Ljava/util/Optional;)V",
                false);
        read.visitVarInsn(Opcodes.ALOAD, 3);
        read.visitVarInsn(Opcodes.ALOAD, 4);
        read.visitTypeInsn(Opcodes.NEW, PACK_SELECTION_CONFIG);
        read.visitInsn(Opcodes.DUP);
        read.visitVarInsn(Opcodes.ILOAD, 2);
        read.visitVarInsn(Opcodes.ALOAD, 5);
        read.visitInsn(Opcodes.ICONST_0);
        read.visitMethodInsn(Opcodes.INVOKESPECIAL, PACK_SELECTION_CONFIG, "<init>",
                "(ZLnet/minecraft/class_3288$class_3289;Z)V", false);
        read.visitMethodInsn(Opcodes.INVOKESTATIC, PACK, READ_META_AND_CREATE, NEW_READ_META, false);
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        MethodVisitor wrap = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                WRAP_SUPPLIER, WRAP_SUPPLIER_DESC, null, null);
        wrap.visitCode();
        wrap.visitTypeInsn(Opcodes.NEW, SUPPLIER_ADAPTER);
        wrap.visitInsn(Opcodes.DUP);
        wrap.visitVarInsn(Opcodes.ALOAD, 0);
        wrap.visitMethodInsn(Opcodes.INVOKESPECIAL, SUPPLIER_ADAPTER, "<init>",
                "(Ljava/util/function/Function;)V", false);
        wrap.visitInsn(Opcodes.ARETURN);
        wrap.visitMaxs(0, 0);
        wrap.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * An abstract {@code AbstractPackResources} with the old {@code (String, boolean)} constructor
     * and a pass-through for the current one, so a subclass written for either shape links.
     */
    static byte[] generatePackResourcesBase() {
        // The built-in flag is a branch, so this class needs stack map frames. Both arms push the
        // same type, so frame computation never has to look up a common superclass.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                LEGACY_PACK_RESOURCES, null, PACK_RESOURCES_BASE, null);

        MethodVisitor legacy = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", OLD_RESOURCES_CTOR, null, null);
        legacy.visitCode();
        legacy.visitVarInsn(Opcodes.ALOAD, 0);
        // new PackLocationInfo(name, Component.literal(name), builtin ? BUILT_IN : DEFAULT, Optional.empty())
        legacy.visitTypeInsn(Opcodes.NEW, PACK_LOCATION_INFO);
        legacy.visitInsn(Opcodes.DUP);
        legacy.visitVarInsn(Opcodes.ALOAD, 1);
        legacy.visitVarInsn(Opcodes.ALOAD, 1);
        legacy.visitMethodInsn(Opcodes.INVOKESTATIC, COMPONENT, COMPONENT_LITERAL,
                "(Ljava/lang/String;)Lnet/minecraft/class_5250;", true);
        org.objectweb.asm.Label defaultSource = new org.objectweb.asm.Label();
        org.objectweb.asm.Label sourceChosen = new org.objectweb.asm.Label();
        legacy.visitVarInsn(Opcodes.ILOAD, 2);
        legacy.visitJumpInsn(Opcodes.IFEQ, defaultSource);
        legacy.visitFieldInsn(Opcodes.GETSTATIC, PACK_SOURCE, PACK_SOURCE_BUILT_IN, "L" + PACK_SOURCE + ";");
        legacy.visitJumpInsn(Opcodes.GOTO, sourceChosen);
        legacy.visitLabel(defaultSource);
        legacy.visitFieldInsn(Opcodes.GETSTATIC, PACK_SOURCE, PACK_SOURCE_DEFAULT, "L" + PACK_SOURCE + ";");
        legacy.visitLabel(sourceChosen);
        legacy.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                "()Ljava/util/Optional;", false);
        legacy.visitMethodInsn(Opcodes.INVOKESPECIAL, PACK_LOCATION_INFO, "<init>",
                "(Ljava/lang/String;L" + COMPONENT + ";L" + PACK_SOURCE + ";Ljava/util/Optional;)V", false);
        legacy.visitMethodInsn(Opcodes.INVOKESPECIAL, PACK_RESOURCES_BASE, "<init>", MODERN_RESOURCES_CTOR, false);
        legacy.visitInsn(Opcodes.RETURN);
        legacy.visitMaxs(0, 0);
        legacy.visitEnd();

        MethodVisitor modern = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", MODERN_RESOURCES_CTOR, null, null);
        modern.visitCode();
        modern.visitVarInsn(Opcodes.ALOAD, 0);
        modern.visitVarInsn(Opcodes.ALOAD, 1);
        modern.visitMethodInsn(Opcodes.INVOKESPECIAL, PACK_RESOURCES_BASE, "<init>", MODERN_RESOURCES_CTOR, false);
        modern.visitInsn(Opcodes.RETURN);
        modern.visitMaxs(0, 0);
        modern.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A {@code ResourcesSupplier} that opens every pack by id through the mod's old lambda. */
    static byte[] generateSupplierAdapter() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                SUPPLIER_ADAPTER, null, "java/lang/Object", new String[] {RESOURCES_SUPPLIER});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "open",
                "Ljava/util/function/Function;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/util/function/Function;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, SUPPLIER_ADAPTER, "open", "Ljava/util/function/Function;");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        openById(cw, OPEN_PRIMARY, OPEN_PRIMARY_DESC);
        openById(cw, OPEN_FULL, OPEN_FULL_DESC);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void openById(ClassWriter cw, String name, String descriptor) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, SUPPLIER_ADAPTER, "open", "Ljava/util/function/Function;");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PACK_LOCATION_INFO, PACK_ID, "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PACK_RESOURCES);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
