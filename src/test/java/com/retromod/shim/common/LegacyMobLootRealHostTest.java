/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The drop override Retromod gives an old mob runs on the real 26.3 classes after Bootstrap and hands the host the old table. */
class LegacyMobLootRealHostTest {

    private static final Path PRISM = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher");
    private static final Path CLIENT = PRISM.resolve("libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
    private static final Path META = PRISM.resolve("meta/net.minecraft/26.3.json");
    private static final String KEY_DROP = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z"
            + "Lnet/minecraft/resources/ResourceKey;)V";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("26.3 after Bootstrap: the old table, the type's table through super, and a custom table, which Mob still clears")
    void dropOverrideOnTheReal263Classes(@TempDir Path dir) throws Throwable {
        assumeTrue(Files.isRegularFile(CLIENT) && Files.isRegularFile(META), "no local 26.3 client; it is local test material");
        Map<String, byte[]> mod = translate(dir);
        try (Game game = new Game(mod)) {
            Object crab = game.crab(false);
            game.drop(crab);
            assertEquals("crabmod:entities/crab", game.captured(), "the table the old getDefaultLootTable() names");

            Object baby = game.crab(true);
            game.drop(baby);
            assertEquals("minecraft:entities/zombie", game.captured(), "super.getDefaultLootTable() is the type's own table");

            Object custom = game.crab(false);
            Object key = game.key("spawner", "custom");
            game.setField(custom, "net.minecraft.world.entity.Mob", "lootTable", Optional.of(key));
            game.drop(custom);
            assertEquals("spawner:custom", game.captured(), "a table set on the mob keeps the host's drop");
            assertTrue(((Optional<?>) game.getField(custom, "net.minecraft.world.entity.Mob", "lootTable")).isEmpty(),
                    "Mob clears the set table after its drop, as without Retromod");
        }
    }

    // The old mob from the stand-in test, translated for 26.3 with the real class hierarchy visible.
    private static Map<String, byte[]> translate(Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), LegacyMobLootAdapterTest.World.old()), "mod/");
        Map<String, byte[]> host = new HashMap<>();
        try (ZipFile jar = new ZipFile(CLIENT.toFile())) {
            for (String name : List.of("net/minecraft/world/entity/monster/Monster", "net/minecraft/world/entity/PathfinderMob",
                    "net/minecraft/world/entity/Mob", "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Entity")) {
                host.put(name, jar.getInputStream(jar.getEntry(name + ".class")).readAllBytes());
            }
        }
        String rl = "net/minecraft/resources/ResourceLocation";
        String id = "net/minecraft/resources/Identifier";
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            t.registerClassRedirect(rl, id);
            t.registerConstructorRedirect(rl, "(Ljava/lang/String;Ljava/lang/String;)V", id, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + id + ";");
            LegacyLootTableKeyBridge.registerUnchecked(t, LegacyLootTableKeyBridge.offlineShape("26.3"));
            LegacyMobLootBridge.registerUnchecked(t, LegacyMobLootBridge.offlineShape("26.3"));
        }, old, host);
        Map<String, byte[]> classes = new HashMap<>(mod);
        classes.putAll(RetromodTransformer.getInstance().getSyntheticClasses());
        classes.put("mod/CapturingCrab", capturingCrab());
        return classes;
    }

    // mod/CapturingCrab extends mod/Crab and keeps the table the host's four-argument drop is handed, since no level exists here.
    private static byte[] capturingCrab() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/CapturingCrab", null, "mod/Crab", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "captured", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor drop = cw.visitMethod(Opcodes.ACC_PUBLIC, "dropFromLootTable", KEY_DROP, null, null);
        drop.visitCode();
        drop.visitVarInsn(Opcodes.ALOAD, 4);
        drop.visitFieldInsn(Opcodes.PUTSTATIC, "mod/CapturingCrab", "captured", "Ljava/lang/Object;");
        drop.visitInsn(Opcodes.RETURN);
        drop.visitMaxs(0, 0);
        drop.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The real 26.3 client and its libraries, bootstrapped, with the translated mob defined on top. */
    private static final class Game implements AutoCloseable {
        private final URLClassLoader host;
        private final ClassLoader mod;
        private final Object unsafe;

        Game(Map<String, byte[]> modClasses) throws Exception {
            List<URL> urls = new ArrayList<>(List.of(CLIENT.toUri().toURL()));
            for (Path library : libraries()) urls.add(library.toUri().toURL());
            host = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            mod = new ClassLoader(host) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = modClasses.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            // Bootstrap routes the standard streams into the game's logger, which outlives this loader in the test JVM.
            PrintStream out = System.out;
            PrintStream err = System.err;
            try {
                host.loadClass("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
                host.loadClass("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
        }

        // A mob made without a level, as only its loot members run; Entity's type and Mob's table are what they read.
        Object crab(boolean baby) throws Exception {
            Class<?> type = mod.loadClass("mod.CapturingCrab");
            Object crab = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
            Class<?> types = host.loadClass("net.minecraft.world.entity.EntityTypes");
            setField(crab, "net.minecraft.world.entity.Entity", "type", types.getField("ZOMBIE").get(null));
            setField(crab, "net.minecraft.world.entity.Mob", "lootTable", Optional.empty());
            mod.loadClass("mod.Crab").getField("baby").setBoolean(crab, baby);
            return crab;
        }

        void drop(Object crab) throws Exception {
            mod.loadClass("mod.CapturingCrab").getField("captured").set(null, null);
            Method drop = mod.loadClass("mod.Crab").getDeclaredMethod("dropFromLootTable", host.loadClass("net.minecraft.server.level.ServerLevel"),
                    host.loadClass("net.minecraft.world.damagesource.DamageSource"), boolean.class);
            drop.setAccessible(true);
            drop.invoke(crab, null, null, false);
        }

        String captured() throws Exception {
            Object key = mod.loadClass("mod.CapturingCrab").getField("captured").get(null);
            return String.valueOf(key.getClass().getMethod("identifier").invoke(key));
        }

        Object key(String namespace, String path) throws Exception {
            Class<?> id = host.loadClass("net.minecraft.resources.Identifier");
            Object location = id.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
            Class<?> registries = host.loadClass("net.minecraft.core.registries.Registries");
            Class<?> keyType = host.loadClass("net.minecraft.resources.ResourceKey");
            return keyType.getMethod("create", keyType, id).invoke(null, registries.getField("LOOT_TABLE").get(null), location);
        }

        void setField(Object target, String owner, String name, Object value) throws Exception {
            Field field = host.loadClass(owner).getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        Object getField(Object target, String owner, String name) throws Exception {
            Field field = host.loadClass(owner).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        @Override
        public void close() throws Exception {
            host.close();
        }

        // Every library the 26.3 client lists that is on disk, leaving out the native ones.
        private static List<Path> libraries() throws Exception {
            List<Path> jars = new ArrayList<>();
            JsonElement meta = JsonParser.parseString(Files.readString(META));
            for (JsonElement library : meta.getAsJsonObject().getAsJsonArray("libraries")) {
                String[] parts = library.getAsJsonObject().get("name").getAsString().split(":");
                if (parts.length != 3) continue;
                Path jar = PRISM.resolve("libraries").resolve(parts[0].replace('.', '/')).resolve(parts[1]).resolve(parts[2])
                        .resolve(parts[1] + "-" + parts[2] + ".jar");
                if (Files.isRegularFile(jar)) jars.add(jar);
            }
            return jars;
        }
    }
}
