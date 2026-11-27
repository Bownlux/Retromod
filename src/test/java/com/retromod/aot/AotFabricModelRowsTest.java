/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** The AOT path gives an old Fabric model the rebuilt model interface and carries its copy, as the runtime path does. */
class AotFabricModelRowsTest {

    private static final String MODEL = "fixture/frapi/AotModel";
    private static final String REBUILT = "com/retromod/legacyapi/fabric/client/renderer/LegacyFabricBakedModel";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedHost;

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("An AOT jar of an old Fabric mod names its own copy of the rebuilt model interface and carries it")
    void aotCarriesTheRebuiltModelInterface(@TempDir Path dir) throws Exception {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Path input = dir.toRealPath().resolve("old-model.jar");
        writeOldMod(input);

        Path prepared;
        try (AutoCloseable runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())) {
            prepared = AotCompiler.forOfflineInputs(new ShimRegistry(), "26.3", dir.toRealPath().resolve("cache"))
                    .compileModAot(input, true);
        }

        try (ZipFile zip = new ZipFile(prepared.toFile())) {
            String copy = zip.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("com/retromod/embedded/") && name.endsWith(REBUILT + ".class"))
                    .findFirst().orElse(null);
            assertNotNull(copy, "the jar carries its own copy of the rebuilt model interface");
            ClassNode model = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(MODEL + ".class")).readAllBytes()).accept(model, 0);
            assertTrue(model.interfaces.contains(copy.substring(0, copy.length() - ".class".length())),
                    "the model names the copy it travels with: " + model.interfaces);
        }
    }

    // A 1.21.1 Fabric mod with one baked model that also implements the old FabricBakedModel.
    private static void writeOldMod(Path jar) throws Exception {
        String metadata = "{\"schemaVersion\":1,\"id\":\"aotmodel\",\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"1.21.1\"}}";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MODEL, null, "java/lang/Object",
                new String[]{"net/minecraft/class_1087", "net/fabricmc/fabric/api/renderer/v1/model/FabricBakedModel"});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fabric.mod.json"));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(MODEL + ".class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
    }
}
