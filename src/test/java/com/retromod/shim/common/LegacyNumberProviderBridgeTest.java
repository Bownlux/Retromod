/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_26_2_to_26_3;
import com.retromod.shim.forge.Forge_26_2_to_26_3;
import com.retromod.shim.neoforge.NeoForge_26_2_to_26_3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.retromod.shim.common.LegacyNumberProviderBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Chef's Delight, a 26.2 Fabric mod, on 26.3: its trade set class passed
 * {@code ConstantValue.exactly(2)} to a {@code NumberProvider} parameter. 26.3 removed both, so
 * verifying the class failed with {@code NoClassDefFoundError}, and the mod's entry point stopped
 * before it registered its Cook profession and village houses.
 *
 * <p>The tests run the transformed code against small stand-ins for the 26.3 classes, compiled at
 * test time in an isolated class loader, so they check which 26.3 provider each consumer receives.
 */
class LegacyNumberProviderBridgeTest {

    private static final String MOD_CLASS = "test/mod/ModLoot";
    private static final String HOLDER_SET = "net/minecraft/core/HolderSet";
    private static final String OPTIONAL = "java/util/Optional";

    /** The Mojang names a 26.2 mod was compiled with. */
    private static final Names MOJANG = new Names(NUMBER_PROVIDER, CONSTANT_VALUE, "exactly",
            UNIFORM_GENERATOR, "between", BINOMIAL, "binomial",
            "net/minecraft/world/level/storage/loot/LootPool", "lootPool", POOL_BUILDER, "setRolls",
            "setBonusRolls");

    /** The same calls in a 1.20.1 Fabric mod, read from Yarn 1.20.1+build.10. */
    private static final Names INTERMEDIARY = new Names("net/minecraft/class_5658",
            "net/minecraft/class_44", "method_32448", "net/minecraft/class_5662", "method_32462",
            "net/minecraft/class_40", "method_273", "net/minecraft/class_55", "method_347",
            "net/minecraft/class_55$class_56", "method_352", "method_35509");

    private RetromodTransformer transformer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a trade set built from ConstantValue.exactly gets an int constant holder on 26.3")
    void tradeSetAmountBecomesAnIntProvider() throws Exception {
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassLoader host = hostWith(transformer.transformClass(modLoot(MOJANG), MOD_CLASS));

        Object tradeSet = call(host, "tradeSet", emptyHolderSet(host));

        assertEquals("ints.ConstantValue(2)", describe(component(tradeSet, "amount")),
                "the old float 2.0 must reach the 26.3 trade set as the int constant 2");
        assertEquals(false, component(tradeSet, "allowDuplicates"));
    }

    @Test
    @DisplayName("loot pool rolls become an int provider and bonus rolls a float provider")
    void poolRollsPickTheirFamily() throws Exception {
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassLoader host = hostWith(transformer.transformClass(modLoot(MOJANG), MOD_CLASS));

        Object pool = call(host, "pool");

        assertEquals("ints.ConstantValue(3)", describe(field(pool, "rolls")),
                "2.6 rolls round the way the old NumberProvider.getInt did");
        assertEquals("floats.UniformGenerator(floats.ConstantValue(0.0), floats.ConstantValue(1.0))",
                describe(field(pool, "bonusRolls")));
    }

    @Test
    @DisplayName("set count takes uniform ranges, constructed ones too, as int providers")
    void setCountTakesIntRanges() throws Exception {
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassLoader host = hostWith(transformer.transformClass(modLoot(MOJANG), MOD_CLASS));

        Object counted = call(host, "count");
        assertEquals("ints.UniformGenerator(ints.ConstantValue(1), ints.ConstantValue(3))",
                describe(field(counted, "count")));
        assertEquals(true, field(counted, "add"));

        Object constructed = call(host, "constructedCount");
        assertEquals("ints.UniformGenerator(ints.ConstantValue(1), ints.ConstantValue(4))",
                describe(field(constructed, "count")));
    }

    @Test
    @DisplayName("binomial(n, p) builds 26.3's int binomial, which a consumer takes as it is")
    void binomialPassesThrough() throws Exception {
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassLoader host = hostWith(transformer.transformClass(modLoot(MOJANG), MOD_CLASS));

        Object pool = call(host, "binomialPool");

        assertEquals("ints.BinomialDistributionGenerator(ints.ConstantValue(3), floats.ConstantValue(0.5))",
                describe(field(pool, "rolls")));
    }

