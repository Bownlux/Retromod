/*
 * Retromod: ASM synthetic that bridges the Forge DeferredRegister(Supplier) pattern onto MC 26.x's
 * "Block/Item id must be set on Properties before construction" requirement.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/** Generates the bridge that sets a block or item's registry id before Minecraft builds it. */
public final class RegistryIdBridgeSynthetic {

    private RegistryIdBridgeSynthetic() {}

    public static final String INTERNAL = "com/retromod/shim/forge/embedded/RegistryIdBridge";

    private static final String FUNCTION = "java/util/function/Function";
    private static final String SUPPLIER = "java/util/function/Supplier";
    private static final String THREADLOCAL = "java/lang/ThreadLocal";
    private static final String RK = "net/minecraft/resources/ResourceKey";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String DR = "net/neoforged/neoforge/registries/DeferredRegister";
    private static final String DH = "net/neoforged/neoforge/registries/DeferredHolder";
    private static final String BB = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String BBP = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String IP = "net/minecraft/world/item/Item$Properties";

    private static final String L_RK = "L" + RK + ";";
    private static final String L_ID = "L" + IDENTIFIER + ";";
    private static final String L_DR = "L" + DR + ";";
    private static final String L_DH = "L" + DH + ";";
    private static final String L_BB = "L" + BB + ";";
    private static final String L_BBP = "L" + BBP + ";";
    private static final String L_IP = "L" + IP + ";";
    private static final String L_SUP = "L" + SUPPLIER + ";";
    private static final String L_TL = "L" + THREADLOCAL + ";";
    private static final String L_OBJ = "Ljava/lang/Object;";

    /**
     * The objects a supplier may return after its own {@code Registry.register} of the entry it
     * builds was handed back instead of registered (see {@code LateVanillaRegistration}), as an
     * {@code Object[]}, checked when the supplier returns.
     */
    public static final String HANDED = "HANDED";

    /** Flag to stamp blocks and items in registered static fields through {@link #INTERNAL}. */
    public static final String STATIC_FIELDS_FLAG = "registry-id-static-fields";
    /** The same flag for {@link #FORGE_INTERNAL} on a Forge host. */
    public static final String FORGE_STATIC_FIELDS_FLAG = "registry-id-static-fields-forge";

    // Redirect targets (all on INTERNAL): the mod's calls are rewritten to these.
    public static final String REGISTER_DESC = "(" + L_DR + "Ljava/lang/String;" + L_SUP + ")" + L_DH;
    public static final String BLOCK_OF_DESC = "()" + L_BBP;
    public static final String BLOCK_COPY_DESC = "(" + L_BB + ")" + L_BBP;
    public static final String ITEM_PROPS_DESC = "()" + L_IP;

    /**
     * Biome suppliers the mod registered, by id. NeoForge never fills a data-driven registry from a
     * {@code DeferredRegister}, so a pre-1.19.3 mod's code-built biome would never be created. The
     * legacy worldgen store in the same embedded package reads this map to write those biomes into
     * its data pack.
     */
    public static final String LEGACY_BIOMES = "LEGACY_BIOMES";
    /** Painting variant suppliers, which 1.21 also made data, kept the same way for the same pack. */
    public static final String LEGACY_PAINTINGS = "LEGACY_PAINTINGS";
    /** Converts an old mod's codec for a registry that holds map codecs; every register wrapper calls it. */
    public static final String LOOT_MODIFIER_CODECS = "com/retromod/shim/forge/embedded/LegacyLootModifierCodecs";
    /** The 1.20.5 to 1.21.10 biome recorder, beside {@link #INTERNAL} in the same embedded package. */
    public static final String BIOME_RECORDER = "com/retromod/shim/forge/embedded/LegacyBiomeRecorder";

    /**
     * Registers the embedded classes the register wrappers call, so the embedder copies them in
     * beside {@link #INTERNAL} or {@link #BIOME_RECORDER}.
     */
    public static void registerWrapperHelpers(com.retromod.core.RetromodTransformer transformer) {
        com.retromod.shim.common.LegacyRegistryMapCodecBridge.registerClasses(transformer);
    }

    /** System property that holds the per-thread key slot every embedded NeoForge and Forge bridge copy shares. */
    public static final String SHARED_CURRENT_KEY = "retromod.shared.registry-id.current.v1";
    /** System property that holds the shared per-thread slot for {@link #HANDED}. */
    public static final String SHARED_HANDED_KEY = "retromod.shared.registry-id.handed.v1";

