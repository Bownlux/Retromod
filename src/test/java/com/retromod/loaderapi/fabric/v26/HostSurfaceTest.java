/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled host surfaces and the Fabric API link check that reads them. */
class HostSurfaceTest {

    private static final String SERVER_PLAY = "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking";
    private static final String FLUID = "net/fabricmc/fabric/api/transfer/v1/fluid/FluidVariant";

    @Test
    @DisplayName("Each host maps to the surface of its line, and newer hosts use the newest line")
    void hostLines() {
        assertEquals("26.1", HostSurface.lineFor("26.1"));
        assertEquals("26.1", HostSurface.lineFor("26.1.2"));
        assertEquals("26.2", HostSurface.lineFor("26.2"));
        assertEquals("26.3", HostSurface.lineFor("26.3-rc-1"));
        assertEquals("26.4", HostSurface.lineFor("26.4"));
        assertEquals("26.4", HostSurface.lineFor("26.6"));
    }

    @Test
    @DisplayName("Every indexed line ships a surface, and nothing else ships")
    void bundledSurfaces() throws IOException {
        Path dir = Path.of("src/main/resources/retromod/loaderapi/fabric-26x");
        Set<String> files;
        try (var list = Files.list(dir)) {
            files = list.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("surface-")).collect(Collectors.toCollection(TreeSet::new));
        }
        Set<String> expected = HostSurface.INDEXED_LINES.stream().map(line -> "surface-" + line + ".tsv")
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, files);
        for (String line : HostSurface.INDEXED_LINES) {
            HostSurface surface = HostSurface.forLine(line);
            assertTrue(surface.size() > 1000, line + " surface looks empty");
            assertFalse(surface.minFabricApi() == null || surface.minFabricApi().isBlank(), line);
        }
        // Fabric API for 26.1 and 26.2 runs on Fabric Loader 0.18.4.
        assertEquals(">=0.18.4", HostSurface.forLine("26.1").minFabricLoader());
        assertEquals(">=0.18.4", HostSurface.forLine("26.2").minFabricLoader());
    }

    @Test
    @DisplayName("Lookups follow Fabric API supertypes and stop where Fabric API ends")
    void memberLookups() {
        HostSurface surface = HostSurface.forLine("26.3");
        assertEquals(HostSurface.Presence.MISSING, surface.member(SERVER_PLAY, "send",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;"
                        + "Lnet/minecraft/network/FriendlyByteBuf;)V"));
        assertEquals(HostSurface.Presence.PRESENT, surface.member(SERVER_PLAY, "send",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"));
        assertEquals(HostSurface.Presence.PRESENT, surface.member(FLUID, "getComponents",
                "()Lnet/minecraft/core/component/DataComponentMap;"),
                "a member declared on TransferVariant is found through FluidVariant");
        assertEquals(HostSurface.Presence.PRESENT, surface.member(SERVER_PLAY, "hashCode", "()I"));
        assertFalse(surface.hasClass("net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback"));
    }

    @Test
    @DisplayName("The link check names each reference the host lacks, once")
    void linkCheckReportsMissingReferences() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Uses", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;"
                        + "Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        mv.visitCode();
        for (int i = 0; i < 2; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERVER_PLAY, "send",
                    "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;"
                            + "Lnet/minecraft/network/FriendlyByteBuf;)V", false);
        }
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback",
                "EVENT", "Lnet/fabricmc/fabric/api/event/Event;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        Set<String> missing = FabricApiLinkCheck.missingReferences(List.of(cw.toByteArray()),
                HostSurface.forLine("26.3"));
        assertEquals(Set.of(SERVER_PLAY + ".send(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)V",
                "net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback"), missing);
    }

    @Test
    @DisplayName("The link check reads lambdas, method handles, class constants, annotations and arrays")
    void linkCheckReadsEveryReferenceShape() {
        String hud = "net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback";
        String ldcOnly = "net/fabricmc/fabric/api/fixture/v1/ClassConstantOnly";
        String annotationOnly = "net/fabricmc/fabric/api/fixture/v1/AnnotationOnly";
        String arrayOnly = "net/fabricmc/fabric/api/fixture/v1/ArrayOnly";
        String oldSend = "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;"
                + "Lnet/minecraft/network/FriendlyByteBuf;)V";
        String samDesc = "(Lnet/minecraft/client/gui/GuiGraphics;F)V";
        org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Shapes", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitAnnotation("L" + annotationOnly + ";", true).visitEnd();
        mv.visitCode();
        // A lambda whose interface the host lacks, implemented by a method reference to a removed member.
        mv.visitInvokeDynamicInsn("onHudRender", "()L" + hud + ";", metafactory,
                org.objectweb.asm.Type.getType(samDesc),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, SERVER_PLAY, "send", oldSend, false),
                org.objectweb.asm.Type.getType(samDesc));
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(ldcOnly));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMultiANewArrayInsn("[[L" + arrayOnly + ";", 2);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        Set<String> missing = FabricApiLinkCheck.missingReferences(List.of(cw.toByteArray()),
                HostSurface.forLine("26.3"));
        assertEquals(Set.of(hud, ldcOnly, annotationOnly, arrayOnly, SERVER_PLAY + ".send" + oldSend),
                missing);
    }

    @Test
    @DisplayName("The link check finds a lambda whose interface method the host renamed or reshaped")
    void linkCheckReadsLambdaInterfaceMethods() {
        String click = "net/fabricmc/fabric/api/client/screen/v1/ScreenMouseEvents$AfterMouseClick";
        String oldSam = "(Lnet/minecraft/client/gui/screens/Screen;DDI)V";
        String hostSam = "(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/input/MouseButtonEvent;Z)Z";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Clicks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        // The 1.20.1 shape, which 26.x no longer declares, and the host's own shape.
        for (String sam : List.of(oldSam, hostSam)) {
            mv.visitInvokeDynamicInsn("afterMouseClick", "()L" + click + ";", METAFACTORY,
                    org.objectweb.asm.Type.getType(sam),
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "fixture/Clicks", "body", sam, false),
                    org.objectweb.asm.Type.getType(sam));
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        Set<String> missing = FabricApiLinkCheck.missingReferences(List.of(cw.toByteArray()),
                HostSurface.forLine("26.3"));
        assertEquals(Set.of(click + ".afterMouseClick" + oldSam), missing,
                "a lambda the host calls by another method never runs, so its stale method is a missing reference");
    }

    @Test
    @DisplayName("The link check reads the jars a mod nests and names each one, skipping bundled Fabric API modules")
    void linkCheckReadsNestedJars() throws IOException {
        String removedImpl = "net/fabricmc/fabric/impl/fixture/RemovedImpl";
        byte[] nested = jar(Map.of("trlib/Channels.class",
                classReading("trlib/Channels", removedImpl, "PLAY_C2S")));
        byte[] deeper = jar(Map.of("deep/Reader.class", classReading("deep/Reader", removedImpl, "DEEP")));
        byte[] withDeeper = jar(Map.of("mid/Empty.class", classReading("mid/Empty", "java/lang/System", "out"),
                "META-INF/jars/deep-1.0.jar", deeper));
        // An old Fabric API module the mod bundled; the host's own module replaces it.
        byte[] bundledModule = jar(Map.of("net/fabricmc/fabric/api/fixture/v1/Old.class",
                classReading("net/fabricmc/fabric/api/fixture/v1/Old", removedImpl, "OLD")));
        byte[] outer = jar(Map.of("mod/Main.class", classReading("mod/Main", "java/lang/System", "out"),
                "META-INF/jars/trlib-1.0.jar", nested,
                "META-INF/jars/mid-1.0.jar", withDeeper,
                "META-INF/jars/fabric-api-fixture-v1-1.0.jar", bundledModule));

        Map<String, Set<String>> missing = FabricApiLinkCheck.missingReferencesByJar(outer,
                HostSurface.forLine("26.3"));
        assertEquals(Map.of("META-INF/jars/trlib-1.0.jar", Set.of(removedImpl),
                "META-INF/jars/mid-1.0.jar > META-INF/jars/deep-1.0.jar", Set.of(removedImpl)), missing);
    }

    private static final org.objectweb.asm.Handle METAFACTORY = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                    + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    /** A class whose {@code run()} reads one static object field. */
    private static byte[] classReading(String name, String owner, String field) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
