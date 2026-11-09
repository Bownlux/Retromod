/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Carry On and FancyMenu read item strings with 1.20.4's ItemParser.parseForItem, which 1.20.5 removed with its result. */
class LegacyItemParserBridgeTest {

    private static final String MOD_CLASS = "test/items/ModelOverride";
    private static final String OLD_RESULT = LegacyItemParserBridge.OLD_RESULT;
    private static final String RESULT = LegacyItemParserBridge.RESULT;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the old call and the result's accessors reach the rebuilt parser and result")
    void callsReachTheRebuiltClasses() {
        LegacyItemParserBridge.registerRedirects(transformer);

        List<MethodInsnNode> calls = calls(transform(modelOverride()));

        assertEquals(3, calls.size(), calls.toString());
        assertCall(calls.get(0), Opcodes.INVOKESTATIC, LegacyItemParserBridge.PARSER, "parseForItem",
                LegacyItemParserBridge.HELPER_DESC);
        assertCall(calls.get(1), Opcodes.INVOKEVIRTUAL, RESULT, "item", "()Lnet/minecraft/core/Holder;");
        assertCall(calls.get(2), Opcodes.INVOKEVIRTUAL, RESULT, "nbt", "()Lnet/minecraft/nbt/CompoundTag;");
        assertTrue(transformer.getSyntheticClasses().containsKey(RESULT), "the rebuilt result travels with the parser");
    }

    @Test
    @DisplayName("a 1.21 parse call and its result reach the rebuilt parse where the host returns ItemInput")
    void parseReachesTheRebuiltParse() {
        // Carry On 1.21.1 called parse, whose ItemResult 26.1 replaced, and the class move once left it naming the host.
        LegacyItemParserBridge.registerRedirects(transformer);
        LegacyItemParserBridge.registerParseRedirects(transformer);

        List<MethodInsnNode> calls = calls(transform(parseCaller()));

        assertEquals(2, calls.size(), calls.toString());
        assertCall(calls.get(0), Opcodes.INVOKESTATIC, LegacyItemParserBridge.PARSER, "parse",
                "(L" + LegacyItemParserBridge.ITEM_PARSER + ";Lcom/mojang/brigadier/StringReader;)L" + RESULT + ";");
        assertCall(calls.get(1), Opcodes.INVOKEVIRTUAL, RESULT, "components",
                "()Lnet/minecraft/core/component/DataComponentPatch;");
    }

