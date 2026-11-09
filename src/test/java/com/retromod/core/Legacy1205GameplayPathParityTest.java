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
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every class-transform path gives a Forge 1.20.1 mod's item, effect and arrow calls and its old effect tick the 1.20.5 bridges, outer and nested jar alike. */
class Legacy1205GameplayPathParityTest {

    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String EFFECT = "net/minecraft/world/effect/MobEffect";
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String PACKET = "net/minecraft/network/protocol/game/ClientboundUpdateMobEffectPacket";
    private static final String ARROW = "net/minecraft/world/entity/projectile/AbstractArrow";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";

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

    @ParameterizedTest(name = "Forge 1.20.1 mod, {0} {1} on {2}")
    @CsvSource({"forge, runtime, 26.2", "forge, runtime, 26.3", "forge, transform, 26.2", "forge, batch, 26.3",
            "forge, aot, 26.2", "neoforge, runtime, 1.21.1", "neoforge, runtime, 26.3", "neoforge, transform, 1.21.1",
            "neoforge, batch, 1.21.1", "neoforge, aot, 26.3"})
    @DisplayName("each path bridges the calls and the old effect tick, in the mod and its nested library")
    void everyPathBridgesTheOldMembers(String host, String path, String version) throws Exception {
        Path input = Files.createDirectories(dir.resolve("in")).resolve("arrows.jar");
        Files.write(input, modJar());

        Path output = translate(host, path, version, input);

        String tick = version.startsWith("26.") ? "applyEffectTick(Lnet/minecraft/server/level/ServerLevel;L" + LIVING + ";I)Z"
                : "applyEffectTick(L" + LIVING + ";I)Z";
        String expected = "[isSameItemSameComponents, updateMobEffectPacket, setKnockback] on the host or embedded helpers; "
                + "effect has " + tick;
        assertEquals(Map.of("mod", expected, "nested:lib", expected), summary(output));
    }

    /** Translates {@code input} for a {@code host} loader through one path and returns the output jar. */
    private Path translate(String host, String path, String version, Path input) throws Exception {
        McReflect.setForceForge(host.equals("forge"));
        McReflect.setForceNeoForge(host.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(host);
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(host, version, input, out);
            case "transform" -> {
                CliHarness.transform(input, out.resolve("arrows.jar"), version);
                yield out.resolve("arrows.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("arrows.jar"), version);
                yield out.resolve("arrows.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, version);
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

    /** Registers what the loader's entry point registers on {@code version}, then runs its mod transformer. */
    private Path runtime(String host, String version, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = version;
        transformer.clearRedirectsForTesting();
        Set<String> types = host.equals("forge") ? Set.of("forge", "common") : Set.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, version)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        return new ForgeModTransformer(version).transformMod(input, out);
    }

    /** What each package's calls reach and which tick its effect answers, for the mod and its nested library. */
    private static Map<String, String> summary(Path output) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod", describe(jar, "mod"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry("META-INF/jarjar/lib.jar");
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib", describe(lib, "lib"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static String describe(JarFile jar, String pkg) throws Exception {
        MethodNode run = read(jar, pkg + "/Calls").methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        List<String> names = new ArrayList<>();
        List<String> elsewhere = new ArrayList<>();
        for (AbstractInsnNode insn : run.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            names.add(call.name);
            boolean host = call.owner.equals(STACK);
            boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null
                    && (call.owner.endsWith("/Legacy1205Gameplay") || call.owner.endsWith("/LegacyArrowMembers"));
            if (!host && !embedded) elsewhere.add(call.owner + "." + call.name);
        }
        String where = elsewhere.isEmpty() ? " on the host or embedded helpers" : " with calls to " + elsewhere;
        List<String> ticks = new ArrayList<>();
        for (MethodNode method : read(jar, pkg + "/OldEffect").methods) {
            if (method.name.equals("applyEffectTick") && method.desc.endsWith(")Z")) ticks.add(method.name + method.desc);
        }
        return names + where + "; effect has " + String.join(", ", ticks);
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    /** A Forge 1.20.1 mod with SRG names, nesting a library mod that makes the same calls. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", metadata("arrowlib"));
        lib.put("lib/Calls.class", callsClass("lib/Calls"));
        lib.put("lib/OldEffect.class", effectClass("lib/OldEffect"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", metadata("arrows"));
        mod.put("mod/Calls.class", callsClass("mod/Calls"));
        mod.put("mod/OldEffect.class", effectClass("mod/OldEffect"));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] metadata(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code static void run(a, b, effect, arrow)}: compare stacks, resend an effect, set an arrow's knockback. */
    private static byte[] callsClass(String name) {
        String lStack = "L" + STACK + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(" + lStack + lStack + "L" + INSTANCE + ";L" + ARROW + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // isSameItemSameTags
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, STACK, "m_150942_", "(" + lStack + lStack + ")Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, PACKET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PACKET, "<init>", "(IL" + INSTANCE + ";)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_2);
        // setKnockback
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ARROW, "m_36735_", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mob effect whose old void tick, {@code applyEffectTick} in SRG, the host no longer calls. */
    private static byte[] effectClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, EFFECT, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m_6742_", "(L" + LIVING + ";I)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
