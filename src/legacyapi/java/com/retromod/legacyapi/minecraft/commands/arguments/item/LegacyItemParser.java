/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.commands.arguments.item;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Minecraft 1.20.4's {@code ItemParser.parseForItem}, which 1.20.5 replaced with a component parser, and the 1.20.5 {@code parse}. */
public final class LegacyItemParser {

    private LegacyItemParser() {}

    /** Reads {@code id} or {@code id{nbt}} from the reader into the old result, as 1.20.4 did. */
    public static LegacyItemResult parseForItem(HolderGetter<Item> items, StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        // The id class is ResourceLocation before 1.21.11 and Identifier after, so it is reached by reflection.
        Class<?> idClass = hostClass("net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation");
        Object id = call(() -> idClass.getMethod("read", StringReader.class).invoke(null, reader));
        Optional<Holder.Reference<Item>> item = items.get(itemKey(idClass, id));
        if (item.isEmpty()) {
            reader.setCursor(start);
            throw new SimpleCommandExceptionType(new LiteralMessage("Unknown item '" + id + "'")).createWithContext(reader);
        }
        // 1.20.4 read the NBT written after the id; 1.20.5 and newer read components in brackets there instead.
        CompoundTag nbt = reader.canRead() && reader.peek() == '{' ? readCompound(reader) : null;
        return new LegacyItemResult(item.get(), nbt);
    }

    /** 1.20.5 to 1.21's {@code parse}, whose result 26.1 replaced with an {@code ItemInput} of the same item and components. */
    public static LegacyItemResult parse(ItemParser parser, StringReader reader) throws CommandSyntaxException {
        ItemInput input = parser.parse(reader);
        return new LegacyItemResult(input.item(), input.components());
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Item> itemKey(Class<?> idClass, Object id) throws CommandSyntaxException {
        return (ResourceKey<Item>) call(() -> ResourceKey.class.getMethod("create", ResourceKey.class, idClass)
                .invoke(null, Registries.ITEM, id));
    }

    /** One SNBT compound from the reader: 1.21.5 added a static reader, and older hosts read it through an instance. */
    private static CompoundTag readCompound(StringReader reader) throws CommandSyntaxException {
        Class<?> parser = hostClass("net.minecraft.nbt.TagParser");
        return (CompoundTag) call(() -> {
            try {
                return parser.getMethod("parseCompoundAsArgument", StringReader.class).invoke(null, reader);
            } catch (NoSuchMethodException before1215) {
                Object instance = parser.getConstructor(StringReader.class).newInstance(reader);
                return parser.getMethod("readStruct").invoke(instance);
            }
        });
    }

    /** The first of these host classes that exists. */
    private static Class<?> hostClass(String... names) {
        ClassLoader loader = LegacyItemParser.class.getClassLoader();
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException absent) {
                // Try the name an older or newer host uses.
            }
        }
        throw new IllegalStateException("Retromod could not find " + String.join(" or ", names) + " to read an old item string");
    }

    private interface Reflective {
        Object run() throws ReflectiveOperationException;
    }

    /** Runs a reflective call, rethrowing a syntax error the host reported as itself. */
    private static Object call(Reflective call) throws CommandSyntaxException {
        try {
            return call.run();
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof CommandSyntaxException syntax) throw syntax;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Retromod could not read an old item string", failed.getCause());
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("Retromod could not read an old item string on this Minecraft version", missing);
        }
    }
}
