/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinCompatibilityTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A Mojang-named mod's Minecraft names follow the renames its host made since the mod's version. */
class MojangRenameTableTest {

    private static final String HEIGHT = "net/minecraft/world/level/LevelHeightAccessor";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String TOAST_COMPONENT = "net/minecraft/client/gui/components/toasts/ToastComponent";
    private static final String TOAST_MANAGER = "net/minecraft/client/gui/components/toasts/ToastManager";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";

    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;
    private final Map<String, byte[]> jar = new HashMap<>();

    @TempDir
    Path directory;

    @BeforeEach
    void onHost263() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        transformer = constructor.newInstance();
        transformer.setModSourceVersion("1.21.1");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("Only hosts from 1.21.2 take rows, and each row starts at the host that renamed it")
    void hostsBeforeTheRenameTakeNothing() {
        assertTrue(MojangRenameTable.forHost("1.20.4").isEmpty(), "an SRG-named Forge host takes no Mojang renames");
        assertTrue(MojangRenameTable.forHost("1.21.1").isEmpty(), "1.21.1 renamed nothing a 1.21.1 mod names");
        assertNotNull(MojangRenameTable.forHost("1.21.2").find(false, HEIGHT, "getMinBuildHeight", "()I", "1.21.1"));
        assertNull(MojangRenameTable.forHost("1.21.4").find(true, "net/minecraft/client/Options",
                "MOVEMENT_HOLD", "Lnet/minecraft/network/chat/Component;", "1.21.1"),
                "1.21.9 renamed MOVEMENT_HOLD, so a 1.21.4 host still has it");
    }

    @Test
    @DisplayName("Renames whose type arguments changed meaning are not in the table")
    void genericMeaningChangesAreLeftOut() {
        MojangRenameTable table = MojangRenameTable.forHost("26.3");
        assertNull(table.find(true, "net/minecraft/world/entity/vehicle/AbstractMinecart", "DATA_ID_DISPLAY_BLOCK",
                "Lnet/minecraft/network/syncher/EntityDataAccessor;", "1.21.1"),
                "EntityDataAccessor<Integer> became EntityDataAccessor<Optional<BlockState>>");
        assertNull(table.find(true, "net/minecraft/world/level/block/WallHangingSignBlock", "AABBS",
                "Ljava/util/Map;", "1.21.1"), "Map<Direction, VoxelShape> became Map<Direction.Axis, VoxelShape>");
        assertNull(table.find(false, "net/minecraft/world/level/block/entity/ContainerOpenersCounter",
                "getPlayersWithContainerOpen",
                "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ljava/util/List;", "1.21.1"),
                "List<Player> became List<ContainerUser>");
        assertNull(table.find(false, "net/minecraft/util/random/WeightedRandomList", "create",
                "(Ljava/util/List;)Lnet/minecraft/util/random/WeightedRandomList;", "1.21.1"),
                "WeightedEntry lists became lists of Weighted<E>");
        assertTrue(table.classRows().stream().noneMatch(row -> row.owner().equals("net/minecraft/util/random/WeightedRandomList")));
    }

