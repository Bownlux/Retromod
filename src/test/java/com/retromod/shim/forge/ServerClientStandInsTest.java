/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A dedicated server lacks Minecraft's client classes; a mod's shared class that names them still has to load. */
class ServerClientStandInsTest {

    private static final String PLAYER = "host/Player";
    private static final String CLIENT_PLAYER = "client/AbstractClientPlayer";
    private static final String LOCAL_PLAYER = "client/LocalPlayer";
    private static final String MINECRAFT = "client/Minecraft";
    private static final String NET = "mod/Net";
    private static final String KEY = "net-mod.jar";

    @TempDir
    Path mod;

    /** The client hierarchy the table records: LocalPlayer extends AbstractClientPlayer extends the shared Player. */
    private static final Map<String, ServerClientStandIns.Row> TABLE = Map.of(
            CLIENT_PLAYER, new ServerClientStandIns.Row("abstract", PLAYER, List.of()),
            LOCAL_PLAYER, new ServerClientStandIns.Row("class", CLIENT_PLAYER, List.of()),
            MINECRAFT, new ServerClientStandIns.Row("class", "java/lang/Object", List.of()));

    /** The server: it has Player and none of the client classes. */
    private static final Map<String, byte[]> SERVER = Map.of(PLAYER, abstractClass(PLAYER));

    @Test
    @DisplayName("a shared class that returns a client player as a Player fails verification on a server")
    void withoutStandInsTheSharedClassFailsToLoad() throws Exception {
        write(NET, networkClass());

        Throwable failure = assertThrows(NoClassDefFoundError.class, () -> load(NET, serverLoader()));
        assertTrue(failure.getMessage().contains("client/LocalPlayer"), failure.toString());
    }

    @Test
    @DisplayName("with stand-ins the class verifies, lists its methods, and runs its server path")
    void standInsLetTheSharedClassLoad() throws Exception {
        write(NET, networkClass());

        int written = ServerClientStandIns.embed(mod, KEY, TABLE, SERVER::containsKey);

        assertEquals(3, written, "LocalPlayer, its client superclass, and Minecraft");
        Class<?> net = load(NET, serverLoader());
        assertEquals(6, net.getDeclaredMethods().length, "NeoForge's event bus lists every method this way");
        assertNull(net.getMethod("clientPlayer", Object.class).invoke(null, (Object) null));
        assertEquals(false, net.getMethod("isLocal", Object.class).invoke(null, new Object()),
                "nothing on a server is a LocalPlayer");
    }

    @Test
    @DisplayName("running client code still fails with NoClassDefFoundError, as it did without stand-ins")
    void clientCodeStillFailsTheWayItDid() throws Exception {
        write(NET, networkClass());
        ServerClientStandIns.embed(mod, KEY, TABLE, SERVER::containsKey);
        Class<?> net = load(NET, serverLoader());

        assertClientMissing(net.getMethod("instance"), MINECRAFT);
        // Creating a LocalPlayer initializes its client superclass first, which fails.
        assertClientMissing(net.getMethod("newPlayer"), CLIENT_PLAYER);
        assertClientMissing(net.getMethod("probe"), LOCAL_PLAYER);
    }

    @Test
    @DisplayName("each stand-in keeps the client hierarchy, so a LocalPlayer is still a Player")
    void standInsKeepTheClientHierarchy() throws Exception {
        write(NET, networkClass());

        ServerClientStandIns.embed(mod, KEY, TABLE, SERVER::containsKey);

        ClassLoader server = serverLoader();
        Class<?> localPlayer = Class.forName(standInName(LOCAL_PLAYER).replace('/', '.'), false, server);
        assertTrue(load(PLAYER, server).isAssignableFrom(localPlayer));
        assertEquals(0, localPlayer.getMethods().length - Object.class.getMethods().length);
    }

    @Test
    @DisplayName("static calls and class literals keep the client name, other references use the stand-in")
    void rewritesOnlyWhatTheVerifierChecks() throws Exception {
        write(NET, networkClass());

        ServerClientStandIns.embed(mod, KEY, TABLE, SERVER::containsKey);

        ClassNode net = node(NET);
        MethodInsnNode call = (MethodInsnNode) first(method(net, "instance"), INVOKESTATIC);
        assertEquals(MINECRAFT, call.owner);
        assertEquals("()L" + standInName(MINECRAFT) + ";", call.desc);
        LdcInsnNode literal = (LdcInsnNode) first(method(net, "probe"), LDC);
        assertEquals(Type.getObjectType(LOCAL_PLAYER), literal.cst);
        assertTrue(method(net, "onClientTick").desc.contains(standInName(LOCAL_PLAYER)));
    }

    @Test
    @DisplayName("mixins and classes the server has keep their names")
    void leavesMixinsAndServerClassesAlone() throws Exception {
        byte[] mixin = mixinClass();
        write("mod/mixin/LocalPlayerMixin", mixin);
        write(NET, networkClass());

        ServerClientStandIns.embed(mod, KEY, TABLE, SERVER::containsKey);

        assertArrayEquals(mixin, Files.readAllBytes(mod.resolve("mod/mixin/LocalPlayerMixin.class")));
        assertEquals("(Ljava/lang/Object;)L" + PLAYER + ";", method(node(NET), "clientPlayer").desc);
    }

    @Test
    @DisplayName("a client class the host has is never replaced")
    void presentClientClassIsNotStoodIn() throws Exception {
        write(NET, networkClass());

        int written = ServerClientStandIns.embed(mod, KEY, TABLE, name -> true);

        assertEquals(0, written);
        assertFalse(Files.exists(mod.resolve("com")));
    }

