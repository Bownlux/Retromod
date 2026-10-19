package net.minecraft.client.gui.components.debug;

import net.minecraft.resources.Identifier;

import java.util.Collection;

public interface DebugScreenDisplayer {
    void addPriorityLine(String line);

    void addLine(String line);

    void addToGroup(Identifier group, Collection<String> lines);

    void addToGroup(Identifier group, String line);
}
