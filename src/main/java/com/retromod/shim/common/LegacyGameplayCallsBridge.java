/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Gameplay, registry, data and network members 1.20.1 and 1.21.1 mods call that 26.x removed, each rebuilt on its replacement. */
public final class LegacyGameplayCallsBridge {

    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/";
    static final String REGISTRY_TAGS = ROOT + "core/LegacyRegistryTags";
    static final String LEVEL_CALLS = ROOT + "world/level/LegacyLevelCalls";
    static final String ITEM_CALLS = ROOT + "world/item/LegacyItemCalls";
    static final String SERVER_CALLS = ROOT + "server/LegacyServerCalls";
    static final String CHAT_CALLS = ROOT + "network/chat/LegacyChatCalls";
    static final String NETWORK_CALLS = ROOT + "network/LegacyNetworkCalls";
    static final String BLOCK_SHADES = ROOT + "client/renderer/block/LegacyBlockShades";

    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String DEFAULTED_REGISTRY = "net/minecraft/core/DefaultedRegistry";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String STRUCTURE_MANAGER = "net/minecraft/world/level/StructureManager";
    static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    static final String ENCHANTMENT_HELPER = "net/minecraft/world/item/enchantment/EnchantmentHelper";
    static final String CUSTOM_DATA = "net/minecraft/world/item/component/CustomData";
    static final String DISPLAY_INFO = "net/minecraft/advancements/DisplayInfo";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String STORAGE_ACCESS = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
    static final String TEXT_COLOR = "net/minecraft/network/chat/TextColor";
    static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    static final String CHUNK_HOLDER = "net/minecraft/server/level/ChunkHolder";
    static final String GENERATION_HOLDER = "net/minecraft/server/level/GenerationChunkHolder";
    static final String CLEARABLE = "net/minecraft/world/Clearable";
    static final String TEMPLATE = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
    static final String LEVEL_ACCESSOR = "net/minecraft/world/level/LevelAccessor";
    static final String LEVEL_READER = "net/minecraft/world/level/LevelReader";
    static final String TINT_GETTER = "net/minecraft/client/renderer/block/BlockAndTintGetter";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String SPAWN_EGG = "net/minecraft/world/item/SpawnEggItem";
    static final String REGISTRY_BUF = "net/minecraft/network/RegistryFriendlyByteBuf";
    static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    static final String ADVANCEMENT = "net/minecraft/advancements/Advancement";
    static final String ADD_ENTITY = "net/minecraft/network/protocol/game/ClientboundAddEntityPacket";
    static final String DIMENSION_TYPE = "net/minecraft/world/level/dimension/DimensionType";
    static final String CHAT_BOUND = "net/minecraft/network/chat/ChatType$Bound";
    static final String SKULL = "net/minecraft/world/level/block/entity/SkullBlockEntity";
    static final String PROFESSION = "net/minecraft/world/entity/npc/villager/VillagerProfession";
    static final String OPERATION = "net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String SPAWN_DATA = "net/minecraft/world/level/SpawnData";
    static final String CONTAINER = "net/minecraft/world/item/component/ItemContainerContents";
    static final String BUNDLE = "net/minecraft/world/item/component/BundleContents";
    static final String VAR_INT = "net/minecraft/network/VarInt";

