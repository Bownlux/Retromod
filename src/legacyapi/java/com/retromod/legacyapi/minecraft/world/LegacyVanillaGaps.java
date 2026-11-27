/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.legacyapi.minecraft.server.LegacyServerCalls;
import it.unimi.dsi.fastutil.Hash;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.WalkAnimationState;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Gameplay, data and network members 1.16.5 to 1.21.1 mods call that 26.x removed or reshaped, each answered by what replaced it. */
public final class LegacyVanillaGaps {

    private static final String EXHAUSTION = "foodExhaustionLevel";
    /** The slot the item property type fills while it calls an old function; LegacyItemProperty writes the same key. */
    public static final String ITEM_OWNER_SLOT = "minecraft.itemOwner";
    // The interaction packets an old mod built as attacks, kept until the server handles them.
    // Attack packets every copy of this helper has built and not yet seen sent, held weakly and matched by identity.
    private static final String ATTACK_SLOT = "minecraft.attackPackets";

    private static final SlotAccess NULL_SLOT = new SlotAccess() {
        @Override
        public ItemStack get() {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean set(ItemStack stack) {
            return false;
        }
    };

    private LegacyVanillaGaps() {}

    /** {@code stack.getXpRepairRatio()}: durability points one experience point mends, which was 2 for every item. */
    public static float getXpRepairRatio(ItemStack stack) {
        return 2.0F;
    }

    /** {@code food.getExhaustionLevel()}: read from the saved form, the one public view of the private field. */
    public static float getExhaustionLevel(FoodData food) {
        return saved(food).getFloatOr(EXHAUSTION, 0.0F);
    }

    /** {@code food.setExhaustion(value)}: the saved form with the new value, read back into the same food data. */
    public static void setExhaustion(FoodData food, float value) {
        CompoundTag tag = saved(food);
        tag.putFloat(EXHAUSTION, value);
        food.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag));
    }

    private static CompoundTag saved(FoodData food) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        food.addAdditionalSaveData(output);
        return output.buildResult();
    }

    /** Forge's {@code item.getFoodProperties(stack, entity)}: the stack's food component, or null when it is no food. */
    public static FoodProperties getFoodProperties(Item item, ItemStack stack, LivingEntity entity) {
        return stack.get(DataComponents.FOOD);
    }

    /** {@code Style.FORMATTING_CODEC}: the style codec, which 1.20.5 folded into one. */
    public static Codec<Style> formattingCodec() {
        return Style.Serializer.CODEC;
    }

    /** {@code enchantment.getFullname(level)}: the name with its level; a curse reads like any other, since a bare value has no tags. */
    public static Component getFullname(Enchantment enchantment, int level) {
        return Enchantment.getFullname(Holder.direct(enchantment), level);
    }

    /** {@code report.getFriendlyReport()}: the crash form of the report. */
    public static String getFriendlyReport(CrashReport report) {
        return report.getFriendlyReport(ReportType.CRASH);
    }

    /** {@code Mth.sqrt(double)} of 1.16.5, which returned a float. */
    public static float sqrt(double value) {
        return (float) Math.sqrt(value);
    }

    /** {@code Direction.fromDelta(x, y, z)}: the direction one block step points, or null for any other step. */
    public static Direction fromDelta(int x, int y, int z) {
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == x && direction.getStepY() == y && direction.getStepZ() == z) return direction;
        }
        return null;
    }

    /** {@code projectile.mayInteract(level, pos)}: the server's answer; a client level, which knows no game rules, allows it. */
    public static boolean mayInteract(Projectile projectile, Level level, BlockPos pos) {
        return !(level instanceof ServerLevel server) || projectile.mayInteract(server, pos);
    }

    /** Reads the old {@code ServerPlayer.latency} field from the connection that keeps it now. */
    public static int latency(Object player) {
        ServerGamePacketListenerImpl connection = ((ServerPlayer) player).connection;
        return connection == null ? 0 : connection.latency();
    }

    /** A write of the old latency field, which the connection measures itself now. */
    public static void setLatency(Object player, int latency) {
    }

    /** Player's {@code playNotifySound}: a server player hears it alone, a client player hears it where it stands. */
    public static void playNotifySound(Player player, SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (player instanceof ServerPlayer server) {
            LegacyServerCalls.playNotifySound(server, sound, source, volume, pitch);
        } else if (player.level().isClientSide()) {
            player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), sound, source, volume, pitch, false);
        }
    }

    /** {@code entity.sendSystemMessage(text)}: players read it, as before; every other entity ignored it. */
    public static void sendSystemMessage(Entity entity, Component text) {
        if (entity instanceof Player player) player.sendSystemMessage(text);
    }

    /** {@code player.sendMessage(text, sender)} of 1.16 to 1.18: the system message every player still takes. */
    public static void sendMessage(Player player, Component text, UUID sender) {
        player.sendSystemMessage(text);
    }

    /** {@code NbtUtils.readBlockState(tag)}: read against the block registry, which 1.19.3 asks for. */
    public static BlockState readBlockState(CompoundTag tag) {
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
    }

    /** {@code component.getKey()} of the old translatable text: its key, else the text itself. */
    public static String getKey(MutableComponent component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : component.getString();
    }

    /** {@code SlotAccess.NULL}: a slot that holds nothing and takes nothing, which 26.1 dropped. */
    public static SlotAccess nullSlot() {
        return NULL_SLOT;
    }

    /** {@code BlockItem.setBlockEntityData(stack, type, tag)}: the tag as the block entity data an item carries now. */
    public static void setBlockEntityData(ItemStack stack, BlockEntityType<?> type, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            stack.remove(DataComponents.BLOCK_ENTITY_DATA);
        } else {
            stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(type, tag.copy()));
        }
    }

    /** {@code tag.putIntArray(key, list)}: the list as the int array tag takes. */
    public static void putIntArray(CompoundTag tag, String key, List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) array[i] = values.get(i);
        tag.putIntArray(key, array);
    }

    /** {@code input.createItemStack(count, checkCount)}: 26.1 always checks the count. */
    public static ItemStack createItemStack(ItemInput input, int count, boolean checkCount) throws CommandSyntaxException {
        return input.createItemStack(count);
    }

    /** {@code ExtraCodecs.validate(codec, check)}, which DataFixerUpper's codec answers itself now. */
    public static <A> Codec<A> validate(Codec<A> codec, Function<A, DataResult<A>> check) {
        return codec.validate(check);
    }

    /** {@code ExtraCodecs.validate(mapCodec, check)}. */
    public static <A> MapCodec<A> validate(MapCodec<A> codec, Function<A, DataResult<A>> check) {
        return codec.validate(check);
    }

    /** {@code walkAnimation.update(speed, scale)}: at the normal pace, as an adult's legs move. */
    public static void update(WalkAnimationState animation, float speed, float scale) {
        animation.update(speed, scale, 1.0F);
    }

    /** The overworld, nether and end sky ids of 1.20.1's {@code BuiltinDimensionTypes}, which 26.1 turned into attributes. */
    public static Identifier overworldEffects() {
        return Identifier.withDefaultNamespace("overworld");
    }

    public static Identifier netherEffects() {
        return Identifier.withDefaultNamespace("the_nether");
    }

    public static Identifier endEffects() {
        return Identifier.withDefaultNamespace("the_end");
    }

    /** {@code block.getCloneItemStack(level, pos, state)}: the state's pick block stack, without its block entity data. */
    public static ItemStack getCloneItemStack(Block block, BlockGetter level, BlockPos pos, BlockState state) {
        if (level instanceof LevelReader reader) return state.getCloneItemStack(reader, pos, false);
        return new ItemStack(block);
    }

    /** {@code stack.isFramed()}: whether an item frame is drawing the stack while an old item property reads it. */
    public static boolean isFramed(ItemStack stack) {
        return getFrame(stack) != null;
    }

    /** {@code stack.getFrame()}: the item frame drawing the stack while an old item property reads it, else null. */
    public static ItemFrame getFrame(ItemStack stack) {
        Object[] reading = currentItemOwner();
        if (reading == null || reading[0] != stack) return null;
        return reading[1] instanceof ItemFrame frame ? frame : null;
    }

    // The stack and owner an old item property function is called with, set by Retromod's item property type.
    @SuppressWarnings("unchecked")
    private static Object[] currentItemOwner() {
        Object reading = LegacySharedSlots.get(ITEM_OWNER_SLOT, 1, ThreadLocal.class, ThreadLocal::new).get();
        return reading instanceof Object[] pair && pair.length == 2 ? pair : null;
    }

    /** {@code TntBlock.explode(level, pos)}: the block primed with no one to blame. */
    public static void explode(Level level, BlockPos pos) {
        TntBlock.prime(level, pos);
    }

    /** {@code stack.getAttributeModifiers(slot)}: the stack's modifiers for that slot, keyed by attribute as before. */
    public static Multimap<Attribute, AttributeModifier> getAttributeModifiers(ItemStack stack, EquipmentSlot slot) {
        return modifiers(stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY), slot);
    }

    /** {@code item.getDefaultAttributeModifiers(slot)}: the item's own modifiers for that slot. */
    public static Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(Item item, EquipmentSlot slot) {
        return modifiers(item.components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY), slot);
    }

    private static Multimap<Attribute, AttributeModifier> modifiers(ItemAttributeModifiers modifiers, EquipmentSlot slot) {
        Multimap<Attribute, AttributeModifier> map = LinkedHashMultimap.create();
        // The old constants read as bare attributes, so the map is keyed by the holder's value.
        modifiers.forEach(slot, (attribute, modifier) -> map.put(attribute.value(), modifier));
        return map;
    }

    /** {@code entity.getPassengersRidingOffset()}: the height of the entity's passenger attachment point. */
    public static double getPassengersRidingOffset(Entity entity) {
        return entity.getAttachments().get(EntityAttachment.PASSENGER, 0, entity.getYRot()).y;
    }

    /** {@code EntityType.updateCustomEntityTag(level, player, entity, tag)}: the tag as the entity data an item carries now. */
    public static void updateCustomEntityTag(Level level, Player player, Entity entity, CompoundTag tag) {
        if (tag == null || entity == null) return;
        EntityType.updateCustomEntityTag(level, player, entity, TypedEntityData.of(entity.getType(), tag));
    }

    /** {@code contents.accountSimpleStack(stack)}: counted the way the item contents count it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void accountSimpleStack(Object contents, ItemStack stack) {
        if (contents instanceof StackedItemContents items) {
            items.accountSimpleStack(stack);
        } else if (contents instanceof StackedContents raw && !stack.isEmpty()) {
            raw.account(stack.typeHolder(), stack.getCount());
        }
    }

    /** {@code item.onCraftedBy(stack, level, player)}: the level came from the player from 1.21.5. */
    public static void onCraftedBy(Item item, ItemStack stack, Level level, Player player) {
        item.onCraftedBy(stack, player);
    }

    /** {@code tracker.recordDamage(source, health, damage)}: the health before the hit is no longer kept. */
    public static void recordDamage(CombatTracker tracker, DamageSource source, float health, float damage) {
        tracker.recordDamage(source, damage);
    }

    /** {@code DamageSource.thrown(projectile, owner)}: the thrown damage of the world the projectile is in. */
    public static DamageSource thrown(Entity projectile, Entity owner) {
        Entity inWorld = projectile != null ? projectile : owner;
        return inWorld.damageSources().thrown(projectile, owner);
    }

    /** {@code stack.getEnchantmentTags()}: the enchantments as the old list of id and level tags. */
    public static ListTag getEnchantmentTags(ItemStack stack) {
        ListTag list = new ListTag();
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : enchantments.entrySet()) {
            CompoundTag tag = new CompoundTag();
            entry.getKey().unwrapKey().ifPresent(key -> tag.putString("id", key.identifier().toString()));
            tag.putShort("lvl", (short) entry.getIntValue());
            list.add(tag);
        }
        return list;
    }

    /** {@code EnchantmentHelper.getEnchantmentId(tag)}: the id an old enchantment tag names, or null. */
    public static Identifier getEnchantmentId(CompoundTag tag) {
        return Identifier.tryParse(tag.getStringOr("id", ""));
    }

    /** {@code EnchantmentHelper.getEnchantmentLevel(tag)}: the level an old enchantment tag holds, kept within 0 to 255. */
    public static int getEnchantmentLevel(CompoundTag tag) {
        return Math.max(0, Math.min(255, tag.getIntOr("lvl", 0)));
    }

    /** {@code stack.getUseDuration()}: asked without the entity that 1.20.5 added. */
    public static int getUseDuration(ItemStack stack) {
        return stack.getUseDuration(null);
    }

    /** {@code GsonHelper.parse(reader, lenient)}: a lenient read still takes comments and trailing commas, as it used to. */
    public static JsonObject parse(Reader reader, boolean lenient) {
        if (!lenient) return GsonHelper.parse(reader);
        try {
            return parse(readAll(reader), true);
        } catch (java.io.IOException failed) {
            throw new JsonParseException(failed);
        }
    }

    /** {@code GsonHelper.parse(text, lenient)}: 26.x reads strictly, so a lenient read goes through Gson's own lenient parser. */
    public static JsonObject parse(String text, boolean lenient) {
        if (!lenient) return GsonHelper.parse(text);
        // Gson's lenient reader takes comments; trailing commas are dropped first, since it would read them as nulls.
        JsonElement read = JsonParser.parseString(withoutTrailingCommas(text));
        if (!read.isJsonObject()) throw new JsonParseException("Expected a JSON object, got " + read);
        return read.getAsJsonObject();
    }

    private static String readAll(Reader reader) throws java.io.IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        for (int read; (read = reader.read(buffer)) > 0; ) text.append(buffer, 0, read);
        return text.toString();
    }

    /** The text with each comma that closes an object or array removed, outside strings and comments. */
    static String withoutTrailingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int pendingComma = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                // Copy the whole string, escapes included.
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '"') end += text.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(end + 1, text.length());
                pendingComma = -1;
                out.append(text, i, end);
                i = end - 1;
            } else if (c == '/' && i + 1 < text.length() && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '*')) {
                // Comments pass through for Gson to skip, and do not end a pending comma.
                int end = text.charAt(i + 1) == '/' ? text.indexOf('\n', i) : text.indexOf("*/", i + 2) + 2;
                if (end <= 1 || end < i) end = text.length();
                out.append(text, i, end);
                i = end - 1;
            } else if (c == ',') {
                pendingComma = out.length();
                out.append(c);
            } else if ((c == '}' || c == ']') && pendingComma >= 0) {
                out.deleteCharAt(pendingComma);
                pendingComma = -1;
                out.append(c);
            } else {
                if (!Character.isWhitespace(c)) pendingComma = -1;
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@code Util.identityStrategy()}: a hash strategy that compares by identity. */
    public static <K> Hash.Strategy<K> identityStrategy() {
        return new Hash.Strategy<K>() {
            @Override
            public int hashCode(K value) {
                return System.identityHashCode(value);
            }

            @Override
            public boolean equals(K first, K second) {
                return first == second;
            }
        };
    }

    /** {@code ServerboundInteractPacket.createAttackPacket(entity, sneaking)}: an interaction marked as an attack until it is handled. */
    public static ServerboundInteractPacket createAttackPacket(Entity entity, boolean sneaking) {
        ServerboundInteractPacket packet = new ServerboundInteractPacket(entity.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, sneaking);
        List<WeakReference<Object>> attacks = attacks();
        synchronized (attacks) {
            attacks.add(new WeakReference<>(packet));
        }
        return packet;
    }

    /** {@code listener.handleInteract(packet)}: an attack an old mod built goes to the attack handler 26.x gave it. */
    public static void handleInteract(ServerGamePacketListenerImpl listener, ServerboundInteractPacket packet) {
        if (takeAttack(packet)) {
            listener.handleAttack(new ServerboundAttackPacket(packet.entityId()));
        } else {
            listener.handleInteract(packet);
        }
    }

    /** The packet to send for one an old mod built: its attack form when it came from createAttackPacket. */
    public static Object attackOrSame(Object packet) {
        if (packet instanceof ServerboundInteractPacket interact && takeAttack(interact)) {
            return new ServerboundAttackPacket(interact.entityId());
        }
        return packet;
    }

    private static boolean takeAttack(ServerboundInteractPacket packet) {
        List<WeakReference<Object>> attacks = attacks();
        synchronized (attacks) {
            // A record equals any interaction with the same values, so only this very packet counts.
            boolean found = false;
            for (Iterator<WeakReference<Object>> each = attacks.iterator(); each.hasNext(); ) {
                Object held = each.next().get();
                if (held == null || held == packet) each.remove();
                found |= held == packet;
            }
            return found;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<WeakReference<Object>> attacks() {
        return LegacySharedSlots.get(ATTACK_SLOT, 1, List.class, ArrayList::new);
    }

    /** {@code new PathPackResources(id, path, builtin)}: a built-in pack location named by its id. */
    public static PathPackResources pathPackResources(String id, java.nio.file.Path path, boolean builtin) {
        return new PathPackResources(location(id, Component.literal(id), builtin ? PackSource.BUILT_IN : PackSource.DEFAULT), path);
    }

    /** 1.20.1's {@code Pack.readMetaAndCreate(id, title, required, supplier, type, position, source)}. */
    public static Pack readMetaAndCreate(String id, Component title, boolean required, Pack.ResourcesSupplier supplier,
            PackType type, Pack.Position position, PackSource source) {
        return Pack.readMetaAndCreate(location(id, title, source), supplier, type, new PackSelectionConfig(required, position, false));
    }

    private static PackLocationInfo location(String id, Component title, PackSource source) {
        return new PackLocationInfo(id, title, source, Optional.empty());
    }

    /** {@code new PackedTicks(blockTicks, fluidTicks)} from tick containers: the copy a mod's own chunk hands out is never saved. */
    public static ChunkAccess.PackedTicks packedTicks(Object blockTicks, Object fluidTicks) {
        return new ChunkAccess.PackedTicks(List.of(), List.of());
    }

    /** {@code builder.addCarver(step, key)}: every carver is in one list since 1.21.2, so the step is dropped. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BiomeGenerationSettings.Builder addCarver(BiomeGenerationSettings.Builder builder, Object step, ResourceKey carver) {
        return builder.addCarver(carver);
    }

    /** {@code builder.addCarver(step, carver)} on the plain builder, without the step. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BiomeGenerationSettings.PlainBuilder addCarver(BiomeGenerationSettings.PlainBuilder builder, Object step, Holder carver) {
        return builder.addCarver(carver);
    }

    /** {@code settings.getCarvers(step)}: the biome's one carver list, which carves for every step. */
    public static Iterable<?> getCarvers(BiomeGenerationSettings settings, Object step) {
        return settings.getCarvers();
    }

    /** The paletted container factory a chunk takes now, built from the biome registry the old chunk constructor took. */
    @SuppressWarnings("unchecked")
    public static PalettedContainerFactory containerFactory(Object biomes) {
        return PalettedContainerFactory.create(new RegistryAccess.ImmutableRegistryAccess(List.of((Registry<Biome>) biomes)));
    }
}
