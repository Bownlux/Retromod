/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.polyfill.PolyfillRegistry;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every path leaves the EMI, Trinkets and MixinExtras names current libraries still ship, in a mod and its nested library. */
class LibraryMovePathParityTest {

    private static final String EMI_TEXTURE = "dev/emi/emi/api/render/EmiTexture";
    private static final String SLOT_TYPE = "dev/emi/trinkets/api/SlotType";
    private static final String WRAP_WITH_CONDITION = "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;";
    private static final String CCA_KEY = "dev/onyxstudios/cca/api/v3/component/ComponentKey";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearLinkedLibraries();
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"fabric, runtime, 1.21.1", "fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, batch, 1.21.1",
            "fabric, aot, 26.3", "forge, runtime, 26.2", "forge, transform, 26.3", "forge, batch, 26.2", "forge, aot, 26.2",
            "neoforge, runtime, 1.21.1", "neoforge, transform, 1.21.1", "neoforge, aot, 26.3"})
    @DisplayName("EmiTexture, SlotType and @WrapWithCondition keep their names in the mod and its nested library")
    void currentLibraryNamesStay(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, writeMod(loader, "lootmod", fixture(loader, "mod"), fixture(loader, "lib")));
        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String where = part.getKey() + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode plugin = read(part.getValue(), part.getKey() + "/Plugin");
                    MethodNode draw = plugin.methods.stream().filter(m -> m.name.equals("draw")).findFirst().orElseThrow();
                    FieldInsnNode texture = (FieldInsnNode) java.util.Arrays.stream(draw.instructions.toArray())
                            .filter(insn -> insn instanceof FieldInsnNode).findFirst().orElseThrow();
                    assertEquals(EMI_TEXTURE, texture.owner, where + ": EMI 1.1.24 still ships EmiTexture");
                    TypeInsnNode slot = (TypeInsnNode) java.util.Arrays.stream(draw.instructions.toArray())
                            .filter(insn -> insn instanceof TypeInsnNode).findFirst().orElseThrow();
                    assertEquals(SLOT_TYPE, slot.desc, where + ": Trinkets 3.x still ships SlotType");
                    ClassNode mixin = read(part.getValue(), part.getKey() + "/WrapMixin");
                    AnnotationNode wrap = mixin.methods.stream().filter(m -> m.name.equals("wrap")).findFirst().orElseThrow()
                            .visibleAnnotations.get(0);
                    assertEquals(WRAP_WITH_CONDITION, wrap.desc, where + ": every MixinExtras keeps WrapWithCondition");
                }
            }
        }
    }

    @Test
    @DisplayName("a CCA 5 addon translated with its CCA 5 library keeps the library's names; alone it moves to CCA 6")
    void ccaFollowsTheLinkedLibrary() throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        Path in = Files.createDirectories(dir.resolve("batch-in"));
        Files.write(in.resolve("cca-base.jar"), LoaderApiFixtures.jar(Map.of(
                "fabric.mod.json", fabricJson("cardinal-components-base", "1.20.1", false),
                CCA_KEY + ".class", emptyClass(CCA_KEY))));
        Files.write(in.resolve("addon.jar"), LoaderApiFixtures.jar(Map.of(
                "fabric.mod.json", fabricJson("ccaaddon", "1.20.1", false),
                "addon/Uses.class", usesType("addon/Uses", CCA_KEY))));
        Path out = Files.createDirectories(dir.resolve("batch-out"));
        CliHarness.batch(in, out, "26.3");
        assertEquals(CCA_KEY, typeIn(out.resolve("addon.jar"), "addon/Uses"), "the batch carries CCA 5 itself");

        Path alone = dir.resolve("alone.jar");
        CliHarness.transform(in.resolve("addon.jar"), alone, "26.3");
        assertEquals("org/ladysnake/cca/api/v3/component/ComponentKey", typeIn(alone, "addon/Uses"),
                "every CCA for 26.3 is CCA 6 or later");
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("lootmod.jar"), host);
                yield out.resolve("lootmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("lootmod.jar"), host);
                yield out.resolve("lootmod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    yield jars.filter(jar -> jar.toString().endsWith(".jar")).findFirst().orElseThrow();
                }
            }
        };
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}, polyfills included, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            return new FabricModTransformer(host).transformMod(input, out);
        }
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        new PolyfillRegistry().loadAndRegister(transformer);
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    private Path writeMod(String loader, String id, Map<String, byte[]> mod, Map<String, byte[]> lib) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>(mod);
        entries.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        Path input = Files.createDirectories(dir.resolve("in")).resolve(id + "-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(entries));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** A Fabric 1.21.1 or Forge 1.20.1 EMI and Trinkets addon with a MixinExtras mixin; NeoForge takes the Forge one. */
    private static Map<String, byte[]> fixture(String loader, String pkg) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String id = "libmove" + pkg;
        if (loader.equals("fabric")) {
            entries.put("fabric.mod.json", fabricJson(id, "1.21.1", pkg.equals("mod")));
        } else {
            entries.put("META-INF/mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                    + "modId=\"" + id + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\nmodId=\"minecraft\"\n"
                    + "mandatory=true\nversionRange=\"[1.20.1,1.21)\"\nordering=\"NONE\"\nside=\"BOTH\"\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        entries.put(pkg + "/Plugin.class", plugin(pkg + "/Plugin"));
        entries.put(pkg + "/WrapMixin.class", wrapMixin(pkg + "/WrapMixin"));
        return entries;
    }

    private static byte[] fabricJson(String id, String minecraft, boolean nests) {
        return ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\""
                + minecraft + "\",\"fabricloader\":\">=0.14.0\"}"
                + (nests ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code static Object draw(Object slot) { Object t = EmiTexture.EMPTY_ARROW; return (SlotType) slot; }} */
    private static byte[] plugin(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, EMI_TEXTURE, "EMPTY_ARROW", "L" + EMI_TEXTURE + ";");
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, SLOT_TYPE);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mixin handler annotated {@code @WrapWithCondition}. */
    private static byte[] wrapMixin(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "wrap", "()Z", null, null);
        AnnotationVisitor av = mv.visitAnnotation(WRAP_WITH_CONDITION, true);
        av.visitArray("method").visit(null, "tick");
        av.visitEnd();
        mv.visitCode();
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Object use(Object o) { return (Type) o; }} */
    private static byte[] usesType(String name, String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, type);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String typeIn(Path jar, String className) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = read(file, className);
            for (var insn : node.methods.stream().filter(m -> m.name.equals("use")).findFirst().orElseThrow().instructions) {
                if (insn instanceof TypeInsnNode type) return type.desc;
            }
        }
        return fail("no cast in " + className);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }
}
