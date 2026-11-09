/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The test mods' recipe, two levels below the host's final showNotification and in a com/retromod package, is renamed on every path. */
class FinalOverrideTwoLevelPathTest {

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String NORMAL_RECIPE = CRAFTING + "NormalCraftingRecipe";
    private static final String SHAPED_RECIPE = CRAFTING + "ShapedRecipe";
    private static final String RENAMED = "retromod$final$showNotification";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private Object savedIndex;

    @TempDir
    Path dir;

    /** Installs an index of the host replica, as the game's jar is indexed in game and with --mc-jar. */
    @BeforeEach
    void indexHost() throws Exception {
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(Files.write(dir.resolve("host.jar"), hostJar()));
        Field field = indexField();
        savedIndex = field.get(transformer);
        field.set(transformer, index);
    }

    @AfterEach
    void restore() throws Exception {
        indexField().set(transformer, savedIndex);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.3",
            "forge, aot, 26.3", "neoforge, runtime, 26.2", "neoforge, runtime, 26.3", "fabric, runtime, 26.3",
            "fabric, transform, 26.2", "fabric, aot, 26.3"})
    @DisplayName("each path renames the override of NormalCraftingRecipe.showNotification and moves the mod's own call")
    void everyPathRenamesTheTestModRecipe(String loader, String path, String host) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("testmod.jar");
        Files.write(input, modJar(loader));

        ClassNode recipe = recipeIn(translate(loader, path, host, input), loader);

        assertEquals(List.of(RENAMED, "hidesToast"), names(recipe),
                "the JVM refuses a class that overrides the host's final showNotification");
        assertEquals(List.of(recipe.name + "." + RENAMED), calls(recipe, "hidesToast"),
                "the mod's own call through its type reaches its renamed method");
    }

    /** NormalCraftingRecipe and ShapedRecipe as the Forge 26.2, NeoForge 26.3 and vanilla 26.3 jars declare them. */
    private static byte[] hostJar() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter crafting = new ClassWriter(0);
        crafting.visit(V21, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, CRAFTING + "CraftingRecipe", null,
                "java/lang/Object", null);
        crafting.visitEnd();
        classes.put(CRAFTING + "CraftingRecipe.class", crafting.toByteArray());

        // The final methods sit on NormalCraftingRecipe, which ShapedRecipe extends without redeclaring them.
        ClassWriter normal = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        normal.visit(V21, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, NORMAL_RECIPE, null, "java/lang/Object",
                new String[]{CRAFTING + "CraftingRecipe"});
        returning(normal, ACC_PUBLIC | ACC_FINAL, "group", "()Ljava/lang/String;");
        returning(normal, ACC_PUBLIC | ACC_FINAL, "category", "()L" + CRAFTING + "CraftingBookCategory;");
        returning(normal, ACC_PUBLIC | ACC_FINAL, "showNotification", "()Z");
        returning(normal, ACC_PUBLIC | ACC_FINAL, "placementInfo", "()L" + CRAFTING + "PlacementInfo;");
        normal.visitEnd();
        classes.put(NORMAL_RECIPE + ".class", normal.toByteArray());

        ClassWriter shaped = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        shaped.visit(V21, ACC_PUBLIC | ACC_SUPER, SHAPED_RECIPE, null, NORMAL_RECIPE, null);
        returning(shaped, ACC_PUBLIC, "getWidth", "()I");
        returning(shaped, ACC_PUBLIC, "getHeight", "()I");
        shaped.visitEnd();
        classes.put(SHAPED_RECIPE + ".class", shaped.toByteArray());
        return LoaderApiFixtures.jar(classes);
    }

    /** The test mod for {@code loader}, holding JEI's recipe shape in that test mod's own package. */
    private static byte[] modJar(String loader) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(recipeName(loader) + ".class", recipe(loader));
        boolean fabric = loader.equals("fabric");
        String metadata = fabric
                ? "{\"schemaVersion\":1,\"id\":\"retromod_test_mod\",\"version\":\"0.1.0\","
                        + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.14.0\"}}"
                : "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\n"
                        + "modId=\"retromod_test_mod_forge\"\nversion=\"0.1.0\"\n"
                        + "[[dependencies.retromod_test_mod_forge]]\nmodId=\"minecraft\"\nmandatory=true\n"
                        + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
        entries.put(fabric ? "fabric.mod.json" : "META-INF/mods.toml", metadata.getBytes(StandardCharsets.UTF_8));
        return LoaderApiFixtures.jar(entries);
    }

    private static String recipeName(String loader) {
        return switch (loader) {
            case "fabric" -> "com/retromod/testmod/tests/Test62OldDataAndFinalShapes$OwnShapedRecipe";
            case "neoforge" -> "com/retromod/testmod/neoforge/OldShapeCases$OwnShapedRecipe";
            default -> "com/retromod/testmod/forge/OldShapeCases$OwnShapedRecipe";
        };
    }

    /** A ShapedRecipe subclass that hides the unlock toast, as JEI's does, loaded and never constructed. */
    private static byte[] recipe(String loader) {
        boolean fabric = loader.equals("fabric");
        // Fabric mods name the host in intermediary: class_1869 is ShapedRecipe, method_49188 showNotification.
        String superName = fabric ? "net/minecraft/class_1869" : SHAPED_RECIPE;
        String showNotification = fabric ? "method_49188" : "showNotification";
        String name = recipeName(loader);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_FINAL | ACC_SUPER, name, null, superName, null);
        MethodVisitor show = cw.visitMethod(ACC_PUBLIC, showNotification, "()Z", null, null);
        show.visitCode();
        show.visitInsn(ICONST_0);
        show.visitInsn(IRETURN);
        show.visitMaxs(0, 0);
        show.visitEnd();
        MethodVisitor hides = cw.visitMethod(ACC_STATIC, "hidesToast", "(L" + name + ";)Z", null, null);
        hides.visitCode();
        hides.visitVarInsn(ALOAD, 0);
        hides.visitMethodInsn(INVOKEVIRTUAL, name, showNotification, "()Z", false);
        hides.visitInsn(IRETURN);
        hides.visitMaxs(0, 0);
        hides.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A method that returns its type's default value. */
    private static void returning(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        Type returns = Type.getReturnType(desc);
        if (returns.getSort() == Type.OBJECT) {
            mv.visitInsn(ACONST_NULL);
        } else {
            mv.visitInsn(ICONST_0);
        }
        mv.visitInsn(returns.getOpcode(IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Translates {@code input} for a {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, String host, Path input) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : "forge");
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("testmod.jar"), host);
                yield out.resolve("testmod.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("testmod.jar"), host);
                yield out.resolve("testmod.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(jar -> jar.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    yield all.get(0);
                }
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
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
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    private static ClassNode recipeIn(Path output, String loader) throws Exception {
        try (JarFile jar = new JarFile(output.toFile())) {
            var entry = jar.getJarEntry(recipeName(loader) + ".class");
            assertNotNull(entry, "the recipe is missing from " + output.getFileName());
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static List<String> names(ClassNode node) {
        List<String> names = new ArrayList<>();
        for (MethodNode method : node.methods) names.add(method.name);
        return names;
    }

    private static List<String> calls(ClassNode node, String methodName) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static Field indexField() throws NoSuchFieldException {
        Field field = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        field.setAccessible(true);
        return field;
    }
}
