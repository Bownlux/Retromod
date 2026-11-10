/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The atlas and texture {@code Material} becomes 26.1's {@code SpriteId} for mods of every loader. */
class MaterialSpriteIdMoveTest {

    private static final String OLD_MATERIAL = "net/minecraft/client/resources/model/Material";
    private static final String SPRITE_ID = "net/minecraft/client/resources/model/sprite/SpriteId";
    private static final String SPRITE_MATERIAL = "net/minecraft/client/resources/model/sprite/Material";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String PAIR = "(L" + IDENTIFIER + ";L" + IDENTIFIER + ";)V";
    private static final String GETTER = "()L" + IDENTIFIER + ";";
    private static final String PROBE = "probe/Sprites";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
    }

    @Test
    @DisplayName("Both name tables send the old Material to SpriteId")
    void tablesNameSpriteId() {
        IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();
        assertEquals(SPRITE_ID, mapper.getClassMoves().get(OLD_MATERIAL),
                "Forge and NeoForge mods name the class by its Mojang name");
        assertEquals(SPRITE_ID, mapper.mapClass("net/minecraft/class_4730"),
                "Fabric mods name it by intermediary id, which 1.21.11 gives to the atlas and texture pair");
    }

    @Test
    @DisplayName("The host gate moves Material on a 26.x host and leaves a 1.21.11 host alone")
    void hostGateLandsOnSpriteId() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer modern = newTransformer();
        modern.initFuzzyResolver(hostJar(dir.resolve("26x.jar"), SPRITE_ID, SPRITE_MATERIAL, IDENTIFIER));
        IntermediaryToMojangMapper.applyClassMovesOnly(modern);
        assertEquals(SPRITE_ID, modern.getClassRedirects().get(OLD_MATERIAL));

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer older = newTransformer();
        older.initFuzzyResolver(hostJar(dir.resolve("12111.jar"), OLD_MATERIAL, IDENTIFIER));
        IntermediaryToMojangMapper.applyClassMovesOnly(older);
        assertNull(older.getClassRedirects().get(OLD_MATERIAL), "1.21.11 still has the old Material");
    }

    @ParameterizedTest(name = "{0} mod on {1}")
    @CsvSource({
            "fabric, 26.1", "fabric, 26.2", "fabric, 26.3",
            "forge, 26.1", "forge, 26.2", "forge, 26.3",
            "neoforge, 26.1", "neoforge, 26.2", "neoforge, 26.3"})
    @DisplayName("new Material(atlas, texture) and its two getters become SpriteId calls that link")
    void oldMaterialBecomesSpriteId(String loader, String target) throws Exception {
        Path input = oldMod(loader);
        Path output = dir.resolve(loader + "-" + target + ".jar");
        // What --target-loader sets before the command runs.
        ModVersionDetector.setDefaultPreferredLoader(loader);
        McReflect.setForceNeoForge("neoforge".equals(loader));
        CliHarness.transform(input, output, target);

        MethodNode make = makeMethod(readEntry(output, PROBE + ".class"));
        assertEquals("(L" + IDENTIFIER + ";L" + IDENTIFIER + ";)L" + SPRITE_ID + ";", make.desc,
                "the mod's own signature names SpriteId too");
        List<String> calls = materialOps(make);
        assertEquals(List.of("NEW " + SPRITE_ID, SPRITE_ID + ".<init>" + PAIR,
                SPRITE_ID + ".atlasLocation" + GETTER, SPRITE_ID + ".texture" + GETTER), calls);

        // Where the host's client jar is on this machine, each call must exist there.
        Path host = hostClientJar(target);
        if (host == null) return;
        for (String[] member : new String[][] {{"<init>", PAIR}, {"atlasLocation", GETTER}, {"texture", GETTER}}) {
            assertTrue(declares(host, SPRITE_ID, member[0], member[1]),
                    host.getFileName() + " must declare SpriteId." + member[0] + member[1]);
        }
    }

    /** One class that builds a Material and reads both halves back, as each loader's old mods wrote it. */
    private Path oldMod(String loader) throws Exception {
        boolean fabric = "fabric".equals(loader);
        String owner = fabric ? "net/minecraft/class_4730" : OLD_MATERIAL;
        String location = fabric ? "net/minecraft/class_2960" : "net/minecraft/resources/ResourceLocation";
        // Forge 1.20.1 mods call members by SRG id, Fabric mods by intermediary id.
        String atlas = switch (loader) {
            case "fabric" -> "method_24144";
            case "forge" -> "m_119193_";
            default -> "atlasLocation";
        };
        String texture = switch (loader) {
            case "fabric" -> "method_24147";
            case "forge" -> "m_119203_";
            default -> "texture";
        };
        String pair = "(L" + location + ";L" + location + ";)V";
        String getter = "()L" + location + ";";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, PROBE, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make",
                "(L" + location + ";L" + location + ";)L" + owner + ";", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, owner);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESPECIAL, owner, "<init>", pair, false);
        m.visitVarInsn(ASTORE, 2);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, atlas, getter, false);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, texture, getter, false);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 2);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        Path jar = dir.resolve("spriteprobe-" + loader + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(metadataPath(loader)));
            out.write(metadata(loader).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(PROBE + ".class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    private static String metadataPath(String loader) {
        return switch (loader) {
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            default -> "META-INF/neoforge.mods.toml";
        };
    }

    private static String metadata(String loader) {
        if ("fabric".equals(loader)) {
            return "{\"schemaVersion\":1,\"id\":\"spriteprobe\",\"version\":\"1.0\","
                    + "\"depends\":{\"minecraft\":\"~1.21.1\",\"fabricloader\":\">=0.15.0\"}}";
        }
        // Forge 1.20.1 and NeoForge 1.21.1, the source versions of the two corpora.
        String range = "forge".equals(loader) ? "[1.20.1,1.21)" : "[1.21.1,1.21.2)";
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"spriteprobe\"\nversion=\"1.0\"\n"
                + "[[dependencies.spriteprobe]]\nmodId=\"minecraft\"\n"
                + "mandatory=true\ntype=\"required\"\nversionRange=\"" + range + "\"\n";
    }

    private static MethodNode makeMethod(byte[] probe) {
        ClassNode node = new ClassNode();
        Set<String> named = new HashSet<>();
        // Every class name the translated class mentions passes through map().
        new ClassReader(probe).accept(new ClassRemapper(node, new Remapper() {
            @Override
            public String map(String internalName) {
                named.add(internalName);
                return internalName;
            }
        }), 0);
        assertFalse(named.contains(SPRITE_MATERIAL),
                "the unrelated sprite Material record must not appear in the translated class");
        return node.methods.stream().filter(method -> method.name.equals("make")).findFirst().orElseThrow();
    }

    /** The class creations and calls on Material's successor, in order. */
    private static List<String> materialOps(MethodNode method) {
        List<String> ops = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) {
                ops.add("NEW " + type.desc);
            } else if (insn instanceof MethodInsnNode call) {
                ops.add(call.owner + "." + call.name + call.desc);
            }
        }
        return ops;
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(name);
            assertNotNull(entry, name + " in " + jar.getFileName());
            try (var input = archive.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    /** The vanilla client jar of the target's line in the local Prism library, or null when absent. */
    private static Path hostClientJar(String target) {
        String version = "26.1".equals(target) ? "26.1.2" : target;
        Path jar = Path.of(System.getProperty("user.home", ""),
                "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft",
                version, "minecraft-" + version + "-client.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    private static boolean declares(Path jar, String owner, String name, String desc) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(owner + ".class");
            if (entry == null) return false;
            ClassNode node = new ClassNode();
            try (var in = zip.getInputStream(entry)) {
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            }
            return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
        }
    }

    private static RetromodTransformer newTransformer() throws Exception {
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /** A game jar holding only empty classes with these names. */
    private static Path hostJar(Path jar, String... classes) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : classes) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
                writer.visitEnd();
                out.putNextEntry(new JarEntry(name + ".class"));
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
        return jar;
    }
}
