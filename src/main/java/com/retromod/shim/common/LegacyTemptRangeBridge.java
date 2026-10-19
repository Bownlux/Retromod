/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Gives an old mod's mob attributes the tempt range that a 1.21.2 {@code TemptGoal} reads. */
public final class LegacyTemptRangeBridge {

    static final String HELPER = "com/retromod/generated/LegacyTemptRange";
    static final String FABRIC_HELPER = "com/retromod/generated/LegacyTemptRangeFabric";
    static final String ARCHITECTURY_HELPER = "com/retromod/generated/LegacyTemptRangeArchitectury";

    /** The first version whose {@code TemptGoal} reads the attribute, and throws when a mob lacks it. */
    static final String TEMPT_RANGE_VERSION = "1.21.2";

    static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    static final String SUPPLIER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier";
    static final String BUILDER = SUPPLIER + "$Builder";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";

    static final String FABRIC_REGISTRY =
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricDefaultAttributeRegistry";
    static final String FABRIC_LIVING =
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityType$Builder$Living";
    static final String FABRIC_MOB =
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityType$Builder$Mob";
    static final String ARCHITECTURY_REGISTRY = "dev/architectury/registry/level/entity/EntityAttributeRegistry";

    static final String L_BUILDER = "L" + BUILDER + ";";
    static final String L_HOLDER = "L" + HOLDER + ";";
    static final String L_SUPPLIER_FN = "Ljava/util/function/Supplier;";
    static final String BUILD = "()L" + SUPPLIER + ";";
    static final String BUILD_HELPER = "(" + L_BUILDER + ")L" + SUPPLIER + ";";
    static final String WITH_RANGE = "(" + L_BUILDER + ")" + L_BUILDER;
    static final String HAS_ATTRIBUTE = "(" + L_HOLDER + ")Z";
    static final String ADD = "(" + L_HOLDER + ")" + L_BUILDER;
    static final String FABRIC_REGISTER = "(L" + ENTITY_TYPE + ";" + L_BUILDER + ")V";
    static final String LIVING_DEFAULTS = "(" + L_SUPPLIER_FN + ")L" + FABRIC_LIVING + ";";
    static final String MOB_DEFAULTS = "(" + L_SUPPLIER_FN + ")L" + FABRIC_MOB + ";";
    /** {@code register(Supplier<EntityType>, Supplier<AttributeSupplier.Builder>)}, 1.19.2 to 26.3. */
    static final String ARCHITECTURY_REGISTER = "(" + L_SUPPLIER_FN + L_SUPPLIER_FN + ")V";

    private LegacyTemptRangeBridge() {}

