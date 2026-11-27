/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyVanillaGapsBridge.*;

/** The vanilla gaps bridge on NeoForge and Forge hosts from 1.20.5 to 1.21.11, each member answered by a helper that links there. */
public final class LegacyVanillaGapsOlderHosts {

    static final String BEFORE26 = ROOT + "world/LegacyVanillaGapsBefore26";
    static final String CLIENT_BEFORE26 = ROOT + "client/LegacyVanillaClientGapsBefore26";
    // The names these hosts used before 1.21.11 renamed them.
    static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    static final String ROOT_UTIL = "net/minecraft/Util";
    static final String GUI_GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    static final String HOLDER_LOOKUP = "net/minecraft/core/HolderLookup$RegistryLookup";

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** The cut-down helpers of one host, which depend on nothing else, so a registration pass reads them once. */
    private record Helpers(Function<String, ClassNode> host, RebuiltHostLinks.Pruned pruned) {}

    private static volatile Helpers cached;

    private LegacyVanillaGapsOlderHosts() {}

    /** Registers each member the host lacks whose helper links there; 26.1 and newer take the whole bridge from their class moves. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!targetAtLeast("1.20.5") || targetAtLeast("26.1")) return;
        // With no Minecraft class to read, as offline with no --mc-jar or --host-jar, no member can be checked.
        if (host.apply(ENTITY) == null) {
            if (t.firstRegistrationNotice(LegacyVanillaGapsOlderHosts.class.getName() + ".noHost")) {
                LOGGER.warn("No Minecraft classes could be read, so the vanilla member repairs for {} stay off. Pass --mc-jar "
                        + "with NeoForge's patched game jar, or the host's jars with --host-jar, to apply them.",
                        RetromodVersion.TARGET_MC_VERSION);
            }
            return;
        }
        // Each NeoForge and Forge shim of the host calls this; every call registers, so a later one still wins a shared key.
        Members m = new Members(t, host, helpers(t, host));
        registerItems(m);
        registerEntities(m);
        registerData(m);
        registerClient(m);
        m.helpers().register(t);
        // 1.21.2 dropped the carving step; the stand-in and the carver calls need the 26.x helpers this host keeps.
        String byKey = "(L" + GEN_BUILDER + ";Ljava/lang/Object;Lnet/minecraft/resources/ResourceKey;)L" + GEN_BUILDER + ";";
        String byHolder = "(L" + PLAIN_BUILDER + ";Ljava/lang/Object;Lnet/minecraft/core/Holder;)L" + PLAIN_BUILDER + ";";
        if (m.helpers().has(WORLD, "addCarver", byKey) && m.helpers().has(WORLD, "addCarver", byHolder)
                && m.helpers().has(WORLD, "getCarvers", "(L" + GEN_SETTINGS + ";Ljava/lang/Object;)Ljava/lang/Iterable;")
                && RebuiltHostLinks.linksOn(LEGACY_CARVING, host)) {
            LegacyVanillaGapsBridge.registerCarving(t, host, false);
        }
        // Removed classes rebuilt whole: each registers only where every call it makes links.
        if (RebuiltHostLinks.linksOn(LEGACY_MATERIAL, host)) LegacyVanillaGapsBridge.registerMaterial(t, host);
        if (host.apply(LegacyVanillaGapsAdapters.FURNACE) != null && RebuiltHostLinks.linksOn(FUELS, host)) {
            LegacyVanillaGapsBridge.registerFurnaces(t, host);
        }
    }

    /** The 26.x helpers cut down to what links on the host, beside helpers built on these hosts' own shapes. */
    private static RebuiltHostLinks.Pruned helpers(RetromodTransformer t, Function<String, ClassNode> host) {
        Helpers now = cached;
        if (now != null && now.host() == host) return now.pruned();
        RebuiltHostLinks.Pruned pruned = RebuiltHostLinks.prune(List.of(WORLD, CLIENT, BEFORE26, CLIENT_BEFORE26), host);
        cached = new Helpers(host, pruned);
        t.onRegistrationReset(() -> cached = null);
        return pruned;
    }

