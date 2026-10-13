/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyEnchantmentRarity;
import com.retromod.shim.common.embedded.LegacyEnchantmentSupport;
import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import com.retromod.shim.forge.embedded.LegacyEnchantmentType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Rebuilds enchantments that a mod built before 1.21 defines in code, on Forge and NeoForge hosts
 * from 1.21.
 *
 * <p>1.21 made {@code Enchantment} a final record read from data packs and removed its rarity and
 * category types, so a mod's enchantment class failed to load with
 * {@code IncompatibleClassChangeError} and every call that took an enchantment failed with it.
 * In a jar written against the old class, {@code Enchantment} becomes a generated class with the
 * old constructor, fields, and overridable methods and their old defaults, and the rarity and
 * category become stand-ins that carry the weight and the items. Registration records the
 * enchantment for a generated data pack, and a level lookup matches the item's enchantments by id.
 * A vanilla constant such as {@code Enchantments.SILK_TOUCH} reads as a stand-in for that id.
 *
 * <p>What the old class did in code, such as {@code doPostAttack} or {@code getDamageProtection},
 * is not called by the game, because 1.21 runs data-driven effects instead. A mod that reads the
 * level itself and acts on it, as most do from their own event handlers, keeps working.
 */
public final class LegacyEnchantmentBridge {

    static final String OLD = "net/minecraft/world/item/enchantment/Enchantment";
    static final String OLD_1122 = "net/minecraft/enchantment/Enchantment";
    public static final String LEGACY = "com/retromod/generated/LegacyEnchantment";
    public static final String HOLDER = "com/retromod/generated/LegacyEnchantmentHolder";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyEnchantmentSupport";
    static final String RARITY = "com/retromod/shim/common/embedded/LegacyEnchantmentRarity";
    static final String TYPE = "com/retromod/shim/forge/embedded/LegacyEnchantmentType";
    static final String PACK_SOURCE = "com/retromod/shim/forge/embedded/LegacyDataPackSource";

    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String SLOT = "net/minecraft/world/entity/EquipmentSlot";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String DEFERRED_HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    private static final String DEFERRED_REGISTER = "net/neoforged/neoforge/registries/DeferredRegister";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String L_LEGACY = "L" + LEGACY + ";";
    private static final String L_RARITY = "L" + RARITY + ";";
    private static final String L_TYPE = "L" + TYPE + ";";
    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_OBJECT = "Ljava/lang/Object;";
    private static final String L_STRING = "Ljava/lang/String;";
    static final String CONSTRUCTOR = "(" + L_RARITY + L_TYPE + "[L" + SLOT + ";)V";

    /** Classes whose presence marks a jar as written against the old enchantment class. */
    private static final List<String> OLD_MARKERS = List.of(
            "net/minecraft/world/item/enchantment/Enchantment$Rarity",
            "net/minecraft/world/item/enchantment/EnchantmentCategory");

    private LegacyEnchantmentBridge() {}

    /** 1.21 and newer, on a host that runs mods under Mojang names. */
    static boolean supportsHost(String host) {
        return host != null && RetromodVersion.compareMcVersions(host, "1.21") >= 0
                && (com.retromod.util.McReflect.isNeoForge() || com.retromod.util.McReflect.isForge()
                        || RetromodVersion.isUnobfuscatedTarget(host));
    }

    public static void register(RetromodTransformer transformer) {
        if (!supportsHost(RetromodVersion.TARGET_MC_VERSION)) return;
        registerRedirects(transformer);
    }