    /** Sets a bridge's {@code CURRENT} and {@code HANDED} to the slots every copy shares, in its static initializer. */
    static void emitSharedSlots(MethodVisitor ci, String owner) {
        // Each translated jar embeds its own bridge copy. An addon's supplier often builds a
        // library's item, whose constructor reads the library's copy, so the key must sit in a slot
        // both copies reach without a shared class: the JDK's system properties, as on Fabric (#249).
        emitSharedThreadLocal(ci, owner, "CURRENT", SHARED_CURRENT_KEY);
        // A library's own Registry.register hands its object back to the copy that runs the supplier.
        // SAVED stays per copy, because an initializer's isolate and restore always call the same copy.
        emitSharedThreadLocal(ci, owner, HANDED, SHARED_HANDED_KEY);
    }

    /** {@code owner.field = } the ThreadLocal under {@code key}, or a private one when the key holds something else. */
    private static void emitSharedThreadLocal(MethodVisitor ci, String owner, String field, String key) {
        // System.getProperties().putIfAbsent(key, new ThreadLocal());
        ci.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        ci.visitLdcInsn(key);
        ci.visitTypeInsn(NEW, THREADLOCAL);
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        ci.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "putIfAbsent", "(" + L_OBJ + L_OBJ + ")" + L_OBJ, false);
        ci.visitInsn(POP);
        // Something else under the key leaves this copy with its own slot, the behavior before sharing.
        Label shared = new Label();
        ci.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        ci.visitLdcInsn(key);
        ci.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "get", "(" + L_OBJ + ")" + L_OBJ, false);
        ci.visitInsn(DUP);
        ci.visitTypeInsn(INSTANCEOF, THREADLOCAL);
        ci.visitJumpInsn(IFNE, shared);
        ci.visitInsn(POP);
        ci.visitTypeInsn(NEW, THREADLOCAL);
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        ci.visitLabel(shared);
        ci.visitTypeInsn(CHECKCAST, THREADLOCAL);
        ci.visitFieldInsn(PUTSTATIC, owner, field, L_TL);
    }

    /**
     * Runs {@code this.sup} with CURRENT holding the key in {@code keyLocal} and stores its result
     * in {@code resultLocal}. CURRENT and HANDED are cleared even when the supplier throws, so a
     * stale key cannot make a later registration on this thread look like the entry being built.
     */
    static void emitSupplierCall(MethodVisitor mv, String owner, int keyLocal, int resultLocal,
            int throwableLocal) {
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(start, end, failed, null);
        mv.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        mv.visitVarInsn(ALOAD, keyLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(" + L_OBJ + ")V", false);
        mv.visitLabel(start);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, owner, "sup", L_SUP);
        mv.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()" + L_OBJ, true);
        mv.visitVarInsn(ASTORE, resultLocal);
        mv.visitLabel(end);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(failed);
        mv.visitVarInsn(ASTORE, throwableLocal);
        mv.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        mv.visitFieldInsn(GETSTATIC, owner, HANDED, L_TL);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        mv.visitVarInsn(ALOAD, throwableLocal);
        mv.visitInsn(ATHROW);
        mv.visitLabel(done);
        mv.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        mv.visitVarInsn(ALOAD, resultLocal);
        mv.visitVarInsn(ALOAD, keyLocal);
        mv.visitMethodInsn(INVOKESTATIC, owner, "checkHanded", "(" + L_OBJ + L_OBJ + ")" + L_OBJ, false);
        mv.visitVarInsn(ASTORE, resultLocal);
    }

    /**
     * {@code static Object checkHanded(Object returned, Object key)}: a supplier that registered one
     * object itself and returns another would leave the registry holding something else, so it
     * fails, as the duplicate entry did on Forge 1.20.1.
     */
    static void emitCheckHanded(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "checkHanded",
                "(" + L_OBJ + L_OBJ + ")" + L_OBJ, null, null);
        mv.visitCode();
        Label same = new Label();
        Label next = new Label();
        Label scan = new Label();
        mv.visitFieldInsn(GETSTATIC, owner, HANDED, L_TL);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "get", "()" + L_OBJ, false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitFieldInsn(GETSTATIC, owner, HANDED, L_TL);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNULL, same);
        // Any of the accepted objects passes; locals: 2 the array, 3 the index.
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitLabel(scan);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;");
        mv.visitInsn(ARRAYLENGTH);
        mv.visitJumpInsn(IF_ICMPGE, next);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;");
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitJumpInsn(IF_ACMPEQ, same);
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(GOTO, scan);
        mv.visitLabel(next);
        mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        mv.visitInsn(DUP);
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("The deferred register supplier for ");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(" + L_OBJ + ")Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn(" registered one object and returned a different one. The id can hold only one;"
                + " report this with the mod and its version.");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(same);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A ClassWriter that never needs the (absent) MC/NeoForge class hierarchy for frame computation. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    public static byte[] generate() {
        // One class is the Function and carries the static helpers, so each mod embeds a single
        // synthetic (through SyntheticEmbedder) that references only itself, NeoForge, and Minecraft.
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object",
                new String[]{FUNCTION});

        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "CURRENT", L_TL, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, HANDED, L_TL, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, LEGACY_BIOMES, "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, LEGACY_PAINTINGS, "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "sup", L_SUP, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "key", L_RK, null, null).visitEnd();

        // static { CURRENT and HANDED from the shared slots; }
        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        emitSharedSlots(ci, INTERNAL);
        // SAVED holds the ids an isolated static initializer sets aside.
        com.retromod.shim.common.StaticFieldIdStamper.emitSavedInit(ci, INTERNAL);
        ci.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        ci.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                "(Ljava/util/Map;)Ljava/util/Map;", false);
        ci.visitFieldInsn(PUTSTATIC, INTERNAL, LEGACY_BIOMES, "Ljava/util/Map;");
        ci.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        ci.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                "(Ljava/util/Map;)Ljava/util/Map;", false);
        ci.visitFieldInsn(PUTSTATIC, INTERNAL, LEGACY_PAINTINGS, "Ljava/util/Map;");
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        // RegistryIdBridge(Supplier sup, ResourceKey key)
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_SUP + L_RK + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, INTERNAL, "sup", L_SUP);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 2);
        c.visitFieldInsn(PUTFIELD, INTERNAL, "key", L_RK);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // Object apply(Object id): runs this.sup with CURRENT holding ResourceKey.create(this.key, id).
        MethodVisitor a = cw.visitMethod(ACC_PUBLIC, "apply", "(" + L_OBJ + ")" + L_OBJ, null, null);
        a.visitCode();
        a.visitVarInsn(ALOAD, 0);
        a.visitFieldInsn(GETFIELD, INTERNAL, "key", L_RK);
        a.visitVarInsn(ALOAD, 1);
        a.visitTypeInsn(CHECKCAST, IDENTIFIER);
        a.visitMethodInsn(INVOKESTATIC, RK, "create", "(" + L_RK + L_ID + ")" + L_RK, false);
        a.visitVarInsn(ASTORE, 3);
        emitSupplierCall(a, INTERNAL, 3, 2, 4);
        // From 26.1 a 1.21 recipe serializer is no longer a RecipeSerializer, so the registry takes
        // it adapted, as Registry.register does. Before 26.1 it still is one and stays as it is.
        Label registered = new Label();
        a.visitVarInsn(ALOAD, 0);
        a.visitFieldInsn(GETFIELD, INTERNAL, "key", L_RK);
        a.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "RECIPE_SERIALIZER", L_RK);
        a.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(" + L_OBJ + ")Z", false);
        a.visitJumpInsn(IFEQ, registered);
        a.visitVarInsn(ALOAD, 2);
        a.visitTypeInsn(INSTANCEOF, "net/minecraft/world/item/crafting/RecipeSerializer");
        a.visitJumpInsn(IFNE, registered);
        a.visitVarInsn(ALOAD, 2);
        a.visitMethodInsn(INVOKESTATIC, com.retromod.shim.common.LegacyRecipeSerializerShim.BRIDGE, "adapt",
                "(" + L_OBJ + ")" + L_OBJ, false);
        a.visitVarInsn(ASTORE, 2);
        a.visitLabel(registered);
        a.visitVarInsn(ALOAD, 2);
        a.visitInsn(ARETURN);
        a.visitMaxs(0, 0);
        a.visitEnd();

        // 1.21.3 and newer refuse to build a block or item whose Properties has no id. NeoForge's
        // typed registers set it only through their Function overloads, so the plain
        // register(String, Supplier) that Forge 1.20.1 mods call died at RegisterEvent with
        // "Block id not set" (Macaw's on NeoForge 26.2, ROADMAP #87). The mod's call is
        // redirected here and goes through the id-aware overload, whose apply sets CURRENT.
        // static DeferredHolder register(DeferredRegister dr, String name, Supplier sup):
        //     DeferredHolder h = dr.register(name, new RegistryIdBridge(sup, dr.getRegistryKey()));
        //     if (dr.getRegistryKey().equals(Registries.BIOME)) { LEGACY_BIOMES.put(h.getId().toString(), sup); noteLegacyBiome(); }
        //     return h;
        MethodVisitor r = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", REGISTER_DESC, null, null);
        r.visitCode();
        emitEnchantmentHolder(r);
        emitLootModifierSerializer(r);
        r.visitVarInsn(ALOAD, 0);                 // dr
        r.visitVarInsn(ALOAD, 1);                 // name
        r.visitTypeInsn(NEW, INTERNAL);
        r.visitInsn(DUP);
        r.visitVarInsn(ALOAD, 2);                 // sup
        r.visitVarInsn(ALOAD, 0);                 // dr
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "getRegistryKey", "()" + L_RK, false);
        r.visitMethodInsn(INVOKESPECIAL, INTERNAL, "<init>", "(" + L_SUP + L_RK + ")V", false);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "register",
                "(Ljava/lang/String;L" + FUNCTION + ";)" + L_DH, false);
        r.visitVarInsn(ASTORE, 3);
        emitRecordLegacyBiome(r);
        r.visitVarInsn(ALOAD, 3);
        r.visitInsn(ARETURN);
        r.visitMaxs(0, 0);
        r.visitEnd();
        emitLegacyBiomes(cw, INTERNAL);

        // The mod's Properties factories are redirected to helpers that set the id the moment the
        // Properties is made, which happens inside the supplier while CURRENT holds the key.
        emitApply(cw, INTERNAL, "applyBlock", BBP, L_BBP, "BLOCK");
        emitApply(cw, INTERNAL, "applyItem", IP, L_IP, "ITEM");
        // A block or item stored in a registered static field gets its id through these instead.
        emitEnterLeave(cw, INTERNAL);
        emitCheckHanded(cw, INTERNAL);
        com.retromod.shim.common.StaticFieldIdStamper.emitIsolation(cw, INTERNAL);

        // block Properties factories -> applyBlock(<original>())
        emitBlockFactory(cw, INTERNAL, "blockOf", "of", "()" + L_BBP);
        emitBlockFactory(cw, INTERNAL, "blockOfFullCopy", "ofFullCopy", "(" + L_BB + ")" + L_BBP);
        emitBlockFactory(cw, INTERNAL, "blockOfLegacyCopy", "ofLegacyCopy", "(" + L_BB + ")" + L_BBP);

        emitItemProps(cw, INTERNAL);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The bridge for NeoForge 1.20.5 to 1.21.10, which never build a data-driven registry's entries:
     * {@code register} passes the call through and keeps biome suppliers for the legacy worldgen
     * store, which reads them by this class's name. It stamps no registry id: 1.21.2 to 1.21.10 need
     * one, but the full bridge names {@code Identifier}, which only 1.21.11 and newer have.
     */
    public static byte[] generateBiomeRecorder() {
        return generateBiomeRecorder(false);
    }

    /**
     * @param enchantments whether the host is 1.21 or newer, where enchantments became data and a
     *                     registration has to build and hold the mod's enchantment itself
     */
    public static byte[] generateBiomeRecorder(boolean enchantments) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, BIOME_RECORDER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, LEGACY_BIOMES, "Ljava/util/Map;", null, null).visitEnd();
        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        ci.visitMethodInsn(INVOKESTATIC, "java/util/Collections", "synchronizedMap", "(Ljava/util/Map;)Ljava/util/Map;", false);
        ci.visitFieldInsn(PUTSTATIC, BIOME_RECORDER, LEGACY_BIOMES, "Ljava/util/Map;");
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        // static DeferredHolder register(DeferredRegister dr, String name, Supplier sup):
        //     DeferredHolder h = dr.register(name, id -> sup.get());
        //     if (dr.getRegistryKey().equals(Registries.BIOME)) { LEGACY_BIOMES.put(dr.getNamespace() + ":" + name, sup); noteLegacyBiome(); }
        //     return h;
        MethodVisitor r = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", REGISTER_DESC, null, null);
        r.visitCode();
        if (enchantments) emitEnchantmentHolder(r);
        emitLootModifierSerializer(r);
        // Through the Function overload, as on 26.x. A mod's DeferredRegister subclass that overrides
        // the Supplier overload and calls super lands here, and a virtual call back to that overload
        // runs the override again until the stack overflows (ProjectE on NeoForge 1.21.11).
        r.visitVarInsn(ALOAD, 0);
        r.visitVarInsn(ALOAD, 1);
        r.visitVarInsn(ALOAD, 2);
        Type objectToObject = Type.getMethodType("(" + L_OBJ + ")" + L_OBJ);
        r.visitInvokeDynamicInsn("apply", "(" + L_SUP + ")L" + FUNCTION + ";",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                objectToObject,
                new Handle(H_INVOKESTATIC, BIOME_RECORDER, "supply", "(" + L_SUP + L_OBJ + ")" + L_OBJ, false),
                objectToObject);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "register", "(Ljava/lang/String;L" + FUNCTION + ";)" + L_DH, false);
        r.visitVarInsn(ASTORE, 3);
        Label notBiome = new Label();
        r.visitVarInsn(ALOAD, 0);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "getRegistryKey", "()" + L_RK, false);
        r.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "BIOME", L_RK);
        r.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(" + L_OBJ + ")Z", false);
        r.visitJumpInsn(IFEQ, notBiome);
        r.visitFieldInsn(GETSTATIC, BIOME_RECORDER, LEGACY_BIOMES, "Ljava/util/Map;");
        r.visitVarInsn(ALOAD, 0);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "getNamespace", "()Ljava/lang/String;", false);
        r.visitLdcInsn(":");
        r.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        r.visitVarInsn(ALOAD, 1);
        r.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        r.visitVarInsn(ALOAD, 2);
        r.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(" + L_OBJ + L_OBJ + ")" + L_OBJ, true);
        r.visitInsn(POP);
        r.visitMethodInsn(INVOKESTATIC, BIOME_RECORDER, "noteLegacyBiome", "()V", false);
        r.visitLabel(notBiome);
        r.visitVarInsn(ALOAD, 3);
        r.visitInsn(ARETURN);
        r.visitMaxs(0, 0);
        r.visitEnd();
        emitLegacyBiomes(cw, BIOME_RECORDER);

        // private static Object supply(Supplier sup, Object id): the Function's body, sup.get().
        MethodVisitor supply = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "supply",
                "(" + L_SUP + L_OBJ + ")" + L_OBJ, null, null);
        supply.visitCode();
        supply.visitVarInsn(ALOAD, 0);
        supply.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()" + L_OBJ, true);
        supply.visitInsn(ARETURN);
        supply.visitMaxs(0, 0);
        supply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * At the start of {@code register}: {@code sup = LegacyRegistryMapCodec.serializer(dr.getRegistryKey(), sup)},
     * so an old codec reaches a registry of map codecs, such as a loot modifier serializer's, as one.
     */
    private static void emitLootModifierSerializer(MethodVisitor r) {
        emitLootModifierSerializer(r, DR);
    }

    private static void emitLootModifierSerializer(MethodVisitor r, String deferredRegister) {
        r.visitVarInsn(ALOAD, 0);
        r.visitMethodInsn(INVOKEVIRTUAL, deferredRegister, "getRegistryKey", "()" + L_RK, false);
        r.visitVarInsn(ALOAD, 2);
        r.visitMethodInsn(INVOKESTATIC, com.retromod.shim.common.LegacyRegistryMapCodecBridge.CLASS, "serializer",
                "(" + L_OBJ + L_SUP + ")" + L_SUP, false);
        r.visitVarInsn(ASTORE, 2);
    }

    /**
     * At the start of {@code register}: an enchantment register's entry is built now, recorded for
     * the legacy enchantment pack, and returned in a holder that answers with it, because NeoForge
     * never builds a data-driven registry's entries and the holder would stay unbound.
     */
    private static void emitEnchantmentHolder(MethodVisitor r) {
        Label notEnchantment = new Label();
        r.visitVarInsn(ALOAD, 0);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "getRegistryKey", "()" + L_RK, false);
        r.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "ENCHANTMENT", L_RK);
        r.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(" + L_OBJ + ")Z", false);
        r.visitJumpInsn(IFEQ, notEnchantment);
        r.visitVarInsn(ALOAD, 0);
        r.visitVarInsn(ALOAD, 1);
        r.visitVarInsn(ALOAD, 2);
        r.visitMethodInsn(INVOKESTATIC, com.retromod.shim.common.LegacyEnchantmentBridge.HOLDER, "of",
                "(" + L_DR + "Ljava/lang/String;" + L_SUP + ")" + L_DH, false);
        r.visitInsn(ARETURN);
        r.visitLabel(notEnchantment);
    }

    /** In {@code register}, with the holder in local 3: records a biome or painting supplier by id. */
    private static void emitRecordLegacyBiome(MethodVisitor r) {
        emitRecordLegacyEntry(r, "BIOME", LEGACY_BIOMES);
        emitRecordLegacyEntry(r, "PAINTING_VARIANT", LEGACY_PAINTINGS);
    }

    /** Remembers the supplier in {@code map} when the register is for the {@code Registries} field named. */
    private static void emitRecordLegacyEntry(MethodVisitor r, String registry, String map) {
        Label notBiome = new Label();
        r.visitVarInsn(ALOAD, 0);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "getRegistryKey", "()" + L_RK, false);
        r.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", registry, L_RK);
        r.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(" + L_OBJ + ")Z", false);
        r.visitJumpInsn(IFEQ, notBiome);
        r.visitFieldInsn(GETSTATIC, INTERNAL, map, "Ljava/util/Map;");
        r.visitVarInsn(ALOAD, 3);
        r.visitMethodInsn(INVOKEVIRTUAL, DH, "getId", "()" + L_ID, false);
        r.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        r.visitVarInsn(ALOAD, 2);
        r.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(" + L_OBJ + L_OBJ + ")" + L_OBJ, true);
        r.visitInsn(POP);
        r.visitMethodInsn(INVOKESTATIC, INTERNAL, "noteLegacyBiome", "()V", false);
        r.visitLabel(notBiome);
    }

    /**
     * {@code static void noteLegacyBiome()}: tells the legacy worldgen store, when this mod has one,
     * that the running mod builds biomes, so the store offers its pack. Looked up by name because
     * the store is embedded only into mods that use the old worldgen API.
     */
    private static void emitLegacyBiomes(ClassWriter cw, String owner) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "noteLegacyBiome", "()V", null, null);
        m.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label(), done = new Label();
        m.visitTryCatchBlock(start, end, handler, "java/lang/Exception");
        m.visitTryCatchBlock(start, end, handler, "java/lang/LinkageError");
        m.visitLabel(start);
        m.visitLdcInsn(Type.getObjectType(owner));
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getPackageName", "()Ljava/lang/String;", false);
        m.visitLdcInsn(".LegacyWorldgenStore");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitInsn(ICONST_1);
        m.visitLdcInsn(Type.getObjectType(owner));
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        m.visitLdcInsn("noteBuildingMod");
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(" + L_OBJ + "[" + L_OBJ + ")" + L_OBJ, false);
        m.visitInsn(POP);
        m.visitLabel(end);
        m.visitJumpInsn(GOTO, done);
        m.visitLabel(handler);
        m.visitInsn(POP);          // no store in this mod: its biomes stay data-only, as before
        m.visitLabel(done);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    // Forge only kept register(String, Supplier), so the bridge wraps the supplier.
    // Once registration returns, the wrapper can read the full key from RegistryObject
    // and expose it while the original supplier creates the item or block.

    public static final String FORGE_INTERNAL = "com/retromod/shim/forge/embedded/ForgeRegistryIdBridge";

    private static final String FDR = "net/minecraftforge/registries/DeferredRegister";
    private static final String FRO = "net/minecraftforge/registries/RegistryObject";
    private static final String L_FDR = "L" + FDR + ";";
    private static final String L_FRO = "L" + FRO + ";";

    public static final String FORGE_REGISTER_DESC = "(" + L_FDR + "Ljava/lang/String;" + L_SUP + ")" + L_FRO;

    /** Registers the Forge bridge and its redirects; the Forge 1.21.11 and 26.1 shims call it. */
    public static void registerForgeRedirects(com.retromod.core.RetromodTransformer t) {
        t.registerSyntheticClass(FORGE_INTERNAL, generateForge());
        registerWrapperHelpers(t);
        // Also stamp blocks and items the mod stores in registered static fields.
        t.enablePostProcess(FORGE_STATIC_FIELDS_FLAG);
        final String B = FORGE_INTERNAL;
        t.registerMethodRedirect(FDR, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)" + L_FRO,
                B, "registerForge", FORGE_REGISTER_DESC, true);
        t.registerMethodRedirect(BBP_NAME, "of", "()" + L_BBP, B, "blockOf", "()" + L_BBP);
        t.registerMethodRedirect(BBP_NAME, "ofFullCopy", "(" + L_BB + ")" + L_BBP,
                B, "blockOfFullCopy", "(" + L_BB + ")" + L_BBP);
        t.registerMethodRedirect(BBP_NAME, "ofLegacyCopy", "(" + L_BB + ")" + L_BBP,
                B, "blockOfLegacyCopy", "(" + L_BB + ")" + L_BBP);
        t.registerConstructorRedirect(IP_NAME, "()V", B, "itemProps", "()" + L_IP);
    }

    private static final String BBP_NAME = BBP;
    private static final String IP_NAME = IP;

    public static byte[] generateForge() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, FORGE_INTERNAL, null, "java/lang/Object",
                new String[]{SUPPLIER});

        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "CURRENT", L_TL, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, HANDED, L_TL, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "sup", L_SUP, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "ro", L_FRO, null, null).visitEnd();

        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        emitSharedSlots(ci, FORGE_INTERNAL);
        // SAVED holds the ids an isolated static initializer sets aside.
        com.retromod.shim.common.StaticFieldIdStamper.emitSavedInit(ci, FORGE_INTERNAL);
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_SUP + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, FORGE_INTERNAL, "sup", L_SUP);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // Object get(): runs sup with CURRENT holding ro.getKey().
        MethodVisitor g = cw.visitMethod(ACC_PUBLIC, "get", "()" + L_OBJ, null, null);
        g.visitCode();
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, FORGE_INTERNAL, "ro", L_FRO);
        g.visitMethodInsn(INVOKEVIRTUAL, FRO, "getKey", "()" + L_RK, false);
        g.visitVarInsn(ASTORE, 2);
        emitSupplierCall(g, FORGE_INTERNAL, 2, 1, 3);
        // An old recipe serializer goes in adapted, as on NeoForge. Left raw, every recipe of its
        // type throws ClassCastException in the recipe reload and the world cannot load.
        Label registered = new Label();
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, FORGE_INTERNAL, "ro", L_FRO);
        g.visitMethodInsn(INVOKEVIRTUAL, FRO, "getKey", "()" + L_RK, false);
        g.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "RECIPE_SERIALIZER", L_RK);
        g.visitMethodInsn(INVOKEVIRTUAL, RK, "isFor", "(" + L_RK + ")Z", false);
        g.visitJumpInsn(IFEQ, registered);
        g.visitVarInsn(ALOAD, 1);
        g.visitTypeInsn(INSTANCEOF, "net/minecraft/world/item/crafting/RecipeSerializer");
        g.visitJumpInsn(IFNE, registered);
        g.visitVarInsn(ALOAD, 1);
        g.visitMethodInsn(INVOKESTATIC, com.retromod.shim.common.LegacyRecipeSerializerShim.BRIDGE, "adapt",
                "(" + L_OBJ + ")" + L_OBJ, false);
        g.visitVarInsn(ASTORE, 1);
        g.visitLabel(registered);
        g.visitVarInsn(ALOAD, 1);
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        // static RegistryObject registerForge(DeferredRegister dr, String name, Supplier sup):
        //   w = new ForgeRegistryIdBridge(sup); ro = dr.register(name, w); w.ro = ro; return ro;
        MethodVisitor r = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerForge",
                FORGE_REGISTER_DESC, null, null);
        r.visitCode();
        // A biome or loot modifier serializer from Forge 1.20.1 is a codec, and Forge 26.x reads map codecs.
        emitLootModifierSerializer(r, FDR);
        r.visitTypeInsn(NEW, FORGE_INTERNAL);
        r.visitInsn(DUP);
        r.visitVarInsn(ALOAD, 2);
        r.visitMethodInsn(INVOKESPECIAL, FORGE_INTERNAL, "<init>", "(" + L_SUP + ")V", false);
        r.visitVarInsn(ASTORE, 3);
        r.visitVarInsn(ALOAD, 0);
        r.visitVarInsn(ALOAD, 1);
        r.visitVarInsn(ALOAD, 3);
        r.visitMethodInsn(INVOKEVIRTUAL, FDR, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)" + L_FRO, false);
        r.visitVarInsn(ASTORE, 4);
        r.visitVarInsn(ALOAD, 3);
        r.visitVarInsn(ALOAD, 4);
        r.visitFieldInsn(PUTFIELD, FORGE_INTERNAL, "ro", L_FRO);
        r.visitVarInsn(ALOAD, 4);
        r.visitInsn(ARETURN);
        r.visitMaxs(0, 0);
        r.visitEnd();

        // The same Properties helpers and static field stamping as the NeoForge bridge.
        emitApply(cw, FORGE_INTERNAL, "applyBlock", BBP, L_BBP, "BLOCK");
        emitApply(cw, FORGE_INTERNAL, "applyItem", IP, L_IP, "ITEM");
        emitEnterLeave(cw, FORGE_INTERNAL);
        emitCheckHanded(cw, FORGE_INTERNAL);
        com.retromod.shim.common.StaticFieldIdStamper.emitIsolation(cw, FORGE_INTERNAL);
        emitBlockFactory(cw, FORGE_INTERNAL, "blockOf", "of", "()" + L_BBP);
        emitBlockFactory(cw, FORGE_INTERNAL, "blockOfFullCopy", "ofFullCopy", "(" + L_BB + ")" + L_BBP);
        emitBlockFactory(cw, FORGE_INTERNAL, "blockOfLegacyCopy", "ofLegacyCopy", "(" + L_BB + ")" + L_BBP);
        emitItemProps(cw, FORGE_INTERNAL);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** static Item.Properties itemProps(): return applyItem(new Item.Properties()); */
    private static void emitItemProps(ClassWriter cw, String owner) {
        MethodVisitor ip = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "itemProps", ITEM_PROPS_DESC, null, null);
        ip.visitCode();
        ip.visitTypeInsn(NEW, IP);
        ip.visitInsn(DUP);
        ip.visitMethodInsn(INVOKESPECIAL, IP, "<init>", "()V", false);
        ip.visitMethodInsn(INVOKESTATIC, owner, "applyItem", "(" + L_IP + ")" + L_IP, false);
        ip.visitInsn(ARETURN);
        ip.visitMaxs(0, 0);
        ip.visitEnd();
    }

    /** Emits {@code static P applyX(P p)}, which sets the current id on the Properties it is given. */
    private static void emitApply(ClassWriter cw, String owner, String name, String propsOwner, String lProps,
                                  String registriesField) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + lProps + ")" + lProps, null, null);
        m.visitCode();
        // Object k = CURRENT.get(); if (k == null) return p;
        m.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        m.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "get", "()" + L_OBJ, false);
        m.visitVarInsn(ASTORE, 1);
        Label present = new Label();
        m.visitVarInsn(ALOAD, 1);
        m.visitJumpInsn(IFNONNULL, present);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ARETURN);                     // no id: the props stay as they were
        m.visitLabel(present);
        // A deferred register sets a full key. A static field's stamp sets only the id, because
        // one class can fill block and item fields alike, so the registry comes from the kind of
        // Properties being built: if (k instanceof Identifier) k = ResourceKey.create(Registries.X, k);
        Label keyed = new Label();
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(INSTANCEOF, IDENTIFIER);
        m.visitJumpInsn(IFEQ, keyed);
        m.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", registriesField, L_RK);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, IDENTIFIER);
        m.visitMethodInsn(INVOKESTATIC, RK, "create", "(" + L_RK + L_ID + ")" + L_RK, false);
        m.visitVarInsn(ASTORE, 1);
        m.visitLabel(keyed);
        // return p.setId((ResourceKey) k);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, RK);
        m.visitMethodInsn(INVOKEVIRTUAL, propsOwner, "setId", "(" + L_RK + ")" + lProps, false);
        m.visitInsn(ARETURN);                     // setId returns the same props
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** Emits {@code enter} and {@code leave}, which set a static field's id while it is filled. */
    private static void emitEnterLeave(ClassWriter cw, String owner) {
        // static Object enter(Identifier id):
        //     Object previous = CURRENT.get(); CURRENT.set(id); return previous;
        // The old id is kept because the statement may run inside a deferred register's supplier,
        // which has its own key set, or start another class's static initializer.
        MethodVisitor enter = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "enter",
                com.retromod.shim.common.StaticFieldIdStamper.ENTER_DESC, null, null);
        enter.visitCode();
        enter.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        enter.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "get", "()" + L_OBJ, false);
        enter.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        enter.visitVarInsn(ALOAD, 0);
        enter.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(" + L_OBJ + ")V", false);
        enter.visitInsn(ARETURN);
        enter.visitMaxs(0, 0);
        enter.visitEnd();

        // static void leave(Object previous):
        //     if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        MethodVisitor leave = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "leave",
                com.retromod.shim.common.StaticFieldIdStamper.LEAVE_DESC, null, null);
        leave.visitCode();
        Label restore = new Label();
        leave.visitVarInsn(ALOAD, 0);
        leave.visitJumpInsn(IFNONNULL, restore);
        leave.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        leave.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        leave.visitInsn(RETURN);
        leave.visitLabel(restore);
        leave.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        leave.visitVarInsn(ALOAD, 0);
        leave.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(" + L_OBJ + ")V", false);
        leave.visitInsn(RETURN);
        leave.visitMaxs(0, 0);
        leave.visitEnd();
    }

    /** static Properties name(...): return applyBlock(BlockBehaviour$Properties.orig(...)); */
    private static void emitBlockFactory(ClassWriter cw, String owner, String name, String orig, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        // load args (0 or 1 BlockBehaviour) for the original factory
        if (desc.startsWith("(" + L_BB)) {
            m.visitVarInsn(ALOAD, 0);
        }
        m.visitMethodInsn(INVOKESTATIC, BBP, orig, desc, false);
        m.visitMethodInsn(INVOKESTATIC, owner, "applyBlock", "(" + L_BBP + ")" + L_BBP, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