    /** Item, food and enchantment reads in their 1.20.1 and 1.21.1 shapes. */
    static void registerItems(Members m) {
        m.receiver(ITEM_STACK, "getXpRepairRatio", "()F", "1.21", WORLD);
        m.receiver(ITEM_STACK, "getAttributeModifiers", "(" + L_SLOT + ")" + MULTIMAP, "1.20.5", WORLD);
        m.receiver(ITEM_STACK, "getEnchantmentTags", "()Lnet/minecraft/nbt/ListTag;", "1.20.5", WORLD, BEFORE26);
        m.statik(ENCHANTMENT_HELPER, "getEnchantmentId", "(" + L_TAG + ")L" + m.idClass() + ";", "1.20.5", WORLD, BEFORE26);
        m.statik(ENCHANTMENT_HELPER, "getEnchantmentLevel", "(" + L_TAG + ")I", "1.20.5", WORLD, BEFORE26);
        m.receiver(ITEM_STACK, "getUseDuration", "()I", "1.21", WORLD);
        m.receiver(ITEM, "getFoodProperties", "(" + L_STACK + "Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/food/FoodProperties;",
                "1.20.5", WORLD);
        m.receiver(ITEM, "onCraftedBy", "(" + L_STACK + L_LEVEL + L_PLAYER + ")V", "1.21.5", WORLD);
        m.receiver(FOOD_DATA, "getExhaustionLevel", "()F", "1.21.5", WORLD);
        m.receiver(FOOD_DATA, "setExhaustion", "(F)V", "1.21.5", WORLD);
        m.receiver(ENCHANTMENT, "getFullname", "(I)" + L_COMPONENT, "1.21", WORLD);
        // The helper takes Object, since the host's StackedContents is a generic counter from 1.21.2.
        m.redirect(STACKED_CONTENTS, "accountSimpleStack", "(" + L_STACK + ")V", "(Ljava/lang/Object;" + L_STACK + ")V", "1.21.2", false,
                WORLD, BEFORE26);
    }

