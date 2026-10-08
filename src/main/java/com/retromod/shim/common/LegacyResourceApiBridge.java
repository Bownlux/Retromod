/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps a pre-1.19 mod's resource loading working against the 1.19 resource manager.
 *
 * <p>1.19 turned {@code Resource} from a closeable interface into a class that opens a new
 * stream on each {@code open()}, and made {@code ReloadableResourceManager} a class too. A lookup
 * now returns an {@code Optional}, and {@code listResources} matches the whole location and
 * returns a map. Model and animation libraries such as GeckoLib 3 load every file through these
 * calls, so without this nothing they draw has a model. The old missing-resource
 * {@code IOException} is kept by going through {@code getResourceOrThrow}, and the old filter is
 * still tested against the file name, as the 1.18 pack readers did.
 */
public final class LegacyResourceApiBridge {

    static final String SYNTH = "com/retromod/generated/LegacyResourceApi";
    static final String FILTER = "com/retromod/generated/LegacyResourceNameFilter";

    private static final String PKG = "net/minecraft/server/packs/resources/";
    private static final String RESOURCE = PKG + "Resource";
    private static final String MANAGER = PKG + "ResourceManager";
    private static final String PROVIDER = PKG + "ResourceProvider";
    private static final String RELOADABLE = PKG + "ReloadableResourceManager";
    private static final String METADATA = PKG + "ResourceMetadata";
    private static final String SERIALIZER = "net/minecraft/server/packs/metadata/MetadataSectionSerializer";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String PREDICATE = "java/util/function/Predicate";

