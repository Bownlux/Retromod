/*
 * Retromod: ASM synthetics for classes NeoForge deleted that old mods still reference.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.shim.forge;

import java.util.function.Supplier;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.core.RetromodTransformer;

import static org.objectweb.asm.Opcodes.*;

/**
 * ASM bridges for classes NeoForge deleted that 1.20.1-1.21.x Forge/NeoForge mods still
 * reference (#85). Each is registered with {@link RetromodTransformer#registerSyntheticClass} and
 * embedded per-mod, under a unique {@code com/retromod/embedded/<mod>/} package, by
 * {@link com.retromod.core.SyntheticEmbedder}.
 *
 * <ul>
 *   <li>FMLJavaModLoadingContext: {@code get().getModEventBus()} bridges to
 *       {@code ModLoadingContext.get().getActiveContainer().getEventBus()}.</li>
 *   <li>DeferredSpawnEggItem: 26.2's {@code SpawnEggItem} takes only {@code Item.Properties}
 *       (entity type moved to a data component). The bridge's old
 *       {@code (Supplier,int,int,Item.Properties)} ctor sets the type via
 *       {@code Item.Properties.spawnEgg(EntityType)} (supplier resolved in a try/catch) then
 *       calls super.</li>
 *   <li>IForgeRegistry: empty marker interface for references that linger as a standalone type
 *       (method parameter, field declaration). Common uses are re-pointed by
 *       {@link com.retromod.shim.api.forge.ForgeRegistryApiShim}; it carries no members.</li>
 *   <li>DistExecutor: all-static helper for client/server-split execution. Each method delegates
 *       to the running side and runs the action only on a matching dist; the
 *       {@code safe*} variants' serializable SAMs are companion marker interfaces. The migration
 *       shim renames {@code Dist} to NeoForge's before this applies.</li>
 * </ul>
 *
 * <p>Each synthetic is registered only when its original class is absent on the host, to avoid
 * shadowing the real one.
 */
public final class ForgeNeoForgeSynthetics {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private ForgeNeoForgeSynthetics() {}

    private static final String FMLJMLC = "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String DSEI = "net/neoforged/neoforge/common/DeferredSpawnEggItem";
    private static final String SPAWN_EGG = "net/minecraft/world/item/SpawnEggItem";
    private static final String PROPS = "net/minecraft/world/item/Item$Properties";
    private static final String L_PROPS = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    private static final String IFORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    private static final String DIST_EXECUTOR = "net/minecraftforge/fml/DistExecutor";
    private static final String DE_SAFE_RUNNABLE = DIST_EXECUTOR + "$SafeRunnable";
    private static final String DE_SAFE_CALLABLE = DIST_EXECUTOR + "$SafeCallable";
    private static final String DE_SAFE_SUPPLIER = DIST_EXECUTOR + "$SafeSupplier";
    private static final String DIST = "net/neoforged/api/distmarker/Dist";
    private static final String L_DIST = "L" + DIST + ";";
    private static final String FMLENV = "net/neoforged/fml/loading/FMLEnvironment";
    private static final String SUPPLIER = "java/util/function/Supplier";

    /** Register the bridges whose original class is absent on the host (runtime entry points). */
    public static void register(RetromodTransformer t) {
        // The registry-id bridge is not a stand-in for a deleted class; it's always needed on 26.x
        // (blocks/items require their id on Properties before construction). Register unconditionally.
        registerRegistryIdBridge(t);
        registerIfAbsent(t, FMLJMLC, ForgeNeoForgeSynthetics::generateFmlJavaModLoadingContext);
        registerIfAbsent(t, DSEI, ForgeNeoForgeSynthetics::generateDeferredSpawnEggItem);
        registerIfAbsent(t, IFORGE_REGISTRY, ForgeNeoForgeSynthetics::generateIForgeRegistry);
        registerIfAbsent(t, DIST_EXECUTOR, ForgeNeoForgeSynthetics::generateDistExecutor);
        registerIfAbsent(t, DE_SAFE_RUNNABLE, () -> markerInterface(DE_SAFE_RUNNABLE, "java/lang/Runnable"));
        registerIfAbsent(t, DE_SAFE_CALLABLE, () -> markerInterface(DE_SAFE_CALLABLE, "java/util/concurrent/Callable"));
        registerIfAbsent(t, DE_SAFE_SUPPLIER, () -> markerInterface(DE_SAFE_SUPPLIER, SUPPLIER));
        ForgeRecipeConditionSynthetics.generateAll().forEach((name, bytes) -> registerIfAbsent(t, name, () -> bytes));
    }

