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
 *       annotation is class-redirected to EventBus 7's, and {@link ForgeSubscribeAnnotations} turns
 *       an old enum {@code priority} into EventBus 7's byte. A class EventBus 7 would refuse (one
 *       listener), cannot reach (a handler that is not public), or would take static handlers from
 *       (EventBus 6 registered only instance handlers for an object) is hooked by {@code registerFallback}.</li>
 *   <li>{@code MinecraftForge.EVENT_BUS} is field-redirected to {@code LegacyEventBusImpl.GAME_BUS}.</li>
 *   <li>{@code addListener(Consumer)} cannot recover the event type from a lambda (EventBus 6 read
 *       it from the lambda's constant pool); for a Consumer class with reified generics it resolves
 *       the type's static {@code BUS} field, otherwise it warns and no-ops (STAGED: lifecycle-setup
 *       listeners are soft-failed, construction + registration is the acceptance bar).</li>
 *   <li>Where the rebuilt event base is active, {@code post}, {@code addGenericListener}, handlers of a
 *       mod's own events and listeners on them go to the embedded {@code LegacyEvents} dispatcher, and a
 *       type without a static {@code BUS} uses the receiving bus's own group.</li>
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
    private static final String OLD_EVENT = LegacyEventBaseAdapter.EVENT;
    /** Retromod's rebuilt dispatcher for old mods' own events, embedded beside the bridge. */
    private static final String EVENTS = LegacyEventBaseBridge.LEGACY_EVENTS;
    /** Keeps each static handler on a bus once, as EventBus 6 registered a class once per bus. */
    static final String STATIC_HANDLERS = com.retromod.shim.common.RebuiltApiClasses.ROOT
            + "forge/eventbus/LegacyStaticHandlers";
    /** Adds an old listener to a host bus as a predicate where the bus takes cancels. */
    static final String CANCEL_LISTENERS = com.retromod.shim.common.RebuiltApiClasses.ROOT
            + "forge/eventbus/LegacyCancelListeners";
    /** Keeps what each registration added, so unregister removes the same listeners. */
    static final String LISTENER_SETS = com.retromod.shim.common.RebuiltApiClasses.ROOT
            + "forge/eventbus/LegacyListenerSets";
    /** Names the mod an old listener moved off its mod bus belongs to, while it runs. */
    static final String NAMESPACES = com.retromod.shim.common.RebuiltApiClasses.ROOT + "forge/fml/LegacyModNamespaces";
    /** LegacyModNamespaces.ofListener(BusGroup group, Class type, Consumer listener). */
    private static final String OF_LISTENER_DESC = "(Lnet/minecraftforge/eventbus/api/bus/BusGroup;Ljava/lang/Class;"
            + "Ljava/util/function/Consumer;)Ljava/util/function/Consumer;";

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
        // Where the rebuilt event base is active, old mods' own events and handlers go to its dispatcher.
        boolean legacy = LegacyEventBaseBridge.hostLacksEventBase();
        t.registerSyntheticClass(LEB, generateInterface(legacy));
        t.registerSyntheticClass(LEB_IMPL, generateImpl(legacy));
        // referenced only by LEB_IMPL - reaches the mod via the embedder's transitive closure
        t.registerSyntheticClass(RC, generateReflectedConsumer());
        com.retromod.shim.common.RebuiltApiClasses.registerTree(t, STATIC_HANDLERS);
        com.retromod.shim.common.RebuiltApiClasses.registerTree(t, LISTENER_SETS);
        com.retromod.shim.common.RebuiltApiClasses.registerTree(t, NAMESPACES);

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
        if (legacy) registerLegacyEntryPoints(t);

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
        // Keyed on BOTH owners: without a Forge target (the CLI with no --target-loader forge),
        // ForgeCorePolyfill class-redirects MinecraftForge to its embedded ForgeCapabilitiesShim
        // (which has no EVENT_BUS field), and ClassRemapper rewrites the GETSTATIC owner before
        // field redirects match (hit in-game before the Forge target flag: NoSuchFieldError on
        // MacawsBridges.<init>).
        t.registerFieldRedirect(MINECRAFT_FORGE, "EVENT_BUS", LEB_IMPL, "GAME_BUS");
        t.registerFieldRedirect("com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim",
                "EVENT_BUS", LEB_IMPL, "GAME_BUS");
    }

    /** Lets old listeners added to a host event's cancellable bus cancel it, where LegacyCancelBridge is active. */
    static void enableCancellation(RetromodTransformer t) {
        com.retromod.shim.common.RebuiltApiClasses.registerTree(t, CANCEL_LISTENERS);
        t.registerSyntheticClass(LEB_IMPL, generateImpl(LegacyEventBaseBridge.hostLacksEventBase(), true));
    }

    /** post and addGenericListener, which reach the rebuilt dispatcher. */
    private static void registerLegacyEntryPoints(RetromodTransformer t) {
        // A class the event base pass left alone still posts through the old Event descriptor.
        for (String owner : new String[]{LEB, OLD_IEVENTBUS}) {
            t.registerMethodRedirect(owner, "post", "(L" + OLD_EVENT + ";)Z", LEB, "post", "(" + L_OBJ + ")Z");
        }
        // A lambda's generic listener carries its event type, as addListener's does.
        t.registerIndyTypedCallRewrite(LEB, "addGenericListener", "(" + L_CLASS + L_CONSUMER + ")V",
                LEB_IMPL, "addGenericListenerTypedIndy", "(" + L_LEB + L_CLASS + L_CONSUMER + L_CLASS + ")V");
        t.registerIndyTypedCallRewrite(LEB, "addGenericListener", "(" + L_CLASS + L_PRI + L_CONSUMER + ")V",
                LEB_IMPL, "addGenericListenerPriTypedIndy", "(" + L_LEB + L_CLASS + L_PRI + L_CONSUMER + L_CLASS + ")V");
        t.registerIndyTypedCallRewrite(LEB, "addGenericListener", "(" + L_CLASS + L_PRI + "Z" + L_CONSUMER + ")V",
                LEB_IMPL, "addGenericListenerPriZTypedIndy",
                "(" + L_LEB + L_CLASS + L_PRI + "Z" + L_CONSUMER + L_CLASS + ")V");
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    /** The LegacyEventBus interface as a Forge 26.2 host gets it, with the rebuilt dispatcher. */
    public static byte[] generateInterface() {
        return generateInterface(true);
    }

    /** The LegacyEventBus interface: group() + static factories + default IEventBus-shaped methods. */
    static byte[] generateInterface(boolean legacy) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, LEB, null, "java/lang/Object", null);

        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "group", "()" + L_BG, null, null).visitEnd();

        // default void register(Object listener) {
        //     if (LegacyEventBusImpl.needsFallback(listener)) { LegacyEventBusImpl.registerFallback(this, listener); return; }
        //     try { group().register(MethodHandles.lookup(), listener); }
        //     catch (IllegalArgumentException e) { LegacyEventBusImpl.warnRefused(listener, e); }
        // }
        // lookup() is caller-sensitive: taken here, in the per-mod embedded copy, it carries the
        // mod module's access - exactly what EventBus 7 needs to unreflect the handler methods.
        MethodVisitor rg = cw.visitMethod(ACC_PUBLIC, "register", "(" + L_OBJ + ")V", null, null);
        rg.visitCode();
        // if (LegacyListenerSets.holds(group(), listener)) return;  EventBus 6 ignored an object it already held.
        org.objectweb.asm.Label fresh = new org.objectweb.asm.Label();
        rg.visitVarInsn(ALOAD, 0);
        rg.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitMethodInsn(INVOKESTATIC, LISTENER_SETS, "holds", "(" + L_BG + L_OBJ + ")Z", false);
        rg.visitJumpInsn(IFEQ, fresh);
        rg.visitInsn(RETURN);
        rg.visitLabel(fresh);
        if (legacy) {
            // if (!LegacyEvents.subscribe(group(), listener)) return;  handlers of a mod's own events go there,
            // and EventBus 7 sees the listener only when handlers of its own are left.
            org.objectweb.asm.Label eventBus7Left = new org.objectweb.asm.Label();
            rg.visitVarInsn(ALOAD, 0);
            rg.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
            rg.visitVarInsn(ALOAD, 1);
            rg.visitMethodInsn(INVOKESTATIC, EVENTS, "subscribe", "(" + L_BG + L_OBJ + ")Z", false);
            rg.visitJumpInsn(IFNE, eventBus7Left);
            rg.visitInsn(RETURN);
            rg.visitLabel(eventBus7Left);
        }
        org.objectweb.asm.Label rTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label rEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label rCatch = new org.objectweb.asm.Label();
        rg.visitTryCatchBlock(rTry, rEnd, rCatch, "java/lang/IllegalArgumentException");
        // EventBus 7 adds a lone listener and then throws, this lookup cannot reach a handler that
        // is not public, and it adds static handlers for an instance, which EventBus 6 never did.
        // Such a class never reaches it and is hooked method by method.
        rg.visitVarInsn(ALOAD, 1);
        rg.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "needsFallback", "(" + L_OBJ + ")Z", false);
        rg.visitJumpInsn(IFEQ, rTry);
        rg.visitVarInsn(ALOAD, 0);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "registerFallback", "(" + L_LEB + L_OBJ + ")V", false);
        rg.visitInsn(RETURN);
        rg.visitLabel(rTry);
        // LegacyListenerSets.remember(group(), listener, group().register(MethodHandles.lookup(), listener)),
        // so unregister can hand EventBus 7 back exactly what it added.
        rg.visitVarInsn(ALOAD, 0);
        rg.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        rg.visitInsn(DUP);
        rg.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()L" + LOOKUP + ";", false);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitMethodInsn(INVOKEINTERFACE, BUS_GROUP, "register",
                "(L" + LOOKUP + ";" + L_OBJ + ")Ljava/util/Collection;", true);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitInsn(SWAP);
        rg.visitMethodInsn(INVOKESTATIC, LISTENER_SETS, "remember", REMEMBER_ALL_DESC, false);
        rg.visitLabel(rEnd);
        rg.visitInsn(RETURN);
        // EventBus 7 may already hold some of the class's listeners here, so none is added twice.
        rg.visitLabel(rCatch);
        rg.visitVarInsn(ASTORE, 2);
        rg.visitVarInsn(ALOAD, 1);
        rg.visitVarInsn(ALOAD, 2);
        rg.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "warnRefused", "(" + L_OBJ + "Ljava/lang/Throwable;)V", false);
        rg.visitInsn(RETURN);
        rg.visitMaxs(0, 0);
        rg.visitEnd();

        // default void unregister(Object listener) { LegacyEvents.unsubscribe(group(), listener); }
        // EventBus 7 removes listeners by the handles it returned, which the bridge kept per registered object,
        // so a split tick listener or a lambda's Pre and Post halves leave together. Where the rebuilt
        // dispatcher is active it also lets the object's own-event handlers go, so a later register hooks them again.
        MethodVisitor ug = cw.visitMethod(ACC_PUBLIC, "unregister", "(" + L_OBJ + ")V", null, null);
        ug.visitCode();
        ug.visitVarInsn(ALOAD, 0);
        ug.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        ug.visitVarInsn(ALOAD, 1);
        ug.visitMethodInsn(INVOKESTATIC, legacy ? EVENTS : LISTENER_SETS, legacy ? "unsubscribe" : "forget",
                "(" + L_BG + L_OBJ + ")V", false);
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

        // default void addListener(LegacyEventPriority p, Consumer c) { LEB_IMPL.addListenerPri(this, p, false, c); }
        MethodVisitor ap = cw.visitMethod(ACC_PUBLIC, "addListener",
                "(" + L_PRI + L_CONSUMER + ")V", null, null);
        ap.visitCode();
        ap.visitVarInsn(ALOAD, 0);
        ap.visitVarInsn(ALOAD, 1);
        ap.visitInsn(ICONST_0);
        ap.visitVarInsn(ALOAD, 2);
        ap.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerPri",
                "(" + L_LEB + L_PRI + "Z" + L_CONSUMER + ")V", false);
        ap.visitInsn(RETURN);
        ap.visitMaxs(0, 0);
        ap.visitEnd();

        // default void addListener(LegacyEventPriority p, boolean receiveCancelled, Consumer c): the flag
        // reaches hookBus, where a legacy event keeps it and a host event's cancellable bus adds a monitor.
        MethodVisitor apz = cw.visitMethod(ACC_PUBLIC, "addListener",
                "(" + L_PRI + "Z" + L_CONSUMER + ")V", null, null);
        apz.visitCode();
        apz.visitVarInsn(ALOAD, 0);
        apz.visitVarInsn(ALOAD, 1);
        apz.visitVarInsn(ILOAD, 2);
        apz.visitVarInsn(ALOAD, 3);
        apz.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerPri",
                "(" + L_LEB + L_PRI + "Z" + L_CONSUMER + ")V", false);
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
        apc.visitVarInsn(ILOAD, 2);
        apc.visitVarInsn(ALOAD, 3);
        apc.visitVarInsn(ALOAD, 4);
        apc.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addListenerTyped",
                "(" + L_LEB + L_PRI + "Z" + L_CLASS + L_CONSUMER + ")V", false);
        apc.visitInsn(RETURN);
        apc.visitMaxs(0, 0);
        apc.visitEnd();

        if (legacy) emitLegacyDefaults(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** post and the four addGenericListener forms, which only the rebuilt dispatcher can serve. */
    private static void emitLegacyDefaults(ClassWriter cw) {
        // default boolean post(Object event) { return LegacyEvents.post(group(), event); }
        MethodVisitor post = cw.visitMethod(ACC_PUBLIC, "post", "(" + L_OBJ + ")Z", null, null);
        post.visitCode();
        post.visitVarInsn(ALOAD, 0);
        post.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        post.visitVarInsn(ALOAD, 1);
        post.visitMethodInsn(INVOKESTATIC, EVENTS, "post", "(" + L_BG + L_OBJ + ")Z", false);
        post.visitInsn(IRETURN);
        post.visitMaxs(0, 0);
        post.visitEnd();

        // Each form passes (this, filter, priority or null, receiveCancelled, event type, consumer) to addGeneric.
        for (int form = 0; form < 4; form++) {
            boolean priority = form > 0;
            boolean receiveCancelled = form > 1;
            boolean typed = form > 2;
            String params = L_CLASS + (priority ? L_PRI : "") + (receiveCancelled ? "Z" : "")
                    + (typed ? L_CLASS : "") + L_CONSUMER;
            int consumer = 2 + (priority ? 1 : 0) + (receiveCancelled ? 1 : 0) + (typed ? 1 : 0);
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "addGenericListener", "(" + params + ")V", null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            if (priority) m.visitVarInsn(ALOAD, 2); else m.visitInsn(ACONST_NULL);
            if (receiveCancelled) m.visitVarInsn(ILOAD, 3); else m.visitInsn(ICONST_0);
            // Without a Class, the type comes from the Consumer's generics, as addListener's does.
            if (typed) {
                m.visitVarInsn(ALOAD, 4);
            } else {
                m.visitVarInsn(ALOAD, consumer);
                m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "eventTypeOf", "(" + L_CONSUMER + ")" + L_CLASS, false);
            }
            m.visitVarInsn(ALOAD, consumer);
            m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addGeneric", GENERIC_DESC, false);
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
    }

    /** LegacyEventBusImpl.addGeneric(bus, filter, priority, receiveCancelled, eventType, consumer). */
    private static final String GENERIC_DESC = "(" + L_LEB + L_CLASS + L_PRI + "Z" + L_CLASS + L_CONSUMER + ")V";

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

    /** The impl class as a Forge 26.2 host gets it, with the rebuilt dispatcher and cancellable listeners. */
    public static byte[] generateImpl() {
        return generateImpl(true, true);
    }

    static byte[] generateImpl(boolean legacy) {
        return generateImpl(legacy, false);
    }

    /** The impl class: the BusGroup field, GAME_BUS (BusGroup.DEFAULT), and the addListener helper. */
    static byte[] generateImpl(boolean legacy, boolean cancel) {
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

        emitSingleListenerFallback(cw, cancel);

        // static void addListenerHelper(LegacyEventBus self, Consumer c):
        //   Class ev = eventTypeOf(c); ev == null ? warn : hookBus(self.group(), ev, Priority.NORMAL, false, c)
        // A lambda carries no reified generics, so it warns. EventBus 7's addListener(c) and
        // addListener(NORMAL, c) build the same listener, so going through hookBus changes nothing
        // for an event with its own bus and lets a tick event reach its Pre and Post buses.
        MethodVisitor h = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerHelper",
                "(" + L_LEB + L_CONSUMER + ")V", null, null);
        h.visitCode();
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "eventTypeOf", "(" + L_CONSUMER + ")" + L_CLASS, false);
        h.visitVarInsn(ASTORE, 2);
        h.visitVarInsn(ALOAD, 2);
        org.objectweb.asm.Label typed = new org.objectweb.asm.Label();
        h.visitJumpInsn(IFNONNULL, typed);
        emitWarn(h, "[Retromod] Could not bridge IEventBus.addListener onto EventBus 7 (lambda "
                + "listeners carry no event type); setup listener soft-failed: ");
        h.visitInsn(RETURN);
        h.visitLabel(typed);
        h.visitVarInsn(ALOAD, 0);
        h.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        h.visitVarInsn(ALOAD, 2);
        h.visitFieldInsn(GETSTATIC, PRIORITY7, "NORMAL", "B");
        h.visitInsn(ICONST_0);
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus", HOOK_BUS_DESC, false);
        h.visitInsn(RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();

        emitEventTypeOf(cw);
        emitHookBus(cw, legacy, cancel);
        emitPriorityHelpers(cw);
        emitHookStatic(cw, cancel);
        if (legacy) emitGenericHelpers(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code static Class eventTypeOf(Consumer c)}: the event type from the Consumer class's
     * reified generic interfaces, or null (lambdas, raw types, reflection failures). Shared by
     * addListenerHelper and the priority-carrying paths.
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

    /** hookBus(BusGroup group, Class ev, byte priority, boolean receiveCancelled, Consumer c). */
    private static final String HOOK_BUS_DESC = "(" + L_BG + L_CLASS + "BZ" + L_CONSUMER + ")V";
    /** busOf(BusGroup group, Class ev): the bus a listener or post of the type belongs on. */
    private static final String BUS_OF_DESC = "(" + L_BG + L_CLASS + ")" + L_OBJ;
    /** LegacyStaticHandlers.claim(BusGroup receiving, Method handler, Object bus). */
    private static final String CLAIM_DESC = "(" + L_BG + "Ljava/lang/reflect/Method;" + L_OBJ + ")Z";
    /** LegacyListenerSets.remember(BusGroup group, Object target, Collection listeners). */
    private static final String REMEMBER_ALL_DESC = "(" + L_BG + L_OBJ + "Ljava/util/Collection;)V";
    /** LegacyListenerSets.remember(BusGroup group, Object target, Object bus, EventListener listener). */
    private static final String REMEMBER_DESC = "(" + L_BG + L_OBJ + L_OBJ + L_LISTENER7 + ")V";
    /** LegacyCancelListeners.addListener(group, registered, bus, ev, priority, receiveCancelled, c). */
    private static final String CANCEL_ADD_DESC = "(" + L_BG + L_OBJ + L_OBJ + L_CLASS + "BZ" + L_CONSUMER + ")V";
    /** LegacyCancelListeners.addHandler(group, registered, bus, Method handler, Object receiver, byte priority). */
    private static final String ADD_HANDLER_DESC = "(" + L_BG + L_OBJ + L_OBJ + "Ljava/lang/reflect/Method;" + L_OBJ + "B)V";
    /** LegacyCancelListeners.hookHandlerChildren(group, registered, Method handler, Object receiver, byte priority, String table). */
    private static final String HOOK_HANDLER_CHILDREN_DESC = "(" + L_BG + L_OBJ + "Ljava/lang/reflect/Method;" + L_OBJ
            + "BLjava/lang/String;)Z";
    private static final String MOD_BUS_EVENT = "net/minecraftforge/fml/event/IModBusEvent";
    /** LegacyListenerSets.remember(group, target, bus, listener, Method handler, Object receiver). */
    private static final String REMEMBER_HANDLER_DESC = "(" + L_BG + L_OBJ + L_OBJ + L_LISTENER7
            + "Ljava/lang/reflect/Method;" + L_OBJ + ")V";

    /** The parents with no bus and the children their listeners move to on the target, written for the bus bridge. */
    private static String buslessTable(boolean cancel) {
        // The children are hooked by LegacyCancelListeners, which only a host with cancellable buses gets.
        return cancel ? LegacyCancelBridge.buslessTable(com.retromod.core.RetromodVersion.TARGET_MC_VERSION) : "";
    }

    /**
     * {@code static void hookBus(BusGroup g, Class ev, byte priority, boolean receiveCancelled, Consumer c)}:
     * add the listener to the event's bus in the receiving bus's group; any failure warns and no-ops.
     */
    private static void emitHookBus(ClassWriter cw, boolean legacy, boolean cancel) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hookBus", HOOK_BUS_DESC, null, null);
        m.visitCode();
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchAll = new org.objectweb.asm.Label();
        m.visitTryCatchBlock(tryStart, tryEnd, catchAll, "java/lang/Throwable");
        m.visitLabel(tryStart);
        if (legacy) {
            // A mod's own event keeps Forge 1.20.1's order, cancel and phase rules in the rebuilt dispatcher:
            // if (LegacyEvents.isLegacyEvent(ev)) { LegacyEvents.addListener(g, ev, priority, receiveCancelled, null, c); return; }
            org.objectweb.asm.Label hostEvent = new org.objectweb.asm.Label();
            m.visitVarInsn(ALOAD, 1);
            m.visitMethodInsn(INVOKESTATIC, EVENTS, "isLegacyEvent", "(" + L_CLASS + ")Z", false);
            m.visitJumpInsn(IFEQ, hostEvent);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitVarInsn(ILOAD, 2);
            m.visitVarInsn(ILOAD, 3);
            m.visitInsn(ACONST_NULL);
            m.visitVarInsn(ALOAD, 4);
            m.visitMethodInsn(INVOKESTATIC, EVENTS, "addListener",
                    "(" + L_BG + L_CLASS + "BZLjava/lang/reflect/Type;" + L_CONSUMER + ")V", false);
            m.visitInsn(RETURN);
            m.visitLabel(hostEvent);
        }
        emitTickSplit(m);
        String busless = buslessTable(cancel);
        if (!busless.isEmpty()) {
            // A parent nothing posts reaches its events through its children, which the transform named here:
            // if (LegacyCancelListeners.hookChildren(g, ev, priority, receiveCancelled, c, "parent=child,...;...")) return;
            org.objectweb.asm.Label posted = new org.objectweb.asm.Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitVarInsn(ILOAD, 2);
            m.visitVarInsn(ILOAD, 3);
            m.visitVarInsn(ALOAD, 4);
            m.visitLdcInsn(busless);
            m.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "hookChildren",
                    "(" + L_BG + L_CLASS + "BZ" + L_CONSUMER + "Ljava/lang/String;)Z", false);
            m.visitJumpInsn(IFEQ, posted);
            m.visitInsn(RETURN);
            m.visitLabel(posted);
        }
        // Object bus = busOf(g, ev); each listener added below is kept under c, so unregister(c) finds every bus it went onto.
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "busOf", BUS_OF_DESC, false);
        m.visitVarInsn(ASTORE, 6);
        // Consumer run = LegacyModNamespaces.ofListener(g, ev, c): a listener a mod added to its mod bus for an event
        // Forge 26.2 posts elsewhere names that mod while it runs; the listener stays kept under c.
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKESTATIC, NAMESPACES, "ofListener", OF_LISTENER_DESC, false);
        m.visitVarInsn(ASTORE, 7);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 4);
        m.visitVarInsn(ALOAD, 6);
        if (cancel) {
            // LegacyCancelListeners.addListener(g, c, bus, ev, priority, receiveCancelled, run): a predicate where the
            // event takes a cancel, so an old listener's setCanceled(true) reaches EventBus 7; a plain Consumer elsewhere.
            m.visitVarInsn(ALOAD, 1);
            m.visitVarInsn(ILOAD, 2);
            m.visitVarInsn(ILOAD, 3);
            m.visitVarInsn(ALOAD, 7);
            m.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "addListener", CANCEL_ADD_DESC, false);
        } else {
            // LegacyListenerSets.remember(g, c, bus, ((EventBus) bus).addListener(priority, run))
            m.visitVarInsn(ALOAD, 6);
            m.visitTypeInsn(CHECKCAST, EVENT_BUS7);
            m.visitVarInsn(ILOAD, 2);
            m.visitVarInsn(ALOAD, 7);
            m.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS7, "addListener",
                    "(B" + L_CONSUMER + ")" + L_LISTENER7, true);
            m.visitMethodInsn(INVOKESTATIC, LISTENER_SETS, "remember", REMEMBER_DESC, false);
        }
        String logger = String.join("/", "org", "slf4j", "Logger");
        m.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        m.visitLdcInsn("[Retromod] Hooked a listener onto EventBus 7 for {}");
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, logger, "debug", "(Ljava/lang/String;" + L_OBJ + ")V", true);
        m.visitLabel(tryEnd);
        m.visitInsn(RETURN);
        m.visitLabel(catchAll);
        m.visitVarInsn(ASTORE, 5);
        m.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        m.visitLdcInsn("[Retromod] Could not hook a listener onto EventBus 7 for {}: {}");
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 5);
        m.visitMethodInsn(INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;" + L_OBJ + L_OBJ + ")V", true);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        MethodVisitor b = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "busOf", BUS_OF_DESC, null,
                new String[]{"java/lang/Exception"});
        b.visitCode();
        if (legacy) {
            // static Object busOf(BusGroup g, Class ev) { return LegacyEvents.busOf(g, ev); }
            b.visitVarInsn(ALOAD, 0);
            b.visitVarInsn(ALOAD, 1);
            b.visitMethodInsn(INVOKESTATIC, EVENTS, "busOf", "(" + L_BG + L_CLASS + ")L" + EVENT_BUS7 + ";", false);
            b.visitInsn(ARETURN);
            b.visitMaxs(0, 0);
            b.visitEnd();
            return;
        }
        // static Object busOf(BusGroup g, Class ev): a game event's static BUS, or the type's bus in the group.
        org.objectweb.asm.Label fieldStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fieldEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label noField = new org.objectweb.asm.Label();
        b.visitTryCatchBlock(fieldStart, fieldEnd, noField, "java/lang/NoSuchFieldException");
        b.visitLabel(fieldStart);
        b.visitVarInsn(ALOAD, 1);
        b.visitLdcInsn("BUS");
        b.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        b.visitInsn(ACONST_NULL);
        b.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "get", "(" + L_OBJ + ")" + L_OBJ, false);
        b.visitLabel(fieldEnd);
        b.visitInsn(ARETURN);
        b.visitLabel(noField);
        b.visitInsn(POP);
        // A mod's group takes only mod-bus events, so any other event goes on the game group's bus for its type:
        // if (!IModBusEvent.class.isAssignableFrom(ev)) return EventBus.create(BusGroup.DEFAULT, ev);
        org.objectweb.asm.Label modBusEvent = new org.objectweb.asm.Label();
        b.visitLdcInsn(org.objectweb.asm.Type.getObjectType(MOD_BUS_EVENT));
        b.visitVarInsn(ALOAD, 1);
        b.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isAssignableFrom", "(" + L_CLASS + ")Z", false);
        b.visitJumpInsn(IFNE, modBusEvent);
        b.visitFieldInsn(GETSTATIC, BUS_GROUP, "DEFAULT", L_BG);
        b.visitVarInsn(ALOAD, 1);
        b.visitMethodInsn(INVOKESTATIC, EVENT_BUS7, "create", "(" + L_BG + L_CLASS + ")L" + EVENT_BUS7 + ";", true);
        b.visitInsn(ARETURN);
        b.visitLabel(modBusEvent);
        b.visitVarInsn(ALOAD, 0);
        b.visitVarInsn(ALOAD, 1);
        b.visitMethodInsn(INVOKESTATIC, MOD_BUS_EVENT, "getBus",
                "(" + L_BG + L_CLASS + ")L" + EVENT_BUS7 + ";", true);
        b.visitInsn(ARETURN);
        b.visitMaxs(0, 0);
        b.visitEnd();
    }

    /** The first host step of hookBus: a tick event's listener goes onto its Pre and Post records. */
    private static void emitTickSplit(MethodVisitor m) {
        // A Forge 26.2 tick event is an interface with no bus, and the old event fired at both ends
        // of a tick. if (ev.isInterface() && ev.getName().startsWith("net.minecraftforge.event.TickEvent$")):
        // the interface check keeps a host whose tick events are classes again on the plain path.
        org.objectweb.asm.Label plain = new org.objectweb.asm.Label();
        org.objectweb.asm.Label nested = new org.objectweb.asm.Label();
        String prefix = LegacyTickEventBridge.TICK.replace('/', '.') + "$";
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isInterface", "()Z", false);
        m.visitJumpInsn(IFEQ, plain);
        // The base TickEvent heard all five old events, so each of them goes through this split for its halves:
        // if (prefix.equals(ev.getName().concat("$"))) { hookBus(g, <ev>$ClientTickEvent, ...); ...; return; }
        m.visitLdcInsn(prefix);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        m.visitLdcInsn("$");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(" + L_OBJ + ")Z", false);
        m.visitJumpInsn(IFEQ, nested);
        for (String event : LegacyTickEventBridge.EVENTS) emitHookNamed(m, "$" + event);
        m.visitInsn(RETURN);
        m.visitLabel(nested);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        m.visitLdcInsn(prefix);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
        m.visitJumpInsn(IFEQ, plain);
        for (String half : new String[]{"$Pre", "$Post"}) emitHookNamed(m, half);
        m.visitInsn(RETURN);
        m.visitLabel(plain);
    }

    /**
     * {@code hookBus(g, Class.forName(ev.getName().concat(suffix), false, ev.getClassLoader()), priority, receiveCancelled, c)}.
     * The names are built at runtime, so no full class name sits in a constant.
     */
    private static void emitHookNamed(MethodVisitor m, String suffix) {
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        m.visitLdcInsn(suffix);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)" + L_CLASS, false);
        m.visitVarInsn(ILOAD, 2);
        m.visitVarInsn(ILOAD, 3);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus", HOOK_BUS_DESC, false);
    }

    /** The priority-carrying addListener helpers (#101), all funneling into hookBus with the bus's own group. */
    private static void emitPriorityHelpers(ClassWriter cw) {
        // static void addListenerPri(LEB self, PRI p, boolean receiveCancelled, Consumer c):
        //   Class ev = eventTypeOf(c); ev == null ? warn : hookBus(self.group(), ev, bitsOf(p), receiveCancelled, c)
        MethodVisitor p = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerPri",
                "(" + L_LEB + L_PRI + "Z" + L_CONSUMER + ")V", null, null);
        p.visitCode();
        p.visitVarInsn(ALOAD, 3);
        p.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "eventTypeOf",
                "(" + L_CONSUMER + ")" + L_CLASS, false);
        p.visitVarInsn(ASTORE, 4);
        p.visitVarInsn(ALOAD, 4);
        org.objectweb.asm.Label ok = new org.objectweb.asm.Label();
        p.visitJumpInsn(IFNONNULL, ok);
        emitWarn(p, "[Retromod] Could not bridge IEventBus.addListener(priority, ...) onto "
                + "EventBus 7 (lambda listeners carry no event type); listener soft-failed: ", 3);
        p.visitInsn(RETURN);
        p.visitLabel(ok);
        emitHookCall(p, 0, 4, 1, 2, 3);
        p.visitInsn(RETURN);
        p.visitMaxs(0, 0);
        p.visitEnd();

        // static void addListenerTyped(LEB self, PRI p, boolean receiveCancelled, Class ev, Consumer c)
        MethodVisitor t = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerTyped",
                "(" + L_LEB + L_PRI + "Z" + L_CLASS + L_CONSUMER + ")V", null, null);
        t.visitCode();
        emitHookCall(t, 0, 3, 1, 2, 4);
        t.visitInsn(RETURN);
        t.visitMaxs(0, 0);
        t.visitEnd();

        // Indy-typed targets: the transform appends the lambda's event type as the LAST arg.
        // static void addListenerTypedIndy(LEB self, Consumer c, Class ev)
        MethodVisitor i1 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerTypedIndy",
                "(" + L_LEB + L_CONSUMER + L_CLASS + ")V", null, null);
        i1.visitCode();
        emitHookCall(i1, 0, 2, -1, -1, 1);
        i1.visitInsn(RETURN);
        i1.visitMaxs(0, 0);
        i1.visitEnd();

        // static void addListenerPriTypedIndy(LEB self, PRI p, Consumer c, Class ev)
        MethodVisitor i2 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerPriTypedIndy",
                "(" + L_LEB + L_PRI + L_CONSUMER + L_CLASS + ")V", null, null);
        i2.visitCode();
        emitHookCall(i2, 0, 3, 1, -1, 2);
        i2.visitInsn(RETURN);
        i2.visitMaxs(0, 0);
        i2.visitEnd();

        // static void addListenerPriZTypedIndy(LEB self, PRI p, boolean z, Consumer c, Class ev)
        MethodVisitor i3 = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addListenerPriZTypedIndy",
                "(" + L_LEB + L_PRI + "Z" + L_CONSUMER + L_CLASS + ")V", null, null);
        i3.visitCode();
        emitHookCall(i3, 0, 4, 1, 2, 3);
        i3.visitInsn(RETURN);
        i3.visitMaxs(0, 0);
        i3.visitEnd();
    }

    /**
     * {@code hookBus(self.group(), ev, priority, receiveCancelled, c)} from the given local slots; a
     * slot of -1 for the priority means NORMAL, and for receiveCancelled means false.
     */
    private static void emitHookCall(MethodVisitor m, int self, int event, int priority, int receiveCancelled,
            int consumer) {
        m.visitVarInsn(ALOAD, self);
        m.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        m.visitVarInsn(ALOAD, event);
        if (priority < 0) {
            m.visitFieldInsn(GETSTATIC, PRIORITY7, "NORMAL", "B");
        } else {
            m.visitVarInsn(ALOAD, priority);
            m.visitMethodInsn(INVOKESTATIC, PRI, "bitsOf", "(" + L_OBJ + ")B", false);
        }
        if (receiveCancelled < 0) m.visitInsn(ICONST_0); else m.visitVarInsn(ILOAD, receiveCancelled);
        m.visitVarInsn(ALOAD, consumer);
        m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookBus", HOOK_BUS_DESC, false);
    }

    /** The addGenericListener helpers, each handing the rebuilt dispatcher the bus's group and the old values. */
    private static void emitGenericHelpers(ClassWriter cw) {
        // static void addGeneric(LEB self, Class filter, PRI p, boolean receiveCancelled, Class ev, Consumer c):
        //   LegacyEvents.addGenericListener(self.group(), ev, filter, bitsOf(p), receiveCancelled, c)
        MethodVisitor g = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addGeneric", GENERIC_DESC, null, null);
        g.visitCode();
        g.visitVarInsn(ALOAD, 0);
        g.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
        g.visitVarInsn(ALOAD, 4);
        g.visitVarInsn(ALOAD, 1);
        g.visitVarInsn(ALOAD, 2);
        g.visitMethodInsn(INVOKESTATIC, PRI, "bitsOf", "(" + L_OBJ + ")B", false);
        g.visitVarInsn(ILOAD, 3);
        g.visitVarInsn(ALOAD, 5);
        g.visitMethodInsn(INVOKESTATIC, EVENTS, "addGenericListener",
                "(" + L_BG + L_CLASS + L_CLASS + "BZ" + L_CONSUMER + ")V", false);
        g.visitInsn(RETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        // The indy-typed forms carry the lambda's event type last: (self, filter, [p], [z], c, ev).
        String[] names = {"addGenericListenerTypedIndy", "addGenericListenerPriTypedIndy", "addGenericListenerPriZTypedIndy"};
        for (int form = 0; form < names.length; form++) {
            boolean priority = form > 0;
            boolean receiveCancelled = form > 1;
            int consumer = 2 + (priority ? 1 : 0) + (receiveCancelled ? 1 : 0);
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, names[form], "(" + L_LEB + L_CLASS
                    + (priority ? L_PRI : "") + (receiveCancelled ? "Z" : "") + L_CONSUMER + L_CLASS + ")V", null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            if (priority) m.visitVarInsn(ALOAD, 2); else m.visitInsn(ACONST_NULL);
            if (receiveCancelled) m.visitVarInsn(ILOAD, 3); else m.visitInsn(ICONST_0);
            m.visitVarInsn(ALOAD, consumer + 1);
            m.visitVarInsn(ALOAD, consumer);
            m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "addGeneric", GENERIC_DESC, false);
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
    }

    /** The helpers {@code register} uses for a listener class EventBus 7 would refuse or cannot reach. */
    private static void emitSingleListenerFallback(ClassWriter cw, boolean cancel) {
        String method = "java/lang/reflect/Method";
        String lMethod = "L" + method + ";";
        String logger = String.join("/", "org", "slf4j", "Logger");

        // static boolean needsFallback(Object o): true for a class, whose static handlers EventBus 7's
        // register(Object) would never see, and when the object's class is not public, declares a
        // listener that is not public or is static, or declares fewer than two listeners. Any
        // failure answers false, so EventBus 7 reports it as it did before.
        MethodVisitor n = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "needsFallback", "(" + L_OBJ + ")Z", null, null);
        n.visitCode();
        org.objectweb.asm.Label nTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label nCatch = new org.objectweb.asm.Label();
        org.objectweb.asm.Label nLoop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label nNext = new org.objectweb.asm.Label();
        org.objectweb.asm.Label nDone = new org.objectweb.asm.Label();
        org.objectweb.asm.Label yes = new org.objectweb.asm.Label();
        n.visitTryCatchBlock(nTry, nCatch, nCatch, "java/lang/Throwable");
        n.visitLabel(nTry);
        n.visitVarInsn(ALOAD, 0);
        n.visitTypeInsn(INSTANCEOF, "java/lang/Class");
        n.visitJumpInsn(IFNE, yes);
        n.visitVarInsn(ALOAD, 0);
        n.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()" + L_CLASS, false);
        n.visitVarInsn(ASTORE, 1);
        n.visitVarInsn(ALOAD, 1);
        n.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getModifiers", "()I", false);
        n.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Modifier", "isPublic", "(I)Z", false);
        n.visitJumpInsn(IFEQ, yes);
        // Count the methods EventBus 7 would take as listeners, as its lenient register does.
        n.visitVarInsn(ALOAD, 1);
        n.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethods", "()[" + lMethod, false);
        n.visitVarInsn(ASTORE, 2);
        n.visitInsn(ICONST_0);
        n.visitVarInsn(ISTORE, 3);
        n.visitInsn(ICONST_0);
        n.visitVarInsn(ISTORE, 4);
        n.visitLabel(nLoop);
        n.visitVarInsn(ILOAD, 3);
        n.visitVarInsn(ALOAD, 2);
        n.visitInsn(ARRAYLENGTH);
        n.visitJumpInsn(IF_ICMPGE, nDone);
        n.visitVarInsn(ALOAD, 2);
        n.visitVarInsn(ILOAD, 3);
        n.visitInsn(AALOAD);
        n.visitVarInsn(ASTORE, 5);
        n.visitVarInsn(ALOAD, 5);
        n.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "isListener", "(" + lMethod + ")Z", false);
        n.visitJumpInsn(IFEQ, nNext);
        n.visitVarInsn(ALOAD, 5);
        n.visitMethodInsn(INVOKEVIRTUAL, method, "getModifiers", "()I", false);
        n.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Modifier", "isPublic", "(I)Z", false);
        n.visitJumpInsn(IFEQ, yes);
        // EventBus 7's lenient register would add a static listener for this instance as well.
        n.visitVarInsn(ALOAD, 5);
        n.visitMethodInsn(INVOKEVIRTUAL, method, "getModifiers", "()I", false);
        n.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Modifier", "isStatic", "(I)Z", false);
        n.visitJumpInsn(IFNE, yes);
        n.visitIincInsn(4, 1);
        n.visitLabel(nNext);
        n.visitIincInsn(3, 1);
        n.visitJumpInsn(GOTO, nLoop);
        n.visitLabel(nDone);
        n.visitVarInsn(ILOAD, 4);
        n.visitInsn(ICONST_2);
        n.visitJumpInsn(IF_ICMPLT, yes);
        n.visitInsn(ICONST_0);
        n.visitInsn(IRETURN);
        n.visitLabel(yes);
        n.visitInsn(ICONST_1);
        n.visitInsn(IRETURN);
        n.visitLabel(nCatch);
        n.visitInsn(POP);
        n.visitInsn(ICONST_0);
        n.visitInsn(IRETURN);
        n.visitMaxs(0, 0);
        n.visitEnd();

        // static boolean isListener(Method m): EventBus 7's rule, a non-synthetic @SubscribeEvent
        // method with one or two parameters that returns void or boolean.
        MethodVisitor l = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "isListener", "(" + lMethod + ")Z", null, null);
        l.visitCode();
        org.objectweb.asm.Label no = new org.objectweb.asm.Label();
        org.objectweb.asm.Label returns = new org.objectweb.asm.Label();
        l.visitVarInsn(ALOAD, 0);
        l.visitMethodInsn(INVOKEVIRTUAL, method, "isSynthetic", "()Z", false);
        l.visitJumpInsn(IFNE, no);
        l.visitVarInsn(ALOAD, 0);
        l.visitMethodInsn(INVOKEVIRTUAL, method, "getParameterCount", "()I", false);
        l.visitVarInsn(ISTORE, 1);
        l.visitVarInsn(ILOAD, 1);
        l.visitJumpInsn(IFLE, no);
        l.visitVarInsn(ILOAD, 1);
        l.visitInsn(ICONST_2);
        l.visitJumpInsn(IF_ICMPGT, no);
        l.visitVarInsn(ALOAD, 0);
        l.visitMethodInsn(INVOKEVIRTUAL, method, "getReturnType", "()" + L_CLASS, false);
        l.visitVarInsn(ASTORE, 2);
        l.visitVarInsn(ALOAD, 2);
        l.visitFieldInsn(GETSTATIC, "java/lang/Void", "TYPE", L_CLASS);
        l.visitJumpInsn(IF_ACMPEQ, returns);
        l.visitVarInsn(ALOAD, 2);
        l.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TYPE", L_CLASS);
        l.visitJumpInsn(IF_ACMPNE, no);
        l.visitLabel(returns);
        l.visitVarInsn(ALOAD, 0);
        l.visitLdcInsn(org.objectweb.asm.Type.getObjectType(NEW_SUBSCRIBE));
        l.visitMethodInsn(INVOKEVIRTUAL, method, "isAnnotationPresent", "(" + L_CLASS + ")Z", false);
        l.visitInsn(IRETURN);
        l.visitLabel(no);
        l.visitInsn(ICONST_0);
        l.visitInsn(IRETURN);
        l.visitMaxs(0, 0);
        l.visitEnd();

        // static void registerFallback(LegacyEventBus self, Object o): an object gives every instance
        // listener its class declares, public or not, then the public ones it inherits. A class given
        // to register(Class) gives only its public static ones, the ones EventBus 6's getMethods() saw.
        MethodVisitor rf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerFallback", "(" + L_LEB + L_OBJ + ")V", null, null);
        rf.visitCode();
        org.objectweb.asm.Label fTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label fCatch = new org.objectweb.asm.Label();
        org.objectweb.asm.Label ofObject = new org.objectweb.asm.Label();
        org.objectweb.asm.Label owned = new org.objectweb.asm.Label();
        rf.visitTryCatchBlock(fTry, fEnd, fCatch, "java/lang/Throwable");
        rf.visitLabel(fTry);
        // boolean statics = o instanceof Class; Class c = statics ? (Class) o : o.getClass(); Object target = statics ? null : o;
        rf.visitVarInsn(ALOAD, 1);
        rf.visitTypeInsn(INSTANCEOF, "java/lang/Class");
        rf.visitVarInsn(ISTORE, 5);
        rf.visitVarInsn(ILOAD, 5);
        rf.visitJumpInsn(IFEQ, ofObject);
        rf.visitVarInsn(ALOAD, 1);
        rf.visitTypeInsn(CHECKCAST, "java/lang/Class");
        rf.visitVarInsn(ASTORE, 2);
        rf.visitInsn(ACONST_NULL);
        rf.visitVarInsn(ASTORE, 6);
        rf.visitJumpInsn(GOTO, owned);
        rf.visitLabel(ofObject);
        rf.visitVarInsn(ALOAD, 1);
        rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()" + L_CLASS, false);
        rf.visitVarInsn(ASTORE, 2);
        rf.visitVarInsn(ALOAD, 1);
        rf.visitVarInsn(ASTORE, 6);
        rf.visitLabel(owned);
        for (boolean inherited : new boolean[]{false, true}) {
            org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
            org.objectweb.asm.Label next = new org.objectweb.asm.Label();
            org.objectweb.asm.Label done = new org.objectweb.asm.Label();
            rf.visitVarInsn(ALOAD, 2);
            rf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", inherited ? "getMethods" : "getDeclaredMethods",
                    "()[" + lMethod, false);
            rf.visitVarInsn(ASTORE, 3);
            rf.visitInsn(ICONST_0);
            rf.visitVarInsn(ISTORE, 4);
            rf.visitLabel(loop);
            rf.visitVarInsn(ILOAD, 4);
            rf.visitVarInsn(ALOAD, 3);
            rf.visitInsn(ARRAYLENGTH);
            rf.visitJumpInsn(IF_ICMPGE, done);
            if (inherited) {
                // getMethods() also lists the class's own public methods, which the first loop took.
                rf.visitVarInsn(ALOAD, 3);
                rf.visitVarInsn(ILOAD, 4);
                rf.visitInsn(AALOAD);
                rf.visitMethodInsn(INVOKEVIRTUAL, method, "getDeclaringClass", "()" + L_CLASS, false);
                rf.visitVarInsn(ALOAD, 2);
                rf.visitJumpInsn(IF_ACMPEQ, next);
            }
            // EventBus 6 took only instance handlers from an object and only static ones from a class.
            rf.visitVarInsn(ALOAD, 3);
            rf.visitVarInsn(ILOAD, 4);
            rf.visitInsn(AALOAD);
            rf.visitMethodInsn(INVOKEVIRTUAL, method, "getModifiers", "()I", false);
            rf.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Modifier", "isStatic", "(I)Z", false);
            rf.visitVarInsn(ILOAD, 5);
            rf.visitJumpInsn(IF_ICMPNE, next);
            // A static handler that is not public was never registered on EventBus 6, so it stays inert.
            org.objectweb.asm.Label hook = new org.objectweb.asm.Label();
            rf.visitVarInsn(ILOAD, 5);
            rf.visitJumpInsn(IFEQ, hook);
            rf.visitVarInsn(ALOAD, 3);
            rf.visitVarInsn(ILOAD, 4);
            rf.visitInsn(AALOAD);
            rf.visitMethodInsn(INVOKEVIRTUAL, method, "getModifiers", "()I", false);
            rf.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Modifier", "isPublic", "(I)Z", false);
            rf.visitJumpInsn(IFEQ, next);
            rf.visitLabel(hook);
            rf.visitVarInsn(ALOAD, 0);
            rf.visitMethodInsn(INVOKEINTERFACE, LEB, "group", "()" + L_BG, true);
            rf.visitVarInsn(ALOAD, 3);
            rf.visitVarInsn(ILOAD, 4);
            rf.visitInsn(AALOAD);
            rf.visitVarInsn(ALOAD, 6);
            rf.visitVarInsn(ALOAD, 1);
            rf.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookListener", "(" + L_BG + lMethod + L_OBJ + L_OBJ + ")V", false);
            rf.visitLabel(next);
            rf.visitIincInsn(4, 1);
            rf.visitJumpInsn(GOTO, loop);
            rf.visitLabel(done);
        }
        rf.visitLabel(fEnd);
        rf.visitInsn(RETURN);
        rf.visitLabel(fCatch);
        rf.visitInsn(POP);
        emitWarn(rf, "[Retromod] EventBus 7 single-listener fallback failed for: ");
        rf.visitInsn(RETURN);
        rf.visitMaxs(0, 0);
        rf.visitEnd();

        // static void hookListener(BusGroup g, Method m, Object o, Object registered): a one-parameter @SubscribeEvent
        // method goes onto its event's bus at the annotation's priority, kept under the object or class registered;
        // one that fails warns and the rest still run.
        MethodVisitor h = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "hookListener",
                "(" + L_BG + lMethod + L_OBJ + L_OBJ + ")V", null, null);
        h.visitCode();
        org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
        org.objectweb.asm.Label hTry = new org.objectweb.asm.Label();
        org.objectweb.asm.Label hEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label hCatch = new org.objectweb.asm.Label();
        h.visitTryCatchBlock(hTry, hEnd, hCatch, "java/lang/Throwable");
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKEVIRTUAL, method, "isSynthetic", "()Z", false);
        h.visitJumpInsn(IFNE, skip);
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKEVIRTUAL, method, "getParameterCount", "()I", false);
        h.visitInsn(ICONST_1);
        if (cancel) {
            // A cancel twin's monitor takes the event and the post's answer.
            org.objectweb.asm.Label counted = new org.objectweb.asm.Label();
            h.visitJumpInsn(IF_ICMPEQ, counted);
            h.visitVarInsn(ALOAD, 1);
            h.visitMethodInsn(INVOKEVIRTUAL, method, "getParameterCount", "()I", false);
            h.visitInsn(ICONST_2);
            h.visitJumpInsn(IF_ICMPNE, skip);
            h.visitLabel(counted);
        } else {
            h.visitJumpInsn(IF_ICMPNE, skip);
        }
        h.visitVarInsn(ALOAD, 1);
        h.visitLdcInsn(org.objectweb.asm.Type.getObjectType(NEW_SUBSCRIBE));
        h.visitMethodInsn(INVOKEVIRTUAL, method, "isAnnotationPresent", "(" + L_CLASS + ")Z", false);
        h.visitJumpInsn(IFEQ, skip);
        h.visitLabel(hTry);
        // The handler may be private; this copy sits in the mod's own module, so it may open it.
        h.visitVarInsn(ALOAD, 1);
        h.visitInsn(ICONST_1);
        h.visitMethodInsn(INVOKEVIRTUAL, method, "setAccessible", "(Z)V", false);
        String busless = buslessTable(cancel);
        if (!busless.isEmpty()) {
            // A parent nothing posts reaches its events through the children the transform named:
            // if (LegacyCancelListeners.hookHandlerChildren(g, registered, m, o, annotation.priority(), table)) return;
            h.visitVarInsn(ALOAD, 0);
            h.visitVarInsn(ALOAD, 3);
            h.visitVarInsn(ALOAD, 1);
            h.visitVarInsn(ALOAD, 2);
            h.visitVarInsn(ALOAD, 1);
            h.visitLdcInsn(org.objectweb.asm.Type.getObjectType(NEW_SUBSCRIBE));
            h.visitMethodInsn(INVOKEVIRTUAL, method, "getAnnotation",
                    "(" + L_CLASS + ")Ljava/lang/annotation/Annotation;", false);
            h.visitTypeInsn(CHECKCAST, NEW_SUBSCRIBE);
            h.visitMethodInsn(INVOKEINTERFACE, NEW_SUBSCRIBE, "priority", "()B", true);
            h.visitLdcInsn(busless);
            h.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "hookHandlerChildren", HOOK_HANDLER_CHILDREN_DESC, false);
            h.visitJumpInsn(IFNE, hEnd);
        }
        // Object bus = busOf(g, m.getParameterTypes()[0]);
        h.visitVarInsn(ALOAD, 0);
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKEVIRTUAL, method, "getParameterTypes", "()[" + L_CLASS, false);
        h.visitInsn(ICONST_0);
        h.visitInsn(AALOAD);
        h.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "busOf", BUS_OF_DESC, false);
        h.visitVarInsn(ASTORE, 4);
        // A static handler FML or an earlier registration already put on this bus is not added again.
        org.objectweb.asm.Label add = new org.objectweb.asm.Label();
        h.visitVarInsn(ALOAD, 2);
        h.visitJumpInsn(IFNONNULL, add);
        h.visitVarInsn(ALOAD, 0);
        h.visitVarInsn(ALOAD, 1);
        h.visitVarInsn(ALOAD, 4);
        h.visitMethodInsn(INVOKESTATIC, STATIC_HANDLERS, "claim", CLAIM_DESC, false);
        h.visitJumpInsn(IFEQ, hEnd);
        h.visitLabel(add);
        if (cancel) {
            // A boolean twin goes on as a predicate and an (event, boolean) twin as a monitor, so a cancel reaches
            // EventBus 7: if (LegacyCancelListeners.isCancelAware(m)) { LegacyCancelListeners.addHandler(g,
            // registered, bus, m, o, priority); }
            org.objectweb.asm.Label consumer = new org.objectweb.asm.Label();
            h.visitVarInsn(ALOAD, 1);
            h.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "isCancelAware", "(" + lMethod + ")Z", false);
            h.visitJumpInsn(IFEQ, consumer);
            h.visitVarInsn(ALOAD, 0);
            h.visitVarInsn(ALOAD, 3);
            h.visitVarInsn(ALOAD, 4);
            h.visitVarInsn(ALOAD, 1);
            h.visitVarInsn(ALOAD, 2);
            h.visitVarInsn(ALOAD, 1);
            h.visitLdcInsn(org.objectweb.asm.Type.getObjectType(NEW_SUBSCRIBE));
            h.visitMethodInsn(INVOKEVIRTUAL, method, "getAnnotation",
                    "(" + L_CLASS + ")Ljava/lang/annotation/Annotation;", false);
            h.visitTypeInsn(CHECKCAST, NEW_SUBSCRIBE);
            h.visitMethodInsn(INVOKEINTERFACE, NEW_SUBSCRIBE, "priority", "()B", true);
            h.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "addHandler", ADD_HANDLER_DESC, false);
            h.visitJumpInsn(GOTO, hEnd);
            h.visitLabel(consumer);
        }
        // ((EventBus) bus).addListener(annotation.priority(), new ReflectedConsumer(m, o))
        h.visitVarInsn(ALOAD, 4);
        h.visitTypeInsn(CHECKCAST, EVENT_BUS7);
        h.visitVarInsn(ALOAD, 1);
        h.visitLdcInsn(org.objectweb.asm.Type.getObjectType(NEW_SUBSCRIBE));
        h.visitMethodInsn(INVOKEVIRTUAL, method, "getAnnotation",
                "(" + L_CLASS + ")Ljava/lang/annotation/Annotation;", false);
        h.visitTypeInsn(CHECKCAST, NEW_SUBSCRIBE);
        h.visitMethodInsn(INVOKEINTERFACE, NEW_SUBSCRIBE, "priority", "()B", true);
        // LegacyModNamespaces.ofListener(g, m.getParameterTypes()[0], new ReflectedConsumer(m, o)), as hookBus does.
        h.visitVarInsn(ALOAD, 0);
        h.visitVarInsn(ALOAD, 1);
        h.visitMethodInsn(INVOKEVIRTUAL, method, "getParameterTypes", "()[" + L_CLASS, false);
        h.visitInsn(ICONST_0);
        h.visitInsn(AALOAD);
        h.visitTypeInsn(NEW, RC);
        h.visitInsn(DUP);
        h.visitVarInsn(ALOAD, 1);
        h.visitVarInsn(ALOAD, 2);
        h.visitMethodInsn(INVOKESPECIAL, RC, "<init>", "(" + lMethod + L_OBJ + ")V", false);
        h.visitMethodInsn(INVOKESTATIC, NAMESPACES, "ofListener", OF_LISTENER_DESC, false);
        h.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS7, "addListener", "(B" + L_CONSUMER + ")" + L_LISTENER7, true);
        // LegacyListenerSets.remember(g, registered, bus, listener, m, o), so unregister removes it with the rest
        // and lets a static handler's claim go.
        h.visitVarInsn(ASTORE, 6);
        h.visitVarInsn(ALOAD, 0);
        h.visitVarInsn(ALOAD, 3);
        h.visitVarInsn(ALOAD, 4);
        h.visitVarInsn(ALOAD, 6);
        h.visitVarInsn(ALOAD, 1);
        h.visitVarInsn(ALOAD, 2);
        h.visitMethodInsn(INVOKESTATIC, LISTENER_SETS, "remember", REMEMBER_HANDLER_DESC, false);
        h.visitLabel(hEnd);
        h.visitLabel(skip);
        h.visitInsn(RETURN);
        h.visitLabel(hCatch);
        h.visitVarInsn(ASTORE, 5);
        h.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        h.visitLdcInsn("[Retromod] The old event handler {} could not be hooked onto EventBus 7 and will not run: {}");
        h.visitVarInsn(ALOAD, 1);
        h.visitVarInsn(ALOAD, 5);
        h.visitMethodInsn(INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;" + L_OBJ + L_OBJ + ")V", true);
        h.visitInsn(RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();

        // static void warnRefused(Object o, Throwable reason): names the class EventBus 7 refused.
        MethodVisitor w = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "warnRefused",
                "(" + L_OBJ + "Ljava/lang/Throwable;)V", null, null);
        w.visitCode();
        w.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        w.visitLdcInsn("[Retromod] EventBus 7 refused the event handlers of {}, so the ones it did not add will not run: {}");
        w.visitVarInsn(ALOAD, 0);
        w.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()" + L_CLASS, false);
        w.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        w.visitVarInsn(ALOAD, 1);
        w.visitMethodInsn(INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;" + L_OBJ + L_OBJ + ")V", true);
        w.visitInsn(RETURN);
        w.visitMaxs(0, 0);
        w.visitEnd();
    }

    /** {@code static void hookStatic(String owner, String handler, String event, byte priority)}, called by a companion. */
    private static void emitHookStatic(ClassWriter cw, boolean cancel) {
        // Hooks one static handler of an old @Mod.EventBusSubscriber class onto its event's bus.
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hookStatic",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;B)V", null, null);
        m.visitCode();
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchAll = new org.objectweb.asm.Label();
        m.visitTryCatchBlock(tryStart, tryEnd, catchAll, "java/lang/Throwable");
        m.visitLabel(tryStart);
        // The mod's classes load through the loader that holds this embedded copy, without initializing.
        m.visitLdcInsn(org.objectweb.asm.Type.getObjectType(LEB_IMPL));
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false);
        m.visitVarInsn(ASTORE, 4);
        // Method m = LegacyStaticHandlers.find(owner, handler, event): the embed moves an event Retromod built
        // into the mod but leaves the name in this string, so a name that does not load is matched by the handler's.
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)" + L_CLASS, false);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKESTATIC, STATIC_HANDLERS, "find",
                "(" + L_CLASS + "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/reflect/Method;", false);
        m.visitVarInsn(ASTORE, 6);
        // Class ev = m.getParameterTypes()[0]; m.setAccessible(true);
        m.visitVarInsn(ALOAD, 6);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getParameterTypes", "()[" + L_CLASS, false);
        m.visitInsn(ICONST_0);
        m.visitInsn(AALOAD);
        m.visitVarInsn(ASTORE, 5);
        m.visitVarInsn(ALOAD, 6);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V", false);
        String busless = buslessTable(cancel);
        if (!busless.isEmpty()) {
            // A game parent nothing posts reaches its events through the children the transform named, on the game
            // group, as Forge 1.20.1 subscribed the class there:
            // if (LegacyCancelListeners.hookHandlerChildren(BusGroup.DEFAULT, m.getDeclaringClass(), m, null, priority, table)) return;
            m.visitFieldInsn(GETSTATIC, BUS_GROUP, "DEFAULT", L_BG);
            m.visitVarInsn(ALOAD, 6);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getDeclaringClass", "()" + L_CLASS, false);
            m.visitVarInsn(ALOAD, 6);
            m.visitInsn(ACONST_NULL);
            m.visitVarInsn(ILOAD, 3);
            m.visitLdcInsn(busless);
            m.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "hookHandlerChildren", HOOK_HANDLER_CHILDREN_DESC, false);
            m.visitJumpInsn(IFNE, tryEnd);
        }
        // A mod that never asked for its event bus still has a mod group for its mod-bus events.
        org.objectweb.asm.Label haveGroup = new org.objectweb.asm.Label();
        m.visitFieldInsn(GETSTATIC, LEB_IMPL, "modGroup", L_BG);
        m.visitJumpInsn(IFNONNULL, haveGroup);
        m.visitMethodInsn(INVOKESTATIC, FMLJMLC, "get", "()L" + FMLJMLC + ";", false);
        m.visitMethodInsn(INVOKEVIRTUAL, FMLJMLC, "getModBusGroup", "()" + L_BG, false);
        m.visitFieldInsn(PUTSTATIC, LEB_IMPL, "modGroup", L_BG);
        m.visitLabel(haveGroup);
        // Object bus = busOf(modGroup, ev); a mod that also registered the class itself already put the handler there.
        m.visitFieldInsn(GETSTATIC, LEB_IMPL, "modGroup", L_BG);
        m.visitVarInsn(ALOAD, 5);
        m.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "busOf", BUS_OF_DESC, false);
        m.visitVarInsn(ASTORE, 8);
        m.visitFieldInsn(GETSTATIC, LEB_IMPL, "modGroup", L_BG);
        m.visitVarInsn(ALOAD, 6);
        m.visitVarInsn(ALOAD, 8);
        m.visitMethodInsn(INVOKESTATIC, STATIC_HANDLERS, "claim", CLAIM_DESC, false);
        m.visitJumpInsn(IFEQ, tryEnd);
        if (cancel) {
            // A boolean twin goes on as a predicate, so its cancel reaches EventBus 7:
            // if (LegacyCancelListeners.isCancelAware(m)) { LegacyCancelListeners.addStaticHandler(bus, m, priority); }
            org.objectweb.asm.Label consumer = new org.objectweb.asm.Label();
            m.visitVarInsn(ALOAD, 6);
            m.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "isCancelAware", "(Ljava/lang/reflect/Method;)Z", false);
            m.visitJumpInsn(IFEQ, consumer);
            m.visitVarInsn(ALOAD, 8);
            m.visitVarInsn(ALOAD, 6);
            m.visitVarInsn(ILOAD, 3);
            m.visitMethodInsn(INVOKESTATIC, CANCEL_LISTENERS, "addStaticHandler",
                    "(" + L_OBJ + "Ljava/lang/reflect/Method;B)V", false);
            m.visitJumpInsn(GOTO, tryEnd);
            m.visitLabel(consumer);
        }
        // ((EventBus) bus).addListener(priority, new ReflectedConsumer(m, null))
        m.visitVarInsn(ALOAD, 8);
        m.visitTypeInsn(CHECKCAST, EVENT_BUS7);
        m.visitVarInsn(ILOAD, 3);
        m.visitTypeInsn(NEW, RC);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 6);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESPECIAL, RC, "<init>", "(Ljava/lang/reflect/Method;" + L_OBJ + ")V", false);
        m.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS7, "addListener", "(B" + L_CONSUMER + ")" + L_LISTENER7, true);
        m.visitInsn(POP);
        m.visitLabel(tryEnd);
        m.visitInsn(RETURN);
        // A handler whose event or class is gone warns once here instead of stopping the mod.
        m.visitLabel(catchAll);
        m.visitVarInsn(ASTORE, 7);
        String logger = String.join("/", "org", "slf4j", "Logger");
        m.visitMethodInsn(INVOKESTATIC, "com/mojang/logging/LogUtils", "getLogger", "()L" + logger + ";", false);
        m.visitLdcInsn("[Retromod] The old event handler {} could not be hooked onto EventBus 7 and will not run: {}");
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(".");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitVarInsn(ALOAD, 7);
        m.visitMethodInsn(INVOKEINTERFACE, logger, "warn", "(Ljava/lang/String;" + L_OBJ + L_OBJ + ")V", true);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * {@code Consumer} that reflects a single {@code @SubscribeEvent} handler method:
     * {@code accept(e) -> method.invoke(target, e)}. Used by the single-listener fallbacks.
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

    /** The class without the {@code @Mod.EventBusSubscriber} FML 26.2 would fail on, or the same array. */
    public static byte[] stripLenientAutoSubscriber(byte[] classBytes,
            java.util.function.BiConsumer<String, byte[]> companions) {
        // EventBus 6 hosts subscribe natively.
        if (!active || classBytes == null) return classBytes;
        // cheap pre-filter before a full parse
        if (new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .indexOf("Mod$EventBusSubscriber") < 0) return classBytes;
        try {
            org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(classBytes).accept(cn, 0);
            org.objectweb.asm.tree.AnnotationNode subscriber = autoSubscriber(cn);
            if (subscriber == null) return classBytes;
            // FML takes one or two parameters, so a cancel twin's (event, boolean) monitor counts as well.
            java.util.List<org.objectweb.asm.tree.MethodNode> staticHandlers = cn.methods.stream()
                    .filter(m -> (m.access & ACC_STATIC) != 0)
                    .filter(m -> org.objectweb.asm.Type.getArgumentTypes(m.desc).length == 1
                            || org.objectweb.asm.Type.getArgumentTypes(m.desc).length == 2)
                    .filter(m -> hasSubscribe(m.visibleAnnotations) || hasSubscribe(m.invisibleAnnotations))
                    .toList();
            // FML 26.2 subscribes two or more static handlers itself. It throws for none ("No
            // listeners found"), which old mods often have since EventBus 6 registered whatever
            // statics existed; instance handlers still flow through the bridge's register.
            if (staticHandlers.size() >= 2) return classBytes;
            // A trade or register-capabilities event Retromod builds into the jar always binds, so FML subscribes such
            // a lone handler itself. hookStatic would find it too, but the CLI's nested-jar loop and AOT strip no
            // subscriber class, so keeping it with FML here keeps every path's output the same.
            String loneEvent = staticHandlers.size() == 1
                    ? org.objectweb.asm.Type.getArgumentTypes(staticHandlers.get(0).desc)[0].getInternalName() : null;
            if (loneEvent != null && (LegacyTradeBridge.isTradeEvent(loneEvent)
                    || LegacyCapabilityEventBridge.isStandIn(loneEvent))) {
                return classBytes;
            }
            // FML has no single-listener rule, but one handler it cannot bind fails the whole mod,
            // so a lone handler goes to a companion that hooks it with a soft fail. The companion
            // is built before the strip, while the class still names its own mod id.
            if (staticHandlers.size() == 1) {
                companions.accept(companionName(cn.name), generateCompanion(cn, subscriber, staticHandlers.get(0)));
                org.slf4j.LoggerFactory.getLogger("Retromod-EventBus7").debug(
                        "Moved the one static handler of {} to a companion that hooks it onto EventBus 7", cn.name);
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

    /** The class that subscribes in place of {@code owner}'s one static handler. */
    public static String companionName(String owner) {
        return owner + "$RetromodSubscriber";
    }

    // Fires on the mod's group right after FML subscribes the mod's classes.
    private static final String CONSTRUCT_EVENT = "net/minecraftforge/fml/event/lifecycle/FMLConstructModEvent";

    /** The companion class that subscribes in place of {@code owner} and hooks its one handler. */
    static byte[] generateCompanion(org.objectweb.asm.tree.ClassNode owner,
            org.objectweb.asm.tree.AnnotationNode subscriber, org.objectweb.asm.tree.MethodNode handler) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, companionName(owner.name), null, "java/lang/Object", null);
        // The old annotation's modid and sides without its bus, so FML puts the construct event on the mod's group.
        org.objectweb.asm.tree.AnnotationNode copy = new org.objectweb.asm.tree.AnnotationNode(AUTO_SUB_DESC);
        copy.values = new java.util.ArrayList<>();
        java.util.List<Object> values = subscriber.values == null ? java.util.List.of() : subscriber.values;
        boolean hasModId = false;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (values.get(i).equals("bus")) continue;
            hasModId |= values.get(i).equals("modid");
            copy.values.add(values.get(i));
            copy.values.add(values.get(i + 1));
        }
        // FML maps a @Mod class to its own id, so a mod class's companion names that id itself.
        String modClassId = hasModId ? null : modIdOf(owner);
        if (modClassId != null) {
            copy.values.add("modid");
            copy.values.add(modClassId);
        }
        copy.accept(cw.visitAnnotation(AUTO_SUB_DESC, true));

        // One listener that passes the handler's names to hookStatic. It names classes only as
        // strings and has no branches, so nothing in it can fail to link and it needs no frames.
        MethodVisitor hook = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "hook", "(L" + CONSTRUCT_EVENT + ";)V", null, null);
        hook.visitAnnotation(SUB7_DESC, true).visitEnd();
        hook.visitCode();
        hook.visitLdcInsn(owner.name.replace('/', '.'));
        hook.visitLdcInsn(handler.name);
        hook.visitLdcInsn(org.objectweb.asm.Type.getArgumentTypes(handler.desc)[0].getClassName());
        hook.visitIntInsn(BIPUSH, priorityBits(handler));
        hook.visitMethodInsn(INVOKESTATIC, LEB_IMPL, "hookStatic",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;B)V", false);
        hook.visitInsn(RETURN);
        hook.visitMaxs(4, 1);
        hook.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** EventBus 7's byte for the handler's priority, normalized or still the old name, or NORMAL's 0. */
    private static int priorityBits(org.objectweb.asm.tree.MethodNode handler) {
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> anns
                : java.util.Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations)) {
            if (anns == null) continue;
            for (org.objectweb.asm.tree.AnnotationNode a : anns) {
                if (SUB7_DESC.equals(a.desc)) return ForgeSubscribeAnnotations.priorityOf(a);
            }
        }
        return 0;
    }

    /** The id of the class's own {@code @Mod} annotation, or null. */
    private static String modIdOf(org.objectweb.asm.tree.ClassNode owner) {
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> anns
                : java.util.Arrays.asList(owner.visibleAnnotations, owner.invisibleAnnotations)) {
            if (anns == null) continue;
            for (org.objectweb.asm.tree.AnnotationNode a : anns) {
                if (!"Lnet/minecraftforge/fml/common/Mod;".equals(a.desc) || a.values == null) continue;
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    if (a.values.get(i).equals("value") && a.values.get(i + 1) instanceof String id) return id;
                }
            }
        }
        return null;
    }

    private static org.objectweb.asm.tree.AnnotationNode autoSubscriber(org.objectweb.asm.tree.ClassNode cn) {
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> anns
                : java.util.Arrays.asList(cn.visibleAnnotations, cn.invisibleAnnotations)) {
            if (anns == null) continue;
            for (org.objectweb.asm.tree.AnnotationNode a : anns) {
                if (AUTO_SUB_DESC.equals(a.desc)) return a;
            }
        }
        return null;
    }

    private static boolean hasSubscribe(java.util.List<org.objectweb.asm.tree.AnnotationNode> anns) {
        return anns != null && anns.stream().anyMatch(a -> SUB7_DESC.equals(a.desc));
    }

    private static void removeAnnotation(java.util.List<org.objectweb.asm.tree.AnnotationNode> anns) {
        if (anns != null) anns.removeIf(a -> AUTO_SUB_DESC.equals(a.desc));
    }

    /** Warns msg + local1.getClass().getName() through Minecraft's logger. */
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
