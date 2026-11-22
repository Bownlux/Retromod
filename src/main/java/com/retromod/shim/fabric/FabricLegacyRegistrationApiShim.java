/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
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
    /** Holds the registry lookup of the condition test in progress, for the old lookup-free tag check. */
    public static final String CONDITION_LOOKUPS = "com/retromod/generated/LegacyResourceConditionLookups";
    static final String CONDITIONS_IMPL = "net/fabricmc/fabric/impl/resource/conditions/ResourceConditionsImpl";
    static final String OLD_TAGS_POPULATED = "(Lnet/minecraft/resources/Identifier;Ljava/util/List;)Z";
    static final String CONDITIONS = "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditions";
    static final String CONDITION_TYPE = "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditionType";
    static final String REGISTER_CONDITION = "(L" + CONDITION_TYPE + ";)V";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String CUSTOM_INGREDIENT = "net/fabricmc/fabric/api/recipe/v1/ingredient/CustomIngredient";
    static final String INGREDIENT_SERIALIZER = CUSTOM_INGREDIENT + "Serializer";
    public static final String LEGACY_INGREDIENT = "com/retromod/shim/fabric/embedded/LegacyCustomIngredient";
    public static final String LEGACY_INGREDIENT_SERIALIZER = "com/retromod/shim/fabric/embedded/LegacyCustomIngredientSerializer";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";

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

        transformer.registerSyntheticClass(CONDITION_LOOKUPS, generateConditionLookups());
        transformer.registerSyntheticClass(LEGACY_CONDITION, generateLegacyCondition());
        transformer.registerInterfaceReplacement(RESOURCE_CONDITION, LEGACY_CONDITION);
        // Fabric API's internal tag check lost its lookup-free form, which Moonlight's tag_empty condition calls
        // when it is handed no lookup; it now reads the lookup of the test in progress. Fabric API for 1.21.11 and
        // 26.1 to 26.3 has only the form that takes the lookup.
        transformer.registerMethodRedirect(CONDITIONS_IMPL, "tagsPopulated", OLD_TAGS_POPULATED,
                CONDITION_LOOKUPS, "tagsPopulated", OLD_TAGS_POPULATED);
        // Fabric API for 26.1 and newer ships fabric:false itself, which Moonlight registers as its own; a second
        // registration of an id in Fabric API's namespace keeps Fabric API's type instead of throwing.
        transformer.registerMethodRedirect(CONDITIONS, "register", REGISTER_CONDITION,
                CONDITION_LOOKUPS, "register", REGISTER_CONDITION);
        // Fabric API's custom ingredients renamed their methods; Moonlight's block type swap ingredient has the old ones.
        if (hostRenamedCustomIngredients()) {
            transformer.registerSyntheticClass(LEGACY_INGREDIENT, generateLegacyIngredient());
            transformer.registerSyntheticClass(LEGACY_INGREDIENT_SERIALIZER, generateLegacyIngredientSerializer());
            transformer.registerInterfaceReplacement(CUSTOM_INGREDIENT, LEGACY_INGREDIENT);
            transformer.registerInterfaceReplacement(INGREDIENT_SERIALIZER, LEGACY_INGREDIENT_SERIALIZER);
        }
    }

    /** Fabric API for 26.1 and newer reads getCodec() and items() where 1.21.1's read getCodec(allowEmpty) and getMatchingStacks(). */
    static boolean hostRenamedCustomIngredients() {
        org.objectweb.asm.tree.ClassNode serializer = com.retromod.core.ClassResourceInspector.read(INGREDIENT_SERIALIZER);
        // An unreadable host means an offline transform; this shim only registers for 26.1 and newer.
        return serializer == null || serializer.methods.stream()
                .noneMatch(m -> m.name.equals("getCodec") && m.desc.equals("(Z)L" + MAP_CODEC + ";"));
    }

    /**
     * {@code interface LegacyCustomIngredient extends CustomIngredient}: {@code items()} answers from the method the mod's
     * Fabric API had. 1.21.1 listed stacks in {@code getMatchingStacks()}, 1.21.2 and 1.21.3 listed item holders there,
     * and 1.21.4 to 1.21.11 streamed holders from {@code getMatchingItems()}, which a newer mod overrides itself.
     */
    public static byte[] generateLegacyIngredient() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LEGACY_INGREDIENT, null, "java/lang/Object",
                new String[]{CUSTOM_INGREDIENT});
        String stream = "()Ljava/util/stream/Stream;";
        String holder = "net/minecraft/core/Holder";
        String stack = "net/minecraft/world/item/ItemStack";
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getMatchingStacks", "()Ljava/util/List;", null, null).visitEnd();

        // getMatchingItems(): the old list as item holders, an empty stack leaving no item.
        MethodVisitor matching = cw.visitMethod(ACC_PUBLIC, "getMatchingItems", stream, null, null);
        matching.visitCode();
        matching.visitVarInsn(ALOAD, 0);
        matching.visitMethodInsn(INVOKEINTERFACE, LEGACY_INGREDIENT, "getMatchingStacks", "()Ljava/util/List;", true);
        matching.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "stream", stream, true);
        matching.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", METAFACTORY,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new org.objectweb.asm.Handle(H_INVOKESTATIC, LEGACY_INGREDIENT, "retromod$holder",
                        "(Ljava/lang/Object;)L" + holder + ";", true),
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)L" + holder + ";"));
        matching.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/Stream", "map",
                "(Ljava/util/function/Function;)Ljava/util/stream/Stream;", true);
        matching.visitInvokeDynamicInsn("test", "()Ljava/util/function/Predicate;", METAFACTORY,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)Z"),
                new org.objectweb.asm.Handle(H_INVOKESTATIC, "java/util/Objects", "nonNull", "(Ljava/lang/Object;)Z", false),
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)Z"));
        matching.visitMethodInsn(INVOKEINTERFACE, "java/util/stream/Stream", "filter",
                "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;", true);
        matching.visitInsn(ARETURN);
        matching.visitMaxs(0, 0);
        matching.visitEnd();

        MethodVisitor items = cw.visitMethod(ACC_PUBLIC, "items", stream, null, null);
        items.visitCode();
        items.visitVarInsn(ALOAD, 0);
        items.visitMethodInsn(INVOKEINTERFACE, LEGACY_INGREDIENT, "getMatchingItems", stream, true);
        items.visitInsn(ARETURN);
        items.visitMaxs(0, 0);
        items.visitEnd();

        // retromod$holder(entry): a stack's item holder, null for an empty stack, or the entry itself when it is a holder.
        MethodVisitor of = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "retromod$holder",
                "(Ljava/lang/Object;)L" + holder + ";", null, null);
        of.visitCode();
        Label notStack = new Label();
        Label empty = new Label();
        of.visitVarInsn(ALOAD, 0);
        of.visitTypeInsn(INSTANCEOF, stack);
        of.visitJumpInsn(IFEQ, notStack);
        of.visitVarInsn(ALOAD, 0);
        of.visitTypeInsn(CHECKCAST, stack);
        of.visitMethodInsn(INVOKEVIRTUAL, stack, "isEmpty", "()Z", false);
        of.visitJumpInsn(IFNE, empty);
        of.visitVarInsn(ALOAD, 0);
        of.visitTypeInsn(CHECKCAST, stack);
        of.visitMethodInsn(INVOKEVIRTUAL, stack, "typeHolder", "()L" + holder + ";", false);
        of.visitInsn(ARETURN);
        of.visitLabel(empty);
        of.visitInsn(ACONST_NULL);
        of.visitInsn(ARETURN);
        of.visitLabel(notStack);
        of.visitVarInsn(ALOAD, 0);
        of.visitTypeInsn(CHECKCAST, holder);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final org.objectweb.asm.Handle METAFACTORY = new org.objectweb.asm.Handle(H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    /**
     * {@code interface LegacyCustomIngredientSerializer extends CustomIngredientSerializer}: {@code getCodec()} is 1.21.1's
     * codec that refuses empty ingredients, which a 1.21.2 to 1.21.11 mod overrides with its own, and
     * {@code getStreamCodec()} is the packet codec every Fabric API before 26.1 named {@code getPacketCodec()}.
     */
    public static byte[] generateLegacyIngredientSerializer() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LEGACY_INGREDIENT_SERIALIZER, null, "java/lang/Object",
                new String[]{INGREDIENT_SERIALIZER});
        String codec = "()L" + MAP_CODEC + ";";
        String stream = "()L" + STREAM_CODEC + ";";
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getCodec", "(Z)L" + MAP_CODEC + ";", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getPacketCodec", stream, null, null).visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getCodec", codec, null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitInsn(ICONST_0);
        get.visitMethodInsn(INVOKEINTERFACE, LEGACY_INGREDIENT_SERIALIZER, "getCodec", "(Z)L" + MAP_CODEC + ";", true);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor packet = cw.visitMethod(ACC_PUBLIC, "getStreamCodec", stream, null, null);
        packet.visitCode();
        packet.visitVarInsn(ALOAD, 0);
        packet.visitMethodInsn(INVOKEINTERFACE, LEGACY_INGREDIENT_SERIALIZER, "getPacketCodec", stream, true);
        packet.visitInsn(ARETURN);
        packet.visitMaxs(0, 0);
        packet.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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
     * The lookup holder: {@code push} and {@code pop} around a test, and the old {@code tagsPopulated(registry, tags)},
     * which asks Fabric API with that lookup. With none, Fabric API reports the tags missing, as it does for a null lookup.
     */
    public static byte[] generateConditionLookups() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CONDITION_LOOKUPS, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CURRENT", "Ljava/lang/ThreadLocal;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "loggedDuplicate", "Z", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/lang/ThreadLocal");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/lang/ThreadLocal", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, CONDITION_LOOKUPS, "CURRENT", "Ljava/lang/ThreadLocal;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor push = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "push", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        push.visitCode();
        push.visitFieldInsn(GETSTATIC, CONDITION_LOOKUPS, "CURRENT", "Ljava/lang/ThreadLocal;");
        push.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        push.visitFieldInsn(GETSTATIC, CONDITION_LOOKUPS, "CURRENT", "Ljava/lang/ThreadLocal;");
        push.visitVarInsn(ALOAD, 0);
        push.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        push.visitInsn(ARETURN);
        push.visitMaxs(0, 0);
        push.visitEnd();

        MethodVisitor pop = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pop", "(Ljava/lang/Object;)V", null, null);
        pop.visitCode();
        pop.visitFieldInsn(GETSTATIC, CONDITION_LOOKUPS, "CURRENT", "Ljava/lang/ThreadLocal;");
        pop.visitVarInsn(ALOAD, 0);
        pop.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ThreadLocal", "set", "(Ljava/lang/Object;)V", false);
        pop.visitInsn(RETURN);
        pop.visitMaxs(0, 0);
        pop.visitEnd();

        MethodVisitor tags = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tagsPopulated", OLD_TAGS_POPULATED, null, null);
        tags.visitCode();
        tags.visitFieldInsn(GETSTATIC, CONDITION_LOOKUPS, "CURRENT", "Ljava/lang/ThreadLocal;");
        tags.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false);
        tags.visitTypeInsn(CHECKCAST, INFO_LOOKUP);
        tags.visitVarInsn(ALOAD, 0);
        tags.visitVarInsn(ALOAD, 1);
        tags.visitMethodInsn(INVOKESTATIC, CONDITIONS_IMPL, "tagsPopulated",
                "(L" + INFO_LOOKUP + ";Lnet/minecraft/resources/Identifier;Ljava/util/List;)Z", false);
        tags.visitInsn(IRETURN);
        tags.visitMaxs(0, 0);
        tags.visitEnd();

        // register(type): skipped when Fabric API already has a condition under the same fabric: id.
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", REGISTER_CONDITION, null, null);
        register.visitCode();
        Label add = new Label();
        register.visitVarInsn(ALOAD, 0);
        register.visitMethodInsn(INVOKEINTERFACE, CONDITION_TYPE, "id", "()L" + IDENTIFIER + ";", true);
        register.visitVarInsn(ASTORE, 1);
        register.visitLdcInsn("fabric");
        register.visitVarInsn(ALOAD, 1);
        register.visitMethodInsn(INVOKEVIRTUAL, IDENTIFIER, "getNamespace", "()Ljava/lang/String;", false);
        register.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        register.visitJumpInsn(IFEQ, add);
        register.visitVarInsn(ALOAD, 1);
        register.visitMethodInsn(INVOKESTATIC, CONDITIONS, "getConditionType",
                "(L" + IDENTIFIER + ";)L" + CONDITION_TYPE + ";", false);
        register.visitJumpInsn(IFNULL, add);
        // The first skip is logged at debug, so a clash stays traceable without repeating for every mod.
        Label done = new Label();
        register.visitFieldInsn(GETSTATIC, CONDITION_LOOKUPS, "loggedDuplicate", "Z");
        register.visitJumpInsn(IFNE, done);
        register.visitInsn(ICONST_1);
        register.visitFieldInsn(PUTSTATIC, CONDITION_LOOKUPS, "loggedDuplicate", "Z");
        register.visitLdcInsn("Retromod");
        // The host's slf4j, named at runtime because the release jar's shading rewrites the literal package.
        String slf4j = com.retromod.util.HostLibraryNames.SLF4J_PACKAGE;
        register.visitMethodInsn(INVOKESTATIC, slf4j + "/LoggerFactory", "getLogger",
                "(Ljava/lang/String;)L" + slf4j + "/Logger;", false);
        register.visitLdcInsn("Kept Fabric API's resource condition {}; a mod registered the same id again");
        register.visitVarInsn(ALOAD, 1);
        register.visitMethodInsn(INVOKEINTERFACE, slf4j + "/Logger", "debug",
                "(Ljava/lang/String;Ljava/lang/Object;)V", true);
        register.visitLabel(done);
        register.visitInsn(RETURN);
        register.visitLabel(add);
        register.visitVarInsn(ALOAD, 0);
        register.visitMethodInsn(INVOKESTATIC, CONDITIONS, "register", REGISTER_CONDITION, false);
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
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
        // The lookup is kept for the test's length, so an old tag check inside it can still read it.
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        bridge.visitTryCatchBlock(start, end, failed, null);
        bridge.visitVarInsn(ALOAD, 1);
        bridge.visitMethodInsn(INVOKESTATIC, CONDITION_LOOKUPS, "push", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        bridge.visitVarInsn(ASTORE, 2);
        bridge.visitLabel(start);
        bridge.visitVarInsn(ALOAD, 0);
        bridge.visitInsn(ACONST_NULL);
        bridge.visitMethodInsn(INVOKEINTERFACE, LEGACY_CONDITION, "test",
                "(L" + HOLDER_PROVIDER + ";)Z", true);
        bridge.visitLabel(end);
        bridge.visitVarInsn(ALOAD, 2);
        bridge.visitMethodInsn(INVOKESTATIC, CONDITION_LOOKUPS, "pop", "(Ljava/lang/Object;)V", false);
        bridge.visitInsn(IRETURN);
        bridge.visitLabel(failed);
        bridge.visitVarInsn(ALOAD, 2);
        bridge.visitMethodInsn(INVOKESTATIC, CONDITION_LOOKUPS, "pop", "(Ljava/lang/Object;)V", false);
        bridge.visitInsn(ATHROW);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
