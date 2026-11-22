/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_26_2_to_26_3;
import com.retromod.shim.forge.Forge_26_2_to_26_3;
import com.retromod.shim.neoforge.NeoForge_26_2_to_26_3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyViewBlockingBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/** A block built for 26.2 or older that sets its view-blocking predicate, as Lootr's do, on 26.3's block properties. */
class LegacyViewBlockingBridgeTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
    /** The 26.3 client and the libraries its bootstrap loads. */
    private static final List<String> HOST = List.of("com/mojang/minecraft/26.3/minecraft-26.3-client.jar",
            "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar", "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
            "com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar", "com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar",
            "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar", "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            "com/mojang/logging/1.7.12/logging-1.7.12.jar", "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar",
            "com/mojang/authlib/10.0.77/authlib-10.0.77.jar", "org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar",
            "io/netty/netty-buffer/4.2.16.Final/netty-buffer-4.2.16.Final.jar", "io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar",
            "io/netty/netty-codec-base/4.2.16.Final/netty-codec-base-4.2.16.Final.jar",
            "io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar",
            "io/netty/netty-handler/4.2.16.Final/netty-handler-4.2.16.Final.jar", "org/joml/joml/1.10.9/joml-1.10.9.jar",
            "com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar", "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
            "com/ibm/icu/icu4j/78.3/icu4j-78.3.jar", "commons-io/commons-io/2.20.0/commons-io-2.20.0.jar");
    private static final String HELPER_CALL = HELPER + ".isViewBlocking(L" + PROPERTIES + ";" + OLD_DESC.substring(1);

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void everyLoaderWrapsTheOldPredicateOn263() {
        for (VersionShim shim : List.of(new Forge_26_2_to_26_3(), new NeoForge_26_2_to_26_3(), new Fabric_26_2_to_26_3())) {
            RetromodTransformer transformer = target("26.3");
            shim.registerRedirects(transformer);
            assertEquals(List.of(HELPER_CALL), propertyCalls(transformer), shim.getShimName());
            assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), shim.getShimName() + " adds the helper to the mod");
        }
    }

    @Test
    void a262HostKeepsTheOldCall() {
        RetromodTransformer transformer = target("26.2");
        LegacyViewBlockingBridge.register(transformer);
        assertEquals(List.of(PROPERTIES + ".isViewBlocking" + OLD_DESC), propertyCalls(transformer));
    }

    @Test
    void theOldPredicateAnswersFor263() throws Exception {
        List<URL> host = new ArrayList<>();
        for (String jar : HOST) {
            Path file = LIBRARIES.resolve(jar);
            assumeTrue(Files.isRegularFile(file), "no local " + file.getFileName() + "; the 26.3 game jars are local test material");
            host.add(file.toUri().toURL());
        }
        RetromodTransformer transformer = target("26.3");
        LegacyViewBlockingBridge.registerRedirects(transformer);
        byte[] helperBytes = transformer.getSyntheticClasses().get(HELPER);
        try (URLClassLoader game = new URLClassLoader(host.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            bootstrap(game);
            Class<?> helper = new DefiningLoader(game, Map.of(HELPER, helperBytes)).loadClass(HELPER.replace('/', '.'));
            Class<?> properties = game.loadClass(PROPERTIES.replace('/', '.'));
            Class<?> oldPredicate = game.loadClass("net.minecraft.world.level.block.state.BlockBehaviour$StatePredicate");

            // The old predicate blocks the view at y 64 only, and records what it was asked.
            List<Object[]> asked = new ArrayList<>();
            Object predicate = Proxy.newProxyInstance(game, new Class<?>[]{oldPredicate}, (proxy, method, args) -> {
                if (!method.getName().equals("test")) return method.invoke(this, args);
                asked.add(args);
                return args[2].getClass().getMethod("getY").invoke(args[2]).equals(64);
            });
            Object props = properties.getMethod("of").invoke(null);
            assertSame(props, helper.getMethod("isViewBlocking", properties, oldPredicate).invoke(null, props, predicate),
                    "the properties are returned for chaining, as before");

            Field field = properties.getDeclaredField("isViewBlocking");
            field.setAccessible(true);
            Object installed = field.get(props);
            Method test = game.loadClass("net.minecraft.world.level.block.state.BlockBehaviour$StateArgumentPredicate").getMethods()[0];
            Object state = blockState(game);
            Object box = game.loadClass("net.minecraft.world.phys.AABB").getConstructor(double.class, double.class, double.class,
                    double.class, double.class, double.class).newInstance(0, 0, 0, 1, 1, 1);
            Class<?> blockPos = game.loadClass("net.minecraft.core.BlockPos");
            Object blocking = blockPos.getConstructor(int.class, int.class, int.class).newInstance(1, 64, 2);
            Object open = blockPos.getConstructor(int.class, int.class, int.class).newInstance(1, 65, 2);
            assertEquals(true, test.invoke(installed, state, null, blocking, box));
            assertEquals(false, test.invoke(installed, state, null, open, box));
            assertSame(state, asked.get(0)[0], "the old predicate sees the state");
            assertSame(blocking, asked.get(0)[2], "and the position");
        }
    }

    private static RetromodTransformer target(String version) {
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        return transformer;
    }

    /** Every call the caller makes on block properties or the helper, after the transform. */
    private static List<String> propertyCalls(RetromodTransformer transformer) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(caller(), "mod/Blocks.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && (call.owner.equals(PROPERTIES) || call.owner.equals(HELPER))) {
                calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    /** {@code properties.isViewBlocking(predicate)} as a mod built for 26.2 or older compiled it. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Blocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "props",
                "(L" + PROPERTIES + ";Lnet/minecraft/world/level/block/state/BlockBehaviour$StatePredicate;)L" + PROPERTIES + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "isViewBlocking", OLD_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object blockState(ClassLoader game) throws Exception {
        Object stone = game.loadClass("net.minecraft.world.level.block.Blocks").getField("STONE").get(null);
        return stone.getClass().getMethod("defaultBlockState").invoke(stone);
    }

    private static void bootstrap(ClassLoader game) throws Exception {
        // Bootstrap routes the standard streams into the game's logger, which this test JVM keeps using.
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            Class.forName("net.minecraft.SharedConstants", true, game).getMethod("tryDetectVersion").invoke(null);
            Class.forName("net.minecraft.server.Bootstrap", true, game).getMethod("bootStrap").invoke(null);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        DefiningLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
