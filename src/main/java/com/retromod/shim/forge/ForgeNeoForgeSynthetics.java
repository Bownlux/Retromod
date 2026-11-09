/*
 * Retromod: ASM synthetics for classes NeoForge deleted that old mods still reference.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.shim.forge;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.RetromodLog;

import static org.objectweb.asm.Opcodes.*;

/**
 * ASM bridges for classes NeoForge deleted that 1.20.1-1.21.x Forge/NeoForge mods still
 * reference (#85). Each is registered with {@link RetromodTransformer#registerSyntheticClass} and
 * embedded per-mod, under a unique {@code com/retromod/embedded/<mod>/} package, by
 * {@link com.retromod.core.SyntheticEmbedder}.
 *
 * <ul>
 *   <li>FMLJavaModLoadingContext: {@code get().getModEventBus()} bridges to
 *       {@code ModLoadingContext.get().getActiveContainer().getEventBus()}; a context handed to a
 *       {@code @Mod} constructor is bound to that mod's container.</li>
 *   <li>ForgeVersion and MCPVersion: answer with the Forge 1.20.1 build whose API the mod sees, and the
 *       running Minecraft version.</li>
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

    public static final String FMLJMLC = "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String MOD_CONTAINER = "net/neoforged/fml/ModContainer";
    private static final String LOADING_CONTEXT = "net/neoforged/fml/ModLoadingContext";
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
    private static final String FORGE_VERSION = "net/minecraftforge/versions/forge/ForgeVersion";
    private static final String MCP_VERSION = "net/minecraftforge/versions/mcp/MCPVersion";

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
        registerIfAbsent(t, FORGE_VERSION, ForgeNeoForgeSynthetics::generateForgeVersion);
        registerIfAbsent(t, MCP_VERSION, ForgeNeoForgeSynthetics::generateMcpVersion);
        ForgeRecipeConditionSynthetics.generateAll().forEach((name, bytes) -> registerIfAbsent(t, name, () -> bytes));
    }

    // The first NeoForge whose jars were checked to lack FMLJavaModLoadingContext and
    // DeferredSpawnEggItem (neoforge-21.11.42 and its loader 10.0.36). NeoForge 21.1 still ships
    // DeferredSpawnEggItem, and the stand-in calls SpawnEggItem(Properties), which 1.21.9 added.
    private static final String NEOFORGE_REMOVALS_CHECKED = "1.21.11";
    // FMLJavaModLoadingContext alone is already gone from NeoForge 21.1.252 and its loader 4.0.44.
    private static final String CONTEXT_REMOVAL_CHECKED = "1.21.1";

    /** {@link #registerAll(RetromodTransformer, String)} for a 26.x target, where every original is gone. */
    public static void registerAll(RetromodTransformer t) {
        registerAll(t, "26.1");
    }

    /**
     * Register the bridges for an offline target, where the host is not on the classpath to ask.
     * Forge's own classes are never on NeoForge from 1.20.2; NeoForge's removals come only from
     * 1.21.11, the first NeoForge checked to lack them. A Forge target gets the Forge shapes instead.
     */
    public static void registerAll(RetromodTransformer t, String target) {
        try {
            t.registerSyntheticClass(RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.generate());
            RegistryIdBridgeSynthetic.registerWrapperHelpers(t);
            if (!com.retromod.core.RetromodVersion.mcVersionExceeds(CONTEXT_REMOVAL_CHECKED, target)) {
                t.registerSyntheticClass(FMLJMLC, generateFmlJavaModLoadingContext());
            }
            if (!com.retromod.core.RetromodVersion.mcVersionExceeds(NEOFORGE_REMOVALS_CHECKED, target)) {
                t.registerSyntheticClass(DSEI, generateDeferredSpawnEggItem());
            }
            if (com.retromod.util.McReflect.isForgeTarget()) {
                registerForgeShapes(t);
                return;
            }
            t.registerSyntheticClass(IFORGE_REGISTRY, generateIForgeRegistry());
            t.registerSyntheticClass(DIST_EXECUTOR, generateDistExecutor());
            t.registerSyntheticClass(DE_SAFE_RUNNABLE, markerInterface(DE_SAFE_RUNNABLE, "java/lang/Runnable"));
            t.registerSyntheticClass(DE_SAFE_CALLABLE, markerInterface(DE_SAFE_CALLABLE, "java/util/concurrent/Callable"));
            t.registerSyntheticClass(DE_SAFE_SUPPLIER, markerInterface(DE_SAFE_SUPPLIER, SUPPLIER));
            // Forge hosts ship these two, so only a jar meant for NeoForge takes the stand-ins.
            if (com.retromod.util.McReflect.isNeoForge()) {
                t.registerSyntheticClass(FORGE_VERSION, generateForgeVersion());
                t.registerSyntheticClass(MCP_VERSION, generateMcpVersion());
            }
            ForgeRecipeConditionSynthetics.generateAll().forEach(t::registerSyntheticClass);
        } catch (Throwable e) {
            LOGGER.warn("Could not register Forge/NeoForge synthetics (offline): {}", e.toString());
        }
    }

    /** The stand-ins a Forge target needs: only for classes its host lacks, in Forge's own shapes. */
    private static void registerForgeShapes(RetromodTransformer t) {
        // Every Forge host checked (1.20.1, 1.21.11, 26.2, 26.3) ships IForgeRegistry and ICondition,
        // so their stand-ins would shadow the real classes. DistExecutor takes Forge's Dist.
        LegacyDistExecutorBridge.register(t);
        registerForgeConditionStandIns(t);
    }

    /** Stand-ins for CraftingHelper and IConditionSerializer on a Forge host that lacks them. */
    public static void registerForgeConditionStandIns(RetromodTransformer t) {
        registerForgeConditionStandIns(t, ClassResourceInspector::read);
    }

    static void registerForgeConditionStandIns(RetromodTransformer t, Function<String, ClassNode> host) {
        // Both are gone from Forge 1.21.11 on and present in 1.20.1. The Forge runtime reaches this
        // from the 1.21.11 shim, the CLI and AOT from registerAll, so both paths embed the same copy.
        // From Forge 26.2 CraftingHelper also registers old ingredient serializers, through rebuilt classes.
        boolean ingredients = LegacyIngredientSerializerBridge.applies(host);
        if (ingredients) LegacyIngredientSerializerBridge.registerHelpers(t);
        Map<String, byte[]> conditions = ForgeRecipeConditionSynthetics.generateAll(ingredients);
        for (String name : List.of(ForgeRecipeConditionSynthetics.SERIALIZER,
                ForgeRecipeConditionSynthetics.CRAFTING_HELPER)) {
            if (ForgeHostShape.matches(List.of(), List.of(name), "1.21.11", host)) {
                t.registerSyntheticClass(name, conditions.get(name));
            }
        }
    }

    /** Register the DeferredRegister id bridge (#87). Unconditional: it's Retromod-owned, never on the host. */
    private static void registerRegistryIdBridge(RetromodTransformer t) {
        try {
            t.registerSyntheticClass(RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.generate());
            RegistryIdBridgeSynthetic.registerWrapperHelpers(t);
        } catch (Throwable e) {
            LOGGER.warn("Could not register registry-id bridge: {}", e.toString());
        }
    }

    private static void registerIfAbsent(RetromodTransformer t, String internalName, Supplier<byte[]> gen) {
        if (classExists(internalName)) return; // don't shadow the real class
        try {
            t.registerSyntheticClass(internalName, gen.get());
            LOGGER.debug(RetromodLog.DETAIL, "Registered synthetic bridge for deleted class {}", internalName);
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

    /** The stand-in's factory for a context bound to one mod, which a rewritten @Mod constructor calls. */
    public static final String CONTEXT_OF = "retromod$of";
    public static final String CONTEXT_OF_DESC = "(L" + MOD_CONTAINER + ";)L" + FMLJMLC + ";";

    static byte[] generateFmlJavaModLoadingContext() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, FMLJMLC, null, "java/lang/Object", null);
        String selfDesc = "L" + FMLJMLC + ";";
        String containerDesc = "L" + MOD_CONTAINER + ";";
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "INSTANCE", selfDesc, null, null).visitEnd();
        // Null for get()'s shared instance, which asks FML for the container constructing at the time.
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "container", containerDesc, null, null).visitEnd();

        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitTypeInsn(NEW, FMLJMLC);
        ci.visitInsn(DUP);
        ci.visitInsn(ACONST_NULL);
        ci.visitMethodInsn(INVOKESPECIAL, FMLJMLC, "<init>", "(" + containerDesc + ")V", false);
        ci.visitFieldInsn(PUTSTATIC, FMLJMLC, "INSTANCE", selfDesc);
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PRIVATE, "<init>", "(" + containerDesc + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, FMLJMLC, "container", containerDesc);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor g = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()" + selfDesc, null, null);
        g.visitCode();
        g.visitFieldInsn(GETSTATIC, FMLJMLC, "INSTANCE", selfDesc);
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        // retromod$of(container): the context Forge 47.2 handed a @Mod constructor, bound to that mod.
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, CONTEXT_OF, CONTEXT_OF_DESC, null, null);
        of.visitCode();
        of.visitTypeInsn(NEW, FMLJMLC);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKESPECIAL, FMLJMLC, "<init>", "(" + containerDesc + ")V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();

        // retromod$container(): the bound container, or the one FML marks active while a mod constructs.
        MethodVisitor own = cw.visitMethod(ACC_PRIVATE, "retromod$container", "()" + containerDesc, null, null);
        own.visitCode();
        own.visitVarInsn(ALOAD, 0);
        own.visitFieldInsn(GETFIELD, FMLJMLC, "container", containerDesc);
        own.visitInsn(DUP);
        Label bound = new Label();
        own.visitJumpInsn(IFNONNULL, bound);
        own.visitInsn(POP);
        own.visitMethodInsn(INVOKESTATIC, LOADING_CONTEXT, "get", "()L" + LOADING_CONTEXT + ";", false);
        own.visitMethodInsn(INVOKEVIRTUAL, LOADING_CONTEXT, "getActiveContainer", "()" + containerDesc, false);
        own.visitLabel(bound);
        own.visitInsn(ARETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();

        emitContainerCall(cw, "getModEventBus", "()Lnet/neoforged/bus/api/IEventBus;", "getEventBus");
        // Forge's context inherited these from ModLoadingContext; NeoForge keeps them on the container.
        emitContainerCall(cw, "getActiveContainer", "()" + containerDesc, null);
        emitContainerCall(cw, "getContainer", "()" + containerDesc, null);
        // Forge 1.21's getContainer() returned the java mod container, which NeoForge names FMLModContainer too.
        String fmlContainer = NeoForgeModConstructorArgs.FML_MOD_CONTAINER;
        MethodVisitor typed = cw.visitMethod(ACC_PUBLIC, "getContainer", "()L" + fmlContainer + ";", null, null);
        typed.visitCode();
        typed.visitVarInsn(ALOAD, 0);
        typed.visitMethodInsn(INVOKESPECIAL, FMLJMLC, "retromod$container", "()" + containerDesc, false);
        typed.visitTypeInsn(CHECKCAST, fmlContainer);
        typed.visitInsn(ARETURN);
        typed.visitMaxs(0, 0);
        typed.visitEnd();
        emitContainerCall(cw, "getActiveNamespace", "()Ljava/lang/String;", "getNamespace");
        String type = "Lnet/neoforged/fml/config/ModConfig$Type;";
        String spec = "Lnet/neoforged/fml/config/IConfigSpec;";
        emitContainerCall(cw, "registerConfig", "(" + type + spec + ")V", "registerConfig");
        emitContainerCall(cw, "registerConfig", "(" + type + spec + "Ljava/lang/String;)V", "registerConfig");
        emitContainerCall(cw, "registerExtensionPoint", "(Ljava/lang/Class;Ljava/util/function/Supplier;)V",
                "registerExtensionPoint");

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code name(args)}: the same call on {@code retromod$container()}, or the container itself when {@code target} is null. */
    private static void emitContainerCall(ClassWriter cw, String name, String desc, String target) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, FMLJMLC, "retromod$container", "()L" + MOD_CONTAINER + ";", false);
        if (target != null) {
            int slot = 1;
            for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(desc)) {
                m.visitVarInsn(arg.getOpcode(ILOAD), slot);
                slot += arg.getSize();
            }
            m.visitMethodInsn(INVOKEVIRTUAL, MOD_CONTAINER, target, desc, false);
        }
        m.visitInsn(org.objectweb.asm.Type.getReturnType(desc).getOpcode(IRETURN));
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** The Forge build ForgeVersion reports: the newest Forge 1.20.1 build checked, whose API Retromod rebuilds on NeoForge. */
    static final String FORGE_API_VERSION = "47.4.26";

    /** Forge's version class on NeoForge, answering with the Forge build whose API the mod sees. */
    static byte[] generateForgeVersion() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC, FORGE_VERSION, null, "java/lang/Object", null);
        // Mods compare this with the Forge build they need and stop loading below it, as Meulti's
        // Backrooms does, so NeoForge's own version number would turn them off.
        for (String name : new String[]{"getVersion", "getTarget"}) {
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()Ljava/lang/String;", null, null);
            m.visitCode();
            m.visitLdcInsn(FORGE_API_VERSION);
            m.visitInsn(ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forge's MCPVersion on NeoForge; the Minecraft version is the minecraft mod's version. */
    static byte[] generateMcpVersion() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC, MCP_VERSION, null, "java/lang/Object", null);
        emitVersionOf(cw, "getMCVersion", "minecraft");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static String name()}: the loaded mod {@code modId}'s version, or an empty string when FML has no such mod. */
    private static void emitVersionOf(ClassWriter cw, String name, String modId) {
        String modList = "net/neoforged/fml/ModList";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, modList, "get", "()L" + modList + ";", false);
        m.visitLdcInsn(modId);
        m.visitMethodInsn(INVOKEVIRTUAL, modList, "getModContainerById", "(Ljava/lang/String;)Ljava/util/Optional;", false);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
        Label absent = new Label();
        m.visitJumpInsn(IFEQ, absent);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, MOD_CONTAINER);
        m.visitMethodInsn(INVOKEVIRTUAL, MOD_CONTAINER, "getModInfo",
                "()Lnet/neoforged/neoforgespi/language/IModInfo;", false);
        m.visitMethodInsn(INVOKEINTERFACE, "net/neoforged/neoforgespi/language/IModInfo", "getVersion",
                "()Lorg/apache/maven/artifact/versioning/ArtifactVersion;", true);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        m.visitInsn(ARETURN);
        m.visitLabel(absent);
        m.visitInsn(POP);
        m.visitLdcInsn("");
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
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