    @Test
    @DisplayName("a host uses the table of its release line, or the newest table when its line is newer")
    void tableFollowsTheReleaseLine() {
        assertEquals("26.3", ServerClientStandIns.tableVersion("26.3"));
        assertEquals("26.3", ServerClientStandIns.tableVersion("26.3.1"));
        assertEquals("26.1.2", ServerClientStandIns.tableVersion("26.1"));
        assertEquals("1.21.11", ServerClientStandIns.tableVersion("1.21.11"));
        assertNull(ServerClientStandIns.tableVersion("1.21.10"));
        assertNull(ServerClientStandIns.tableVersion("1.21.1"));
        assertEquals("26.3", ServerClientStandIns.tableVersion("26.4"), "a newer line uses the newest table");
        assertEquals("26.3", ServerClientStandIns.tableVersion("27.1"));
    }

    @Test
    @DisplayName("the bundled 26.3 table records LocalPlayer's client hierarchy")
    void bundledTableHasTheClientHierarchy() {
        Map<String, ServerClientStandIns.Row> table = ServerClientStandIns.tableFor("26.3");

        assertEquals("net/minecraft/client/player/AbstractClientPlayer",
                table.get("net/minecraft/client/player/LocalPlayer").superName());
        assertFalse(table.containsKey("net/minecraft/world/entity/player/Player"), "the server has Player");
        assertTrue(table.get("net/minecraft/client/gui/components/Renderable").isInterface());
    }

    private static void assertClientMissing(Method method, String clientClass) {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> method.invoke(null));
        assertInstanceOf(NoClassDefFoundError.class, failure.getCause(), failure.getCause().toString());
        assertTrue(failure.getCause().getMessage().contains(clientClass), failure.getCause().getMessage());
    }

    /** A shared class: a client player returned as a Player, a client listener, and three client-only paths. */
    private static byte[] networkClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, NET, null, "java/lang/Object", null);

        // static Player clientPlayer(Object o) { return (LocalPlayer) o; }
        MethodVisitor pick = method(cw, "clientPlayer", "(Ljava/lang/Object;)L" + PLAYER + ";");
        pick.visitVarInsn(ALOAD, 0);
        pick.visitTypeInsn(CHECKCAST, LOCAL_PLAYER);
        end(pick, ARETURN);

        // static boolean isLocal(Object o) { return o instanceof LocalPlayer; }
        MethodVisitor isLocal = method(cw, "isLocal", "(Ljava/lang/Object;)Z");
        isLocal.visitVarInsn(ALOAD, 0);
        isLocal.visitTypeInsn(INSTANCEOF, LOCAL_PLAYER);
        end(isLocal, IRETURN);

        // static void onClientTick(LocalPlayer player) {}
        end(method(cw, "onClientTick", "(L" + LOCAL_PLAYER + ";)V"), RETURN);

        // static Object instance() { return Minecraft.getInstance(); }
        MethodVisitor instance = method(cw, "instance", "()Ljava/lang/Object;");
        instance.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        end(instance, ARETURN);

        // static Player newPlayer() { return new LocalPlayer(); }
        MethodVisitor create = method(cw, "newPlayer", "()L" + PLAYER + ";");
        create.visitTypeInsn(NEW, LOCAL_PLAYER);
        create.visitInsn(DUP);
        create.visitMethodInsn(INVOKESPECIAL, LOCAL_PLAYER, "<init>", "()V", false);
        end(create, ARETURN);

        // static Object probe() { return LocalPlayer.class; }
        MethodVisitor probe = method(cw, "probe", "()Ljava/lang/Object;");
        probe.visitLdcInsn(Type.getObjectType(LOCAL_PLAYER));
        end(probe, ARETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor method(ClassWriter cw, String name, String descriptor) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, "mod/mixin/LocalPlayerMixin", null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        cw.visitField(ACC_PRIVATE, "player", "L" + LOCAL_PLAYER + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] abstractClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String standInName(String clientClass) {
        return com.retromod.core.SyntheticEmbedder.embeddedBase(KEY) + ServerClientStandIns.FOLDER + clientClass;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static AbstractInsnNode first(MethodNode method, int opcode) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) return insn;
        }
        throw new AssertionError("no instruction " + opcode + " in " + method.name);
    }

    private void write(String name, byte[] bytes) throws Exception {
        Path file = mod.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private ClassNode node(String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(mod.resolve(name + ".class"))).accept(node, 0);
        return node;
    }

    private static Class<?> load(String internalName, ClassLoader loader) throws Exception {
        return Class.forName(internalName.replace('/', '.'), true, loader);
    }

    /** Loads classes the way a server would: the server's classes and the mod's folder, nothing from the client. */
    private ClassLoader serverLoader() {
        Map<String, byte[]> server = new HashMap<>(SERVER);
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
                String internal = binaryName.replace('.', '/');
                try {
                    byte[] bytes = server.get(internal);
                    Path file = mod.resolve(internal + ".class");
                    if (bytes == null && Files.exists(file)) bytes = Files.readAllBytes(file);
                    if (bytes == null) throw new ClassNotFoundException(binaryName);
                    return defineClass(binaryName, bytes, 0, bytes.length);
                } catch (java.io.IOException e) {
                    throw new ClassNotFoundException(binaryName, e);
                }
            }
        };
    }
}
