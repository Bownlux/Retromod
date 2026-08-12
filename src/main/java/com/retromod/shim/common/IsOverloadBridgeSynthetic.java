/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * Common synthetic bridges for small vanilla methods removed by the 26.x API changes.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * MC 26.1 purged the single-argument {@code is(...)} convenience overloads that virtually every mod
 * calls (verified against the real 26.1 + 26.2 jars): {@code BlockStateBase} keeps only
 * {@code is(TagKey, Predicate)} (so {@code is(Block)}, {@code is(TagKey)}, {@code is(Holder)},
 * {@code is(ResourceKey)} are gone), {@code ItemStack} keeps only {@code is(Predicate<Holder>)}
 * (so {@code is(Item)}, {@code is(TagKey)}, {@code is(Holder)} are gone), and {@code FluidState}
 * lost all of its {@code is(...)} overloads. A 1.21.x mod dies at first use with
 * {@code NoSuchMethodError} - e.g. Macaw's Bridges crashed the server tick loop in
 * {@code Bridge_Block.onPlace} calling {@code BlockState.is(Block)} on block placement.
 *
 * <p>This synthetic carries static reimplementations of the removed overloads (each a faithful copy
 * of the old vanilla implementation, using only members verified present on 26.1/26.2:
 * {@code getBlock()}, {@code builtInRegistryHolder()}, {@code Holder.is(...)}, {@code getItem()},
 * {@code getType()}, {@code typeHolder()}). The old calls are rewritten to it with devirtualized
 * method redirects (receiver becomes argument 0). Registered from the 1.21.11 -> 26.1 shims (the
 * layer where the overloads died), so every chain targeting 26.1+ gets it on all three loaders;
 * the synthetic reaches the mod jar via the Fabric synthetic-writing pass or the NeoForge/Forge
 * {@link com.retromod.core.SyntheticEmbedder}.
 *
 * <p>The same synthetic also carries focused adapters for other direct API reshapes found in
 * real transformed mods. Keeping these small helpers together lets the embedder relocate only one
 * compatibility surface per mod, plus the predicate adapter when that specific bridge is used.
 */
public final class IsOverloadBridgeSynthetic {

    private IsOverloadBridgeSynthetic() {}

    public static final String INTERNAL = "com/retromod/shim/common/embedded/IsOverloadBridge";

    // Owners whose calls get redirected. javac emits the receiver's static type, which for mod code
    // is almost always BlockState, but a helper typed BlockStateBase can also hold the receiver, so
    // both owners are matched (the helper params are typed BlockStateBase, the supertype).
    private static final String BS = "net/minecraft/world/level/block/state/BlockState";
    private static final String BSB = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String FLUID_STATE = "net/minecraft/world/level/material/FluidState";
    private static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String PHANTOM = "net/minecraft/world/entity/monster/Phantom";
    private static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String SERVER_ENTITY_GETTER = "net/minecraft/server/level/ServerEntityGetter";
    private static final String TARGETING_CONDITIONS =
            "net/minecraft/world/entity/ai/targeting/TargetingConditions";
    private static final String TARGETING_SELECTOR = TARGETING_CONDITIONS + "$Selector";
    private static final String AABB = "net/minecraft/world/phys/AABB";
    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String ENTITY_RENDER_DISPATCHER =
            "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String QUATERNION = "org/joml/Quaternionf";

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String FLUID = "net/minecraft/world/level/material/Fluid";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String HOLDER_REF = "net/minecraft/core/Holder$Reference";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";