    @Test
    @DisplayName("only a host whose parse returns ItemInput takes the parse bridge")
    void parseBridgeFollowsTheHost() {
        ClassNode current = parser(false);
        current.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "parse",
                "(Lcom/mojang/brigadier/StringReader;)L" + LegacyItemParserBridge.ITEM_INPUT + ";", null, null));
        ClassNode older = parser(false);
        older.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "parse",
                "(Lcom/mojang/brigadier/StringReader;)L" + OLD_RESULT + ";", null, null));

        assertTrue(LegacyItemParserBridge.hostParsesToInput(current));
        assertFalse(LegacyItemParserBridge.hostParsesToInput(older), "a 1.21.1 host still returns the result");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyItemParserBridge.hostParsesToInput(null), "offline, a 26.x target returns ItemInput");
    }

    @Test
    @DisplayName("the host shape decides: a 1.20.4 ItemParser keeps its static parseForItem, a 1.20.5 one has none")
    void hostShapeDecides() {
        assertFalse(LegacyItemParserBridge.hostDropped(parser(true)));
        assertTrue(LegacyItemParserBridge.hostDropped(parser(false)));
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        assertFalse(LegacyItemParserBridge.hostDropped(null), "offline, a 1.20.4 target keeps the old call");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(LegacyItemParserBridge.hostDropped(null));
    }

    @Test
    @DisplayName("on the real 26.2 classes the rebuilt parser reads an id and its NBT, and refuses an unknown id as a syntax error")
    void parsesOnTheRealHost() throws Exception {
        List<Path> jars = hostJars();
        Assumptions.assumeTrue(jars != null, "Minecraft 26.2 and its libraries are not installed in Prism's libraries");
        try (URLClassLoader host = new HostLoader(jars)) {
            Class<?> parser = host.loadClass(LegacyItemParserBridge.PARSER.replace('/', '.'));
            Class<?> readerType = host.loadClass("com.mojang.brigadier.StringReader");
            Class<?> getterType = host.loadClass("net.minecraft.core.HolderGetter");
            Class<?> reference = host.loadClass("net.minecraft.core.Holder$Reference");
            Class<?> ownerType = host.loadClass("net.minecraft.core.HolderOwner");
            Object owner = Proxy.newProxyInstance(host, new Class<?>[] {ownerType}, (proxy, method, args) -> false);
            Object[] diamond = new Object[1];
            // A lookup that knows one item and answers it with a reference, as the item registry's lookup does.
            Object items = Proxy.newProxyInstance(host, new Class<?>[] {getterType}, (proxy, method, args) -> {
                if (!method.getName().equals("get") || !String.valueOf(args[0]).contains("minecraft:diamond")) {
                    return Optional.empty();
                }
                diamond[0] = reference.getMethod("createStandAlone", ownerType, args[0].getClass()).invoke(null, owner, args[0]);
                return Optional.of(diamond[0]);
            });
            Method parse = parser.getMethod("parseForItem", getterType, readerType);

            Object reader = readerType.getConstructor(String.class).newInstance("minecraft:diamond{Damage:3,display:{Name:'x'}}");
            Object result = parse.invoke(null, items, reader);
            assertSame(diamond[0], result.getClass().getMethod("item").invoke(result));
            Object nbt = result.getClass().getMethod("nbt").invoke(result);
            assertTrue(String.valueOf(nbt).contains("Damage:3"), "the NBT after the id is read: " + nbt);
            assertFalse((Boolean) readerType.getMethod("canRead").invoke(reader), "the whole string is consumed");

            Object plain = parse.invoke(null, items, readerType.getConstructor(String.class).newInstance("minecraft:diamond"));
            assertNull(plain.getClass().getMethod("nbt").invoke(plain), "an id alone has no NBT, as in 1.20.4");

            InvocationTargetException unknown = assertThrows(InvocationTargetException.class, () ->
                    parse.invoke(null, items, readerType.getConstructor(String.class).newInstance("minecraft:nope")));
            assertEquals("com.mojang.brigadier.exceptions.CommandSyntaxException", unknown.getCause().getClass().getName(),
                    "Carry On catches the syntax error and reports the bad entry");
        }
    }

    // ---- fixtures ----

    /** Carry On's ModelOverride.of: parse an item string, then read the result's item and NBT. */
    private static byte[] modelOverride() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(Lnet/minecraft/core/HolderLookup;Lcom/mojang/brigadier/StringReader;)V", null,
                new String[] {"com/mojang/brigadier/exceptions/CommandSyntaxException"});
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyItemParserBridge.ITEM_PARSER, "parseForItem",
                "(Lnet/minecraft/core/HolderLookup;Lcom/mojang/brigadier/StringReader;)L" + OLD_RESULT + ";", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_RESULT, "item", "()Lnet/minecraft/core/Holder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_RESULT, "nbt", "()Lnet/minecraft/nbt/CompoundTag;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Carry On 1.21.1's ModelOverride: parse an item string, then read the result's components. */
    private static byte[] parseCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(L" + LegacyItemParserBridge.ITEM_PARSER + ";Lcom/mojang/brigadier/StringReader;)V", null,
                new String[] {"com/mojang/brigadier/exceptions/CommandSyntaxException"});
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyItemParserBridge.ITEM_PARSER, "parse",
                "(Lcom/mojang/brigadier/StringReader;)L" + OLD_RESULT + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_RESULT, "components",
                "()Lnet/minecraft/core/component/DataComponentPatch;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode parser(boolean withParseForItem) {
        ClassNode node = new ClassNode();
        node.name = LegacyItemParserBridge.ITEM_PARSER;
        if (withParseForItem) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "parseForItem",
                    "(Lnet/minecraft/core/HolderLookup;Lcom/mojang/brigadier/StringReader;)L" + OLD_RESULT + ";", null, null));
        }
        return node;
    }

    /** The 26.2 client and the newest of each library Prism installed beside it, or null when it has not installed 26.2. */
    private static List<Path> hostJars() throws java.io.IOException {
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
        Path client = libraries.resolve("com/mojang/minecraft/26.2/minecraft-26.2-client.jar");
        if (!Files.isRegularFile(client)) return null;
        List<Path> jars = new ArrayList<>(List.of(client));
        // Identifier and the NBT reader reach Brigadier, DataFixerUpper, authlib, Netty, JOML and the rest while
        // they initialize, so every library group the client uses is on the path, one version of each artifact.
        for (String group : List.of("com/mojang", "com/google", "it/unimi", "io/netty", "org/joml", "org/slf4j",
                "org/apache/commons", "org/apache/logging")) {
            Path root = libraries.resolve(group);
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root, 4)) {
                files.filter(Files::isDirectory).filter(LegacyItemParserBridgeTest::isArtifact)
                        .filter(artifact -> !artifact.endsWith("minecraft"))
                        .map(LegacyItemParserBridgeTest::newestJar).filter(java.util.Objects::nonNull).forEach(jars::add);
            }
        }
        return jars;
    }

    /** A folder whose children are version folders holding jars. */
    private static boolean isArtifact(Path dir) {
        try (var children = Files.list(dir)) {
            return children.anyMatch(version -> Files.isDirectory(version) && hasJar(version));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static boolean hasJar(Path dir) {
        try (var files = Files.list(dir)) {
            return files.anyMatch(f -> f.toString().endsWith(".jar"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** The plain jar of the artifact's newest version. */
    private static Path newestJar(Path artifact) {
        try (var versions = Files.list(artifact)) {
            Path newest = versions.filter(LegacyItemParserBridgeTest::hasJar)
                    .max((a, b) -> compareVersions(a.getFileName().toString(), b.getFileName().toString())).orElse(null);
            if (newest == null) return null;
            try (var files = Files.list(newest)) {
                return files.filter(f -> f.toString().endsWith(".jar") && !f.getFileName().toString().contains("-natives"))
                        .findFirst().orElse(null);
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Compares version folder names by their numbers in order, so 4.2.16 is newer than 4.2.7. */
    private static int compareVersions(String a, String b) {
        String[] left = a.split("[^0-9]+");
        String[] right = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long x = i < left.length && !left[i].isEmpty() ? Long.parseLong(left[i]) : 0;
            long y = i < right.length && !right[i].isEmpty() ? Long.parseLong(right[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    /** The host jars, with Retromod's rebuilt classes defined beside them so they link against the host. */
    private static final class HostLoader extends URLClassLoader {
        HostLoader(List<Path> jars) throws Exception {
            super(urls(jars), ClassLoader.getPlatformClassLoader());
        }

        private static URL[] urls(List<Path> jars) throws Exception {
            List<URL> urls = new ArrayList<>();
            for (Path jar : jars) urls.add(jar.toUri().toURL());
            return urls.toArray(URL[]::new);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith("com.retromod.legacyapi.")) {
                try (InputStream in = LegacyItemParserBridgeTest.class.getClassLoader()
                        .getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) throw new ClassNotFoundException(name);
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            return super.findClass(name);
        }
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_CLASS)).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static void assertCall(MethodInsnNode call, int opcode, String owner, String name, String desc) {
        String where = call.owner + "." + call.name + call.desc;
        assertEquals(opcode, call.getOpcode(), where);
        assertEquals(owner, call.owner, where);
        assertEquals(name, call.name, where);
        assertEquals(desc, call.desc, where);
    }
}