    /**
     * Register all bridges unconditionally, for the offline CLI/AOT path where the host MC isn't
     * on the classpath so the absence check can't run. The caller version-gates (26.1+ targets,
     * where the originals are gone) and loader-gates (NeoForge mods).
     */
    public static void registerAll(RetromodTransformer t) {
        try {
            t.registerSyntheticClass(RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.generate());
            t.registerSyntheticClass(FMLJMLC, generateFmlJavaModLoadingContext());
            t.registerSyntheticClass(DSEI, generateDeferredSpawnEggItem());
            t.registerSyntheticClass(IFORGE_REGISTRY, generateIForgeRegistry());
            t.registerSyntheticClass(DIST_EXECUTOR, generateDistExecutor());
            t.registerSyntheticClass(DE_SAFE_RUNNABLE, markerInterface(DE_SAFE_RUNNABLE, "java/lang/Runnable"));
            t.registerSyntheticClass(DE_SAFE_CALLABLE, markerInterface(DE_SAFE_CALLABLE, "java/util/concurrent/Callable"));
            t.registerSyntheticClass(DE_SAFE_SUPPLIER, markerInterface(DE_SAFE_SUPPLIER, SUPPLIER));
            ForgeRecipeConditionSynthetics.generateAll().forEach(t::registerSyntheticClass);
        } catch (Throwable e) {
            LOGGER.warn("Could not register Forge/NeoForge synthetics (offline): {}", e.toString());
        }
    }

    /** Register the DeferredRegister id bridge (#87). Unconditional: it's Retromod-owned, never on the host. */
    private static void registerRegistryIdBridge(RetromodTransformer t) {
        try {
            t.registerSyntheticClass(RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.generate());
        } catch (Throwable e) {
            LOGGER.warn("Could not register registry-id bridge: {}", e.toString());
        }
    }

    private static void registerIfAbsent(RetromodTransformer t, String internalName, Supplier<byte[]> gen) {
        if (classExists(internalName)) return; // don't shadow the real class
        try {
            t.registerSyntheticClass(internalName, gen.get());
            LOGGER.info("Registered synthetic bridge for deleted class {}", internalName);
        } catch (Throwable e) {
            LOGGER.warn("Could not register synthetic {}: {}", internalName, e.toString());
        }
    }

