/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Builds the items whose constructors 1.21.2 and later reshaped, from a 1.21.1 call.
 *
 * <p>A mod registers these items inside one registration method, so a single
 * {@code NoSuchMethodError} stops every later item, block entity, feature and trunk placer of the
 * mod from registering. The factories keep each item's meaning:
 *
 * <ul>
 *   <li>{@code SignItem(Properties, Block, Block)} takes its properties last on 26.1.</li>
 *   <li>{@code StandingAndWallBlockItem(Block, Block, Properties, Direction)} takes the attachment
 *       direction before the properties on 26.1.</li>
 *   <li>{@code Potion(MobEffectInstance...)} needs a name on 26.1. The name keys the potion's
 *       translation. Before 1.21.2 an unnamed potion used its registry path, which is not known
 *       while the potion is built, so the factory takes the first effect's path from its
 *       description id. Long and strong variants then share one key, as vanilla's long and strong
 *       swiftness share {@code swiftness}. A potion named differently from its effect gets the
 *       effect's key. A potion without effects is named {@code empty}, like vanilla's.</li>
 *   <li>{@code Boat.Type} was removed in 1.21.2, when each wood got its own boat entity type.
 *       Registering a new entity type needs a renderer and a model layer on the client, which a
 *       bytecode bridge cannot provide, so a modded boat type becomes a named stand-in and its
 *       boat item places the vanilla oak boat. The substitution is logged once per boat type.</li>
 * </ul>
 *
 * <p>The shapes were checked against the 26.1.2 jar, so the bridge registers nothing on older
 * hosts. 26.3 removed {@code SignItem}, so an old sign item there still needs a real port.
 */
public final class LegacyItemConstructorBridge {

    private LegacyItemConstructorBridge() {}

    public static final String INTERNAL =
            "com/retromod/shim/common/embedded/LegacyItemConstructorBridge";
    public static final String BOAT_TYPE =
            "com/retromod/shim/common/embedded/LegacyBoatType";

    static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    static final String ITEM_PROPS = "Lnet/minecraft/world/item/Item$Properties;";
    static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    static final String SIGN_ITEM = "net/minecraft/world/item/SignItem";
    static final String STANDING_ITEM = "net/minecraft/world/item/StandingAndWallBlockItem";
    static final String POTION = "net/minecraft/world/item/alchemy/Potion";
    static final String EFFECT_INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    static final String EFFECTS_DESC = "[L" + EFFECT_INSTANCE + ";";
    static final String BOAT_ITEM = "net/minecraft/world/item/BoatItem";
    static final String OLD_BOAT_TYPE = "net/minecraft/world/entity/vehicle/Boat$Type";
    static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";

    static final String OLD_SIGN_DESC = "(" + ITEM_PROPS + BLOCK + BLOCK + ")V";
    static final String OLD_STANDING_DESC = "(" + BLOCK + BLOCK + ITEM_PROPS + DIRECTION + ")V";
    static final String OLD_POTION_DESC = "(" + EFFECTS_DESC + ")V";
    static final String OLD_BOAT_DESC = "(ZL" + OLD_BOAT_TYPE + ";" + ITEM_PROPS + ")V";
    static final String STAND_IN_BOAT_DESC = "(ZL" + BOAT_TYPE + ";" + ITEM_PROPS + ")V";