    @Test
    @DisplayName("a 1.20.1 Fabric mod's loot pool reaches the same providers through intermediary")
    void intermediaryModReachesTheBridge() throws Exception {
        IntermediaryToMojangMapper.applyTo(transformer);
        Mc26_2To26_3CoreMoves.register(transformer);
        ClassLoader host = hostWith(transformer.transformClass(modLoot(INTERMEDIARY), MOD_CLASS));

        Object pool = call(host, "pool");

        assertEquals("ints.ConstantValue(3)", describe(field(pool, "rolls")));
        assertEquals("floats.UniformGenerator(floats.ConstantValue(0.0), floats.ConstantValue(1.0))",
                describe(field(pool, "bonusRolls")));
    }

    @Test
    @DisplayName("no removed loot type survives, and the stand-ins embed into the mod's own package")
    void removedTypesAreGoneAndStandInsEmbed() throws Exception {
        Mc26_2To26_3CoreMoves.register(transformer);
        byte[] transformed = transformer.transformClass(modLoot(MOJANG), MOD_CLASS);

        Set<String> referenced = referencedTypes(transformed);
        for (String removed : List.of(NUMBER_PROVIDER, CONSTANT_VALUE, UNIFORM_GENERATOR, BINOMIAL)) {
            assertFalse(referenced.contains(removed), removed + " is absent on 26.3, so a reference fails to link");
        }

        Path jar = tempDir.resolve("mod.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(MOD_CLASS + ".class"));
            out.write(transformed);
            out.closeEntry();
        }
        assertEquals(3, SyntheticEmbedder.embedIntoJar(jar, "chefsdelight", transformer),
                "the interface, the value, and the helper are all needed at run time");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Set<String> embedded = referencedTypes(zip.getInputStream(zip.getEntry(MOD_CLASS + ".class")).readAllBytes());
            assertTrue(embedded.stream().noneMatch(type -> type.startsWith("com/retromod/shim/")),
                    "the mod must reach its own embedded copies, not Retromod's: " + embedded);
            assertTrue(embedded.stream().anyMatch(type -> type.startsWith(SyntheticEmbedder.PREFIX)
                    && type.endsWith("/LegacyNumberProviders")), "the helper is relocated: " + embedded);
        }
    }

    @Test
    @DisplayName("only a 26.3 host gets the bridge, on Fabric, Forge, and NeoForge alike")
    void gatedTo26_3OnEveryLoader() {
        for (VersionShim shim : List.<VersionShim>of(new Fabric_26_2_to_26_3(), new Forge_26_2_to_26_3(),
                new NeoForge_26_2_to_26_3())) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "26.2"),
                    shim.getShimName() + ": 26.2 still has NumberProvider, so its mods must keep it");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "26.3"), shim.getShimName());

            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            assertEquals(LEGACY, transformer.getClassRedirects().get(NUMBER_PROVIDER), shim.getShimName());
        }
    }

    /** The old Minecraft names a mod class was compiled against. */
    private record Names(String provider, String constant, String exactly, String uniform, String between,
            String binomial, String binomialFactory, String pool, String lootPool, String builder,
            String setRolls, String setBonusRolls) {
        String providerDesc() { return "L" + provider + ";"; }
        String builderDesc() { return "L" + builder + ";"; }
    }

    /**
     * A mod class written against the old loot number API:
     * <pre>
     * static Object tradeSet(HolderSet trades) { return tradeSet(trades, ConstantValue.exactly(2F)); }
     * static Object tradeSet(HolderSet trades, NumberProvider amount) {
     *     return new TradeSet(trades, amount, false, Optional.empty()); }
     * static Object pool() { return LootPool.lootPool().setRolls(ConstantValue.exactly(2.6F))
     *     .setBonusRolls(UniformGenerator.between(0F, 1F)); }
     * static Object binomialPool() {
     *     return LootPool.lootPool().setRolls(BinomialDistributionGenerator.binomial(3, 0.5F)); }
     * static Object count() { return SetItemCountFunction.setCount(UniformGenerator.between(1F, 3F), true); }
     * static Object constructedCount() { return SetItemCountFunction.setCount(
     *     new UniformGenerator(new ConstantValue(1F), ConstantValue.exactly(4F))); }
     * </pre>
     * Only the loot pool calls exist before 26.1, so the intermediary form carries those alone.
     */
    private static byte[] modLoot(Names n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null, "java/lang/Object", null);

        MethodVisitor mv = begin(cw, "pool");
        lootPool(mv, n);
        exactly(mv, n, 2.6F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, n.builder(), n.setRolls(),
                "(" + n.providerDesc() + ")" + n.builderDesc(), false);
        between(mv, n, 0F, 1F);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, n.builder(), n.setBonusRolls(),
                "(" + n.providerDesc() + ")" + n.builderDesc(), false);
        end(mv);

        mv = begin(cw, "binomialPool");
        lootPool(mv, n);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitLdcInsn(0.5F);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.binomial(), n.binomialFactory(), "(IF)L" + n.binomial() + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, n.builder(), n.setRolls(),
                "(" + n.providerDesc() + ")" + n.builderDesc(), false);
        end(mv);

        if (n == MOJANG) addTradeSetAndCount(cw, n);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addTradeSetAndCount(ClassWriter cw, Names n) {
        String tradeSet = TRADE_SET;
        String withAmount = "(L" + HOLDER_SET + ";" + n.providerDesc() + ")Ljava/lang/Object;";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tradeSet",
                "(L" + HOLDER_SET + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        exactly(mv, n, 2F);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MOD_CLASS, "tradeSet", withAmount, false);
        end(mv);

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tradeSet", withAmount, null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, tradeSet);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPTIONAL, "empty", "()L" + OPTIONAL + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, tradeSet, "<init>",
                "(L" + HOLDER_SET + ";" + n.providerDesc() + "ZL" + OPTIONAL + ";)V", false);
        end(mv);

        String setCountReturn = "Lnet/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction$Builder;";
        mv = begin(cw, "count");
        between(mv, n, 1F, 3F);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SET_COUNT, "setCount",
                "(" + n.providerDesc() + "Z)" + setCountReturn, false);
        end(mv);

        mv = begin(cw, "constructedCount");
        mv.visitTypeInsn(Opcodes.NEW, n.uniform());
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.NEW, n.constant());
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, n.constant(), "<init>", "(F)V", false);
        exactly(mv, n, 4F);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, n.uniform(), "<init>",
                "(" + n.providerDesc() + n.providerDesc() + ")V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SET_COUNT, "setCount",
                "(" + n.providerDesc() + ")" + setCountReturn, false);
        end(mv);
    }

    private static MethodVisitor begin(ClassWriter cw, String name) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void lootPool(MethodVisitor mv, Names n) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.pool(), n.lootPool(), "()" + n.builderDesc(), false);
    }

    private static void exactly(MethodVisitor mv, Names n, float value) {
        mv.visitLdcInsn(value);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.constant(), n.exactly(), "(F)L" + n.constant() + ";", false);
    }

    private static void between(MethodVisitor mv, Names n, float min, float max) {
        mv.visitLdcInsn(min);
        mv.visitLdcInsn(max);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, n.uniform(), n.between(), "(FF)L" + n.uniform() + ";", false);
    }

    private static Object call(ClassLoader host, String method, Object... args) throws Exception {
        Class<?> mod = host.loadClass(MOD_CLASS.replace('/', '.'));
        for (Method candidate : mod.getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                return candidate.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static Object emptyHolderSet(ClassLoader host) throws Exception {
        return host.loadClass("net.minecraft.core.HolderSet").getMethod("empty").invoke(null);
    }

    /**
     * Renders a provider tree as {@code family.Type(components)}, looking through holders, so
     * an int constant and a float constant read differently.
     */
    private static String describe(Object value) throws Exception {
        if (value == null || !value.getClass().isRecord()) return String.valueOf(value);
        Class<?> type = value.getClass();
        if (type.getName().equals("net.minecraft.core.Holder$Direct")) return describe(component(value, "value"));
        String pkg = type.getPackageName();
        StringJoiner parts = new StringJoiner(", ",
                pkg.substring(pkg.lastIndexOf('.') + 1) + "." + type.getSimpleName() + "(", ")");
        for (RecordComponent part : type.getRecordComponents()) parts.add(describe(part.getAccessor().invoke(value)));
        return parts.toString();
    }

    private static Object component(Object record, String name) throws Exception {
        for (RecordComponent part : record.getClass().getRecordComponents()) {
            if (part.getName().equals(name)) return part.getAccessor().invoke(record);
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(Object owner, String name) throws Exception {
        return owner.getClass().getField(name).get(owner);
    }

    /** Every type the class names, in its descriptors and its method bodies. */
    private static Set<String> referencedTypes(byte[] bytes) {
        Set<String> types = new HashSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                types.add(internalName);
                return internalName;
            }
        }), 0);
        return types;
    }

    /** The transformed mod class, Retromod's stand-ins, and compiled 26.3 stand-ins, isolated. */
    private ClassLoader hostWith(byte[] modClass) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(compile263StandIns());
        classes.put(MOD_CLASS.replace('/', '.'), modClass);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** The 26.3 shapes the bridge calls, checked against the 26.3 client jar with javap. */
    private Map<String, byte[]> compile263StandIns() throws Exception {
        String number = "net.minecraft.world.level.storage.loot.providers.number";
        String importHolder = "import net.minecraft.core.Holder;\n";
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/core/Holder.java", """
                package net.minecraft.core;
                public interface Holder<T> {
                    T value();
                    static <T> Holder<T> direct(T value) { return new Direct<>(value); }
                    record Direct<T>(T value) implements Holder<T> {}
                }""");
        sources.put("net/minecraft/core/HolderSet.java", """
                package net.minecraft.core;
                public interface HolderSet<T> {
                    static <T> HolderSet<T> empty() { return new Empty<>(); }
                    record Empty<T>() implements HolderSet<T> {}
                }""");
        String ints = "package " + number + ".ints;\n" + importHolder
                + "import " + number + ".floats.ContextFloatProvider;\n";
        String floats = "package " + number + ".floats;\n" + importHolder
                + "import " + number + ".ints.ContextIntProvider;\n";
        String intsDir = number.replace('.', '/') + "/ints/";
        String floatsDir = number.replace('.', '/') + "/floats/";
        sources.put(intsDir + "ContextIntProvider.java", ints + "public interface ContextIntProvider {}");
        sources.put(intsDir + "ConstantValue.java", ints
                + "public record ConstantValue(int value) implements ContextIntProvider {}");
        sources.put(intsDir + "UniformGenerator.java", ints + "public record UniformGenerator("
                + "Holder<ContextIntProvider> min, Holder<ContextIntProvider> max) implements ContextIntProvider {}");
        sources.put(intsDir + "FromFloat.java", ints
                + "public record FromFloat(Holder<ContextFloatProvider> input) implements ContextIntProvider {}");
        sources.put(intsDir + "BinomialDistributionGenerator.java", ints + "public record BinomialDistributionGenerator("
                + "Holder<ContextIntProvider> n, Holder<ContextFloatProvider> p) implements ContextIntProvider {}");
        sources.put(floatsDir + "ContextFloatProvider.java", floats + "public interface ContextFloatProvider {}");
        sources.put(floatsDir + "ConstantValue.java", floats
                + "public record ConstantValue(float value) implements ContextFloatProvider {}");
        sources.put(floatsDir + "UniformGenerator.java", floats + "public record UniformGenerator("
                + "Holder<ContextFloatProvider> min, Holder<ContextFloatProvider> max) implements ContextFloatProvider {}");
        sources.put(floatsDir + "FromInt.java", floats
                + "public record FromInt(Holder<ContextIntProvider> input) implements ContextFloatProvider {}");
        sources.put("net/minecraft/world/item/trading/TradeSet.java", """
                package net.minecraft.world.item.trading;
                import java.util.Optional;
                import net.minecraft.core.Holder;
                import net.minecraft.core.HolderSet;
                public record TradeSet(HolderSet<?> trades, Holder<?> amount, boolean allowDuplicates,
                        Optional<?> randomSequence) {}""");
        sources.put("net/minecraft/world/level/storage/loot/LootPool.java", """
                package net.minecraft.world.level.storage.loot;
                import net.minecraft.core.Holder;
                public class LootPool {
                    public static Builder lootPool() { return new Builder(); }
                    public static class Builder {
                        public Holder<?> rolls;
                        public Holder<?> bonusRolls;
                        public Builder setRolls(Holder<?> rolls) { this.rolls = rolls; return this; }
                        public Builder setBonusRolls(Holder<?> bonusRolls) { this.bonusRolls = bonusRolls; return this; }
                    }
                }""");
        sources.put("net/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction.java", """
                package net.minecraft.world.level.storage.loot.functions;
                public class LootItemConditionalFunction {
                    public static class Builder<T> {
                        public final Object count;
                        public final boolean add;
                        Builder(Object count, boolean add) { this.count = count; this.add = add; }
                    }
                }""");
        sources.put("net/minecraft/world/level/storage/loot/functions/SetItemCountFunction.java", """
                package net.minecraft.world.level.storage.loot.functions;
                import net.minecraft.core.Holder;
                public class SetItemCountFunction {
                    public static LootItemConditionalFunction.Builder<?> setCount(Holder<?> count) {
                        return setCount(count, false);
                    }
                    public static LootItemConditionalFunction.Builder<?> setCount(Holder<?> count, boolean add) {
                        return new LootItemConditionalFunction.Builder<>(count, add);
                    }
                }""");

        Path src = tempDir.resolve("src");
        Path out = tempDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the 26.3 stand-ins must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
