/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-1.18.2 {@code Biome.Category} bridge for Fabric on pre-26.1 hosts (intermediary names).
 *
 * <p>{@code Biome.Category} ({@code class_1959$class_1961}) and its accessor
 * {@code class_1959.method_8688()} were deleted in 1.18.2 when biome categories moved to
 * data-driven tags, so pre-1.18.2 spawn-registration mods that touch the enum in their own
 * {@code <clinit>} crash with {@code NoClassDefFoundError}/{@code NoSuchMethodError} before
 * onInitialize runs. We inject a stand-in enum, class-redirect every reference onto it, and
 * rewrite {@code method_8688} to a static that always returns the NONE slot. Category-keyed
 * spawn arrays then come back empty (the mod's mobs aren't added to vanilla pools) but the
 * mod loads; a tag-based spawn-injection bridge would be a far larger rewrite.
 */
public final class Pre1_18_2BiomeCategoryBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** Intermediary internal name of {@code Biome.Category}. */
    private static final String BIOME_CATEGORY = "net/minecraft/class_1959$class_1961";

    /** Intermediary internal name of {@code Biome}. */
    private static final String BIOME = "net/minecraft/class_1959";

    /** Intermediary name of {@code Biome.getCategory}. */
    private static final String GET_CATEGORY = "method_8688";

    /** Stand-in enum's internal name. */
    private static final String SELF = "com/retromod/generated/LegacyBiomeCategory";

    private static final String L_SELF = "L" + SELF + ";";
    private static final String L_BIOME = "L" + BIOME + ";";

    /**
     * Intermediary field IDs for {@code Biome.Category} from 1.16-era yarn
     * ({@code field_9354 .. field_9370}: BEACH, DESERT, EXTREME_HILLS, FOREST, ICY, JUNGLE,
     * MESA, MUSHROOM, NETHER, NONE, OCEAN, PLAINS, RIVER, SAVANNA, SWAMP, TAIGA, THEEND).
     * All 17 so any pre-1.18.2 mod's subset resolves.
     */
    private static final String[] CATEGORY_FIELDS = {
        "field_9354", "field_9355", "field_9356", "field_9357", "field_9358",
        "field_9359", "field_9360", "field_9361", "field_9362", "field_9363",
        "field_9364", "field_9365", "field_9366", "field_9367", "field_9368",
        "field_9369", "field_9370",
    };

    /** Constant returned from {@code getCategory(Biome)}: the NONE slot on 1.16 yarn. */
    private static final String NONE_FIELD = "field_9363";

    private Pre1_18_2BiomeCategoryBridge() {}

    /** Wire the synthetic, class redirect, and Biome.getCategory rewrite. */
    public static void register(RetromodTransformer transformer) {
        // Nothing to do when Biome isn't on the host (unit test / headless tool).
        if (!ClassResourceInspector.exists(BIOME)) {
            LOGGER.debug("Biome.Category support is not needed because class_1959 is unavailable");
            return;
        }

        transformer.registerSyntheticClass(SELF, generateLegacyBiomeCategory());
        transformer.registerClassRedirect(BIOME_CATEGORY, SELF);

        // Rewrite the deleted Biome.getCategory() to our static, moving the receiver into
        // the first arg. The redirect key uses the post-redirect return type (LegacyBiomeCategory):
        // the BIOME_CATEGORY class redirect already rewrote the call site's return type.
        transformer.registerMethodRedirect(
                BIOME, GET_CATEGORY, "()" + L_SELF,
                SELF, "getCategory", "(" + L_BIOME + ")" + L_SELF,
                true);

        LOGGER.debug("Added a {}-value replacement for the removed Biome.Category enum",
                CATEGORY_FIELDS.length);
    }

    /**
     * Generate an enum with one public static final field per legacy category ID, in the shape
     * javac emits for a plain enum (private {@code (String,int)} ctor, synthetic {@code $VALUES},
     * {@code values()}/{@code valueOf}, {@code <clinit>}), plus a static {@code getCategory(Biome)}
     * returning NONE that swaps in for the deleted accessor.
     */
    static byte[] generateLegacyBiomeCategory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                // The resolver runs without MC on the classpath in unit tests; fall back to Object.
                try { return super.getCommonSuperClass(t1, t2); }
                catch (Throwable t) { return "java/lang/Object"; }
            }
        };

        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM,
                SELF,
                "Ljava/lang/Enum<" + L_SELF + ">;",
                "java/lang/Enum",
                null);

        for (String field : CATEGORY_FIELDS) {
            cw.visitField(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                    field, L_SELF, null, null
            ).visitEnd();
        }

        cw.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "$VALUES", "[" + L_SELF, null, null
        ).visitEnd();

        MethodVisitor ctor = cw.visitMethod(
                Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>",
                "(Ljava/lang/String;I)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // public static T[] values() { return $VALUES.clone(); }
        MethodVisitor values = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[" + L_SELF, null, null);
        values.visitCode();
        values.visitFieldInsn(Opcodes.GETSTATIC, SELF, "$VALUES", "[" + L_SELF);
        values.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[" + L_SELF, "clone",
                "()Ljava/lang/Object;", false);
        values.visitTypeInsn(Opcodes.CHECKCAST, "[" + L_SELF);
        values.visitInsn(Opcodes.ARETURN);
        values.visitMaxs(0, 0);
        values.visitEnd();

        // public static T valueOf(String) { return (T) Enum.valueOf(T.class, name); }
        MethodVisitor valueOf = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf",
                "(Ljava/lang/String;)" + L_SELF, null, null);
        valueOf.visitCode();
        valueOf.visitLdcInsn(org.objectweb.asm.Type.getType(L_SELF));
        valueOf.visitVarInsn(Opcodes.ALOAD, 0);
        valueOf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        valueOf.visitTypeInsn(Opcodes.CHECKCAST, SELF);
        valueOf.visitInsn(Opcodes.ARETURN);
        valueOf.visitMaxs(0, 0);
        valueOf.visitEnd();

        // public static LegacyBiomeCategory getCategory(Biome) { return field_9363; }
        // Biome receiver is unused; every lookup collapses to NONE (see class javadoc).
        MethodVisitor getCat = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getCategory",
                "(" + L_BIOME + ")" + L_SELF, null, null);
        getCat.visitCode();
        getCat.visitFieldInsn(Opcodes.GETSTATIC, SELF, NONE_FIELD, L_SELF);
        getCat.visitInsn(Opcodes.ARETURN);
        getCat.visitMaxs(0, 0);
        getCat.visitEnd();

        // <clinit>: new <constant>; populate $VALUES.
        MethodVisitor clinit = cw.visitMethod(
                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (int i = 0; i < CATEGORY_FIELDS.length; i++) {
            String field = CATEGORY_FIELDS[i];
            clinit.visitTypeInsn(Opcodes.NEW, SELF);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitLdcInsn(field);
            pushInt(clinit, i);
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, "<init>",
                    "(Ljava/lang/String;I)V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, SELF, field, L_SELF);
        }
        pushInt(clinit, CATEGORY_FIELDS.length);
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, SELF);
        for (int i = 0; i < CATEGORY_FIELDS.length; i++) {
            clinit.visitInsn(Opcodes.DUP);
            pushInt(clinit, i);
            clinit.visitFieldInsn(Opcodes.GETSTATIC, SELF, CATEGORY_FIELDS[i], L_SELF);
            clinit.visitInsn(Opcodes.AASTORE);
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, SELF, "$VALUES", "[" + L_SELF);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pick the smallest-encoding int push for n (ICONST_0..5, BIPUSH, SIPUSH, LDC). */
    private static void pushInt(MethodVisitor mv, int n) {
        if (n >= -1 && n <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + n);
        } else if (n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, n);
        } else if (n >= Short.MIN_VALUE && n <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, n);
        } else {
            mv.visitLdcInsn(n);
        }
    }
}
