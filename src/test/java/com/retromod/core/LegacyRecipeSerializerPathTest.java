/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 recipe serializer, recipe and lookup, translated for Forge and NeoForge 26.x on every path. */
class LegacyRecipeSerializerPathTest {

    private static final String SERIALIZER = "mod/CookSerializer";
    private static final String RECIPE = "mod/CookRecipe";
    private static final String USES = "mod/Uses";
    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String ID = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String CONTAINER = "Lnet/minecraft/world/Container;";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String ACCESS = "Lnet/minecraft/core/RegistryAccess;";
    private static final String BUF = "Lnet/minecraft/network/FriendlyByteBuf;";
    private static final String L_RECIPE = "L" + CRAFTING + "Recipe;";
    private static final String JSON = "L" + String.join("/", "com", "google", "gson") + "/JsonObject;";

    @TempDir
    Path tempDir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} on {1} {2}")
    @CsvSource({"runtime, forge, 26.2", "runtime, forge, 26.3", "transform, forge, 26.2", "batch, forge, 26.2",
            "aot, forge, 26.2", "transform, neoforge, 26.3", "aot, neoforge, 26.3"})
    @DisplayName("The serializer gains codecs, the recipe answers the recipe book, and its JSON stacks and lookups reach the helpers")
    void oldRecipeApiIsBridged(String path, String loader, String host) throws Exception {
        Path output = translate(path, loader, host, writeMod());

        ClassNode serializer = node(output, SERIALIZER);
        assertTrue(serializer.interfaces.stream().noneMatch(name -> name.equals(CRAFTING + "RecipeSerializer")),
                "26.x's serializer is a record, so the old class no longer implements it: " + serializer.interfaces);
        assertNotNull(method(serializer, "codec", "()Lcom/mojang/serialization/MapCodec;"), "the serializer has a codec");
        assertNotNull(method(serializer, "streamCodec", "()Lnet/minecraft/network/codec/StreamCodec;"), "and a stream codec");
        String stack = embeddedCall(output, references(serializer, "fromJson"), "LegacyRecipeCalls.craftingItemStack(");
        ClassNode calls = node(output, stack.substring(0, stack.indexOf('.')));
        String early = embeddedCall(output, references(calls, "craftingItemStack"), "EarlyItemStacks.of(");
        assertTrue(early.contains("EarlyItemStacks.of(Lnet/minecraft/world/level/ItemLike;I)"),
                "a result read before a world binds item components waits for them: " + early);

        ClassNode recipe = node(output, RECIPE);
        for (String answer : List.of("placementInfo", "recipeBookCategory", "showNotification", "group")) {
            assertTrue(recipe.methods.stream().anyMatch(m -> m.name.equals(answer)), "the recipe answers " + answer);
        }
        assertNotNull(method(recipe, "matches", "(L" + CRAFTING + "RecipeInput;" + LEVEL + ")Z"), "the host's matches");
        assertNotNull(method(recipe, "assemble", "(L" + CRAFTING + "RecipeInput;)" + STACK), "26.x's assemble takes no registries");

        embeddedCall(output, references(node(output, USES), "find"), "LegacyRecipeCalls.getRecipeFor(");
    }

    /** Translates {@code input} for {@code loader} {@code host} through one path and returns the output jar. */
    private Path translate(String path, String loader, String host, Path input) throws Exception {
        ModVersionDetector.setDefaultPreferredLoader(loader);
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        McReflect.setForceForge(loader.equals("forge"));
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

    /** Registers what RetromodForge registers on a Forge {@code host} that runs Mojang names. */
    private static void registerForgeHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    private Path writeMod() throws Exception {
        Path input = Files.createDirectories(tempDir.resolve("in")).resolve("cookmod-1.20.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"cookmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.cookmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.cookmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            for (var entry : List.of(new Object[]{SERIALIZER, serializer()}, new Object[]{RECIPE, recipe()},
                    new Object[]{USES, uses()})) {
                out.putNextEntry(new JarEntry(entry[0] + ".class"));
                out.write((byte[]) entry[1]);
            }
        }
        return input;
    }

    /** {@code fromJson} reads its result with CraftingHelper; the network methods read and write the stack. */
    private static byte[] serializer() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SERIALIZER, null, "java/lang/Object", new String[]{CRAFTING + "RecipeSerializer"});
        constructor(cw);
        MethodVisitor json = cw.visitMethod(ACC_PUBLIC, "fromJson", "(" + ID + JSON + ")" + L_RECIPE, null, null);
        json.visitCode();
        json.visitTypeInsn(NEW, RECIPE);
        json.visitInsn(DUP);
        json.visitVarInsn(ALOAD, 1);
        json.visitVarInsn(ALOAD, 2);
        json.visitInsn(ICONST_1);
        json.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/common/crafting/CraftingHelper", "getItemStack",
                "(" + JSON + "Z)" + STACK, false);
        json.visitMethodInsn(INVOKESPECIAL, RECIPE, "<init>", "(" + ID + STACK + ")V", false);
        json.visitInsn(ARETURN);
        json.visitMaxs(0, 0);
        json.visitEnd();
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC, "fromNetwork", "(" + ID + BUF + ")" + L_RECIPE, null, null);
        read.visitCode();
        read.visitTypeInsn(NEW, RECIPE);
        read.visitInsn(DUP);
        read.visitVarInsn(ALOAD, 1);
        read.visitVarInsn(ALOAD, 2);
        read.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf", "readItem", "()" + STACK, false);
        read.visitMethodInsn(INVOKESPECIAL, RECIPE, "<init>", "(" + ID + STACK + ")V", false);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        MethodVisitor write = cw.visitMethod(ACC_PUBLIC, "toNetwork", "(" + BUF + L_RECIPE + ")V", null, null);
        write.visitCode();
        write.visitInsn(RETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A recipe over the old container that always matches and assembles its result. */
    private static byte[] recipe() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, RECIPE, null, "java/lang/Object", new String[]{CRAFTING + "Recipe"});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "id", ID, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "result", STACK, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + ID + STACK + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, RECIPE, "id", ID);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, RECIPE, "result", STACK);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        constant(cw, "matches", "(" + CONTAINER + LEVEL + ")Z", ICONST_1, IRETURN);
        constant(cw, "canCraftInDimensions", "(II)Z", ICONST_1, IRETURN);
        result(cw, "assemble", "(" + CONTAINER + ACCESS + ")" + STACK);
        result(cw, "getResultItem", "(" + ACCESS + ")" + STACK);
        MethodVisitor id = cw.visitMethod(ACC_PUBLIC, "getId", "()" + ID, null, null);
        id.visitCode();
        id.visitVarInsn(ALOAD, 0);
        id.visitFieldInsn(GETFIELD, RECIPE, "id", ID);
        id.visitInsn(ARETURN);
        id.visitMaxs(0, 0);
        id.visitEnd();
        constant(cw, "getSerializer", "()L" + CRAFTING + "RecipeSerializer;", ACONST_NULL, ARETURN);
        constant(cw, "getType", "()L" + CRAFTING + "RecipeType;", ACONST_NULL, ARETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code manager.getRecipeFor(type, container, level)}, as a 1.20.1 block entity asks it. */
    private static byte[] uses() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, USES, null, "java/lang/Object", null);
        String manager = "L" + CRAFTING + "RecipeManager;";
        String type = "L" + CRAFTING + "RecipeType;";
        MethodVisitor find = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "find",
                "(" + manager + type + CONTAINER + LEVEL + ")Ljava/util/Optional;", null, null);
        find.visitCode();
        for (int i = 0; i < 4; i++) find.visitVarInsn(ALOAD, i);
        find.visitMethodInsn(INVOKEVIRTUAL, CRAFTING + "RecipeManager", "getRecipeFor",
                "(" + type + CONTAINER + LEVEL + ")Ljava/util/Optional;", false);
        find.visitInsn(ARETURN);
        find.visitMaxs(0, 0);
        find.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static void constant(ClassWriter cw, String name, String desc, int value, int returns) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(value);
        mv.visitInsn(returns);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void result(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, RECIPE, "result", STACK);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The one reference containing {@code call}, checked to name a helper embedded in the jar. */
    private static String embeddedCall(Path jar, List<String> refs, String call) throws Exception {
        String found = refs.stream().filter(ref -> ref.contains(call)).findFirst()
                .orElseThrow(() -> new AssertionError("expected a call to " + call + " in " + refs));
        assertTrue(found.startsWith(SyntheticEmbedder.PREFIX), "the helper is embedded in the mod: " + found);
        String owner = found.substring(0, found.indexOf('.'));
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNotNull(file.getJarEntry(owner + ".class"), owner + " travels in the jar");
        }
        return found;
    }

    private static ClassNode node(Path jar, String className) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is missing from " + jar.getFileName());
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    /** Every method call in the methods called {@code name}, as owner.name(desc). */
    private static List<String> references(ClassNode node, String name) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
