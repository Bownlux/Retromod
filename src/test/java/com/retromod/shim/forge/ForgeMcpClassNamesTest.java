/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.Forge_1_16_5_to_1_17.McpClassName;
import com.retromod.util.McReflect;
import com.retromod.shim.ShimRegistry;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.*;

/** Forge mods for 1.14 to 1.16.5 name Minecraft classes by MCP names, which 26.x hosts know under Mojang's. */
class ForgeMcpClassNamesTest {

    private static final String ZOMBIE = "net/minecraft/entity/monster/ZombieEntity";
    private static final String HOST_ZOMBIE = "net/minecraft/world/entity/monster/zombie/Zombie";
    // An MCP name Mojang's own 1.17 to 1.20.1 names share for the same class, which 26.x renamed.
    private static final String LIVE_NAME = "net/minecraft/advancements/AdvancementList";
    // An MCP name a 26.x host uses for another class: MCP's is the host's BaseAshSmokeParticle.
    private static final String HOST_NAME_OF_ANOTHER_CLASS = "net/minecraft/client/particle/RisingParticle";
    // MultiBufferSource is a host class on 26.1 only; later hosts send it to Retromod's own rebuild.
    private static final String UNTIL_26_2 = "net/minecraft/client/renderer/IRenderTypeBuffer";
    // A 1.14 to 1.16.5 interface whose name 1.12.2 also used; 26.x has it as the abstract class RenderLayer.
    private static final String LAYER_RENDERER = "net/minecraft/client/renderer/entity/layers/LayerRenderer";

    @TempDir
    Path directory;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("names Mojang split in two are left out, while a text component follows the chain's bridge")
    void splitsAreLeftOut() {
        Set<String> names = new HashSet<>();
        for (McpClassName row : Forge_1_16_5_to_1_17.mcpClassNames()) names.add(row.mcpName());
        // Cow and AbstractCow, HorseModel and AbstractEquineModel: the chain and the intermediary id disagree.
        for (String split : List.of("net/minecraft/entity/passive/CowEntity",
                "net/minecraft/client/renderer/entity/model/HorseModel", "net/minecraft/data/loot/BlockLootTables",
                "net/minecraft/data/loot/EntityLootTables", "net/minecraft/util/text/filter/ChatFilterClient",
                "net/minecraft/world/gen/feature/structure/StructureFeatures")) {
            assertFalse(names.contains(split), split + " is a split, not a rename");
        }
        assertTrue(names.contains("net/minecraft/util/text/StringTextComponent"),
                "the id's contents record left the Component hierarchy, so the chain's bridge wins");
    }

    @Test
    @DisplayName("the table maps each MCP name once, never to itself, and leaves the 1.12.2 table's names alone")
    void tableIsWellFormed() throws Exception {
        List<McpClassName> rows = Forge_1_16_5_to_1_17.mcpClassNames();
        assertTrue(rows.size() > 3000, "the bundled table should be loaded, got " + rows.size());
        Set<String> older = keysOf("/retromod/forge-1.12.2-class-moves.tsv");
        Set<String> seen = new HashSet<>();
        for (McpClassName row : rows) {
            assertTrue(seen.add(row.mcpName()), "listed twice: " + row.mcpName());
            assertNotEquals(row.mcpName(), row.target(), "maps to itself: " + row.mcpName());
            assertFalse(older.contains(row.mcpName()), "the 1.12.2 table already maps " + row.mcpName());
            assertTrue(row.untilHost().equals("*") || RetromodVersion.isUnobfuscatedTarget(row.untilHost()),
                    "a row can only stop at a 26.x host: " + row);
        }
    }