    private static final String L_BSB = "L" + BSB + ";";
    private static final String L_STACK = "L" + STACK + ";";
    private static final String L_FS = "L" + FLUID_STATE + ";";
    private static final String L_ENTITY_TYPE = "L" + ENTITY_TYPE + ";";
    private static final String L_LIVING_ENTITY = "L" + LIVING_ENTITY + ";";
    private static final String L_PHANTOM = "L" + PHANTOM + ";";
    private static final String L_DAMAGE_SOURCE = "L" + DAMAGE_SOURCE + ";";
    private static final String L_TARGETING_CONDITIONS = "L" + TARGETING_CONDITIONS + ";";
    private static final String L_TARGETING_SELECTOR = "L" + TARGETING_SELECTOR + ";";
    private static final String L_LEVEL = "L" + LEVEL + ";";
    private static final String L_AABB = "L" + AABB + ";";
    private static final String L_PREDICATE = "L" + PREDICATE + ";";
    private static final String L_ENTITY_RENDER_DISPATCHER = "L" + ENTITY_RENDER_DISPATCHER + ";";
    private static final String L_QUATERNION = "L" + QUATERNION + ";";
    private static final String L_BLOCK = "L" + BLOCK + ";";
    private static final String L_ITEM = "L" + ITEM + ";";
    private static final String L_FLUID = "L" + FLUID + ";";
    private static final String L_TAG = "L" + TAG_KEY + ";";
    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_HOLDER_REF = "L" + HOLDER_REF + ";";
    private static final String L_RK = "L" + RESOURCE_KEY + ";";

    public static final String PREDICATE_SELECTOR_INTERNAL = INTERNAL + "$PredicateSelector";

    private static final String SPE = "net/minecraft/world/level/levelgen/structure/pools/StructurePoolElement";
    private static final String STM = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager";
    private static final String JBI = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$JigsawBlockInfo";
    private static final String SBI = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo";
    private static final String SHUFFLED_DESC = "(L" + STM + ";Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/util/RandomSource;)Ljava/util/List;";

    private static final String INT_PROVIDER = "net/minecraft/util/valueproviders/IntProvider";
    private static final String FLOAT_PROVIDER = "net/minecraft/util/valueproviders/FloatProvider";
    private static final String INT_PROVIDERS = "net/minecraft/util/valueproviders/IntProviders";
    private static final String FLOAT_PROVIDERS = "net/minecraft/util/valueproviders/FloatProviders";
    private static final String L_CODEC = "Lcom/mojang/serialization/Codec;";

