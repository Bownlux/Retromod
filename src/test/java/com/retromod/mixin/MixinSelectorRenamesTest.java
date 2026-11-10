/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.google.gson.JsonParser;
import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Selectors and refmap entries follow target methods the host renamed, only where the rename is proven. */
class MixinSelectorRenamesTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MOUSE = "net/minecraft/client/MouseHandler";
    private static final String BUTTON_INFO = "Lnet/minecraft/client/input/MouseButtonInfo;";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String DAMAGE = "Lnet/minecraft/world/damagesource/DamageSource;";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private static final String TITLE = "net/minecraft/client/gui/screens/TitleScreen";
    private static final String EXTRACTOR = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String FIRE = "net/minecraft/world/level/block/FireBlock";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private FuzzyMethodResolver host;
    private MixinHostDriftRepair repair;

    @BeforeEach
    void indexHost() throws IOException {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(MINECRAFT, c -> {
                method(c, 0, "resizeGui", "()V");
                method(c, 0, "pickBlockOrEntity", "()V");
            }));
            put(out, hostClass(MOUSE, c -> method(c, Opcodes.ACC_PRIVATE, "onButton", "(J" + BUTTON_INFO + "I)V")));
            put(out, hostClass(LIVING, c -> method(c, 0, "hurtServer", "(" + SERVER_LEVEL + DAMAGE + "F)Z")));
            put(out, hostClass(TITLE, c -> method(c, 0, "extractRenderState", "(" + EXTRACTOR + "IIF)V")));
            put(out, hostClass(BLOCK, c -> {
                method(c, 0, "onPlace", "(" + LEVEL + POS + ")V");
                method(c, 0, "onPlace", "(" + ENTITY + ")V");
            }));
            put(out, hostClass(FIRE, c -> {
                method(c, 0, "onPlace", "(" + LEVEL + POS + ")V");
                method(c, 0, "onPlace", "(" + LEVEL + ENTITY + ")V");
            }));
            for (String name : List.of("net/minecraft/world/level/Level", "net/minecraft/core/BlockPos",
                    "net/minecraft/world/entity/Entity", "net/minecraft/world/damagesource/DamageSource",
                    "net/minecraft/server/level/ServerLevel")) {
                put(out, hostClass(name, c -> { }));
            }
        }
        host = new FuzzyMethodResolver();
        host.indexJar(jar);
        repair = new MixinHostDriftRepair(host, Map.of(), Map.of());
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    void aSameDescriptorRenameMovesTheSelector() {
        byte[] mixin = mixin("ResizeMixin", MINECRAFT, c -> handler(c, "onResize", 0, "(" + CI + ")V", "resizeDisplay"));

        MethodNode handler = method(repair.apply(mixin), "onResize");

        assertEquals(List.of("resizeGui"), selectors(handler), "26.1 renamed resizeDisplay() to resizeGui()");
        assertEquals("(" + CI + ")V", handler.desc);
    }

    @Test
    void aMixinExtrasSugarParameterDoesNotBlockTheRename() {
        byte[] mixin = mixin("SugarMixin", MINECRAFT, c -> {
            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "onResize",
                    "(" + CI + "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;)V", null, null);
            handler.visitParameterAnnotation(1, "Lcom/llamalad7/mixinextras/sugar/Share;", false).visitEnd();
            inject(handler, "resizeDisplay");
            handler.visitCode();
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 3);
            handler.visitEnd();
        });

        assertEquals(List.of("resizeGui"), selectors(method(repair.apply(mixin), "onResize")),
                "MixinExtras strips the shared local before Mixin checks the callback layout");
    }

    @Test
    void aRenameWithNewParametersRetypesTheHandlerToo() {
        byte[] mixin = mixin("PressMixin", MOUSE, c -> handler(c, "onPress", 0, "(JIII" + CI + ")V", "onPress"));

        MethodNode handler = method(repair.apply(mixin), "onPress");

        assertEquals(List.of("onButton"), selectors(handler));
        assertEquals("(" + CI + ")V", handler.desc, "the handler reads nothing, so it keeps only the callback");
    }

    @Test
    void aHandlerThatReadsAChangedParameterKeepsItsSelector() {
        byte[] mixin = mixin("ButtonMixin", MOUSE, c -> {
            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "onPress", "(JIII" + CI + ")V", null, null);
            inject(handler, "onPress");
            handler.visitCode();
            handler.visitVarInsn(Opcodes.ILOAD, 3);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 7);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "the button int became MouseButtonInfo, so the handler cannot follow the rename");
    }

    @Test
    void theDamageSplitMovesAHandlerToHurtServer() {
        byte[] mixin = mixin("DamageMixin", LIVING, c -> {
            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "onHurt", "(" + DAMAGE + "F" + CIR + ")V", null, null);
            inject(handler, "hurt(" + DAMAGE + "F)Z");
            handler.visitCode();
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "onHurt");

        assertEquals("(" + SERVER_LEVEL + DAMAGE + "F" + CIR + ")V", handler.desc,
                "the damage source keeps its meaning; the new ServerLevel comes first");
        assertEquals(List.of("hurtServer(" + SERVER_LEVEL + DAMAGE + "F)Z"), selectors(handler));
    }

    @Test
    void aGuiRenderSelectorFollowsTheExtractRename() {
        String old = "render(" + EXTRACTOR + "IIF)V";
        byte[] mixin = mixin("TitleMixin", TITLE, c -> handler(c, "onRender", 0, "(" + EXTRACTOR + "IIF" + CI + ")V", old));

        assertEquals(List.of("extractRenderState(" + EXTRACTOR + "IIF)V"), selectors(method(repair.apply(mixin), "onRender")));
    }

    @Test
    void anOlderHostKeepsTheSelector() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        byte[] mixin = mixin("ResizeMixin", MINECRAFT, c -> handler(c, "onResize", 0, "(" + CI + ")V", "resizeDisplay"));

        assertArrayEquals(mixin, repair.apply(mixin), "the row starts at 26.1, whatever the index holds");
    }

    @Test
    void anOverloadIsPickedOnlyWhenTheCapturesFitOne() {
        byte[] fits = mixin("PlaceMixin", BLOCK, c -> readingHandler(c, "(" + LEVEL + CI + ")V", "onPlace(" + LEVEL + ")V"));
        byte[] ambiguous = mixin("FireMixin", FIRE, c -> readingHandler(c, "(" + LEVEL + CI + ")V", "onPlace(" + LEVEL + ")V"));
        byte[] callbackOnly = mixin("QuietMixin", BLOCK, c -> handler(c, "onPlace", 0, "(" + CI + ")V", "onPlace(" + LEVEL + ")V"));

        MethodNode handler = method(repair.apply(fits), "onPlace");
        assertEquals("(" + LEVEL + POS + CI + ")V", handler.desc);
        assertEquals(List.of("onPlace(" + LEVEL + POS + ")V"), selectors(handler), "the selector names the overload");
        assertArrayEquals(ambiguous, repair.apply(ambiguous), "both overloads take a Level first");
        assertArrayEquals(callbackOnly, repair.apply(callbackOnly), "the callback alone fits every overload");
    }

    @Test
    void aRefmapEntryMovesWithTheRename() {
        assertEquals("L" + MINECRAFT + ";resizeGui()V",
                MixinSelectorRenames.renameResourceSelector(host, "L" + MINECRAFT + ";resizeDisplay()V"));
        assertEquals("L" + MINECRAFT + ";pickBlockOrEntity()V",
                MixinSelectorRenames.renameResourceSelector(host, "L" + MINECRAFT + ";pickBlock()V"));
    }

    @Test
    void aRefmapRenameWithNewParametersWaitsForTheHandler() {
        // A Yarn key the class repair cannot resolve: only the refmap names the old method.
        String refmap = "{\"mappings\":{\"mod/PressMixin\":{\"onMouseButton\":\"L" + MOUSE + ";onPress(JIII)V\"}}}";
        byte[] quiet = mixin("PressMixin", MOUSE, c -> handler(c, "onPress", 0, "(" + CI + ")V", "onMouseButton"));
        byte[] reading = mixin("PressMixin", MOUSE, c -> handler(c, "onPress", 0, "(JIII" + CI + ")V", "onMouseButton"));

        assertEquals("L" + MOUSE + ";onButton(J" + BUTTON_INFO + "I)V", entry(MixinRefmapTargetRepair.apply(refmap,
                name -> quiet, host)), "a handler that takes only the callback fits the one onButton");
        assertSame(refmap, MixinRefmapTargetRepair.apply(refmap, name -> reading, host),
                "a handler still in the old layout keeps the entry, so the two never disagree");
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static String entry(String refmap) {
        return JsonParser.parseString(refmap).getAsJsonObject().getAsJsonObject("mappings")
                .getAsJsonObject("mod/PressMixin").get("onMouseButton").getAsString();
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static byte[] hostClass(String name, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(ClassWriter writer, int access, String name, String desc) {
        MethodVisitor body = writer.visitMethod(access == 0 ? Opcodes.ACC_PUBLIC : access, name, desc, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.ACONST_NULL);
        body.visitInsn(Opcodes.ATHROW);
        body.visitMaxs(1, Type.getArgumentsAndReturnSizes(desc) >> 2);
        body.visitEnd();
    }

    private static byte[] mixin(String simpleName, String target, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/" + simpleName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A HEAD handler that reads nothing. */
    private static void handler(ClassWriter writer, String name, int access, String desc, String selector) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | access, name, desc, null, null);
        inject(handler, selector);
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, Type.getArgumentsAndReturnSizes(desc) >> 2);
        handler.visitEnd();
    }

    /** A HEAD handler that reads its first capture. */
    private static void readingHandler(ClassWriter writer, String desc, String selector) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE, "onPlace", desc, null, null);
        inject(handler, selector);
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 1);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(1, Type.getArgumentsAndReturnSizes(desc) >> 2);
        handler.visitEnd();
    }

    private static void inject(MethodVisitor handler, String selector) {
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
    }

    private static MethodNode method(byte[] classBytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> selectors(MethodNode method) {
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (!annotation.desc.equals(INJECT)) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals("method")) return (List<Object>) annotation.values.get(i + 1);
            }
        }
        throw new AssertionError("no @Inject on " + method.name);
    }
}
