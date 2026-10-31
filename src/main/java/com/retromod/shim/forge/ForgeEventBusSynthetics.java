/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * Forge 26.2 EventBus 6 -> 7 bridge (#85): old IEventBus idiom onto BusGroup.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Forge 26.2 (65.x) replaced EventBus 6 with EventBus 7: {@code IEventBus} and the old {@code Event}
 * base are gone, replaced by {@code BusGroup} + per-type {@code EventBus<T>}. Every old Forge mod
 * dies at construction: {@code FMLJavaModLoadingContext.getModEventBus()} no longer exists (now
 * {@code getModBusGroup(): BusGroup}), {@code DeferredRegister.register} now takes a
 * {@code BusGroup}, and {@code MinecraftForge.EVENT_BUS} is now typed
 * {@code EventBusMigrationHelper} (so even the field read fails on the old descriptor). All
 * signatures verified against forge-26.2-65.0.0 + eventbus-7.0.1.
 *
 * <p>The bridge is a synthetic INTERFACE, {@code LegacyEventBus} (an interface so the mod's
 * {@code INVOKEINTERFACE IEventBus.*} call sites stay verifiable after the class redirect), with
 * default methods over an underlying {@code BusGroup}, plus an impl class carrying the group and
 * the {@code GAME_BUS} constant ({@code BusGroup.DEFAULT}, which backs Forge's own
 * {@code EventBusMigrationHelper.INSTANCE}):
 * <ul>
 *   <li>{@code getModEventBus()} is devirtualized to {@code LegacyEventBus.modBus(ctx)} =
 *       {@code of(ctx.getModBusGroup())}.</li>
 *   <li>{@code DeferredRegister.register(IEventBus)} is devirtualized to
 *       {@code registerDeferred(dr, bus)} = {@code dr.register(bus.group())} - so blocks/items
 *       register through Forge's own RegisterEvent machinery.</li>
 *   <li>{@code bus.register(listener)} routes to {@code group().register(MethodHandles.lookup(),
 *       listener)}. The lookup is taken INSIDE the bridge, which the embedder copies into the
 *       mod's own module, so it has the mod's access rights. The old {@code @SubscribeEvent}
 *       annotation is class-redirected to EventBus 7's (bare annotations carry no elements, so the
 *       rewrite is value-compatible; an explicit old-enum {@code priority} is a known limitation).</li>
 *   <li>{@code MinecraftForge.EVENT_BUS} is field-redirected to {@code LegacyEventBusImpl.GAME_BUS}.</li>
 *   <li>{@code addListener(Consumer)} cannot recover the event type from a lambda (EventBus 6 read
 *       it from the lambda's constant pool); for a Consumer class with reified generics it resolves
 *       the type's static {@code BUS} field, otherwise it warns and no-ops (STAGED: lifecycle-setup
 *       listeners are soft-failed, construction + registration is the acceptance bar).</li>
 * </ul>
 */
public final class ForgeEventBusSynthetics {

    private ForgeEventBusSynthetics() {}

    public static final String LEB = "com/retromod/shim/forge/embedded/LegacyEventBus";
    public static final String LEB_IMPL = "com/retromod/shim/forge/embedded/LegacyEventBusImpl";
    public static final String RC = "com/retromod/shim/forge/embedded/ReflectedConsumer";
    /** Stand-in for EventBus 6's deleted {@code EventPriority} enum (#101). */
    public static final String PRI = "com/retromod/shim/forge/embedded/LegacyEventPriority";

    private static final String OLD_IEVENTBUS = "net/minecraftforge/eventbus/api/IEventBus";
    private static final String OLD_PRIORITY = "net/minecraftforge/eventbus/api/EventPriority";
    /** EventBus 7's byte-constant priority holder (HIGHEST..MONITOR, verified in eventbus-7.0.1). */
    private static final String PRIORITY7 = "net/minecraftforge/eventbus/api/listener/Priority";
    private static final String OLD_SUBSCRIBE = "net/minecraftforge/eventbus/api/SubscribeEvent";
    private static final String NEW_SUBSCRIBE = "net/minecraftforge/eventbus/api/listener/SubscribeEvent";
    private static final String BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
    private static final String EVENT_BUS7 = "net/minecraftforge/eventbus/api/bus/EventBus";
    private static final String FMLJMLC = "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String DEFERRED_REG = "net/minecraftforge/registries/DeferredRegister";
    private static final String MINECRAFT_FORGE = "net/minecraftforge/common/MinecraftForge";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";

    private static final String L_LEB = "L" + LEB + ";";
    private static final String L_BG = "L" + BUS_GROUP + ";";
    private static final String L_OLD_BUS = "L" + OLD_IEVENTBUS + ";";
    private static final String L_CONSUMER = "Ljava/util/function/Consumer;";
    private static final String L_OBJ = "Ljava/lang/Object;";
    private static final String L_PRI = "L" + PRI + ";";
    private static final String L_CLASS = "Ljava/lang/Class;";
    private static final String L_LISTENER7 = "Lnet/minecraftforge/eventbus/api/listener/EventListener;";

    /** Set when the Forge 26.2 chain is active; gates {@link #stripLenientAutoSubscriber}. */
    private static volatile boolean active;

