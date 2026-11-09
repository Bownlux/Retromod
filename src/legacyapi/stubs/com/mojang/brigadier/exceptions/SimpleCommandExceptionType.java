// Compile-only stub of a Brigadier type the host ships, for src/legacyapi; never packaged.
package com.mojang.brigadier.exceptions;

import com.mojang.brigadier.ImmutableStringReader;
import com.mojang.brigadier.Message;

public class SimpleCommandExceptionType implements CommandExceptionType {
    public SimpleCommandExceptionType(Message message) { throw new UnsupportedOperationException(); }
    public CommandSyntaxException createWithContext(ImmutableStringReader reader) { throw new UnsupportedOperationException(); }
}
