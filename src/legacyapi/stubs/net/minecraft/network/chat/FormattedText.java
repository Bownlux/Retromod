// Compile-only stub of a 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.network.chat;

public interface FormattedText {
    static FormattedText of(String text, Style style) { throw new UnsupportedOperationException(); }

    <T> java.util.Optional<T> visit(StyledContentConsumer<T> consumer, Style style);

    interface StyledContentConsumer<T> {
        java.util.Optional<T> accept(Style style, String text);
    }
}
