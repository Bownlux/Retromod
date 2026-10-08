/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Host-proven Mixin repairs for 26.x member drift (Hold My Items, #281/#312). */
class MixinHostDriftRepairTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    private static final String SECTIONS = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;";
    private static final String DELTA_TRACKER = "Lnet/minecraft/client/DeltaTracker;";
    private static final String FEATURES = "net/minecraft/client/renderer/feature/FeatureRenderDispatcher";
    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String LOCAL_PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String HAND = "Lnet/minecraft/world/InteractionHand;";
    private static final String ANIMATION = "Lnet/minecraft/world/item/component/SwingAnimation;";
    private static final String SWING = "net/minecraft/world/entity/LivingEntity$SwingDescription";
    private static final String RENDERER = "net/minecraft/client/renderer/ItemInHandRenderer";
    private static final String OLD_SELECTION = "net/minecraft/client/renderer/ItemInHandRenderer$HandRenderSelection";
    private static final String NEW_SELECTION =
            "net/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState$HandRenderSelection";

    @TempDir
    Path tempDir;

    private MixinHostDriftRepair repair;
    private FuzzyMethodResolver host;

    @BeforeEach
    void indexHost() throws IOException {
        Path jar = tempDir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, hostClass(GAME_RENDERER, "java/lang/Object", c -> method(c, 0, "render", "()V")));
            put(out, hostClass(LEVEL_RENDERER, "java/lang/Object",
                    c -> method(c, 0, "render", "(" + DELTA_TRACKER + "Z)V")));
            put(out, hostClass(FEATURES, "java/lang/Object",
                    c -> method(c, Opcodes.ACC_STATIC, "renderAllFeatures", "(Ljava/lang/Object;)V")));
            put(out, hostClass(CAMERA, "java/lang/Object", c -> {
                c.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "FORWARDS",
                        "Lorg/joml/Vector3fc;", null, null).visitEnd();
                method(c, 0, "setRotation", "(FF)V");
            }));
            put(out, hostClass(LIVING, "java/lang/Object", c -> {
                method(c, 0, "swing", "(" + HAND + ANIMATION + "Z)Z");
                method(c, Opcodes.ACC_PRIVATE, "getModifiedSwingDuration", "(" + ANIMATION + ")I");
                method(c, 0, "getCurrentSwing", "()L" + SWING + ";");
                method(c, 0, "getMainHandItem", "()L" + STACK + ";");
            }));
            put(out, hostClass(LOCAL_PLAYER, LIVING, c -> {}));
            put(out, hostClass(STACK, "java/lang/Object",
                    c -> method(c, 0, "getAttackAnimation", "()" + ANIMATION)));
            put(out, hostClass(MINECRAFT, "java/lang/Object", c -> method(c, 0, "startUseItem", "()V")));
            put(out, hostClass(RENDERER, "java/lang/Object",
                    c -> method(c, 0, "submitArmWithItem", "(F)V")));
            put(out, hostClass(NEW_SELECTION, "java/lang/Object", c -> {}));
            put(out, hostClass("net/minecraft/world/InteractionHand", "java/lang/Object", c -> {}));
        }
        host = new FuzzyMethodResolver();
        host.indexJar(jar);
        assertTrue(host.isIndexed(), "the synthetic host jar must be indexed");
        repair = new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(RENDERER, "renderArmWithItem", "(F)V"),
                        new RetromodTransformer.MethodTarget(RENDERER, "submitArmWithItem", "(F)V", false)),
                Map.of(OLD_SELECTION, NEW_SELECTION));
    }

    @Test
    void dropsCapturesTheHandlerNeverReadsWhenTheTargetLostThem() {
        byte[] mixin = mixin("GameRendererMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "deltaTime", "(" + DELTA_TRACKER + "Z" + CI + ")V",
                    "render", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 4);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "deltaTime");

        assertEquals("(" + CI + ")V", handler.desc,
                "GameRenderer.render() lost both parameters, so only the callback may remain");
    }

    @Test
    void dropsAnUnreadTrailingCaptureAndKeepsTheLeadingOnes() {
        byte[] mixin = mixin("LevelRendererMixin", LEVEL_RENDERER, c -> {
            MethodVisitor handler = inject(c, "beforeRender",
                    "(" + DELTA_TRACKER + "Z" + SECTIONS + CI + ")V", "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ILOAD, 2);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ALOAD, 4);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 5);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "beforeRender");

        assertEquals("(" + DELTA_TRACKER + "Z" + CI + ")V", handler.desc,
                "26.2 dropped render's trailing parameter; the unread capture goes, the rest stay");
        VarInsnNode callback = (VarInsnNode) handler.instructions.get(4);
        assertEquals(3, callback.var, "the callback moves down into the dropped capture's slot");
    }

    @Test
    void keepsATrailingDropWhoseTypeAKeptCaptureShares() {
        byte[] mixin = mixin("AmbiguousDropMixin", LEVEL_RENDERER, c -> {
            MethodVisitor handler = inject(c, "beforeRender",
                    "(" + DELTA_TRACKER + "ZZ" + CI + ")V", "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ILOAD, 2);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 5);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "the host may have dropped the first boolean, so the read one could change meaning");
    }

    @Test
    void keepsAHandlerThatReadsACaptureTheTargetLost() {
        byte[] mixin = mixin("ReadsCaptureMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "deltaTime", "(" + DELTA_TRACKER + "Z" + CI + ")V",
                    "render", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "a handler that uses a removed capture has no safe layout and must be left alone");
    }

    @Test
    void leavesASelectorWrittenInRefmapSourceNamesAlone() {
        // Yarn source text: Mixin resolves it through the mod's refmap, which may name a
        // different host method than the same-named GameRenderer.render().
        byte[] mixin = mixin("YarnSelectorMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "deltaTime", "(" + DELTA_TRACKER + "Z" + CI + ")V",
                    "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 4);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin),
                "a selector naming classes the host lacks is a refmap key, not proof of the target");
    }

    @Test
    void makesAHandlerStaticWhenItsTargetBecameStatic() {
        byte[] mixin = mixin("FeatureMixin", FEATURES, c -> {
            MethodVisitor handler = inject(c, "tester", "(" + CI + ")V", "renderAllFeatures", "TAIL", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 2);
            handler.visitEnd();
        });

        MethodNode handler = method(repair.apply(mixin), "tester");

        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0,
                "the handler never reads this, so it can follow its target to static");
        assertEquals("(" + CI + ")V", handler.desc);
    }

    @Test
    void retypesAReadOnlyJomlShadowAndCastsEachRead() {
        byte[] mixin = mixin("CameraMixin", CAMERA, c -> {
            FieldVisitor field = c.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "FORWARDS",
                    "Lorg/joml/Vector3f;", null, null);
            field.visitAnnotation(SHADOW, true).visitEnd();
            field.visitEnd();
            MethodVisitor reader = c.visitMethod(Opcodes.ACC_PUBLIC, "read", "()Ljava/lang/Object;", null, null);
            reader.visitCode();
            reader.visitFieldInsn(Opcodes.GETSTATIC, "test/mixin/CameraMixin", "FORWARDS", "Lorg/joml/Vector3f;");
            reader.visitInsn(Opcodes.ARETURN);
            reader.visitMaxs(1, 1);
            reader.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));
        FieldNode forwards = repaired.fields.get(0);
        MethodNode reader = method(repaired, "read");
        FieldInsnNode get = first(reader, FieldInsnNode.class);

        assertEquals("Lorg/joml/Vector3fc;", forwards.desc, "the shadow must match the host's read-only type");
        assertEquals("Lorg/joml/Vector3fc;", get.desc);
        assertTrue(get.getNext() instanceof TypeInsnNode cast && cast.desc.equals("org/joml/Vector3f"),
                "each read must be cast back to the type the mixin body uses");
    }

    @Test
    void bridgesTheRemovedSwingDurationShadowToTheSwingState() {
        byte[] mixin = mixin("LivingEntityMixin", LIVING, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                    "getCurrentSwingDuration", "()I", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        }, true);

        ClassNode repaired = read(repair.apply(mixin));
        MethodNode bridge = method(repaired, "getCurrentSwingDuration");

        assertTrue(hasAnnotation(bridge, UNIQUE), "the old shadow must become mixin-owned");
        assertFalse(hasAnnotation(bridge, SHADOW));
        assertTrue(calls(bridge, "getCurrentSwing") && calls(bridge, "getModifiedSwingDuration"),
                "the bridge must read the running swing and fall back to the modified duration");
        assertNotNull(method(repaired, "getModifiedSwingDuration"),
                "the private host method needs a shadow so the bridge can call it");
    }

    @Test
    void retypesAnInjectForATargetThatGainedAParameterAndAReturnValue() {
        byte[] mixin = mixin("SwingMixin", LIVING, c -> {
            MethodVisitor handler = inject(c, "onSwingHand", "(" + HAND + "Z" + CI + ")V",
                    "swing(" + HAND + "Z)V", "HEAD", 0);
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitInsn(Opcodes.POP);
            handler.visitVarInsn(Opcodes.ILOAD, 2);
            handler.visitInsn(Opcodes.POP);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));
        MethodNode handler = method(repaired, "onSwingHand");

        assertEquals("(" + HAND + ANIMATION + "Z" + CIR + ")V", handler.desc,
                "the new SwingAnimation slot is inserted and the unread callback becomes returnable");
        assertEquals(List.of("swing"), selectors(handler, INJECT));
        assertEquals(3, ((org.objectweb.asm.tree.VarInsnNode) handler.instructions.toArray()[2]).var,
                "the boolean capture moves past the inserted SwingAnimation slot");
    }

    @Test
    void regrowsARedirectWhoseCallGainedTrailingParameters() {
        String oldCall = "L" + LOCAL_PLAYER + ";swing(" + HAND + ")V";
        byte[] mixin = mixin("UseItemMixin", MINECRAFT, c -> {
            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "doItemUse",
                    "(L" + LOCAL_PLAYER + ";" + HAND + ")V", null, null);
            AnnotationVisitor redirect = handler.visitAnnotation(REDIRECT, true);
            AnnotationVisitor methods = redirect.visitArray("method");
            methods.visit(null, "startUseItem");
            methods.visitEnd();
            AnnotationVisitor at = redirect.visitAnnotation("at", AT);
            at.visit("value", "INVOKE");
            at.visit("target", oldCall);
            at.visitEnd();
            redirect.visitEnd();
            handler.visitCode();
            handler.visitVarInsn(Opcodes.ALOAD, 1);
            handler.visitVarInsn(Opcodes.ALOAD, 2);
            handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOCAL_PLAYER, "swing", "(" + HAND + ")V", false);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(2, 3);
            handler.visitEnd();
        });

        MethodNode handler = method(read(repair.apply(mixin)), "doItemUse");
        MethodInsnNode passThrough = first(handler, MethodInsnNode.class);

        assertEquals("(L" + LOCAL_PLAYER + ";" + HAND + ANIMATION + "Z)Z", handler.desc,
                "the handler must accept and return what the new call does");
        assertEquals("(" + HAND + ANIMATION + "Z)Z", passThrough.desc,
                "the pass-through call must forward the new arguments");
    }

    @Test
    void renamesAShadowThroughAHostProvenMethodRedirect() {
        byte[] mixin = mixin("ArmMixin", RENDERER, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PRIVATE, "renderArmWithItem", "(F)V", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitCode();
            shadow.visitInsn(Opcodes.RETURN);
            shadow.visitMaxs(0, 2);
            shadow.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertNotNull(method(repaired, "submitArmWithItem"), "26.2 renamed the arm call to submitArmWithItem");
        assertNull(method(repaired, "renderArmWithItem"));
    }

    @Test
    void turnsAShadowWhoseCallsGoToAnAdapterIntoAnAdapterCall() {
        String attribute = "Lnet/minecraft/world/entity/ai/attributes/Attribute;";
        String instance = "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;";
        String oldDesc = "(" + attribute + ")" + instance;
        String adapter = "com/retromod/generated/LegacyRegistryHolders";
        String adapterDesc = "(L" + LIVING + ";Ljava/lang/Object;)" + instance;
        MixinHostDriftRepair adapting = new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(LIVING, "getAttribute", oldDesc),
                        new RetromodTransformer.MethodTarget(adapter, "LivingEntity$getAttribute", adapterDesc)),
                Map.of());
        byte[] mixin = mixin("AttributeMixin", LIVING, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "getAttribute", oldDesc, null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        }, true);

        MethodNode bridged = method(adapting.apply(mixin), "getAttribute");

        assertFalse(hasAnnotation(bridged, SHADOW), "the host has no getAttribute(Attribute) to shadow");
        assertTrue(hasAnnotation(bridged, UNIQUE));
        assertEquals(0, bridged.access & Opcodes.ACC_ABSTRACT);
        MethodInsnNode call = first(bridged, MethodInsnNode.class);
        assertEquals(adapter, call.owner);
        assertEquals(adapterDesc, call.desc, "the mixin must call the same adapter its callers use");
    }

    @Test
    void leavesAnAccessorInterfaceShadowAlone() {
        String oldDesc = "(Ljava/lang/Object;)V";
        MixinHostDriftRepair adapting = new MixinHostDriftRepair(host,
                Map.of(new RetromodTransformer.MethodKey(LIVING, "tick", oldDesc),
                        new RetromodTransformer.MethodTarget("com/retromod/generated/Adapters",
                                "tick", "(L" + LIVING + ";Ljava/lang/Object;)V")),
                Map.of());
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "test/mixin/TickAccessor", null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = annotation.visitArray("value");
        value.visit(null, Type.getObjectType(LIVING));
        value.visitEnd();
        annotation.visitEnd();
        MethodVisitor shadow = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tick", oldDesc,
                null, null);
        shadow.visitAnnotation(SHADOW, true).visitEnd();
        shadow.visitEnd();
        writer.visitEnd();
        byte[] mixin = writer.toByteArray();

        assertArrayEquals(mixin, adapting.apply(mixin), "an interface mixin is not merged into its target");
    }

    @Test
    void dropsAShadowFieldTheMixinNeverUsesWhenTheHostChangedIt() {
        byte[] mixin = mixin("SelectorMixin", LIVING, c -> {
            FieldVisitor field = c.visitField(Opcodes.ACC_PRIVATE, "predicate",
                    "Ljava/util/function/Predicate;", null, null);
            field.visitAnnotation(SHADOW, true).visitEnd();
            field.visitEnd();
        });

        ClassNode repaired = read(repair.apply(mixin));

        assertTrue(repaired.fields.isEmpty(),
                "an unused shadow of a field the host no longer has in that shape only fails validation");
    }

    @Test
    void keepsAShadowFieldTheMixinReads() {
        byte[] mixin = mixin("ReadsSelectorMixin", LIVING, c -> {
            FieldVisitor field = c.visitField(Opcodes.ACC_PRIVATE, "predicate",
                    "Ljava/util/function/Predicate;", null, null);
            field.visitAnnotation(SHADOW, true).visitEnd();
            field.visitEnd();
            MethodVisitor reader = c.visitMethod(Opcodes.ACC_PRIVATE, "read", "()V", null, null);
            reader.visitCode();
            reader.visitVarInsn(Opcodes.ALOAD, 0);
            reader.visitFieldInsn(Opcodes.GETFIELD, "test/mixin/ReadsSelectorMixin", "predicate",
                    "Ljava/util/function/Predicate;");
            reader.visitInsn(Opcodes.POP);
            reader.visitInsn(Opcodes.RETURN);
            reader.visitMaxs(1, 1);
            reader.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin), "a shadow the mixin uses must keep failing loudly");
    }

    @Test
    void movesAStringMixinTargetThroughAClassRedirect() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/SelectionMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, OLD_SELECTION);
        targets.visitEnd();
        mixin.visitEnd();
        writer.visitEnd();

        ClassNode repaired = read(repair.apply(writer.toByteArray()));

        assertEquals(List.of(NEW_SELECTION), annotationValue(repaired.invisibleAnnotations.get(0), "targets"),
                "a string target is annotation text, so the class move must be applied explicitly");
    }

    @Test
    void leavesAMixinAlreadyBuiltForTheHostByteIdentical() {
        byte[] mixin = mixin("CurrentMixin", GAME_RENDERER, c -> {
            MethodVisitor handler = inject(c, "onRender", "(" + CI + ")V", "render", "HEAD", 0);
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 2);
            handler.visitEnd();
        });

        assertArrayEquals(mixin, repair.apply(mixin));
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static byte[] hostClass(String name, String superName, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, superName, null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void method(ClassWriter writer, int access, String name, String desc) {
        if ((access & Opcodes.ACC_STATIC) == 0) {
            writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | access, name, desc, null, null)
                    .visitEnd();
            return;
        }
        MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC | access, name, desc, null, null);
        body.visitCode();
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(0, Type.getArgumentsAndReturnSizes(desc) >> 2);
        body.visitEnd();
    }

    private static byte[] mixin(String simpleName, String target, Consumer<ClassWriter> members) {
        return mixin(simpleName, target, members, false);
    }

    private static byte[] mixin(String simpleName, String target, Consumer<ClassWriter> members,
            boolean isAbstract) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | (isAbstract ? Opcodes.ACC_ABSTRACT : 0),
                "test/mixin/" + simpleName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodVisitor inject(ClassWriter writer, String name, String desc, String selector,
            String point, int extraAccess) {
        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | extraAccess, name, desc, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", point);
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        return handler;
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(byte[] classBytes, String name) {
        return method(read(classBytes), name);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }

    private static boolean calls(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean hasAnnotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations,
                method.invisibleAnnotations)) {
            if (list == null) continue;
            for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
        }
        return false;
    }

    private static List<Object> selectors(MethodNode method, String injectorDesc) {
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals(injectorDesc)) return annotationValue(annotation, "method");
        }
        throw new AssertionError("no " + injectorDesc + " on " + method.name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> annotationValue(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return (List<Object>) annotation.values.get(i + 1);
        }
        throw new AssertionError("no " + key + " on " + annotation.desc);
    }
}
