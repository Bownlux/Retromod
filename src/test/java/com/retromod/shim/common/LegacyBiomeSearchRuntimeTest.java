/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.testsupport.fakemc.biomesearch.FakeBiomeResolver;
import com.retromod.testsupport.fakemc.biomesearch.FakeBiomeSource;
import com.retromod.testsupport.fakemc.biomesearch.FakeBlockPos;
import com.retromod.testsupport.fakemc.biomesearch.FakeHolder;
import com.retromod.testsupport.fakemc.biomesearch.FakeNamedBiome;
import com.retromod.testsupport.fakemc.biomesearch.FakePair;
import com.retromod.testsupport.fakemc.biomesearch.FakeRandomSource;
import com.retromod.testsupport.fakemc.biomesearch.FakeSampler;
import com.retromod.testsupport.fakemc.biomesearch.FakeSeededRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt biome search, run on stand-ins and compared with the old search's pick and random use. */
class LegacyBiomeSearchRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String MEMBERS = "com/retromod/legacyapi/minecraft/world/level/levelgen/LegacyWorldgenMembers263";
    private static final Map<Class<?>, String> NAMES = Map.of(
            FakeHolder.class, "net/minecraft/core/Holder",
            FakeBiomeResolver.class, "net/minecraft/world/level/biome/BiomeResolver",
            FakeBiomeSource.class, "net/minecraft/world/level/biome/BiomeSource",
            FakeSampler.class, "net/minecraft/world/level/biome/Climate$Sampler",
            FakeBlockPos.class, "net/minecraft/core/BlockPos",
            FakePair.class, "com/mojang/datafixers/util/Pair",
            FakeRandomSource.class, "net/minecraft/util/RandomSource",
            FakeNamedBiome.class, internal(FakeNamedBiome.class),
            FakeSeededRandom.class, internal(FakeSeededRandom.class));

    @Test
    @DisplayName("the short form picks a random match the way the old search did, and leaves the random in the same place")
    void shortFormMatchesTheOldRandomSearch() throws Exception {
        ClassLoader game = game();
        for (long seed : new long[]{1L, 42L, 2026L}) {
            Object random = game.loadClass(FakeSeededRandom.class.getName()).getConstructor(long.class).newInstance(seed);
            Object pair = search(game, 8, random, 100, 64, -36, 40);
            Random expected = new Random(seed);
            String[] old = oldSearch(100, 64, -36, 40, 1, false, expected);

            assertEquals(old[0], first(pair), "seed " + seed);
            assertEquals(old[1], second(pair), "seed " + seed);
            assertEquals(expected.nextInt(1000), random.getClass().getMethod("nextInt", int.class).invoke(random, 1000),
                    "the search used the random as often as the old one, seed " + seed);
        }
    }

    @Test
    @DisplayName("the long closest form returns the nearest ring's first match without drawing")
    void closestFormReturnsTheNearestMatch() throws Exception {
        ClassLoader game = game();
        Object random = game.loadClass(FakeSeededRandom.class.getName()).getConstructor(long.class).newInstance(5L);
        Object pair = search(game, 10, random, 8, 70, 4, 64, 2, 1);
        Random expected = new Random(5L);

        assertEquals(oldSearch(8, 70, 4, 64, 2, true, expected)[0], first(pair));
        assertEquals(expected.nextInt(1000), random.getClass().getMethod("nextInt", int.class).invoke(random, 1000));
    }

    /** The 26.2 search, transcribed from its bytecode over the same biome layout. */
    private static String[] oldSearch(int x, int y, int z, int radius, int step, boolean closest, Random random) {
        int centerX = x >> 2;
        int centerZ = z >> 2;
        int reach = radius >> 2;
        String[] pair = null;
        int matches = 0;
        for (int ring = closest ? 0 : reach; ring <= reach; ring += step) {
            for (int dz = -ring; dz <= ring; dz += step) {
                boolean edgeRow = Math.abs(dz) == ring;
                for (int dx = -ring; dx <= ring; dx += step) {
                    if (closest && Math.abs(dx) != ring && !edgeRow) continue;
                    int quartX = centerX + dx;
                    int quartZ = centerZ + dz;
                    if (Math.floorMod(quartX + quartZ, 3) != 0) continue;
                    if (pair == null || random.nextInt(matches + 1) == 0) {
                        String[] found = {(quartX << 2) + "," + y + "," + (quartZ << 2), "match"};
                        if (closest) return found;
                        pair = found;
                    }
                    matches++;
                }
            }
        }
        return pair;
    }

    private static Object search(ClassLoader game, int arity, Object random, int... ints) throws Exception {
        Class<?> members = game.loadClass(MEMBERS.replace('/', '.'));
        Method method = Arrays.stream(members.getMethods())
                .filter(m -> m.getName().equals("findBiomeHorizontal") && m.getParameterCount() == arity).findFirst().orElseThrow();
        Object source = game.loadClass("net.minecraft.world.level.biome.BiomeSource").getConstructor().newInstance();
        Object sampler = game.loadClass("net.minecraft.world.level.biome.Climate$Sampler").getConstructor().newInstance();
        Predicate<Object> allowed = biome -> biome.toString().equals("match");
        Object[] args = new Object[arity];
        args[0] = source;
        int next = 0;
        for (int i = 1; i <= (arity == 8 ? 4 : 5); i++) args[i] = ints[next++];
        int at = arity == 8 ? 5 : 6;
        args[at] = allowed;
        args[at + 1] = random;
        if (arity == 10) args[8] = ints[next] != 0;
        args[arity - 1] = sampler;
        return method.invoke(null, args);
    }

    private static String first(Object pair) throws Exception {
        return pair.getClass().getMethod("getFirst").invoke(pair).toString();
    }

    private static String second(Object pair) throws Exception {
        return pair.getClass().getMethod("getSecond").invoke(pair).toString();
    }

    /** The stand-ins under their game names and the compiled search in one loader; other game types are empty. */
    private static ClassLoader game() throws IOException {
        Map<String, String> rename = new HashMap<>();
        NAMES.forEach((fake, name) -> rename.put(internal(fake), name));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : NAMES.entrySet()) {
            classes.put(fake.getValue().replace('/', '.'), remap(bytesOf(fake.getKey()), remapper));
        }
        classes.put(MEMBERS.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(MEMBERS + ".class")));
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                // The verifier may load other types the class names; an empty one is enough for that.
                if (bytes == null && (name.startsWith("net.minecraft.") || name.startsWith("com.mojang."))) {
                    bytes = empty(name.replace('.', '/'));
                }
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static byte[] empty(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(internal(type) + ".class")) {
            return in.readAllBytes();
        }
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(cw, remapper), 0);
        return cw.toByteArray();
    }
}
