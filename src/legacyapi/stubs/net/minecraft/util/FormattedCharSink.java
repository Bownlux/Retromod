// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.util;

import net.minecraft.network.chat.Style;

public interface FormattedCharSink {
    boolean accept(int index, Style style, int codePoint);
}
