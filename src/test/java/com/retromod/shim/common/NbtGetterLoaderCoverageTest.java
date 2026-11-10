/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.fabric.Fabric_1_21_4_to_1_21_5;
import com.retromod.shim.forge.Forge_1_21_10_to_1_21_11;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_4_to_1_21_5;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The pre-1.21.5 NBT getters are bridged for every loader from 1.21.5: Forge, NeoForge, Fabric 26.x, and Fabric 1.21.5 to 1.21.11. */
class NbtGetterLoaderCoverageTest {

    private static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    private static final String LIST = "net/minecraft/nbt/ListTag";
    private static final String LOOKUP = "com/retromod/polyfill/registry/NbtCompatLookup";
    private static final String TAG = "net/minecraft/nbt/Tag";
    // The intermediary names a Fabric 1.21.4 mod carries, from the 1.21.4 intermediary table.
    private static final String INT_COMPOUND = "net/minecraft/class_2487";
    private static final String INT_LIST = "net/minecraft/class_2499";
    private static final String INT_TAG = "net/minecraft/class_2520";
    private static final String NUMERIC = "net/minecraft/nbt/NumericTag";
    private static final String COLLECTION = "net/minecraft/nbt/CollectionTag";
    private static final String INT_NUMERIC = "net/minecraft/class_2514";
    private static final String INT_COLLECTION = "net/minecraft/class_2483";
    private static final List<String> BRIDGED = List.of(
            "compoundGetString", "compoundGetInt", "compoundGetLong", "compoundGetFloat",
            "compoundGetDouble", "compoundGetBoolean", "compoundGetByte", "compoundGetShort",
            "compoundGetByteArray", "compoundGetIntArray", "compoundGetLongArray", "compoundGetUUID",
            "compoundHasUUID", "compoundPutUUID", "compoundGetTagType", "tagGetAsString", "listGetString", "listGetInt",
            "numericGetAsInt", "numericGetAsNumber");

    private RetromodTransformer transformer;
    private String previousTarget;

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
    @DisplayName("every 1.21.5 and 26.1 loader shim bridges the getters, UUID helpers, getAsString, getList and contains")
    void everyShimBridgesTheReadRefactor() {
        List<VersionShim> shims = List.of(new Fabric_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1(),
                new NeoForge_1_21_11_to_26_1(), new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5());
        for (VersionShim shim : shims) {
            List<String> out = transformWith(shim);
            String name = shim.getShimName();
            for (String bridge : BRIDGED) {
                assertTrue(out.contains(LOOKUP + "." + bridge), name + " leaves " + bridge + " unbridged: " + out);
            }
            assertTrue(out.contains(COMPOUND + ".getListOrEmpty(Ljava/lang/String;)Lnet/minecraft/nbt/ListTag;"),
                    name + ": getList(String,int) is gone from 1.21.5: " + out);
            assertTrue(out.contains(COMPOUND + ".contains(Ljava/lang/String;)Z"),
                    name + ": contains(String,int) is gone from 1.21.5: " + out);
            assertTrue(transformer.getSyntheticClasses().containsKey(LOOKUP), name + " must embed the lookup");
        }
    }

