/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

/** The pose stack old GUI code gets from {@code pose()}, copying each change onto the frame's 2D stack. */
public final class LegacyGuiPoseStack extends PoseStack {

    // The frame's 2D stack holds 16 matrices, so a copy deeper than that only holds pushes popped through the 2D stack.
    static final int MAX_DEPTH = 16;

    private final Matrix3x2fStack gui;
    private int depth;

    LegacyGuiPoseStack(Matrix3x2fStack gui) {
        this.gui = gui;
    }

    @Override
    public void pushPose() {
        // Code migrated to the 2D stack can pop what was pushed here, which this copy never sees, so it starts over.
        if (depth >= MAX_DEPTH) {
            while (!isEmpty()) super.popPose();
            depth = 0;
        }
        super.pushPose();
        depth++;
        gui.pushMatrix();
    }

    @Override
    public void popPose() {
        // A fresh copy for this frame stack starts empty, though the push it pairs with happened.
        if (depth > 0) {
            super.popPose();
            depth--;
        }
        gui.popMatrix();
    }

    // translate(double, double, double) and translate(Vec3) reach this one through the host's own calls.
    @Override
    public void translate(float x, float y, float z) {
        super.translate(x, y, z);
        // The 2D stack has no depth: 26.x stacks GUI layers in the order they are drawn.
        gui.translate(x, y);
    }

    @Override
    public void scale(float x, float y, float z) {
        super.scale(x, y, z);
        gui.scale(x, y);
    }

    /** The quaternion rotation as 26.1 and 26.2 name it. */
    public void mulPose(Quaternionfc rotation) {
        rotate(rotation);
    }

    /** The quaternion rotation as 26.3 names it. */
    public void rotate(Quaternionfc rotation) {
        last().rotate(rotation);
        gui.rotate(screenAngle(rotation));
    }

    @Override
    public void rotateAround(Quaternionfc rotation, float x, float y, float z) {
        super.rotateAround(rotation, x, y, z);
        gui.translate(x, y);
        gui.rotate(screenAngle(rotation));
        gui.translate(-x, -y);
    }

    @Override
    public void mulPose(Matrix4fc pose) {
        super.mulPose(pose);
        gui.mul(new Matrix3x2f(pose.m00(), pose.m01(), pose.m10(), pose.m11(), pose.m30(), pose.m31()));
    }

    @Override
    public void setIdentity() {
        super.setIdentity();
        gui.identity();
    }

    /** The turn a rotation gives the screen plane. A tilt out of the plane has no 2D form and is dropped. */
    static float screenAngle(Quaternionfc q) {
        return (float) Math.atan2(2 * (q.w() * q.z() + q.x() * q.y()), 1 - 2 * (q.y() * q.y() + q.z() * q.z()));
    }
}