    /** Registers the bridge when the host's {@code TemptGoal} reads the attribute. */
    public static void register(RetromodTransformer transformer) {
        if (hostTemptGoalReadsAttribute()) registerRedirects(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        // Each library's calls get a class of their own, embedded only into a mod that calls it.
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerSyntheticClass(FABRIC_HELPER, generateFabricHelper());
        transformer.registerSyntheticClass(ARCHITECTURY_HELPER, generateArchitecturyHelper());
        // Only Animal.createAnimalAttributes() adds the attribute, but every mob's builder ends in
        // build(), so that is the hook. A super call in a builder subclass still reaches the host's.
        transformer.registerWrappingMethodRedirect(BUILDER, "build", BUILD, HELPER, "build", BUILD_HELPER);
        // These methods still exist, and newer mods call them too, so only mods before 1.21.2 change.
        transformer.limitRedirectToModsBefore(BUILDER, "build", BUILD, TEMPT_RANGE_VERSION);

        // Fabric API builds the builder for the mod in FabricDefaultAttributeRegistry and in
        // defaultAttributes(Supplier) on its entity type builder, so those check it first.
        transformer.registerMethodRedirect(FABRIC_REGISTRY, "register", FABRIC_REGISTER,
                FABRIC_HELPER, "register", FABRIC_REGISTER);
        transformer.limitRedirectToModsBefore(FABRIC_REGISTRY, "register", FABRIC_REGISTER,
                TEMPT_RANGE_VERSION);
        registerDefaultAttributes(transformer, FABRIC_LIVING, LIVING_DEFAULTS);
        registerDefaultAttributes(transformer, FABRIC_MOB, MOB_DEFAULTS);

        // Architectury builds it in EntityAttributeRegistry.register(Supplier, Supplier) on every loader.
        transformer.registerMethodRedirect(ARCHITECTURY_REGISTRY, "register", ARCHITECTURY_REGISTER,
                ARCHITECTURY_HELPER, "register", ARCHITECTURY_REGISTER);
        transformer.limitRedirectToModsBefore(ARCHITECTURY_REGISTRY, "register", ARCHITECTURY_REGISTER,
                TEMPT_RANGE_VERSION);
    }

    /** Redirects a Fabric entity type builder's {@code defaultAttributes(Supplier)} for old mods. */
    private static void registerDefaultAttributes(RetromodTransformer transformer, String owner, String desc) {
        transformer.registerMethodRedirect(owner, "defaultAttributes", desc,
                FABRIC_HELPER, "defaultAttributes", receiverFirst(owner, desc), true);
        transformer.limitRedirectToModsBefore(owner, "defaultAttributes", desc, TEMPT_RANGE_VERSION);
    }

    /** The static helper's descriptor for an instance method: the receiver, then the parameters. */
    private static String receiverFirst(String owner, String desc) {
        return "(L" + owner + ";" + desc.substring(1);
    }

    /** Whether the host has the attribute and every supplier and builder call the helper makes. */
    static boolean hostTemptGoalReadsAttribute() {
        ClassNode attributes = ClassResourceInspector.read(ATTRIBUTES);
        ClassNode supplier = ClassResourceInspector.read(SUPPLIER);
        ClassNode builder = ClassResourceInspector.read(BUILDER);
        // Offline the host classes are not readable, so the host version decides.
        if (attributes == null || supplier == null || builder == null) {
            return RetromodVersion.compareMcVersions(
                    RetromodVersion.TARGET_MC_VERSION, TEMPT_RANGE_VERSION) >= 0;
        }
        return hasHolderField(attributes, "TEMPT_RANGE")
                && hasMethod(supplier, "hasAttribute", HAS_ATTRIBUTE)
                && hasMethod(builder, "add", ADD)
                && hasMethod(builder, "build", BUILD);
    }

    private static boolean hasHolderField(ClassNode owner, String name) {
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name) && field.desc.equals(L_HOLDER)) return true;
        }
        return false;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** Writes the shared helper with {@code withTemptRange(builder)} and {@code build(builder)}. */
    static byte[] generateHelper() {
        ClassWriter cw = writer();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);

        // withTemptRange: vanilla's builder cannot say what it holds, so a probe supplier answers.
        // A builder may be built more than once, and adding an attribute after a build is allowed.
        MethodVisitor with = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withTemptRange",
                WITH_RANGE, null, null);
        with.visitCode();
        Label done = new Label();
        with.visitVarInsn(Opcodes.ALOAD, 0);
        with.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD, false);
        with.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "TEMPT_RANGE", L_HOLDER);
        with.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SUPPLIER, "hasAttribute", HAS_ATTRIBUTE, false);
        with.visitJumpInsn(Opcodes.IFNE, done);
        // 1.21.2 replaced TemptGoal's fixed range of 10 with this attribute, whose default is 10 on
        // every host up to 26.3. A mob that sets it keeps its value; one without the goal never reads it.
        with.visitVarInsn(Opcodes.ALOAD, 0);
        with.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "TEMPT_RANGE", L_HOLDER);
        with.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "add", ADD, false);
        with.visitInsn(Opcodes.POP);
        with.visitLabel(done);
        with.visitVarInsn(Opcodes.ALOAD, 0);
        with.visitInsn(Opcodes.ARETURN);
        with.visitMaxs(0, 0);
        with.visitEnd();

        // build: add the tempt range, then build as the mod asked.
        MethodVisitor build = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                BUILD_HELPER, null, null);
        build.visitCode();
        build.visitVarInsn(Opcodes.ALOAD, 0);
        build.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "withTemptRange", WITH_RANGE, false);
        build.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD, false);
        build.visitInsn(Opcodes.ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes the Fabric API helper, which is also the {@code Supplier} that checks a mod's builder. */
    static byte[] generateFabricHelper() {
        ClassWriter cw = writer();
        beginBuilderSupplier(cw, FABRIC_HELPER);

        // register(type, builder) adds the tempt range before it hands the builder on.
        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                FABRIC_REGISTER, null, null);
        register.visitCode();
        register.visitVarInsn(Opcodes.ALOAD, 0);
        register.visitVarInsn(Opcodes.ALOAD, 1);
        register.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "withTemptRange", WITH_RANGE, false);
        register.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_REGISTRY, "register", FABRIC_REGISTER, false);
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();

        emitDefaultAttributes(cw, FABRIC_LIVING, LIVING_DEFAULTS);
        emitDefaultAttributes(cw, FABRIC_MOB, MOB_DEFAULTS);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes the Architectury helper, which is also the {@code Supplier} that checks a mod's builder. */
    static byte[] generateArchitecturyHelper() {
        ClassWriter cw = writer();
        beginBuilderSupplier(cw, ARCHITECTURY_HELPER);

        // Architectury builds the builder later, inside the library, so register wraps the mod's
        // supplier: EntityAttributeRegistry.register(type, new LegacyTemptRangeArchitectury(attributes)).
        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                ARCHITECTURY_REGISTER, null, null);
        register.visitCode();
        register.visitVarInsn(Opcodes.ALOAD, 0);
        register.visitTypeInsn(Opcodes.NEW, ARCHITECTURY_HELPER);
        register.visitInsn(Opcodes.DUP);
        register.visitVarInsn(Opcodes.ALOAD, 1);
        register.visitMethodInsn(Opcodes.INVOKESPECIAL, ARCHITECTURY_HELPER, "<init>",
                "(" + L_SUPPLIER_FN + ")V", false);
        register.visitMethodInsn(Opcodes.INVOKESTATIC, ARCHITECTURY_REGISTRY, "register",
                ARCHITECTURY_REGISTER, false);
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Starts {@code owner} as a {@code Supplier} that adds the tempt range to another supplier's builder. */
    private static void beginBuilderSupplier(ClassWriter cw, String owner) {
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object", new String[] {"java/util/function/Supplier"});
        // The field holds the mod's own supplier, and the constructor stores it.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "attributes", L_SUPPLIER_FN, null, null)
                .visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + L_SUPPLIER_FN + ")V",
                null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, owner, "attributes", L_SUPPLIER_FN);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // get() asks the mod's supplier for its builder and adds the tempt range.
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, owner, "attributes", L_SUPPLIER_FN);
        get.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get",
                "()Ljava/lang/Object;", true);
        get.visitTypeInsn(Opcodes.CHECKCAST, BUILDER);
        get.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "withTemptRange", WITH_RANGE, false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
    }

    /** Writes {@code builder.defaultAttributes(new LegacyTemptRangeFabric(supplier))}. */
    private static void emitDefaultAttributes(ClassWriter cw, String owner, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "defaultAttributes",
                receiverFirst(owner, desc), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, FABRIC_HELPER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FABRIC_HELPER, "<init>", "(" + L_SUPPLIER_FN + ")V", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "defaultAttributes", desc, true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A class writer that does not need Minecraft's classes to compute frames. */
    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when these classes are generated.
                return "java/lang/Object";
            }
        };
    }
}
