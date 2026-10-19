package net.minecraft.server.packs.resources;

import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.IOException;

public interface ResourceProvider {
    BufferedReader openAsReader(Identifier id) throws IOException;
}
