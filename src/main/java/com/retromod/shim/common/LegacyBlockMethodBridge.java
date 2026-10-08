/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Restores block methods that 1.21.2 removed or reshaped, for mods built against 1.21.1 or older.
 *
 * <p>{@code Properties.dropsLike(Block)}: up to 1.21.1 {@code dropsLike} copied another block's loot
 * table key into the properties, so a wall torch or a potted plant dropped what its base block
 * drops. 1.21.2 replaced it with
 * {@code overrideLootTable(Optional<ResourceKey<LootTable>>)}, and the source block's key now comes
 * from {@code Block.getLootTable()}, which returns that same optional. A 1.21.1 mod calling the old
 * method dies with {@code NoSuchMethodError} while it registers its blocks. Bountiful Fares does
 * this for its wall-mounted and potted variants, and the failure stopped every later block from
 * registering.
 *
 * <p>{@code BlockState.getOffset(BlockGetter, BlockPos)}: 1.21.2 dropped the unused
 * {@code BlockGetter}, leaving {@code getOffset(BlockPos)}. The dropped argument is the first one, so
 * the trailing argument-drop redirect cannot express it. Bountiful Fares' hanging fruit call it from
 * {@code getShape}, which Minecraft runs while caching block shapes at registration.
 *
 * <p>Light and direction changes from the same release: {@code BlockState.getLightBlock(BlockGetter,
 * BlockPos)} lost both arguments, {@code Direction.getNormal()} became {@code getUnitVec3i()}, and
 * {@code BlockGetter.getMaxLightLevel()} was removed because it always returned 15. The light
 * accessor is {@code getLightBlock()} on 1.21.2 to 1.21.11 and {@code getLightDampening()} from
 * 26.1, so the redirect names whichever one the host declares.
 *
 * <p>The helpers are generated because their descriptors name Minecraft types, and the class is
 * embedded into each mod that uses it.
 */
public final class LegacyBlockMethodBridge {

    private LegacyBlockMethodBridge() {}

    public static final String INTERNAL =
            "com/retromod/shim/common/embedded/LegacyBlockMethodBridge";

    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String DROPS_LIKE_DESC = "(L" + BLOCK + ";)L" + PROPS + ";";
    static final String HELPER_DESC = "(L" + PROPS + ";L" + BLOCK + ";)L" + PROPS + ";";

    static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String STATE_BASE =
            "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    static final String GETTER = "net/minecraft/world/level/BlockGetter";
    static final String POS = "net/minecraft/core/BlockPos";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String OLD_OFFSET_DESC = "(L" + GETTER + ";L" + POS + ";)L" + VEC3 + ";";
    static final String DIRECTION = "net/minecraft/core/Direction";
    static final String VEC3I_DESC = "()Lnet/minecraft/core/Vec3i;";
    static final String MAX_LIGHT_HELPER_DESC = "(Ljava/lang/Object;)I";
    /** The light level {@code getMaxLightLevel()} always returned before it was removed. */
    private static final int MAX_LIGHT_LEVEL = 15;
    static final String OFFSET_HELPER_DESC =
            "(L" + STATE_BASE + ";L" + GETTER + ";L" + POS + ";)L" + VEC3 + ";";

    /** Registers the helper and routes the removed calls to it. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, RetromodVersion.TARGET_MC_VERSION);
    }

    /** Registers the helper for {@code hostVersion}, which decides the light accessor's name. */
    static void register(RetromodTransformer transformer, String hostVersion) {
        String lightAccessor = lightAccessorName(hostVersion);
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(INTERNAL, generate());
        }
        transformer.registerMethodRedirect(PROPS, "dropsLike", DROPS_LIKE_DESC,
                INTERNAL, "dropsLike", HELPER_DESC);
        for (String owner : new String[]{STATE, STATE_BASE}) {
            transformer.registerMethodRedirect(owner, "getOffset", OLD_OFFSET_DESC,
                    INTERNAL, "getOffset", OFFSET_HELPER_DESC);
            transformer.registerArgDropMethodRedirect(owner, "getLightBlock",
                    "(L" + GETTER + ";L" + POS + ";)I", owner, lightAccessor, "()I");
        }
        transformer.registerMethodRedirect(DIRECTION, "getNormal", VEC3I_DESC,
                DIRECTION, "getUnitVec3i", VEC3I_DESC);
        for (String owner : new String[]{GETTER, "net/minecraft/world/level/LevelReader",
                "net/minecraft/world/level/Level"}) {
            transformer.registerMethodRedirect(owner, "getMaxLightLevel", "()I",
                    INTERNAL, "maxLightLevel", MAX_LIGHT_HELPER_DESC);
        }
    }

    /** The argument-free light accessor's name on {@code hostVersion}. */
    static String lightAccessorName(String hostVersion) {
        return RetromodVersion.mcVersionExceeds("26.1", hostVersion)
                ? "getLightBlock" : "getLightDampening";
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);

        // static Properties dropsLike(Properties p, Block b) {
        //     return p.overrideLootTable(b.getLootTable()); }
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "dropsLike", HELPER_DESC,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "getLootTable", "()Ljava/util/Optional;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, PROPS, "overrideLootTable",
                "(Ljava/util/Optional;)L" + PROPS + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // static Vec3 getOffset(BlockStateBase state, BlockGetter unused, BlockPos pos) {
        //     return state.getOffset(pos); }
        MethodVisitor offset = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getOffset",
                OFFSET_HELPER_DESC, null, null);
        offset.visitCode();
        offset.visitVarInsn(ALOAD, 0);
        offset.visitVarInsn(ALOAD, 2);
        offset.visitMethodInsn(INVOKEVIRTUAL, STATE_BASE, "getOffset",
                "(L" + POS + ";)L" + VEC3 + ";", false);
        offset.visitInsn(ARETURN);
        offset.visitMaxs(0, 0);
        offset.visitEnd();

        // static int maxLightLevel(Object level) { return 15; }
        MethodVisitor maxLight = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "maxLightLevel",
                MAX_LIGHT_HELPER_DESC, null, null);
        maxLight.visitCode();
        maxLight.visitIntInsn(BIPUSH, MAX_LIGHT_LEVEL);
        maxLight.visitInsn(IRETURN);
        maxLight.visitMaxs(0, 0);
        maxLight.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
