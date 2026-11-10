/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.inplace.InPlaceStatus;
import com.retromod.inplace.TranslatedJarMarkers;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A NeoForge 1.21.1 mod's client-only listener on a NeoForge 26.3 dedicated server: removed, and the jar says so. */
class ForgeServerStripTest {

    private static final String HANDLER = "mod/Handler";
    private static final String[] SIDE_FIELDS = {"isClient", "isHeadless", "isDedicatedServer"};

    @TempDir
    Path tmp;

    private final Object[] savedSide = new Object[SIDE_FIELDS.length];
    private String savedMode;
    private String savedDist;

    @BeforeEach
    void dedicatedNeoForgeServer() throws Exception {
        for (int i = 0; i < SIDE_FIELDS.length; i++) savedSide[i] = sideField(SIDE_FIELDS[i]).get(null);
        savedMode = System.getProperty(InPlaceStatus.MODE_PROPERTY);
        savedDist = System.getProperty(InPlaceStatus.DIST_PROPERTY);
        EnvironmentDetector.setLoaderEnvironment(false);
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void restore() throws Exception {
        McReflect.setForceNeoForge(false);
        for (int i = 0; i < SIDE_FIELDS.length; i++) sideField(SIDE_FIELDS[i]).set(null, savedSide[i]);
        restoreProperty(InPlaceStatus.MODE_PROPERTY, savedMode);
        restoreProperty(InPlaceStatus.DIST_PROPERTY, savedDist);
    }

    @Test
    @DisplayName("an in-place copy on a reported dedicated server loses the listener and says so in its manifest")
    void inPlaceServerCopyIsStrippedAndMarked() throws Exception {
        System.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        System.setProperty(InPlaceStatus.DIST_PROPERTY, InPlaceStatus.DIST_DEDICATED_SERVER);

        Path out = translate("[1.21.1]");

        assertFalse(hasMethod(out, "onRenderWorld"), "NeoForge 1.21.1 removed this method on a server");
        assertTrue(hasMethod(out, "onTick"));
        try (JarFile jar = new JarFile(out.toFile())) {
            assertTrue(TranslatedJarMarkers.isServerOnly(jar.getManifest()),
                    "export and a client must be able to tell this copy lacks client code");
        }
    }

    @Test
    @DisplayName("rewrite mode strips and stamps too, so the jar it writes into mods/ stays off clients")
    void rewriteModeStripsAndStamps() throws Exception {
        System.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_REWRITE);
        System.setProperty(InPlaceStatus.DIST_PROPERTY, InPlaceStatus.DIST_DEDICATED_SERVER);

        Path out = translate("[1.21.1]");

        assertFalse(hasMethod(out, "onRenderWorld"));
        try (JarFile jar = new JarFile(out.toFile())) {
            assertTrue(TranslatedJarMarkers.isServerOnly(jar.getManifest()));
        }
    }

    @Test
    @DisplayName("a server whose loader reports no side is found by Retromod's own detection")
    void unreportedSideFallsBackToDetection() throws Exception {
        System.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_REWRITE);
        System.clearProperty(InPlaceStatus.DIST_PROPERTY);

        Path out = translate("[1.21.1]");

        assertFalse(hasMethod(out, "onRenderWorld"), "NeoForge 1.20.2 to 1.20.4 report no side");
        try (JarFile jar = new JarFile(out.toFile())) {
            assertTrue(TranslatedJarMarkers.isServerOnly(jar.getManifest()));
        }
    }

    @Test
    @DisplayName("a client keeps every member and writes no server marker")
    void clientKeepsClientCode() throws Exception {
        EnvironmentDetector.setLoaderEnvironment(true);
        System.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_REWRITE);
        System.setProperty(InPlaceStatus.DIST_PROPERTY, InPlaceStatus.DIST_CLIENT);

        Path out = translate("[1.21.1]");

        assertTrue(hasMethod(out, "onRenderWorld"));
        try (JarFile jar = new JarFile(out.toFile())) {
            assertFalse(TranslatedJarMarkers.isServerOnly(jar.getManifest()));
            assertFalse(hasStandIn(jar));
        }
    }

    @Test
    @DisplayName("a server's copy gets stand-ins for the client classes its shared classes name")
    void serverCopyGetsClientStandIns() throws Exception {
        System.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        System.setProperty(InPlaceStatus.DIST_PROPERTY, InPlaceStatus.DIST_DEDICATED_SERVER);

        Path out = translate("[26.1]");

        try (JarFile jar = new JarFile(out.toFile())) {
            assertTrue(hasStandIn(jar), "onPlayer names LocalPlayer, which the server lacks");
            assertTrue(TranslatedJarMarkers.isServerOnly(jar.getManifest()),
                    "a copy with stand-ins is as unfit for a client as a stripped one");
        }
    }

    @Test
    @DisplayName("a mod built after NeoForge 1.21.1 keeps NeoForge's marker, as its own loader did")
    void newerModKeepsNeoForgeMarker() throws Exception {
        System.setProperty(InPlaceStatus.MODE_PROPERTY, InPlaceStatus.MODE_IN_PLACE);
        System.setProperty(InPlaceStatus.DIST_PROPERTY, InPlaceStatus.DIST_DEDICATED_SERVER);

        Path out = translate("[26.1]");

        assertTrue(hasMethod(out, "onRenderWorld"));
    }

    private Path translate(String minecraftRange) throws Exception {
        Path source = tmp.resolve("handler-mod.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source))) {
            entry(jar, "META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"handler\"\nversion=\"1.0\"\n"
                    + "[[dependencies.handler]]\nmodId=\"minecraft\"\ntype=\"required\"\nversionRange=\""
                    + minecraftRange + "\"\n").getBytes(StandardCharsets.UTF_8));
            entry(jar, HANDLER + ".class", handler());
        }
        Path out = new ForgeModTransformer("26.3").transformMod(source, Files.createDirectories(tmp.resolve("out")));
        assertNotNull(out, "the mod should translate");
        return out;
    }

    /** A common subscriber with one client-only listener, as Create's SymmetryHandler has. */
    private static byte[] handler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, HANDLER, null, "java/lang/Object", null);
        for (String name : new String[]{"onTick", "onRenderWorld"}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(Ljava/lang/Object;)V", null, null);
            if (name.equals("onRenderWorld")) {
                AnnotationVisitor onlyIn = mv.visitAnnotation("Lnet/neoforged/api/distmarker/OnlyIn;", false);
                onlyIn.visitEnum("value", "Lnet/neoforged/api/distmarker/Dist;", "CLIENT");
                onlyIn.visitEnd();
            }
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        // A shared method that names a client class, as architectury's getClientPlayer does.
        MethodVisitor player = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onPlayer",
                "(Lnet/minecraft/client/player/LocalPlayer;)V", null, null);
        player.visitCode();
        player.visitInsn(RETURN);
        player.visitMaxs(0, 0);
        player.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean hasStandIn(JarFile jar) {
        return jar.stream().anyMatch(entry ->
                entry.getName().endsWith("/clientonly/net/minecraft/client/player/LocalPlayer.class"));
    }

    private static boolean hasMethod(Path jar, String name) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(file.getJarEntry(HANDLER + ".class")).readAllBytes()).accept(node, 0);
            return node.methods.stream().anyMatch(m -> m.name.equals(name));
        }
    }

    private static void entry(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static Field sideField(String name) throws Exception {
        Field field = EnvironmentDetector.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
