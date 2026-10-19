package net.minecraft.client.gui;

/** A frame's GUI graphics; the name tells test frames apart. */
public final class GuiGraphicsExtractor {
    private final String name;
    public GuiGraphicsExtractor(String name) { this.name = name; }
    @Override public String toString() { return name; }
}