    static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    static final String L_STATE = "L" + STATE + ";";
    static final String L_BLOCK = "Lnet/minecraft/world/level/block/Block;";
    static final String L_STACK = "Lnet/minecraft/world/item/ItemStack;";
    static final String TEMPLATE_MANAGER = "()Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;";
    static final String TAG = "(Lnet/minecraft/tags/TagKey;)Lnet/minecraft/core/HolderSet$Named;";
    static final String OWNER = "()Lnet/minecraft/core/HolderOwner;";
    static final String PIECE_AT_KEY = "(" + L_POS + "Lnet/minecraft/resources/ResourceKey;)"
            + "Lnet/minecraft/world/level/levelgen/structure/StructureStart;";
    static final String PATHFINDABLE = "(Lnet/minecraft/world/level/BlockGetter;" + L_POS
            + "Lnet/minecraft/world/level/pathfinder/PathComputationType;)Z";
    static final String PARTICLE = "(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V";
    static final String NEIGHBOR = "(" + L_STATE + L_POS + L_BLOCK + L_POS + "Z)V";
    static final String NEIGHBOR_AT = "(" + L_POS + L_BLOCK + L_POS + ")V";
    static final String UPDATE_STATE = "(" + L_STATE + "Lnet/minecraft/world/level/block/state/properties/Property;"
            + "Ljava/lang/String;)" + L_STATE;
    static final String CURSE = "(" + L_STACK + ")Z";
    static final String ICON = "()" + L_STACK;
    static final String TEMPLATE_ICON = "()Lnet/minecraft/world/item/ItemStackTemplate;";
    static final String PROFILE_PERMISSIONS = "(Lcom/mojang/authlib/GameProfile;)I";
    static final String DATA_TAG = "()Lcom/mojang/serialization/Dynamic;";
    static final String DATA_TAG_FALLBACK = "(Z)Lcom/mojang/serialization/Dynamic;";
    static final String PARSE_COLOR = "(Ljava/lang/String;)L" + TEXT_COLOR + ";";
    static final String OLD_SPAWN_DATA = "(Lnet/minecraft/nbt/CompoundTag;Ljava/util/Optional;)V";
    static final String STREAM = "()Ljava/util/stream/Stream;";
    static final String SHORT_SOUND = "(Lnet/minecraft/world/entity/player/Player;" + L_POS
            + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;)V";
    static final String DIFFICULTY = "(" + L_POS + ")Lnet/minecraft/world/DifficultyInstance;";
    static final String SHADE = "(Lnet/minecraft/core/Direction;Z)F";
    static final String OCCLUSION = "(Lnet/minecraft/world/level/BlockGetter;" + L_POS + "Lnet/minecraft/core/Direction;)"
            + "Lnet/minecraft/world/phys/shapes/VoxelShape;";
    static final String NOTIFY_SOUND = "(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V";
    static final String CRAFTED_BY = "(L" + LEVEL + ";Lnet/minecraft/world/entity/player/Player;I)V";
    static final String EGG_TYPE = "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/EntityType;";
    static final String READ_BY_ID = "(Lnet/minecraft/core/IdMap;)Ljava/lang/Object;";
    static final String WRITE_ID = "(Lnet/minecraft/core/IdMap;Ljava/lang/Object;)V";
    static final String ATTACK_BLOCK = "(" + L_STATE + "L" + LEVEL + ";" + L_POS + "Lnet/minecraft/world/entity/player/Player;)Z";
    static final String STATUS = "()Lnet/minecraft/world/level/chunk/status/ChunkStatus;";
    static final String SCHEDULE = "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/server/level/ChunkMap;)"
            + "Ljava/util/concurrent/CompletableFuture;";
    static final String FILL = "(L" + LEVEL + ";" + L_POS + "Lnet/minecraft/core/Vec3i;Z" + L_BLOCK + ")V";

    /** The level types javac names for an old level call. */
    static final List<String> LEVEL_OWNERS = List.of(LEVEL, SERVER_LEVEL, CLIENT_LEVEL);
    /** The server types javac names for an old server call. */
    static final List<String> SERVER_OWNERS = List.of(SERVER, "net/minecraft/server/dedicated/DedicatedServer",
            "net/minecraft/client/server/IntegratedServer");

    private LegacyGameplayCallsBridge() {}

    /** Registers each bridge whose old member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Every helper is built on 26.x shapes, so an older target keeps its own members.
        if (!targetAtLeast("26.1")) return;
        registerRegistry(t, host);
        registerLevel(t, host);
        registerItems(t, host);
        registerServer(t, host);
        registerNetwork(t, host);
    }

    /** The registry's tag and owner getters, which 1.21.2 folded into its lookup. */
    static void registerRegistry(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode registry = host.apply(REGISTRY);
        for (String owner : List.of(REGISTRY, DEFAULTED_REGISTRY)) {
            if (lacks(registry, "getOrCreateTag", TAG)) {
                registryCall(t, owner, "getOrCreateTag", TAG, "1.21.2");
            }
            if (lacks(registry, "holderOwner", OWNER)) {
                registryCall(t, owner, "holderOwner", OWNER, "1.21.2");
            }
        }
    }

    /** Structure, block state, spawner, sound, difficulty and shade calls in their 1.20.1 and 1.21.1 shapes. */
    static void registerLevel(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(STRUCTURE_MANAGER), "getStructureWithPieceAt", PIECE_AT_KEY)) {
            receiverCall(t, STRUCTURE_MANAGER, "getStructureWithPieceAt", PIECE_AT_KEY, LEVEL_CALLS, "1.21.1");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        // The state helpers take the state as Object, so a call made through BlockStateBase passes the verifier too.
        if (lacks(host.apply(STATE_BASE), "isPathfindable", PATHFINDABLE)) {
            ownersCall(t, List.of(STATE, STATE_BASE), "isPathfindable", PATHFINDABLE, LEVEL_CALLS, "isPathfindable",
                    "(Ljava/lang/Object;" + PATHFINDABLE.substring(1), "1.21.1");
        }
        if (lacks(host.apply(STATE_BASE), "getFaceOcclusionShape", OCCLUSION)) {
            ownersCall(t, List.of(STATE, STATE_BASE), "getFaceOcclusionShape", OCCLUSION, LEVEL_CALLS, "getFaceOcclusionShape",
                    "(Ljava/lang/Object;" + OCCLUSION.substring(1), "1.21.2");
        }
        ClassNode level = host.apply(LEVEL);
        String[][] calls = {{"addParticle", PARTICLE, "1.21.4"}, {"neighborChanged", NEIGHBOR, "1.21.2"},
                {"neighborChanged", NEIGHBOR_AT, "1.21.2"}};
        for (String[] call : calls) {
            if (!lacks(level, call[0], call[1])) continue;
            ownersCall(t, LEVEL_OWNERS, call[0], call[1], LEVEL_CALLS, call[0], "(L" + LEVEL + ";" + call[1].substring(1), call[2]);
        }
        // 1.20.5 gave a spawner entry its equipment; an old entry has none.
        if (lacks(host.apply(SPAWN_DATA), "<init>", OLD_SPAWN_DATA)) {
            t.registerConstructorRedirect(SPAWN_DATA, OLD_SPAWN_DATA, LEVEL_CALLS, "spawnData",
                    "(Lnet/minecraft/nbt/CompoundTag;Ljava/util/Optional;)L" + SPAWN_DATA + ";");
            t.limitRedirectToModsBefore(SPAWN_DATA, "<init>", OLD_SPAWN_DATA, "1.21.1");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        // 1.21.5 dropped the static clear helper, and 1.21.6 let a template capture skip a list of blocks.
        if (lacks(host.apply(CLEARABLE), "tryClear", "(Ljava/lang/Object;)V")) {
            staticCall(t, LEVEL_CALLS, CLEARABLE, "tryClear", "(Ljava/lang/Object;)V", "1.21.5");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        if (lacks(host.apply(TEMPLATE), "fillFromWorld", FILL)) {
            receiverCall(t, TEMPLATE, "fillFromWorld", FILL, LEVEL_CALLS, "1.21.6");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        // A skull's owner and a profession's name became richer types; old code reads the plain value.
        if (lacks(host.apply(SKULL), "getOwnerProfile", "()Lcom/mojang/authlib/GameProfile;")) {
            receiverCall(t, SKULL, "getOwnerProfile", "()Lcom/mojang/authlib/GameProfile;", LEVEL_CALLS, "1.21.1");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        if (lacks(host.apply(PROFESSION), "name", "()Ljava/lang/String;")) {
            t.registerMethodRedirect(PROFESSION, "name", "()Ljava/lang/String;", LEVEL_CALLS, "professionName",
                    "(L" + PROFESSION + ";)Ljava/lang/String;", true);
            t.limitRedirectToModsBefore(PROFESSION, "name", "()Ljava/lang/String;", "1.21.11");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        // The short sound call played at full volume, and the difficulty and shade reads left the base level class.
        if (lacks(level, "playSound", SHORT_SOUND)) {
            ownersCall(t, List.of(LEVEL, SERVER_LEVEL, CLIENT_LEVEL), "playSound", SHORT_SOUND, LEVEL_CALLS, "playSound",
                    "(L" + LEVEL + ";" + SHORT_SOUND.substring(1), "1.21.5");
        }
        if (lacks(level, "getCurrentDifficultyAt", DIFFICULTY)) {
            ownersCall(t, List.of(LEVEL, LEVEL_ACCESSOR, CLIENT_LEVEL), "getCurrentDifficultyAt", DIFFICULTY, LEVEL_CALLS,
                    "getCurrentDifficultyAt", "(Ljava/lang/Object;" + DIFFICULTY.substring(1), "1.21.11");
        }
        if (lacks(level, "getShade", SHADE)) {
            ownersCall(t, List.of(LEVEL, LEVEL_READER, LEVEL_ACCESSOR, SERVER_LEVEL, "net/minecraft/server/level/WorldGenRegion"),
                    "getShade", SHADE, BLOCK_SHADES, "getLevelShade",
                    "(Ljava/lang/Object;" + SHADE.substring(1), "26.1");
            // A client level is a block getter, which the 26.1 shade helper already reads.
            ownersCall(t, List.of(CLIENT_LEVEL), "getShade", SHADE, BLOCK_SHADES, "getShade",
                    "(L" + TINT_GETTER + ";" + SHADE.substring(1), "26.1");
        }
        // 1.21.11 moved the dimension's rule flags into environment attributes.
        ClassNode dimension = host.apply(DIMENSION_TYPE);
        for (String flag : new String[]{"ultraWarm", "natural", "respawnAnchorWorks"}) {
            if (!lacks(dimension, flag, "()Z")) continue;
            t.registerMethodRedirect(DIMENSION_TYPE, flag, "()Z", LEVEL_CALLS, flag, "(L" + DIMENSION_TYPE + ";)Z", true);
            t.limitRedirectToModsBefore(DIMENSION_TYPE, flag, "()Z", "1.21.11");
            RebuiltApiClasses.registerTree(t, LEVEL_CALLS);
        }
        // 26.3 renamed the template manager getter on the server and its levels.
        String[] holders = {SERVER_LEVEL, SERVER};
        for (String owner : holders) {
            ClassNode node = host.apply(owner);
            boolean renamed = node == null ? targetAtLeast("26.3")
                    : lacks(node, "getStructureManager", TEMPLATE_MANAGER) && !lacks(node, "getStructureTemplateManager", TEMPLATE_MANAGER);
            if (renamed) {
                t.registerMethodRedirect(owner, "getStructureManager", TEMPLATE_MANAGER, owner, "getStructureTemplateManager",
                        TEMPLATE_MANAGER);
            }
        }
    }

    /** Block item states, item checks, curses, custom data, container streams and advancement displays. */
    static void registerItems(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean used = false;
        if (lacks(host.apply(BLOCK_ITEM), "updateState", UPDATE_STATE)) {
            staticCall(t, ITEM_CALLS, BLOCK_ITEM, "updateState", UPDATE_STATE, "1.21.1");
            used = true;
        }
        // Mining checks ask the stack now; the old item check asks about the one the player holds.
        if (lacks(host.apply(ITEM), "canAttackBlock", ATTACK_BLOCK)) {
            t.registerMethodRedirect(ITEM, "canAttackBlock", ATTACK_BLOCK, ITEM_CALLS, "canAttackBlock",
                    "(L" + ITEM + ";" + ATTACK_BLOCK.substring(1), true);
            t.limitRedirectToModsBefore(ITEM, "canAttackBlock", ATTACK_BLOCK, "1.21.5");
            used = true;
        }
        if (lacks(host.apply(ITEM_STACK), "onCraftedBy", CRAFTED_BY)) {
            receiverCall(t, ITEM_STACK, "onCraftedBy", CRAFTED_BY, ITEM_CALLS, "1.21.5");
            used = true;
        }
        if (lacks(host.apply(SPAWN_EGG), "getType", EGG_TYPE)) {
            t.registerMethodRedirect(SPAWN_EGG, "getType", EGG_TYPE, ITEM_CALLS, "spawnEggType", "(L" + SPAWN_EGG + ";" + EGG_TYPE.substring(1), true);
            t.limitRedirectToModsBefore(SPAWN_EGG, "getType", EGG_TYPE, "1.21.1");
            used = true;
        }
        if (lacks(host.apply(OPERATION), "toValue", "()I")) {
            t.registerMethodRedirect(OPERATION, "toValue", "()I", OPERATION, "id", "()I");
            t.limitRedirectToModsBefore(OPERATION, "toValue", "()I", "1.21.1");
        }
        ClassNode enchantments = host.apply(ENCHANTMENT_HELPER);
        for (String curse : new String[]{"hasVanishingCurse", "hasBindingCurse"}) {
            if (!lacks(enchantments, curse, CURSE)) continue;
            staticCall(t, ITEM_CALLS, ENCHANTMENT_HELPER, curse, CURSE, "1.21.1");
            used = true;
        }
        // NeoForge 26.3 put contains back on custom data; vanilla and Forge lack it from 1.21.9.
        if (lacks(host.apply(CUSTOM_DATA), "contains", "(Ljava/lang/String;)Z")) {
            receiverCall(t, CUSTOM_DATA, "contains", "(Ljava/lang/String;)Z", ITEM_CALLS, "1.21.9");
            used = true;
        }
        // Container contents hold stack templates from 26.1; the copying streams kept their meaning under new names.
        renameToHosts(t, host.apply(CONTAINER), CONTAINER, "stream", STREAM, "allItemsCopyStream", "itemCopies");
        renameToHosts(t, host.apply(CONTAINER), CONTAINER, "nonEmptyStream", STREAM, "nonEmptyItemCopyStream", "nonEmptyItemCopyStream");
        renameToHosts(t, host.apply(BUNDLE), BUNDLE, "itemCopyStream", STREAM, "itemCopyStream", "itemCopies");
        if (lacks(host.apply(BUNDLE), "items", "()Ljava/lang/Iterable;")) {
            receiverCall(t, BUNDLE, "items", "()Ljava/lang/Iterable;", ITEM_CALLS, "26.1");
            used = true;
        }
        // 1.20.2 made the advancement a record whose display is optional.
        if (lacks(host.apply(ADVANCEMENT), "getDisplay", "()L" + DISPLAY_INFO + ";")) {
            receiverCall(t, ADVANCEMENT, "getDisplay", "()L" + DISPLAY_INFO + ";", ITEM_CALLS, "1.21.1");
            used = true;
        }
        ClassNode display = host.apply(DISPLAY_INFO);
        if (lacks(display, "getIcon", ICON)) {
            receiverCall(t, DISPLAY_INFO, "getIcon", ICON, ITEM_CALLS, "26.1");
        }
        // The helper reads the 26.3 record's icon(); 26.1 and 26.2 name the same getter getIcon.
        if (used || lacks(display, "getIcon", ICON)) RebuiltApiClasses.registerTree(t, ITEM_CALLS, iconNames(display));
    }

    /** Permission levels as numbers, notify sounds, chunk generation futures, and the saved level data before upgrades. */
    static void registerServer(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode server = host.apply(SERVER);
        String[][] calls = {{"getProfilePermissions", PROFILE_PERMISSIONS, "1.21.9"}, {"getOperatorUserPermissionLevel", "()I", "1.21.11"}};
        for (String[] call : calls) {
            if (!lacks(server, call[0], call[1])) continue;
            ownersCall(t, SERVER_OWNERS, call[0], call[1], SERVER_CALLS, call[0], "(L" + SERVER + ";" + call[1].substring(1), call[2]);
        }
        if (lacks(host.apply(SERVER_PLAYER), "playNotifySound", NOTIFY_SOUND)) {
            receiverCall(t, SERVER_PLAYER, "playNotifySound", NOTIFY_SOUND, SERVER_CALLS, "1.21.11");
            RebuiltApiClasses.registerTree(t, SERVER_CALLS);
        }
        // 1.20.5 split chunk generation into GenerationChunkHolder, which names the same status and future differently.
        ClassNode generation = host.apply(GENERATION_HOLDER);
        if (lacks(host.apply(CHUNK_HOLDER), "getLastAvailableStatus", STATUS) && (generation == null || !lacks(generation, "getLatestStatus", STATUS))) {
            t.registerMethodRedirect(CHUNK_HOLDER, "getLastAvailableStatus", STATUS, CHUNK_HOLDER, "getLatestStatus", STATUS);
            t.limitRedirectToModsBefore(CHUNK_HOLDER, "getLastAvailableStatus", STATUS, "1.21.1");
        }
        if (lacks(host.apply(CHUNK_HOLDER), "getOrScheduleFuture", SCHEDULE)) {
            t.registerMethodRedirect(CHUNK_HOLDER, "getOrScheduleFuture", SCHEDULE, SERVER_CALLS, "getOrScheduleFuture",
                    "(L" + GENERATION_HOLDER + ";" + SCHEDULE.substring(1), true);
            t.limitRedirectToModsBefore(CHUNK_HOLDER, "getOrScheduleFuture", SCHEDULE, "1.21.1");
            RebuiltApiClasses.registerTree(t, SERVER_CALLS);
        }
        // 26.1 renamed both level data reads to their unfixed names and stopped upgrading the data in them.
        ClassNode access = host.apply(STORAGE_ACCESS);
        for (String dataTag : new String[]{DATA_TAG, DATA_TAG_FALLBACK}) {
            if (!lacks(access, "getDataTag", dataTag)) continue;
            receiverCall(t, STORAGE_ACCESS, "getDataTag", dataTag, SERVER_CALLS, "26.1");
            RebuiltApiClasses.registerTree(t, SERVER_CALLS);
        }
    }

    /** Color parsing, chat types, id map reads and writes, and the var int size, in their 1.20.1 shapes. */
    static void registerNetwork(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(TEXT_COLOR), "parseColor", PARSE_COLOR)) {
            staticCall(t, CHAT_CALLS, TEXT_COLOR, "parseColor", PARSE_COLOR, "1.21.1");
            RebuiltApiClasses.registerTree(t, CHAT_CALLS);
        }
        String boundType = "()Lnet/minecraft/network/chat/ChatType;";
        if (lacks(host.apply(CHAT_BOUND), "chatType", boundType)) {
            receiverCall(t, CHAT_BOUND, "chatType", boundType, CHAT_CALLS, "1.21.1");
            RebuiltApiClasses.registerTree(t, CHAT_CALLS);
        }
        // The id map reads and writes became IntFunction forms, with the same var int.
        ClassNode buf = host.apply(BUF);
        for (String[] idCall : new String[][]{{"readById", READ_BY_ID}, {"writeId", WRITE_ID}}) {
            if (!lacks(buf, idCall[0], idCall[1])) continue;
            ownersCall(t, List.of(BUF, REGISTRY_BUF), idCall[0], idCall[1], NETWORK_CALLS, idCall[0], "(L" + BUF + ";" + idCall[1].substring(1), "1.21.1");
        }
        // A spawn packet is built from the server's entity tracker now; the old forms read the entity itself.
        ClassNode addEntity = host.apply(ADD_ENTITY);
        for (String form : new String[]{"(Lnet/minecraft/world/entity/Entity;)", "(Lnet/minecraft/world/entity/Entity;I)"}) {
            if (!lacks(addEntity, "<init>", form + "V")) continue;
            t.registerConstructorRedirect(ADD_ENTITY, form + "V", NETWORK_CALLS, "addEntity", form + "L" + ADD_ENTITY + ";");
            t.limitRedirectToModsBefore(ADD_ENTITY, "<init>", form + "V", "1.21.1");
            RebuiltApiClasses.registerTree(t, NETWORK_CALLS);
        }
        // VarInt took the size helper in 1.20.2 under a new name, with the same meaning.
        if (lacks(host.apply(BUF), "getVarIntSize", "(I)I")) {
            t.registerMethodRedirect(BUF, "getVarIntSize", "(I)I", VAR_INT, "getByteSize", "(I)I");
            t.limitRedirectToModsBefore(BUF, "getVarIntSize", "(I)I", "1.21.1");
        }
    }

    /** Sends {@code oldName} to the name the host has: {@code before263} on 26.1 and 26.2, {@code from263} on 26.3. */
    static void renameToHosts(RetromodTransformer t, ClassNode owner, String ownerName, String oldName, String desc,
            String before263, String from263) {
        String target;
        if (owner == null) {
            target = targetAtLeast("26.3") ? from263 : before263;
        } else {
            if (!lacks(owner, oldName, desc)) return;
            target = !lacks(owner, from263, desc) ? from263 : !lacks(owner, before263, desc) ? before263 : null;
        }
        if (target != null && !target.equals(oldName)) t.registerMethodRedirect(ownerName, oldName, desc, ownerName, target, desc);
    }

    /** Renames the helper's DisplayInfo.icon() to getIcon on a host that still calls it that; 26.3 and offline 26.3 keep it. */
    static Remapper iconNames(ClassNode display) {
        boolean named = display == null ? !targetAtLeast("26.3") : !lacks(display, "getIcon", TEMPLATE_ICON);
        return named ? new SimpleRemapper(Map.of(DISPLAY_INFO + ".icon" + TEMPLATE_ICON, "getIcon")) : null;
    }

    /** A registry call, whose helper takes any registry type as Registry. */
    private static void registryCall(RetromodTransformer t, String owner, String name, String desc, String firstWithout) {
        t.registerMethodRedirect(owner, name, desc, REGISTRY_TAGS, name, "(L" + REGISTRY + ";" + desc.substring(1), true);
        t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
        RebuiltApiClasses.registerTree(t, REGISTRY_TAGS);
    }

    /** An instance call on each of {@code owners}, answered by a helper that takes the receiver first. */
    private static void ownersCall(RetromodTransformer t, List<String> owners, String name, String desc, String helper, String helperName,
            String helperDesc, String firstWithout) {
        for (String owner : owners) {
            t.registerMethodRedirect(owner, name, desc, helper, helperName, helperDesc, true);
            t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
        }
        RebuiltApiClasses.registerTree(t, helper);
    }

    private static void receiverCall(RetromodTransformer t, String owner, String name, String desc, String helper, String firstWithout) {
        t.registerMethodRedirect(owner, name, desc, helper, name, "(L" + owner + ";" + desc.substring(1), true);
        t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
    }

    private static void staticCall(RetromodTransformer t, String helper, String owner, String name, String desc, String firstWithout) {
        t.registerMethodRedirect(owner, name, desc, helper, name, desc);
        t.limitRedirectToModsBefore(owner, name, desc, firstWithout);
    }

    /** Whether the host class lacks the member; an unreadable class counts as lacking it, since this bridge starts at 26.1. */
    static boolean lacks(ClassNode owner, String name, String desc) {
        if (owner == null) return true;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
