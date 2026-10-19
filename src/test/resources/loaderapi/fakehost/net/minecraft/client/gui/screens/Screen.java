package net.minecraft.client.gui.screens;

public class Screen {
    private final String title;
    public Screen(String title) { this.title = title; }
    @Override public String toString() { return title; }
}