    @Test
    @DisplayName("A call to a renamed method takes the host's name")
    void renamedMethodCall() {
        register();
        MethodNode run = method(callingClass(Opcodes.INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I"), "run");
        assertEquals(List.of(HEIGHT + ".getMinY()I"), calls(run));
    }

    @Test
    @DisplayName("A call through a host subclass is renamed where the host hierarchy reaches the declaring class")
    void callThroughHostSubclass() throws Exception {
        transformer.initFuzzyResolver(hostJar(
                new FakeClass(SERVER_LEVEL, LEVEL), new FakeClass(LEVEL, "java/lang/Object", HEIGHT),
                new FakeClass(HEIGHT, null, "getMinY()I")));
        register();
        MethodNode run = method(callingClass(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, "getMinBuildHeight", "()I"), "run");
        assertEquals(List.of(SERVER_LEVEL + ".getMinY()I"), calls(run));
    }

    @Test
    @DisplayName("A mod's override of a renamed method is renamed, so the host still calls it")
    void overrideOfRenamedMethod() {
        register();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/FlatHeight", null, "java/lang/Object", new String[]{HEIGHT});
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getMinBuildHeight", "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.BIPUSH, -64);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode transformed = transform("mod/FlatHeight", cw.toByteArray());

        assertNotNull(method(transformed, "getMinY"), "the override must implement the host's getMinY");
        assertNull(method(transformed, "getMinBuildHeight"));
    }

    @Test
    @DisplayName("A renamed field and a method reference take the host's names")
    void renamedFieldAndMethodReference() {
        register();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Zoom", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(L" + GAME_RENDERER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, GAME_RENDERER, "fov", "F");
        mv.visitInsn(Opcodes.POP);
        mv.visitInvokeDynamicInsn("applyAsInt", "()Ljava/util/function/ToIntFunction;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getType("(Ljava/lang/Object;)I"),
                new Handle(Opcodes.H_INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I", true),
                Type.getType("(L" + HEIGHT + ";)I"));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();

        MethodNode run = method(transform("mod/Zoom", cw.toByteArray()), "run");

        FieldInsnNode read = (FieldInsnNode) first(run, FieldInsnNode.class);
        assertEquals("fovModifier", read.name);
        InvokeDynamicInsnNode reference = (InvokeDynamicInsnNode) first(run, InvokeDynamicInsnNode.class);
        assertEquals("getMinY", ((Handle) reference.bsmArgs[1]).getName());
    }

    @Test
    @DisplayName("A renamed class is redirected only where the host has the new class and not the old one")
    void renamedClass() throws Exception {
        transformer.initFuzzyResolver(hostJar(new FakeClass(TOAST_MANAGER, "java/lang/Object")));
        register();
        assertEquals(TOAST_MANAGER, transformer.getClassRedirects().get(TOAST_COMPONENT));

        RetromodTransformer keeps = freshTransformer();
        keeps.initFuzzyResolver(hostJar(new FakeClass(TOAST_MANAGER, "java/lang/Object"),
                new FakeClass(TOAST_COMPONENT, "java/lang/Object")));
        MojangRenameTable.register(keeps, "26.3");
        assertNull(keeps.getClassRedirects().get(TOAST_COMPONENT), "a host that still has the class keeps it");
        assertNull(transformer.getClassRedirects().get("net/minecraft/client/renderer/item/ItemPropertyFunction"),
                "Retromod rebuilds ItemPropertyFunction, so its bridge, not 1.21.4's range property, takes it");
    }

    @Test
    @DisplayName("A mixin's shadow, own call, selectors, invoker, accessor and refmap entry follow the rename")
    void mixinFollowsTheRename() {
        register();
        byte[] mixin = mixinClass();
        byte[] invoker = accessorMixin("mod/mixin/HeightInvoker", HEIGHT, "callMinHeight", "()I", INVOKER,
                "getMinBuildHeight");
        byte[] accessor = accessorMixin("mod/mixin/RendererAccessor", GAME_RENDERER, "getFov", "()F", ACCESSOR,
                "fov");
        jar.put("mod/mixin/HeightMixin", mixin);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);

        ClassNode transformed = transform("mod/mixin/HeightMixin", mixins.transformMixinClass(mixin));
        ClassNode invoked = transform("mod/mixin/HeightInvoker", mixins.transformMixinClass(invoker));
        ClassNode accessed = transform("mod/mixin/RendererAccessor", mixins.transformMixinClass(accessor));

        assertNotNull(method(transformed, "getMinY"), "the @Shadow must name the host method");
        MethodNode handler = method(transformed, "onHeight");
        assertEquals(List.of("mod/mixin/HeightMixin.getMinY()I"), calls(handler));
        AnnotationNode inject = handler.visibleAnnotations.get(0);
        assertEquals(List.of("getMinY", "getMinY()I"), value(inject, "method"));
        AnnotationNode at = (AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("L" + HEIGHT + ";getMinY()I", value(at, "target"));
        assertEquals("getMinY", value(method(invoked, "callMinHeight").visibleAnnotations.get(0), "value"));
        assertEquals("fovModifier", value(method(accessed, "getFov").visibleAnnotations.get(0), "value"));
        assertEquals("L" + HEIGHT + ";getMinY()I",
                mixins.remapResourceSelector("L" + HEIGHT + ";getMinBuildHeight()I"));
    }

    @Test
    @DisplayName("A mixin on a renamed class keeps its old target and stays inert, while other classes follow the rename")
    void mixinOnARenamedClassStaysInert() throws Exception {
        transformer.initFuzzyResolver(hostJar(new FakeClass(TOAST_MANAGER, "java/lang/Object")));
        register();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/ToastMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(TOAST_COMPONENT));
        targets.visitEnd();
        mixin.visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "owner", "L" + TOAST_COMPONENT + ";", null, null).visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        jar.put("mod/mixin/ToastMixin", bytes);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);

        ClassNode transformed = transform("mod/mixin/ToastMixin", mixins.transformMixinClass(bytes));

        assertEquals(List.of(Type.getObjectType(TOAST_COMPONENT)), value(transformed.invisibleAnnotations.get(0), "value"),
                "the mixin never applied to ToastManager, so its handlers were never checked against it");
        assertEquals("L" + TOAST_COMPONENT + ";", transformed.fields.get(0).desc);
        MethodNode run = method(callingClass(Opcodes.INVOKEVIRTUAL, TOAST_COMPONENT, "freeSlots", "()I"), "run");
        assertEquals(List.of(TOAST_MANAGER + ".freeSlotCount()I"), calls(run), "a plain class follows the rename");
    }

    @Test
    @DisplayName("A mod built after the rename keeps its name")
    void newerModKeepsItsName() {
        register();
        for (String version : List.of("1.21.2", "1.21.4", "26.3")) {
            transformer.setModSourceVersion(version);
            MethodNode run = method(callingClass(Opcodes.INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I"), "run");
            assertEquals(List.of(HEIGHT + ".getMinBuildHeight()I"), calls(run), "built for " + version);
        }
    }

    @Test
    @DisplayName("A host before the rename, and a namespace without the table, keep the old name")
    void olderHostsAndFabricKeepTheName() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        MojangRenameTable.register(transformer, "1.21.1");
        assertNull(transformer.getMojangRenames());
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.setMojangRenames(null);
        // The redirect keeps the remapper running, as other registrations do for a Fabric mod.
        transformer.registerClassRedirect("mod/Unrelated", "mod/Other");
        MethodNode run = method(callingClass(Opcodes.INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I"), "run");
        assertEquals(List.of(HEIGHT + ".getMinBuildHeight()I"), calls(run));
    }

    @Test
    @DisplayName("A shim written for the old name keeps the call")
    void shimForTheOldNameWins() {
        transformer.registerMethodRedirect(HEIGHT, "getMinBuildHeight", "()I",
                "mod/compat/Heights", "minHeight", "(L" + HEIGHT + ";)I", true);
        register();
        MethodNode run = method(callingClass(Opcodes.INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I"), "run");
        assertEquals(List.of("mod/compat/Heights.minHeight(L" + HEIGHT + ";)I"), calls(run));
    }

    @Test
    @DisplayName("A class Retromod rebuilds keeps the member names the mod calls on it")
    void rebuiltClassKeepsItsMembers() {
        // Forge's BakedModel bridge points the old interface at a rebuilt copy that still has getOverrides.
        String bakedModel = "net/minecraft/client/resources/model/BakedModel";
        String overridesDesc = "()Lnet/minecraft/client/renderer/block/model/ItemOverrides;";
        transformer.registerClassRedirect(bakedModel, "com/retromod/legacyapi/forge/client/model/BakedModel");
        register();
        assertNotNull(transformer.getMojangRenames().find(false, bakedModel, "getOverrides", overridesDesc, "1.21.1"),
                "the table renames BakedModel.getOverrides for vanilla 1.21.2");

        MethodNode run = method(callingClass(Opcodes.INVOKEINTERFACE, bakedModel, "getOverrides", overridesDesc), "run");

        assertEquals("getOverrides", ((MethodInsnNode) first(run, MethodInsnNode.class)).name);
    }

    @Test
    @DisplayName("A host that still declares the old name keeps the call")
    void hostThatKeepsTheOldName() throws Exception {
        transformer.initFuzzyResolver(hostJar(new FakeClass(HEIGHT, null, "getMinY()I", "getMinBuildHeight()I")));
        register();
        MethodNode run = method(callingClass(Opcodes.INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I"), "run");
        assertEquals(List.of(HEIGHT + ".getMinBuildHeight()I"), calls(run));
    }

    // ---- helpers ----

    private void register() {
        MojangRenameTable.register(transformer, "26.3");
        assertNotNull(transformer.getMojangRenames(), "a 26.3 host has renames for a 1.21.1 mod");
    }

    private RetromodTransformer freshTransformer() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** {@code static void run()} making one call, as a 1.21.1 mod compiled it. */
    private ClassNode callingClass(int opcode, String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + owner + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == Opcodes.INVOKEINTERFACE);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return transform("mod/Caller", cw.toByteArray());
    }

    /** A class mixin on LevelHeightAccessor with a shadow, a handler that calls it, and two selectors. */
    private static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/HeightMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(HEIGHT));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor shadow = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getMinBuildHeight", "()I",
                null, null);
        shadow.visitAnnotation(SHADOW, true).visitEnd();
        shadow.visitEnd();
        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "onHeight",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "getMinBuildHeight");
        methods.visit(null, "getMinBuildHeight()I");
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, AT);
        at.visit("value", "INVOKE");
        at.visit("target", "L" + HEIGHT + ";getMinBuildHeight()I");
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 0);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "mod/mixin/HeightMixin", "getMinBuildHeight", "()I", false);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(1, 2);
        handler.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An accessor mixin on {@code target} with one {@code @Invoker} or {@code @Accessor} method. */
    private static byte[] accessorMixin(String name, String target, String method, String desc,
            String annotation, String value) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, name, null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, desc, null, null);
        AnnotationVisitor generated = mv.visitAnnotation(annotation, true);
        generated.visit("value", value);
        generated.visitEnd();
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(String name, byte[] bytes) {
        jar.put(name, bytes);
        try (var scope = transformer.pushJarClassBytesProvider(jar::get)) {
            ClassNode node = new ClassNode();
            new ClassReader(transformer.transformClass(bytes, name)).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static AbstractInsnNode first(MethodNode method, Class<?> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return insn;
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        return null;
    }

    /** A host class with a superclass (null for an interface), interfaces, and declared methods as name plus descriptor. */
    private record FakeClass(String name, String superName, String... membersOrInterfaces) {}

    private Path hostJar(FakeClass... classes) throws Exception {
        Path path = Files.createTempFile(directory, "host", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (FakeClass fake : classes) {
                ClassWriter cw = new ClassWriter(0);
                List<String> interfaces = new ArrayList<>();
                List<String> methods = new ArrayList<>();
                for (String entry : fake.membersOrInterfaces()) (entry.contains("(") ? methods : interfaces).add(entry);
                boolean isInterface = fake.superName() == null;
                cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0),
                        fake.name(), null, isInterface ? "java/lang/Object" : fake.superName(),
                        interfaces.toArray(String[]::new));
                for (String method : methods) {
                    int open = method.indexOf('(');
                    cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method.substring(0, open),
                            method.substring(open), null, null).visitEnd();
                }
                cw.visitEnd();
                out.putNextEntry(new JarEntry(fake.name() + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
        }
        return path;
    }
}