    public static void registerRedirects(RetromodTransformer transformer) {
        registerSynthetics(transformer);
        transformer.registerClassRedirect(OLD + "$Rarity", RARITY);
        transformer.registerClassRedirect("net/minecraft/world/item/enchantment/EnchantmentCategory", TYPE);
        transformer.registerClassRedirect(OLD_1122, LEGACY);
        transformer.registerClassRedirect(OLD_1122 + "$Rarity", RARITY);
        transformer.registerMethodRedirect("net/minecraft/world/item/CreativeModeTab", "setEnchantmentCategories",
                "([" + L_TYPE + ")Lnet/minecraft/world/item/CreativeModeTab;", TYPE, "keepTab",
                "(" + L_OBJECT + L_OBJECT + ")" + L_OBJECT, true);
        // Forge's EnchantmentCategory.create(name, predicate) for a mod's own item category.
        transformer.registerMethodRedirect(TYPE, "create", "(" + L_STRING + "Ljava/util/function/Predicate;)" + L_TYPE,
                TYPE, "create", "(" + L_STRING + L_OBJECT + ")" + L_TYPE);

        String helper = "(" + L_OBJECT + L_OBJECT + ")I";
        String helperOwner = "net/minecraft/world/item/enchantment/EnchantmentHelper";
        transformer.registerMethodRedirect(helperOwner, "getItemEnchantmentLevel", "(" + L_LEGACY + L_STACK + ")I",
                SUPPORT, "level", helper);
        transformer.registerMethodRedirect(helperOwner, "getEnchantmentLevel", "(" + L_LEGACY + "L" + LIVING + ";)I",
                SUPPORT, "levelOn", helper);
        transformer.registerMethodRedirect(ITEM_STACK, "getEnchantmentLevel", "(" + L_LEGACY + ")I",
                SUPPORT, "stackLevel", helper, true);
        transformer.registerMethodRedirect(ITEM_STACK, "enchant", "(" + L_LEGACY + "I)V",
                SUPPORT, "enchant", "(" + L_OBJECT + L_OBJECT + "I)V", true);
        transformer.registerStaticFieldTypeDrift("net/minecraft/world/item/enchantment/Enchantments", L_LEGACY,
                "L" + RESOURCE_KEY + ";", LEGACY, "fromKey", "(L" + RESOURCE_KEY + ";)" + L_LEGACY);
    }

