/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.GeneratedLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

/** Sends Fabric API's v2 loot table events, which Fabric API removed for 26.x, to the v3 events that replaced them. */
public final class FabricLootV2EventsBridge {

    private static final String V2 = "net/fabricmc/fabric/api/loot/v2/";
    private static final String V3 = "net/fabricmc/fabric/api/loot/v3/";
    private static final String GENERATED = "com/retromod/generated/LegacyLootV2";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String TABLE = "Lnet/minecraft/world/level/storage/loot/LootTable;";
    private static final String BUILDER = "Lnet/minecraft/world/level/storage/loot/LootTable$Builder;";
    private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String OLD_SOURCE = "L" + V2 + "LootTableSource;";
    private static final String NEW_SOURCE = "L" + V3 + "LootTableSource;";

    /** A v2 callback whose v3 form gained the registries as a last parameter. */
    record Callback(String simpleName, String sam, String oldArgs, String returnDesc) {
        String oldIface() {
            return V2 + "LootTableEvents$" + simpleName;
        }

        String newIface() {
            return V3 + "LootTableEvents$" + simpleName;
        }

        String callbackIface() {
            return GENERATED + simpleName + "Callback";
        }

        String adapter() {
            return GENERATED + simpleName + "Adapter";
        }

        String oldDesc() {
            return "(" + oldArgs + OLD_SOURCE + ")" + returnDesc;
        }

        String newDesc() {
            return "(" + oldArgs + NEW_SOURCE + PROVIDER + ")" + returnDesc;
        }

        int oldArgumentCount() {
            return Type.getArgumentTypes(oldDesc()).length;
        }

        String erasedDesc() {
            String erasedReturn = returnDesc.equals("V") ? "V" : "Ljava/lang/Object;";
            return "(" + "Ljava/lang/Object;".repeat(oldArgumentCount()) + ")" + erasedReturn;
        }

        String wrapDesc() {
            return "(L" + callbackIface() + ";)L" + newIface() + ";";
        }
    }

    static final List<Callback> CALLBACKS = List.of(
            new Callback("Modify", "modifyLootTable", KEY + BUILDER, "V"),
            new Callback("Replace", "replaceLootTable", KEY + TABLE, TABLE));

    private FabricLootV2EventsBridge() {}