    @Test
    @DisplayName("CompoundTag.remove(String) gets the returning form only from 1.21.11, where it changed")
    void removeChangesOnlyFrom12111() {
        String voidRemove = COMPOUND + ".remove(Ljava/lang/String;)V";
        String tagRemove = COMPOUND + ".remove(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;";
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5())) {
            List<String> out = transformWith(shim);
            assertTrue(out.contains(voidRemove), shim.getShimName() + ": 1.21.5 to 1.21.10 keep void remove: " + out);
        }
        for (VersionShim shim : List.of(new Forge_1_21_10_to_1_21_11(), new NeoForge_1_21_10_to_1_21_11(),
                new Forge_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Fabric_1_21_11_to_26_1())) {
            List<String> out = transformWith(shim);
            assertTrue(out.contains(tagRemove), shim.getShimName() + ": 1.21.11 returns the removed tag: " + out);
        }
    }

    @Test
    @DisplayName("the 1.21.5 shims register on 1.21.5 and newer hosts only")
    void hostGateStartsAt1215() {
        for (VersionShim shim : List.of(new Forge_1_21_4_to_1_21_5(), new NeoForge_1_21_4_to_1_21_5(),
                new Fabric_1_21_4_to_1_21_5())) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, "1.21.4"), shim.getShimName() + " on 1.21.4");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "1.21.5"), shim.getShimName() + " on 1.21.5");
            assertTrue(ShimRegistry.isAvailableOnHost(shim, "26.3"), shim.getShimName() + " on 26.3");
        }
    }

    @Test
    @DisplayName("a Fabric 1.21.5 to 1.21.11 host bridges the same calls under their intermediary names")
    void fabricIntermediaryHostsBridgeTheReadRefactor() {
        List<String> out = transformWith(new Fabric_1_21_4_to_1_21_5(), "1.21.5", intermediaryReads());
        for (String bridge : BRIDGED) {
            assertTrue(out.contains(LOOKUP + "." + bridge), "1.21.5 Fabric leaves " + bridge + " unbridged: " + out);
        }
        // contains, getList, getCompound and parseTag reach the 1.21.5 intermediary members.
        assertTrue(out.contains(INT_COMPOUND + ".method_10545(Ljava/lang/String;)Z"), out.toString());
        assertTrue(out.contains(INT_COMPOUND + ".method_68569(Ljava/lang/String;)L" + INT_LIST + ";"), out.toString());
        assertTrue(out.contains(INT_COMPOUND + ".method_68568(Ljava/lang/String;)L" + INT_COMPOUND + ";"), out.toString());
        assertTrue(out.contains("net/minecraft/class_2522.method_67315(Ljava/lang/String;)L" + INT_COMPOUND + ";"),
                out.toString());
        // An IntTag call still links, so it keeps its id.
        assertTrue(out.contains("net/minecraft/class_2497.method_10701()I"), out.toString());
        // 1.21.5 to 1.21.10 keep the void remove under the same name.
        assertTrue(out.contains(INT_COMPOUND + ".method_10551(Ljava/lang/String;)V"), out.toString());
    }

    @Test
    @DisplayName("the intermediary rules stay off 26.x hosts, where the remap and the Mojang-named rules apply")
    void intermediaryRulesStayOffUnobfuscatedHosts() {
        List<String> out = transformWith(new Fabric_1_21_4_to_1_21_5(), "26.1", intermediaryReads());
        assertTrue(out.stream().noneMatch(call -> call.startsWith(LOOKUP)), out.toString());
        assertTrue(out.contains(INT_COMPOUND + ".method_10571(Ljava/lang/String;)B"), out.toString());
        List<String> removes = transformWith(new Fabric_1_21_10_to_1_21_11(), "26.1", intermediaryReads());
        assertTrue(removes.contains(INT_COMPOUND + ".method_10551(Ljava/lang/String;)V"), removes.toString());
    }

    @Test
    @DisplayName("a Fabric 1.21.11 host gets the tag-returning remove under its intermediary name")
    void fabricIntermediaryRemoveReturnsTag() {
        List<String> out = transformWith(new Fabric_1_21_10_to_1_21_11(), "1.21.11", intermediaryReads());
        assertTrue(out.contains(INT_COMPOUND + ".method_10551(Ljava/lang/String;)Lnet/minecraft/class_2520;"),
                out.toString());
    }

    @Test
    @DisplayName("offline, a Fabric 26.1 run with no Minecraft jar calls the remapped NumericTag and CollectionTag as interfaces")
    void fabricOfflineRemapUsesInterfaceCalls() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(IntermediaryToMojangMapper.applyTo(transformer) > 0, "the bundled intermediary table must load");
        new Fabric_1_21_4_to_1_21_5().registerRedirects(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        // The remap lands on the 26.x names, so only the call form is left to fix.
        List<String> out = callForms(transformer.transformClass(tagTypeCalls(true), "test/mod/SaveData"));
        assertEquals(List.of("interface " + NUMERIC + ".intValue()I",
                "interface " + NUMERIC + ".box()Ljava/lang/Number;",
                "interface " + COLLECTION + ".size()I"), out);
    }

    @Test
    @DisplayName("NumericTag and CollectionTag take the interface call form from 1.21.5, and stay class calls on 1.21.4")
    void numberAndCollectionTagsBecomeInterfacesIn1215() {
        for (boolean intermediary : new boolean[]{false, true}) {
            String names = intermediary ? "intermediary" : "Mojang";
            RetromodVersion.TARGET_MC_VERSION = "1.21.4";
            enableTransformWithoutNbtRules();
            List<String> old = callForms(transformer.transformClass(tagTypeCalls(intermediary), "test/mod/SaveData"));
            assertEquals(3, old.size(), old.toString());
            assertTrue(old.stream().allMatch(call -> call.startsWith("virtual ")),
                    names + ": 1.21.4 still has the classes: " + old);

            RetromodVersion.TARGET_MC_VERSION = "1.21.5";
            enableTransformWithoutNbtRules();
            List<String> now = callForms(transformer.transformClass(tagTypeCalls(intermediary), "test/mod/SaveData"));
            assertEquals(3, now.size(), now.toString());
            assertTrue(now.stream().allMatch(call -> call.startsWith("interface ")),
                    names + ": 1.21.5 has interfaces: " + now);
        }
    }

    /** Registers one shim alone, at its own target, and returns the NBT calls a 1.21.4 mod ends up making. */
    private List<String> transformWith(VersionShim shim) {
        return transformWith(shim, shim.getTargetVersion(), nbtReads());
    }

    /** Registers one shim alone for {@code host} and returns the calls {@code modClass} makes after the transform. */
    private List<String> transformWith(VersionShim shim, String host, byte[] modClass) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        shim.registerRedirects(transformer);
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(modClass, "test/mod/SaveData")).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) {
                    out.add(c.owner.equals(LOOKUP) ? LOOKUP + "." + c.name : c.owner + "." + c.name + c.desc);
                }
            }
        }
        return out;
    }

    /** Clears every rule and registers one unrelated call, since with no call rules the transformer leaves method bodies alone. */
    private void enableTransformWithoutNbtRules() {
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect("test/mod/Unused", "a", "()V", "test/mod/Unused", "b", "()V");
    }

    /** Each call in the transformed class as its form (interface or virtual), owner, name and descriptor. */
    private static List<String> callForms(byte[] modClass) {
        ClassNode cn = new ClassNode();
        new ClassReader(modClass).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode c)) continue;
                // The opcode and the constant pool entry kind must agree, or the call fails to link.
                String form = c.getOpcode() == INVOKEINTERFACE && c.itf ? "interface"
                        : c.getOpcode() == INVOKEVIRTUAL && !c.itf ? "virtual" : c.getOpcode() + " itf=" + c.itf;
                out.add(form + " " + c.owner + "." + c.name + c.desc);
            }
        }
        return out;
    }

    /** A 1.21.4 mod reading a number tag and a collection tag, which were still classes, in Mojang or intermediary names. */
    private static byte[] tagTypeCalls(boolean intermediary) {
        String numeric = intermediary ? INT_NUMERIC : NUMERIC;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/SaveData", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, numeric, intermediary ? "method_10701" : "getAsInt", "()I", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, numeric, intermediary ? "method_10702" : "getAsNumber",
                "()Ljava/lang/Number;", false);
        mv.visitInsn(POP);
        // CollectionTag extended AbstractList, so size() keeps the JDK name under both mappings.
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, intermediary ? INT_COLLECTION : COLLECTION, "size", "()I", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** What a 1.21.4 mod's save code calls on CompoundTag and ListTag. */
    private static byte[] nbtReads() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/SaveData", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load",
                "(L" + COMPOUND + ";L" + LIST + ";)V", null, null);
        mv.visitCode();
        String[][] compoundGetters = {{"getString", "Ljava/lang/String;"}, {"getInt", "I"}, {"getLong", "J"},
                {"getFloat", "F"}, {"getDouble", "D"}, {"getBoolean", "Z"}, {"getByte", "B"}, {"getShort", "S"},
                {"getByteArray", "[B"}, {"getIntArray", "[I"}, {"getLongArray", "[J"},
                {"getUUID", "Ljava/util/UUID;"}, {"hasUUID", "Z"}, {"getTagType", "B"}};
        for (String[] getter : compoundGetters) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("key");
            mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, getter[0], "(Ljava/lang/String;)" + getter[1], false);
            mv.visitInsn(getter[1].equals("J") || getter[1].equals("D") ? POP2 : POP);
        }
        for (String[] getter : new String[][]{{"getString", "Ljava/lang/String;"}, {"getInt", "I"}}) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, LIST, getter[0], "(I)" + getter[1], false);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("items");
        mv.visitIntInsn(BIPUSH, 10);
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "getList", "(Ljava/lang/String;I)L" + LIST + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitIntInsn(BIPUSH, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "contains", "(Ljava/lang/String;I)Z", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("Owner");
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "putUUID", "(Ljava/lang/String;Ljava/util/UUID;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)L" + TAG + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, TAG, "getAsString", "()Ljava/lang/String;", true);
        mv.visitInsn(POP);
        // NumericTag was a class, so the old call is INVOKEVIRTUAL; a number tag type names itself.
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/NumericTag", "getAsInt", "()I", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/IntTag", "getAsNumber", "()Ljava/lang/Number;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "remove", "(Ljava/lang/String;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The same save code as a Fabric 1.21.4 mod carries it, in intermediary names. */
    private static byte[] intermediaryReads() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/SaveData", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load",
                "(L" + INT_COMPOUND + ";L" + INT_LIST + ";)V", null, null);
        mv.visitCode();
        String[][] compoundGetters = {{"method_10558", "Ljava/lang/String;"}, {"method_10550", "I"},
                {"method_10537", "J"}, {"method_10583", "F"}, {"method_10574", "D"}, {"method_10577", "Z"},
                {"method_10571", "B"}, {"method_10568", "S"}, {"method_10547", "[B"}, {"method_10561", "[I"},
                {"method_10565", "[J"}, {"method_25926", "Ljava/util/UUID;"}, {"method_25928", "Z"},
                {"method_10540", "B"}};
        for (String[] getter : compoundGetters) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("key");
            mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, getter[0], "(Ljava/lang/String;)" + getter[1], false);
            mv.visitInsn(getter[1].equals("J") || getter[1].equals("D") ? POP2 : POP);
        }
        for (String[] getter : new String[][]{{"method_10608", "Ljava/lang/String;"}, {"method_10600", "I"}}) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, INT_LIST, getter[0], "(I)" + getter[1], false);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("Owner");
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, "method_25927", "(Ljava/lang/String;Ljava/util/UUID;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, "method_10580", "(Ljava/lang/String;)L" + INT_TAG + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, INT_TAG, "method_10714", "()Ljava/lang/String;", true);
        mv.visitInsn(POP);
        // NumericTag's getters kept their ids but became interface methods; IntTag stayed a class.
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_2514", "method_10701", "()I", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_2514", "method_10702", "()Ljava/lang/Number;", false);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_2497", "method_10701", "()I", false);
        mv.visitInsn(POP);
        // contains(String,int), getList(String,int), getCompound(String) and TagParser.parseTag(String).
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitIntInsn(BIPUSH, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, "method_10573", "(Ljava/lang/String;I)Z", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("items");
        mv.visitIntInsn(BIPUSH, 10);
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, "method_10554", "(Ljava/lang/String;I)L" + INT_LIST + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("child");
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, "method_10562", "(Ljava/lang/String;)L" + INT_COMPOUND + ";", false);
        mv.visitInsn(POP);
        mv.visitLdcInsn("{}");
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/class_2522", "method_10718",
                "(Ljava/lang/String;)L" + INT_COMPOUND + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("key");
        mv.visitMethodInsn(INVOKEVIRTUAL, INT_COMPOUND, "method_10551", "(Ljava/lang/String;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
