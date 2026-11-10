/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.fabric.Fabric_1_21_4_to_1_21_5;
import com.retromod.shim.fabric.Fabric_1_21_5_to_1_21_6;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_4_to_1_21_5;
import com.retromod.shim.forge.Forge_1_21_5_to_1_21_6;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5;
import com.retromod.shim.neoforge.NeoForge_1_21_5_to_1_21_6;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old item stack and container saves reach Retromod's rebuilt members from 1.21.5 and 1.21.6, for every loader. */
class LegacyItemNbtApiTest {

    private static final String STACK = LegacyItemNbtApi.ITEM_STACK;
    private static final String HELPER = LegacyItemNbtApi.CONTAINER_HELPER;
    private static final String CHEST = "test.mod.Chest";
    private static final String INT_STACK_MEMBERS = RebuiltApiClasses.ROOT + "intermediary/minecraft/world/item/ItemStackMembers";
    private static final String INT_CONTAINER_MEMBERS = RebuiltApiClasses.ROOT + "intermediary/minecraft/world/ContainerHelperMembers";

    /** One old call a mod makes: owner, name, descriptor, and whether it has a receiver. */
    private record Call(String owner, String name, String desc, boolean instance) {}

    private static final List<Call> PARSE_OPTIONAL_CALLS = List.of(
            new Call(STACK, "parseOptional", LegacyItemNbtApi.PARSE_OPTIONAL, false),
            new Call(STACK, "saveOptional", LegacyItemNbtApi.SAVE_OPTIONAL, true));
    private static final List<Call> PARSE_CALLS = List.of(
            new Call(STACK, "parse", LegacyItemNbtApi.PARSE, false),
            new Call(STACK, "save", LegacyItemNbtApi.SAVE, true),
            new Call(STACK, "save", LegacyItemNbtApi.SAVE_INTO, true),
            new Call(HELPER, "loadAllItems", LegacyItemNbtApi.LOAD_ALL, false),
            new Call(HELPER, "saveAllItems", LegacyItemNbtApi.SAVE_ALL, false),
            new Call(HELPER, "saveAllItems", LegacyItemNbtApi.SAVE_ALL_FLAG, false));

    // The same calls as a Fabric 1.21.4 mod carries them, from the 1.21.1 and 1.21.4 intermediary tables.
    private static final String I_STACK = "Lnet/minecraft/class_1799;";
    private static final String I_PROVIDER = "Lnet/minecraft/class_7225$class_7874;";
    private static final String I_TAG = "Lnet/minecraft/class_2520;";
    private static final String I_COMPOUND = "Lnet/minecraft/class_2487;";
    private static final String I_LIST = "Lnet/minecraft/class_2371;";
    private static final List<Call> INTERMEDIARY_PARSE_OPTIONAL_CALLS = List.of(
            new Call("net/minecraft/class_1799", "method_57359", "(" + I_PROVIDER + I_COMPOUND + ")" + I_STACK, false),
            new Call("net/minecraft/class_1799", "method_57375", "(" + I_PROVIDER + ")" + I_TAG, true));
    private static final List<Call> INTERMEDIARY_PARSE_CALLS = List.of(
            new Call("net/minecraft/class_1799", "method_57360", "(" + I_PROVIDER + I_TAG + ")Ljava/util/Optional;", false),
            new Call("net/minecraft/class_1799", "method_57358", "(" + I_PROVIDER + ")" + I_TAG, true),
            new Call("net/minecraft/class_1799", "method_57376", "(" + I_PROVIDER + I_TAG + ")" + I_TAG, true),
            new Call("net/minecraft/class_1262", "method_5429", "(" + I_COMPOUND + I_LIST + I_PROVIDER + ")V", false),
            new Call("net/minecraft/class_1262", "method_5426", "(" + I_COMPOUND + I_LIST + I_PROVIDER + ")" + I_COMPOUND, false),
            new Call("net/minecraft/class_1262", "method_5427", "(" + I_COMPOUND + I_LIST + "Z" + I_PROVIDER + ")" + I_COMPOUND, false));

