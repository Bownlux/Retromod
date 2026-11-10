/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A refmap entry follows the host method its repaired {@code @Inject} handler fits, and only then. */
class MixinRefmapTargetRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    // Balm 1.20.1's view of loadResources, and 26.2's.
    private static final String RELOADABLE = "net/minecraft/server/ReloadableServerResources";
    private static final String EXECUTOR = "Ljava/util/concurrent/Executor;";
    private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
    private static final String OLD_ARGS = "Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/core/RegistryAccess$Frozen;Lnet/minecraft/world/flag/FeatureFlagSet;"
            + "Lnet/minecraft/commands/Commands$CommandSelection;I" + EXECUTOR + EXECUTOR;
    private static final String NEW_ARGS = "Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/core/LayeredRegistryAccess;Ljava/util/List;Lnet/minecraft/world/flag/FeatureFlagSet;"
            + "Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/server/permissions/PermissionSet;"
            + EXECUTOR + EXECUTOR;
    private static final String OLD_ENTRY = "L" + RELOADABLE + ";loadResources(" + OLD_ARGS + ")" + FUTURE;
    private static final String NEW_ENTRY = "L" + RELOADABLE + ";loadResources(" + NEW_ARGS + ")" + FUTURE;

    // A name the host overloads: two flags were appended to one overload, the other is unrelated.
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";

    @TempDir
    Path dir;

    private FuzzyMethodResolver host;

    @BeforeEach
    void indexHost() throws IOException {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(RELOADABLE, writer -> method(writer, Opcodes.ACC_STATIC, "loadResources",
                    "(" + NEW_ARGS + ")" + FUTURE)));
            put(out, hostClass(BLOCK, writer -> {
                method(writer, 0, "updateShape", "(" + STATE + POS + "ZZ)V");
                method(writer, 0, "updateShape", "(" + STATE + ")V");
            }));
        }
        host = new FuzzyMethodResolver();
        host.indexJar(jar);
    }

    @Test
    void balmsEntryMovesWithTheHandlerTheDriftRepairRetyped() {
        byte[] old = mixin("mod/ReloadMixin", RELOADABLE, writer ->
                handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + OLD_ARGS + CIR + ")V", "loadResources", "RETURN"));
        byte[] repaired = new MixinHostDriftRepair(host, Map.of(), Map.of()).apply(old);
        String refmap = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);

        assertEquals("(" + CIR + ")V", method(repaired, "onReload").desc, "the class side takes the callback alone");
        assertEquals(NEW_ENTRY, entry(apply(refmap, "mod/ReloadMixin", repaired), "mod/ReloadMixin", "loadResources"),
                "the refmap names the method the repaired handler now fits");
        assertSame(refmap, apply(refmap, "mod/ReloadMixin", old),
                "without the class repair the handler fits nothing, so the entry stays as it was");
    }

    @Test
    void aFullLayoutPicksOneOverloadOfAnOverloadedName() {
        byte[] mixin = mixin("mod/ShapeMixin", BLOCK, writer ->
                handler(writer, "onShape", 0, "(" + STATE + POS + "ZZ" + CI + ")V", "updateShape", "HEAD"));
        String refmap = refmap("mod/ShapeMixin", "updateShape", "L" + BLOCK + ";updateShape(" + STATE + POS + ")V");

        assertEquals("L" + BLOCK + ";updateShape(" + STATE + POS + "ZZ)V",
                entry(apply(refmap, "mod/ShapeMixin", mixin), "mod/ShapeMixin", "updateShape"));
    }

    @Test
    void aCallbackOnlyHandlerLeavesAnOverloadedNameAlone() {
        byte[] mixin = mixin("mod/ShapeMixin", BLOCK, writer ->
                handler(writer, "onShape", 0, "(" + CI + ")V", "updateShape", "HEAD"));
        String refmap = refmap("mod/ShapeMixin", "updateShape", "L" + BLOCK + ";updateShape(" + STATE + POS + ")V");

        assertSame(refmap, apply(refmap, "mod/ShapeMixin", mixin),
                "the callback fits both overloads, so the name cannot pick one");
    }

    @Test
    void anInvokePointHandlerMovesItsEntryLikeAReturnPoint() {
        String refmap = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);
        byte[] atInvoke = mixin("mod/ReloadMixin", RELOADABLE, writer ->
                handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "INVOKE"));

        assertEquals(NEW_ENTRY, entry(apply(refmap, "mod/ReloadMixin", atInvoke), "mod/ReloadMixin", "loadResources"),
                "every injection point passes the target's own arguments");
    }

    @Test
    void aHandlerWithLocalCapturesMovesItsEntryByTheArgumentsBeforeThem() {
        String refmap = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);
        byte[] sugared = mixin("mod/ReloadMixin", RELOADABLE, writer -> {
            MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "onReload",
                    "(" + CIR + "Ljava/lang/String;)V", null, null);
            AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
            AnnotationVisitor methods = inject.visitArray("method");
            methods.visit(null, "loadResources");
            methods.visitEnd();
            AnnotationVisitor ats = inject.visitArray("at");
            AnnotationVisitor at = ats.visitAnnotation(null, AT);
            at.visit("value", "RETURN");
            at.visitEnd();
            ats.visitEnd();
            inject.visitEnd();
            handler.visitParameterAnnotation(1, "Lcom/llamalad7/mixinextras/sugar/Local;", false).visitEnd();
            handler.visitCode();
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 2);
            handler.visitEnd();
        });

        assertEquals(NEW_ENTRY, entry(apply(refmap, "mod/ReloadMixin", sugared), "mod/ReloadMixin", "loadResources"),
                "MixinExtras takes the @Local off before Mixin compares the rest with the target");
    }

    @Test
    void anInjectionTheDriftRepairDeclinesKeepsItsEntry() {
        String refmap = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);
        byte[] capturesLocals = mixin("mod/ReloadMixin", RELOADABLE, writer ->
                handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "INVOKE", true));
        byte[] sharedKey = mixin("mod/ReloadMixin", RELOADABLE, writer -> {
            handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "RETURN");
            handler(writer, "onCall", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "INVOKE", true);
        });

        assertSame(refmap, apply(refmap, "mod/ReloadMixin", capturesLocals), "a locals capture is not retypable");
        assertSame(refmap, apply(refmap, "mod/ReloadMixin", sharedKey),
                "every use of a key follows the entry, so one the repair declines pins it");
    }

    @Test
    void aKeyAnotherInjectorSharesStaysPinned() {
        String refmap = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);
        byte[] withRedirect = mixin("mod/ReloadMixin", RELOADABLE, writer -> {
            handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "RETURN");
            MethodVisitor redirect = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "onSupply",
                    "(Ljava/lang/Object;)I", null, null);
            AnnotationVisitor annotation = redirect.visitAnnotation(
                    "Lorg/spongepowered/asm/mixin/injection/Redirect;", true);
            AnnotationVisitor methods = annotation.visitArray("method");
            methods.visit(null, "loadResources");
            methods.visitEnd();
            AnnotationVisitor at = annotation.visitAnnotation("at", AT);
            at.visit("value", "INVOKE");
            at.visit("target", "Ljava/lang/Object;hashCode()I");
            at.visitEnd();
            annotation.visitEnd();
            redirect.visitCode();
            redirect.visitInsn(Opcodes.ICONST_0);
            redirect.visitInsn(Opcodes.IRETURN);
            redirect.visitMaxs(1, 1);
            redirect.visitEnd();
        });

        assertSame(refmap, apply(refmap, "mod/ReloadMixin", withRedirect),
                "the redirect resolves the same entry, and its handler was never retyped");
    }

    @Test
    void aShadowFieldNamedLikeTheKeyPinsTheEntry() {
        String refmap = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);
        byte[] mixin = mixin("mod/ReloadMixin", RELOADABLE, writer -> {
            handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "RETURN");
            writer.visitField(Opcodes.ACC_PRIVATE, "loadResources", "Ljava/lang/Object;", null, null)
                    .visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
        });

        assertSame(refmap, apply(refmap, "mod/ReloadMixin", mixin), "the shadow field also resolves through that key");
    }

    @Test
    void anEntryThatResolvesOrAMissingClassIsLeftAlone() {
        byte[] mixin = mixin("mod/ReloadMixin", RELOADABLE, writer ->
                handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "RETURN"));
        String current = refmap("mod/ReloadMixin", "loadResources", NEW_ENTRY);

        assertSame(current, apply(current, "mod/ReloadMixin", mixin));
        String stale = refmap("mod/ReloadMixin", "loadResources", OLD_ENTRY);
        assertSame(stale, MixinRefmapTargetRepair.apply(stale, name -> null, host), "no class, no proof");
    }

    @Test
    void everySectionMixinReadsIsRepairedAlike() {
        byte[] mixin = mixin("mod/ReloadMixin", RELOADABLE, writer ->
                handler(writer, "onReload", Opcodes.ACC_STATIC, "(" + CIR + ")V", "loadResources", "RETURN"));
        String refmap = "{\"mappings\":{\"mod/ReloadMixin\":{\"loadResources\":\"" + OLD_ENTRY + "\"}},"
                + "\"data\":{\"searge\":{\"mod/ReloadMixin\":{\"loadResources\":\"" + OLD_ENTRY + "\"}}}}";

        JsonObject root = JsonParser.parseString(apply(refmap, "mod/ReloadMixin", mixin)).getAsJsonObject();

        assertEquals(NEW_ENTRY, root.getAsJsonObject("mappings").getAsJsonObject("mod/ReloadMixin")
                .get("loadResources").getAsString());
        assertEquals(NEW_ENTRY, root.getAsJsonObject("data").getAsJsonObject("searge")
                .getAsJsonObject("mod/ReloadMixin").get("loadResources").getAsString());
    }

    @Test
    void anExtractedJarAnswersOnlyItsOwnClasses() throws IOException {
        Path jar = Files.createDirectories(dir.resolve("jar/mod"));
        Files.write(jar.resolve("ReloadMixin.class"), new byte[] {1});
        Files.write(dir.resolve("outside.class"), new byte[] {2});
        var classes = MixinRefmapTargetRepair.classesIn(dir.resolve("jar"));

        assertEquals(1, classes.apply("mod/ReloadMixin").length);
        assertNull(classes.apply("../outside"), "a refmap name that leaves the folder answers nothing");
    }

    // ---- fixtures ------------------------------------------------------------------------

    private String apply(String refmap, String mixinName, byte[] mixin) {
        return MixinRefmapTargetRepair.apply(refmap, name -> name.equals(mixinName) ? mixin : null, host);
    }

    private static String refmap(String mixin, String key, String value) {
        return "{\"mappings\":{\"" + mixin + "\":{\"" + key + "\":\"" + value + "\"}}}";
    }

    private static String entry(String refmap, String mixin, String key) {
        return JsonParser.parseString(refmap).getAsJsonObject().getAsJsonObject("mappings")
                .getAsJsonObject(mixin).get(key).getAsString();
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static byte[] hostClass(String name, java.util.function.Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(ClassWriter writer, int access, String name, String desc) {
        MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC | access, name, desc, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.ACONST_NULL);
        body.visitInsn(Opcodes.ATHROW);
        body.visitMaxs(1, Type.getArgumentsAndReturnSizes(desc) >> 2);
        body.visitEnd();
    }

    private static byte[] mixin(String name, String target, java.util.function.Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A handler at {@code point} that reads nothing. */
    private static void handler(ClassWriter writer, String name, int access, String desc, String selector,
            String point) {
        handler(writer, name, access, desc, selector, point, false);
    }

    /** As above; {@code capturesLocals} adds Mixin's own {@code locals} capture, which the drift repair declines. */
    private static void handler(ClassWriter writer, String name, int access, String desc, String selector,
            String point, boolean capturesLocals) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | access, name, desc, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", point);
        if (point.equals("INVOKE")) at.visit("target", "Ljava/lang/Object;hashCode()I");
        at.visitEnd();
        ats.visitEnd();
        if (capturesLocals) {
            inject.visitEnum("locals", "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;",
                    "CAPTURE_FAILHARD");
        }
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, Type.getArgumentsAndReturnSizes(desc) >> 2);
        handler.visitEnd();
    }

    private static MethodNode method(byte[] classBytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
