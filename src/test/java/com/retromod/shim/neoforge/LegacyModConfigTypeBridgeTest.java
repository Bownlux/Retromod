/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * FancyModLoader 12.0.8 (NeoForge 26.3 beta 43) renamed {@code ModConfig.Type.COMMON} to {@code LOCAL} and
 * {@code SERVER} to {@code SYNCED}, so Farmer's Delight, Citadel, JourneyMap and 65 more 1.21.1 mods stopped
 * in their constructor with {@code NoSuchFieldError}. NeoForge 1.21.9 dropped SecureJarHandler, so a mod
 * file's {@code findResource} and {@code getSecureJar} are gone too.
 */
class LegacyModConfigTypeBridgeTest {

    private static final String TYPE = LegacyModConfigTypeBridge.TYPE;
    private static final String L_TYPE = "L" + TYPE + ";";
    private static final String SPEC = "net/neoforged/fml/config/IConfigSpec";
    private static final String CONTAINER = LegacyModConfigTypeBridge.CONTAINER;
    private static final String HELPER = LegacyModConfigTypeBridge.HELPER;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A 1.21.1 mod's COMMON and SERVER configs reach LOCAL and SYNCED under their old file names")
    void oldModReachesTheRenamedTypes() {
        bridge();
        List<String> refs = transform("1.21.1", configMod("test/mod/Configs", "COMMON", "SERVER"));

        assertTrue(refs.contains("GETSTATIC " + TYPE + ".LOCAL"), refs.toString());
        assertTrue(refs.contains("GETSTATIC " + TYPE + ".SYNCED"), refs.toString());
        assertFalse(refs.stream().anyMatch(ref -> ref.endsWith(".COMMON") || ref.endsWith(".SERVER")), refs.toString());
        assertTrue(refs.contains("INVOKESTATIC " + HELPER + ".registerConfig(L" + CONTAINER + ";" + L_TYPE
                + "L" + SPEC + ";)V"), "a config with no file name keeps modid-common.toml: " + refs);
        assertTrue(refs.contains("INVOKESTATIC " + HELPER + ".extension(" + L_TYPE + ")Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains("INVOKESTATIC " + HELPER + ".name(" + L_TYPE + ")Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains("INVOKESTATIC " + HELPER + ".valueOf(Ljava/lang/String;)" + L_TYPE), refs.toString());
        assertTrue(refs.contains("INVOKESTATIC " + HELPER + ".registerConfig(Lnet/neoforged/fml/config/ConfigTracker;"
                + L_TYPE + "L" + SPEC + ";L" + CONTAINER + ";)Lnet/neoforged/fml/config/ModConfig;"), refs.toString());
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper travels with the mod");
    }

    @Test
    @DisplayName("A mod built for 26.3 keeps the host's registerConfig, extension, name and valueOf")
    void newModKeepsTheHostMembers() {
        bridge();
        List<String> refs = transform("26.3", configMod("test/mod/Configs", "LOCAL", "SYNCED"));

        assertTrue(refs.contains("GETSTATIC " + TYPE + ".LOCAL"), refs.toString());
        assertTrue(refs.contains("INVOKEVIRTUAL " + CONTAINER + ".registerConfig(" + L_TYPE + "L" + SPEC + ";)V"),
                "a 26.3 mod's config stays modid-local.toml: " + refs);
        assertTrue(refs.contains("INVOKEVIRTUAL " + TYPE + ".extension()Ljava/lang/String;"), refs.toString());
        assertTrue(refs.contains("INVOKESTATIC " + TYPE + ".valueOf(Ljava/lang/String;)" + L_TYPE), refs.toString());
        assertFalse(refs.stream().anyMatch(ref -> ref.contains(HELPER)), refs.toString());
    }

    @Test
    @DisplayName("The bridge registers from the 26.3 host on, where beta 43 renamed the types")
    void hostDecidesTheRename() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyModConfigTypeBridge.hostRenamedTypes(), "26.2 still has COMMON");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyModConfigTypeBridge.hostRenamedTypes(), "an offline 26.3 target takes the new names");

        transformer.clearRedirectsForTesting();
        new NeoForge_26_2_to_26_3().registerRedirects(transformer);
        assertTrue(transform("1.21.1", configMod("test/mod/Configs", "COMMON", "SERVER")).contains("GETSTATIC " + TYPE + ".LOCAL"),
                "the 26.2 to 26.3 shim carries the bridge");
    }

    @Test
    @DisplayName("A 1.21.1 mod's findResource and getSecureJar reach the rebuilt lookups on 1.21.9 and newer")
    void modFileLookupsReachTheHelper() {
        transformer.clearRedirectsForTesting();
        LegacyModFileBridge.register(transformer);
        transformer.setModSourceVersion("1.21.1");
        List<String> refs = refs(transformer.transformClass(modFileMod("test/mod/Files"), "test/mod/Files"));

        String files = LegacyModFileBridge.HELPER;
        String jar = LegacyModFileBridge.LEGACY_JAR;
        String modFile = "Lnet/neoforged/neoforgespi/locating/IModFile;";
        assertTrue(refs.contains("INVOKESTATIC " + files + ".findResource(" + modFile + "[Ljava/lang/String;)Ljava/nio/file/Path;"),
                refs.toString());
        assertTrue(refs.contains("INVOKESTATIC " + files + ".getSecureJar(" + modFile + ")L" + jar + ";"), refs.toString());
        assertTrue(refs.contains("INVOKEINTERFACE " + jar + ".getRootPath()Ljava/nio/file/Path;"), refs.toString());
        assertTrue(refs.contains("INVOKEINTERFACE " + jar + "$ModuleDataProvider.uri()Ljava/net/URI;"), refs.toString());
        assertFalse(refs.stream().anyMatch(ref -> ref.contains("cpw/mods/jarhandling")), refs.toString());
        for (String rebuilt : List.of(files, jar, jar + "$ModuleDataProvider")) {
            assertTrue(transformer.getSyntheticClasses().containsKey(rebuilt), rebuilt + " travels with the mod");
        }
    }

    private List<String> transform(String source, byte[] mod) {
        transformer.setModSourceVersion(source);
        return refs(transformer.transformClass(mod, "test/mod/Configs"));
    }

    private void bridge() {
        transformer.clearRedirectsForTesting();
        LegacyModConfigTypeBridge.registerRedirects(transformer);
    }

    /** Every field and method reference in a class, as "OPCODE owner.name" or "OPCODE owner.name(desc)". */
    static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (var method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode f) refs.add(opcode(f.getOpcode()) + " " + f.owner + "." + f.name);
                if (insn instanceof MethodInsnNode m) {
                    refs.add(opcode(m.getOpcode()) + " " + m.owner + "." + m.name + m.desc);
                }
            }
        }
        return refs;
    }

    private static String opcode(int opcode) {
        return switch (opcode) {
            case GETSTATIC -> "GETSTATIC";
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case INVOKESTATIC -> "INVOKESTATIC";
            case INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> "OP" + opcode;
        };
    }

    /** Registers two configs by type, reads a type's extension and name, parses one, and registers through the tracker. */
    static byte[] configMod(String name, String common, String server) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(L" + CONTAINER + ";L" + SPEC + ";)Ljava/lang/String;", null, null);
        mv.visitCode();
        for (String constant : new String[]{common, server}) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, TYPE, constant, L_TYPE);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, CONTAINER, "registerConfig", "(" + L_TYPE + "L" + SPEC + ";)V", false);
        }
        mv.visitFieldInsn(GETSTATIC, "net/neoforged/fml/config/ConfigTracker", "INSTANCE",
                "Lnet/neoforged/fml/config/ConfigTracker;");
        mv.visitFieldInsn(GETSTATIC, TYPE, server, L_TYPE);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/neoforged/fml/config/ConfigTracker", "registerConfig",
                "(" + L_TYPE + "L" + SPEC + ";L" + CONTAINER + ";)Lnet/neoforged/fml/config/ModConfig;", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, TYPE, common, L_TYPE);
        mv.visitMethodInsn(INVOKEVIRTUAL, TYPE, "extension", "()Ljava/lang/String;", false);
        mv.visitFieldInsn(GETSTATIC, TYPE, server, L_TYPE);
        mv.visitMethodInsn(INVOKEVIRTUAL, TYPE, "name", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitLdcInsn(common);
        mv.visitMethodInsn(INVOKESTATIC, TYPE, "valueOf", "(Ljava/lang/String;)" + L_TYPE, false);
        mv.visitInsn(POP);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Reads a bundled file, the jar's root and its location, as Patchouli, Architectury and PuzzlesLib do. */
    static byte[] modFileMod(String name) {
        String modFile = "net/neoforged/neoforgespi/locating/IModFile";
        String secureJar = "cpw/mods/jarhandling/SecureJar";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(L" + modFile + ";)[Ljava/lang/Object;",
                null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_3);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_2);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_0);
        mv.visitLdcInsn("data");
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitLdcInsn("bundled.json");
        mv.visitInsn(AASTORE);
        mv.visitMethodInsn(INVOKEINTERFACE, modFile, "findResource", "([Ljava/lang/String;)Ljava/nio/file/Path;", true);
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, modFile, "getSecureJar", "()L" + secureJar + ";", true);
        mv.visitMethodInsn(INVOKEINTERFACE, secureJar, "getRootPath", "()Ljava/nio/file/Path;", true);
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, modFile, "getSecureJar", "()L" + secureJar + ";", true);
        mv.visitMethodInsn(INVOKEINTERFACE, secureJar, "moduleDataProvider",
                "()L" + secureJar + "$ModuleDataProvider;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, secureJar + "$ModuleDataProvider", "uri", "()Ljava/net/URI;", true);
        mv.visitInsn(AASTORE);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
