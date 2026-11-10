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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Every path moves a 1.21.1 mod's own placement modifiers onto the rebuilt bases, rebuilds their types as codecs, and
 * disables its class mixin on Feature on 26.3, in the mod and its nested library. 26.2 keeps all of it.
 */
class PlacementInterfacePathParityTest {

    private static final String PLACEMENT = "net/minecraft/world/level/levelgen/placement/";
    private static final String REBUILT = "com/retromod/legacyapi/minecraft/world/level/levelgen/placement/";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";
    private static final String PROVIDERS = "net/minecraft/world/level/levelgen/feature/stateproviders/";
    private static final String REBUILT_PROVIDERS = "com/retromod/legacyapi/minecraft/world/level/levelgen/feature/stateproviders/";

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
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.3", "forge, transform, 26.3", "forge, batch, 26.3", "forge, aot, 26.3",
            "neoforge, runtime, 26.3", "neoforge, transform, 26.3", "neoforge, batch, 26.3", "neoforge, aot, 26.3",
            "fabric, runtime, 26.3", "fabric, transform, 26.3", "fabric, aot, 26.3",
            "neoforge, runtime, 26.2", "fabric, transform, 26.2", "forge, transform, 26.2"})
    @DisplayName("the modifiers extend the embedded rebuilt bases, their types become codecs, and the Feature mixin is disabled")
    void everyPathRebuildsThePlacementApi(String loader, String path, String host) throws Exception {
        boolean bridged = RetromodVersion.compareMcVersions(host, "26.3") >= 0;
        Path output = translate(loader, path, host, writeMod(loader));

        try (JarFile jar = new JarFile(output.toFile())) {
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            Files.write(nested, jar.getInputStream(jar.getJarEntry(nestedPath(loader))).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                for (Map.Entry<String, JarFile> part : Map.of("mod", jar, "lib", lib).entrySet()) {
                    String pkg = part.getKey();
                    String where = pkg + " (" + loader + " " + path + " on " + host + ")";
                    ClassNode filter = read(part.getValue(), pkg + "/ShapeFilter");
                    ClassNode twice = read(part.getValue(), pkg + "/Twice");
                    ClassNode thrice = read(part.getValue(), pkg + "/Thrice");
                    ClassNode types = read(part.getValue(), pkg + "/Types");
                    List<String> mixinTargets = targets(read(part.getValue(), pkg + "/FeatureMixin"));
                    ClassNode stone = read(part.getValue(), pkg + "/StoneProvider");
                    ClassNode pick = read(part.getValue(), pkg + "/Pick");
                    if (!bridged) {
                        assertEquals(PROVIDERS + "BlockStateProvider", stone.superName, where);
                        assertEquals(PLACEMENT + "PlacementFilter", filter.superName, where);
                        assertEquals(PLACEMENT + "PlacementModifier", twice.superName, where);
                        assertEquals("()L" + PLACEMENT + "PlacementModifierType;", method(twice, "type").desc, where);
                        assertEquals(List.of(FEATURE), mixinTargets, where + ": Feature is still a class on 26.2");
                        continue;
                    }
                    assertRebased(jar, lib, filter, "LegacyPlacementFilter", where);
                    assertRebased(jar, lib, twice, "LegacyPlacementModifier", where);
                    assertRebased(jar, lib, thrice, "LegacyRepeatingPlacement", where);
                    assertNotNull(method(twice, "getPositions"), where + ": the override keeps its name");
                    assertNotNull(method(filter, "shouldPlace"), where);
                    assertNotNull(method(thrice, "count"), where);
                    for (ClassNode modifier : List.of(filter, twice, thrice)) {
                        assertRebuiltType(method(modifier, "type").desc, REBUILT + "LegacyPlacementModifierType",
                                where + ": " + modifier.name + "'s type is the codec 26.3 registers, a class of its own");
                    }

                    // The type lambda now builds a supplier that the rebuilt types wrap into the registered codec.
                    MethodNode convert = method(types, "convert");
                    InvokeDynamicInsnNode lambda = indy(convert);
                    assertEquals("get", lambda.name, where);
                    assertTrue(lambda.desc.endsWith(")Ljava/util/function/Supplier;"), where + ": " + lambda.desc);
                    MethodInsnNode wrap = calls(convert).stream().filter(c -> c.name.equals("wrap")).findFirst()
                            .orElseThrow(() -> new AssertionError(where + ": no wrap call"));
                    assertTrue(wrap.owner.startsWith(SyntheticEmbedder.PREFIX) && wrap.owner.endsWith(REBUILT + "LegacyPlacementModifierTypes"),
                            where + ": " + wrap.owner);
                    assertNotNull(read(jar, lib, wrap.owner), where + ": the rebuilt types travel with the mod");
                    assertEquals(List.of("retromod/stripped/FeatureMixin"), mixinTargets,
                            where + ": a class mixin cannot change the Feature interface");

                    // The block state provider, its type built from a codec, and an old level-less getState call.
                    assertTrue(stone.superName.startsWith(SyntheticEmbedder.PREFIX)
                            && stone.superName.endsWith(REBUILT_PROVIDERS + "LegacyBlockStateProvider"), where + ": " + stone.superName);
                    assertRebuiltType(method(stone, "type").desc, REBUILT_PROVIDERS + "LegacyBlockStateProviderType", where);
                    assertNotNull(method(stone, "getState"), where);
                    MethodInsnNode made = calls(method(types, "<clinit>")).stream().filter(c -> c.name.equals("type")).findFirst()
                            .orElseThrow(() -> new AssertionError(where + ": the provider type is not built through the rebuilt calls"));
                    assertTrue(made.owner.endsWith(REBUILT_PROVIDERS + "LegacyBlockStateProviders"), where + ": " + made.owner);
                    MethodInsnNode asked = calls(method(pick, "pick")).get(0);
                    assertEquals(INVOKESTATIC, asked.getOpcode(), where);
                    assertEquals(made.owner + ".getState", asked.owner + "." + asked.name, where);
                }
            }
        }
    }

    /** {@code desc} returns the embedded copy of the rebuilt type {@code rebuilt}. */
    private static void assertRebuiltType(String desc, String rebuilt, String where) {
        assertTrue(desc.startsWith("()L" + SyntheticEmbedder.PREFIX) && desc.endsWith(rebuilt + ";"), where + ": " + desc);
    }

    /** The class extends the embedded copy of {@code base}, whose root implements the host's interface. */
    private static void assertRebased(JarFile jar, JarFile lib, ClassNode modifier, String base, String where) throws Exception {
        assertTrue(modifier.superName.startsWith(SyntheticEmbedder.PREFIX) && modifier.superName.endsWith(REBUILT + base),
                where + ": " + modifier.name + " extends " + modifier.superName);
        MethodInsnNode superCall = calls(method(modifier, "<init>")).get(0);
        assertEquals(modifier.superName + ".<init>()V", superCall.owner + "." + superCall.name + superCall.desc, where);
        ClassNode rebuilt = read(jar, lib, modifier.superName);
        while (!rebuilt.name.endsWith("LegacyPlacementModifier")) rebuilt = read(jar, lib, rebuilt.superName);
        assertEquals(List.of(PLACEMENT + "PlacementModifier"), rebuilt.interfaces, where);
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
                CliHarness.transform(input, out.resolve("placement.jar"), host);
                yield out.resolve("placement.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("placement.jar"), host);
                yield out.resolve("placement.jar");
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

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform(host);
            return new FabricModTransformer(host).transformMod(input, out);
        }
        Set<String> kinds = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (kinds.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    private Path writeMod(String loader) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>(fixture(loader, "lib"));
        Map<String, byte[]> mod = new LinkedHashMap<>(fixture(loader, "mod"));
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        Path input = Files.createDirectories(dir.resolve("in")).resolve("placement-" + loader + ".jar");
        Files.write(input, LoaderApiFixtures.jar(mod));
        return input;
    }

    private static String nestedPath(String loader) {
        return loader.equals("fabric") ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    /** The host names a 1.21.1 mod wrote: Mojang names, or intermediary on Fabric. */
    private record Names(String filter, String modifier, String repeating, String type, String context, String random,
            String pos, String feature, String getPositions, String shouldPlace, String count, String typeMethod,
            String provider, String providerType, String state, String providerTypeMethod, String getState) {
        static Names of(String loader) {
            if (!loader.equals("fabric")) {
                return new Names(PLACEMENT + "PlacementFilter", PLACEMENT + "PlacementModifier", PLACEMENT + "RepeatingPlacement",
                        PLACEMENT + "PlacementModifierType", PLACEMENT + "PlacementContext", "net/minecraft/util/RandomSource",
                        "net/minecraft/core/BlockPos", FEATURE, "getPositions", "shouldPlace", "count", "type",
                        PROVIDERS + "BlockStateProvider", PROVIDERS + "BlockStateProviderType",
                        "net/minecraft/world/level/block/state/BlockState", "type", "getState");
            }
            return new Names("net/minecraft/class_6661", "net/minecraft/class_6797", "net/minecraft/class_5857",
                    "net/minecraft/class_6798", "net/minecraft/class_5444", "net/minecraft/class_5819",
                    "net/minecraft/class_2338", "net/minecraft/class_3031", "method_14452", "method_38918", "method_14452",
                    "method_39615", "net/minecraft/class_4651", "net/minecraft/class_4652", "net/minecraft/class_2680",
                    "method_28862", "method_23455");
        }
    }

    /** A 1.21.1 mod for {@code loader}: a filter, a modifier, a repeating placement, their types, and a Feature mixin. */
    private static Map<String, byte[]> fixture(String loader, String pkg) {
        Names n = Names.of(loader);
        String id = "placement" + pkg;
        String config = id + ".mixins.json";
        String metadata = switch (loader) {
            case "fabric" -> "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"},\"mixins\":[\"" + config + "\"]"
                    + (pkg.equals("mod") ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}";
            default -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id
                    + "\"\nversion=\"1.0.0\"\n[[mixins]]\nconfig=\"" + config + "\"\n[[dependencies." + id + "]]\n"
                    + "modId=\"minecraft\"\ntype=\"required\"\nmandatory=true\nversionRange=\"[1.21.1,1.22)\"\n"
                    + "ordering=\"NONE\"\nside=\"BOTH\"\n";
        };
        String metadataPath = switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(metadataPath, metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(config, ("{\"required\":true,\"package\":\"" + pkg + "\",\"compatibilityLevel\":\"JAVA_21\","
                + "\"mixins\":[\"FeatureMixin\"]}").getBytes(StandardCharsets.UTF_8));
        String types = pkg + "/Types";
        String typeDesc = "()L" + n.type() + ";";
        String context = "(L" + n.context() + ";L" + n.random() + ";L" + n.pos() + ";";
        entries.put(pkg + "/ShapeFilter.class", modifier(pkg + "/ShapeFilter", n.filter(), types, "FILTER", n,
                mv -> { mv.visitInsn(ICONST_1); mv.visitInsn(IRETURN); }, ACC_PROTECTED, n.shouldPlace(), context + ")Z"));
        entries.put(pkg + "/Twice.class", modifier(pkg + "/Twice", n.modifier(), types, "TWICE", n, mv -> {
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESTATIC, "java/util/stream/Stream", "of",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/stream/Stream;", true);
            mv.visitInsn(ARETURN);
        }, ACC_PUBLIC, n.getPositions(), context + ")Ljava/util/stream/Stream;"));
        entries.put(pkg + "/Thrice.class", modifier(pkg + "/Thrice", n.repeating(), types, "THRICE", n,
                mv -> { mv.visitInsn(ICONST_3); mv.visitInsn(IRETURN); }, ACC_PROTECTED, n.count(),
                "(L" + n.random() + ";L" + n.pos() + ";)I"));
        entries.put(types + ".class", types(types, n));
        entries.put(pkg + "/StoneProvider.class", provider(pkg + "/StoneProvider", types, n));
        entries.put(pkg + "/Pick.class", pick(pkg + "/Pick", n));
        entries.put(pkg + "/FeatureMixin.class", featureMixin(pkg + "/FeatureMixin", n.feature()));
        return entries;
    }

    /** A modifier class with a no-argument constructor, its one placement method, and {@code type()} reading {@code field}. */
    private static byte[] modifier(String name, String base, String types, String field, Names n,
            java.util.function.Consumer<MethodVisitor> body, int access, String methodName, String methodDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, base, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, base, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor placement = cw.visitMethod(access, methodName, methodDesc, null, null);
        placement.visitCode();
        body.accept(placement);
        placement.visitMaxs(0, 0);
        placement.visitEnd();
        MethodVisitor type = cw.visitMethod(ACC_PUBLIC, n.typeMethod(), "()L" + n.type() + ";", null, null);
        type.visitCode();
        type.visitFieldInsn(GETSTATIC, types, field, "L" + n.type() + ";");
        type.visitInsn(ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The types, made as Farmer's Delight makes them: {@code convert(CODEC)} returns the lambda {@code () -> codec}. */
    private static byte[] types(String name, Names n) {
        String type = "L" + n.type() + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        for (String field : List.of("FILTER", "TWICE", "THRICE")) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, field, type, null, null).visitEnd();
        }
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "STONE", "L" + n.providerType() + ";", null, null).visitEnd();
        MethodVisitor convert = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "convert", "(L" + MAP_CODEC + ";)" + type, null, null);
        convert.visitCode();
        convert.visitVarInsn(ALOAD, 0);
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        convert.visitInvokeDynamicInsn("codec", "(L" + MAP_CODEC + ";)" + type, metafactory,
                Type.getType("()L" + MAP_CODEC + ";"),
                new Handle(H_INVOKESTATIC, name, "lambda$convert$0", "(L" + MAP_CODEC + ";)L" + MAP_CODEC + ";", false),
                Type.getType("()L" + MAP_CODEC + ";"));
        convert.visitInsn(ARETURN);
        convert.visitMaxs(0, 0);
        convert.visitEnd();
        MethodVisitor lambda = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$convert$0",
                "(L" + MAP_CODEC + ";)L" + MAP_CODEC + ";", null, null);
        lambda.visitCode();
        lambda.visitVarInsn(ALOAD, 0);
        lambda.visitInsn(ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        String pkg = name.substring(0, name.lastIndexOf('/') + 1);
        for (String[] made : new String[][] {{"FILTER", "ShapeFilter"}, {"TWICE", "Twice"}, {"THRICE", "Thrice"}}) {
            clinit.visitTypeInsn(NEW, pkg + made[1]);
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, pkg + made[1], "<init>", "()V", false);
            clinit.visitMethodInsn(INVOKESTATIC, MAP_CODEC, "unit", "(Ljava/lang/Object;)L" + MAP_CODEC + ";", false);
            clinit.visitMethodInsn(INVOKESTATIC, name, "convert", "(L" + MAP_CODEC + ";)" + type, false);
            clinit.visitFieldInsn(PUTSTATIC, name, made[0], type);
        }
        // Lithostitched builds its provider type with the constructor.
        clinit.visitTypeInsn(NEW, n.providerType());
        clinit.visitInsn(DUP);
        clinit.visitTypeInsn(NEW, pkg + "StoneProvider");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, pkg + "StoneProvider", "<init>", "()V", false);
        clinit.visitMethodInsn(INVOKESTATIC, MAP_CODEC, "unit", "(Ljava/lang/Object;)L" + MAP_CODEC + ";", false);
        clinit.visitMethodInsn(INVOKESPECIAL, n.providerType(), "<init>", "(L" + MAP_CODEC + ";)V", false);
        clinit.visitFieldInsn(PUTSTATIC, name, "STONE", "L" + n.providerType() + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class StoneProvider extends BlockStateProvider} with the 1.21.1 type() and level-less getState. */
    private static byte[] provider(String name, String types, Names n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, n.provider(), null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, n.provider(), "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor type = cw.visitMethod(ACC_PROTECTED, n.providerTypeMethod(), "()L" + n.providerType() + ";", null, null);
        type.visitCode();
        type.visitFieldInsn(GETSTATIC, types, "STONE", "L" + n.providerType() + ";");
        type.visitInsn(ARETURN);
        type.visitMaxs(0, 0);
        type.visitEnd();
        MethodVisitor state = cw.visitMethod(ACC_PUBLIC, n.getState(), "(L" + n.random() + ";L" + n.pos() + ";)L" + n.state() + ";", null, null);
        state.visitCode();
        state.visitInsn(ACONST_NULL);
        state.visitInsn(ARETURN);
        state.visitMaxs(0, 0);
        state.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code provider.getState(random, pos)}, as Lithostitched's weighted provider asks the providers it holds. */
    private static byte[] pick(String name, Names n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        String desc = "(L" + n.random() + ";L" + n.pos() + ";)L" + n.state() + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pick",
                "(L" + n.provider() + ";L" + n.random() + ";L" + n.pos() + ";)L" + n.state() + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, n.provider(), n.getState(), desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code @Mixin(Feature.class)} with one injector, as Farmer's Delight wrote it before Feature became an interface. */
    private static byte[] featureMixin(String name, String feature) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(feature));
        value.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = cw.visitMethod(ACC_PRIVATE, "onPlace", "()V", null, null);
        handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).visitEnd();
        handler.visitCode();
        handler.visitInsn(RETURN);
        handler.visitMaxs(0, 1);
        handler.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Every target the {@code @Mixin} annotation still names, as internal names. */
    private static List<String> targets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : mixin.invisibleAnnotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (!(annotation.values.get(i + 1) instanceof List<?> list)) continue;
                for (Object target : list) {
                    targets.add(target instanceof Type t ? t.getInternalName() : ((String) target).replace('.', '/'));
                }
            }
        }
        return targets;
    }

    /** A class from the nested library, or from the outer jar where the embedder put it. */
    private static ClassNode read(JarFile jar, JarFile lib, String name) throws Exception {
        return lib.getJarEntry(name + ".class") != null ? read(lib, name) : read(jar, name);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        return fail(method.name + " has no invokedynamic");
    }
}
