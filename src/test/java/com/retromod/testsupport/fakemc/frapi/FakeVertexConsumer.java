/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for VertexConsumer. */
public interface FakeVertexConsumer {
    FakeVertexConsumer addVertex(float x, float y, float z);

    FakeVertexConsumer setColor(int red, int green, int blue, int alpha);

    FakeVertexConsumer setColor(int argb);

    FakeVertexConsumer setUv(float u, float v);

    FakeVertexConsumer setUv1(int u, int v);

    FakeVertexConsumer setUv2(int u, int v);

    FakeVertexConsumer setUv3(float u, float v);

    FakeVertexConsumer setNormal(float x, float y, float z);

    FakeVertexConsumer setLineWidth(float width);
}
