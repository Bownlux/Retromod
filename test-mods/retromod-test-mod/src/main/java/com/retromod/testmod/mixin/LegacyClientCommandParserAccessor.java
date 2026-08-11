/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.command.CommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Load-time regression for the command parser method removed after Minecraft 1.21.5. */
@Pseudo
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener", remap = false)
public interface LegacyClientCommandParserAccessor {

    @Invoker(value = "parseCommand", remap = false)
    ParseResults<CommandSource> retromod$invokeParseCommand(String command);
}