    /** Entity, block, damage and chat members in their old shapes. */
    static void registerEntities(Members m) {
        m.rename(LOOK_CONTROL, "isHasWanted", "()Z", "isLookingAtTarget", "1.17");
        m.inherited(PROJECTILE, "mayInteract", "(" + L_LEVEL + L_POS + ")Z", "1.21.2", WORLD);
        // Mobs call their parent's offset from their own override, so the call may name any entity class.
        m.inherited(ENTITY, "getPassengersRidingOffset", "()D", "1.20.5", WORLD);
        m.statik(ENTITY_TYPE, "updateCustomEntityTag", "(" + L_LEVEL + L_PLAYER + L_ENTITY + L_TAG + ")V", "1.20.5", WORLD, BEFORE26);
        if (lacksField(m.host().apply(SERVER_PLAYER), "latency") && m.host().apply(SERVER_PLAYER) != null
                && m.helpers().has(WORLD, "latency", "(Ljava/lang/Object;)I") && m.helpers().has(WORLD, "setLatency", "(Ljava/lang/Object;I)V")) {
            m.t().registerFieldStaticBridge(SERVER_PLAYER, "latency", WORLD, "latency", "(Ljava/lang/Object;)I",
                    "setLatency", "(Ljava/lang/Object;I)V");
        }
        m.statik(TNT, "explode", "(" + L_LEVEL + L_POS + ")V", "1.21.2", WORLD);
        m.receiver(COMBAT_TRACKER, "recordDamage", "(L" + DAMAGE_SOURCE + ";FF)V", "1.19.4", WORLD);
        m.statik(DAMAGE_SOURCE, "thrown", "(" + L_ENTITY + L_ENTITY + ")L" + DAMAGE_SOURCE + ";", "1.19.4", WORLD);
        String sectionOf = "(" + L_ENTITY + ")L" + SECTION_POS + ";";
        String byAccess = "(Lnet/minecraft/world/level/entity/EntityAccess;)L" + SECTION_POS + ";";
        if (m.lacks(SECTION_POS, "of", sectionOf) && m.has(SECTION_POS, "of", byAccess)) {
            m.t().registerMethodRedirect(SECTION_POS, "of", sectionOf, SECTION_POS, "of", byAccess);
        }
        // Mods call these on a player or a living entity, so the call usually names a subclass.
        m.inherited(PLAYER, "playNotifySound", "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", "1.21.11", WORLD);
        m.inherited(ENTITY, "sendSystemMessage", "(" + L_COMPONENT + ")V", "1.21.5", WORLD, BEFORE26);
        m.receiver(WALK_ANIMATION, "update", "(FF)V", "1.21.5", WORLD);
        // 1.21 to 1.21.4 ask a block for a level reader's copy; from 1.21.5 the state answers it.
        String oldClone = "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + L_STATE + ")" + L_STACK;
        String cloneHelper = "(L" + BLOCK + ";" + oldClone.substring(1);
        if (m.has(BLOCK, "getCloneItemStack", "(Lnet/minecraft/world/level/LevelReader;" + L_POS + L_STATE + ")" + L_STACK)) {
            m.inherited(BLOCK, "getCloneItemStack", oldClone, "1.21", BEFORE26);
        } else if (m.lacks(BLOCK, "getCloneItemStack", oldClone) && m.helpers().has(WORLD, "getCloneItemStack", cloneHelper)) {
            LegacyVanillaGapsBridge.registerCloneStack(m.t());
        }
        if (m.lacks(EFFECT_INSTANCE, "setNoCounter", "(Z)V")) m.t().registerRemovedMethodNeutralize(EFFECT_INSTANCE, "setNoCounter", "(Z)V");
        // Chat and block state reads mods for 1.16.5 to 1.19.2 make.
        String sendMessage = "(" + L_COMPONENT + "Ljava/util/UUID;)V";
        String messageHelper = "(L" + PLAYER + ";" + sendMessage.substring(1);
        String messages = m.helpers().has(WORLD, "sendMessage", messageHelper) ? WORLD
                : m.helpers().has(BEFORE26, "sendMessage", messageHelper) ? BEFORE26 : null;
        if (m.lacks(PLAYER, "sendMessage", sendMessage) && messages != null) {
            for (String owner : List.of(PLAYER, SERVER_PLAYER, LOCAL_PLAYER, CLIENT_PLAYER)) {
                m.t().registerMethodRedirect(owner, "sendMessage", sendMessage, messages, "sendMessage", messageHelper, true);
                m.t().limitRedirectToModsBefore(owner, "sendMessage", sendMessage, "1.19");
            }
        }
        // A registry is its own block lookup from 1.21.2; before, the lookup is asked of it.
        String readState = "(" + L_TAG + ")" + L_STATE;
        m.statik(NBT_UTILS, "readBlockState", readState, "1.19.3", m.isA("net/minecraft/core/Registry", HOLDER_LOOKUP) ? WORLD : BEFORE26);
        m.receiver(MUTABLE_COMPONENT, "getKey", "()Ljava/lang/String;", "1.19", WORLD);
        String vecPos = "(Lnet/minecraft/world/phys/Vec3;)V";
        if (m.lacks(BLOCK_POS, "<init>", vecPos) && m.has(BLOCK_POS, "containing", POS_CONTAINING)) {
            m.t().registerConstructorRedirect(BLOCK_POS, vecPos, BLOCK_POS, "containing", POS_CONTAINING);
        }
    }

    /** Math, codec, crash report, JSON, id, pack and network members in their old shapes. */
    static void registerData(Members m) {
        m.statik(MTH, "sqrt", "(D)F", "1.17", WORLD);
        m.statik(DIRECTION, "fromDelta", "(III)L" + DIRECTION + ";", "1.21.2", WORLD);
        if (lacksField(m.host().apply(STYLE), "FORMATTING_CODEC") && m.host().apply(STYLE) != null
                && m.helpers().has(WORLD, "formattingCodec", "()Lcom/mojang/serialization/Codec;")) {
            m.t().registerFieldRedirect(STYLE, "FORMATTING_CODEC", "Lcom/mojang/serialization/Codec;", WORLD, "formattingCodec",
                    "()Lcom/mojang/serialization/Codec;");
        }
        m.receiver(CRASH_REPORT, "getFriendlyReport", "()Ljava/lang/String;", "1.20.5", WORLD);
        for (String input : new String[]{"Ljava/io/Reader;", "Ljava/lang/String;"}) {
            m.statik(GSON_HELPER, "parse", "(" + input + "Z)L" + HostLibraryNames.GSON_PACKAGE + "/JsonObject;", "1.21.6", WORLD);
        }
        String id = m.idClass();
        String bySeparator = "(Ljava/lang/String;C)L" + id + ";";
        if (m.lacks(id, "of", bySeparator) && m.has(id, "bySeparator", bySeparator)) {
            m.t().registerMethodRedirect(id, "of", bySeparator, id, "bySeparator", bySeparator);
            m.t().limitRedirectToModsBefore(id, "of", bySeparator, "1.21");
        }
        // 1.21.11 moved Util into the util package.
        String util = m.host().apply(UTIL) != null ? UTIL : ROOT_UTIL;
        m.statik(util, "identityStrategy", "()Lit/unimi/dsi/fastutil/Hash$Strategy;", "1.21.2", WORLD);
        String oldPath = "(Ljava/lang/String;Ljava/nio/file/Path;Z)V";
        if (m.lacks(PATH_PACK, "<init>", oldPath) && m.helpers().has(WORLD, "pathPackResources", oldPath.replace(")V", ")L" + PATH_PACK + ";"))) {
            m.t().registerConstructorRedirect(PATH_PACK, oldPath, WORLD, "pathPackResources", oldPath.replace(")V", ")L" + PATH_PACK + ";"));
            m.t().limitRedirectToModsBefore(PATH_PACK, "<init>", oldPath, "1.20.5");
        }
        m.statik(PACK, "readMetaAndCreate", OLD_READ_META, "1.20.5", WORLD);
        // The supplier a mod hands it is a lambda of 1.20.1's open(String), which these hosts never call.
        LegacyPackSupplierLambdas.register(m.t(), m.host());
        m.statik(BLOCK_ITEM, "setBlockEntityData", "(" + L_STACK + "Lnet/minecraft/world/level/block/entity/BlockEntityType;" + L_TAG + ")V",
                "1.21.11", WORLD);
        m.receiver("net/minecraft/nbt/CompoundTag", "putIntArray", "(Ljava/lang/String;Ljava/util/List;)V", "1.21.5", WORLD);
        for (String codec : new String[]{"Lcom/mojang/serialization/Codec;", "Lcom/mojang/serialization/MapCodec;"}) {
            m.statik(EXTRA_CODECS, "validate", "(" + codec + "Ljava/util/function/Function;)" + codec, "1.20.5", WORLD);
        }
        m.statik(EXTRA_CODECS, "lazyInitializedCodec", "(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;", "1.20.5", WORLD);
        String writeVector = "(Lio/netty/buffer/ByteBuf;Lorg/joml/Vector3f;)V";
        String readOnly = "(Lio/netty/buffer/ByteBuf;Lorg/joml/Vector3fc;)V";
        if (m.lacks(BUF, "writeVector3f", writeVector) && m.has(BUF, "writeVector3f", readOnly)) {
            m.t().registerMethodRedirect(BUF, "writeVector3f", writeVector, BUF, "writeVector3f", readOnly);
        }
    }

    /** Client members: reach, models, settings, screens, sprites, languages, sounds and the fixed-function GL calls 1.17 removed. */
    static void registerClient(Members m) {
        if (m.host().apply(MINECRAFT) == null) return;
        m.statik(MINECRAFT, "fillReport", OLD_FILL_REPORT, "1.17", CLIENT);
        m.receiver(GAME_MODE, "getPickRange", "()F", "1.20.5", CLIENT);
        // Forge's reach getter became the vanilla interaction range attribute in 1.20.5.
        if (m.lacks(LOCAL_PLAYER, "getEntityReach", "()D") && m.has(LOCAL_PLAYER, "entityInteractionRange", "()D")) {
            m.t().registerMethodRedirect(LOCAL_PLAYER, "getEntityReach", "()D", LOCAL_PLAYER, "entityInteractionRange", "()D");
            m.t().limitRedirectToModsBefore(LOCAL_PLAYER, "getEntityReach", "()D", "1.20.5");
        }
        m.inherited(ENTITY_RENDERER, "getRenderOffset", "(" + L_ENTITY + "F)Lnet/minecraft/world/phys/Vec3;", "1.21.2", CLIENT);
        if (lacksField(m.host().apply(ENTITY), "noCulling") && m.helpers().has(CLIENT, "noCulling", "(Ljava/lang/Object;)Z")
                && m.helpers().has(CLIENT, "setNoCulling", "(Ljava/lang/Object;Z)V")) {
            m.t().registerFieldStaticBridge(ENTITY, "noCulling", CLIENT, "noCulling", "(Ljava/lang/Object;)Z",
                    "setNoCulling", "(Ljava/lang/Object;Z)V");
        }
        m.receiver(CUBE, "compile", OLD_CUBE_COMPILE, "1.21", CLIENT);
        String oldVideo = "(" + L_SCREEN + L_OPTIONS + ")V";
        String newVideo = "(" + L_SCREEN + "L" + MINECRAFT + ";" + L_OPTIONS + ")V";
        String videoFactory = "(" + L_SCREEN + L_OPTIONS + ")L" + VIDEO_SETTINGS + ";";
        if (m.lacks(VIDEO_SETTINGS, "<init>", oldVideo) && m.has(VIDEO_SETTINGS, "<init>", newVideo)
                && m.helpers().has(CLIENT, "videoSettings", videoFactory)) {
            m.t().registerConstructorRedirect(VIDEO_SETTINGS, oldVideo, CLIENT, "videoSettings", videoFactory);
            // parent, options -> parent, the running client, options
            m.t().registerSuperConstructorAdapter(VIDEO_SETTINGS, oldVideo, newVideo, mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
                mv.visitInsn(Opcodes.SWAP);
            });
        }
        String fovHelper = "(L" + CLIENT_PLAYER + ";)F";
        if (m.lacks(CLIENT_PLAYER, "getFieldOfViewModifier", "()F") && m.helpers().has(CLIENT, "getFieldOfViewModifier", fovHelper)) {
            for (String owner : List.of(CLIENT_PLAYER, LOCAL_PLAYER)) {
                m.t().registerMethodRedirect(owner, "getFieldOfViewModifier", "()F", CLIENT, "getFieldOfViewModifier", fovHelper, true);
                m.t().limitRedirectToModsBefore(owner, "getFieldOfViewModifier", "()F", "1.21.2");
            }
        }
        String volume = "(Lnet/minecraft/sounds/SoundSource;F)V";
        if (m.lacks(SOUND_MANAGER, "updateSourceVolume", volume) && m.has(SOUND_MANAGER, "updateCategoryVolume", volume)) {
            m.t().registerMethodRedirect(SOUND_MANAGER, "updateSourceVolume", volume, SOUND_MANAGER, "updateCategoryVolume", volume);
        }
        m.receiver(PINGER, "pingServer", OLD_PING, "1.21.6", CLIENT);
        if (m.host().apply(IDENTIFIER) != null) m.receiver(SPRITE, "getName", "()" + L_ID, "1.19.3", CLIENT);
        for (String size : new String[]{"getWidth", "getHeight"}) m.receiver(SPRITE, size, "()I", "1.19.3", CLIENT);
        String info = "()Lnet/minecraft/client/resources/language/LanguageInfo;";
        if (m.receiver(LANGUAGES, "getSelected", info, "1.19.4", CLIENT)) {
            m.receiver(LANGUAGE_INFO, "getCode", "()Ljava/lang/String;", "1.19.4", CLIENT);
        }
        m.receiver(CLIENT_LEVEL, "disconnect", "()V", "1.21.6", CLIENT);
        String recipeBook = "()Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;";
        String recipeHelper = "(Ljava/lang/Object;)Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;";
        // A recipe screen of the mod's own, or a vanilla one such as CraftingScreen, names its own class.
        m.redirect(RECIPE_LISTENER, "getRecipeBookComponent", recipeBook, recipeHelper, "1.21.2", true, CLIENT);
        // 1.20.2 turned the nine-slice draw into GUI sprites; these hosts still blit from a texture.
        for (String nineSliced : new String[]{NINE_SLICED_9, NINE_SLICED_10, NINE_SLICED_12}) {
            m.receiver(GUI_GRAPHICS, "blitNineSliced", nineSliced.replace(L_ID, "L" + m.idClass() + ";"), "1.20.2", CLIENT_BEFORE26);
        }
        registerRemovedClient(m);
    }

    /** Client members with no replacement call: renamed fields and classes, and calls that have no effect now. */
    static void registerRemovedClient(Members m) {
        if (m.has(SCREEN, "MENU_BACKGROUND") && lacksField(m.host().apply(SCREEN), "BACKGROUND_LOCATION")) {
            m.t().registerFieldRedirect(SCREEN, "BACKGROUND_LOCATION", SCREEN, "MENU_BACKGROUND");
        }
        if (m.host().apply(DIRT_SCREEN) == null && m.host().apply(MESSAGE_SCREEN) != null) {
            m.t().registerClassRedirect(DIRT_SCREEN, MESSAGE_SCREEN);
            m.t().limitClassRedirectToModsBefore(DIRT_SCREEN, "1.20.5");
        }
        if (m.host().apply(OGG_STREAM) == null && m.host().apply(JORBIS_STREAM) != null) m.t().registerClassRedirect(OGG_STREAM, JORBIS_STREAM);
        if (m.lacks(ENTITY_DATA_PACKET, "getUnpackedData", "()Ljava/util/List;") && m.has(ENTITY_DATA_PACKET, "packedItems", "()Ljava/util/List;")) {
            m.t().registerMethodRedirect(ENTITY_DATA_PACKET, "getUnpackedData", "()Ljava/util/List;", ENTITY_DATA_PACKET, "packedItems",
                    "()Ljava/util/List;");
            m.t().limitRedirectToModsBefore(ENTITY_DATA_PACKET, "getUnpackedData", "()Ljava/util/List;", "1.19.3");
        }
        // 1.20.2 renamed the advancement frame type and its getter.
        String frame = "()Lnet/minecraft/advancements/AdvancementType;";
        if (m.lacks(DISPLAY_INFO, "getFrame", frame) && m.has(DISPLAY_INFO, "getType", frame)) {
            m.t().registerMethodRedirect(DISPLAY_INFO, "getFrame", frame, DISPLAY_INFO, "getType", frame);
            m.t().limitRedirectToModsBefore(DISPLAY_INFO, "getFrame", frame, "1.20.2");
        }
        if (m.lacks(TEXTURE, "bind", "()V")) m.t().registerRemovedMethodNeutralize(TEXTURE, "bind", "()V");
        for (String[] call : REMOVED_GL_STATE) {
            if (m.lacks(RENDER_SYSTEM, call[0], call[1])) m.t().registerRemovedMethodNeutralize(RENDER_SYSTEM, call[0], call[1]);
        }
        for (String lighting : new String[]{"turnOff", "turnBackOn"}) {
            if (m.lacks(LIGHTING, lighting, "()V")) m.t().registerRemovedMethodNeutralize(LIGHTING, lighting, "()V");
        }
        if (m.lacks(KEYBOARD, "setSendRepeatsToGui", "(Z)V")) m.t().registerRemovedMethodNeutralize(KEYBOARD, "setSendRepeatsToGui", "(Z)V");
    }

    /** The transformer, the host and the helper methods that link there, with the checks each member makes. */
    record Members(RetromodTransformer t, Function<String, ClassNode> host, RebuiltHostLinks.Pruned helpers) {

        /** Whether the host has the class, and neither it nor anything it extends declares the method. */
        boolean lacks(String owner, String name, String desc) {
            return host.apply(owner) != null && lacksInherited(host, owner, name, desc);
        }

        boolean has(String owner, String name, String desc) {
            return host.apply(owner) != null && !lacksInherited(host, owner, name, desc);
        }

        boolean has(String owner, String field) {
            return !lacksField(host.apply(owner), field);
        }

        /** The resource id class the host names: Identifier from 1.21.11, ResourceLocation before. */
        String idClass() {
            return host.apply(IDENTIFIER) != null ? IDENTIFIER : RESOURCE_LOCATION;
        }

        /** Whether the host's {@code type} is or extends {@code ancestor}. */
        boolean isA(String type, String ancestor) {
            ArrayDeque<String> todo = new ArrayDeque<>(List.of(type));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String next = todo.poll();
                if (next.equals(ancestor)) return true;
                ClassNode node = seen.add(next) ? host.apply(next) : null;
                if (node == null) continue;
                if (node.superName != null) todo.add(node.superName);
                todo.addAll(node.interfaces);
            }
            return false;
        }

        /** Sends an instance call to the first helper that keeps a method for it, which takes the receiver first. */
        boolean receiver(String owner, String name, String desc, String firstWithout, String... helpers) {
            return redirect(owner, name, desc, "(L" + owner + ";" + desc.substring(1), firstWithout, false, helpers);
        }

        /** The same for a call that may name a subclass of {@code owner}, as a super call from an override does. */
        void inherited(String owner, String name, String desc, String firstWithout, String... helpers) {
            redirect(owner, name, desc, "(L" + owner + ";" + desc.substring(1), firstWithout, true, helpers);
        }

        void statik(String owner, String name, String desc, String firstWithout, String... helpers) {
            redirect(owner, name, desc, desc, firstWithout, false, helpers);
        }

        /** Sends the call to the first helper that keeps {@code name + helperDesc}, for mods built before {@code firstWithout}. */
        boolean redirect(String owner, String name, String desc, String helperDesc, String firstWithout, boolean inherited,
                String... helpers) {
            String helper = firstKeeping(owner, name, desc, helperDesc, helpers);
            if (helper == null) return false;
            if (inherited) t.registerInheritedMethodRedirect(owner, name, desc, helper, name, helperDesc);
            // A helper that takes the receiver turns the instance call static.
            if (helperDesc.equals(desc)) {
                t.registerMethodRedirect(owner, name, desc, helper, name, helperDesc);
            } else {
                t.registerMethodRedirect(owner, name, desc, helper, name, helperDesc, true);
            }
            t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
            return true;
        }

        /** A call the host answers under a new name with the same descriptor. */
        void rename(String owner, String name, String desc, String newName, String firstWithout) {
            if (!lacks(owner, name, desc) || !has(owner, newName, desc)) return;
            t.registerMethodRedirect(owner, name, desc, owner, newName, desc);
            t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
        }

        private String firstKeeping(String owner, String name, String desc, String helperDesc, String... helpers) {
            if (!lacks(owner, name, desc)) return null;
            for (String helper : helpers) {
                if (helpers().has(helper, name, helperDesc)) return helper;
            }
            return null;
        }
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
