/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1's {@code ModLoader.get()} instance calls on Forge 26.2 and 26.3, where ModLoader is static. */
class ModLoaderForgeTest {

    private static final String MOD_LOADER = LegacyModLoaderBridge.FORGE;
    private static final String HELPER = LegacyModLoaderBridge.FORGE_HELPER;
    private static final String L_LOADER = "L" + MOD_LOADER + ";";
    private static final String WARNING = "net/minecraftforge/fml/ModLoadingWarning";
    private static final String L_WARNING = "L" + WARNING + ";";
    private static final String MOD_BUS_EVENT = "net/minecraftforge/fml/event/IModBusEvent";
    private static final String STATE = "net/minecraftforge/fml/IModLoadingState";
    private static final String OLD_EVENT = "Lnet/minecraftforge/eventbus/api/Event;";
    private static final String CALLER = "mod/Loading";
    private static final String NATIVE = "mod/NativeWarnings";
    private static final String EVENT = "mod/RegistryEvent";
    private static final String FUNCTION = "Ljava/util/function/Function;";
    private static final String BI_CONSUMER = "Ljava/util/function/BiConsumer;";
    private static final String STAND_INS = "com/retromod/shim/forge/fmlhost/";
    private static final Map<String, String> HOST_NAMES = Map.of(
            STAND_INS + "ModLoader", MOD_LOADER,
            STAND_INS + "ModStateManager", "net/minecraftforge/fml/ModStateManager",
            STAND_INS + "ModLoadingWarning", WARNING,
            STAND_INS + "IModLoadingState", STATE,
            STAND_INS + "IModInfo", "net/minecraftforge/forgespi/language/IModInfo",
            STAND_INS + "IModBusEvent", MOD_BUS_EVENT);

    @TempDir
    Path tempDir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        // RetromodForge and --target-loader forge set the Forge flag before any shim registers.
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        ModVersionDetector.setDefaultPreferredLoader("forge");
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim sends ModLoader.get() and every old instance call to the rebuilt helper")
    void oldInstanceCallsReachTheHelper(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        List<String> calls = calls(transformer.transformClass(oldCaller(), CALLER + ".class"), MethodInsnNode.class);

        assertTrue(calls.contains(INVOKESTATIC + " " + HELPER + ".get()" + L_LOADER), calls.toString());
        for (String[] method : LegacyModLoaderBridge.FORGE_INSTANCE_METHODS) {
            String helperCall = INVOKESTATIC + " " + HELPER + "." + method[0] + "(" + L_LOADER + method[1].substring(1);
            assertTrue(calls.contains(helperCall), "the old event parameter is Object after the event-base pass, so "
                    + method[0] + " must reach " + helperCall + ": " + calls);
        }
        assertTrue(calls.stream().noneMatch(call -> call.contains(" " + MOD_LOADER + ".")),
                "no call may stay on the host ModLoader, which has no instance methods: " + calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper travels with the mod");
    }

    @Test
    @DisplayName("The helper casts an old event to IModBusEvent and logs a warning instead of handing it to Forge")
    void helperBodiesCastAndLog() {
        LegacyModLoaderBridge.registerForge(transformer);
        ClassNode helper = node(transformer.getSyntheticClasses().get(HELPER));

        List<String> post = body(helper, "postEvent");
        assertEquals(List.of(CHECKCAST + " " + MOD_BUS_EVENT,
                INVOKESTATIC + " " + MOD_LOADER + ".postEvent(L" + MOD_BUS_EVENT + ";)V"), post);
        List<String> warn = body(helper, "addWarning");
        assertTrue(warn.stream().anyMatch(insn -> insn.contains("LegacyLog.warnOnce(")), warn.toString());
        assertTrue(warn.stream().noneMatch(insn -> insn.contains(MOD_LOADER + ".addWarning")),
                "on Forge 26.2 a warning shuts down every game event once loading ends: " + warn);
    }

    @Test
    @DisplayName("A newer mod's static ModLoader calls and method references stay on the host")
    void nativeStaticCallsStayOnTheHost() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        byte[] transformed = transformer.transformClass(nativeCaller(), NATIVE + ".class");

        assertEquals(List.of(
                INVOKESTATIC + " " + MOD_LOADER + ".addWarning(" + L_WARNING + ")V",
                INVOKESTATIC + " " + MOD_LOADER + ".getWarnings()Ljava/util/List;",
                INVOKESTATIC + " " + MOD_LOADER + ".runEventGenerator(" + FUNCTION + ")V",
                INVOKESTATIC + " " + MOD_LOADER + ".hasCompletedState(L" + STATE + ";)Z"),
                calls(transformed, MethodInsnNode.class));
        assertEquals(List.of(H_INVOKESTATIC + " " + MOD_LOADER + ".addWarning(" + L_WARNING + ")V"),
                calls(transformed, InvokeDynamicInsnNode.class), "ModLoader::addWarning names the host's static method");
    }

