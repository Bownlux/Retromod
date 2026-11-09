/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.CompoundIngredient;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;

/** Calls that stopped Balm, Waystones, Farmer's Delight and JEI from constructing on Forge 26.2. */
final class ConstructionCases {

    private ConstructionCases() {}

    /** Both forms of DataFixerUpper 6's {@code Codec.unit}, which Balm's world gen codecs use. */
    static boolean unitCodecs() {
        Codec<String> fixed = Codec.unit("fixed");
        Codec<String> supplied = Codec.unit(() -> "supplied");
        return "fixed".equals(fixed.parse(JsonOps.INSTANCE, new JsonObject()).result().orElse(null))
                && "supplied".equals(supplied.parse(JsonOps.INSTANCE, new JsonObject()).result().orElse(null));
    }

    /** A recipe book type made in a static initializer, as Farmer's Delight's main class makes its cooking type. */
    static boolean recipeBookType() {
        RecipeBookType type = BookTypes.COOKING;
        // Forge 1.20.1 adds the type; a host that cannot add one hands back the smoker's.
        return type != null && (type.name().equals("RETROMOD_TEST_COOKING") || type == RecipeBookType.SMOKER);
    }

    private static final class BookTypes {
        static final RecipeBookType COOKING = RecipeBookType.create("RETROMOD_TEST_COOKING");
    }

    /** {@code CompoundIngredient.of}, and on Forge also Farmer's Delight's own subclass, build while the mod constructs. */
    static boolean compoundIngredientsBuild() {
        // NeoForge's Ingredient is final, so a subclass has nothing to extend there; its own class keeps it out of this one.
        return compound() != null && (LegacyEventBaseCase.onNeoForge() || FoodIngredient.build() != null);
    }

    /** The same ingredients match either child's items. Item stacks need item components, which a server binds as its data loads. */
    static boolean compoundIngredientsMatch() {
        Ingredient built = compound();
        boolean factory = built.test(new ItemStack(Items.WHEAT)) && !built.test(new ItemStack(Items.STONE));
        return factory && (LegacyEventBaseCase.onNeoForge() || FoodIngredient.matches());
    }

    private static Ingredient compound() {
        return CompoundIngredient.of(Ingredient.of(Items.CARROT), Ingredient.of(Items.WHEAT));
    }

    /** Farmer's Delight's {@code new CompoundIngredient(list) {}}. */
    private static final class FoodIngredient {
        static Ingredient build() {
            return new CompoundIngredient(List.of(Ingredient.of(Items.CARROT), Ingredient.of(Items.WHEAT))) {};
        }

        static boolean matches() {
            Ingredient food = build();
            return food.test(new ItemStack(Items.WHEAT)) && food.test(new ItemStack(Items.CARROT))
                    && !food.test(new ItemStack(Items.STONE));
        }
    }

    /** Balm's ByteBufCodecs writes and reads an id through the buffer by method reference. */
    static boolean bufferIdentifierRoundTrip() {
        BiConsumer<FriendlyByteBuf, ResourceLocation> write = FriendlyByteBuf::writeResourceLocation;
        Function<FriendlyByteBuf, ResourceLocation> read = FriendlyByteBuf::readResourceLocation;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ResourceLocation id = new ResourceLocation("retromod_test_mod_forge", "buffer_id");
        write.accept(buffer, id);
        buffer.writeResourceLocation(id);
        return id.equals(read.apply(buffer)) && id.equals(buffer.readResourceLocation());
    }

    /** JEI opens an event channel and listens on it while it constructs. */
    static boolean eventChannel() {
        EventNetworkChannel channel = NetworkRegistry.newEventChannel(
                new ResourceLocation("retromod_test_mod_forge", "events"), () -> "1", version -> true, version -> true);
        channel.addListener((NetworkEvent.ServerCustomPayloadEvent event) -> event.getSource().get().setPacketHandled(true));
        return channel != null;
    }

    /** JEI reads its mod name format through ChatFormatting.getByName. */
    static boolean chatFormattingByName() {
        return ChatFormatting.getByName("gold") == ChatFormatting.GOLD && ChatFormatting.getByName("teal") == null;
    }
}
