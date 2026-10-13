/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
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

import static org.objectweb.asm.Opcodes.*;

/**
 * Puts back the villager and wandering trader trade events for Forge and NeoForge mods on 26.x.
 *
 * <p>26.1 made villager trades data: each profession level reads a trade set, which draws from a
 * {@code villager_trade} tag. {@code VillagerTrades.ItemListing} and both trade events were removed,
 * so a mod that added trades through them lost the trades, and on NeoForge every one of its event
 * subscriptions failed until Retromod started dropping the handler.
 *
 * <p>The events come back as generated classes with the old getters. Once the server has started,
 * Retromod posts one villager event per profession and one wandering trader event, then writes
 * each listing the mod added as a {@code villager_trade} entry in a data pack and appends it to the
 * tag the matching trade set reads. 26.x loads trades before item stacks can be built and keeps
 * them for the session, so the trades apply from the next start. Vanilla's trades and other packs'
 * additions stay in the tag. Forge's
 * {@code BasicItemListing} carries its stacks, so it converts exactly. Any other listing is asked
 * for one offer, so a listing that picks its items or prices at random keeps the one it picked, and
 * one that cannot build an offer without a villager is skipped. Item components on the stacks are
 * not carried over. Professions with no trade set for a level, such as a mod's own profession that
 * ships no trade data, get no trades.
 */
public final class LegacyTradeBridge {

    static final String ITEM_LISTING = "com/retromod/generated/LegacyItemListing";
    static final String BASIC_LISTING = "com/retromod/generated/LegacyBasicItemListing";
    static final String VILLAGER_EVENT = "com/retromod/generated/LegacyVillagerTradesEvent";
    static final String WANDERER_EVENT = "com/retromod/generated/LegacyWandererTradesEvent";
    static final String STORE = "com/retromod/shim/forge/embedded/LegacyTradeStore";
    static final String PROFESSIONS = "com/retromod/generated/LegacyVillagerProfessions";

    private static final String EVENT = "net/neoforged/bus/api/Event";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String PROFESSION = "net/minecraft/world/entity/npc/villager/VillagerProfession";
    private static final String INT_MAP = "it/unimi/dsi/fastutil/ints/Int2ObjectMap";
    private static final String OFFER_DESC = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/util/RandomSource;)"
            + "Lnet/minecraft/world/item/trading/MerchantOffer;";

    private LegacyTradeBridge() {}

    /** NeoForge 26.x only: older hosts still have the events, and Forge's bus has no {@code Event} to extend. */
    static boolean supportsHost(String host) {
        return RetromodVersion.isUnobfuscatedTarget(host) && McReflect.isNeoForge()
                && !ClassResourceInspector.exists("net/neoforged/neoforge/event/village/VillagerTradesEvent");
    }

    public static void register(RetromodTransformer transformer) {
        if (!supportsHost(RetromodVersion.TARGET_MC_VERSION)) return;
        registerRedirects(transformer);
    }

    static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, STORE, LegacyTradeStore.class);
        transformer.registerSyntheticClass(ITEM_LISTING, generateItemListing());
        transformer.registerSyntheticClass(BASIC_LISTING, generateBasicListing());
        transformer.registerSyntheticClass(VILLAGER_EVENT, generateVillagerEvent());
        transformer.registerSyntheticClass(WANDERER_EVENT, generateWandererEvent());
        for (String loader : new String[] {"net/minecraftforge/", "net/neoforged/neoforge/"}) {
            transformer.registerClassRedirect(loader + "event/village/VillagerTradesEvent", VILLAGER_EVENT);
            transformer.registerClassRedirect(loader + "event/village/WandererTradesEvent", WANDERER_EVENT);
            transformer.registerClassRedirect(loader + "common/BasicItemListing", BASIC_LISTING);
        }
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
     * the mod's event bus. Returns the input array when the class has no such handler.
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
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, VILLAGER_EVENT, null, EVENT, null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "trades", "L" + INT_MAP + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "type", "Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "key", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + INT_MAP + ";Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, EVENT, "<init>", "()V", false);
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
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code WandererTradesEvent}: the common, rare, and (NeoForge 1.21.5 and newer) buying listings. */
    static byte[] generateWandererEvent() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, WANDERER_EVENT, null, EVENT, null);
        String[] lists = {"generic", "rare", "buying"};
        for (String list : lists) cw.visitField(ACC_PRIVATE | ACC_FINAL, list, "Ljava/util/List;", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;Ljava/util/List;Ljava/util/List;)V",
                null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, EVENT, "<init>", "()V", false);
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
        cw.visitEnd();
        return cw.toByteArray();
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