    /** Register the synthetics and every redirect that maps the EventBus 6 idiom onto 7. */
    public static void register(RetromodTransformer t) {
        active = true;
        t.registerSyntheticClass(LEB, generateInterface());
        t.registerSyntheticClass(LEB_IMPL, generateImpl());
        // referenced only by LEB_IMPL - reaches the mod via the embedder's transitive closure
        t.registerSyntheticClass(RC, generateReflectedConsumer());

        // The deleted types. ClassRemapper runs first, so the annotation rewrite also fixes the
        // @SubscribeEvent descriptors EventBus 7's scanner looks for.
        t.registerClassRedirect(OLD_IEVENTBUS, LEB);
        t.registerClassRedirect(OLD_SUBSCRIBE, NEW_SUBSCRIBE);

        // EventBus 6's EventPriority enum is gone on 7 (priorities are Priority's byte constants
        // now). A real-enum stand-in keeps GETSTATIC EventPriority.HIGH / ordinal() / switch
        // working; its bits() carries the EventBus 7 byte (#101).
        t.registerSyntheticClass(PRI, generatePriorityEnum());
        t.registerClassRedirect(OLD_PRIORITY, PRI);

        // Lambda listeners: addListener(lambda) carries no runtime generics, but at transform
        // time the LambdaMetafactory indy right before the call reifies the event type. Rewrite
        // such calls to the typed impl helpers with the type as a trailing Class constant; calls
        // not directly fed by a lambda fall through to the interface defaults below (runtime
        // generics recovery, or soft-fail warn for unresolvable ones).
        t.registerIndyTypedCallRewrite(LEB, "addListener", "(" + L_CONSUMER + ")V",
                LEB_IMPL, "addListenerTypedIndy", "(" + L_LEB + L_CONSUMER + L_CLASS + ")V");
        t.registerIndyTypedCallRewrite(LEB, "addListener", "(" + L_PRI + L_CONSUMER + ")V",
                LEB_IMPL, "addListenerPriTypedIndy", "(" + L_LEB + L_PRI + L_CONSUMER + L_CLASS + ")V");
        t.registerIndyTypedCallRewrite(LEB, "addListener", "(" + L_PRI + "Z" + L_CONSUMER + ")V",
                LEB_IMPL, "addListenerPriZTypedIndy", "(" + L_LEB + L_PRI + "Z" + L_CONSUMER + L_CLASS + ")V");

        // getModEventBus() -> modBus(ctx). Method redirects match AFTER the ClassRemapper pass, so
        // the return type in the matched descriptor is already the bridge; the pre-remap spelling is
        // registered too in case the redirect table is consulted earlier on some path. The static
        // targets live on the IMPL CLASS: the devirtualizer emits a plain INVOKESTATIC Methodref,
        // and a static on an interface needs an InterfaceMethodref (IncompatibleClassChangeError
        // otherwise - bitten in the first Forge 26.2 in-game run).
        for (String ret : new String[]{L_LEB, L_OLD_BUS}) {
            t.registerMethodRedirect(FMLJMLC, "getModEventBus", "()" + ret,
                    LEB_IMPL, "modBus", "(L" + FMLJMLC + ";)" + L_LEB, true);
        }
        // DeferredRegister.register(IEventBus) -> registerDeferred(dr, bus) -> dr.register(group)
        for (String arg : new String[]{L_LEB, L_OLD_BUS}) {
            t.registerMethodRedirect(DEFERRED_REG, "register", "(" + arg + ")V",
                    LEB_IMPL, "registerDeferred", "(L" + DEFERRED_REG + ";" + L_LEB + ")V", true);
        }
        // MinecraftForge.EVENT_BUS (old descriptor -> post-remap bridge descriptor) -> GAME_BUS.
        // Keyed on BOTH owners: ForgeCorePolyfill class-redirects MinecraftForge to its embedded
        // ForgeCapabilitiesShim (which has no EVENT_BUS field), and ClassRemapper rewrites the
        // GETSTATIC owner before field redirects match - so on a Forge 26.2 host the read arrives
        // owned by the capabilities shim (hit in-game: NoSuchFieldError on MacawsBridges.<init>).
        t.registerFieldRedirect(MINECRAFT_FORGE, "EVENT_BUS", LEB_IMPL, "GAME_BUS");
        t.registerFieldRedirect("com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim",
                "EVENT_BUS", LEB_IMPL, "GAME_BUS");
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    /** The LegacyEventBus interface: group() + static factories + default IEventBus-shaped methods. */
    public static byte[] generateInterface() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, LEB, null, "java/lang/Object", null);

        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "group", "()" + L_BG, null, null).visitEnd();

        // default void register(Object listener) {
        //     try { group().register(MethodHandles.lookup(), listener); }
        //     catch (IllegalArgumentException e) { LegacyEventBusImpl.registerFallback(this, listener); }
        // }
        // lookup() is caller-sensitive: taken here, in the per-mod embedded copy, it carries the
        // mod module's access - exactly what EventBus 7 needs to unreflect the handler methods.
        // The catch handles EventBus 7's single-listener policy: BusGroup.register THROWS
        // IllegalArgumentException("Only a single listener found ... directly call addListener()")
        // for one-handler classes (hit in-game on Macaw's Bridges); the fallback wires that handler
        // to its event's own static BUS reflectively.
        MethodVisitor rg = cw.visitMethod(ACC_PUBLIC, "register", "(" + L_OBJ + ")V", null, null);
        rg.visitCode();
        org.objectweb.asm.Label rTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label rEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label rCatch = new org.objectweb.asm.Label();
        rg.visitTryCatchBlock(rTry, rEnd, rCatch, "java/lang/IllegalArgumentException");
        rg.visitLabel(rTry);
        rg.visitVarInsn(ALOAD, 0);
        rg.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        rg.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()L" + LOOKUP + ";", false);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitMethodInsn(INVOKEINTERFACE, BUS_GROUP, "register",
                "(L" + LOOKUP + ";" + L_OBJ + ")Ljava/util/Collection;", true);
        rg.visitInsn(POP);
        rg.visitLabel(rEnd);
        rg.visitInsn(RETURN);
        rg.visitLabel(rCatch);
        rg.visitInsn(POP);
        rg.visitVarInsn(ALOAD, 0);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "registerFallback",
                "(" + L_LEB + L_OBJ + ")V", false);
        rg.visitInsn(RETURN);
        rg.visitMaxs(0, 0);
        rg.visitEnd();

        // default void unregister(Object listener) - EventBus 7 unregisters by listener handle
        // collection, which the old idiom never kept; warn-and-noop (rare in mod constructors).
        MethodVisitor ug = cw.visitMethod(ACC_PUBLIC, "unregister", "(" + L_OBJ + ")V", null, null);
        ug.visitCode();
        emitWarn(ug, "[Retromod] IEventBus.unregister(Object) is not bridgeable on EventBus 7; ignored for: ");
        ug.visitInsn(RETURN);
        ug.visitMaxs(0, 0);
        ug.visitEnd();

        // default void addListener(Consumer c): resolve the event type from the Consumer's reified
        // generics and hook its static BUS; lambdas carry no generics -> warn + no-op (staged).
        MethodVisitor al = cw.visitMethod(ACC_PUBLIC, "addListener", "(" + L_CONSUMER + ")V", null, null);
        al.visitCode();
        al.visitVarInsn(ALOAD, 0);
        al.visitVarInsn(ALOAD, 1);
        al.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerHelper",
                "(" + L_LEB + L_CONSUMER + ")V", false);
        al.visitInsn(RETURN);
        al.visitMaxs(0, 0);
        al.visitEnd();

        // default void addListener(LegacyEventPriority p, Consumer c) { LEB_IMPL.addListenerPri(this, p, c); }
        MethodVisitor ap = cw.visitMethod(ACC_PUBLIC, "addListener",
                "(" + L_PRI + L_CONSUMER + ")V", null, null);
        ap.visitCode();
        ap.visitVarInsn(ALOAD, 0);
        ap.visitVarInsn(ALOAD, 1);
        ap.visitVarInsn(ALOAD, 2);
        ap.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerPri",
                "(" + L_LEB + L_PRI + L_CONSUMER + ")V", false);
        ap.visitInsn(RETURN);
        ap.visitMaxs(0, 0);
        ap.visitEnd();

        // default void addListener(LegacyEventPriority p, boolean receiveCancelled, Consumer c):
        // EventBus 7 handles cancelled-delivery via a different listener shape; the flag is
        // dropped (a receiveCancelled=true listener just sees fewer events than on EB6).
        MethodVisitor apz = cw.visitMethod(ACC_PUBLIC, "addListener",
                "(" + L_PRI + "Z" + L_CONSUMER + ")V", null, null);
        apz.visitCode();
        apz.visitVarInsn(ALOAD, 0);
        apz.visitVarInsn(ALOAD, 1);
        apz.visitVarInsn(ALOAD, 3);
        apz.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerPri",
                "(" + L_LEB + L_PRI + L_CONSUMER + ")V", false);
        apz.visitInsn(RETURN);
        apz.visitMaxs(0, 0);
        apz.visitEnd();

        // default void addListener(LegacyEventPriority p, boolean z, Class ev, Consumer c):
        // the Class-carrying overload resolves the event's BUS exactly - no generics needed.
        MethodVisitor apc = cw.visitMethod(ACC_PUBLIC, "addListener",
                "(" + L_PRI + "Z" + L_CLASS + L_CONSUMER + ")V", null, null);
        apc.visitCode();
        apc.visitVarInsn(ALOAD, 0);
        apc.visitVarInsn(ALOAD, 1);
        apc.visitVarInsn(ALOAD, 3);
        apc.visitVarInsn(ALOAD, 4);
        apc.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerTyped",
                "(" + L_LEB + L_PRI + L_CLASS + L_CONSUMER + ")V", false);
        apc.visitInsn(RETURN);
        apc.visitMaxs(0, 0);
        apc.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A REAL enum (extends {@code java/lang/Enum}) standing in for EventBus 6's deleted
     * {@code EventPriority}: GETSTATIC constants, {@code ordinal()}, {@code values()},
     * {@code valueOf()} and enum-switch all keep working in old mod code. Each constant carries
     * the matching EventBus 7 {@code Priority} byte in {@code bits}, read at clinit from the
     * host's Priority class (never hardcoded).
     */
    public static byte[] generatePriorityEnum() {
        ClassWriter cw = newWriter();
        String[] names = {"HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST"};
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, PRI,
                "Ljava/lang/Enum<" + L_PRI + ">;", "java/lang/Enum", null);
        for (String n : names) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, n, L_PRI, null, null)
                    .visitEnd();
        }
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC, "$VALUES",
                "[" + L_PRI, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "bits", "B", null, null).visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;IB)V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitVarInsn(ILOAD, 2);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ILOAD, 3);
        c.visitFieldInsn(PUTFIELD, PRI, "bits", "B");
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor b = cw.visitMethod(ACC_PUBLIC, "bits", "()B", null, null);
        b.visitCode();
        b.visitVarInsn(ALOAD, 0);
        b.visitFieldInsn(GETFIELD, PRI, "bits", "B");
        b.visitInsn(IRETURN);
        b.visitMaxs(0, 0);
        b.visitEnd();

        // static byte bitsOf(Object p): our enum's bits, or Priority.NORMAL for null/foreign.
        MethodVisitor bo = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "bitsOf",
                "(" + L_OBJ + ")B", null, null);
        bo.visitCode();
        bo.visitVarInsn(ALOAD, 0);
        bo.visitTypeInsn(INSTANCEOF, PRI);
        org.objectweb.asm.Label foreign = new org.objectweb.asm.Label();
        bo.visitJumpInsn(IFEQ, foreign);
        bo.visitVarInsn(ALOAD, 0);
        bo.visitTypeInsn(CHECKCAST, PRI);
        bo.visitMethodInsn(INVOKEVIRTUAL, PRI, "bits", "()B", false);
        bo.visitInsn(IRETURN);
        bo.visitLabel(foreign);
        bo.visitFieldInsn(GETSTATIC, PRIORITY7, "NORMAL", "B");
        bo.visitInsn(IRETURN);
        bo.visitMaxs(0, 0);
        bo.visitEnd();

        MethodVisitor v = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "values", "()[" + L_PRI, null, null);
        v.visitCode();
        v.visitFieldInsn(GETSTATIC, PRI, "$VALUES", "[" + L_PRI);
        v.visitMethodInsn(INVOKEVIRTUAL, "[" + L_PRI, "clone", "()" + L_OBJ, false);
        v.visitTypeInsn(CHECKCAST, "[" + L_PRI);
        v.visitInsn(ARETURN);
        v.visitMaxs(0, 0);
        v.visitEnd();

        MethodVisitor vo = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "valueOf",
                "(Ljava/lang/String;)" + L_PRI, null, null);
        vo.visitCode();
        vo.visitLdcInsn(org.objectweb.asm.Type.getObjectType(PRI));
        vo.visitVarInsn(ALOAD, 0);
        vo.visitMethodInsn(INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        vo.visitTypeInsn(CHECKCAST, PRI);
        vo.visitInsn(ARETURN);
        vo.visitMaxs(0, 0);
        vo.visitEnd();

        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        for (int i = 0; i < names.length; i++) {
            ci.visitTypeInsn(NEW, PRI);
            ci.visitInsn(DUP);
            ci.visitLdcInsn(names[i]);
            ci.visitLdcInsn(i);
            ci.visitFieldInsn(GETSTATIC, PRIORITY7, names[i], "B");
            ci.visitMethodInsn(INVOKESPECIAL, PRI, "<init>", "(Ljava/lang/String;IB)V", false);
            ci.visitFieldInsn(PUTSTATIC, PRI, names[i], L_PRI);
        }
        ci.visitLdcInsn(names.length);
        ci.visitTypeInsn(ANEWARRAY, PRI);
        for (int i = 0; i < names.length; i++) {
            ci.visitInsn(DUP);
            ci.visitLdcInsn(i);
            ci.visitFieldInsn(GETSTATIC, PRI, names[i], L_PRI);
            ci.visitInsn(AASTORE);
        }
        ci.visitFieldInsn(PUTSTATIC, PRI, "$VALUES", "[" + L_PRI);
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The impl class: the BusGroup field, GAME_BUS (BusGroup.DEFAULT), and the addListener helper. */
    public static byte[] generateImpl() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, LEB_IMPL, null, "java/lang/Object", new String[]{LEB});

        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "GAME_BUS", L_LEB, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "g", L_BG, null, null).visitEnd();
        // The mod's own bus group, kept when the mod asks for its event bus: a mod-bus event's bus lives there.
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, "modGroup", L_BG, null, null).visitEnd();

        // static { GAME_BUS = of(BusGroup.DEFAULT); }  (backs MinecraftForge.EVENT_BUS)
        MethodVisitor ci = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        ci.visitCode();
        ci.visitFieldInsn(GETSTATIC, BUS_GROUP, "DEFAULT", L_BG);
        ci.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "of", "(" + L_BG + ")" + L_LEB, false);
        ci.visitFieldInsn(PUTSTATIC, LEB_IMPL, "GAME_BUS", L_LEB);
        ci.visitInsn(RETURN);
        ci.visitMaxs(0, 0);
        ci.visitEnd();

        // static LegacyEventBus of(BusGroup g) { return new LegacyEventBusImpl(g); }
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", "(" + L_BG + ")" + L_LEB, null, null);
        of.visitCode();
        of.visitTypeInsn(NEW, LEB_IMPL);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKESPECIAL, LEB_IMPL, "<init>", "(" + L_BG + ")V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();

        // static LegacyEventBus modBus(FMLJavaModLoadingContext ctx) { return of(ctx.getModBusGroup()); }
        MethodVisitor mb = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "modBus",
                "(L" + FMLJMLC + ";)" + L_LEB, null, null);
        mb.visitCode();
        mb.visitVarInsn(ALOAD, 0);
        mb.visitMethodInsn(INVOKEVIRTUAL, FMLJMLC, "getModBusGroup", "()" + L_BG, false);
        mb.visitInsn(DUP);
        mb.visitFieldInsn(PUTSTATIC, LEB_IMPL, "modGroup", L_BG);
        mb.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "of", "(" + L_BG + ")" + L_LEB, false);
        mb.visitInsn(ARETURN);
        mb.visitMaxs(0, 0);
        mb.visitEnd();

        // static void registerDeferred(DeferredRegister dr, LegacyEventBus bus) { dr.register(bus.group()); }
        MethodVisitor rd = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerDeferred",
                "(L" + DEFERRED_REG + ";" + L_LEB + ")V", null, null);
        rd.visitCode();
        rd.visitVarInsn(ALOAD, 0);
        rd.visitVarInsn(ALOAD, 1);
        rd.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        rd.visitMethodInsn(INVOKEVIRTUAL, DEFERRED_REG, "register", "(" + L_BG + ")V", false);
        rd.visitInsn(RETURN);
        rd.visitMaxs(0, 0);
        rd.visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_BG + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, LEB_IMPL, "g", L_BG);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor g = cw.visitMethod(ACC_PUBLIC, "group", "()" + L_BG, null, null);
        g.visitCode();
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, LEB_IMPL, "g", L_BG);
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        // static void registerFallback(LegacyEventBus self, Object o):
        //   EventBus 7's BusGroup.register throws IllegalArgumentException for a class with exactly
        //   one @SubscribeEvent handler ("directly call addListener() on the EventBus of <Event>").
        //   Wire each 1-arg @SubscribeEvent(EB7) method to its event's static BUS via reflection:
        //     for (Method m : o.getClass().getMethods())
        //       if (m.getParameterCount() == 1 && m.isAnnotationPresent(SubscribeEvent.class))
        //         ((EventBus) m.getParameterTypes()[0].getField("BUS").get(null))
        //             .addListener(new ReflectedConsumer(m, o));
        //   Any reflection failure warns and no-ops.
        MethodVisitor rf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerFallback",
                "(" + L_LEB + L_OBJ + ")V", null, null);
        rf.visitCode();
        org.objectweb.asm.Label fTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fCatch = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fLoop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fNext = new org.objectweb.asm.Label();
        rf.visitTryCatchBlock(fTry, fEnd, fCatch, "java/lang/Throwable");
        rf.visitLabel(fTry);
        rf.visitVarInsn(ALOAD, 1);
        rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethods",
                "()[Ljava/lang/reflect/Method;", false);
        rf.visitVarInsn(ASTORE, 2);
        rf.visitInsn(ICONST_0);
        rf.visitVarInsn(ISTORE, 3);
        rf.visitLabel(fLoop);
        rf.visitVarInsn(ILOAD, 3);
        rf.visitVarInsn(ALOAD, 2);
        rf.visitInsn(ARRAYLENGTH);
        rf.visitJumpInsn(IF_ICMPGE, fEnd);
        rf.visitVarInsn(ALOAD, 2);
        rf.visitVarInsn(ILOAD, 3);
        rf.visitInsn(AALOAD);
        rf.visitVarInsn(ASTORE, 4);
        rf.visitVarInsn(ALOAD, 4);
        rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getParameterCount", "()I", false);
        rf.visitInsn(ICONST_1);
        rf.visitJumpInsn(IF_ICMPNE, fNext);
        rf.visitVarInsn(ALOAD, 4);
        rf.visitLdcInsn(org.objectweb.asm.Type.getObjectType(NEW_SUBSCRIBE));
        rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "isAnnotationPresent",
                "(Ljava/lang/Class;)Z", false);
        rf.visitJumpInsn(IFEQ, fNext);
        // ((EventBus) m.getParameterTypes()[0].getField("BUS").get(null)).addListener(new ReflectedConsumer(m, o))
        rf.visitVarInsn(ALOAD, 4);
        rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getParameterTypes",
                "()[Ljava/lang/Class;", false);
        rf.visitInsn(ICONST_0);
        rf.visitInsn(AALOAD);
        rf.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "busOf", "(" + L_CLASS + ")" + L_OBJ, false);
        rf.visitTypeInsn(CHECKCAST, EVENT_BUS7);
        rf.visitTypeInsn(NEW, RC);
        rf.visitInsn(DUP);
        rf.visitVarInsn(ALOAD, 4);
        rf.visitVarInsn(ALOAD, 1);
        rf.visitMethodInsn(INVOKESPECIAL, RC, "<init>",
                "(Ljava/lang/reflect/Method;" + L_OBJ + ")V", false);
        rf.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS7, "addListener",
                "(" + L_CONSUMER + ")Lnet/minecraftforge/eventbus/api/listener/EventListener;", true);
        rf.visitInsn(POP);
        rf.visitLabel(fNext);
        rf.visitIincInsn(3, 1);
        rf.visitJumpInsn(GOTO, fLoop);
        rf.visitLabel(fEnd);
        rf.visitInsn(RETURN);
        rf.visitLabel(fCatch);
        rf.visitInsn(POP);
        emitWarn(rf, "[Retromod] EventBus 7 single-listener fallback failed for: ");
        rf.visitInsn(RETURN);
        rf.visitMaxs(0, 0);
        rf.visitEnd();

        // static void addListenerHelper(LegacyEventBus self, Consumer c):
        //   for (Type it : c.getClass().getGenericInterfaces())
        //     if (it instanceof ParameterizedType pt && pt.getRawType() == Consumer.class
        //         && pt.getActualTypeArguments()[0] instanceof Class<?> ev) {
        //       ((EventBus) ev.getField("BUS").get(null)).addListener(c); return;
        //     }
        //   warn(...);   // lambdas land here: no reified generics
        // Any reflection failure (no BUS field, wrong type) falls into the same warn path.
        MethodVisitor h = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerHelper",
                "(" + L_LEB + L_CONSUMER + ")V", null, null);
        h.visitCode();
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchAll = new org.objectweb.asm.Label();
        org.objectweb.asm.Label warn = new org.objectweb.asm.Label();
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label next = new org.objectweb.asm.Label();
        h.visitTryCatchBlock(tryStart, tryEnd, catchAll, "java/lang/Throwable");
        h.visitLabel(tryStart);
        // Type[] ifaces = c.getClass().getGenericInterfaces(); int i = 0;
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        h.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getGenericInterfaces",
                "()[Ljava/lang/reflect/Type;", false);
        h.visitVarInsn(ASTORE, 2);
        h.visitInsn(ICONST_0);
        h.visitVarInsn(ISTORE, 3);
        h.visitLabel(loop);
        h.visitVarInsn(ILOAD, 3);
        h.visitVarInsn(ALOAD, 2);
        h.visitInsn(ARRAYLENGTH);
        h.visitJumpInsn(IF_ICMPGE, warn);
        // if (!(ifaces[i] instanceof ParameterizedType)) continue;
        h.visitVarInsn(ALOAD, 2);
        h.visitVarInsn(ILOAD, 3);
        h.visitInsn(AALOAD);
        h.visitInsn(DUP);
        h.visitTypeInsn(INSTANCEOF, "java/lang/reflect/ParameterizedType");
        org.objectweb.asm.Label isPt = new org.objectweb.asm.Label();
        h.visitJumpInsn(IFNE, isPt);
        h.visitInsn(POP);
        h.visitJumpInsn(GOTO, next);
        h.visitLabel(isPt);
        h.visitTypeInsn(CHECKCAST, "java/lang/reflect/ParameterizedType");
        h.visitVarInsn(ASTORE, 4);
        // if (pt.getRawType() != Consumer.class) continue;
        h.visitVarInsn(ALOAD, 4);
        h.visitMethodInsn(INVOKEINTERFACE, "java/lang/reflect/ParameterizedType", "getRawType",
                "()Ljava/lang/reflect/Type;", true);
        h.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/util/function/Consumer"));
        h.visitJumpInsn(IF_ACMPNE, next);
        // Object t = pt.getActualTypeArguments()[0]; if (!(t instanceof Class)) continue;
        h.visitVarInsn(ALOAD, 4);
        h.visitMethodInsn(INVOKEINTERFACE, "java/lang/reflect/ParameterizedType",
                "getActualTypeArguments", "()[Ljava/lang/reflect/Type;", true);
        h.visitInsn(ICONST_0);
        h.visitInsn(AALOAD);
        h.visitInsn(DUP);
        h.visitTypeInsn(INSTANCEOF, "java/lang/Class");
        org.objectweb.asm.Label isClass = new org.objectweb.asm.Label();
        h.visitJumpInsn(IFNE, isClass);
        h.visitInsn(POP);
        h.visitJumpInsn(GOTO, next);
        h.visitLabel(isClass);
        h.visitTypeInsn(CHECKCAST, "java/lang/Class");
        // ((EventBus) busOf(ev)).addListener(c); return;
        h.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "busOf", "(" + L_CLASS + ")" + L_OBJ, false);
        h.visitTypeInsn(CHECKCAST, EVENT_BUS7);
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS7, "addListener",
                "(" + L_CONSUMER + ")Lnet/minecraftforge/eventbus/api/listener/EventListener;", true);
        h.visitInsn(POP);
        h.visitLabel(tryEnd);
        h.visitInsn(RETURN);
        h.visitLabel(next);
        h.visitIincInsn(3, 1);
        h.visitJumpInsn(GOTO, loop);
        h.visitLabel(catchAll);
        h.visitInsn(POP);
        h.visitLabel(warn);
        emitWarn(h, "[Retromod] Could not bridge IEventBus.addListener onto EventBus 7 (lambda "
                + "listeners carry no event type); setup listener soft-failed: ");
        h.visitInsn(RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();

        emitEventTypeOf(cw);
        emitHookBus(cw);
        emitPriorityHelpers(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code static Class eventTypeOf(Consumer c)}: the event type from the Consumer class's
     * reified generic interfaces, or null (lambdas, raw types, reflection failures). Same
     * recovery walk as addListenerHelper, factored for the priority-carrying paths.
     */
    private static void emitEventTypeOf(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "eventTypeOf",
                "(" + L_CONSUMER + ")" + L_CLASS, null, null);
        m.visitCode();
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchAll = new org.objectweb.asm.Label();
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label next = new org.objectweb.asm.Label();
        org.objectweb.asm.Label nullRet = new org.objectweb.asm.Label();
        m.visitTryCatchBlock(tryStart, tryEnd, catchAll, "java/lang/Throwable");
        m.visitLabel(tryStart);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getGenericInterfaces",
                "()[Ljava/lang/reflect/Type;", false);
        m.visitVarInsn(ASTORE, 1);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ISTORE, 2);
        m.visitLabel(loop);
        m.visitVarInsn(ILOAD, 2);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ARRAYLENGTH);
        m.visitJumpInsn(IF_ICMPGE, nullRet);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ILOAD, 2);
        m.visitInsn(AALOAD);
        m.visitInsn(DUP);
        m.visitTypeInsn(INSTANCEOF, "java/lang/reflect/ParameterizedType");
        org.objectweb.asm.Label isPt = new org.objectweb.asm.Label();
        m.visitJumpInsn(IFNE, isPt);
        m.visitInsn(POP);
        m.visitJumpInsn(GOTO, next);
        m.visitLabel(isPt);
        m.visitTypeInsn(CHECKCAST, "java/lang/reflect/ParameterizedType");
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEINTERFACE, "java/lang/reflect/ParameterizedType", "getRawType",
                "()Ljava/lang/reflect/Type;", true);
        m.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/util/function/Consumer"));
        m.visitJumpInsn(IF_ACMPNE, next);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEINTERFACE, "java/lang/reflect/ParameterizedType",
                "getActualTypeArguments", "()[Ljava/lang/reflect/Type;", true);
        m.visitInsn(ICONST_0);
        m.visitInsn(AALOAD);
        m.visitInsn(DUP);
        m.visitTypeInsn(INSTANCEOF, "java/lang/Class");
        org.objectweb.asm.Label isCls = new org.objectweb.asm.Label();
        m.visitJumpInsn(IFNE, isCls);
        m.visitInsn(POP);
        m.visitJumpInsn(GOTO, next);
        m.visitLabel(isCls);
        m.visitTypeInsn(CHECKCAST, "java/lang/Class");
        m.visitLabel(tryEnd);
        m.visitInsn(ARETURN);
        m.visitLabel(next);
        m.visitIincInsn(2, 1);
        m.visitJumpInsn(GOTO, loop);
        m.visitLabel(catchAll);
        m.visitInsn(POP);
        m.visitLabel(nullRet);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * {@code static void hookBus(Class ev, byte bits, Consumer c)}: resolve the event's static
     * {@code BUS} and {@code addListener(bits, c)}; any failure warns and no-ops.
     */
    private static void emitHookBus(ClassWriter cw) {
        // static void hookBus(Class ev, byte priority, Consumer c): ((EventBus) busOf(ev)).addListener(priority, c),
        // or a warning naming the event and the reason.
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hookBus",
                "(" + L_CLASS + "B" + L_CONSUMER + ")V", null, null);
        m.visitCode();
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchAll = new org.objectweb.asm.Label();
        m.visitTryCatchBlock(tryStart, tryEnd, catchAll, "java/lang/Throwable");
        m.visitLabel(tryStart);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "busOf", "(" + L_CLASS + ")" + L_OBJ, false);
        m.visitTypeInsn(CHECKCAST, EVENT_BUS7);
        m.visitVarInsn(ILOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS7, "addListener",
                "(B" + L_CONSUMER + ")" + L_LISTENER7, true);
        m.visitInsn(POP);
        String logger = String.join("/", "org", "slf4j", "Logger");
        m.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        m.visitLdcInsn("[Retromod] Hooked a listener onto EventBus 7 for {}");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEINTERFACE, logger, "debug", "(Ljava/lang/String;" + L_OBJ + ")V", true);
        m.visitLabel(tryEnd);
        m.visitInsn(RETURN);
        m.visitLabel(catchAll);
        m.visitVarInsn(ASTORE, 3);
        m.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        m.visitLdcInsn("[Retromod] Could not hook a listener onto EventBus 7 for {}: {}");
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;" + L_OBJ + L_OBJ + ")V", true);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        // static Object busOf(Class ev): a game event's static BUS, or a mod-bus event's bus in the mod's group.
        MethodVisitor b = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "busOf", "(" + L_CLASS + ")" + L_OBJ, null,
                new String[]{"java/lang/Exception"});
        b.visitCode();
        org.objectweb.asm.Label fieldStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fieldEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label noField = new org.objectweb.asm.Label();
        b.visitTryCatchBlock(fieldStart, fieldEnd, noField, "java/lang/NoSuchFieldException");
        b.visitLabel(fieldStart);
        b.visitVarInsn(ALOAD, 0);
        b.visitLdcInsn("BUS");
        b.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        b.visitInsn(ACONST_NULL);
        b.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(" + L_OBJ + ")" + L_OBJ, false);
        b.visitLabel(fieldEnd);
        b.visitInsn(ARETURN);
        b.visitLabel(noField);
        b.visitInsn(POP);
        b.visitFieldInsn(GETSTATIC, LEB_IMPL, "modGroup", L_BG);
        b.visitVarInsn(ALOAD, 0);
        b.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fml/event/IModBusEvent", "getBus",
                "(" + L_BG + L_CLASS + ")L" + EVENT_BUS7 + ";", true);
        b.visitInsn(ARETURN);
        b.visitMaxs(0, 0);
        b.visitEnd();
    }

    /** The priority-carrying addListener helpers (#101), all funneling into hookBus. */
    private static void emitPriorityHelpers(ClassWriter cw) {
        // static void addListenerPri(LEB self, PRI p, Consumer c):
        //   Class ev = eventTypeOf(c); ev == null ? warn : hookBus(ev, bitsOf(p), c)
        MethodVisitor p = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerPri",
                "(" + L_LEB + L_PRI + L_CONSUMER + ")V", null, null);
        p.visitCode();
        p.visitVarInsn(ALOAD, 2);
        p.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "eventTypeOf",
                "(" + L_CONSUMER + ")" + L_CLASS, false);
        p.visitVarInsn(ASTORE, 3);
        p.visitVarInsn(ALOAD, 3);
        org.objectweb.asm.Label ok = new org.objectweb.asm.Label();
        p.visitJumpInsn(IFNONNULL, ok);
        emitWarn(p, "[Retromod] Could not bridge IEventBus.addListener(priority, ...) onto "
                + "EventBus 7 (lambda listeners carry no event type); listener soft-failed: ", 2);
        p.visitInsn(RETURN);
        p.visitLabel(ok);
        p.visitVarInsn(ALOAD, 3);
        p.visitVarInsn(ALOAD, 1);
        p.visitMethodInsn(INVOKESTATIC, PRI, "bitsOf", "(" + L_OBJ + ")B", false);
        p.visitVarInsn(ALOAD, 2);
        p.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus",
                "(" + L_CLASS + "B" + L_CONSUMER + ")V", false);
        p.visitInsn(RETURN);
        p.visitMaxs(0, 0);
        p.visitEnd();

        // static void addListenerTyped(LEB self, PRI p, Class ev, Consumer c)
        MethodVisitor t = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerTyped",
                "(" + L_LEB + L_PRI + L_CLASS + L_CONSUMER + ")V", null, null);
        t.visitCode();
        t.visitVarInsn(ALOAD, 2);
        t.visitVarInsn(ALOAD, 1);
        t.visitMethodInsn(INVOKESTATIC, PRI, "bitsOf", "(" + L_OBJ + ")B", false);
        t.visitVarInsn(ALOAD, 3);
        t.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus",
                "(" + L_CLASS + "B" + L_CONSUMER + ")V", false);
        t.visitInsn(RETURN);
        t.visitMaxs(0, 0);
        t.visitEnd();

        // Indy-typed targets: the transform appends the lambda's event type as the LAST arg.
        // static void addListenerTypedIndy(LEB self, Consumer c, Class ev)
        MethodVisitor i1 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerTypedIndy",
                "(" + L_LEB + L_CONSUMER + L_CLASS + ")V", null, null);
        i1.visitCode();
        i1.visitVarInsn(ALOAD, 2);
        i1.visitFieldInsn(GETSTATIC, PRIORITY7, "NORMAL", "B");
        i1.visitVarInsn(ALOAD, 1);
        i1.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus",
                "(" + L_CLASS + "B" + L_CONSUMER + ")V", false);
        i1.visitInsn(RETURN);
        i1.visitMaxs(0, 0);
        i1.visitEnd();

        // static void addListenerPriTypedIndy(LEB self, PRI p, Consumer c, Class ev)
        MethodVisitor i2 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerPriTypedIndy",
                "(" + L_LEB + L_PRI + L_CONSUMER + L_CLASS + ")V", null, null);
        i2.visitCode();
        i2.visitVarInsn(ALOAD, 3);
        i2.visitVarInsn(ALOAD, 1);
        i2.visitMethodInsn(INVOKESTATIC, PRI, "bitsOf", "(" + L_OBJ + ")B", false);
        i2.visitVarInsn(ALOAD, 2);
        i2.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus",
                "(" + L_CLASS + "B" + L_CONSUMER + ")V", false);
        i2.visitInsn(RETURN);
        i2.visitMaxs(0, 0);
        i2.visitEnd();

        // static void addListenerPriZTypedIndy(LEB self, PRI p, boolean z, Consumer c, Class ev)
        MethodVisitor i3 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerPriZTypedIndy",
                "(" + L_LEB + L_PRI + "Z" + L_CONSUMER + L_CLASS + ")V", null, null);
        i3.visitCode();
        i3.visitVarInsn(ALOAD, 4);
        i3.visitVarInsn(ALOAD, 1);
        i3.visitMethodInsn(INVOKESTATIC, PRI, "bitsOf", "(" + L_OBJ + ")B", false);
        i3.visitVarInsn(ALOAD, 3);
        i3.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus",
                "(" + L_CLASS + "B" + L_CONSUMER + ")V", false);
        i3.visitInsn(RETURN);
        i3.visitMaxs(0, 0);
        i3.visitEnd();
    }

    /**
     * {@code Consumer} that reflects a single {@code @SubscribeEvent} handler method:
     * {@code accept(e) -> method.invoke(target, e)}. Used by the single-listener fallback.
     */
    public static byte[] generateReflectedConsumer() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, RC, null, "java/lang/Object",
                new String[]{"java/util/function/Consumer"});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "m", "Ljava/lang/reflect/Method;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "t", L_OBJ, null, null).visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Ljava/lang/reflect/Method;" + L_OBJ + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, RC, "m", "Ljava/lang/reflect/Method;");
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 2);
        c.visitFieldInsn(PUTFIELD, RC, "t", L_OBJ);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        // void accept(Object e) { try { m.invoke(t, e); } catch (Throwable x) { throw new RuntimeException(x); } }
        MethodVisitor a = cw.visitMethod(ACC_PUBLIC, "accept", "(" + L_OBJ + ")V", null, null);
        a.visitCode();
        org.objectweb.asm.Label aTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label aEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label aCatch = new org.objectweb.asm.Label();
        a.visitTryCatchBlock(aTry, aEnd, aCatch, "java/lang/Throwable");
        a.visitLabel(aTry);
        a.visitVarInsn(ALOAD, 0);
        a.visitFieldInsn(GETFIELD, RC, "m", "Ljava/lang/reflect/Method;");
        a.visitVarInsn(ALOAD, 0);
        a.visitFieldInsn(GETFIELD, RC, "t", L_OBJ);
        a.visitInsn(ICONST_1);
        a.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        a.visitInsn(DUP);
        a.visitInsn(ICONST_0);
        a.visitVarInsn(ALOAD, 1);
        a.visitInsn(AASTORE);
        a.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(" + L_OBJ + "[" + L_OBJ + ")" + L_OBJ, false);
        a.visitInsn(POP);
        a.visitLabel(aEnd);
        a.visitInsn(RETURN);
        a.visitLabel(aCatch);
        a.visitVarInsn(ASTORE, 2);
        a.visitTypeInsn(NEW, "java/lang/RuntimeException");
        a.visitInsn(DUP);
        a.visitVarInsn(ALOAD, 2);
        a.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(Ljava/lang/Throwable;)V", false);
        a.visitInsn(ATHROW);
        a.visitMaxs(0, 0);
        a.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String AUTO_SUB_DESC = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    private static final String SUB7_DESC = "L" + NEW_SUBSCRIBE + ";";

    /**
     * EventBus 7's {@code AutomaticEventSubscriber} THROWS for a {@code @Mod.EventBusSubscriber}
     * class with zero ("No listeners found") or one ("Only a single listener found") static
     * {@code @SubscribeEvent} methods - both common in old mods, where EventBus 6 silently
     * registered whatever statics existed (instance handlers were never auto-subscribed). Strip
     * the annotation for those classes so FML skips them: for the zero case this is exactly the
     * EventBus 6 behaviour (instance handlers still flow through the bridge's {@code register}),
     * for the one-static case the handler is soft-failed (logged). Classes with 2+ static handlers
     * keep the annotation - EventBus 7 accepts them. No-op unless the Forge 26.2 chain is active,
     * since on EventBus 6 hosts the auto-subscription works natively.
     *
     * <p>Returns the original array when nothing changed.
     */
    public static byte[] stripLenientAutoSubscriber(byte[] classBytes) {
        if (!active || classBytes == null) return classBytes;
        // cheap pre-filter before a full parse
        if (new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .indexOf("Mod$EventBusSubscriber") < 0) return classBytes;
        try {
            org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(classBytes).accept(cn, 0);
            if (!hasAnnotation(cn.visibleAnnotations) && !hasAnnotation(cn.invisibleAnnotations)) {
                return classBytes;
            }
            long staticHandlers = cn.methods.stream()
                    .filter(m -> (m.access & ACC_STATIC) != 0)
                    .filter(m -> org.objectweb.asm.Type.getArgumentTypes(m.desc).length == 1)
                    .filter(m -> hasSubscribe(m.visibleAnnotations) || hasSubscribe(m.invisibleAnnotations))
                    .count();
            if (staticHandlers >= 2) return classBytes;
            if (staticHandlers == 1) {
                org.slf4j.LoggerFactory.getLogger("Retromod-EventBus7")
                        .warn("Stripped @Mod.EventBusSubscriber from {} (EventBus 7 rejects "
                                + "single-listener classes); its one static handler is soft-failed",
                                cn.name);
            }
            removeAnnotation(cn.visibleAnnotations);
            removeAnnotation(cn.invisibleAnnotations);
            ClassWriter cw = new ClassWriter(0);   // annotation-only change: frames untouched
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            return classBytes;
        }
    }

    private static boolean hasAnnotation(java.util.List<org.objectweb.asm.tree.AnnotationNode> anns) {
        return anns != null && anns.stream().anyMatch(a -> AUTO_SUB_DESC.equals(a.desc));
    }

    private static boolean hasSubscribe(java.util.List<org.objectweb.asm.tree.AnnotationNode> anns) {
        return anns != null && anns.stream().anyMatch(a -> SUB7_DESC.equals(a.desc));
    }

    private static void removeAnnotation(java.util.List<org.objectweb.asm.tree.AnnotationNode> anns) {
        if (anns != null) anns.removeIf(a -> AUTO_SUB_DESC.equals(a.desc));
    }

    /** System.err.println(msg + local1.getClass().getName()) - the embedded copy can't assume slf4j. */
    private static void emitWarn(MethodVisitor m, String msg) {
        emitWarn(m, msg, 1);
    }

    /** As {@link #emitWarn(MethodVisitor, String)}, naming the object in the given local slot. */
    private static void emitWarn(MethodVisitor m, String msg, int slot) {
        // Through Minecraft's own logger, so the warning reaches latest.log; standard error does not
        // on most launchers. The name is assembled here so the release jar's SLF4J relocation leaves it.
        String logger = String.join("/", "org", "slf4j", "Logger");
        m.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        m.visitLdcInsn(msg);
        m.visitVarInsn(ALOAD, slot);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;)V", true);
    }
}