    @Test
    @DisplayName("ModLoader.get()::addWarning, as Shoulder Surfing writes it, binds the helper with the old receiver")
    void boundMethodReferenceReachesTheHelper() {
        LegacyModLoaderBridge.registerForge(transformer);

        List<String> handles = calls(transformer.transformClass(oldCaller(), CALLER + ".class"),
                InvokeDynamicInsnNode.class);

        assertEquals(List.of(H_INVOKESTATIC + " " + HELPER + ".addWarning(" + L_LOADER + L_WARNING + ")V"), handles);
    }

    @Test
    @DisplayName("NeoForge, other loaders and Forge before 26.2 keep ModLoader's own instance calls")
    void otherTargetsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyModLoaderBridge.registerForge(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "Forge 1.21.11");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyModLoaderBridge.registerForge(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "NeoForge");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        LegacyModLoaderBridge.registerForge(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "Fabric, Quilt or an unnamed loader");
        assertFalse(transformer.getMethodRedirects().keySet().stream().anyMatch(key -> key.owner().equals(MOD_LOADER)
                && key.name().equals("addWarning")));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("On the host, posts reach the static ModLoader, states resolve by name, and warnings stay in the log")
    void helperRunsAgainstTheHost() throws Exception {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        FmlHost host = new FmlHost(transformer.getSyntheticClasses().get(HELPER),
                transformer.transformClass(oldCaller(), CALLER + ".class"));
        Class<?> loading = host.loadClass(CALLER.replace('/', '.'));
        Object event = host.loadClass(EVENT.replace('/', '.')).getConstructor().newInstance();
        Object warning = host.warning("mod.warning.key");

        Object loader = call(loading, "loader");
        assertNotNull(loader, "ModLoader.get() hands out the host's object, as Forge 1.20.1's get() never returned null");
        assertSame(loader, call(loading, "loader"));
        assertSame(host.hostModLoader(), loader.getClass());

        call(loading, "warn", warning);
        ((Consumer<Object>) call(loading, "warner")).accept(warning);
        assertEquals(List.of(), call(loading, "warnings"), "the host never holds an old mod's warning");
        assertFalse(host.calls().contains("addWarning"), host.calls().toString());

        assertFalse((boolean) call(loading, "completed", "COMPLETE"));
        host.complete("COMPLETE");
        assertTrue((boolean) call(loading, "completed", "COMPLETE"));
        assertFalse((boolean) call(loading, "completed", "DONE"), "Forge 26.2 never completes DONE");
        assertTrue(host.calls().containsAll(List.of("hasCompletedState COMPLETE", "hasCompletedState DONE")),
                host.calls().toString());

        // Forge 26.2 dropped OBJECT_HOLDERS, which ran right after CREATE_REGISTRIES on Forge 1.20.1.
        assertFalse((boolean) call(loading, "completed", "OBJECT_HOLDERS"));
        assertTrue(host.calls().contains("hasCompletedState CREATE_REGISTRIES"), host.calls().toString());
        host.complete("CREATE_REGISTRIES");
        assertTrue((boolean) call(loading, "completed", "OBJECT_HOLDERS"), "OBJECT_HOLDERS is done once its place is");
        InvocationTargetException unknown = assertThrows(InvocationTargetException.class,
                () -> call(loading, "completed", "NOT_A_STATE"));
        assertInstanceOf(IllegalArgumentException.class, unknown.getCause(), "a name no Forge ever had still fails");

        call(loading, "post", event);
        assertSame(event, call(loading, "postWithReturn", event));
        call(loading, "postInOrder", event);
        call(loading, "postWrapped", event, (BiConsumer<?, ?>) (a, b) -> {}, (BiConsumer<?, ?>) (a, b) -> {});
        call(loading, "generate", (Function<Object, Object>) container -> event);
        assertEquals(List.of(event, event, event, event, event), host.posted(), "each post hands the host the same event");
    }

