/*
 * Retromod: ASM synthetic that bridges the Forge DeferredRegister(Supplier) pattern onto MC 26.x's
 * "Block/Item id must be set on Properties before construction" requirement.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/**
 * MC 1.21.3+ made {@code BlockBehaviour.<init>}/{@code Item.<init>} call
 * {@code Objects.requireNonNull(properties.id, "Block/Item id not set")}: a block/item can no longer
 * be constructed without its registry id stamped on its {@code Properties}. NeoForge's TYPED
 * {@code DeferredRegister.Blocks/Items} set that id, but only through the {@code Function<Properties,T>}
 * register overloads; the plain {@code register(String, Supplier<T>)} that Forge 1.20.1 mods use builds
 * the object inside the supplier with no id, so it dies at {@code RegisterEvent} with
 * {@code NullPointerException: Block id not set} (Macaw's on NeoForge 26.2, ROADMAP #87).
 *
 * <p>This synthetic threads the id through a {@link ThreadLocal}:
 * <ul>
 *   <li>{@code register(dr, name, supplier)} (the devirtualized target of the mod's
 *       {@code DeferredRegister.register(String, Supplier)}) registers via the id-AWARE
 *       {@code register(String, Function<Identifier,T>)} overload, and its {@code apply} sets the
 *       thread-local {@code ResourceKey} (built from the register's own registry key + the entry id)
 *       around the supplier call.</li>
 *   <li>The block/item {@code Properties} factories the mod uses ({@code Properties.of/ofFullCopy/
 *       ofLegacyCopy}, {@code new Item.Properties()}) are redirected to the {@code block*}/{@code
 *       itemProps} helpers, which stamp {@code setId(threadLocalKey)} the moment the Properties is
 *       created (inside the supplier, so the thread-local is live).</li>
 * </ul>
 * One class implements {@code Function} AND carries the static helpers, so only a single synthetic is
 * embedded per mod (via {@link com.retromod.core.SyntheticEmbedder}); it references only itself and
 * runtime NeoForge/MC classes.
 */
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
    /** The 1.20.5 to 1.21.11 biome recorder, beside {@link #INTERNAL} in the same embedded package. */
    public static final String BIOME_RECORDER = "com/retromod/shim/forge/embedded/LegacyBiomeRecorder";

    /** A ClassWriter that never needs the (absent) MC/NeoForge class hierarchy for frame computation. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    public static byte[] generate() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, INTERNAL, null, "java/lang/Object",
                new String[]{FUNCTION});

        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "CURRENT", L_TL, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, LEGACY_BIOMES, "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, LEGACY_PAINTINGS, "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "sup", L_SUP, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "key", L_RK, null, null).visitEnd();

        // static { CURRENT = new ThreadLocal(); }
        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitTypeInsn(NEW, THREADLOCAL);
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        ci.visitFieldInsn(PUTSTATIC, INTERNAL, "CURRENT", L_TL);
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

        // Object apply(Object id): CURRENT.set(ResourceKey.create(this.key, (Identifier) id));
        //                          Object r = this.sup.get(); CURRENT.remove(); return r;
        // (thread-local leaks only if the supplier throws; the registration has already failed then.)
        MethodVisitor a = cw.visitMethod(ACC_PUBLIC, "apply", "(" + L_OBJ + ")" + L_OBJ, null, null);
        a.visitCode();
        a.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", L_TL);
        a.visitVarInsn(ALOAD, 0);
        a.visitFieldInsn(GETFIELD, INTERNAL, "key", L_RK);
        a.visitVarInsn(ALOAD, 1);
        a.visitTypeInsn(CHECKCAST, IDENTIFIER);
        a.visitMethodInsn(INVOKESTATIC, RK, "create", "(" + L_RK + L_ID + ")" + L_RK, false);
        a.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(" + L_OBJ + ")V", false);
        a.visitVarInsn(ALOAD, 0);
        a.visitFieldInsn(GETFIELD, INTERNAL, "sup", L_SUP);
        a.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()" + L_OBJ, true);
        a.visitVarInsn(ASTORE, 2);
        a.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", L_TL);
        a.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        a.visitVarInsn(ALOAD, 2);
        a.visitInsn(ARETURN);
        a.visitMaxs(0, 0);
        a.visitEnd();

        // static DeferredHolder register(DeferredRegister dr, String name, Supplier sup):
        //     DeferredHolder h = dr.register(name, new RegistryIdBridge(sup, dr.getRegistryKey()));
        //     if (dr.getRegistryKey().equals(Registries.BIOME)) { LEGACY_BIOMES.put(h.getId().toString(), sup); noteLegacyBiome(); }
        //     return h;
        MethodVisitor r = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", REGISTER_DESC, null, null);
        r.visitCode();
        emitEnchantmentHolder(r);
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

        // static Properties applyBlock(Properties p): if (CURRENT.get()!=null) p.setId((ResourceKey)k); return p;
        emitApply(cw, INTERNAL, "applyBlock", BBP, L_BBP);
        emitApply(cw, INTERNAL, "applyItem", IP, L_IP);

        // block Properties factories -> applyBlock(<original>())
        emitBlockFactory(cw, INTERNAL, "blockOf", "of", "()" + L_BBP);
        emitBlockFactory(cw, INTERNAL, "blockOfFullCopy", "ofFullCopy", "(" + L_BB + ")" + L_BBP);
        emitBlockFactory(cw, INTERNAL, "blockOfLegacyCopy", "ofLegacyCopy", "(" + L_BB + ")" + L_BBP);

        emitItemProps(cw, INTERNAL);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The bridge for NeoForge 1.20.5 to 1.21.11, which need no registry id on properties but also
     * never build a data-driven registry's entries: {@code register} passes the call through and
     * keeps biome suppliers for the legacy worldgen store, which reads them by this class's name.
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
        //     DeferredHolder h = dr.register(name, sup);
        //     if (dr.getRegistryKey().equals(Registries.BIOME)) { LEGACY_BIOMES.put(dr.getNamespace() + ":" + name, sup); noteLegacyBiome(); }
        //     return h;
        MethodVisitor r = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", REGISTER_DESC, null, null);
        r.visitCode();
        if (enchantments) emitEnchantmentHolder(r);
        r.visitVarInsn(ALOAD, 0);
        r.visitVarInsn(ALOAD, 1);
        r.visitVarInsn(ALOAD, 2);
        r.visitMethodInsn(INVOKEVIRTUAL, DR, "register", "(Ljava/lang/String;" + L_SUP + ")" + L_DH, false);
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
        cw.visitEnd();
        return cw.toByteArray();
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

    /** In {@code register}, with the holder in local 3: remember a biome's or painting's supplier by its id. */
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

    /** Register the Forge-variant synthetic + redirects (call from the Forge 26.2 chain only). */
    public static void registerForgeRedirects(com.retromod.core.RetromodTransformer t) {
        t.registerSyntheticClass(FORGE_INTERNAL, generateForge());
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
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "sup", L_SUP, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "ro", L_FRO, null, null).visitEnd();

        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitTypeInsn(NEW, THREADLOCAL);
        ci.visitInsn(DUP);
        ci.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        ci.visitFieldInsn(PUTSTATIC, FORGE_INTERNAL, "CURRENT", L_TL);
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

        // Object get(): CURRENT.set(ro.getKey()); Object r = sup.get(); CURRENT.remove(); return r;
        MethodVisitor g = cw.visitMethod(ACC_PUBLIC, "get", "()" + L_OBJ, null, null);
        g.visitCode();
        g.visitFieldInsn(GETSTATIC, FORGE_INTERNAL, "CURRENT", L_TL);
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, FORGE_INTERNAL, "ro", L_FRO);
        g.visitMethodInsn(INVOKEVIRTUAL, FRO, "getKey", "()" + L_RK, false);
        g.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(" + L_OBJ + ")V", false);
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, FORGE_INTERNAL, "sup", L_SUP);
        g.visitMethodInsn(INVOKEINTERFACE, SUPPLIER, "get", "()" + L_OBJ, true);
        g.visitVarInsn(ASTORE, 1);
        g.visitFieldInsn(GETSTATIC, FORGE_INTERNAL, "CURRENT", L_TL);
        g.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        g.visitVarInsn(ALOAD, 1);
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        // static RegistryObject registerForge(DeferredRegister dr, String name, Supplier sup):
        //   w = new ForgeRegistryIdBridge(sup); ro = dr.register(name, w); w.ro = ro; return ro;
        MethodVisitor r = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerForge",
                FORGE_REGISTER_DESC, null, null);
        r.visitCode();
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

        emitApply(cw, FORGE_INTERNAL, "applyBlock", BBP, L_BBP);
        emitApply(cw, FORGE_INTERNAL, "applyItem", IP, L_IP);
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

    /** static P applyX(P p): P k=(P)CURRENT.get(); if (k!=null) p.setId(k); return p; */
    private static void emitApply(ClassWriter cw, String owner, String name, String propsOwner, String lProps) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + lProps + ")" + lProps, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);                 // props
        m.visitFieldInsn(GETSTATIC, owner, "CURRENT", L_TL);
        m.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "get", "()" + L_OBJ, false);
        m.visitInsn(DUP);
        Label nullLabel = new Label();
        m.visitJumpInsn(IFNULL, nullLabel);       // stack: props, keyObj
        m.visitTypeInsn(CHECKCAST, RK);           // stack: props, key
        m.visitMethodInsn(INVOKEVIRTUAL, propsOwner, "setId", "(" + L_RK + ")" + lProps, false);
        m.visitInsn(ARETURN);                     // returns props (setId returns this)
        m.visitLabel(nullLabel);                  // stack: props, null
        m.visitInsn(POP);
        m.visitInsn(ARETURN);                     // returns props
        m.visitMaxs(0, 0);
        m.visitEnd();
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