    /** Register the synthetic classes and their exact removed-method rewrites. */
    public static void register(RetromodTransformer t) {
        t.registerSyntheticClass(INTERNAL, generate());
        t.registerSyntheticClass(PREDICATE_SELECTOR_INTERNAL, generatePredicateSelector());

        // BlockState.is(...) - both plausible call-site owners map to the same helpers.
        for (String owner : new String[]{BS, BSB}) {
            t.registerMethodRedirect(owner, "is", "(" + L_BLOCK + ")Z",
                    INTERNAL, "blockStateIs", "(" + L_BSB + L_BLOCK + ")Z", true);
            t.registerMethodRedirect(owner, "is", "(" + L_TAG + ")Z",
                    INTERNAL, "blockStateIsTag", "(" + L_BSB + L_TAG + ")Z", true);
            t.registerMethodRedirect(owner, "is", "(" + L_HOLDER + ")Z",
                    INTERNAL, "blockStateIsHolder", "(" + L_BSB + L_HOLDER + ")Z", true);
            t.registerMethodRedirect(owner, "is", "(" + L_RK + ")Z",
                    INTERNAL, "blockStateIsKey", "(" + L_BSB + L_RK + ")Z", true);
        }
        // ItemStack.is(...)
        t.registerMethodRedirect(STACK, "is", "(" + L_ITEM + ")Z",
                INTERNAL, "itemStackIs", "(" + L_STACK + L_ITEM + ")Z", true);
        t.registerMethodRedirect(STACK, "is", "(" + L_TAG + ")Z",
                INTERNAL, "itemStackIsTag", "(" + L_STACK + L_TAG + ")Z", true);
        t.registerMethodRedirect(STACK, "is", "(" + L_HOLDER + ")Z",
                INTERNAL, "itemStackIsHolder", "(" + L_STACK + L_HOLDER + ")Z", true);
        // FluidState.is(...)
        t.registerMethodRedirect(FLUID_STATE, "is", "(" + L_FLUID + ")Z",
                INTERNAL, "fluidStateIs", "(" + L_FS + L_FLUID + ")Z", true);
        t.registerMethodRedirect(FLUID_STATE, "is", "(" + L_TAG + ")Z",
                INTERNAL, "fluidStateIsTag", "(" + L_FS + L_TAG + ")Z", true);

        // EntityType lost the same tag convenience method. Its old implementation delegated to
        // the registry holder, which remains public on 26.1 and 26.2.
        t.registerMethodRedirect(ENTITY_TYPE, "is", "(" + L_TAG + ")Z",
                INTERNAL, "entityTypeIsTag", "(" + L_ENTITY_TYPE + L_TAG + ")Z", true);

        // The old combined hurt method was mapped onto hurtClient during intermediary remapping,
        // even though the amount proves it is the pre-split API. hurtOrSimulate is the modern
        // dispatcher that preserves both server damage and client simulation behavior.
        t.registerMethodRedirect(LIVING_ENTITY, "hurtClient", "(" + L_DAMAGE_SOURCE + "F)Z",
                INTERNAL, "livingEntityHurtClient",
                "(" + L_LIVING_ENTITY + L_DAMAGE_SOURCE + "F)Z", true);

        // LivingEntity.canAttack(target, conditions) was replaced by the level-aware
        // TargetingConditions.test call. Revamped Phantoms emits Phantom as the call owner, so
        // reproduce the current Phantom helper's exact test(level, self, target) behavior.
        t.registerMethodRedirect(PHANTOM, "canAttack",
                "(" + L_LIVING_ENTITY + L_TARGETING_CONDITIONS + ")Z",
                INTERNAL, "phantomCanAttack",
                "(" + L_PHANTOM + L_LIVING_ENTITY + L_TARGETING_CONDITIONS + ")Z", true);

        // TargetingConditions changed its selector from Predicate<LivingEntity> to a two-argument
        // Selector that also receives ServerLevel. Wrap the old predicate and ignore only the new
        // contextual argument.
        t.registerMethodRedirect(TARGETING_CONDITIONS, "selector",
                "(" + L_PREDICATE + ")" + L_TARGETING_CONDITIONS,
                INTERNAL, "targetingConditionsSelector",
                "(" + L_TARGETING_CONDITIONS + L_PREDICATE + ")" + L_TARGETING_CONDITIONS, true);

        // The level-aware nearby query moved from EntityGetter/Level to ServerEntityGetter when
        // TargetingConditions gained its ServerLevel parameter. Delegate on a server level and
        // return an empty result on the client, where the modern operation is not defined.
        String nearbyDesc = "(Ljava/lang/Class;" + L_TARGETING_CONDITIONS + L_LIVING_ENTITY
                + L_AABB + ")Ljava/util/List;";
        t.registerMethodRedirect(LEVEL, "getNearbyEntities", nearbyDesc,
                INTERNAL, "levelGetNearbyEntities", "(" + L_LEVEL + nearbyDesc.substring(1), true);

        // EntityRenderDispatcher no longer caches and exposes cameraOrientation. Its prepare
        // method now stores only the Camera, whose rotation() is the same orientation value.
        t.registerMethodRedirect(ENTITY_RENDER_DISPATCHER, "cameraOrientation", "()" + L_QUATERNION,
                INTERNAL, "cameraOrientation",
                "(" + L_ENTITY_RENDER_DISPATCHER + ")" + L_QUATERNION, true);

        // 26.1+ moved IntProvider/FloatProvider's static codec factories AND codec constants to
        // the companion classes with IDENTICAL signatures (verified on 26.2: IntProviders has
        // codec(II), CODEC, NON_NEGATIVE_CODEC, POSITIVE_CODEC; FloatProviders has codec(FF),
        // codec(F), CODEC). Plain owner moves. Found in the field twice: YungJigsawStructure's
        // codec calls IntProvider.codec(0,15) (26.2 server run), and Apollo's Enchantment
        // Rebalance's gap report flags IntProvider.CODEC (#114).
        t.registerMethodRedirect(INT_PROVIDER, "codec", "(II)" + L_CODEC,
                INT_PROVIDERS, "codec", "(II)" + L_CODEC);
        t.registerMethodRedirect(FLOAT_PROVIDER, "codec", "(FF)" + L_CODEC,
                FLOAT_PROVIDERS, "codec", "(FF)" + L_CODEC);
        t.registerMethodRedirect(FLOAT_PROVIDER, "codec", "(F)" + L_CODEC,
                FLOAT_PROVIDERS, "codec", "(F)" + L_CODEC);
        for (String f : new String[]{"CODEC", "NON_NEGATIVE_CODEC", "POSITIVE_CODEC"}) {
            t.registerFieldRedirect(INT_PROVIDER, f, L_CODEC, INT_PROVIDERS, f, L_CODEC);
        }
        t.registerFieldRedirect(FLOAT_PROVIDER, "CODEC", L_CODEC, FLOAT_PROVIDERS, "CODEC", L_CODEC);

        // 26.1 MapCodec-ized the loot/density type registries and DELETED the
        // LootItemFunctionType/LootItemConditionType wrappers (#114); wrapper stand-ins +
        // registration-time value conversion. (The 26.1->26.2 shim later overwrites the
        // conversion bridge with the variant that also covers STRUCTURE_PROCESSOR.)
        WorldgenTypeBridgeSynthetic.registerPre26_2(t);

        // 26.1 wrapped jigsaw query results: StructurePoolElement.getShuffledJigsawBlocks still
        // returns List, but the elements are now StructureTemplate$JigsawBlockInfo, and a 1.21.x
        // caller's element cast to StructureBlockInfo dies CCE (YUNG's custom JigsawManager,
        // captured live during /place on the 26.2 server). The bridge unwraps each element via
        // JigsawBlockInfo.info().
        t.registerMethodRedirect(SPE, "getShuffledJigsawBlocks", SHUFFLED_DESC,
                INTERNAL, "shuffledJigsawBlocks", "(L" + SPE + ";" + SHUFFLED_DESC.substring(1), true);

        // 26.1 re-typed JigsawBlock.canAttach from (StructureBlockInfo x2) to (JigsawBlockInfo x2)
        // (same YUNG's jigsaw path as above; hit right after the list unwrap). The bridge wraps
        // both args via JigsawBlockInfo.of and calls the modern overload.
        t.registerMethodRedirect("net/minecraft/world/level/block/JigsawBlock", "canAttach",
                "(L" + SBI + ";L" + SBI + ";)Z", INTERNAL, "jigsawCanAttach", "(L" + SBI + ";L" + SBI + ";)Z");

        // Modern MC renamed Registry's holder getters (getHolder -> get, getHolderOrThrow ->
        // getOrThrow, now inherited from HolderGetter; all four targets verified present on
        // 26.2). A 1.21.1 mod dies NoSuchMethodError at first holder lookup, e.g. YUNG's
        // JigsawStructureAssembler.addChildrenForPiece during structure generation (captured
        // live on the 26.2 server). Same-owner renames, so the INVOKEINTERFACE opcode and
        // descriptors are untouched.
        String registry = "net/minecraft/core/Registry";
        String optDesc = "(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;";
        t.registerMethodRedirect(registry, "getHolder", optDesc, registry, "get", optDesc);
        t.registerMethodRedirect(registry, "getHolder",
                "(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;",
                registry, "get", "(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;");
        t.registerMethodRedirect(registry, "getHolder", "(I)Ljava/util/Optional;",
                registry, "get", "(I)Ljava/util/Optional;");
        String orThrowDesc = "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;";
        t.registerMethodRedirect(registry, "getHolderOrThrow", orThrowDesc,
                registry, "getOrThrow", orThrowDesc);

        // 26.1 dropped ConditionalEffect.codec's ContextKeySet validation arg (exact old
        // signature captured live: codec(Codec, net.minecraft.util.context.ContextKeySet)).
        // #114: Apollo's Enchantment Rebalance's entrypoint died on this before registering its
        // custom enchantment effect/condition types, which made its datapack look broken.
        t.registerArgDropMethodRedirect(
                "net/minecraft/world/item/enchantment/ConditionalEffect", "codec",
                "(" + L_CODEC + "Lnet/minecraft/util/context/ContextKeySet;)" + L_CODEC,
                "net/minecraft/world/item/enchantment/ConditionalEffect", "codec",
                "(" + L_CODEC + ")" + L_CODEC);
    }

