/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** The {@code NbtIo} file members that took a {@code File} or no size limit, which later versions moved to paths and size limits. */
public final class LegacyNbtFiles {

    private LegacyNbtFiles() {}

    /** {@code NbtIo.read(file)}: null when the file does not exist, as before. */
    public static CompoundTag read(File file) throws IOException {
        return file.exists() ? NbtIo.read(file.toPath()) : null;
    }

    /** {@code NbtIo.write(tag, file)}. */
    public static void write(CompoundTag tag, File file) throws IOException {
        NbtIo.write(tag, file.toPath());
    }

    /** {@code NbtIo.readCompressed(file)}, with no size limit, as before. */
    public static CompoundTag readCompressed(File file) throws IOException {
        return NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap());
    }

    /** {@code NbtIo.readCompressed(stream)}, with no size limit, as before. */
    public static CompoundTag readCompressed(InputStream stream) throws IOException {
        return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
    }

    /** {@code NbtIo.writeCompressed(tag, file)}. */
    public static void writeCompressed(CompoundTag tag, File file) throws IOException {
        NbtIo.writeCompressed(tag, file.toPath());
    }
}
