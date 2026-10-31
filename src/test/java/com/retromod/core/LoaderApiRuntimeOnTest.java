/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With the runtime on, only declared keys change and every Fabric output carries its identity. */
class LoaderApiRuntimeOnTest {

    private static final String HOST = "26.3";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("Every table line the runtime changes belongs to a key it declared")
    void changesOnlyDeclaredKeys() {
        RetromodPreLaunch.registerShimsForTransform(HOST);
        // The built-in redirect Retromod.onInitialize registers, which the runtime suppresses.
        transformer.registerMethodRedirect("net/fabricmc/loader/api/FabricLoader", "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;", "com/retromod/shim/fabric/FabricLoaderShim",
                "getModContainer", "(Ljava/lang/String;)Ljava/util/Optional;");
        Set<String> before = new HashSet<>(transformer.registrationLines());
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", HOST,
                LoaderApiContext.TransformPath.FABRIC_PRELAUNCH, null));
        Set<String> after = new HashSet<>(transformer.registrationLines());

        List<String> changed = new ArrayList<>();
        for (String line : before) if (!after.contains(line)) changed.add("- " + line);
        for (String line : after) if (!before.contains(line)) changed.add("+ " + line);
        assertFalse(changed.isEmpty(), "the runtime registered nothing");

        Set<RedirectKey> declared = new HashSet<>();
        LoaderApiRuntime.ledgers(transformer).forEach(ledger -> declared.addAll(ledger.declared()));
        for (String line : changed) {
            // The embedded shim table names classes in dotted form.
            boolean owned = declared.stream().anyMatch(key -> (line.contains(key.owner())
                    || line.contains(key.owner().replace('/', '.')))
                    && (key.name() == null || line.contains(key.name())));
            assertTrue(owned, "the runtime changed a key it did not declare: " + line);
        }
    }

    @Test
    @DisplayName("The trade synthetics the runtime claims are still registered afterwards")
    void claimedTradeSyntheticsSurvive() {
        RetromodPreLaunch.registerShimsForTransform(HOST);
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", HOST,
                LoaderApiContext.TransformPath.FABRIC_PRELAUNCH, null));
        // A claim clears what is registered under its type, so claiming after registering left an
        // old Fabric trade handler with NoClassDefFoundError on 26.3.
        for (String synthetic : List.of(com.retromod.shim.forge.LegacyTradeBridge.ITEM_LISTING,
                com.retromod.shim.forge.LegacyTradeBridge.PROFESSIONS)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(synthetic), synthetic + " was cleared by its own claim");
        }
    }

    @Test
    @DisplayName("Runtime, CLI and AOT outputs carry the translation identity")
    void outputsCarryIdentity() throws Exception {
        String identity = LoaderApiRuntime.translationIdentity("fabric", HOST);
        assertEquals("fabric-26x/" + LoaderApiTestSupport.fabricProvider().rowsVersion(), identity);

        Path input = dir.resolve("loaderapi-fixture-fabric.jar");
        Files.write(input, LoaderApiFixtures.fabricJar());
        RetromodPreLaunch.registerShimsForTransform(HOST);
        Path runtimeOut = new FabricModTransformer(HOST).transformMod(input,
                Files.createDirectory(dir.resolve("mods")));
        assertEquals(identity, attribute(runtimeOut));

        Path cliOut = dir.resolve("cli.jar");
        CliHarness.transform(input, cliOut, HOST);
        assertEquals(identity, attribute(cliOut));

        Path aotOut = dir.resolve("aot.jar");
        CliHarness.aot(input, aotOut, HOST);
        assertEquals(identity, attribute(aotOut));
    }

    @Test
    @DisplayName("The AOT cache stamp names the runtime rows for a Fabric host")
    void cacheStampSuffix() {
        LoaderApiTestSupport.defaultContext("fabric", HOST);
        assertEquals("|loaderApi=" + LoaderApiRuntime.translationIdentity("fabric", HOST),
                LoaderApiRuntime.cacheStampSuffix());
        LoaderApiTestSupport.defaultContext("neoforge", HOST);
        assertEquals("", LoaderApiRuntime.cacheStampSuffix(),
                "no provider serves NeoForge mods yet, so its caches keep their stamp");
    }

    @Test
    @DisplayName("FabricLoader.getModContainer keeps the version spoofer with the runtime on and off")
    void modContainerKeepsVersionSpoofer() throws Exception {
        Path input = dir.resolve("loaderapi-fixture-fabric.jar");
        Files.write(input, LoaderApiFixtures.fabricJar());
        Path onOutput = dir.resolve("on.jar");
        CliHarness.transform(input, onOutput, HOST);
        String withRuntime = modContainerCall(onOutput);

        String withoutRuntime;
        try (var off = LoaderApiTestSupport.runtimeOff()) {
            transformer.clearRedirectsForTesting();
            Path offOutput = dir.resolve("off.jar");
            CliHarness.transform(input, offOutput, HOST);
            withoutRuntime = modContainerCall(offOutput);
        }
        assertEquals(VERSION_SPOOFER_CALL, withoutRuntime, "the off path reads spoofed versions");
        assertEquals(withoutRuntime, withRuntime,
                "the runtime must keep the version spoofer that the off path uses");
    }

    @Test
    @DisplayName("AOT output names the Fabric API references the host still lacks")
    void aotReportsMissingFabricApi() throws Exception {
        Path input = dir.resolve("loaderapi-fixture-fabric.jar");
        Files.write(input, LoaderApiFixtures.fabricJar());
        String log = LoaderApiTestSupport.captureErr(
                () -> CliHarness.aot(input, dir.resolve("aot.jar"), HOST));
        assertTrue(log.contains("Fabric API reference(s) that Minecraft " + HOST + " does not have"),
                "the AOT path must run the same link check as the other paths");
    }

    @Test
    @DisplayName("The runtime, CLI and AOT paths check the jars a mod nests, by name")
    void everyPathChecksNestedJars() throws Exception {
        Path input = dir.resolve("nesting-mod.jar");
        Files.write(input, nestingJar());
        String removed = "net/fabricmc/fabric/impl/fixture/RemovedImpl";
        String nested = "nesting-mod.jar > META-INF/jars/stalelib-1.0.jar";

        RetromodPreLaunch.registerShimsForTransform(HOST);
        String runtimeLog = LoaderApiTestSupport.captureErr(() -> new FabricModTransformer(HOST)
                .transformMod(input, Files.createDirectory(dir.resolve("mods"))));
        String cliLog = LoaderApiTestSupport.captureErr(
                () -> CliHarness.transform(input, dir.resolve("cli.jar"), HOST));
        String aotLog = LoaderApiTestSupport.captureErr(
                () -> CliHarness.aot(input, dir.resolve("aot.jar"), HOST));
        for (String[] path : List.of(new String[]{"runtime", runtimeLog}, new String[]{"CLI", cliLog},
                new String[]{"AOT", aotLog})) {
            assertTrue(path[1].contains(nested + " still uses 1 Fabric API reference(s)")
                    && path[1].contains(removed), "the " + path[0] + " path must name the nested jar's "
                    + "missing reference:\n" + path[1]);
        }
    }

    @Test
    @DisplayName("The CLI and AOT link checks read the jar after its synthetics are embedded")
    void cliAndAotCheckTheEmbeddedJar() throws Exception {
        // A renamed callback goes to a per-mod synthetic, which the embedder relocates into the jar.
        java.util.Map<String, byte[]> mod = new java.util.LinkedHashMap<>();
        mod.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        mod.put("fabric.mod.json", fabricModJson("tooltip_mod"));
        mod.put("tooltips/Hooks.class", tooltipHooks("net/fabricmc/fabric/api/client/rendering/v1/TooltipComponentCallback"));
        mod.put("META-INF/jars/stalelib-1.0.jar", staleLibrary());
        Path input = dir.resolve("tooltip-mod.jar");
        Files.write(input, LoaderApiFixtures.jar(mod));

        String cliLog = LoaderApiTestSupport.captureErr(
                () -> CliHarness.transform(input, dir.resolve("cli.jar"), HOST));
        String aotLog = LoaderApiTestSupport.captureErr(
                () -> CliHarness.aot(input, dir.resolve("aot.jar"), HOST));
        for (String[] path : List.of(new String[]{"CLI", cliLog}, new String[]{"AOT", aotLog})) {
            int embedded = path[1].indexOf("Embedded ");
            int checked = path[1].indexOf("still uses");
            assertTrue(embedded >= 0 && checked > embedded, "the " + path[0] + " path must check the jar "
                    + "it ships, after embedding:\n" + path[1]);
        }
    }

    /** Registers a lambda on {@code callback}'s EVENT and calls its invoker, as Iceberg 1.20.1 does. */
    private static byte[] tooltipHooks(String callback) {
        String sam = "(Lnet/minecraft/class_5632;)Lnet/minecraft/class_5684;";
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "tooltips/Hooks", null,
                "java/lang/Object", null);
        var mv = LoaderApiFixtures.method(cw, "register", "()V");
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, callback, "EVENT",
                "Lnet/fabricmc/fabric/api/event/Event;");
        LoaderApiFixtures.lambda(mv, callback, "getComponent", sam, "tooltips/Hooks", "lambda$register$0");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "net/fabricmc/fabric/api/event/Event",
                "register", "(Ljava/lang/Object;)V", false);
        LoaderApiFixtures.end(mv, org.objectweb.asm.Opcodes.RETURN);
        // The event's invoker, cast to the callback and called, as Iceberg's tooltip gathering does.
        mv = LoaderApiFixtures.method(cw, "component", sam);
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, callback, "EVENT",
                "Lnet/fabricmc/fabric/api/event/Event;");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "net/fabricmc/fabric/api/event/Event",
                "invoker", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, callback);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, callback, "getComponent", sam, true);
        LoaderApiFixtures.end(mv, org.objectweb.asm.Opcodes.ARETURN);
        var body = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC
                | org.objectweb.asm.Opcodes.ACC_SYNTHETIC, "lambda$register$0", sam, null, null);
        body.visitCode();
        body.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        LoaderApiFixtures.end(body, org.objectweb.asm.Opcodes.ARETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod with no Fabric API reference of its own that nests a library with one the host lacks. */
    private static byte[] nestingJar() throws Exception {
        java.util.Map<String, byte[]> mod = new java.util.LinkedHashMap<>();
        mod.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        mod.put("fabric.mod.json", fabricModJson("nesting_mod"));
        mod.put("nesting/Main.class", staticRead("nesting/Main", "java/lang/System", "out"));
        mod.put("META-INF/jars/stalelib-1.0.jar", staleLibrary());
        return LoaderApiFixtures.jar(mod);
    }

    /** A nested library that reads a Fabric API class no host has. */
    private static byte[] staleLibrary() throws Exception {
        java.util.Map<String, byte[]> library = new java.util.LinkedHashMap<>();
        library.put("fabric.mod.json", fabricModJson("stalelib"));
        library.put("stalelib/Channels.class", staticRead("stalelib/Channels",
                "net/fabricmc/fabric/impl/fixture/RemovedImpl", "PLAY_C2S"));
        return LoaderApiFixtures.jar(library);
    }

    private static byte[] fabricModJson(String id) {
        return ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabric-api\":\"*\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] staticRead(String name, String owner, String field) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null,
                "java/lang/Object", null);
        var mv = LoaderApiFixtures.method(cw, "run", "()V");
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, owner, field, "Ljava/lang/Object;");
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        LoaderApiFixtures.end(mv, org.objectweb.asm.Opcodes.RETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String VERSION_SPOOFER_CALL = "com/retromod/compat/VersionSpoofer.getModContainer"
            + "(Ljava/lang/Object;Ljava/lang/String;)Ljava/util/Optional;";

    /** The getModContainer call in the fixture's {@code modContainer} method, as owner.name(desc). */
    private static String modContainerCall(Path jar) throws Exception {
        try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.getName().equals("fixture/Calls.class")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(node, 0);
                for (var method : node.methods) {
                    if (!method.name.equals("modContainer")) continue;
                    for (var insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call && call.name.equals("getModContainer")) {
                            return call.owner + "." + call.name + call.desc;
                        }
                    }
                }
            }
        }
        throw new AssertionError("fixture/Calls.modContainer has no getModContainer call in " + jar);
    }

    private static String attribute(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest().getMainAttributes().getValue(LoaderApiRuntime.MANIFEST_ATTRIBUTE);
        }
    }
}