    private static boolean classExists(String internalName) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ForgeNeoForgeSynthetics.class.getClassLoader();
            Class.forName(internalName.replace('/', '.'), false, cl);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** A ClassWriter whose frame computation never needs the (absent) MC class hierarchy. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    static byte[] generateFmlJavaModLoadingContext() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, FMLJMLC, null, "java/lang/Object", null);
        String selfDesc = "L" + FMLJMLC + ";";
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "INSTANCE", selfDesc, null, null).visitEnd();

        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitTypeInsn(NEW, FMLJMLC);
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, FMLJMLC, "<init>", "()V", false);
        ci.visitFieldInsn(PUTSTATIC, FMLJMLC, "INSTANCE", selfDesc);
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor g = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()" + selfDesc, null, null);
        g.visitCode();
        g.visitFieldInsn(GETSTATIC, FMLJMLC, "INSTANCE", selfDesc);
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "getModEventBus",
                "()Lnet/neoforged/bus/api/IEventBus;", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, "net/neoforged/fml/ModLoadingContext", "get",
                "()Lnet/neoforged/fml/ModLoadingContext;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "net/neoforged/fml/ModLoadingContext", "getActiveContainer",
                "()Lnet/neoforged/fml/ModContainer;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "net/neoforged/fml/ModContainer", "getEventBus",
                "()Lnet/neoforged/bus/api/IEventBus;", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] generateDeferredSpawnEggItem() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC, DSEI, null, SPAWN_EGG, null);

        // retromod$resolve(props, type): try { props.spawnEgg((EntityType) type.get()); } catch (Throwable) { props; }
        MethodVisitor r = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "retromod$resolve",
                "(" + L_PROPS + "Ljava/util/function/Supplier;)" + L_PROPS, null, null);
        r.visitCode();
        Label tryStart = new Label(), tryEnd = new Label(), handler = new Label();
        r.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        r.visitLabel(tryStart);
        r.visitVarInsn(ALOAD, 0); // props
        r.visitVarInsn(ALOAD, 1); // supplier
        r.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get",
                "()Ljava/lang/Object;", true);
        r.visitTypeInsn(CHECKCAST, ENTITY_TYPE);
        r.visitMethodInsn(INVOKEVIRTUAL, PROPS, "spawnEgg",
                "(L" + ENTITY_TYPE + ";)" + L_PROPS, false);
        r.visitInsn(ARETURN);
        r.visitLabel(tryEnd);
        r.visitLabel(handler);
        r.visitInsn(POP); // discard the Throwable
        r.visitVarInsn(ALOAD, 0); // props
        r.visitInsn(ARETURN);
        r.visitMaxs(0, 0);
        r.visitEnd();

        // <init>(Supplier type, int bg, int hl, Item.Properties props): super(retromod$resolve(props, type));
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Ljava/util/function/Supplier;II" + L_PROPS + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0); // this
        c.visitVarInsn(ALOAD, 4); // props
        c.visitVarInsn(ALOAD, 1); // supplier
        c.visitMethodInsn(INVOKESTATIC, DSEI, "retromod$resolve",
                "(" + L_PROPS + "Ljava/util/function/Supplier;)" + L_PROPS, false);
        c.visitMethodInsn(INVOKESPECIAL, SPAWN_EGG, "<init>", "(" + L_PROPS + ")V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    // Empty interface so IForgeRegistry resolves as a type (implements/generic bound).
    static byte[] generateIForgeRegistry() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, IFORGE_REGISTRY, null,
                "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Nine methods, three bodies: run* (Supplier<Runnable>), call* (Supplier<Callable>),
    // *ForDist (pick the client/server Supplier<Supplier>). The safe* variants' SAMs extend the
    // plain JDK type, so they share the same cast and body.
    static byte[] generateDistExecutor() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, DIST_EXECUTOR, null, "java/lang/Object", null);

        MethodVisitor c = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        for (String n : new String[]{"runWhenOn", "unsafeRunWhenOn", "safeRunWhenOn"}) emitRunWhenOn(cw, n);
        for (String n : new String[]{"callWhenOn", "unsafeCallWhenOn", "safeCallWhenOn"}) emitCallWhenOn(cw, n);
        for (String n : new String[]{"runForDist", "unsafeRunForDist", "safeRunForDist"}) emitRunForDist(cw, n);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Pushes the running side. {@code FMLEnvironment.getDist()} arrived in NeoForge 1.21.9 and the
     * public {@code dist} field it replaced is gone there, so the generated code follows the host.
     */
    private static void readDist(MethodVisitor m) {
        if (com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.9") >= 0) {
            m.visitMethodInsn(INVOKESTATIC, FMLENV, "getDist", "()" + L_DIST, false);
        } else {
            m.visitFieldInsn(GETSTATIC, FMLENV, "dist", L_DIST);
        }
    }

    /** {@code static void name(Dist, Supplier)}: if getDist()==dist, ((Runnable) supplier.get()).run(). */
    private static void emitRunWhenOn(ClassWriter cw, String name) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(" + L_DIST + "L" + SUPPLIER + ";)V", null, null);
        m.visitCode();
        readDist(m);
        m.visitVarInsn(ALOAD, 0);
        Label end = new Label();
        m.visitJumpInsn(IF_ACMPNE, end);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()Ljava/lang/Object;", true);
        m.visitTypeInsn(CHECKCAST, "java/lang/Runnable");
        m.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        m.visitLabel(end);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code static Object name(Dist, Supplier)}: if getDist()==dist, return ((Callable) supplier.get()).call(); else null. */
    private static void emitCallWhenOn(ClassWriter cw, String name) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(" + L_DIST + "L" + SUPPLIER + ";)Ljava/lang/Object;", null,
                new String[]{"java/lang/Exception"});
        m.visitCode();
        readDist(m);
        m.visitVarInsn(ALOAD, 0);
        Label els = new Label();
        m.visitJumpInsn(IF_ACMPNE, els);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()Ljava/lang/Object;", true);
        m.visitTypeInsn(CHECKCAST, "java/util/concurrent/Callable");
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/concurrent/Callable", "call", "()Ljava/lang/Object;", true);
        m.visitInsn(ARETURN);
        m.visitLabel(els);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code static Object name(Supplier client, Supplier server)}: pick by Dist.CLIENT, then .get().get(). */
    private static void emitRunForDist(ClassWriter cw, String name) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(L" + SUPPLIER + ";L" + SUPPLIER + ";)Ljava/lang/Object;", null, null);
        m.visitCode();
        readDist(m);
        m.visitFieldInsn(GETSTATIC, DIST, "CLIENT", L_DIST);
        Label server = new Label(), run = new Label();
        m.visitJumpInsn(IF_ACMPNE, server);
        m.visitVarInsn(ALOAD, 0); // client side supplier
        m.visitJumpInsn(GOTO, run);
        m.visitLabel(server);
        m.visitVarInsn(ALOAD, 1); // server side supplier
        m.visitLabel(run);
        m.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()Ljava/lang/Object;", true);
        m.visitTypeInsn(CHECKCAST, SUPPLIER);
        m.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()Ljava/lang/Object;", true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** An empty {@code interface name extends superIface, java.io.Serializable} (a serializable SAM). */
    static byte[] markerInterface(String name, String superIface) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null,
                "java/lang/Object", new String[]{superIface, "java/io/Serializable"});
        cw.visitEnd();
        return cw.toByteArray();
    }
}