    private LegacyResourceApiBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generateHelpers());
        transformer.registerSyntheticClass(FILTER, generateFilter());

        // Both were interfaces before 1.19. Their unchanged calls need a class method reference.
        transformer.registerClassOwner(RESOURCE);
        transformer.registerClassOwner(RELOADABLE);

        String resource = "L" + RESOURCE + ";";
        for (String owner : new String[]{MANAGER, PROVIDER}) {
            transformer.registerMethodRedirect(owner, "getResource", "(" + LOCATION + ")" + resource,
                    owner, "getResourceOrThrow", "(" + LOCATION + ")" + resource);
        }
        transformer.registerMethodRedirect(MANAGER, "getResources", "(" + LOCATION + ")Ljava/util/List;",
                MANAGER, "getResourceStack", "(" + LOCATION + ")Ljava/util/List;");
        transformer.registerMethodRedirect(MANAGER, "hasResource", "(" + LOCATION + ")Z",
                SYNTH, "hasResource", "(L" + MANAGER + ";" + LOCATION + ")Z", true);
        transformer.registerMethodRedirect(MANAGER, "listResources",
                "(Ljava/lang/String;L" + PREDICATE + ";)Ljava/util/Collection;",
                SYNTH, "listResources",
                "(L" + MANAGER + ";Ljava/lang/String;L" + PREDICATE + ";)Ljava/util/Collection;", true);

        transformer.registerMethodRedirect(RESOURCE, "getInputStream", "()Ljava/io/InputStream;",
                RESOURCE, "open", "()Ljava/io/InputStream;");
        transformer.registerMethodRedirect(RESOURCE, "getSourceName", "()Ljava/lang/String;",
                RESOURCE, "sourcePackId", "()Ljava/lang/String;");
        transformer.registerMethodRedirect(RESOURCE, "getMetadata",
                "(L" + SERIALIZER + ";)Ljava/lang/Object;",
                SYNTH, "getMetadata", "(" + resource + "L" + SERIALIZER + ";)Ljava/lang/Object;", true);
        // A resource no longer holds a stream, so there is nothing left to close.
        transformer.registerMethodRedirect(RESOURCE, "close", "()V",
                SYNTH, "close", "(" + resource + ")V", true);
    }

    static byte[] generateHelpers() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        String resource = "L" + RESOURCE + ";";

        MethodVisitor close = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "close", "(" + resource + ")V",
                null, null);
        close.visitCode();
        close.visitInsn(RETURN);
        close.visitMaxs(0, 0);
        close.visitEnd();

        // The old getMetadata returned null when the section was absent.
        MethodVisitor metadata = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getMetadata",
                "(" + resource + "L" + SERIALIZER + ";)Ljava/lang/Object;", null,
                new String[]{"java/io/IOException"});
        metadata.visitCode();
        metadata.visitVarInsn(ALOAD, 0);
        metadata.visitMethodInsn(INVOKEVIRTUAL, RESOURCE, "metadata", "()L" + METADATA + ";", false);
        metadata.visitVarInsn(ALOAD, 1);
        metadata.visitMethodInsn(INVOKEINTERFACE, METADATA, "getSection",
                "(L" + SERIALIZER + ";)Ljava/util/Optional;", true);
        metadata.visitInsn(ACONST_NULL);
        metadata.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        metadata.visitInsn(ARETURN);
        metadata.visitMaxs(0, 0);
        metadata.visitEnd();

        MethodVisitor has = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hasResource",
                "(L" + MANAGER + ";" + LOCATION + ")Z", null, null);
        has.visitCode();
        has.visitVarInsn(ALOAD, 0);
        has.visitVarInsn(ALOAD, 1);
        has.visitMethodInsn(INVOKEINTERFACE, PROVIDER, "getResource",
                "(" + LOCATION + ")Ljava/util/Optional;", true);
        has.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
        has.visitInsn(IRETURN);
        has.visitMaxs(0, 0);
        has.visitEnd();

        MethodVisitor list = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "listResources",
                "(L" + MANAGER + ";Ljava/lang/String;L" + PREDICATE + ";)Ljava/util/Collection;", null, null);
        list.visitCode();
        list.visitVarInsn(ALOAD, 0);
        list.visitVarInsn(ALOAD, 1);
        list.visitTypeInsn(NEW, FILTER);
        list.visitInsn(DUP);
        list.visitVarInsn(ALOAD, 2);
        list.visitMethodInsn(INVOKESPECIAL, FILTER, "<init>", "(L" + PREDICATE + ";)V", false);
        list.visitMethodInsn(INVOKEINTERFACE, MANAGER, "listResources",
                "(Ljava/lang/String;L" + PREDICATE + ";)Ljava/util/Map;", true);
        list.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "keySet", "()Ljava/util/Set;", true);
        list.visitInsn(ARETURN);
        list.visitMaxs(0, 0);
        list.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A location filter that hands the old name filter only the file name. */
    static byte[] generateFilter() {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FILTER, null, "java/lang/Object",
                new String[]{PREDICATE});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "names", "L" + PREDICATE + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + PREDICATE + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, FILTER, "names", "L" + PREDICATE + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor test = cw.visitMethod(ACC_PUBLIC, "test", "(Ljava/lang/Object;)Z", null, null);
        test.visitCode();
        test.visitVarInsn(ALOAD, 1);
        test.visitTypeInsn(CHECKCAST, LOCATION.substring(1, LOCATION.length() - 1));
        test.visitMethodInsn(INVOKEVIRTUAL, LOCATION.substring(1, LOCATION.length() - 1), "getPath",
                "()Ljava/lang/String;", false);
        test.visitVarInsn(ASTORE, 2);
        test.visitVarInsn(ALOAD, 0);
        test.visitFieldInsn(GETFIELD, FILTER, "names", "L" + PREDICATE + ";");
        test.visitVarInsn(ALOAD, 2);
        test.visitVarInsn(ALOAD, 2);
        test.visitIntInsn(BIPUSH, '/');
        test.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        test.visitInsn(ICONST_1);
        test.visitInsn(IADD);
        test.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring", "(I)Ljava/lang/String;", false);
        test.visitMethodInsn(INVOKEINTERFACE, PREDICATE, "test", "(Ljava/lang/Object;)Z", true);
        test.visitInsn(IRETURN);
        test.visitMaxs(0, 0);
        test.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }
}
