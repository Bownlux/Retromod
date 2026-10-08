/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps 1.20.2 to 1.21.4 world saved data working on 26.1 and newer.
 *
 * <p>A mod of that era stores world-wide state with
 * {@code storage.computeIfAbsent(new SavedData.Factory(constructor, loader, fixType), name)},
 * where {@code loader} reads a {@code CompoundTag} and the class writes itself back through
 * {@code save(CompoundTag, HolderLookup.Provider)}. 26.1 removed {@code SavedData.Factory} and
 * the name-based lookup: data is now described by a {@code SavedDataType(id, constructor, codec,
 * fixType)} and the storage class is {@code SavedDataStorage} (verified against the 26.1.2 and
 * 26.2 jars). The mod's first lookup, often the first time a player opens a backpack (#249), then
 * fails with {@code NoClassDefFoundError}.
 *
 * <p>{@code SavedData.Factory} becomes a stand-in with the same constructor. The two lookups build a
 * {@code SavedDataType} named after the old string, whose codec wraps the mod's own loader and
 * {@code save} method with the storage's registry provider. The type is cached per storage and
 * name, because the storage caches loaded data by type and the mod builds a new factory on every
 * call. The file keeps the old name in the default namespace.
 *
 * <p>Registration probes the host, so the bridge only applies where {@code SavedDataStorage}
 * exists and {@code SavedData.Factory} does not.
 */
public class LegacySavedDataFactoryShim implements MinecraftVersionedApiShim {

    static final String OLD_FACTORY = "net/minecraft/world/level/saveddata/SavedData$Factory";
    static final String OLD_STORAGE = "net/minecraft/world/level/storage/DimensionDataStorage";
    static final String STORAGE = "net/minecraft/world/level/storage/SavedDataStorage";
    public static final String FACTORY = "com/retromod/shim/common/embedded/LegacySavedDataFactory";
    public static final String LOAD = FACTORY + "$Load";
    public static final String SAVE = FACTORY + "$Save";

    private static final String SAVED_DATA = "net/minecraft/world/level/saveddata/SavedData";
    private static final String SAVED_DATA_TYPE = "net/minecraft/world/level/saveddata/SavedDataType";
    private static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String FIX_TYPES = "net/minecraft/util/datafix/DataFixTypes";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String SUPPLIER = "java/util/function/Supplier";
    private static final String BI_FUNCTION = "java/util/function/BiFunction";
    private static final String FUNCTION = "java/util/function/Function";
    private static final String MAP = "java/util/Map";

    private static final String L_FACTORY = "L" + FACTORY + ";";
    private static final String L_SAVED_DATA = "L" + SAVED_DATA + ";";
    private static final String L_TYPE = "L" + SAVED_DATA_TYPE + ";";
    private static final String CTOR_DESC =
            "(L" + SUPPLIER + ";L" + BI_FUNCTION + ";L" + FIX_TYPES + ";)V";
    private static final String LOOKUP_DESC =
            "(L" + STORAGE + ";" + L_FACTORY + "Ljava/lang/String;)" + L_SAVED_DATA;

    @Override
    public String getShimName() {
        return "Legacy Saved Data Factories";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.11";
    }

    @Override
    public String getTargetVersion() {
        return "26.1";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        boolean hostUsesSavedDataTypes = ClassResourceInspector.read(STORAGE) != null
                && ClassResourceInspector.read(OLD_FACTORY) == null;
        if (hostUsesSavedDataTypes) register(transformer);
    }