    @ParameterizedTest(name = "a mod for {0} gets {1}")
    @CsvSource({
            "1.16.5, " + HOST_ZOMBIE,
            "1.14.4, " + HOST_ZOMBIE,
            "1.14, " + HOST_ZOMBIE,
            // 1.13.2 used some of these names for other classes, and the table starts at 1.14.4.
            "1.13.2, " + ZOMBIE,
            "1.12.2, " + ZOMBIE,
            // An unknown version is most often a 1.12.2 mcmod.info placeholder such as ${mcversion}.
            "'', " + ZOMBIE,
            // Only MCP-era code names ZombieEntity, even in a jar whose pack format reads as a newer version.
            "1.18.2, " + HOST_ZOMBIE})
    @DisplayName("mods built before 1.14 or of unknown version keep their names, which can mean other classes there")
    void modsFrom114TakeTheTable(String source, String expected) {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);

        transformer.setModSourceVersion(source.isEmpty() ? null : source);
        assertEquals(expected, transformer.classRedirectForCurrentMod(ZOMBIE));
    }

    @ParameterizedTest(name = "a mod for {0} gets {1}")
    @CsvSource({
            "1.16.5, net/minecraft/advancements/AdvancementTree",
            "1.14.4, net/minecraft/advancements/AdvancementTree",
            "1.17.1, " + LIVE_NAME,
            "1.20.1, " + LIVE_NAME})
    @DisplayName("a name a host or a Mojang-named version also has moves only for mods known to be built before 1.17")
    void liveNamesMoveOnlyForKnownMcpMods(String source, String expected) {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);

        transformer.setModSourceVersion(source);
        assertEquals(expected, transformer.classRedirectForCurrentMod(LIVE_NAME));
    }

    @Test
    @DisplayName("an MCP name the host gives to another class is left out, since some passes read redirects unlimited")
    void hostNamesOfOtherClassesAreLeftOut() {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);

        assertFalse(transformer.getClassRedirects().containsKey(HOST_NAME_OF_ANOTHER_CLASS),
                "a 1.20.1 mod's RisingParticle is the host's own class and must keep its inherited renames");
        assertFalse(transformer.getClassRedirects().containsKey("net/minecraft/client/particle/HugeExplosionParticle"),
                "MCP's HugeExplosionParticle is the host's HugeExplosionSeedParticle, and the host has its own");
    }

    @ParameterizedTest(name = "host {0}: MCP names {1}, IRenderTypeBuffer {2}")
    @CsvSource({"1.20.1, false, false", "1.21.11, false, false", "26.1.2, true, true", "26.2, true, false",
            "26.3, true, false"})
    @DisplayName("the table loads on 26.x hosts, and a row stops at the first host its class is gone from")
    void hostGates(String host, boolean loads, boolean bufferRow) {
        onHost(host);
        int registered = Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);

        assertEquals(loads, registered > 0, "rows registered on " + host);
        assertEquals(loads, transformer.getClassRedirects().containsKey(ZOMBIE));
        assertEquals(bufferRow, transformer.getClassRedirects().containsKey(UNTIL_26_2));
    }

    @Test
    @DisplayName("a name another shim already redirects keeps that redirect and gets no version limit")
    void earlierRedirectWins() {
        onHost("26.3");
        transformer.registerClassRedirect(ZOMBIE, "fixture/OtherZombie");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);

        transformer.setModSourceVersion("1.12.2");
        assertEquals("fixture/OtherZombie", transformer.classRedirectForCurrentMod(ZOMBIE),
                "the 1.14 limit belongs to the table's rows, not to another shim's redirect");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"neoforge, 26.3", "forge, 26.3", "forge, 26.2", "neoforge, 26.1.2", "forge, 26.1.2"})
    @DisplayName("in the game every row keeps the table's target, so no later shim inherits its limits")
    void gameRegistrationKeepsEveryRow(String loader, String host) throws Exception {
        onHost(host);
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        String entryPoint = loader.equals("forge") ? "RetromodForge" : "RetromodNeoForge";
        Method register = Class.forName("com.retromod.core." + entryPoint)
                .getDeclaredMethod("registerTransformRedirects", RetromodTransformer.class);
        register.setAccessible(true);
        register.invoke(null, transformer);

        List<String> changed = new ArrayList<>();
        for (McpClassName row : Forge_1_16_5_to_1_17.mcpClassNames()) {
            if (!row.untilHost().equals("*") && !RetromodVersion.mcVersionExceeds(row.untilHost(), host)) continue;
            String registered = transformer.getClassRedirects().get(row.mcpName());
            if (!row.target().equals(registered)) changed.add(row.mcpName() + " -> " + registered);
        }
        assertEquals(List.of(), changed, "rows another registration replaced or took first");
    }

    @ParameterizedTest(name = "a mod for {0}: bridged {1}")
    @CsvSource({"1.16.5, true", "1.12.2, false"})
    @DisplayName("a bridge on the host class reaches an MCP name only for a mod that takes the table")
    void inheritedBridgesFollowTheSameLimit(String source, boolean bridged) {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);
        transformer.registerInheritedFieldStaticBridge("net/minecraft/client/player/ClientInput", "jumping", "Z",
                "fixture/InputBridge", "jumping", "(Ljava/lang/Object;)Z", "setJumping", "(Ljava/lang/Object;Z)V");

        transformer.setModSourceVersion(source);
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(readsJumping(), "fixture/Jumper")).accept(out, 0);

        boolean callsBridge = false;
        for (AbstractInsnNode insn : out.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("fixture/InputBridge")) callsBridge = true;
            if (insn instanceof FieldInsnNode field) {
                assertEquals("net/minecraft/util/MovementInput", field.owner, "a 1.12.2 mod keeps its own field read");
            }
        }
        assertEquals(bridged, callsBridge);
    }

    @ParameterizedTest(name = "a mod for {0}")
    @CsvSource({"1.16.5, " + HOST_ZOMBIE, "1.12.2, " + ZOMBIE, "1.20.1, " + HOST_ZOMBIE})
    @DisplayName("passes that read redirects outside the remapper see only what the current mod takes")
    void currentModViewHonorsTheLimits(String source, String expected) {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);

        transformer.setModSourceVersion(source);
        Map<String, String> view = transformer.getClassRedirectsForCurrentMod();
        assertEquals(expected, view.getOrDefault(ZOMBIE, ZOMBIE));
        assertEquals(!expected.equals(ZOMBIE), view.entrySet().stream().anyMatch(e -> e.getKey().equals(ZOMBIE)));
        transformer.setModSourceVersion("1.20.1");
        assertEquals(LIVE_NAME, transformer.getClassRedirectsForCurrentMod().getOrDefault(LIVE_NAME, LIVE_NAME),
                "a newer mod keeps a live name in every pass");
    }

    @ParameterizedTest(name = "a mod for {0}, redirect registered {1} the table")
    @CsvSource({"1.16.5, before", "1.16.5, after", "1.12.2, before"})
    @DisplayName("a method redirect whose descriptor names a table key reaches a call whether the mod takes the table")
    void methodRedirectAliasesSeeTheTable(String source, String order) {
        onHost("26.3");
        String owner = "fixture/Spawner";
        String oldDesc = "(L" + ZOMBIE + ";)V";
        Runnable redirect = () -> transformer.registerMethodRedirect(owner, "spawn", oldDesc,
                "fixture/SpawnBridge", "spawn", oldDesc);
        // Registration happens before any mod is known, as in the game.
        if (order.equals("before")) redirect.run();
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);
        if (order.equals("after")) redirect.run();

        transformer.setModSourceVersion(source);
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(callsSpawn(owner, oldDesc), "fixture/Caller")).accept(out, 0);
        boolean callsBridge = false;
        for (AbstractInsnNode insn : out.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("fixture/SpawnBridge")) callsBridge = true;
        }
        // A 1.16.5 mod's call carries Zombie after the remap and needs the alias; a 1.12.2 mod's keeps the old key.
        assertTrue(callsBridge, "the call must reach the redirect");
    }

    @ParameterizedTest(name = "a mod for {0}: {1}")
    @CsvSource({"1.16.5, true", "1.12.2, false", "'', false"})
    @DisplayName("a rebase check follows a table key only for a mod that takes the table")
    void writtenAsFollowsTheLimit(String source, boolean reaches) throws Exception {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);
        Method writtenAs = RetromodTransformer.class.getDeclaredMethod("writtenAs", String.class, String.class);
        writtenAs.setAccessible(true);

        transformer.setModSourceVersion(source.isEmpty() ? null : source);
        assertEquals(reaches, writtenAs.invoke(transformer, ZOMBIE, HOST_ZOMBIE));
    }

    @ParameterizedTest(name = "a mod for {0}")
    @CsvSource({"1.16.5, net/minecraft/client/renderer/entity/layers/RenderLayer", "1.12.2, " + LAYER_RENDERER})
    @DisplayName("the AOT fallback transform keeps the names a mod keeps, as the main transform does")
    void aotFallbackHonorsTheLimits(String source, String expectedInterface) throws Exception {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);
        Class<?> aot = Class.forName("com.retromod.aot.AotCompiler");
        Constructor<?> create = aot.getDeclaredConstructor(ShimRegistry.class, String.class, Path.class);
        create.setAccessible(true);
        Object compiler = create.newInstance(new ShimRegistry(), "26.3", directory.resolve("aot-cache"));
        Method simple = aot.getDeclaredMethod("transformClassSimple", byte[].class, String.class);
        simple.setAccessible(true);

        transformer.setModSourceVersion(source);
        ClassNode out = new ClassNode();
        new ClassReader((byte[]) simple.invoke(compiler, implementsLayerRenderer(), "fixture/Layer")).accept(out, 0);
        assertEquals(List.of(expectedInterface), out.interfaces);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "1.12.2 mcversion, 1.12.2, 6, false, true",
            "pack format 3, ${mcversion}, 3, false, true",
            "1.12.2 classes, ${mcversion}, 5, true, true",
            "stale 1.14 mcmod.info, ${mcversion}, 4, false, false"})
    @DisplayName("a 1.12.2 jar with a stale mods.toml keeps its names, a 1.14 jar with a stale mcmod.info does not")
    void staleModsTomlStillMeans1122(String label, String mcversion, int packFormat, boolean names1122Classes,
            boolean keeps) throws Exception {
        onHost("26.3");
        Forge_1_16_5_to_1_17.loadMcpClassNames(transformer);
        Path jar = directory.resolve(label.replace(' ', '-') + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "mcmod.info", "[{\"modid\":\"fixture\",\"mcversion\":\"" + mcversion + "\"}]");
            put(out, "pack.mcmeta", "{\"pack\":{\"pack_format\":" + packFormat + ",\"description\":\"x\"}}");
            String uses = names1122Classes ? "net/minecraft/entity/EntityLivingBase" : "java/lang/Object";
            out.putNextEntry(new JarEntry("fixture/Mob.class"));
            out.write(classExtending(uses));
            out.closeEntry();
        }

        // The jar's mods.toml declares 1.15, as geckolib-forge-1.12.2's template does.
        transformer.setModSourceVersion("1.15");
        transformer.setModSourceJar(jar);
        assertEquals(keeps ? ZOMBIE : HOST_ZOMBIE, transformer.classRedirectForCurrentMod(ZOMBIE));
    }

    private static void put(JarOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static byte[] classExtending(String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Mob", null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that implements the MCP interface {@code LayerRenderer}. */
    private static byte[] implementsLayerRenderer() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fixture/Layer", null, "java/lang/Object",
                new String[]{LAYER_RENDERER});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that calls {@code owner.spawn(desc)} with a null argument. */
    private static byte[] callsSpawn(String owner, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "spawn", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that reads the 1.16.5 MCP field {@code MovementInput.jumping}. */
    private static byte[] readsJumping() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Jumper", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "jumping",
                "(Lnet/minecraft/util/MovementInput;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/util/MovementInput", "jumping", "Z");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void onHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
    }

    private static Set<String> keysOf(String resource) throws Exception {
        Set<String> keys = new HashSet<>();
        try (InputStream in = ForgeMcpClassNamesTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.startsWith("#") && line.contains("\t")) keys.add(line.substring(0, line.indexOf('\t')));
            }
        }
        return keys;
    }
}
