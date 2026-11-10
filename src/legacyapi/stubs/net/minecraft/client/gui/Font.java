// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public class Font {
    // Set in the constructor, so javac reads the field instead of inlining a constant.
    public final int lineHeight;

    public Font() { this.lineHeight = 0; }

    public int width(String text) { throw new UnsupportedOperationException(); }
    public int width(FormattedCharSequence text) { throw new UnsupportedOperationException(); }
    public List<FormattedCharSequence> split(FormattedText text, int width) { throw new UnsupportedOperationException(); }
}
