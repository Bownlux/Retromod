/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the per-state copper constants and copper records that 26.2 folded into weathering copper collections. */
public final class LegacyCopperConstantBridge {

    static final String HELPER = "com/retromod/generated/LegacyCopperConstants";

    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String ITEMS = "net/minecraft/world/item/Items";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String ITEM = "net/minecraft/world/item/Item";
    static final String COLLECTION = "net/minecraft/world/level/block/WeatheringCopperCollection";
    private static final String BY_STATE = COLLECTION + "$ByState";
    private static final String L_COLLECTION = "L" + COLLECTION + ";";
    private static final String OLD_BLOCKS_RECORD = "net/minecraft/world/level/block/WeatheringCopperBlocks";
    private static final String OLD_ITEMS_RECORD = "net/minecraft/world/item/WeatheringCopperItems";
    private static final String BI_MAP = "com/google/common/collect/ImmutableBiMap";
    private static final String LIST = "com/google/common/collect/ImmutableList";

    /** Each family's old unaffected name, which 26.2 kept for the whole collection on Blocks and Items. */
    static final List<String> FAMILIES = List.of("COPPER_BLOCK", "CUT_COPPER", "CHISELED_COPPER",
            "CUT_COPPER_STAIRS", "CUT_COPPER_SLAB", "COPPER_DOOR", "COPPER_TRAPDOOR", "COPPER_GRATE",
            "COPPER_BULB", "COPPER_CHEST", "COPPER_GOLEM_STATUE", "LIGHTNING_ROD");
    private static final List<String> STATES = List.of("unaffected", "exposed", "weathered", "oxidized");