    /** Registers the factories on 26.1 and newer hosts. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, RetromodVersion.TARGET_MC_VERSION);
    }

    static void register(RetromodTransformer transformer, String hostVersion) {
        if (!RetromodVersion.isUnobfuscatedTarget(hostVersion)) return;
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(BOAT_TYPE, generateBoatType());
            transformer.registerSyntheticClass(INTERNAL, generate());
        }
        transformer.registerConstructorRedirect(SIGN_ITEM, OLD_SIGN_DESC,
                INTERNAL, "signItem", OLD_SIGN_DESC.replace(")V", ")L" + SIGN_ITEM + ";"));
        transformer.registerConstructorRedirect(STANDING_ITEM, OLD_STANDING_DESC,
                INTERNAL, "standingAndWallBlockItem",
                OLD_STANDING_DESC.replace(")V", ")L" + STANDING_ITEM + ";"));
        transformer.registerConstructorRedirect(POTION, OLD_POTION_DESC,
                INTERNAL, "potion", "(" + EFFECTS_DESC + ")L" + POTION + ";");

        transformer.registerClassRedirect(OLD_BOAT_TYPE, BOAT_TYPE);
        // The class redirect can run before the call is matched, so both spellings are listed.
        for (String desc : new String[]{OLD_BOAT_DESC, STAND_IN_BOAT_DESC}) {
            transformer.registerConstructorRedirect(BOAT_ITEM, desc,
                    INTERNAL, "boatItem", STAND_IN_BOAT_DESC.replace(")V", ")L" + BOAT_ITEM + ";"));
        }
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);

        // static SignItem signItem(Properties p, Block standing, Block wall) {
        //     return new SignItem(standing, wall, p); }
        MethodVisitor sign = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "signItem",
                OLD_SIGN_DESC.replace(")V", ")L" + SIGN_ITEM + ";"), null, null);
        sign.visitCode();
        sign.visitTypeInsn(NEW, SIGN_ITEM);
        sign.visitInsn(DUP);
        sign.visitVarInsn(ALOAD, 1);
        sign.visitVarInsn(ALOAD, 2);
        sign.visitVarInsn(ALOAD, 0);
        sign.visitMethodInsn(INVOKESPECIAL, SIGN_ITEM, "<init>",
                "(" + BLOCK + BLOCK + ITEM_PROPS + ")V", false);
        sign.visitInsn(ARETURN);
        sign.visitMaxs(0, 0);
        sign.visitEnd();

        // static StandingAndWallBlockItem standingAndWallBlockItem(Block s, Block w, Properties p,
        //     Direction d) { return new StandingAndWallBlockItem(s, w, d, p); }
        MethodVisitor standing = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                "standingAndWallBlockItem",
                OLD_STANDING_DESC.replace(")V", ")L" + STANDING_ITEM + ";"), null, null);
        standing.visitCode();
        standing.visitTypeInsn(NEW, STANDING_ITEM);
        standing.visitInsn(DUP);
        standing.visitVarInsn(ALOAD, 0);
        standing.visitVarInsn(ALOAD, 1);
        standing.visitVarInsn(ALOAD, 3);
        standing.visitVarInsn(ALOAD, 2);
        standing.visitMethodInsn(INVOKESPECIAL, STANDING_ITEM, "<init>",
                "(" + BLOCK + BLOCK + DIRECTION + ITEM_PROPS + ")V", false);
        standing.visitInsn(ARETURN);
        standing.visitMaxs(0, 0);
        standing.visitEnd();

        generatePotion(cw);
        generateBoatItem(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Potion potion(MobEffectInstance[] effects)}: named after the first effect. */
    private static void generatePotion(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "potion",
                "(" + EFFECTS_DESC + ")L" + POTION + ";", null, null);
        mv.visitCode();
        mv.visitLdcInsn("empty");
        mv.visitVarInsn(ASTORE, 1);
        Label named = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitJumpInsn(IFEQ, named);
        // String id = effects[0].getDescriptionId(); name = id.substring(id.lastIndexOf('.') + 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(AALOAD);
        mv.visitMethodInsn(INVOKEVIRTUAL, EFFECT_INSTANCE, "getDescriptionId",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitIntInsn(BIPUSH, '.');
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "substring",
                "(I)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitLabel(named);
        mv.visitTypeInsn(NEW, POTION);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, POTION, "<init>",
                "(Ljava/lang/String;" + EFFECTS_DESC + ")V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static BoatItem boatItem(boolean chest, LegacyBoatType type, Properties p)}. */
    private static void generateBoatItem(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "boatItem",
                STAND_IN_BOAT_DESC.replace(")V", ")L" + BOAT_ITEM + ";"), null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BOAT_TYPE, "warnStandIn", "()V", false);
        mv.visitTypeInsn(NEW, BOAT_ITEM);
        mv.visitInsn(DUP);
        Label plain = new Label();
        Label typed = new Label();
        mv.visitVarInsn(ILOAD, 0);
        mv.visitJumpInsn(IFEQ, plain);
        mv.visitFieldInsn(GETSTATIC, ENTITY_TYPE, "OAK_CHEST_BOAT", "L" + ENTITY_TYPE + ";");
        mv.visitJumpInsn(GOTO, typed);
        mv.visitLabel(plain);
        mv.visitFieldInsn(GETSTATIC, ENTITY_TYPE, "OAK_BOAT", "L" + ENTITY_TYPE + ";");
        mv.visitLabel(typed);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, BOAT_ITEM, "<init>",
                "(L" + ENTITY_TYPE + ";" + ITEM_PROPS + ")V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * The stand-in for a removed {@code Boat.Type}: {@code byName} and {@code getName} keep working,
     * and {@code warnStandIn} reports the oak substitution once per type.
     */
    public static byte[] generateBoatType() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, BOAT_TYPE, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "warned", "Z", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;)V",
                null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, BOAT_TYPE, "name", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor byName = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "byName",
                "(Ljava/lang/String;)L" + BOAT_TYPE + ";", null, null);
        byName.visitCode();
        byName.visitTypeInsn(NEW, BOAT_TYPE);
        byName.visitInsn(DUP);
        byName.visitVarInsn(ALOAD, 0);
        byName.visitMethodInsn(INVOKESPECIAL, BOAT_TYPE, "<init>", "(Ljava/lang/String;)V", false);
        byName.visitInsn(ARETURN);
        byName.visitMaxs(0, 0);
        byName.visitEnd();

        for (String getter : new String[]{"getName", "toString", "getSerializedName"}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, getter, "()Ljava/lang/String;",
                    null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, BOAT_TYPE, "name", "Ljava/lang/String;");
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        MethodVisitor warn = cw.visitMethod(ACC_PUBLIC | ACC_SYNCHRONIZED, "warnStandIn", "()V",
                null, null);
        warn.visitCode();
        Label done = new Label();
        warn.visitVarInsn(ALOAD, 0);
        warn.visitFieldInsn(GETFIELD, BOAT_TYPE, "warned", "Z");
        warn.visitJumpInsn(IFNE, done);
        warn.visitVarInsn(ALOAD, 0);
        warn.visitInsn(ICONST_1);
        warn.visitFieldInsn(PUTFIELD, BOAT_TYPE, "warned", "Z");
        warn.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        warn.visitLdcInsn("[Retromod] Boat type ");
        warn.visitVarInsn(ALOAD, 0);
        warn.visitFieldInsn(GETFIELD, BOAT_TYPE, "name", "Ljava/lang/String;");
        warn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        warn.visitLdcInsn(" has no boat entity on this Minecraft version. Its boat items place"
                + " a vanilla oak boat.");
        warn.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        warn.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);
        warn.visitLabel(done);
        warn.visitInsn(RETURN);
        warn.visitMaxs(0, 0);
        warn.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