    /** The generated classes and the compiled helpers they call, for every path that can reach them. */
    public static void registerSynthetics(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyEnchantmentSupport.class);
        SyntheticEmbedder.registerClassResource(transformer, RARITY, LegacyEnchantmentRarity.class);
        SyntheticEmbedder.registerClassResource(transformer, TYPE, LegacyEnchantmentType.class);
        SyntheticEmbedder.registerClassResource(transformer, PACK_SOURCE, LegacyDataPackSource.class);
        transformer.registerSyntheticClass(LEGACY, generateEnchantment());
        if (com.retromod.util.McReflect.isNeoForge()) transformer.registerSyntheticClass(HOLDER, generateHolder());
    }

    // ---- per-jar rename ----------------------------------------------------------------------

    /**
     * Renames {@code Enchantment} to the generated class in every class of a jar written against
     * the old class, before the class pass. A jar from 1.21 or newer uses the record and is left
     * alone; the whole jar gets one answer, so a field and the code reading it agree.
     *
     * @return the number of classes rewritten
     */
    public static int renameInLegacyJar(RetromodTransformer transformer, Path root) throws IOException {
        if (!transformer.getSyntheticClasses().containsKey(LEGACY)) return 0;
        List<Path> classFiles;
        try (var stream = Files.walk(root)) {
            classFiles = stream.filter(p -> p.toString().endsWith(".class")).toList();
        }
        boolean legacy = false;
        for (Path file : classFiles) {
            if (usesOldEnchantment(Files.readAllBytes(file))) {
                legacy = true;
                break;
            }
        }
        if (!legacy) return 0;
        int renamed = 0;
        for (Path file : classFiles) {
            byte[] original = Files.readAllBytes(file);
            byte[] updated = rename(original);
            if (updated != original) {
                Files.write(file, updated);
                renamed++;
            }
        }
        return renamed;
    }

    /** Extends the old class, or names a type only the old enchantment API had. */
    static boolean usesOldEnchantment(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (OLD_MARKERS.stream().anyMatch(text::contains)) return true;
        return OLD.equals(new ClassReader(classBytes).getSuperName());
    }

    static byte[] rename(byte[] classBytes) {
        if (!new String(classBytes, StandardCharsets.ISO_8859_1).contains(OLD)) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor remapper = new ClassRemapper(writer, new SimpleRemapper(Map.of(OLD, LEGACY)));
        new ClassReader(classBytes).accept(remapper, 0);
        return writer.toByteArray();
    }

    // ---- generated classes -------------------------------------------------------------------

    /**
     * The old {@code Enchantment}: rarity, category, and slots, the overridable methods with their
     * 1.20.1 defaults, the 1.12 name setter, and a constructor for a stand-in of a registered id.
     */
    static byte[] generateEnchantment() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, LEGACY, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "rarity", L_RARITY, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "category", L_TYPE, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "slots", "[L" + SLOT + ";", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "id", L_STRING, null, null).visitEnd();
        cw.visitField(ACC_PROTECTED, "descriptionId", L_STRING, null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PROTECTED, "<init>", CONSTRUCTOR, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        putField(ctor, 1, "rarity", L_RARITY);
        putField(ctor, 2, "category", L_TYPE);
        putField(ctor, 3, "slots", "[L" + SLOT + ";");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // A stand-in for an enchantment known only by id, such as a vanilla constant.
        MethodVisitor view = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_STRING + ")V", null, null);
        view.visitCode();
        view.visitVarInsn(ALOAD, 0);
        view.visitFieldInsn(GETSTATIC, RARITY, "COMMON", L_RARITY);
        view.visitFieldInsn(GETSTATIC, TYPE, "BREAKABLE", L_TYPE);
        view.visitInsn(ICONST_0);
        view.visitTypeInsn(ANEWARRAY, SLOT);
        view.visitMethodInsn(INVOKESPECIAL, LEGACY, "<init>", CONSTRUCTOR, false);
        view.visitVarInsn(ALOAD, 0);
        view.visitVarInsn(ALOAD, 1);
        view.visitFieldInsn(PUTFIELD, LEGACY, "id", L_STRING);
        view.visitInsn(RETURN);
        view.visitMaxs(0, 0);
        view.visitEnd();

        MethodVisitor fromKey = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fromKey", "(L" + RESOURCE_KEY + ";)" + L_LEGACY, null, null);
        fromKey.visitCode();
        fromKey.visitVarInsn(ALOAD, 0);
        fromKey.visitLdcInsn(Type.getObjectType(LEGACY));
        fromKey.visitMethodInsn(INVOKESTATIC, SUPPORT, "view", "(" + L_OBJECT + "Ljava/lang/Class;)" + L_OBJECT, false);
        fromKey.visitTypeInsn(CHECKCAST, LEGACY);
        fromKey.visitInsn(ARETURN);
        fromKey.visitMaxs(0, 0);
        fromKey.visitEnd();

        constant(cw, "getMaxLevel", "()I", 1);
        constant(cw, "getMinLevel", "()I", 1);
        constant(cw, "isTreasureOnly", "()Z", 0);
        constant(cw, "isCurse", "()Z", 0);
        constant(cw, "isTradeable", "()Z", 1);
        constant(cw, "isDiscoverable", "()Z", 1);
        constant(cw, "isAllowedOnBooks", "()Z", 1);

        // getMinCost(level) = 1 + level * 10; getMaxCost(level) = getMinCost(level) + 5, as 1.20.1.
        MethodVisitor min = cw.visitMethod(ACC_PUBLIC, "getMinCost", "(I)I", null, null);
        min.visitCode();
        min.visitInsn(ICONST_1);
        min.visitVarInsn(ILOAD, 1);
        min.visitIntInsn(BIPUSH, 10);
        min.visitInsn(IMUL);
        min.visitInsn(IADD);
        min.visitInsn(IRETURN);
        min.visitMaxs(0, 0);
        min.visitEnd();
        MethodVisitor max = cw.visitMethod(ACC_PUBLIC, "getMaxCost", "(I)I", null, null);
        max.visitCode();
        max.visitVarInsn(ALOAD, 0);
        max.visitVarInsn(ILOAD, 1);
        max.visitMethodInsn(INVOKEVIRTUAL, LEGACY, "getMinCost", "(I)I", false);
        max.visitInsn(ICONST_5);
        max.visitInsn(IADD);
        max.visitInsn(IRETURN);
        max.visitMaxs(0, 0);
        max.visitEnd();

        MethodVisitor rarity = cw.visitMethod(ACC_PUBLIC, "getRarity", "()" + L_RARITY, null, null);
        rarity.visitCode();
        rarity.visitVarInsn(ALOAD, 0);
        rarity.visitFieldInsn(GETFIELD, LEGACY, "rarity", L_RARITY);
        rarity.visitInsn(ARETURN);
        rarity.visitMaxs(0, 0);
        rarity.visitEnd();

        for (String name : new String[] {"canEnchant", "canApplyAtEnchantingTable"}) {
            MethodVisitor can = cw.visitMethod(ACC_PUBLIC, name, "(" + L_STACK + ")Z", null, null);
            can.visitCode();
            can.visitVarInsn(ALOAD, 0);
            can.visitVarInsn(ALOAD, 1);
            can.visitMethodInsn(INVOKESTATIC, SUPPORT, "canEnchant", "(" + L_OBJECT + L_OBJECT + ")Z", false);
            can.visitInsn(IRETURN);
            can.visitMaxs(0, 0);
            can.visitEnd();
        }

        // checkCompatibility(other) = this != other; isCompatibleWith asks both sides.
        MethodVisitor check = cw.visitMethod(ACC_PUBLIC, "checkCompatibility", "(" + L_LEGACY + ")Z", null, null);
        check.visitCode();
        Label same = new Label();
        check.visitVarInsn(ALOAD, 0);
        check.visitVarInsn(ALOAD, 1);
        check.visitJumpInsn(IF_ACMPEQ, same);
        check.visitInsn(ICONST_1);
        check.visitInsn(IRETURN);
        check.visitLabel(same);
        check.visitInsn(ICONST_0);
        check.visitInsn(IRETURN);
        check.visitMaxs(0, 0);
        check.visitEnd();
        MethodVisitor compatible = cw.visitMethod(ACC_PUBLIC, "isCompatibleWith", "(" + L_LEGACY + ")Z", null, null);
        compatible.visitCode();
        Label no = new Label();
        compatible.visitVarInsn(ALOAD, 0);
        compatible.visitVarInsn(ALOAD, 1);
        compatible.visitMethodInsn(INVOKEVIRTUAL, LEGACY, "checkCompatibility", "(" + L_LEGACY + ")Z", false);
        compatible.visitJumpInsn(IFEQ, no);
        compatible.visitVarInsn(ALOAD, 1);
        compatible.visitVarInsn(ALOAD, 0);
        compatible.visitMethodInsn(INVOKEVIRTUAL, LEGACY, "checkCompatibility", "(" + L_LEGACY + ")Z", false);
        compatible.visitInsn(IRETURN);
        compatible.visitLabel(no);
        compatible.visitInsn(ICONST_0);
        compatible.visitInsn(IRETURN);
        compatible.visitMaxs(0, 0);
        compatible.visitEnd();

        for (String name : new String[] {"getDescriptionId", "getOrCreateDescriptionId", "getName"}) {
            MethodVisitor description = cw.visitMethod(ACC_PUBLIC, name, "()" + L_STRING, null, null);
            description.visitCode();
            description.visitVarInsn(ALOAD, 0);
            description.visitMethodInsn(INVOKESTATIC, SUPPORT, "descriptionId", "(" + L_OBJECT + ")" + L_STRING, false);
            description.visitInsn(ARETURN);
            description.visitMaxs(0, 0);
            description.visitEnd();
        }

        // 1.12.2 setName("spikes") chose the translation key enchantment.spikes.
        MethodVisitor setName = cw.visitMethod(ACC_PUBLIC, "setName", "(" + L_STRING + ")" + L_LEGACY, null, null);
        setName.visitCode();
        setName.visitVarInsn(ALOAD, 0);
        setName.visitLdcInsn("enchantment.");
        setName.visitVarInsn(ALOAD, 1);
        setName.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(" + L_STRING + ")" + L_STRING, false);
        setName.visitFieldInsn(PUTFIELD, LEGACY, "descriptionId", L_STRING);
        setName.visitVarInsn(ALOAD, 0);
        setName.visitInsn(ARETURN);
        setName.visitMaxs(0, 0);
        setName.visitEnd();

        constant(cw, "getDamageProtection", "(ILnet/minecraft/world/damagesource/DamageSource;)I", 0);
        empty(cw, "doPostAttack", "(L" + LIVING + ";L" + ENTITY + ";I)V");
        empty(cw, "doPostHurt", "(L" + LIVING + ";L" + ENTITY + ";I)V");

        MethodVisitor text = cw.visitMethod(ACC_PUBLIC, "toString", "()" + L_STRING, null, null);
        text.visitCode();
        text.visitVarInsn(ALOAD, 0);
        text.visitFieldInsn(GETFIELD, LEGACY, "id", L_STRING);
        text.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(" + L_OBJECT + ")" + L_STRING, false);
        text.visitInsn(ARETURN);
        text.visitMaxs(0, 0);
        text.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A holder that answers with a recorded enchantment. NeoForge never builds entries of a
     * data-driven registry, so the mod's {@code RegistryObject} or {@code DeferredHolder} would
     * stay unbound; this one returns the object its supplier built.
     */
    static byte[] generateHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, HOLDER, null, DEFERRED_HOLDER, null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "enchantment", L_OBJECT, null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_OBJECT + L_OBJECT + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitTypeInsn(CHECKCAST, RESOURCE_KEY);
        ctor.visitMethodInsn(INVOKESPECIAL, DEFERRED_HOLDER, "<init>", "(L" + RESOURCE_KEY + ";)V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitFieldInsn(PUTFIELD, HOLDER, "enchantment", L_OBJECT);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (String name : new String[] {"value", "get"}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + L_OBJECT, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, HOLDER, "enchantment", L_OBJECT);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        constant(cw, "isBound", "()Z", 1);

        // static DeferredHolder of(DeferredRegister dr, String name, Supplier sup): build, record, hold.
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of",
                "(L" + DEFERRED_REGISTER + ";" + L_STRING + "Ljava/util/function/Supplier;)L" + DEFERRED_HOLDER + ";", null, null);
        of.visitCode();
        of.visitVarInsn(ALOAD, 2);
        of.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()" + L_OBJECT, true);
        of.visitVarInsn(ASTORE, 3);
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKEVIRTUAL, DEFERRED_REGISTER, "getNamespace", "()" + L_STRING, false);
        of.visitLdcInsn(":");
        of.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(" + L_STRING + ")" + L_STRING, false);
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(" + L_STRING + ")" + L_STRING, false);
        of.visitVarInsn(ASTORE, 4);
        of.visitVarInsn(ALOAD, 4);
        of.visitVarInsn(ALOAD, 3);
        of.visitMethodInsn(INVOKESTATIC, SUPPORT, "record", "(" + L_STRING + L_OBJECT + ")V", false);
        of.visitTypeInsn(NEW, HOLDER);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKEVIRTUAL, DEFERRED_REGISTER, "getRegistryKey", "()L" + RESOURCE_KEY + ";", false);
        of.visitVarInsn(ALOAD, 4);
        of.visitMethodInsn(INVOKESTATIC, SUPPORT, "key", "(" + L_OBJECT + L_STRING + ")" + L_OBJECT, false);
        of.visitVarInsn(ALOAD, 3);
        of.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(" + L_OBJECT + L_OBJECT + ")V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void putField(MethodVisitor mv, int slot, String name, String desc) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, slot);
        mv.visitFieldInsn(PUTFIELD, LEGACY, name, desc);
    }

    private static void constant(ClassWriter cw, String name, String desc, int value) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(value == 0 ? ICONST_0 : ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void empty(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
