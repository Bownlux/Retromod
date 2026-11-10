/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.packs.metadata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;

import java.util.Optional;

/** The public pack metadata codec of 1.21.1, which hosts from 1.21.11 replaced with private and per-pack-type ones. */
public final class LegacyPackMetadataCodec {

    /** The host refuses a pack format below this one next to a range of formats. */
    private static final int OLDEST_RANGED_FORMAT = 15;

    /** A {@code pack} section in either shape: 1.21.1's pack_format and supported_formats, or the newer min_format and max_format. */
    private record Fields(Component description, Optional<Integer> packFormat, Optional<InclusiveRange<Integer>> supportedFormats,
            Optional<PackFormat> minFormat, Optional<PackFormat> maxFormat) {}

    private static final Codec<Fields> FIELDS = RecordCodecBuilder.create(instance -> instance.group(
            ComponentSerialization.CODEC.fieldOf("description").forGetter(Fields::description),
            Codec.INT.optionalFieldOf("pack_format").forGetter(Fields::packFormat),
            // As in 1.21.1, a malformed supported_formats is ignored rather than failing the whole section.
            InclusiveRange.INT.lenientOptionalFieldOf("supported_formats").forGetter(Fields::supportedFormats),
            PackFormat.BOTTOM_CODEC.optionalFieldOf("min_format").forGetter(Fields::minFormat),
            PackFormat.TOP_CODEC.optionalFieldOf("max_format").forGetter(Fields::maxFormat))
            .apply(instance, Fields::new));

    private static final Codec<PackMetadataSection> CODEC =
            FIELDS.flatXmap(LegacyPackMetadataCodec::read, section -> DataResult.success(write(section)));

    private LegacyPackMetadataCodec() {}

    /** What an old read of {@code PackMetadataSection.CODEC} now gets. */
    public static Codec<PackMetadataSection> codec() {
        return CODEC;
    }

    /** A section from either shape; an old one names major formats only, so its range takes every minor format of them. */
    private static DataResult<PackMetadataSection> read(Fields fields) {
        if (fields.minFormat().isPresent() != fields.maxFormat().isPresent()) {
            return DataResult.error(() -> "Pack metadata needs both min_format and max_format, or neither");
        }
        if (fields.minFormat().isPresent()) {
            PackFormat min = fields.minFormat().get();
            PackFormat max = fields.maxFormat().get();
            if (min.compareTo(max) > 0) return DataResult.error(() -> "Pack metadata has min_format above max_format");
            return DataResult.success(new PackMetadataSection(fields.description(), new InclusiveRange<>(min, max)));
        }
        if (fields.packFormat().isEmpty() && fields.supportedFormats().isEmpty()) {
            return DataResult.error(() -> "Pack metadata has no pack_format");
        }
        // The range spans the format and the supported formats, as the old constructors of the section do.
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        if (fields.packFormat().isPresent()) {
            low = fields.packFormat().get();
            high = low;
        }
        if (fields.supportedFormats().isPresent()) {
            low = Math.min(low, fields.supportedFormats().get().minInclusive());
            high = Math.max(high, fields.supportedFormats().get().maxInclusive());
        }
        InclusiveRange<PackFormat> range = new InclusiveRange<>(PackFormat.of(low, 0), PackFormat.of(high, Integer.MAX_VALUE));
        return DataResult.success(new PackMetadataSection(fields.description(), range));
    }

    /** The section's fields as the host reads them for either pack type. */
    private static Fields write(PackMetadataSection section) {
        PackFormat min = section.supportedFormats().minInclusive();
        PackFormat max = section.supportedFormats().maxInclusive();
        // The host takes such an old format only on its own, as the oldest packs wrote it.
        if (min.major() < OLDEST_RANGED_FORMAT) {
            return new Fields(section.description(), Optional.of(min.major()), Optional.empty(), Optional.empty(), Optional.empty());
        }
        // A range that starts after both pack types gained minor formats takes the new fields alone.
        int lastWithoutMinor = Math.max(PackFormat.lastPreMinorVersion(PackType.CLIENT_RESOURCES),
                PackFormat.lastPreMinorVersion(PackType.SERVER_DATA));
        if (min.major() > lastWithoutMinor) {
            return new Fields(section.description(), Optional.empty(), Optional.empty(), Optional.of(min), Optional.of(max));
        }
        // An older start also needs the old fields, matching it. Between the two pack types' cut-offs only a data
        // pack wants them and a resource pack refuses them, so such a resource pack falls back to its description.
        InclusiveRange<Integer> majors = new InclusiveRange<>(min.major(), max.major());
        return new Fields(section.description(), Optional.of(min.major()), Optional.of(majors), Optional.of(min), Optional.of(max));
    }
}
