/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Keeps loot pool entries built in code working on 26.3, which rebuilt the loot entry builders.
 *
 * <p>Mods add items to vanilla chests from a loot table event with
 * {@code LootItem.lootTableItem(item).setWeight(n)}, {@code EmptyLootItem.emptyItem()} and
 * {@code NestedLootTable.lootTableReference(key)}. On 26.3 these return
 * {@code UniformContainerBase.Builder} instead of {@code LootPoolSingletonContainer.Builder}, the
 * builders take a function or condition as a {@code Holder}, and a nested table is a
 * {@code Holder<LootTable>} rather than a key.
 *
 * <p>The builder class moves to its successor, which keeps {@code setWeight} and
 * {@code setQuality}. A builder's {@code apply} and {@code when} still accept the old builders on
 * 26.3 through interface defaults, so those calls only change their return type. A nested table
 * key is looked up in the registries of the method that builds the entry, which is how the Fabric
 * loot table event hands them over. A method with no registries in its parameters keeps its call
 * unchanged, because no other registry lookup is known to hold the loot tables being loaded.
 */
public final class LegacyLootBuilderBridge {

    public static final String HELPER = "com/retromod/generated/LegacyLootEntries";

    static final String LOOT = "net/minecraft/world/level/storage/loot/";
    static final String ENTRIES = LOOT + "entries/";
    static final String SINGLETON_BUILDER = ENTRIES + "LootPoolSingletonContainer$Builder";
    static final String UNIFORM_BUILDER = ENTRIES + "UniformContainerBase$Builder";
    static final String ENTRY_BUILDER = ENTRIES + "LootPoolEntryContainer$Builder";
    static final String NESTED_TABLE = ENTRIES + "NestedLootTable";
    static final String POOL_BUILDER = LOOT + "LootPool$Builder";
    static final String TABLE_BUILDER = LOOT + "LootTable$Builder";
    static final String FUNCTION_BUILDER = LOOT + "functions/LootItemFunction$Builder";
    static final String CONDITIONAL_FUNCTION_BUILDER = LOOT + "functions/LootItemConditionalFunction$Builder";
    static final String FUNCTION_USER = LOOT + "functions/FunctionUserBuilder";
    static final String CONDITION_BUILDER = LOOT + "predicates/LootItemCondition$Builder";
    static final String CONDITION_USER = LOOT + "predicates/ConditionUserBuilder";

    static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String HOLDER_REFERENCE = "net/minecraft/core/Holder$Reference";
    static final String GETTER_PROVIDER = "net/minecraft/core/HolderGetter$Provider";
    static final String LOOKUP_PROVIDER = "net/minecraft/core/HolderLookup$Provider";

    static final String OLD_REFERENCE = "(L" + RESOURCE_KEY + ";)L" + UNIFORM_BUILDER + ";";
    static final String NEW_REFERENCE = "(L" + HOLDER + ";)L" + UNIFORM_BUILDER + ";";
    static final String HELPER_REFERENCE =
            "(L" + RESOURCE_KEY + ";L" + GETTER_PROVIDER + ";)L" + UNIFORM_BUILDER + ";";

    private LegacyLootBuilderBridge() {}

    /** Registers on a host from 26.3 on, through the 26.2 to 26.3 core moves. */
    public static void register(RetromodTransformer t) {
        // Every factory that returned the old builder returns this one on 26.3, with the same
        // setWeight and setQuality. The entry classes themselves changed shape and are not moved.
        t.registerClassRedirect(SINGLETON_BUILDER, UNIFORM_BUILDER);
        registerBuilderReturns(t);
        t.registerSyntheticClass(HELPER, generateHelper());
    }

