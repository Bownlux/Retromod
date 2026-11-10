/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Bridges the small members 26.2 removed or reshaped, for mods built for 26.1 or older. */
public final class Legacy262MemberBridge {

    static final String HELPER = "com/retromod/generated/Legacy262Members";
    static final String I18N = "net/minecraft/client/resources/language/I18n";
    static final String LANGUAGE = "net/minecraft/locale/Language";
    static final String EXISTS_DESC = "(Ljava/lang/String;)Z";
    static final String SURFACE_RULES = "net/minecraft/world/level/levelgen/SurfaceRules";
    static final String SURFACE_CONTEXT = SURFACE_RULES + "$Context";
    static final String OLD_IS_BIOME = "([Lnet/minecraft/resources/ResourceKey;)L" + SURFACE_RULES + "$ConditionSource;";
    static final String MATERIAL_RULES = "net/minecraft/world/level/levelgen/material/MaterialRules";
    static final String RULES_HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/LegacySurfaceRules";
    static final String SYSTEM_REPORT = "net/minecraft/SystemReport";
    static final String CRASH_DETAIL = "net/minecraft/CrashReportDetail";
    static final String SUPPLIER_DETAIL = "(Ljava/lang/String;Ljava/util/function/Supplier;)V";
    static final String HOST_DETAIL = "(Ljava/lang/String;L" + CRASH_DETAIL + ";)V";

    private Legacy262MemberBridge() {}

    /** Registers a bridge for each member this host lost. */
    public static void register(RetromodTransformer transformer) {
        if (lostI18nExists()) registerRedirects(transformer);
        ClassNode materialRules = ClassResourceInspector.read(MATERIAL_RULES);
        if (surfaceRulesTakeLookup(ClassResourceInspector.read(SURFACE_RULES), materialRules)) registerSurfaceRules(transformer);
        if (lostSupplierDetail(ClassResourceInspector.read(SYSTEM_REPORT))) registerSystemReport(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        // I18n.exists(key) went away with I18n's own copy of the language. Tooltip and config
        // screens call it to decide whether a translation is there before showing it.
        transformer.registerMethodRedirect(I18N, "exists", EXISTS_DESC, HELPER, "exists", EXISTS_DESC);
    }

    /** 26.2's surface rule members: {@code isBiome(keys)} and the context's biome supplier. */
    static void registerSurfaceRules(RetromodTransformer transformer) {
        // isBiome took the biome lookup. Biomes O' Plenty builds its rules while blocks register and
        // TerraBlender while a world generates, so the rebuilt condition keeps the keys and finds the
        // holders in each world it is applied in.
        RebuiltApiClasses.registerTree(transformer, RULES_HELPER);
        transformer.registerMethodRedirect(SURFACE_RULES, "isBiome", OLD_IS_BIOME, RULES_HELPER, "isBiome", OLD_IS_BIOME);
        // The context's biome supplier became a holder that getBiome() fills in. Only the old Supplier
        // read is rewritten; the field was private, so nothing wrote it.
        transformer.registerExactFieldRedirect(SURFACE_CONTEXT, "biome", "Ljava/util/function/Supplier;",
                RULES_HELPER, "biome", "(Ljava/lang/Object;)Ljava/util/function/Supplier;");
    }

    /** {@code SystemReport.setDetail(name, supplier)}, which 26.2 replaced with a crash report detail form. */
    static void registerSystemReport(RetromodTransformer transformer) {
        // Patchouli adds a line to every system report this way, so the missing method hid the report's own error.
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerMethodRedirect(SYSTEM_REPORT, "setDetail", SUPPLIER_DETAIL, HELPER, "setDetail",
                "(L" + SYSTEM_REPORT + ";" + SUPPLIER_DETAIL.substring(1), true);
    }

    /** Whether the host has 26.2's surface rules, whose isBiome takes a lookup; offline, a 26.2 target. */
    static boolean surfaceRulesTakeLookup(ClassNode rules, ClassNode materialRules) {
        // 26.3 replaced surface rules with material rules, which this bridge does not reach.
        if (rules == null && materialRules != null) return false;
        if (rules == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0
                    && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") < 0;
        }
        for (MethodNode method : rules.methods) {
            if (method.name.equals("isBiome") && method.desc.equals(OLD_IS_BIOME)) return false;
        }
        return true;
    }

    /** Whether the host's {@code SystemReport} lost {@code setDetail(String, Supplier)}; offline the version decides. */
    static boolean lostSupplierDetail(ClassNode report) {
        if (report == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0;
        for (MethodNode method : report.methods) {
            if (method.name.equals("setDetail") && method.desc.equals(SUPPLIER_DETAIL)) return false;
        }
        return true;
    }

    /** Whether the host's {@code I18n} no longer has {@code exists(String)}. */
    static boolean lostI18nExists() {
        ClassNode i18n = ClassResourceInspector.read(I18N);
        // Offline the host classes are not readable, so the host version decides.
        if (i18n == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0;
        return lostI18nExists(i18n);
    }

    static boolean lostI18nExists(ClassNode i18n) {
        for (MethodNode method : i18n.methods) {
            if (method.name.equals("exists") && method.desc.equals(EXISTS_DESC)) return false;
        }
        return true;
    }

    /** {@code exists(key)}, and {@code setDetail(report, name, supplier)}, which hands the host the supplier as a detail. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        // I18n read the language the client installed, which Language.getInstance() returns too.
        MethodVisitor exists = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "exists", EXISTS_DESC,
                null, null);
        exists.visitCode();
        exists.visitMethodInsn(Opcodes.INVOKESTATIC, LANGUAGE, "getInstance", "()L" + LANGUAGE + ";", false);
        exists.visitVarInsn(Opcodes.ALOAD, 0);
        exists.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LANGUAGE, "has", EXISTS_DESC, false);
        exists.visitInsn(Opcodes.IRETURN);
        exists.visitMaxs(0, 0);
        exists.visitEnd();
        // The host reads the detail at once and records a failure in place of the value, as the
        // old method read the supplier at once and wrote ERR when it threw.
        MethodVisitor detail = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setDetail",
                "(L" + SYSTEM_REPORT + ";" + SUPPLIER_DETAIL.substring(1), null, null);
        detail.visitCode();
        detail.visitVarInsn(Opcodes.ALOAD, 0);
        detail.visitVarInsn(Opcodes.ALOAD, 1);
        detail.visitVarInsn(Opcodes.ALOAD, 2);
        Type call = Type.getMethodType("()Ljava/lang/Object;");
        detail.visitInvokeDynamicInsn("call", "(Ljava/util/function/Supplier;)L" + CRASH_DETAIL + ";",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                call, new Handle(Opcodes.H_INVOKEINTERFACE, "java/util/function/Supplier", "get",
                        "()Ljava/lang/Object;", true), call);
        detail.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SYSTEM_REPORT, "setDetail", HOST_DETAIL, false);
        detail.visitInsn(Opcodes.RETURN);
        detail.visitMaxs(0, 0);
        detail.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