    /** A ClassWriter that never consults the (absent) MC class hierarchy for frame computation. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    public static byte[] generate() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object", null);

        MethodVisitor c = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // Old impl: this.getBlock() == block
        emitIdentityCheck(cw, "blockStateIs", L_BSB, L_BLOCK,
                m -> m.visitMethodInsn(INVOKEVIRTUAL, BSB, "getBlock", "()" + L_BLOCK, false));
        // Old impl: this.getItem() == item
        emitIdentityCheck(cw, "itemStackIs", L_STACK, L_ITEM,
                m -> m.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()" + L_ITEM, false));
        // Old impl: this.getType() == fluid
        emitIdentityCheck(cw, "fluidStateIs", L_FS, L_FLUID,
                m -> m.visitMethodInsn(INVOKEVIRTUAL, FLUID_STATE, "getType", "()" + L_FLUID, false));

        // Old impls delegating to the entry's registry Holder: holder().is(tag/holder/key).
        emitHolderIs(cw, "blockStateIsTag", L_BSB, L_TAG, IsOverloadBridgeSynthetic::emitBlockHolder);
        emitHolderIs(cw, "blockStateIsHolder", L_BSB, L_HOLDER, IsOverloadBridgeSynthetic::emitBlockHolder);
        emitHolderIs(cw, "blockStateIsKey", L_BSB, L_RK, IsOverloadBridgeSynthetic::emitBlockHolder);
        emitHolderIs(cw, "itemStackIsTag", L_STACK, L_TAG, IsOverloadBridgeSynthetic::emitItemHolder);
        emitHolderIs(cw, "itemStackIsHolder", L_STACK, L_HOLDER, IsOverloadBridgeSynthetic::emitItemHolder);
        emitHolderIs(cw, "fluidStateIsTag", L_FS, L_TAG,
                m -> m.visitMethodInsn(INVOKEVIRTUAL, FLUID_STATE, "typeHolder", "()" + L_HOLDER, false));
        emitHolderIs(cw, "entityTypeIsTag", L_ENTITY_TYPE, L_TAG,
                m -> m.visitMethodInsn(INVOKEVIRTUAL, ENTITY_TYPE, "builtInRegistryHolder",
                        "()" + L_HOLDER_REF, false));

        emitLivingEntityHurtClient(cw);
        emitPhantomCanAttack(cw);
        emitTargetingConditionsSelector(cw);
        emitLevelGetNearbyEntities(cw);
        emitCameraOrientation(cw);

        emitShuffledJigsawBlocks(cw);
        emitJigsawCanAttach(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code static List shuffledJigsawBlocks(SPE, STM, BlockPos, Rotation, RandomSource)}: calls
     * the real {@code getShuffledJigsawBlocks} and unwraps each JigsawBlockInfo element to its
     * wrapped StructureBlockInfo (26.x wraps; 1.21.x callers cast the elements). Elements that are
     * not JigsawBlockInfo pass through, so the helper is harmless if the shape changes again.
     */
    private static void emitShuffledJigsawBlocks(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "shuffledJigsawBlocks",
                "(L" + SPE + ";" + SHUFFLED_DESC.substring(1), null, null);
        m.visitCode();
        for (int i = 0; i <= 4; i++) m.visitVarInsn(ALOAD, i);
        m.visitMethodInsn(INVOKEVIRTUAL, SPE, "getShuffledJigsawBlocks", SHUFFLED_DESC, false);
        m.visitVarInsn(ASTORE, 5);
        m.visitTypeInsn(NEW, "java/util/ArrayList");
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 5);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false);
        m.visitVarInsn(ASTORE, 6);
        m.visitVarInsn(ALOAD, 5);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        m.visitVarInsn(ASTORE, 7);
        Label loop = new Label(), end = new Label(), addRaw = new Label();
        m.visitLabel(loop);
        m.visitVarInsn(ALOAD, 7);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        m.visitJumpInsn(IFEQ, end);
        m.visitVarInsn(ALOAD, 7);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        m.visitVarInsn(ASTORE, 8);
        m.visitVarInsn(ALOAD, 8);
        m.visitTypeInsn(INSTANCEOF, JBI);
        m.visitJumpInsn(IFEQ, addRaw);
        m.visitVarInsn(ALOAD, 6);
        m.visitVarInsn(ALOAD, 8);
        m.visitTypeInsn(CHECKCAST, JBI);
        m.visitMethodInsn(INVOKEVIRTUAL, JBI, "info", "()L" + SBI + ";", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        m.visitInsn(POP);
        m.visitJumpInsn(GOTO, loop);
        m.visitLabel(addRaw);
        m.visitVarInsn(ALOAD, 6);
        m.visitVarInsn(ALOAD, 8);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        m.visitInsn(POP);
        m.visitJumpInsn(GOTO, loop);
        m.visitLabel(end);
        m.visitVarInsn(ALOAD, 6);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** static boolean jigsawCanAttach(SBI a, SBI b) = JigsawBlock.canAttach(of(a), of(b)). */
    private static void emitJigsawCanAttach(ClassWriter cw) {
        String jigsawBlock = "net/minecraft/world/level/block/JigsawBlock";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "jigsawCanAttach",
                "(L" + SBI + ";L" + SBI + ";)Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, JBI, "of", "(L" + SBI + ";)L" + JBI + ";", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, JBI, "of", "(L" + SBI + ";)L" + JBI + ";", false);
        m.visitMethodInsn(INVOKESTATIC, jigsawBlock, "canAttach",
                "(L" + JBI + ";L" + JBI + ";)Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** receiver-chain(arg0) == arg1, as `static boolean name(recv, arg)` */
    private static void emitIdentityCheck(ClassWriter cw, String name, String lRecv, String lArg,
                                          java.util.function.Consumer<MethodVisitor> receiverChain) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + lRecv + lArg + ")Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        receiverChain.accept(m);              // -> the Block/Item/Fluid instance
        m.visitVarInsn(ALOAD, 1);
        Label ne = new Label();
        m.visitJumpInsn(IF_ACMPNE, ne);
        m.visitInsn(ICONST_1);
        m.visitInsn(IRETURN);
        m.visitLabel(ne);
        m.visitInsn(ICONST_0);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** receiver-chain(arg0) -> a Holder, then Holder.is(arg1), as `static boolean name(recv, arg)` */
    private static void emitHolderIs(ClassWriter cw, String name, String lRecv, String lArg,
                                     java.util.function.Consumer<MethodVisitor> holderChain) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + lRecv + lArg + ")Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        holderChain.accept(m);                // -> a Holder (or Holder$Reference, which implements it)
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, HOLDER, "is", "(" + lArg + ")Z", true);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitBlockHolder(MethodVisitor m) {
        m.visitMethodInsn(INVOKEVIRTUAL, BSB, "getBlock", "()" + L_BLOCK, false);
        m.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "builtInRegistryHolder", "()" + L_HOLDER_REF, false);
    }

    private static void emitItemHolder(MethodVisitor m) {
        m.visitMethodInsn(INVOKEVIRTUAL, STACK, "getItem", "()" + L_ITEM, false);
        m.visitMethodInsn(INVOKEVIRTUAL, ITEM, "builtInRegistryHolder", "()" + L_HOLDER_REF, false);
    }

    /** Route the old combined damage call through the modern server-or-client dispatcher. */
    private static void emitLivingEntityHurtClient(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "livingEntityHurtClient",
                "(" + L_LIVING_ENTITY + L_DAMAGE_SOURCE + "F)Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(FLOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "hurtOrSimulate",
                "(" + L_DAMAGE_SOURCE + "F)Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Match modern Phantom's private level-aware targeting helper without calling it privately. */
    private static void emitPhantomCanAttack(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "phantomCanAttack",
                "(" + L_PHANTOM + L_LIVING_ENTITY + L_TARGETING_CONDITIONS + ")Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 3);
        m.visitTypeInsn(INSTANCEOF, SERVER_LEVEL);
        Label serverLevel = new Label();
        m.visitJumpInsn(IFNE, serverLevel);
        m.visitInsn(ICONST_0);
        m.visitInsn(IRETURN);
        m.visitLabel(serverLevel);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 3);
        m.visitTypeInsn(CHECKCAST, SERVER_LEVEL);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, TARGETING_CONDITIONS, "test",
                "(L" + SERVER_LEVEL + ";" + L_LIVING_ENTITY + L_LIVING_ENTITY + ")Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Convert the old Predicate selector into the modern level-aware selector interface. */
    private static void emitTargetingConditionsSelector(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "targetingConditionsSelector",
                "(" + L_TARGETING_CONDITIONS + L_PREDICATE + ")" + L_TARGETING_CONDITIONS,
                null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        Label wrap = new Label();
        m.visitJumpInsn(IFNONNULL, wrap);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, TARGETING_CONDITIONS, "selector",
                "(" + L_TARGETING_SELECTOR + ")" + L_TARGETING_CONDITIONS, false);
        m.visitInsn(ARETURN);
        m.visitLabel(wrap);
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(NEW, PREDICATE_SELECTOR_INTERNAL);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESPECIAL, PREDICATE_SELECTOR_INTERNAL, "<init>",
                "(" + L_PREDICATE + ")V", false);
        m.visitMethodInsn(INVOKEVIRTUAL, TARGETING_CONDITIONS, "selector",
                "(" + L_TARGETING_SELECTOR + ")" + L_TARGETING_CONDITIONS, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Delegate Level's removed query to the modern server-only interface. */
    private static void emitLevelGetNearbyEntities(ClassWriter cw) {
        String desc = "(Ljava/lang/Class;" + L_TARGETING_CONDITIONS + L_LIVING_ENTITY
                + L_AABB + ")Ljava/util/List;";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "levelGetNearbyEntities",
                "(" + L_LEVEL + desc.substring(1), null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(INSTANCEOF, SERVER_ENTITY_GETTER);
        Label server = new Label();
        m.visitJumpInsn(IFNE, server);
        m.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        m.visitInsn(ARETURN);
        m.visitLabel(server);
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(CHECKCAST, SERVER_ENTITY_GETTER);
        for (int i = 1; i <= 4; i++) m.visitVarInsn(ALOAD, i);
        m.visitMethodInsn(INVOKEINTERFACE, SERVER_ENTITY_GETTER, "getNearbyEntities", desc, true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Read the orientation from the Camera now retained by EntityRenderDispatcher.prepare. */
    private static void emitCameraOrientation(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "cameraOrientation",
                "(" + L_ENTITY_RENDER_DISPATCHER + ")" + L_QUATERNION, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, ENTITY_RENDER_DISPATCHER, "camera", "L" + CAMERA + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, CAMERA, "rotation", "()" + L_QUATERNION, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Generate the tiny adapter that makes a Predicate implement the modern selector SAM. */
    public static byte[] generatePredicateSelector() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PREDICATE_SELECTOR_INTERNAL, null,
                "java/lang/Object", new String[]{TARGETING_SELECTOR});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "predicate", L_PREDICATE, null, null).visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_PREDICATE + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, PREDICATE_SELECTOR_INTERNAL, "predicate", L_PREDICATE);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor test = cw.visitMethod(ACC_PUBLIC, "test",
                "(" + L_LIVING_ENTITY + "L" + SERVER_LEVEL + ";)Z", null, null);
        test.visitCode();
        test.visitVarInsn(ALOAD, 0);
        test.visitFieldInsn(GETFIELD, PREDICATE_SELECTOR_INTERNAL, "predicate", L_PREDICATE);
        test.visitVarInsn(ALOAD, 1);
        test.visitMethodInsn(INVOKEINTERFACE, PREDICATE, "test", "(Ljava/lang/Object;)Z", true);
        test.visitInsn(IRETURN);
        test.visitMaxs(0, 0);
        test.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
