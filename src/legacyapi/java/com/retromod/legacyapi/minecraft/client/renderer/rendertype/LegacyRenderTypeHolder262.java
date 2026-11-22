/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyRenderTypeResolver;
import net.minecraft.client.renderer.rendertype.RenderType;

/** The old RenderType as a base class on 26.2, whose vertex format and mode types differ from 26.3's. */
public abstract class LegacyRenderTypeHolder262 extends LegacyRenderStateShard {

    // The old base sizes, kept for mods that read them through their own subclass.
    public static final int BIG_BUFFER_SIZE = 4194304;
    public static final int MEDIUM_BUFFER_SIZE = 262144;
    public static final int SMALL_BUFFER_SIZE = 786432;
    public static final int TRANSIENT_BUFFER_SIZE = 1536;

    /** The old constructor; a mod that builds its own instances gets objects 26.3 cannot draw with. */
    protected LegacyRenderTypeHolder262(String name, VertexFormat format, PrimitiveTopology mode, int bufferSize,
            boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
        super(name, setupState, clearState);
        LegacyLog.warnOnce("render type instance " + name, "The old render type " + name + " is a subclass the mod builds itself."
                + " 26.3 cannot draw with it, so geometry sent to it is not drawn.");
    }

    /** {@code RenderType.create(name, format, mode, bufferSize, crumbling, sort, state)}. */
    public static RenderType create(String name, VertexFormat format, PrimitiveTopology mode, int bufferSize,
            boolean affectsCrumbling, boolean sortOnUpload, LegacyCompositeState state) {
        return (RenderType) LegacyRenderTypeResolver.create(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload,
                state.describe());
    }

    /** {@code RenderType.create(name, format, mode, bufferSize, state)}, which neither crumbles nor sorts. */
    public static RenderType create(String name, VertexFormat format, PrimitiveTopology mode, int bufferSize,
            LegacyCompositeState state) {
        return create(name, format, mode, bufferSize, false, false, state);
    }
}