    @ParameterizedTest(name = "{0} on Forge {1}")
    @CsvSource({"runtime, 26.2", "runtime, 26.3", "transform, 26.2", "batch, 26.2", "aot, 26.2"})
    @DisplayName("Every translation path sends the old calls to the helper embedded in the mod")
    void everyPathEmbedsTheHelper(String path, String host) throws Exception {
        Path output = translate(path, host, writeMod());

        List<String> refs = references(output, CALLER);
        String warn = refs.stream().filter(ref -> ref.endsWith("/LegacyModLoader.addWarning(" + L_LOADER + L_WARNING + ")V"))
                .findFirst().orElseThrow(() -> new AssertionError(path + " must call the helper: " + refs));
        assertTrue(warn.startsWith(SyntheticEmbedder.PREFIX), "the helper is embedded in the mod: " + warn);
        assertTrue(hasEntry(output, warn.substring(0, warn.indexOf('.')) + ".class"), "the helper travels in the jar");
        assertTrue(refs.stream().noneMatch(ref -> ref.startsWith(MOD_LOADER + ".")), refs.toString());
    }

    // ---- an old mod's calls, as javac compiled them against Forge 1.20.1 ----

    /** Each ModLoader instance call Forge 1.20.1 had, through {@code ModLoader.get()}. */
    private static byte[] oldCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CALLER, null, "java/lang/Object", null);
        instanceCall(cw, "loader", "()Ljava/lang/Object;", null, null);
        instanceCall(cw, "warn", "(" + L_WARNING + ")V", "addWarning", "(" + L_WARNING + ")V");
        instanceCall(cw, "warnings", "()Ljava/util/List;", "getWarnings", "()Ljava/util/List;");
        instanceCall(cw, "completed", "(Ljava/lang/String;)Z", "hasCompletedState", "(Ljava/lang/String;)Z");
        instanceCall(cw, "generate", "(" + FUNCTION + ")V", "runEventGenerator", "(" + FUNCTION + ")V");
        instanceCall(cw, "post", "(" + OLD_EVENT + ")V", "postEvent", "(" + OLD_EVENT + ")V");
        instanceCall(cw, "postWithReturn", "(" + OLD_EVENT + ")" + OLD_EVENT, "postEventWithReturn",
                "(" + OLD_EVENT + ")" + OLD_EVENT);
        instanceCall(cw, "postInOrder", "(" + OLD_EVENT + ")V", "postEventWrapContainerInModOrder", "(" + OLD_EVENT + ")V");
        instanceCall(cw, "postWrapped", "(" + OLD_EVENT + BI_CONSUMER + BI_CONSUMER + ")V", "postEventWithWrapInModOrder",
                "(" + OLD_EVENT + BI_CONSUMER + BI_CONSUMER + ")V");

        // ModLoader.get()::addWarning: javac checks the receiver for null, then binds it into the lambda.
        MethodVisitor warner = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "warner", "()Ljava/util/function/Consumer;", null, null);
        warner.visitCode();
        warner.visitMethodInsn(INVOKESTATIC, MOD_LOADER, "get", "()" + L_LOADER, false);
        warner.visitInsn(DUP);
        warner.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        warner.visitInsn(POP);
        warner.visitInvokeDynamicInsn("accept", "(" + L_LOADER + ")Ljava/util/function/Consumer;", metafactory(),
                Type.getType("(Ljava/lang/Object;)V"),
                new Handle(H_INVOKEVIRTUAL, MOD_LOADER, "addWarning", "(" + L_WARNING + ")V", false),
                Type.getType("(" + L_WARNING + ")V"));
        warner.visitInsn(ARETURN);
        warner.visitMaxs(0, 0);
        warner.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static desc name(args) { return ModLoader.get().method(args); }}, or just {@code get()} without a method. */
    private static void instanceCall(ClassWriter cw, String name, String desc, String method, String methodDesc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, MOD_LOADER, "get", "()" + L_LOADER, false);
        if (method != null) {
            int slot = 0;
            for (Type argument : Type.getArgumentTypes(desc)) {
                mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
                slot += argument.getSize();
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, MOD_LOADER, method, methodDesc, false);
        }
        mv.visitInsn(Type.getReturnType(desc).getOpcode(IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A Forge 26.2 mod's static calls of the names Forge kept, and a static method reference. */
    private static byte[] nativeCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, NATIVE, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "report",
                "(" + L_WARNING + FUNCTION + "L" + STATE + ";)Ljava/util/function/Consumer;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, MOD_LOADER, "addWarning", "(" + L_WARNING + ")V", false);
        mv.visitMethodInsn(INVOKESTATIC, MOD_LOADER, "getWarnings", "()Ljava/util/List;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, MOD_LOADER, "runEventGenerator", "(" + FUNCTION + ")V", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, MOD_LOADER, "hasCompletedState", "(L" + STATE + ";)Z", false);
        mv.visitInsn(POP);
        mv.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory(),
                Type.getType("(Ljava/lang/Object;)V"),
                new Handle(H_INVOKESTATIC, MOD_LOADER, "addWarning", "(" + L_WARNING + ")V", false),
                Type.getType("(" + L_WARNING + ")V"));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's own mod-bus event, as the host sees it after the event-base pass. */
    private static byte[] modBusEvent() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, EVENT, null, "java/lang/Object", new String[]{MOD_BUS_EVENT});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Handle metafactory() {
        return new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    // ---- reading the results ----

    /** Each method call as "opcode owner.name(desc)", or each lambda's handle as "tag owner.name(desc)". */
    private static List<String> calls(byte[] bytes, Class<? extends AbstractInsnNode> kind) {
        List<String> found = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!kind.isInstance(insn)) continue;
                if (insn instanceof MethodInsnNode call && !call.owner.startsWith("java/")) {
                    found.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
                } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle target) {
                    found.add(target.getTag() + " " + target.getOwner() + "." + target.getName() + target.getDesc());
                }
            }
        }
        return found;
    }

    /** The type and method instructions of one helper method, in order. */
    private static List<String> body(ClassNode helper, String name) {
        MethodNode method = helper.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type) found.add(type.getOpcode() + " " + type.desc);
            if (insn instanceof MethodInsnNode call && !call.owner.startsWith("java/")) {
                found.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            }
        }
        return found;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Object call(Class<?> owner, String name, Object... args) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)) return method.invoke(null, args);
        }
        throw new AssertionError(owner.getName() + " has no method " + name);
    }

    // ---- the translation paths ----

    /** Translates {@code input} for a Forge {@code host} through one path and returns the output jar. */
    private Path translate(String path, String host, Path input) throws Exception {
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                registerForgeHost(host);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                yield out.resolve(input.getFileName());
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what RetromodForge registers on a Forge {@code host}. */
    private void registerForgeHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private Path writeMod() throws IOException {
        Path input = Files.createDirectories(tempDir.resolve("in")).resolve("loadingmod-1.20.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"loadingmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.loadingmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.loadingmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(CALLER + ".class"));
            out.write(oldCaller());
            out.closeEntry();
        }
        return input;
    }

    private static boolean hasEntry(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getJarEntry(name) != null;
        }
    }

    /** Every method reference in one class of {@code jar}, as owner.name(desc). */
    private static List<String> references(Path jar, String className) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is missing from " + jar.getFileName());
            List<String> refs = new ArrayList<>();
            for (MethodNode method : node(file.getInputStream(entry).readAllBytes()).methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                }
            }
            return refs;
        }
    }

    /** Forge 26.2's static FML classes as recording stand-ins, with the helper and one translated mod class. */
    private static final class FmlHost extends ClassLoader {

        private final Map<String, byte[]> classes = new HashMap<>();

        FmlHost(byte[] helper, byte[] modClass) throws IOException {
            super(ModLoaderForgeTest.class.getClassLoader());
            SimpleRemapper hostNames = new SimpleRemapper(HOST_NAMES);
            for (Map.Entry<String, String> standIn : HOST_NAMES.entrySet()) {
                ClassWriter out = new ClassWriter(0);
                new ClassReader(read(standIn.getKey())).accept(new ClassRemapper(out, hostNames), 0);
                classes.put(standIn.getValue(), out.toByteArray());
            }
            classes.put(HELPER, helper);
            classes.put(CALLER, modClass);
            classes.put(EVENT, modBusEvent());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    loaded = bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, false);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        Class<?> hostModLoader() throws ClassNotFoundException {
            return loadClass(MOD_LOADER.replace('/', '.'));
        }

        Object warning(String key) throws Exception {
            Class<?> modInfo = loadClass(HOST_NAMES.get(STAND_INS + "IModInfo").replace('/', '.'));
            return loadClass(WARNING.replace('/', '.')).getConstructor(modInfo, String.class, Object[].class)
                    .newInstance(null, key, new Object[0]);
        }

        @SuppressWarnings("unchecked")
        List<String> calls() throws Exception {
            return (List<String>) hostModLoader().getField("CALLS").get(null);
        }

        @SuppressWarnings("unchecked")
        List<Object> posted() throws Exception {
            return (List<Object>) hostModLoader().getField("POSTED").get(null);
        }

        @SuppressWarnings("unchecked")
        void complete(String state) throws Exception {
            ((Set<String>) hostModLoader().getField("COMPLETED").get(null)).add(state);
        }

        private static byte[] read(String internalName) throws IOException {
            try (InputStream in = ModLoaderForgeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
                if (in == null) throw new IOException("missing test class " + internalName);
                return in.readAllBytes();
            }
        }
    }
}
