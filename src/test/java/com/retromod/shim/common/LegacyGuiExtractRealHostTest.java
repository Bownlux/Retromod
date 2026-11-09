/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/** The embedded GUI helpers from a CLI-translated jar draw on the real 26.3 GuiGraphicsExtractor and its 2D stack. */
class LegacyGuiExtractRealHostTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    // The 26.3 client and the libraries its GUI, text and id classes link against.
    private static final List<String> HOST = List.of("com/mojang/minecraft/26.3/minecraft-26.3-client.jar",
            "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar", "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
            "com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar", "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar",
            "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar", "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            "com/mojang/logging/1.7.12/logging-1.7.12.jar", "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar",
            "com/mojang/authlib/10.0.77/authlib-10.0.77.jar", "org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar",
            "io/netty/netty-buffer/4.2.16.Final/netty-buffer-4.2.16.Final.jar", "io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar",
            "io/netty/netty-codec-base/4.2.16.Final/netty-codec-base-4.2.16.Final.jar",
            "io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar",
            "io/netty/netty-handler/4.2.16.Final/netty-handler-4.2.16.Final.jar", "org/joml/joml/1.10.9/joml-1.10.9.jar",
            "com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar", "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
            "com/ibm/icu/icu4j/78.3/icu4j-78.3.jar", "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar");
    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String PIPELINE = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;";

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old screen's pose stack moves the real 2D stack, and its fills, lines, sprites and text reach the extractor")
    void drawsOnTheRealExtractor() throws Throwable {
        for (String jar : HOST) assumeTrue(Files.isRegularFile(LIBRARIES.resolve(jar)), "needs " + jar);
        Path translated = translate();
        List<URL> urls = new ArrayList<>();
        for (String jar : HOST) urls.add(LIBRARIES.resolve(jar).toUri().toURL());
        try (URLClassLoader host = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Map<String, byte[]> classes = classesOf(translated);
            classes.put("test/Recorder", recorder());
            classes.put("test/RecordingGraphics", recordingGraphics());
            classes.put("test/FixedFont", fixedFont());
            ClassLoader game = new ClassLoader(host) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            Object unsafe = unsafe();
            Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            // An extractor made without a running game: only its 2D stack is needed, and the overrides record the rest.
            Object graphics = allocate.invoke(unsafe, game.loadClass("test.RecordingGraphics"));
            Object stack = host.loadClass("org.joml.Matrix3x2fStack").getConstructor(int.class).newInstance(16);
            Field pose = host.loadClass(GUI.replace('/', '.')).getDeclaredField("pose");
            pose.setAccessible(true);
            pose.set(graphics, stack);
            Object font = allocate.invoke(unsafe, game.loadClass("test.FixedFont"));
            Object sprite = host.loadClass("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, "mod", "badge");

            Method draw = null;
            for (Method method : game.loadClass("mod.Screenish").getMethods()) if (method.getName().equals("draw")) draw = method;
            draw.invoke(null, graphics, font, sprite);

            Class<?> pipelines = host.loadClass("net.minecraft.client.renderer.RenderPipelines");
            String highlight = String.valueOf(pipelines.getField("GUI_TEXT_HIGHLIGHT").get(null));
            String textured = String.valueOf(pipelines.getField("GUI_TEXTURED").get(null));
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) game.loadClass("test.Recorder").getField("LINES").get(null);
            // Everything after shift(pose) is moved by (10, 20); the fractional text adds its own (1.5, 2.5).
            assertEquals(List.of("fill 1 2 3 4 5 @10.0 20.0", "horizontalLine 1 9 3 6 @10.0 20.0", "verticalLine 2 4 8 7 @10.0 20.0",
                    "fillGradient 1 2 3 4 8 9 @10.0 20.0", "fill " + highlight + " 1 2 3 4 10 @10.0 20.0",
                    "blitSprite " + textured + " mod:badge 1 2 3 4 @10.0 20.0", "text hi 0 0 11 true @11.5 22.5"), lines);
            // The old drawString returned the x after the text and its shadow, and 0 for no text, which it did not draw.
            assertEquals(14, game.loadClass("mod.Screenish").getField("end").getInt(null));
            assertEquals(0, game.loadClass("mod.Screenish").getField("nullEnd").getInt(null));
            // popPose put the real stack back where the screen found it.
            Class<?> matrix = host.loadClass("org.joml.Matrix3x2f");
            assertEquals(0F, (float) matrix.getMethod("m20").invoke(stack));
            assertEquals(0F, (float) matrix.getMethod("m21").invoke(stack));
        }
    }

    @Test
    @DisplayName("A pose stack copy pushed through but popped through the 2D stack stays bounded")
    void poseCopyStaysBounded() throws Throwable {
        for (String jar : HOST) assumeTrue(Files.isRegularFile(LIBRARIES.resolve(jar)), "needs " + jar);
        Map<String, byte[]> classes = classesOf(translate());
        String copyName = classes.keySet().stream().filter(name -> name.endsWith("/legacyapi/minecraft/client/gui/LegacyGuiPoseStack"))
                .findFirst().orElseThrow();
        List<URL> urls = new ArrayList<>();
        for (String jar : HOST) urls.add(LIBRARIES.resolve(jar).toUri().toURL());
        try (URLClassLoader host = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            ClassLoader game = new ClassLoader(host) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            Class<?> stackType = host.loadClass("org.joml.Matrix3x2fStack");
            Object stack = stackType.getConstructor(int.class).newInstance(16);
            var make = game.loadClass(copyName.replace('/', '.')).getDeclaredConstructor(stackType);
            make.setAccessible(true);
            Object copy = make.newInstance(stack);
            Method push = copy.getClass().getMethod("pushPose");
            Method popMatrix = stackType.getMethod("popMatrix");
            // As a mod whose push went through the copy and whose pop the 2D migration rewrote.
            for (int i = 0; i < 40; i++) {
                push.invoke(copy);
                popMatrix.invoke(stack);
            }
            Field poses = host.loadClass("com.mojang.blaze3d.vertex.PoseStack").getDeclaredField("poses");
            poses.setAccessible(true);
            assertTrue(((List<?>) poses.get(copy)).size() <= 17, "the copy kept " + ((List<?>) poses.get(copy)).size() + " poses");
            // A normal push and pop still pair up afterwards.
            push.invoke(copy);
            copy.getClass().getMethod("popPose").invoke(copy);
        }
    }

    /** The fixture screen, translated for NeoForge 26.3 by the CLI, so the embedded copies are what runs. */
    private Path translate() throws Exception {
        Map<String, String> sources = new HashMap<>(LegacyGuiExtractBridgeTest.OldHost.sources());
        sources.put("mod/Screenish.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.gui.*;
                import net.minecraft.client.renderer.RenderType;
                import net.minecraft.resources.ResourceLocation;
                public class Screenish {
                    public static int end;
                    public static int nullEnd = -1;
                    public static void draw(GuiGraphics g, Font f, ResourceLocation id) {
                        PoseStack pose = g.pose();
                        pose.pushPose();
                        shift(pose);
                        g.fill(RenderType.gui(), 1, 2, 3, 4, 5);
                        g.hLine(1, 9, 3, 6);
                        g.vLine(2, 4, 8, 7);
                        g.fillGradient(1, 2, 3, 4, 50, 8, 9);
                        g.fill(RenderType.guiTextHighlight(), 1, 2, 3, 4, 10);
                        g.blitSprite(id, 1, 2, 50, 3, 4);
                        end = g.drawString(f, "hi", 1.5F, 2.5F, 11, true);
                        nullEnd = g.drawString(f, (String) null, 1F, 1F, 11, false);
                        pose.popPose();
                    }
                    static void shift(PoseStack pose) { pose.translate(10, 20, 5); }
                }
                """);
        Map<String, byte[]> classes = BridgeHarness.only(BridgeHarness.compile(dir.resolve("compile"), sources), "mod/");
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                + "modId=\"screenish\"\nversion=\"1.0.0\"\n[[dependencies.screenish]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"CLIENT\"\n").getBytes(StandardCharsets.UTF_8));
        classes.forEach((name, bytes) -> jar.put(name + ".class", bytes));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("screenish.jar");
        Files.write(input, LoaderApiFixtures.jar(jar));
        McReflect.setForceNeoForge(true);
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        Path output = Files.createDirectories(dir.resolve("out")).resolve("screenish.jar");
        CliHarness.transform(input, output, "26.3");
        assertTrue(Files.isRegularFile(output), "the CLI must write " + output);
        return output;
    }

    private static Map<String, byte[]> classesOf(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (entry.getName().endsWith(".class")) {
                    classes.put(entry.getName().substring(0, entry.getName().length() - 6), file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return classes;
    }

    // test/Recorder: one list the recording extractor writes its draws to.
    private static byte[] recorder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Recorder", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "LINES", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(NEW, "java/util/ArrayList");
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        init.visitFieldInsn(PUTSTATIC, "test/Recorder", "LINES", "Ljava/util/List;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // test/RecordingGraphics: the real extractor with each draw the helpers use written down with the 2D stack's offset.
    private static byte[] recordingGraphics() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/RecordingGraphics", null, GUI, null);
        String font = "Lnet/minecraft/client/gui/Font;";
        for (String[] draw : new String[][]{{"fill", "(IIIII)V"}, {"fill", "(" + PIPELINE + "IIIII)V"},
                {"fillGradient", "(IIIIII)V"}, {"horizontalLine", "(IIII)V"}, {"verticalLine", "(IIII)V"},
                {"text", "(" + font + "Ljava/lang/String;IIIZ)V"},
                {"blitSprite", "(" + PIPELINE + "Lnet/minecraft/resources/Identifier;IIII)V"}}) {
            record(cw, draw[0], draw[1]);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void record(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        int slot = 1;
        for (Type arg : Type.getArgumentTypes(desc)) {
            // The font is a stand-in without a useful name, so only the text after it is written.
            if (arg.getDescriptor().equals("Lnet/minecraft/client/gui/Font;")) {
                slot++;
                continue;
            }
            mv.visitLdcInsn(" ");
            append(mv, "Ljava/lang/String;");
            mv.visitVarInsn(arg.getOpcode(ILOAD), slot);
            append(mv, arg.getSort() == Type.OBJECT ? "Ljava/lang/Object;" : arg.getDescriptor());
            slot += arg.getSize();
        }
        for (String element : new String[]{"m20", "m21"}) {
            mv.visitLdcInsn(element.equals("m20") ? " @" : " ");
            append(mv, "Ljava/lang/String;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, GUI, "pose", "()Lorg/joml/Matrix3x2fStack;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/joml/Matrix3x2fStack", element, "()F", false);
            append(mv, "F");
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitFieldInsn(GETSTATIC, "test/Recorder", "LINES", "Ljava/util/List;");
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void append(MethodVisitor mv, String desc) {
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + desc + ")Ljava/lang/StringBuilder;", false);
    }

    // test/FixedFont: a font whose every string is 12 wide, made without the game's glyph providers.
    private static byte[] fixedFont() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/FixedFont", null, "net/minecraft/client/gui/Font", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "width", "(Ljava/lang/String;)I", null, null);
        mv.visitCode();
        mv.visitIntInsn(BIPUSH, 12);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object unsafe() throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return theUnsafe.get(null);
    }
}
