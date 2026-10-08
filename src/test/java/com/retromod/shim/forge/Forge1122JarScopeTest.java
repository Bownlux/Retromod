/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge 1.13 reused the 1.12.2 names {@code FluidStack}, {@code FluidUtil} and
 * {@code IFluidBlock}. The runtime transform renames them to the 1.12.2 stand-ins in a pre-1.13
 * jar only, so a newer Forge mod on the same host keeps the real classes.
 */
class Forge1122JarScopeTest {

    private static final String STACK = "net/minecraftforge/fluids/FluidStack";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
        Forge1122EventBridge.resetForTesting();
        Forge1122FluidBridge.resetForTesting();
    }

    private static void bridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);
        Forge1122EventBridge.register(t);
        Forge1122ServiceStubs.register(t);
    }

    /** {@code static Object fill(fluid) { return new FluidStack(fluid, 1000); }} */
    private static byte[] tank(String fluidType) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Tank", null, "java/lang/Object", null);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fill",
                "(L" + fluidType + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, STACK);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(SIPUSH, 1000);
        mv.visitMethodInsn(INVOKESPECIAL, STACK, "<init>", "(L" + fluidType + ";I)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path jar(Path dir, String name, String metadataPath, String metadata, byte[] tank)
            throws Exception {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(metadataPath));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("test/Tank.class"));
            out.write(tank);
            out.closeEntry();
        }
        return jar;
    }

    private static String tankPool(Path jar) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            try (var in = jf.getInputStream(jf.getJarEntry("test/Tank.class"))) {
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }

    @Test
    void onlyAPreFlatteningJarGetsTheLegacyFluidStack(@TempDir Path tmp) throws Exception {
        bridged();
        Path legacy = jar(tmp, "legacy-tanks.jar", "mcmod.info",
                "[{\"modid\":\"legacytanks\",\"name\":\"Legacy Tanks\",\"version\":\"1.0\","
                        + "\"mcversion\":\"1.12.2\"}]",
                tank("net/minecraftforge/fluids/Fluid"));
        Path modern = jar(tmp, "modern-tanks.jar", "META-INF/mods.toml",
                "modLoader=\"javafml\"\nloaderVersion=\"[41,)\"\nlicense=\"MIT\"\n"
                        + "[[mods]]\nmodId=\"moderntanks\"\n"
                        + "[[dependencies.moderntanks]]\nmodId=\"minecraft\"\n"
                        + "versionRange=\"[1.19.2,1.20)\"\n",
                tank("net/minecraft/world/level/material/Fluid"));

        ForgeModTransformer transformer = new ForgeModTransformer("1.20.1");
        String legacyPool = tankPool(transformer.transformMod(legacy, Files.createDirectory(tmp.resolve("a"))));
        String modernPool = tankPool(transformer.transformMod(modern, Files.createDirectory(tmp.resolve("b"))));

        assertTrue(legacyPool.contains("LegacyFluidStack"),
                "a 1.12.2 FluidStack holds a 1.12.2 Fluid and needs the stand-in");
        assertFalse(legacyPool.contains(STACK), "no 1.12.2 FluidStack may reach the host class");
        assertTrue(modernPool.contains(STACK), "a newer Forge mod keeps the host FluidStack");
        assertFalse(modernPool.contains("LegacyFluidStack"),
                "the 1.12.2 stand-in must never replace a newer mod's FluidStack");
    }
}