    /** Registers where the host has the v3 events and not the v2 ones; offline, where no Fabric API is readable, on 26.x targets. */
    public static void register(RetromodTransformer t) {
        boolean v2 = ClassResourceInspector.exists(V2 + "LootTableEvents");
        boolean v3 = ClassResourceInspector.exists(V3 + "LootTableEvents");
        boolean offline = !v2 && !v3
                && com.retromod.core.RetromodVersion.isUnobfuscatedTarget(com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
        if ((v3 && !v2) || offline) registerRedirects(t);
    }

    static void registerRedirects(RetromodTransformer t) {
        // Same events, sources, loaded callback and table builder; only the pool builder renamed two methods.
        for (String name : new String[]{"LootTableEvents", "LootTableSource", "LootTableEvents$Loaded", "FabricLootTableBuilder",
                "FabricLootPoolBuilder"}) {
            t.registerClassRedirect(V2 + name, V3 + name);
        }
        String poolBuilder = "Lnet/minecraft/world/level/storage/loot/LootPool$Builder;";
        String entry = "Lnet/minecraft/world/level/storage/loot/entries/LootPoolEntryContainer;";
        String condition = "Lnet/minecraft/world/level/storage/loot/predicates/LootItemCondition;";
        // Mods call these on the pool builder Fabric API injects the interface into, or on the interface itself.
        for (String pool : new String[]{V3 + "FabricLootPoolBuilder", poolBuilder.substring(1, poolBuilder.length() - 1)}) {
            t.registerMethodRedirect(pool, "with", "(" + entry + ")" + poolBuilder, pool, "add", "(" + entry + ")" + poolBuilder);
            t.registerMethodRedirect(pool, "with", "(Ljava/util/Collection;)" + poolBuilder, pool, "add", "(Ljava/util/Collection;)" + poolBuilder);
            t.registerMethodRedirect(pool, "conditionally", "(" + condition + ")" + poolBuilder, pool, "when", "(" + condition + ")" + poolBuilder);
            t.registerMethodRedirect(pool, "conditionally", "(Ljava/util/Collection;)" + poolBuilder, pool, "when",
                    "(Ljava/util/Collection;)" + poolBuilder);
        }
        // A v2 lambda is built against an old-shaped interface and wrapped in a v3 callback.
        for (Callback callback : CALLBACKS) {
            t.registerSyntheticClass(callback.callbackIface(), generateCallbackInterface(callback));
            t.registerSyntheticClass(callback.adapter(), generateAdapter(callback));
            RetromodTransformer.LambdaAdapter adapter = new RetromodTransformer.LambdaAdapter(
                    callback.callbackIface(), callback.sam(), callback.erasedDesc(), callback.adapter(), "wrap", callback.wrapDesc());
            // The source class redirect may reach the lambda's type before or after this lookup.
            t.registerLambdaAdapter(callback.oldIface(), callback.sam(), callback.oldDesc(), adapter);
            t.registerLambdaAdapter(callback.oldIface(), callback.sam(), callback.oldDesc().replace(OLD_SOURCE, NEW_SOURCE), adapter);
        }
    }

    /** A functional interface whose method takes the v2 parameters, erased to Object. */
    static byte[] generateCallbackInterface(Callback callback) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                callback.callbackIface(), null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, callback.sam(), callback.erasedDesc(), null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Implements the v3 callback by calling the v2-shaped one without the registries. */
    static byte[] generateAdapter(Callback callback) {
        String adapter = callback.adapter();
        String delegateDesc = "L" + callback.callbackIface() + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                adapter, null, "java/lang/Object", new String[]{callback.newIface()});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "delegate", delegateDesc, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "reported", "Z", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(" + delegateDesc + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, adapter, "delegate", delegateDesc);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor wrap = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wrap", callback.wrapDesc(), null, null);
        wrap.visitCode();
        wrap.visitTypeInsn(Opcodes.NEW, adapter);
        wrap.visitInsn(Opcodes.DUP);
        wrap.visitVarInsn(Opcodes.ALOAD, 0);
        wrap.visitMethodInsn(Opcodes.INVOKESPECIAL, adapter, "<init>", "(" + delegateDesc + ")V", false);
        wrap.visitInsn(Opcodes.ARETURN);
        wrap.visitMaxs(0, 0);
        wrap.visitEnd();

        MethodVisitor call = cw.visitMethod(Opcodes.ACC_PUBLIC, callback.sam(), callback.newDesc(), null, null);
        call.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label linkFailure = new Label();
        call.visitTryCatchBlock(start, end, linkFailure, "java/lang/LinkageError");
        call.visitLabel(start);
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitFieldInsn(Opcodes.GETFIELD, adapter, "delegate", delegateDesc);
        // Every v3 parameter is a reference, so slot = index + 1; the registries are left out.
        for (int argument = 0; argument < callback.oldArgumentCount(); argument++) {
            call.visitVarInsn(Opcodes.ALOAD, argument + 1);
        }
        call.visitMethodInsn(Opcodes.INVOKEINTERFACE, callback.callbackIface(), callback.sam(), callback.erasedDesc(), true);
        boolean returnsTable = !callback.returnDesc().equals("V");
        if (returnsTable) call.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(callback.returnDesc()).getInternalName());
        call.visitLabel(end);
        call.visitInsn(returnsTable ? Opcodes.ARETURN : Opcodes.RETURN);

        // Loot callbacks run while data packs load, where an exception stops the world; a callback that still
        // reaches a removed API is skipped and reported once.
        int caught = Type.getArgumentTypes(callback.newDesc()).length + 1;
        call.visitLabel(linkFailure);
        call.visitVarInsn(Opcodes.ASTORE, caught);
        Label reported = new Label();
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitFieldInsn(Opcodes.GETFIELD, adapter, "reported", "Z");
        call.visitJumpInsn(Opcodes.IFNE, reported);
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitInsn(Opcodes.ICONST_1);
        call.visitFieldInsn(Opcodes.PUTFIELD, adapter, "reported", "Z");
        GeneratedLog.pushLogger(call);
        call.visitLdcInsn("[Retromod] The loot table " + callback.sam() + " callback ");
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitFieldInsn(Opcodes.GETFIELD, adapter, "delegate", delegateDesc);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        concat(call);
        call.visitLdcInsn(" needs an API this Minecraft version removed, so its loot changes are skipped: ");
        concat(call);
        call.visitVarInsn(Opcodes.ALOAD, caught);
        call.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        concat(call);
        GeneratedLog.warn(call);
        call.visitLabel(reported);
        if (returnsTable) {
            // A null replacement keeps the table as it was.
            call.visitInsn(Opcodes.ACONST_NULL);
            call.visitInsn(Opcodes.ARETURN);
        } else {
            call.visitInsn(Opcodes.RETURN);
        }
        call.visitMaxs(0, 0);
        call.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void concat(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
    }
}
