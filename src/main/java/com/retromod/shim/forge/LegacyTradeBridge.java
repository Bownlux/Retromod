/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import com.retromod.shim.forge.embedded.LegacyTradeStore;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Puts back the villager and wandering trader trade events and their listings for Forge and NeoForge mods on 26.x. */
public final class LegacyTradeBridge {

    public static final String ITEM_LISTING = "com/retromod/generated/LegacyItemListing";
    static final String BASIC_LISTING = "com/retromod/generated/LegacyBasicItemListing";
    static final String VILLAGER_EVENT = "com/retromod/generated/LegacyVillagerTradesEvent";
    static final String WANDERER_EVENT = "com/retromod/generated/LegacyWandererTradesEvent";
    static final String STORE = "com/retromod/shim/forge/embedded/LegacyTradeStore";
    public static final String PROFESSIONS = "com/retromod/generated/LegacyVillagerProfessions";

    static final String NEOFORGE_EVENT = "net/neoforged/bus/api/Event";
    static final String FORGE_EVENT = "net/minecraftforge/eventbus/api/event/MutableEvent";
    static final String FORGE_EVENT_BUS = "net/minecraftforge/eventbus/api/bus/EventBus";
    static final String FORGE_VILLAGER_EVENT = "net/minecraftforge/event/village/VillagerTradesEvent";
    static final String FORGE_WANDERER_EVENT = "net/minecraftforge/event/village/WandererTradesEvent";
    // The event base, and the events the store listens to; javap shows both with a static BUS on Forge 65.0.0 and 66.1.2.
    private static final List<String> FORGE_HOST_CLASSES = List.of(FORGE_EVENT,
            "net/minecraftforge/event/AddPackFindersEvent", "net/minecraftforge/event/server/ServerStartedEvent");
    private static final String PACK_SOURCE = "com/retromod/shim/forge/embedded/LegacyDataPackSource";
    private static final List<String> FORGE_TRADE_EVENTS = List.of(FORGE_VILLAGER_EVENT, FORGE_WANDERER_EVENT);
    private static final ModConstructorHooks.Call START_STORE =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, STORE, "watch", "()V", List.of());

    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String PROFESSION = "net/minecraft/world/entity/npc/villager/VillagerProfession";
    private static final String INT_MAP = "it/unimi/dsi/fastutil/ints/Int2ObjectMap";
    private static final String OFFER_DESC = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/util/RandomSource;)"
            + "Lnet/minecraft/world/item/trading/MerchantOffer;";

    private LegacyTradeBridge() {}

    /** NeoForge 26.x only: older hosts still have the events, and Forge goes through {@link #registerForge}. */
    static boolean supportsHost(String host) {
        return RetromodVersion.isUnobfuscatedTarget(host) && McReflect.isNeoForge()
                && !ClassResourceInspector.exists("net/neoforged/neoforge/event/village/VillagerTradesEvent");
    }

    public static void register(RetromodTransformer transformer) {
        if (!supportsHost(RetromodVersion.TARGET_MC_VERSION)) return;
        registerRedirects(transformer);
    }

    // 26.1 made trades data: each profession level reads a trade set, which draws from a villager_trade
    // tag. ItemListing and both events went away, so a mod lost its trades, and on NeoForge every event
    // subscription of the mod failed until Retromod started dropping the handler.
    static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, STORE, LegacyTradeStore.class);
        registerItemListings(transformer);
        transformer.registerSyntheticClass(BASIC_LISTING, generateBasicListing());
        transformer.registerSyntheticClass(VILLAGER_EVENT, generateVillagerEvent());
        transformer.registerSyntheticClass(WANDERER_EVENT, generateWandererEvent());
        for (String loader : new String[] {"net/minecraftforge/", "net/neoforged/neoforge/"}) {
            transformer.registerClassRedirect(loader + "event/village/VillagerTradesEvent", VILLAGER_EVENT);
            transformer.registerClassRedirect(loader + "event/village/WandererTradesEvent", WANDERER_EVENT);
            transformer.registerClassRedirect(loader + "common/BasicItemListing", BASIC_LISTING);
        }
    }

    /** Forge 26.2 and newer, which dropped both events with EventBus 6; offline, a Forge target from 26.2. */
    public static void registerForge(RetromodTransformer transformer) {
        // A subscriber class naming a removed event fails to load, which stops every listener in it.
        if (!ForgeHostShape.matches(FORGE_HOST_CLASSES, FORGE_TRADE_EVENTS)) return;
        SyntheticEmbedder.registerClassResource(transformer, STORE, LegacyTradeStore.class);
        // The store offers its pack through this helper, which a Forge 1.21.1 mod's chain may not register.
        SyntheticEmbedder.registerClassResource(transformer, PACK_SOURCE, LegacyDataPackSource.class);
        registerItemListings(transformer);
        transformer.registerSyntheticClass(BASIC_LISTING, generateBasicListing());
        transformer.registerSyntheticClass(VILLAGER_EVENT, generateVillagerEvent(FORGE_EVENT));
        transformer.registerSyntheticClass(WANDERER_EVENT, generateWandererEvent(FORGE_EVENT));
        // javap shows neither event nor BasicItemListing on Forge 65.0.0 or 66.1.2, so no native mod names them.
        transformer.registerClassRedirect(FORGE_VILLAGER_EVENT, VILLAGER_EVENT);
        transformer.registerClassRedirect(FORGE_WANDERER_EVENT, WANDERER_EVENT);
        transformer.registerClassRedirect("net/minecraftforge/common/BasicItemListing", BASIC_LISTING);
        // FML initializes only the subscriber classes it subscribes itself, so the mod's constructor starts the store.
        ModConstructorHooks.register(transformer, "legacy-trades", LegacyTradeBridge::planStoreStart);
    }

    /** The store's start in each {@code @Mod} constructor of a jar whose classes name a Forge trade event; null for other jars. */
    static ModConstructorHooks.JarPlan planStoreStart(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (namesForgeTradeEvent(original)) return (modClass, modId) -> List.of(START_STORE);
        }
        return null;
    }

    /** Whether the internal name is one of the generated trade events, before the embed moves it into a mod. */
    static boolean isTradeEvent(String internalName) {
        return internalName.equals(VILLAGER_EVENT) || internalName.equals(WANDERER_EVENT);
    }

    private static boolean namesForgeTradeEvent(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return FORGE_TRADE_EVENTS.stream().anyMatch(text::contains);
    }

    /**
     * The old trade factory type and profession reads, which every loader's trade API takes. The
     * Fabric loader API runtime calls this for Fabric's {@code TradeOfferHelper}.
     */
    public static void registerItemListings(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(ITEM_LISTING, generateItemListing());
        transformer.registerClassRedirect("net/minecraft/world/entity/npc/VillagerTrades$ItemListing", ITEM_LISTING);
        transformer.registerClassRedirect("net/minecraft/world/item/trading/VillagerTrades$ItemListing", ITEM_LISTING);

        // A handler compares the event's profession with VillagerProfession.FARMER and the like,
        // which became registry keys in 1.21.5, so an old read gets the registered profession.
        transformer.registerSyntheticClass(PROFESSIONS, generateProfessions());
        transformer.registerStaticFieldTypeDrift(PROFESSION, "L" + PROFESSION + ";", "Lnet/minecraft/resources/ResourceKey;",
                PROFESSIONS, "of", "(Lnet/minecraft/resources/ResourceKey;)L" + PROFESSION + ";");
    }

    /** {@code static VillagerProfession of(ResourceKey key)}: the profession registered under the key. */
    static byte[] generateProfessions() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PROFESSIONS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of",
                "(Lnet/minecraft/resources/ResourceKey;)L" + PROFESSION + ";", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "VILLAGER_PROFESSION",
                "Lnet/minecraft/core/DefaultedRegistry;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/core/Registry", "getValue",
                "(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, PROFESSION);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Makes a class that handles a trade event start the trade store when it initializes. NeoForge
     * initializes a subscriber class while it constructs the mod, which is when the store can find
     * the mod's event bus; on Forge the mod's constructor starts it first. Returns the input array
     * when the class has no such handler.
     */
    public static byte[] watchHandlers(byte[] classBytes) {
        if (classBytes == null) return null;
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!text.contains(VILLAGER_EVENT) && !text.contains(WANDERER_EVENT)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & ACC_INTERFACE) != 0 || !handlesTradeEvent(node)) return classBytes;

        MethodNode clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(RETURN));
            node.methods.add(clinit);
        }
        InsnList watch = new InsnList();
        watch.add(new MethodInsnNode(INVOKESTATIC, STORE, "watch", "()V", false));
        clinit.instructions.insert(watch);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); // a call with no stack effect at the method start
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean handlesTradeEvent(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                String name = argument.getInternalName();
                if (name.equals(VILLAGER_EVENT) || name.equals(WANDERER_EVENT)) return true;
            }
        }
        return false;
    }

    /** The removed {@code VillagerTrades.ItemListing}: one offer for a trader. */
    static byte[] generateItemListing() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, ITEM_LISTING, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getOffer", OFFER_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Forge's {@code BasicItemListing}: the price, an optional second price, the item sold, how
     * often it can be traded, the experience it gives, and the price multiplier. The store reads
     * these fields; there is no villager to build an offer for.
     */
    static byte[] generateBasicListing() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, BASIC_LISTING, null, "java/lang/Object", new String[] {ITEM_LISTING});
        for (String field : new String[] {"price", "price2", "forSale"}) {
            cw.visitField(ACC_PUBLIC | ACC_FINAL, field, L_STACK, null, null).visitEnd();
        }
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "maxTrades", "I", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "xp", "I", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "priceMult", "F", null, null).visitEnd();

        String full = "(" + L_STACK + L_STACK + L_STACK + "IIF)V";
        MethodVisitor all = cw.visitMethod(ACC_PUBLIC, "<init>", full, null, null);
        all.visitCode();
        all.visitVarInsn(ALOAD, 0);
        all.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        String[][] fields = {{"price", L_STACK}, {"price2", L_STACK}, {"forSale", L_STACK},
                {"maxTrades", "I"}, {"xp", "I"}, {"priceMult", "F"}};
        int[] loads = {ALOAD, ALOAD, ALOAD, ILOAD, ILOAD, FLOAD};
        for (int i = 0; i < fields.length; i++) {
            all.visitVarInsn(ALOAD, 0);
            all.visitVarInsn(loads[i], i + 1);
            all.visitFieldInsn(PUTFIELD, BASIC_LISTING, fields[i][0], fields[i][1]);
        }
        all.visitInsn(RETURN);
        all.visitMaxs(0, 0);
        all.visitEnd();

        // (price, forSale, maxTrades, xp, priceMult): no second price.
        MethodVisitor single = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_STACK + L_STACK + "IIF)V", null, null);
        single.visitCode();
        single.visitVarInsn(ALOAD, 0);
        single.visitVarInsn(ALOAD, 1);
        single.visitFieldInsn(GETSTATIC, ITEM_STACK, "EMPTY", L_STACK);
        single.visitVarInsn(ALOAD, 2);
        single.visitVarInsn(ILOAD, 3);
        single.visitVarInsn(ILOAD, 4);
        single.visitVarInsn(FLOAD, 5);
        single.visitMethodInsn(INVOKESPECIAL, BASIC_LISTING, "<init>", full, false);
        single.visitInsn(RETURN);
        single.visitMaxs(0, 0);
        single.visitEnd();

        // (emeralds, forSale, maxTrades, xp, priceMult) and (emeralds, forSale, maxTrades, xp) at a multiplier of 1.
        emitEmeraldConstructor(cw, "(I" + L_STACK + "IIF)V", true, full);
        emitEmeraldConstructor(cw, "(I" + L_STACK + "II)V", false, full);

        MethodVisitor offer = cw.visitMethod(ACC_PUBLIC, "getOffer", OFFER_DESC, null, null);
        offer.visitCode();
        offer.visitInsn(ACONST_NULL);
        offer.visitInsn(ARETURN);
        offer.visitMaxs(0, 0);
        offer.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitEmeraldConstructor(ClassWriter cw, String desc, boolean hasMultiplier, String full) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, ITEM_STACK);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/world/item/Items", "EMERALD", "Lnet/minecraft/world/item/Item;");
        mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, ITEM_STACK, "<init>", "(Lnet/minecraft/world/level/ItemLike;I)V", false);
        mv.visitFieldInsn(GETSTATIC, ITEM_STACK, "EMPTY", L_STACK);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        if (hasMultiplier) mv.visitVarInsn(FLOAD, 5);
        else mv.visitInsn(FCONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, BASIC_LISTING, "<init>", full, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code VillagerTradesEvent}: the listings for each level of one profession. Forge's
     * {@code getType} returned the profession and NeoForge's from 1.21.5 its key, so both are here;
     * the class file allows two methods that differ only in return type.
     */
    static byte[] generateVillagerEvent() {
        return generateVillagerEvent(NEOFORGE_EVENT);
    }

    /** The villager event on {@code base}: NeoForge's {@code Event}, or EventBus 7's {@code MutableEvent} on Forge. */
    static byte[] generateVillagerEvent(String base) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, VILLAGER_EVENT, null, base, null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "trades", "L" + INT_MAP + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "type", "Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "key", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + INT_MAP + ";Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, base, "<init>", "()V", false);
        String[][] fields = {{"trades", "L" + INT_MAP + ";"}, {"type", "Ljava/lang/Object;"}, {"key", "Ljava/lang/Object;"}};
        for (int i = 0; i < fields.length; i++) {
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, i + 1);
            ctor.visitFieldInsn(PUTFIELD, VILLAGER_EVENT, fields[i][0], fields[i][1]);
        }
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitGetter(cw, VILLAGER_EVENT, "getTrades", "trades", "L" + INT_MAP + ";", null);
        emitGetter(cw, VILLAGER_EVENT, "getType", "type", "Ljava/lang/Object;", PROFESSION);
        emitGetter(cw, VILLAGER_EVENT, "getType", "key", "Ljava/lang/Object;", "net/minecraft/resources/ResourceKey");
        if (base.equals(FORGE_EVENT)) emitForgeBus(cw, VILLAGER_EVENT);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code WandererTradesEvent}: the common, rare, and (NeoForge 1.21.5 and newer) buying listings. */
    static byte[] generateWandererEvent() {
        return generateWandererEvent(NEOFORGE_EVENT);
    }

    /** The wandering trader event on {@code base}, as for {@link #generateVillagerEvent(String)}. */
    static byte[] generateWandererEvent(String base) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, WANDERER_EVENT, null, base, null);
        String[] lists = {"generic", "rare", "buying"};
        for (String list : lists) cw.visitField(ACC_PRIVATE | ACC_FINAL, list, "Ljava/util/List;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;Ljava/util/List;Ljava/util/List;)V",
                null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, base, "<init>", "()V", false);
        for (int i = 0; i < lists.length; i++) {
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, i + 1);
            ctor.visitFieldInsn(PUTFIELD, WANDERER_EVENT, lists[i], "Ljava/util/List;");
        }
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitGetter(cw, WANDERER_EVENT, "getGenericTrades", "generic", "Ljava/util/List;", null);
        emitGetter(cw, WANDERER_EVENT, "getRareTrades", "rare", "Ljava/util/List;", null);
        emitGetter(cw, WANDERER_EVENT, "getBuyingTrades", "buying", "Ljava/util/List;", null);
        if (base.equals(FORGE_EVENT)) emitForgeBus(cw, WANDERER_EVENT);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code public static final EventBus BUS = EventBus.create(owner.class)}, as each Forge 26.2 game
     * event has. It is the bus FML and EventBus 7 make for the class in the game group, and Retromod's
     * bus bridge reads it for a listener a mod adds on any bus.
     */
    private static void emitForgeBus(ClassWriter cw, String owner) {
        String busDesc = "L" + FORGE_EVENT_BUS + ";";
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", busDesc, null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(Type.getObjectType(owner));
        clinit.visitMethodInsn(INVOKESTATIC, FORGE_EVENT_BUS, "create", "(Ljava/lang/Class;)" + busDesc, true);
        clinit.visitFieldInsn(PUTSTATIC, owner, "BUS", busDesc);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
    }

    /** A getter for {@code field}, cast to {@code castTo} when it is stored as a plain object. */
    private static void emitGetter(ClassWriter cw, String owner, String name, String field, String fieldDesc, String castTo) {
        String returned = castTo == null ? fieldDesc : "L" + castTo + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + returned, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, owner, field, fieldDesc);
        if (castTo != null) mv.visitTypeInsn(CHECKCAST, castTo);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_MAXS);
    }
}