    /**
     * Before 26.3 each builder declared {@code apply} and {@code when} for the old builders and
     * returned its own type. On 26.3 only the interface defaults take them, and those return the
     * interface type, so a call keeps its name and arguments and casts the result back.
     */
    private static void registerBuilderReturns(RetromodTransformer t) {
        String applyArg = "(L" + FUNCTION_BUILDER + ";)";
        String whenArg = "(L" + CONDITION_BUILDER + ";)";
        String toFunctionUser = applyArg + "L" + FUNCTION_USER + ";";
        String toConditionUser = whenArg + "L" + CONDITION_USER + ";";

        // Entry builders are subclassed, and javac names the receiver's static type as the owner.
        t.registerInheritedMethodRedirect(UNIFORM_BUILDER, "apply", applyArg + "L" + UNIFORM_BUILDER + ";",
                UNIFORM_BUILDER, "apply", toFunctionUser);
        t.registerInheritedMethodRedirect(ENTRY_BUILDER, "when", whenArg + "L" + ENTRY_BUILDER + ";",
                ENTRY_BUILDER, "when", toConditionUser);
        // The common case, lootTableItem(item).when(...), named on the entry builder itself, needs
        // no hierarchy to prove.
        t.registerMethodRedirect(UNIFORM_BUILDER, "when", whenArg + "L" + ENTRY_BUILDER + ";",
                UNIFORM_BUILDER, "when", toConditionUser);
        t.registerInheritedMethodRedirect(CONDITIONAL_FUNCTION_BUILDER, "when",
                whenArg + "L" + CONDITIONAL_FUNCTION_BUILDER + ";",
                CONDITIONAL_FUNCTION_BUILDER, "when", toConditionUser);
        t.registerMethodRedirect(POOL_BUILDER, "when", whenArg + "L" + POOL_BUILDER + ";",
                POOL_BUILDER, "when", toConditionUser);
        t.registerMethodRedirect(POOL_BUILDER, "apply", applyArg + "L" + POOL_BUILDER + ";",
                POOL_BUILDER, "apply", toFunctionUser);
        t.registerMethodRedirect(TABLE_BUILDER, "apply", applyArg + "L" + TABLE_BUILDER + ";",
                TABLE_BUILDER, "apply", toFunctionUser);
    }

    /**
     * Passes the enclosing method's registries to each {@code lootTableReference(key)} call.
     *
     * @param classBytes a class after the remap and redirects
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] adaptNestedTableReferences(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classBytes).accept(cn, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }

        int changes = 0;
        for (MethodNode method : cn.methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            int registries = -1;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!isOldReference(insn)) continue;
                if (registries == -1) registries = registriesParameter(method);
                if (registries < 0) break;
                MethodInsnNode call = (MethodInsnNode) insn;
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, registries));
                call.owner = HELPER;
                call.desc = HELPER_REFERENCE;
                call.itf = false;
                changes++;
            }
        }
        if (changes == 0) return classBytes;
        // The registries are pushed just before the call that takes them, so no frame changes.
        try {
            ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failed) {
            return classBytes; // never ship a half-rewritten class
        }
    }

    private static boolean isOldReference(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && NESTED_TABLE.equals(call.owner) && "lootTableReference".equals(call.name)
                && OLD_REFERENCE.equals(call.desc);
    }

    /**
     * The local slot of the method's only registries parameter, or -2 when it has none or more
     * than one, since then the loot tables it would look in are unknown.
     */
    static int registriesParameter(MethodNode method) {
        int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int found = -2;
        for (Type parameter : Type.getArgumentTypes(method.desc)) {
            String name = parameter.getSort() == Type.OBJECT ? parameter.getInternalName() : null;
            if (LOOKUP_PROVIDER.equals(name) || GETTER_PROVIDER.equals(name)) {
                if (found >= 0) return -2;
                found = slot;
            }
            slot += parameter.getSize();
        }
        return found;
    }

    /**
     * Writes {@code lootTableReference(key, registries)}, which is
     * {@code NestedLootTable.lootTableReference(registries.getOrThrow(key))}. Generated because its
     * signature names Minecraft types. A lookup that lacks the loot tables fails with Minecraft's
     * own message naming the registry and the key.
     */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lootTableReference", HELPER_REFERENCE, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, GETTER_PROVIDER, "getOrThrow",
                "(L" + RESOURCE_KEY + ";)L" + HOLDER_REFERENCE + ";", true);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NESTED_TABLE, "lootTableReference", NEW_REFERENCE, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