    /** Registers the stand-in and the lookups without probing the host. */
    static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        transformer.registerSyntheticClass(LOAD, generateLoad());
        transformer.registerSyntheticClass(SAVE, generateSave());
        transformer.registerClassRedirect(OLD_FACTORY, FACTORY);
        // Both storage names and both factory names, so the call matches whichever of the class
        // moves the remapper applied before the method redirect is consulted.
        for (String owner : new String[]{OLD_STORAGE, STORAGE}) {
            for (String factory : new String[]{OLD_FACTORY, FACTORY}) {
                String desc = "(L" + factory + ";Ljava/lang/String;)" + L_SAVED_DATA;
                transformer.registerMethodRedirect(owner, "computeIfAbsent", desc,
                        FACTORY, "computeIfAbsent", LOOKUP_DESC);
                transformer.registerMethodRedirect(owner, "get", desc, FACTORY, "get", LOOKUP_DESC);
            }
        }
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }

    /** The {@code SavedData.Factory} stand-in plus the two storage lookups. */
    public static byte[] generateFactory() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FACTORY, null, "java/lang/Object", null);
        cw.visitField(ACC_FINAL, "constructor", "L" + SUPPLIER + ";", null, null).visitEnd();
        cw.visitField(ACC_FINAL, "loader", "L" + BI_FUNCTION + ";", null, null).visitEnd();
        cw.visitField(ACC_FINAL, "fixType", "L" + FIX_TYPES + ";", null, null).visitEnd();
        // Storage -> (name -> SavedDataType). Weak keys so a closed world's storage is released.
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "TYPES", "L" + MAP + ";", null, null)
                .visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/WeakHashMap");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/WeakHashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, FACTORY, "TYPES", "L" + MAP + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        storeField(init, 1, "constructor", "L" + SUPPLIER + ";");
        storeField(init, 2, "loader", "L" + BI_FUNCTION + ";");
        storeField(init, 3, "fixType", "L" + FIX_TYPES + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitLookup(cw, "computeIfAbsent");
        emitLookup(cw, "get");
        emitTypeFor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void storeField(MethodVisitor m, int slot, String name, String desc) {
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, slot);
        m.visitFieldInsn(PUTFIELD, FACTORY, name, desc);
    }

    /** {@code static SavedData name(SavedDataStorage s, Factory f, String id)}. */
    private static void emitLookup(ClassWriter cw, String name) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, LOOKUP_DESC, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKESTATIC, FACTORY, "typeFor",
                "(L" + STORAGE + ";" + L_FACTORY + "Ljava/lang/String;)" + L_TYPE, false);
        m.visitMethodInsn(INVOKEVIRTUAL, STORAGE, name, "(" + L_TYPE + ")" + L_SAVED_DATA, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Builds, or returns the cached, {@code SavedDataType} for one storage and old name. */
    private static void emitTypeFor(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED, "typeFor",
                "(L" + STORAGE + ";" + L_FACTORY + "Ljava/lang/String;)" + L_TYPE, null,
                new String[]{"java/lang/ReflectiveOperationException"});
        m.visitCode();
        // Map perStorage = TYPES.get(storage), created on first use
        Label havePerStorage = new Label();
        m.visitFieldInsn(GETSTATIC, FACTORY, "TYPES", "L" + MAP + ";");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEINTERFACE, MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitTypeInsn(CHECKCAST, MAP);
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 3);
        m.visitJumpInsn(IFNONNULL, havePerStorage);
        m.visitTypeInsn(NEW, "java/util/HashMap");
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 3);
        m.visitFieldInsn(GETSTATIC, FACTORY, "TYPES", "L" + MAP + ";");
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEINTERFACE, MAP, "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitInsn(POP);
        m.visitLabel(havePerStorage);

        // cached = perStorage.get(name)
        Label build = new Label();
        m.visitVarInsn(ALOAD, 3);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEINTERFACE, MAP, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitVarInsn(ASTORE, 4);
        m.visitVarInsn(ALOAD, 4);
        m.visitJumpInsn(IFNULL, build);
        m.visitVarInsn(ALOAD, 4);
        m.visitTypeInsn(CHECKCAST, SAVED_DATA_TYPE);
        m.visitInsn(ARETURN);
        m.visitLabel(build);

        // The storage keeps its registry provider private; the mod's loader and save need it.
        m.visitLdcInsn(Type.getObjectType(STORAGE));
        m.visitLdcInsn("registries");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitVarInsn(ASTORE, 5);

        // codec = CompoundTag.CODEC.xmap(new Load(f.loader, provider), new Save(provider))
        m.visitFieldInsn(GETSTATIC, COMPOUND_TAG, "CODEC", "L" + CODEC + ";");
        m.visitTypeInsn(NEW, LOAD);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(GETFIELD, FACTORY, "loader", "L" + BI_FUNCTION + ";");
        m.visitVarInsn(ALOAD, 5);
        m.visitMethodInsn(INVOKESPECIAL, LOAD, "<init>",
                "(L" + BI_FUNCTION + ";Ljava/lang/Object;)V", false);
        m.visitTypeInsn(NEW, SAVE);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 5);
        m.visitMethodInsn(INVOKESPECIAL, SAVE, "<init>", "(Ljava/lang/Object;)V", false);
        m.visitMethodInsn(INVOKEINTERFACE, CODEC, "xmap",
                "(L" + FUNCTION + ";L" + FUNCTION + ";)L" + CODEC + ";", true);
        m.visitVarInsn(ASTORE, 6);

        // type = new SavedDataType(Identifier.withDefaultNamespace(name), f.constructor, codec,
        //         f.fixType); an Identifier path has no upper case, which old names allowed.
        m.visitTypeInsn(NEW, SAVED_DATA_TYPE);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 2);
        m.visitFieldInsn(GETSTATIC, "java/util/Locale", "ROOT", "Ljava/util/Locale;");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "toLowerCase",
                "(Ljava/util/Locale;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKESTATIC, IDENTIFIER, "withDefaultNamespace",
                "(Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(GETFIELD, FACTORY, "constructor", "L" + SUPPLIER + ";");
        m.visitVarInsn(ALOAD, 6);
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(GETFIELD, FACTORY, "fixType", "L" + FIX_TYPES + ";");
        m.visitMethodInsn(INVOKESPECIAL, SAVED_DATA_TYPE, "<init>",
                "(L" + IDENTIFIER + ";L" + SUPPLIER + ";L" + CODEC + ";L" + FIX_TYPES + ";)V",
                false);
        m.visitVarInsn(ASTORE, 4);
        m.visitVarInsn(ALOAD, 3);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKEINTERFACE, MAP, "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 4);
        m.visitTypeInsn(CHECKCAST, SAVED_DATA_TYPE);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code Load.apply(tag)}: the mod's {@code loader.apply(tag, provider)}. */
    public static byte[] generateLoad() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, LOAD, null, "java/lang/Object",
                new String[]{FUNCTION});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "loader", "L" + BI_FUNCTION + ";", null, null)
                .visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "provider", "Ljava/lang/Object;", null, null)
                .visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + BI_FUNCTION + ";Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, LOAD, "loader", "L" + BI_FUNCTION + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, LOAD, "provider", "Ljava/lang/Object;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        apply.visitVarInsn(ALOAD, 0);
        apply.visitFieldInsn(GETFIELD, LOAD, "loader", "L" + BI_FUNCTION + ";");
        apply.visitVarInsn(ALOAD, 1);
        apply.visitVarInsn(ALOAD, 0);
        apply.visitFieldInsn(GETFIELD, LOAD, "provider", "Ljava/lang/Object;");
        apply.visitMethodInsn(INVOKEINTERFACE, BI_FUNCTION, "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        apply.visitInsn(ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code Save.apply(data)}: the mod's {@code data.save(new CompoundTag(), provider)}. The
     * method is no longer declared by {@code SavedData}, so it is resolved on the mod's class. A
     * method handle resolves only that method: {@code Class.getMethod} would resolve every public
     * signature of the class, and a client-only one fails on a dedicated server.
     */
    public static byte[] generateSave() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SAVE, null, "java/lang/Object",
                new String[]{FUNCTION});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "provider", "Ljava/lang/Object;", null, null)
                .visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null,
                null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, SAVE, "provider", "Ljava/lang/Object;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        String lookup = "java/lang/invoke/MethodHandles$Lookup";
        String methodType = "java/lang/invoke/MethodType";
        String methodHandle = "java/lang/invoke/MethodHandle";
        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
        apply.visitCode();
        // MethodHandles.lookup().findVirtual(data.getClass(), "save",
        //         MethodType.methodType(CompoundTag.class, CompoundTag.class, Provider.class))
        apply.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()L" + lookup + ";", false);
        apply.visitVarInsn(ALOAD, 1);
        apply.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass",
                "()Ljava/lang/Class;", false);
        apply.visitLdcInsn("save");
        apply.visitLdcInsn(Type.getObjectType(COMPOUND_TAG));
        apply.visitLdcInsn(Type.getObjectType(COMPOUND_TAG));
        apply.visitInsn(ICONST_1);
        apply.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        apply.visitInsn(DUP);
        apply.visitInsn(ICONST_0);
        apply.visitLdcInsn(Type.getObjectType(PROVIDER));
        apply.visitInsn(AASTORE);
        apply.visitMethodInsn(INVOKESTATIC, methodType, "methodType",
                "(Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)L" + methodType + ";",
                false);
        apply.visitMethodInsn(INVOKEVIRTUAL, lookup, "findVirtual",
                "(Ljava/lang/Class;Ljava/lang/String;L" + methodType + ";)L" + methodHandle + ";",
                false);
        // return handle.invokeWithArguments(data, new CompoundTag(), provider)
        apply.visitInsn(ICONST_3);
        apply.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        apply.visitInsn(DUP);
        apply.visitInsn(ICONST_0);
        apply.visitVarInsn(ALOAD, 1);
        apply.visitInsn(AASTORE);
        apply.visitInsn(DUP);
        apply.visitInsn(ICONST_1);
        apply.visitTypeInsn(NEW, COMPOUND_TAG);
        apply.visitInsn(DUP);
        apply.visitMethodInsn(INVOKESPECIAL, COMPOUND_TAG, "<init>", "()V", false);
        apply.visitInsn(AASTORE);
        apply.visitInsn(DUP);
        apply.visitInsn(ICONST_2);
        apply.visitVarInsn(ALOAD, 0);
        apply.visitFieldInsn(GETFIELD, SAVE, "provider", "Ljava/lang/Object;");
        apply.visitInsn(AASTORE);
        apply.visitMethodInsn(INVOKEVIRTUAL, methodHandle, "invokeWithArguments",
                "([Ljava/lang/Object;)Ljava/lang/Object;", false);
        apply.visitInsn(ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
