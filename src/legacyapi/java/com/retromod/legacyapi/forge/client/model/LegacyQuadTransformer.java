/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Forge 1.20.1's IQuadTransformer, which edits a quad's int[] vertex data in place. */
@FunctionalInterface
public interface LegacyQuadTransformer {

    // Forge 1.20.1 read these from DefaultVertexFormat.BLOCK. UV1 is not in that format, so it was -1.
    int STRIDE = LegacyQuadVertices.STRIDE;
    int POSITION = LegacyQuadVertices.POSITION;
    int COLOR = LegacyQuadVertices.COLOR;
    int UV0 = LegacyQuadVertices.UV0;
    int UV1 = -1;
    int UV2 = LegacyQuadVertices.UV2;
    int NORMAL = LegacyQuadVertices.NORMAL;

    /** Edits the array {@code LegacyQuadVertices.getVertices(quad)} returns; a later sync turns it into a quad. */
    void processInPlace(BakedQuad quad);

    /** Edits each quad, then puts the rebuilt quads into the list where the list allows it. */
    default void processInPlace(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) processInPlace(quad);
        LegacyQuadVertices.syncInPlace(quads);
    }

    /** A changed copy of the quad; the quad itself stays as it was. */
    default BakedQuad process(BakedQuad quad) {
        BakedQuad copy = LegacyQuadVertices.copy(quad);
        processInPlace(copy);
        return LegacyQuadVertices.sync(copy);
    }

    /** Changed copies of the quads, in a list that cannot be changed, as Forge 1.20.1 returned. */
    default List<BakedQuad> process(List<BakedQuad> quads) {
        List<BakedQuad> processed = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) processed.add(process(quad));
        return Collections.unmodifiableList(processed);
    }

    /** This transformer, then {@code other}, on the same array. */
    default LegacyQuadTransformer andThen(LegacyQuadTransformer other) {
        return quad -> {
            processInPlace(quad);
            other.processInPlace(quad);
        };
    }
}
