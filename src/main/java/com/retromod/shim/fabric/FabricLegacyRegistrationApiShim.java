/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Two registration APIs that a 1.21.x Fabric mod calls during its entry point and that 26.1
 * reshaped (Sophisticated Core and Backpacks, #249).
 *
 * <p>{@code BlockEntityType.Builder} is gone from 26.1. Fabric API still ships
 * {@code FabricBlockEntityTypeBuilder} with the same {@code create(factory, blocks)} and
 * {@code build(type)} shape, so {@code Builder.of(supplier, blocks)} becomes
 * {@code FabricBlockEntityTypeBuilder.create} through a small adapter from the vanilla supplier to
 * Fabric's factory, and the builder type follows.
 *
 * <p>Fabric API's {@code ResourceCondition.test} now takes a {@code RegistryOps.RegistryInfoLookup}
 * instead of a {@code HolderLookup.Provider}. A mod's condition still implements the old method, so
 * the class would be abstract on 26.1 and fail when its codec builds one. The condition is given a
 * generated subinterface that keeps the old method abstract and implements the new one by calling
 * it with no provider, which the old contract already allowed.
 */
public class FabricLegacyRegistrationApiShim implements MinecraftVersionedApiShim {

    static final String VANILLA_BUILDER = "net/minecraft/world/level/block/entity/BlockEntityType$Builder";
    static final String SUPPLIER =
            "net/minecraft/world/level/block/entity/BlockEntityType$BlockEntitySupplier";
    static final String FABRIC_BUILDER =
            "net/fabricmc/fabric/api/object/builder/v1/block/entity/FabricBlockEntityTypeBuilder";
    static final String FABRIC_FACTORY = FABRIC_BUILDER + "$Factory";
    static final String RESOURCE_CONDITION =
            "net/fabricmc/fabric/api/resource/conditions/v1/ResourceCondition";
    public static final String BUILDER_HELPER =
            "com/retromod/shim/fabric/embedded/LegacyBlockEntityTypeBuilder";
    public static final String FACTORY_ADAPTER = BUILDER_HELPER + "$SupplierFactory";
    public static final String LEGACY_CONDITION =
            "com/retromod/shim/fabric/embedded/LegacyResourceCondition";

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String HOLDER_PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String INFO_LOOKUP = "net/minecraft/resources/RegistryOps$RegistryInfoLookup";

    static final String OF_DESC = "(L" + SUPPLIER + ";[L" + BLOCK + ";)L" + VANILLA_BUILDER + ";";
    private static final String CREATE_DESC =
            "(L" + BLOCK_POS + ";L" + BLOCK_STATE + ";)L" + BLOCK_ENTITY + ";";

    @Override
    public String getShimName() {
        return "Fabric Legacy Registration APIs";
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
        return "fabric";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(BUILDER_HELPER, generateBuilderHelper());
        transformer.registerSyntheticClass(FACTORY_ADAPTER, generateFactoryAdapter());
        transformer.registerMethodRedirect(VANILLA_BUILDER, "of", OF_DESC,
                BUILDER_HELPER, "of", "(L" + SUPPLIER + ";[L" + BLOCK + ";)L" + FABRIC_BUILDER + ";");
        transformer.registerClassRedirect(VANILLA_BUILDER, FABRIC_BUILDER);

        transformer.registerSyntheticClass(LEGACY_CONDITION, generateLegacyCondition());
        transformer.registerInterfaceReplacement(RESOURCE_CONDITION, LEGACY_CONDITION);
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

    /** {@code static FabricBlockEntityTypeBuilder of(BlockEntitySupplier, Block...)}. */
    public static byte[] generateBuilderHelper() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, BUILDER_HELPER, null, "java/lang/Object",
                null);
        cw.visitInnerClass(FACTORY_ADAPTER, BUILDER_HELPER, "SupplierFactory",
                ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of",
                "(L" + SUPPLIER + ";[L" + BLOCK + ";)L" + FABRIC_BUILDER + ";", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, FACTORY_ADAPTER);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, FACTORY_ADAPTER, "<init>", "(L" + SUPPLIER + ";)V", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, FABRIC_BUILDER, "create",
                "(L" + FABRIC_FACTORY + ";[L" + BLOCK + ";)L" + FABRIC_BUILDER + ";", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Fabric {@code Factory} that forwards {@code create(pos, state)} to the vanilla supplier. */
    public static byte[] generateFactoryAdapter() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, FACTORY_ADAPTER, null, "java/lang/Object",
                new String[]{FABRIC_FACTORY});
        cw.visitInnerClass(FACTORY_ADAPTER, BUILDER_HELPER, "SupplierFactory",
                ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "supplier", "L" + SUPPLIER + ";", null, null)
                .visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + SUPPLIER + ";)V", null,
                null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, FACTORY_ADAPTER, "supplier", "L" + SUPPLIER + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC, "create", CREATE_DESC, null, null);
        create.visitCode();
        create.visitVarInsn(ALOAD, 0);
        create.visitFieldInsn(GETFIELD, FACTORY_ADAPTER, "supplier", "L" + SUPPLIER + ";");
        create.visitVarInsn(ALOAD, 1);
        create.visitVarInsn(ALOAD, 2);
        create.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "create", CREATE_DESC, true);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code interface LegacyResourceCondition extends ResourceCondition}: the mod's
     * {@code test(Provider)} stays abstract and the 26.1 {@code test(RegistryInfoLookup)} calls it.
     */
    public static byte[] generateLegacyCondition() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LEGACY_CONDITION, null,
                "java/lang/Object", new String[]{RESOURCE_CONDITION});
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "test", "(L" + HOLDER_PROVIDER + ";)Z", null, null)
                .visitEnd();
        MethodVisitor bridge = cw.visitMethod(ACC_PUBLIC, "test", "(L" + INFO_LOOKUP + ";)Z", null,
                null);
        bridge.visitCode();
        bridge.visitVarInsn(ALOAD, 0);
        bridge.visitInsn(ACONST_NULL);
        bridge.visitMethodInsn(INVOKEINTERFACE, LEGACY_CONDITION, "test",
                "(L" + HOLDER_PROVIDER + ";)Z", true);
        bridge.visitInsn(IRETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