    private RetromodTransformer transformer;
    private String previousTarget;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("a 1.21.1 chest saves and loads its items on a 1.21.6 host, with the 1.21.1 defaults")
    void oldChestRoundTripsOn1216() throws Exception {
        LegacyItemNbtApi.register1215(transformer);
        LegacyItemNbtApi.register1216(transformer);

        // Slots are bytes beside each item; an empty tag reads as the empty stack, an empty stack
        // saves as an empty compound, and an empty list is left out only when asked.
        assertEquals("{Items=[{Slot=1, id=diamond, count=5}, {Slot=2, id=sword, count=1}]}"
                        + " [air x0, diamond x5, sword x1] diamond x5 {Slot=3, id=diamond, count=5}"
                        + " true true {} {} {Items=[]} invalid:true", roundTrip(true));
    }

    @Test
    @DisplayName("without the bridge the same chest fails on 1.21.6")
    void withoutTheBridgeTheChestFails() {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> roundTrip(false));
        assertInstanceOf(NoSuchMethodError.class, failure.getCause());
    }

    @Test
    @DisplayName("each loader's 1.21.5 shim moves parseOptional and saveOptional and keeps parse and save, which 1.21.5 still has")
    void shims1215MoveOnlyWhat1215Removed() {
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5())) {
            List<String> out = transformWith(shim, "1.21.5", PARSE_OPTIONAL_CALLS);
            assertEquals(List.of(
                    "184 " + LegacyItemNbtApi.STACK_MEMBERS + ".parseOptional" + LegacyItemNbtApi.PARSE_OPTIONAL,
                    "184 " + LegacyItemNbtApi.STACK_MEMBERS + ".saveOptional(L" + STACK + ";" + LegacyItemNbtApi.SAVE_OPTIONAL.substring(1)),
                    out, shim.getShimName());
            assertEquals(asWritten(PARSE_CALLS), transformWith(shim, "1.21.5", PARSE_CALLS), shim.getShimName());
        }
    }

    @Test
    @DisplayName("each loader's 1.21.6 and 26.1 shim moves parse, save, and the ContainerHelper tag forms")
    void shims1216And261MoveTheRest() {
        List<VersionShim> shims = List.of(new Forge_1_21_5_to_1_21_6(), new NeoForge_1_21_5_to_1_21_6(),
                new Forge_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Fabric_1_21_11_to_26_1());
        for (VersionShim shim : shims) {
            List<String> out = transformWith(shim, shim.getTargetVersion(), PARSE_CALLS);
            assertEquals(List.of(
                    "184 " + LegacyItemNbtApi.STACK_MEMBERS + ".parse" + LegacyItemNbtApi.PARSE,
                    "184 " + LegacyItemNbtApi.STACK_MEMBERS + ".save(L" + STACK + ";" + LegacyItemNbtApi.SAVE.substring(1),
                    "184 " + LegacyItemNbtApi.STACK_MEMBERS + ".save(L" + STACK + ";" + LegacyItemNbtApi.SAVE_INTO.substring(1),
                    "184 " + LegacyItemNbtApi.CONTAINER_MEMBERS + ".loadAllItems" + LegacyItemNbtApi.LOAD_ALL,
                    "184 " + LegacyItemNbtApi.CONTAINER_MEMBERS + ".saveAllItems" + LegacyItemNbtApi.SAVE_ALL,
                    "184 " + LegacyItemNbtApi.CONTAINER_MEMBERS + ".saveAllItems" + LegacyItemNbtApi.SAVE_ALL_FLAG),
                    out, shim.getShimName());
            // Forge-family and offline output embed both rebuilt classes.
            assertTrue(transformer.getSyntheticClasses().containsKey(LegacyItemNbtApi.STACK_MEMBERS), shim.getShimName());
            assertTrue(transformer.getSyntheticClasses().containsKey(LegacyItemNbtApi.CONTAINER_MEMBERS), shim.getShimName());
        }
        // The 26.1 shims carry the 1.21.5 removals too, for a Fabric mod that reaches them after the remap.
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1())) {
            List<String> out = transformWith(shim, "26.1", PARSE_OPTIONAL_CALLS);
            assertTrue(out.stream().allMatch(call -> call.contains(LegacyItemNbtApi.STACK_MEMBERS)), out.toString());
        }
    }

    @Test
    @DisplayName("the shims register only on hosts that lost the members")
    void hostGate() {
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5(),
                new Fabric_1_21_4_to_1_21_5())) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.21.4"), shim.getShimName() + " on 1.21.4");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "1.21.5"), shim.getShimName() + " on 1.21.5");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "26.3"), shim.getShimName() + " on 26.3");
        }
        for (VersionShim shim : List.of(new Forge_1_21_5_to_1_21_6(), new NeoForge_1_21_5_to_1_21_6(),
                new Fabric_1_21_5_to_1_21_6())) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.21.5"), shim.getShimName() + " on 1.21.5, which still has parse and save");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "1.21.6"), shim.getShimName() + " on 1.21.6");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "26.3"), shim.getShimName() + " on 26.3");
        }
    }

    @Test
    @DisplayName("a Fabric 1.21.5 to 1.21.11 host moves the intermediary calls to intermediary copies")
    void fabricIntermediaryHosts() {
        List<String> on1215 = transformWith(new Fabric_1_21_4_to_1_21_5(), "1.21.5", INTERMEDIARY_PARSE_OPTIONAL_CALLS);
        assertEquals(List.of(
                "184 " + INT_STACK_MEMBERS + ".parseOptional(" + I_PROVIDER + I_COMPOUND + ")" + I_STACK,
                "184 " + INT_STACK_MEMBERS + ".saveOptional(" + I_STACK + I_PROVIDER + ")" + I_TAG), on1215);
        assertEquals(asWritten(INTERMEDIARY_PARSE_CALLS),
                transformWith(new Fabric_1_21_4_to_1_21_5(), "1.21.5", INTERMEDIARY_PARSE_CALLS), "1.21.5 still has these");

        List<String> on12111 = transformWith(new Fabric_1_21_5_to_1_21_6(), "1.21.11", INTERMEDIARY_PARSE_CALLS);
        assertEquals(List.of(
                "184 " + INT_STACK_MEMBERS + ".parse(" + I_PROVIDER + I_TAG + ")Ljava/util/Optional;",
                "184 " + INT_STACK_MEMBERS + ".save(" + I_STACK + I_PROVIDER + ")" + I_TAG,
                "184 " + INT_STACK_MEMBERS + ".save(" + I_STACK + I_PROVIDER + I_TAG + ")" + I_TAG,
                "184 " + INT_CONTAINER_MEMBERS + ".loadAllItems(" + I_COMPOUND + I_LIST + I_PROVIDER + ")V",
                "184 " + INT_CONTAINER_MEMBERS + ".saveAllItems(" + I_COMPOUND + I_LIST + I_PROVIDER + ")" + I_COMPOUND,
                "184 " + INT_CONTAINER_MEMBERS + ".saveAllItems(" + I_COMPOUND + I_LIST + "Z" + I_PROVIDER + ")" + I_COMPOUND),
                on12111);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyItemNbtApi.STACK_MEMBERS),
                "the Mojang-named copy cannot link on an intermediary host");
    }

    @Test
    @DisplayName("a 26.x host gets no intermediary rules, because the remap and the Mojang-named rules apply there")
    void intermediaryRulesStayOffUnobfuscatedHosts() {
        List<String> out = new ArrayList<>(transformWith(new Fabric_1_21_4_to_1_21_5(), "26.1", INTERMEDIARY_PARSE_OPTIONAL_CALLS));
        out.addAll(transformWith(new Fabric_1_21_5_to_1_21_6(), "26.3", INTERMEDIARY_PARSE_CALLS));
        assertTrue(out.stream().noneMatch(call -> call.contains("/intermediary/")), out.toString());
        assertTrue(transformer.getSyntheticClasses().keySet().stream().noneMatch(name -> name.contains("/intermediary/")));
    }

    @Test
    @DisplayName("the intermediary copies call only the members the 1.21.5 to 1.21.11 intermediary tables list")
    void intermediaryCopiesNameOnlyIntermediaryMembers() {
        LegacyItemNbtApi.register1216Intermediary(transformer);

        Set<String> used = new TreeSet<>();
        for (String copy : List.of(INT_STACK_MEMBERS, INT_CONTAINER_MEMBERS)) {
            byte[] bytes = transformer.getSyntheticClasses().get(copy);
            assertNotNull(bytes, copy + " must be registered");
            // A Mojang name anywhere in the pool, an InnerClasses outer class included, is missing on the host.
            assertEquals(List.of(), mojangNamesInPool(bytes), copy + " still names Mojang classes");
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/minecraft/")) {
                        used.add(call.owner + "." + call.name + call.desc);
                    } else if (insn instanceof FieldInsnNode field && field.owner.startsWith("net/minecraft/")) {
                        used.add(field.owner + "." + field.name + ":" + field.desc);
                    }
                }
            }
        }
        // Each id and descriptor checked in the 1.21.5, 1.21.6 and 1.21.11 intermediary tables.
        // ListTag and NonNullList are java.util lists, so their list methods keep the JDK names.
        assertEquals(new TreeSet<>(List.of(
                "net/minecraft/class_1799.field_24671:Lcom/mojang/serialization/Codec;",
                "net/minecraft/class_1799.field_8037:Lnet/minecraft/class_1799;",
                "net/minecraft/class_1799.method_7960()Z",
                "net/minecraft/class_2371.get(I)Ljava/lang/Object;",
                "net/minecraft/class_2371.set(ILjava/lang/Object;)Ljava/lang/Object;",
                "net/minecraft/class_2371.size()I",
                "net/minecraft/class_2487.<init>()V",
                "net/minecraft/class_2487.method_10566(Ljava/lang/String;Lnet/minecraft/class_2520;)Lnet/minecraft/class_2520;",
                "net/minecraft/class_2487.method_10567(Ljava/lang/String;B)V",
                "net/minecraft/class_2487.method_33133()Z",
                "net/minecraft/class_2487.method_68562(Ljava/lang/String;B)B",
                "net/minecraft/class_2487.method_68569(Ljava/lang/String;)Lnet/minecraft/class_2499;",
                "net/minecraft/class_2499.<init>()V",
                "net/minecraft/class_2499.add(Ljava/lang/Object;)Z",
                "net/minecraft/class_2499.isEmpty()Z",
                "net/minecraft/class_2499.iterator()Ljava/util/Iterator;",
                "net/minecraft/class_2509.field_11560:Lnet/minecraft/class_2509;",
                "net/minecraft/class_7225$class_7874.method_57093(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/class_6903;")),
                used);
    }

    /** Every {@code net/minecraft/} name in the class's constant pool that is not an intermediary {@code class_} id. */
    private static List<String> mojangNamesInPool(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        Pattern mojangName = Pattern.compile("net/minecraft/(?!class_)[\\w/$]+");
        List<String> found = new ArrayList<>();
        for (int index = 1; index < reader.getItemCount(); index++) {
            int offset = reader.getItem(index);
            // Every class name, descriptor and signature is a CONSTANT_Utf8 (tag 1). The slot after a
            // long or double has no entry and reads as offset 0.
            if (offset == 0 || bytes[offset - 1] != 1) continue;
            String text = new String(bytes, offset + 2, reader.readUnsignedShort(offset), StandardCharsets.UTF_8);
            Matcher match = mojangName.matcher(text);
            while (match.find()) found.add(match.group());
        }
        return found;
    }

    /** Registers one shim alone for {@code host} and returns the calls the old mod makes after the transform. */
    private List<String> transformWith(VersionShim shim, String host, List<Call> calls) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        shim.registerRedirects(transformer);
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(oldCalls(calls), "test/mod/Saves")).accept(node, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) out.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            }
        }
        return out;
    }

    /** The calls exactly as the mod wrote them. */
    private static List<String> asWritten(List<Call> calls) {
        List<String> out = new ArrayList<>();
        for (Call call : calls) out.add((call.instance() ? INVOKEVIRTUAL : INVOKESTATIC) + " " + call.owner() + "." + call.name() + call.desc());
        return out;
    }

    /** A mod class making each call with null or zero arguments, enough for the transform to see it. */
    private static byte[] oldCalls(List<Call> calls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Saves", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "save", "()V", null, null);
        mv.visitCode();
        for (Call call : calls) {
            if (call.instance()) mv.visitInsn(ACONST_NULL);
            for (Type arg : Type.getArgumentTypes(call.desc())) mv.visitInsn(arg.getSort() == Type.BOOLEAN ? ICONST_0 : ACONST_NULL);
            mv.visitMethodInsn(call.instance() ? INVOKEVIRTUAL : INVOKESTATIC, call.owner(), call.name(), call.desc(), false);
            if (Type.getReturnType(call.desc()) != Type.VOID_TYPE) mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Compiles the chest against 1.21.1, translates it if asked, and runs it on 1.21.6. */
    private String roundTrip(boolean translate) throws Exception {
        byte[] chest = compile("old", OLD_HOST, MOD).get(CHEST);
        if (translate) chest = transformer.transformClass(chest, CHEST.replace('.', '/'));

        Map<String, byte[]> classes = new HashMap<>(compile("new", NEW_HOST));
        classes.put(CHEST, chest);
        transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        ClassLoader host = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> provider = host.loadClass("net.minecraft.core.HolderLookup$Provider");
        Object registries = host.loadClass("net.minecraft.core.TestRegistries").getConstructor().newInstance();
        return (String) host.loadClass(CHEST).getMethod("roundTrip", provider).invoke(null, registries);
    }

    // Both hosts: a list with fixed slots and a registry provider that stands for the game's.
    private static final Map<String, String> SHARED = Map.of(
            "net/minecraft/core/NonNullList.java", """
                    package net.minecraft.core;
                    public class NonNullList<E> extends java.util.AbstractList<E> {
                        private final java.util.List<E> values;
                        private NonNullList(java.util.List<E> values) { this.values = values; }
                        public static <E> NonNullList<E> withSize(int size, E fill) {
                            return new NonNullList<>(new java.util.ArrayList<>(java.util.Collections.nCopies(size, fill)));
                        }
                        public E get(int i) { return values.get(i); }
                        public E set(int i, E value) { return values.set(i, value); }
                        public int size() { return values.size(); }
                    }""",
            "net/minecraft/nbt/Tag.java", "package net.minecraft.nbt; public interface Tag {}");

    /** The 1.21.1 members the chest compiles against, checked with javap against the 1.21.1 mappings. */
    private static final Map<String, String> OLD_HOST = with(SHARED, Map.of(
            "net/minecraft/core/HolderLookup.java",
            "package net.minecraft.core; public interface HolderLookup { interface Provider {} }",
            "net/minecraft/nbt/CompoundTag.java", """
                    package net.minecraft.nbt;
                    public class CompoundTag implements Tag {
                        public void putByte(String key, byte value) { throw new AssertionError(); }
                        public void putString(String key, String value) { throw new AssertionError(); }
                    }""",
            "net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.core.HolderLookup;
                    import net.minecraft.nbt.*;
                    public final class ItemStack {
                        public static final ItemStack EMPTY = null;
                        public ItemStack(String id, int count) {}
                        public boolean isEmpty() { throw new AssertionError(); }
                        public static java.util.Optional<ItemStack> parse(HolderLookup.Provider r, Tag t) { throw new AssertionError(); }
                        public static ItemStack parseOptional(HolderLookup.Provider r, CompoundTag t) { throw new AssertionError(); }
                        public Tag save(HolderLookup.Provider r) { throw new AssertionError(); }
                        public Tag save(HolderLookup.Provider r, Tag prefix) { throw new AssertionError(); }
                        public Tag saveOptional(HolderLookup.Provider r) { throw new AssertionError(); }
                    }""",
            "net/minecraft/world/ContainerHelper.java", """
                    package net.minecraft.world;
                    import net.minecraft.core.*;
                    import net.minecraft.nbt.CompoundTag;
                    import net.minecraft.world.item.ItemStack;
                    public class ContainerHelper {
                        public static CompoundTag saveAllItems(CompoundTag t, NonNullList<ItemStack> l, HolderLookup.Provider r) { throw new AssertionError(); }
                        public static CompoundTag saveAllItems(CompoundTag t, NonNullList<ItemStack> l, boolean always, HolderLookup.Provider r) { throw new AssertionError(); }
                        public static void loadAllItems(CompoundTag t, NonNullList<ItemStack> l, HolderLookup.Provider r) { throw new AssertionError(); }
                    }"""));

    /**
     * The 1.21.6 shape: no tag forms on ItemStack or ContainerHelper, and an item codec. The codec
     * writes {@code id} and {@code count} and refuses an unknown item, the way the game's does.
     */
    private static final Map<String, String> NEW_HOST = with(SHARED, Map.ofEntries(
            Map.entry("com/mojang/serialization/DynamicOps.java", "package com.mojang.serialization; public interface DynamicOps<T> {}"),
            Map.entry("com/mojang/serialization/DataResult.java", """
                    package com.mojang.serialization;
                    public interface DataResult<R> {
                        java.util.Optional<R> resultOrPartial(java.util.function.Consumer<String> onError);
                        R getOrThrow();
                        static <R> DataResult<R> of(R value, String error) {
                            return new DataResult<>() {
                                public java.util.Optional<R> resultOrPartial(java.util.function.Consumer<String> onError) {
                                    if (error != null) onError.accept(error);
                                    return java.util.Optional.ofNullable(value);
                                }
                                public R getOrThrow() { if (error != null) throw new IllegalStateException(error); return value; }
                            };
                        }
                    }"""),
            Map.entry("com/mojang/serialization/Codec.java", """
                    package com.mojang.serialization;
                    public interface Codec<A> {
                        <T> DataResult<A> parse(DynamicOps<T> ops, T input);
                        <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix);
                        <T> DataResult<T> encodeStart(DynamicOps<T> ops, A input);
                    }"""),
            Map.entry("net/minecraft/resources/RegistryOps.java", """
                    package net.minecraft.resources;
                    public class RegistryOps<T> implements com.mojang.serialization.DynamicOps<T> {}"""),
            Map.entry("net/minecraft/core/HolderLookup.java", """
                    package net.minecraft.core;
                    public interface HolderLookup {
                        interface Provider {
                            default <V> net.minecraft.resources.RegistryOps<V> createSerializationContext(com.mojang.serialization.DynamicOps<V> ops) {
                                return new net.minecraft.resources.RegistryOps<>();
                            }
                        }
                    }"""),
            Map.entry("net/minecraft/core/TestRegistries.java",
                    "package net.minecraft.core; public class TestRegistries implements HolderLookup.Provider {}"),
            Map.entry("net/minecraft/nbt/NbtOps.java", """
                    package net.minecraft.nbt;
                    public class NbtOps implements com.mojang.serialization.DynamicOps<Tag> { public static final NbtOps INSTANCE = new NbtOps(); }"""),
            Map.entry("net/minecraft/nbt/ListTag.java", """
                    package net.minecraft.nbt;
                    public class ListTag extends java.util.AbstractList<Tag> implements Tag {
                        private final java.util.List<Tag> values = new java.util.ArrayList<>();
                        public Tag get(int i) { return values.get(i); }
                        public void add(int i, Tag value) { values.add(i, value); }
                        public int size() { return values.size(); }
                    }"""),
            Map.entry("net/minecraft/nbt/CompoundTag.java", """
                    package net.minecraft.nbt;
                    public class CompoundTag implements Tag {
                        final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
                        public boolean isEmpty() { return values.isEmpty(); }
                        public Tag put(String key, Tag value) { return (Tag) values.put(key, value); }
                        public void putByte(String key, byte value) { values.put(key, value); }
                        public byte getByteOr(String key, byte fallback) { return values.get(key) instanceof Number n ? n.byteValue() : fallback; }
                        public ListTag getListOrEmpty(String key) { return values.get(key) instanceof ListTag l ? l : new ListTag(); }
                        public void putString(String key, String value) { values.put(key, value); }
                        public void putInt(String key, int value) { values.put(key, value); }
                        public Object raw(String key) { return values.get(key); }
                        public CompoundTag copy() { CompoundTag copy = new CompoundTag(); copy.values.putAll(values); return copy; }
                        public String toString() {
                            StringBuilder out = new StringBuilder("{");
                            values.forEach((k, v) -> out.append(out.length() > 1 ? ", " : "").append(k).append('=').append(v));
                            return out.append('}').toString();
                        }
                    }"""),
            Map.entry("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    import com.mojang.serialization.*;
                    import net.minecraft.nbt.*;
                    public final class ItemStack {
                        public static final ItemStack EMPTY = new ItemStack("air", 0);
                        public static final Codec<ItemStack> CODEC = new Codec<>() {
                            public <T> DataResult<ItemStack> parse(DynamicOps<T> ops, T input) {
                                if (!(input instanceof CompoundTag tag) || "not_an_item".equals(tag.raw("id"))) {
                                    return DataResult.of(null, "Unknown item " + input);
                                }
                                return DataResult.of(new ItemStack((String) tag.raw("id"), (Integer) tag.raw("count")), null);
                            }
                            @SuppressWarnings("unchecked")
                            public <T> DataResult<T> encode(ItemStack stack, DynamicOps<T> ops, T prefix) {
                                CompoundTag out = ((CompoundTag) prefix).copy();
                                out.putString("id", stack.id);
                                out.putInt("count", stack.count);
                                return DataResult.of((T) out, null);
                            }
                            @SuppressWarnings("unchecked")
                            public <T> DataResult<T> encodeStart(DynamicOps<T> ops, ItemStack stack) {
                                return encode(stack, ops, (T) new CompoundTag());
                            }
                        };
                        final String id;
                        final int count;
                        public ItemStack(String id, int count) { this.id = id; this.count = count; }
                        public boolean isEmpty() { return count <= 0; }
                        public String toString() { return id + " x" + count; }
                    }"""),
            Map.entry("net/minecraft/world/ContainerHelper.java",
                    "package net.minecraft.world; public class ContainerHelper {}")));

    /** A 1.21.1 chest that saves its slots, reads them back, and uses each single-stack form. */
    private static final Map<String, String> MOD = Map.of("test/mod/Chest.java", """
            package test.mod;
            import net.minecraft.core.*;
            import net.minecraft.nbt.*;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.item.ItemStack;
            public class Chest {
                public static String roundTrip(HolderLookup.Provider registries) {
                    ItemStack diamond = new ItemStack("diamond", 5);
                    NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
                    items.set(1, diamond);
                    items.set(2, new ItemStack("sword", 1));
                    CompoundTag saved = ContainerHelper.saveAllItems(new CompoundTag(), items, registries);
                    NonNullList<ItemStack> loaded = NonNullList.withSize(3, ItemStack.EMPTY);
                    ContainerHelper.loadAllItems(saved, loaded, registries);

                    ItemStack back = ItemStack.parse(registries, diamond.save(registries)).orElseThrow();
                    CompoundTag slot = new CompoundTag();
                    slot.putByte("Slot", (byte) 3);
                    Tag intoSlot = diamond.save(registries, slot);
                    boolean emptyTagIsEmpty = ItemStack.parseOptional(registries, new CompoundTag()).isEmpty();
                    String emptyThrows;
                    try {
                        ItemStack.EMPTY.save(registries);
                        emptyThrows = "false";
                    } catch (IllegalStateException e) {
                        emptyThrows = "true";
                    }
                    Tag emptySaved = ItemStack.EMPTY.saveOptional(registries);
                    NonNullList<ItemStack> none = NonNullList.withSize(2, ItemStack.EMPTY);
                    CompoundTag skipped = ContainerHelper.saveAllItems(new CompoundTag(), none, false, registries);
                    CompoundTag kept = ContainerHelper.saveAllItems(new CompoundTag(), none, registries);
                    CompoundTag bad = new CompoundTag();
                    bad.putString("id", "not_an_item");
                    boolean invalid = ItemStack.parse(registries, bad).isEmpty();
                    return saved + " " + loaded + " " + back + " " + intoSlot + " " + emptyTagIsEmpty + " "
                            + emptyThrows + " " + emptySaved + " " + skipped + " " + kept + " invalid:" + invalid;
                }
            }""");

    private static Map<String, String> with(Map<String, String> base, Map<String, String> more) {
        Map<String, String> all = new HashMap<>(base);
        all.putAll(more);
        return all;
    }

    @SafeVarargs
    private Map<String, byte[]> compile(String label, Map<String, String>... sourceSets) throws Exception {
        Path src = tempDir.resolve(label + "-src");
        Path out = tempDir.resolve(label + "-out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map<String, String> sources : sourceSets) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = src.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile the host stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), label + " sources must compile");

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
