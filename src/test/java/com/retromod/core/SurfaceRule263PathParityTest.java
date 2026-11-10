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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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

/** Every class-transform path on every loader sends an old surface rule or density function user to the 26.3 rebuild embedded in its jar. */
class SurfaceRule263PathParityTest {

    private static final String MATERIAL = "net/minecraft/world/level/levelgen/material/";
    private static final List<String> EXPECTED = List.of(
            "embedded LegacyMaterialRules.isBiome", "embedded LegacyMaterialRules.ON_FLOOR",
            "MaterialRules.noiseCondition2d", "embedded LegacyMaterialRules.applyRule",
            "embedded LegacyMaterialRules.biome", "MaterialRuleContext.blockY",
            "MinecraftServer.getWorldGenSettings", "WorldGenSettings.options",
            "embedded LegacyMaterialRules.surfaceRule");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private java.lang.reflect.Field indexField;
    private FuzzyMethodResolver savedIndex;

    @TempDir
    Path dir;

    @AfterEach
    void restore() throws Exception {
        if (indexField != null) indexField.set(transformer, savedIndex);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, runtime", "forge, transform", "forge, batch", "forge, aot",
            "neoforge, runtime", "neoforge, transform", "neoforge, aot",
            "fabric, runtime", "fabric, transform", "fabric, aot"})
    @DisplayName("the old members reach the rebuild, and the mod's own rule source gains compile")
    void membersReachTheRebuild(String loader, String path) throws Exception {
        Names names = Names.of(loader);
        Path output = translate(loader, path, jar(loader, names));
        try (JarFile jar = new JarFile(output.toFile())) {
            assertEquals(EXPECTED, reached(jar, node(jar, "mod/Rules"), "run"));
            ClassNode own = node(jar, "mod/OwnRule");
            assertTrue(own.methods.stream().anyMatch(m -> m.name.equals("compile")
                    && m.desc.equals("(L" + MATERIAL + "MaterialRuleContext;)L" + MATERIAL + "rule/RuleEvaluator;")),
                    "26.3 calls compile on every rule");
            assertTrue(own.interfaces.stream().anyMatch(i -> i.startsWith(SyntheticEmbedder.PREFIX)
                    && i.endsWith("LegacyMaterialRuleCodec") && jar.getJarEntry(i + ".class") != null),
                    "the rule bridge travels with the mod: " + own.interfaces);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, runtime", "forge, transform", "forge, batch", "forge, aot", "neoforge, runtime", "neoforge, aot",
            "fabric, runtime", "fabric, transform", "fabric, aot"})
    @DisplayName("TerraBlender's settings mixin moves to the rule holder on every path")
    void settingsMixinRepairedOnEveryPath(String loader, String path) throws Exception {
        installHostIndex();
        Path output = translate(loader, path, settingsMixinJar(loader));
        try (JarFile jar = new JarFile(output.toFile())) {
            ClassNode mixin = node(jar, "mod/mixin/SettingsMixin");
            assertEquals(List.of("materialRule:Lnet/minecraft/core/Holder;"),
                    mixin.fields.stream().map(f -> f.name + ":" + f.desc).toList(), loader + " " + path);
            MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("surfaceRule")).findFirst().orElseThrow();
            assertTrue(handler.visibleAnnotations.get(0).values.contains(List.of("materialRule")),
                    loader + " " + path + ": " + handler.visibleAnnotations.get(0).values);
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode insn : handler.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
            assertEquals(calls.indexOf("net/minecraft/core/Holder.direct") + 1,
                    calls.indexOf("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.setReturnValue"),
                    loader + " " + path + ": " + calls);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, runtime", "forge, transform", "forge, batch", "forge, aot", "neoforge, runtime", "neoforge, aot",
            "fabric, runtime", "fabric, transform", "fabric, aot"})
    @DisplayName("Regions Unexplored's settings accessor and its caller move to the rule holder together on every path")
    void settingsAccessorRetargetedOnEveryPath(String loader, String path) throws Exception {
        Path output = translate(loader, path, settingsAccessorJar(loader));
        try (JarFile jar = new JarFile(output.toFile())) {
            ClassNode access = node(jar, "mod/mixin/SettingsAccess");
            MethodNode setter = access.methods.get(0);
            assertEquals("(Lnet/minecraft/core/Holder;)V", setter.desc, loader + " " + path);
            assertTrue(setter.visibleAnnotations.stream().anyMatch(a -> a.values != null && a.values.contains("materialRule")),
                    loader + " " + path + ": the accessor names the holder field");
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode insn : node(jar, "mod/Swap").methods.stream().filter(m -> m.name.equals("swap"))
                    .findFirst().orElseThrow().instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
            assertEquals(List.of("net/minecraft/core/Holder.direct(Ljava/lang/Object;)Lnet/minecraft/core/Holder;",
                    "mod/mixin/SettingsAccess.ru$setSurfaceRule(Lnet/minecraft/core/Holder;)V"), calls, loader + " " + path);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, runtime", "forge, transform", "forge, batch", "forge, aot",
            "neoforge, runtime", "neoforge, transform", "neoforge, aot"})
    @DisplayName("Citadel's read of a biome condition's keys goes through the embedded rebuild on every path")
    void biomeConditionReadOnEveryPath(String loader, String path) throws Exception {
        Path output = translate(loader, path, biomeReadJar(loader));
        try (JarFile jar = new JarFile(output.toFile())) {
            assertEquals(List.of("embedded LegacyMaterialRules.isBiomeCondition", "embedded LegacyMaterialRules.asBiomeCondition",
                    "embedded LegacyMaterialRules.biomes", "List.get"), reached(jar, node(jar, "mod/Grouping"), "first"),
                    loader + " " + path);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, runtime", "forge, transform", "forge, batch", "forge, aot",
            "neoforge, runtime", "neoforge, transform", "neoforge, aot",
            "fabric, runtime", "fabric, transform", "fabric, aot"})
    @DisplayName("an old density function and TerraBlender's End lookup reach the embedded rebuild on every path")
    void densityFunctionsReachTheRebuild(String loader, String path) throws Exception {
        Path output = translate(loader, path, densityFunctionJar(loader));
        try (JarFile jar = new JarFile(output.toFile())) {
            assertEquals(List.of("embedded LegacyDensityFunctions.erosion", "embedded LegacyDensityFunction$SinglePointContext.<init>",
                    "embedded LegacyDensityFunctions.compute"), reached(jar, node(jar, "mod/End"), "erosion"), loader + " " + path);
            ClassNode wave = node(jar, "mod/Wave");
            assertEquals(1, wave.interfaces.size(), wave.interfaces.toString());
            // Its compute reads no coordinate, so every path records that it depends on none.
            assertTrue(wave.methods.stream().anyMatch(m -> m.name.equals("retromod$readAxes")),
                    loader + " " + path + ": the axes the function reads are recorded");
            ClassNode bridge = node(jar, wave.interfaces.get(0));
            assertTrue(wave.interfaces.get(0).startsWith(SyntheticEmbedder.PREFIX)
                    && wave.interfaces.get(0).endsWith("LegacySimpleDensityFunctionCodec"), wave.interfaces.toString());
            for (String parent : bridge.interfaces) {
                assertTrue(parent.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(parent + ".class") != null,
                        "the bridge's parent " + parent + " travels with the mod");
            }
        }
    }

    /** Regions Unexplored's accessor interface and a caller that sets the rule through it. */
    private static byte[] settingsAccessorJar(String loader) throws Exception {
        String ruleSource = "net/minecraft/world/level/levelgen/SurfaceRules$RuleSource";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        String meta = new String(metadata(loader), StandardCharsets.UTF_8);
        if (loader.equals("fabric")) meta = meta.substring(0, meta.length() - 1) + ",\"mixins\":[\"rules.mixins.json\"]}";
        mod.put(metadataPath(loader), meta.getBytes(StandardCharsets.UTF_8));
        mod.put("rules.mixins.json", "{\"required\":true,\"package\":\"mod.mixin\",\"mixins\":[\"SettingsAccess\"]}"
                .getBytes(StandardCharsets.UTF_8));
        ClassWriter access = new ClassWriter(0);
        access.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "mod/mixin/SettingsAccess",
                null, "java/lang/Object", null);
        var target = access.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitArray("value");
        target.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/world/level/levelgen/NoiseGeneratorSettings"));
        target.visitEnd();
        MethodVisitor setter = access.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "ru$setSurfaceRule",
                "(L" + ruleSource + ";)V", null, null);
        setter.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true).visit("value", "surfaceRule");
        setter.visitAnnotation("Lorg/spongepowered/asm/mixin/Mutable;", true).visitEnd();
        setter.visitEnd();
        access.visitEnd();
        mod.put("mod/mixin/SettingsAccess.class", access.toByteArray());
        ClassWriter swap = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        swap.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Swap", null, "java/lang/Object", null);
        MethodVisitor mv = swap.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "swap",
                "(Ljava/lang/Object;L" + ruleSource + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "mod/mixin/SettingsAccess");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "mod/mixin/SettingsAccess", "ru$setSurfaceRule", "(L" + ruleSource + ";)V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        swap.visitEnd();
        mod.put("mod/Swap.class", swap.toByteArray());
        return LoaderApiFixtures.jar(mod);
    }

    /** Citadel's grouping read: the first key of a biome condition source, in the loader's own names. */
    private static byte[] biomeReadJar(String loader) throws Exception {
        String source = "net/minecraft/world/level/levelgen/SurfaceRules$BiomeConditionSource";
        String field = loader.equals("forge") ? "f_189489_" : "biomes";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Grouping", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "first", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        org.objectweb.asm.Label none = new org.objectweb.asm.Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, source);
        mv.visitJumpInsn(Opcodes.IFEQ, none);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, source);
        mv.visitFieldInsn(Opcodes.GETFIELD, source, field, "Ljava/util/List;");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(none);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        mod.put("mod/Grouping.class", cw.toByteArray());
        return LoaderApiFixtures.jar(mod);
    }

    /**
     * A simple density function of the mod's own and TerraBlender's End lookup, in Mojang names on every loader.
     * The intermediary and SRG spellings are covered by translating the real mods.
     */
    private static byte[] densityFunctionJar(String loader) throws Exception {
        String old = "net/minecraft/world/level/levelgen/DensityFunction";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader));
        ClassWriter wave = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        wave.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Wave", null, "java/lang/Object",
                new String[]{old + "$SimpleFunction"});
        MethodVisitor compute = wave.visitMethod(Opcodes.ACC_PUBLIC, "compute", "(L" + old + "$FunctionContext;)D", null, null);
        compute.visitCode();
        compute.visitInsn(Opcodes.DCONST_1);
        compute.visitInsn(Opcodes.DRETURN);
        compute.visitMaxs(0, 0);
        compute.visitEnd();
        wave.visitEnd();
        mod.put("mod/Wave.class", wave.toByteArray());
        ClassWriter end = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        end.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/End", null, "java/lang/Object", null);
        MethodVisitor mv = end.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "erosion",
                "(Lnet/minecraft/world/level/biome/Climate$Sampler;)D", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/biome/Climate$Sampler", "erosion", "()L" + old + ";", false);
        mv.visitTypeInsn(Opcodes.NEW, old + "$SinglePointContext");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, old + "$SinglePointContext", "<init>", "(III)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, old, "compute", "(L" + old + "$FunctionContext;)D", true);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        end.visitEnd();
        mod.put("mod/End.class", end.toByteArray());
        return LoaderApiFixtures.jar(mod);
    }

    /** Indexes a 26.3 host with the holder, the rule type and the generator settings, which the repaired mixin loads. */
    private void installHostIndex() throws Exception {
        Path jar = Files.createTempFile(dir, "host-", ".jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter holder = new ClassWriter(0);
            holder.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                    "net/minecraft/core/Holder", null, "java/lang/Object", null);
            holder.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor direct = holder.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "direct",
                    "(Ljava/lang/Object;)Lnet/minecraft/core/Holder;", null, null);
            direct.visitCode();
            direct.visitInsn(Opcodes.ACONST_NULL);
            direct.visitInsn(Opcodes.ARETURN);
            direct.visitMaxs(1, 1);
            direct.visitEnd();
            holder.visitEnd();
            putClass(out, holder.toByteArray());
            ClassWriter rule = new ClassWriter(0);
            rule.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, MATERIAL + "rule/MaterialRule",
                    null, "java/lang/Object", null);
            rule.visitEnd();
            putClass(out, rule.toByteArray());
            ClassWriter settings = new ClassWriter(0);
            settings.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "net/minecraft/world/level/levelgen/NoiseGeneratorSettings",
                    null, "java/lang/Record", null);
            settings.visitEnd();
            putClass(out, settings.toByteArray());
        }
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(jar);
        indexField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        indexField.setAccessible(true);
        savedIndex = (FuzzyMethodResolver) indexField.get(transformer);
        indexField.set(transformer, index);
    }

    private static void putClass(java.util.jar.JarOutputStream out, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(new ClassReader(bytes).getClassName() + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    /** A mod with TerraBlender's settings mixin and the manager its handler calls, listed in a mixin config on Fabric. */
    private static byte[] settingsMixinJar(String loader) throws Exception {
        String ruleSource = "net/minecraft/world/level/levelgen/SurfaceRules$RuleSource";
        String callback = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
        Map<String, byte[]> mod = new LinkedHashMap<>();
        String meta = new String(metadata(loader), StandardCharsets.UTF_8);
        if (loader.equals("fabric")) meta = meta.substring(0, meta.length() - 1) + ",\"mixins\":[\"rules.mixins.json\"]}";
        mod.put(metadataPath(loader), meta.getBytes(StandardCharsets.UTF_8));
        mod.put("rules.mixins.json", "{\"required\":true,\"package\":\"mod.mixin\",\"mixins\":[\"SettingsMixin\"]}"
                .getBytes(StandardCharsets.UTF_8));

        ClassWriter manager = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        manager.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Manager", null, "java/lang/Object", null);
        MethodVisitor wrap = manager.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wrap",
                "(L" + ruleSource + ";)L" + ruleSource + ";", null, null);
        wrap.visitCode();
        wrap.visitVarInsn(Opcodes.ALOAD, 0);
        wrap.visitInsn(Opcodes.ARETURN);
        wrap.visitMaxs(0, 0);
        wrap.visitEnd();
        manager.visitEnd();
        mod.put("mod/Manager.class", manager.toByteArray());

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER, "mod/mixin/SettingsMixin", null,
                "java/lang/Object", null);
        var target = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitArray("value");
        target.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/world/level/levelgen/NoiseGeneratorSettings"));
        target.visitEnd();
        var shadow = cw.visitField(Opcodes.ACC_PRIVATE, "surfaceRule", "L" + ruleSource + ";", null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
        shadow.visitEnd();
        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "surfaceRule", "(L" + callback + ";)V", null, null);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        var methods = inject.visitArray("method");
        methods.visit(null, "surfaceRule");
        methods.visitEnd();
        inject.visit("cancellable", true);
        inject.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 1);
        handler.visitVarInsn(Opcodes.ALOAD, 0);
        handler.visitFieldInsn(Opcodes.GETFIELD, "mod/mixin/SettingsMixin", "surfaceRule", "L" + ruleSource + ";");
        handler.visitMethodInsn(Opcodes.INVOKESTATIC, "mod/Manager", "wrap", "(L" + ruleSource + ";)L" + ruleSource + ";", false);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, callback, "setReturnValue", "(Ljava/lang/Object;)V", false);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        cw.visitEnd();
        mod.put("mod/mixin/SettingsMixin.class", cw.toByteArray());
        return LoaderApiFixtures.jar(mod);
    }

    /** One loader's spelling of the old members. */
    record Names(String rules, String ruleSource, String conditionSource, String context, String surfaceRule,
            String key, String isBiome, String onFloor, String noise, String biome, String blockY, String data,
            String options, String getWorldData, String worldGenOptions, String settings, String surfaceRuleGetter,
            String codecHolder, String codec) {

        static Names of(String loader) {
            String rules = "net/minecraft/world/level/levelgen/SurfaceRules";
            return switch (loader) {
                case "forge" -> new Names(rules, rules + "$RuleSource", rules + "$ConditionSource", rules + "$Context",
                        rules + "$SurfaceRule", "net/minecraft/resources/ResourceKey", "m_189416_", "f_189375_",
                        "m_189409_", "f_189555_", "f_189557_", "net/minecraft/world/level/storage/WorldData",
                        "net/minecraft/world/level/levelgen/WorldOptions", "m_129910_", "m_246337_",
                        "net/minecraft/world/level/levelgen/NoiseGeneratorSettings", "f_188871_",
                        "net/minecraft/util/KeyDispatchDataCodec", "m_213795_");
                case "neoforge" -> new Names(rules, rules + "$RuleSource", rules + "$ConditionSource", rules + "$Context",
                        rules + "$SurfaceRule", "net/minecraft/resources/ResourceKey", "isBiome", "ON_FLOOR",
                        "noiseCondition", "biome", "blockY", "net/minecraft/world/level/storage/WorldData",
                        "net/minecraft/world/level/levelgen/WorldOptions", "getWorldData", "worldGenOptions",
                        "net/minecraft/world/level/levelgen/NoiseGeneratorSettings", "surfaceRule",
                        "net/minecraft/util/KeyDispatchDataCodec", "codec");
                default -> new Names("net/minecraft/class_6686", "net/minecraft/class_6686$class_6708",
                        "net/minecraft/class_6686$class_6693", "net/minecraft/class_6686$class_6694",
                        "net/minecraft/class_6686$class_6715", "net/minecraft/class_5321", "method_39055", "field_35222",
                        "method_39052", "field_35614", "field_35617", "net/minecraft/class_5219", "net/minecraft/class_5285",
                        "method_27728", "method_28057", "net/minecraft/class_5284", "comp_478", "net/minecraft/class_7243",
                        "method_39061");
            };
        }
    }

    /** Translates {@code jar} for a 26.3 {@code loader} host through one path and returns the output jar. */
    private Path translate(String loader, String path, byte[] jar) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("rules.jar");
        Files.write(input, jar);
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = out.resolve("rules.jar");
        switch (path) {
            case "runtime" -> output = runtime(loader, input, out);
            case "transform" -> CliHarness.transform(input, output, "26.3");
            case "aot" -> CliHarness.aot(input, output, "26.3");
            default -> {
                CliHarness.batch(input.getParent(), out, "26.3");
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(file -> file.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    output = all.get(0);
                }
            }
        }
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what the loader's entry point registers on 26.3, then runs its mod transformer. */
    private Path runtime(String loader, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        if (loader.equals("fabric")) {
            RetromodPreLaunch.registerShimsForTransform("26.3");
            return new FabricModTransformer("26.3").transformMod(input, out);
        }
        Set<String> types = loader.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, "26.3")) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer("26.3").transformMod(input, out);
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    /** Each call and field access of {@code method} as {@code Owner.name}, marked embedded when the owner is a copy in this jar. */
    private static List<String> reached(JarFile jar, ClassNode node, String method) {
        List<String> reached = new ArrayList<>();
        MethodNode run = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        for (AbstractInsnNode insn : run.instructions) {
            String owner;
            String name;
            if (insn instanceof MethodInsnNode call) {
                owner = call.owner;
                name = call.name;
            } else if (insn instanceof FieldInsnNode field) {
                owner = field.owner;
                name = field.name;
            } else {
                continue;
            }
            String simple = owner.substring(owner.lastIndexOf('/') + 1);
            if (owner.startsWith(SyntheticEmbedder.PREFIX)) {
                assertNotNull(jar.getJarEntry(owner + ".class"), owner + " is not in " + jar.getName());
                reached.add("embedded " + simple + "." + name);
            } else {
                reached.add(simple + "." + name);
            }
        }
        return reached;
    }

    /** A mod for {@code loader}'s old version that uses the old members, with a rule source of its own. */
    private static byte[] jar(String loader, Names names) throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader));
        mod.put("mod/Rules.class", rulesClass(loader, names));
        mod.put("mod/OwnRule.class", ownRule(names));
        return LoaderApiFixtures.jar(mod);
    }

    private static String metadataPath(String loader) {
        return switch (loader) {
            case "forge" -> "META-INF/mods.toml";
            case "neoforge" -> "META-INF/neoforge.mods.toml";
            default -> "fabric.mod.json";
        };
    }

    private static byte[] metadata(String loader) {
        String text = switch (loader) {
            case "forge" -> "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"rules\"\n"
                    + "version=\"1.0.0\"\n[[dependencies.rules]]\nmodId=\"minecraft\"\nmandatory=true\n"
                    + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
            case "neoforge" -> "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"rules\"\n"
                    + "version=\"1.0.0\"\n[[dependencies.rules]]\nmodId=\"minecraft\"\ntype=\"required\"\n"
                    + "versionRange=\"[1.21.1,1.22)\"\nordering=\"NONE\"\nside=\"BOTH\"\n";
            default -> "{\"schemaVersion\":1,\"id\":\"rules\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"1.21.1\",\"fabricloader\":\">=0.15.0\"}}";
        };
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code static void run()} using each old member, made a {@code @Mod} on Forge-family loaders. */
    private static byte[] rulesClass(String loader, Names n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Rules", null, "java/lang/Object", null);
        if (!loader.equals("fabric")) {
            var annotation = cw.visitAnnotation(loader.equals("forge") ? "Lnet/minecraftforge/fml/common/Mod;"
                    : "Lnet/neoforged/fml/common/Mod;", true);
            annotation.visit("value", "rules");
            annotation.visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        String condition = "L" + n.conditionSource() + ";";
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.rules(), n.isBiome(), "([L" + n.key() + ";)" + condition, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, n.rules(), n.onFloor(), condition);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.rules(), n.noise(), "(L" + n.key() + ";D)" + condition, false);
        mv.visitInsn(Opcodes.POP);
        // A rule source applied to a context through the old interface, as TerraBlender runs the rules it holds.
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, n.ruleSource(), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, n.context(), n.biome(), "Ljava/util/function/Supplier;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, n.context(), n.blockY(), "I");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/MinecraftServer", n.getWorldData(),
                "()L" + n.data() + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, n.data(), n.worldGenOptions(), "()L" + n.options() + ";", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, n.settings(), n.surfaceRuleGetter(), "()L" + n.ruleSource() + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A rule source of the mod's own, with the methods javac writes for it. */
    private static byte[] ownRule(Names n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/OwnRule", null, "java/lang/Object",
                new String[]{n.ruleSource()});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(L" + n.context() + ";)L" + n.surfaceRule() + ";",
                null, null);
        apply.visitCode();
        apply.visitInsn(Opcodes.ACONST_NULL);
        apply.visitInsn(Opcodes.ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        MethodVisitor bridge = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitTypeInsn(Opcodes.CHECKCAST, n.context());
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "mod/OwnRule", "apply", "(L" + n.context() + ";)L" + n.surfaceRule() + ";", false);
        bridge.visitInsn(Opcodes.ARETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        MethodVisitor codec = cw.visitMethod(Opcodes.ACC_PUBLIC, n.codec(), "()L" + n.codecHolder() + ";", null, null);
        codec.visitCode();
        codec.visitInsn(Opcodes.ACONST_NULL);
        codec.visitInsn(Opcodes.ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
