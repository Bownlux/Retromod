/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bridges Fabric API's loot table event callbacks from their 1.20.1 shape to the one Fabric API
 * adopted with Minecraft 1.20.5, for intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>Before 1.20.5 the {@code LootTableEvents} callbacks received the resource manager, the
 * {@code LootDataManager} and the table's identifier. Minecraft 1.20.5 removed
 * {@code LootDataManager}, and the callbacks now receive the table's {@code ResourceKey} instead.
 * A mod that registered an old callback died with {@code NoClassDefFoundError} for the removed
 * class, which ended its whole initializer.
 *
 * <p>The removed class is redirected to an empty stand-in so the mod's callback still links. Old
 * callback lambdas are built against a generated callback interface with the old parameter list
 * and wrapped in an adapter that implements the current interface. The adapter passes the key's
 * identifier where the old callback expected one, and null for the resource manager and
 * {@code LootDataManager} the current event no longer supplies. A callback that reads either of
 * those, or still reaches another removed API, is skipped with one report so the server can load
 * its data packs. The same callback shape holds for Fabric API's v2 loot events through 1.21.11,
 * so the bridge applies on every pre-26.1 host from 1.20.5. A class that implements the old
 * interface directly, instead of a lambda, is not covered.
 */
public final class Pre1_20_5LootTableEventsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String LOOT_DATA_MANAGER = "net/minecraft/class_60";
    static final String LOOT_DATA_STAND_IN = "com/retromod/generated/LegacyLootDataManager";
    private static final String EVENTS = "net/fabricmc/fabric/api/loot/v2/LootTableEvents$";
    private static final String GENERATED = "com/retromod/generated/LegacyLoot";

    private static final String RESOURCE_MANAGER = "Lnet/minecraft/class_3300;";
    private static final String DATA_MANAGER = "L" + LOOT_DATA_MANAGER + ";";
    private static final String ID = "Lnet/minecraft/class_2960;";
    private static final String TABLE = "Lnet/minecraft/class_52;";
    private static final String BUILDER = "Lnet/minecraft/class_52$class_53;";
    private static final String SOURCE = "Lnet/fabricmc/fabric/api/loot/v2/LootTableSource;";
    private static final String KEY_CLASS = "net/minecraft/class_5321";
    private static final String KEY = "L" + KEY_CLASS + ";";
    private static final String REGISTRY = "Lnet/minecraft/class_2378;";
    private static final String KEY_LOCATION = "method_29177";

    /** Where the old callback's argument comes from in the current callback. */
    private static final int NULL = -1;
    private static final int LOCATION_OF_FIRST = -2;

    /**
     * One callback interface. {@code sources} lists, for each old parameter, the current
     * parameter index to pass, {@link #NULL}, or {@link #LOCATION_OF_FIRST} for the identifier of
     * the current first parameter, which is the table's key.
     */
    record Callback(String simpleName, String sam, String oldDesc, String newDesc, int[] sources) {
        String iface() {
            return EVENTS + simpleName;
        }

        String callbackIface() {
            return GENERATED + simpleName + "Callback";
        }

        String adapter() {
            return GENERATED + simpleName + "Adapter";
        }

        int newArgumentCount() {
            return Type.getArgumentTypes(newDesc).length;
        }

        String erasedDesc() {
            Type returnType = Type.getReturnType(oldDesc);
            String erasedReturn = returnType.getSort() == Type.VOID ? "V" : "Ljava/lang/Object;";
            return "(" + "Ljava/lang/Object;".repeat(Type.getArgumentTypes(oldDesc).length) + ")" + erasedReturn;
        }

        String wrapDesc() {
            return "(L" + callbackIface() + ";)L" + iface() + ";";
        }

        /** The old descriptor as it reads once the removed class points at the stand-in. */
        String redirectedOldDesc() {
            return oldDesc.replace(DATA_MANAGER, "L" + LOOT_DATA_STAND_IN + ";");
        }
    }

    static final List<Callback> CALLBACKS = List.of(
            new Callback("Modify", "modifyLootTable",
                    "(" + RESOURCE_MANAGER + DATA_MANAGER + ID + BUILDER + SOURCE + ")V",
                    "(" + KEY + BUILDER + SOURCE + ")V",
                    new int[] {NULL, NULL, LOCATION_OF_FIRST, 1, 2}),
            new Callback("Replace", "replaceLootTable",
                    "(" + RESOURCE_MANAGER + DATA_MANAGER + ID + TABLE + SOURCE + ")" + TABLE,
                    "(" + KEY + TABLE + SOURCE + ")" + TABLE,
                    new int[] {NULL, NULL, LOCATION_OF_FIRST, 1, 2}),
            new Callback("Loaded", "onLootTablesLoaded",
                    "(" + RESOURCE_MANAGER + DATA_MANAGER + ")V",
                    "(" + RESOURCE_MANAGER + REGISTRY + ")V",
                    new int[] {0, NULL}));

    private Pre1_20_5LootTableEventsBridge() {}

    /** Registers the bridge only when the host lacks LootDataManager and the old callbacks. */
    public static void register(RetromodTransformer transformer) {
        if (ClassResourceInspector.exists(LOOT_DATA_MANAGER)) {
            LOGGER.debug("The host still has LootDataManager");
            return;
        }
        List<Callback> changed = CALLBACKS.stream().filter(callback -> {
            ClassNode iface = ClassResourceInspector.read(callback.iface());
            return iface != null && hasMethod(iface, callback.sam(), callback.newDesc())
                    && !hasMethod(iface, callback.sam(), callback.oldDesc());
        }).toList();
        registerRedirects(transformer, changed);
        LOGGER.debug("Added support for {} pre-1.20.5 loot table event callbacks", changed.size());
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer, List<Callback> callbacks) {
        transformer.registerSyntheticClass(LOOT_DATA_STAND_IN, generateStandIn());
        transformer.registerClassRedirect(LOOT_DATA_MANAGER, LOOT_DATA_STAND_IN);
        for (Callback callback : callbacks) {
            transformer.registerSyntheticClass(callback.callbackIface(), generateCallbackInterface(callback));
            transformer.registerSyntheticClass(callback.adapter(), generateAdapter(callback));
            RetromodTransformer.LambdaAdapter adapter = new RetromodTransformer.LambdaAdapter(
                    callback.callbackIface(), callback.sam(), callback.erasedDesc(),
                    callback.adapter(), "wrap", callback.wrapDesc());
            // The class redirect may reach the lambda's method type before or after the adapter
            // lookup, so both spellings of the old descriptor are keyed.
            transformer.registerLambdaAdapter(callback.iface(), callback.sam(), callback.oldDesc(), adapter);
            transformer.registerLambdaAdapter(callback.iface(), callback.sam(), callback.redirectedOldDesc(), adapter);
        }
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    /** An empty class that stands in for the removed {@code LootDataManager} in old signatures. */
    static byte[] generateStandIn() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                LOOT_DATA_STAND_IN, null, "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A functional interface whose method takes the old parameters, erased to Object. */
    static byte[] generateCallbackInterface(Callback callback) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                callback.callbackIface(), null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, callback.sam(),
                callback.erasedDesc(), null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Implements the current callback interface by calling an old-shaped callback. */
    static byte[] generateAdapter(Callback callback) {
        String adapter = callback.adapter();
        String delegateDesc = "L" + callback.callbackIface() + ";";
        // The handler branch needs stack map frames. No two paths merge different reference
        // types, so frame computation never has to load a Minecraft class to find a supertype.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                adapter, null, "java/lang/Object", new String[] {callback.iface()});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "delegate", delegateDesc, null, null).visitEnd();
        // Per adapter, so each broken callback is reported once and one cannot hide another.
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

        MethodVisitor wrap = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "wrap",
                callback.wrapDesc(), null, null);
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
        // The resource manager and LootDataManager arrive as null, so a callback that reads
        // either one fails the same way a removed API does.
        call.visitTryCatchBlock(start, end, linkFailure, "java/lang/NullPointerException");
        call.visitLabel(start);
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitFieldInsn(Opcodes.GETFIELD, adapter, "delegate", delegateDesc);
        for (int source : callback.sources()) {
            if (source == NULL) {
                call.visitInsn(Opcodes.ACONST_NULL);
            } else if (source == LOCATION_OF_FIRST) {
                call.visitVarInsn(Opcodes.ALOAD, 1);
                call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KEY_CLASS, KEY_LOCATION, "()" + ID, false);
            } else {
                // Every current parameter is a reference, so slot = index + 1.
                call.visitVarInsn(Opcodes.ALOAD, source + 1);
            }
        }
        call.visitMethodInsn(Opcodes.INVOKEINTERFACE, callback.callbackIface(), callback.sam(),
                callback.erasedDesc(), true);
        Type returnType = Type.getReturnType(callback.newDesc());
        if (returnType.getSort() == Type.VOID) {
            call.visitLabel(end);
            call.visitInsn(Opcodes.RETURN);
        } else {
            call.visitTypeInsn(Opcodes.CHECKCAST, returnType.getInternalName());
            call.visitLabel(end);
            call.visitInsn(Opcodes.ARETURN);
        }

        // The callback runs while the server loads its data packs, where an exception stops the
        // world from loading at all. A link failure means the mod's own callback still reaches a
        // removed API or reads an argument the current event no longer supplies, so the change is
        // skipped and reported once instead.
        call.visitLabel(linkFailure);
        call.visitVarInsn(Opcodes.ASTORE, callback.newArgumentCount() + 1);
        Label reported = new Label();
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitFieldInsn(Opcodes.GETFIELD, adapter, "reported", "Z");
        call.visitJumpInsn(Opcodes.IFNE, reported);
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitInsn(Opcodes.ICONST_1);
        call.visitFieldInsn(Opcodes.PUTFIELD, adapter, "reported", "Z");
        // One println, because the game's console captures stderr per line and other threads
        // log while data packs load. The callback's class names the mod it came from.
        call.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        call.visitLdcInsn("[Retromod] The pre-1.20.5 loot table " + callback.sam() + " callback ");
        call.visitVarInsn(Opcodes.ALOAD, 0);
        call.visitFieldInsn(Opcodes.GETFIELD, adapter, "delegate", delegateDesc);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        concat(call);
        call.visitLdcInsn(" needs an API or argument this Minecraft version removed, so its loot changes are skipped: ");
        concat(call);
        call.visitVarInsn(Opcodes.ALOAD, callback.newArgumentCount() + 1);
        call.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        concat(call);
        call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        call.visitLabel(reported);
        if (returnType.getSort() == Type.VOID) {
            call.visitInsn(Opcodes.RETURN);
        } else {
            // Replace callbacks return null for "no replacement", which keeps the table as it was.
            call.visitInsn(Opcodes.ACONST_NULL);
            call.visitInsn(Opcodes.ARETURN);
        }
        call.visitMaxs(0, 0);
        call.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void concat(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
    }
}