    /** One removed constant: {@code owner.oldName} is {@code owner.family.<half>().<state>()}. */
    record Constant(String owner, String type, String oldName, String family, boolean waxed, String state) {
        String getter() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "$" + oldName;
        }
    }

    /** One accessor of the 1.21.9 copper records, and the half and state it reads now. */
    record Accessor(String name, boolean waxed, String state) {}

    /** One entry of the collection: its waxed or weathering half, and the state in it. */
    record Slot(boolean waxed, String state) {}

    // The old records' list and waxed map, in their order: each state, then its waxed form.
    private static final List<Slot> INTERLEAVED = STATES.stream()
            .flatMap(state -> Stream.of(new Slot(false, state), new Slot(true, state))).toList();
    // The old weathering map: each unwaxed state to the next one.
    private static final List<Slot> PROGRESSION = List.of(new Slot(false, "unaffected"), new Slot(false, "exposed"),
            new Slot(false, "exposed"), new Slot(false, "weathered"), new Slot(false, "weathered"), new Slot(false, "oxidized"));

    static final List<Accessor> ACCESSORS = List.of(
            new Accessor("unaffected", false, "unaffected"), new Accessor("exposed", false, "exposed"),
            new Accessor("weathered", false, "weathered"), new Accessor("oxidized", false, "oxidized"),
            new Accessor("waxed", true, "unaffected"), new Accessor("waxedExposed", true, "exposed"),
            new Accessor("waxedWeathered", true, "weathered"), new Accessor("waxedOxidized", true, "oxidized"));

    private LegacyCopperConstantBridge() {}

    /** Registers the constants and record calls on a host whose copper families are collections. */
    public static void register(RetromodTransformer t) {
        if (!hostHasCollections()) return;
        t.registerSyntheticClass(HELPER, generate());
        // Exact descriptors: COPPER_BLOCK read as a Block is the old constant, read as a collection
        // it is 26.2 code, which must stay a field read.
        for (Constant constant : constants()) {
            String desc = "L" + constant.type() + ";";
            t.registerExactFieldRedirect(constant.owner(), constant.oldName(), desc, HELPER, constant.getter(), "()" + desc);
        }
        // COPPER_BARS, COPPER_CHAIN and COPPER_LANTERN were records from 1.21.9 to 26.1. The fields
        // keep their names, so the record types become the collection and their calls go to helpers.
        registerRecord(t, OLD_BLOCKS_RECORD, BLOCK);
        registerRecord(t, OLD_ITEMS_RECORD, ITEM);
    }

    private static void registerRecord(RetromodTransformer t, String record, String type) {
        t.registerClassRedirect(record, COLLECTION);
        String prefix = simpleName(record) + "$";
        List<String[]> calls = new ArrayList<>();
        for (Accessor accessor : ACCESSORS) calls.add(new String[]{accessor.name(), "()L" + type + ";"});
        calls.add(new String[]{"waxedMapping", "()L" + BI_MAP + ";"});
        if (type.equals(BLOCK)) {
            calls.add(new String[]{"weatheringMapping", "()L" + BI_MAP + ";"});
            calls.add(new String[]{"asList", "()L" + LIST + ";"});
        }
        // Keyed on the old owner and on the collection, because a call site can reach the redirects
        // before or after its owner is renamed. The collection's own waxed() returns a ByState, so
        // the old waxed()Block never matches a 26.2 call.
        for (String[] call : calls) {
            // ()R becomes (WeatheringCopperCollection)R: the receiver is the first argument.
            String helperDesc = "(" + L_COLLECTION + call[1].substring(1);
            for (String owner : List.of(record, COLLECTION)) {
                t.registerMethodRedirect(owner, call[0], call[1], HELPER, prefix + call[0], helperDesc, true);
            }
        }
    }

    // Offline, where the host cannot be read, the version decides.
    private static boolean hostHasCollections() {
        ClassNode blocks = ClassResourceInspector.read(BLOCKS);
        if (blocks == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0;
        return blocks.fields.stream().anyMatch(field -> field.name.equals("COPPER_BLOCK") && field.desc.equals(L_COLLECTION));
    }

    /** All 192 removed or retyped constants: 12 families, 8 states, on Blocks and Items. */
    static List<Constant> constants() {
        List<Constant> constants = new ArrayList<>();
        for (String[] owner : new String[][]{{BLOCKS, BLOCK}, {ITEMS, ITEM}}) {
            for (String family : FAMILIES) {
                for (boolean waxed : new boolean[]{false, true}) {
                    for (String state : STATES) {
                        constants.add(new Constant(owner[0], owner[1], oldName(family, waxed, state), family, waxed, state));
                    }
                }
            }
        }
        return constants;
    }

    /** The old name of one state, such as EXPOSED_CUT_COPPER or WAXED_COPPER_BLOCK. */
    static String oldName(String family, boolean waxed, String state) {
        // The plain block's weathered states dropped the _BLOCK: EXPOSED_COPPER, WAXED_OXIDIZED_COPPER.
        boolean plainBlockState = family.equals("COPPER_BLOCK") && !state.equals("unaffected");
        String base = plainBlockState ? "COPPER" : family;
        String prefix = state.equals("unaffected") ? "" : state.toUpperCase() + "_";
        return (waxed ? "WAXED_" : "") + prefix + base;
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        for (Constant constant : constants()) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, constant.getter(), "()L" + constant.type() + ";", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, constant.owner(), constant.family(), L_COLLECTION);
            pick(mv, constant.waxed(), constant.state());
            mv.visitTypeInsn(CHECKCAST, constant.type());
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writeRecordHelpers(cw, OLD_BLOCKS_RECORD, BLOCK);
        writeRecordHelpers(cw, OLD_ITEMS_RECORD, ITEM);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeRecordHelpers(ClassWriter cw, String record, String type) {
        String prefix = simpleName(record) + "$";
        for (Accessor accessor : ACCESSORS) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, prefix + accessor.name(),
                    "(" + L_COLLECTION + ")L" + type + ";", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            pick(mv, accessor.waxed(), accessor.state());
            mv.visitTypeInsn(CHECKCAST, type);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writeCollect(cw, prefix + "waxedMapping", BI_MAP, INTERLEAVED);
        if (!type.equals(BLOCK)) return;
        writeCollect(cw, prefix + "weatheringMapping", BI_MAP, PROGRESSION);
        writeCollect(cw, prefix + "asList", LIST, INTERLEAVED);
    }

    /** {@code ImmutableBiMap.of} or {@code ImmutableList.of} over the given states of the collection. */
    private static void writeCollect(ClassWriter cw, String name, String result, List<Slot> values) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + L_COLLECTION + ")L" + result + ";", null, null);
        mv.visitCode();
        for (Slot value : values) {
            mv.visitVarInsn(ALOAD, 0);
            pick(mv, value.waxed(), value.state());
        }
        mv.visitMethodInsn(INVOKESTATIC, result, "of", "(" + "Ljava/lang/Object;".repeat(values.size()) + ")L" + result + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** With a collection on the stack, leaves {@code collection.<half>().<state>()} as an Object. */
    private static void pick(MethodVisitor mv, boolean waxed, String state) {
        mv.visitMethodInsn(INVOKEVIRTUAL, COLLECTION, waxed ? "waxed" : "weathering", "()L" + BY_STATE + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, BY_STATE, state, "()Ljava/lang/Object;", false);
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
