// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.client;

import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.LevelRenderState;

public class FramePassManager {
    public FramePassManager() {}

    // Forge 26.3 adds the frame's LevelRenderState to extracts; an implementation declares both shapes.
    public interface PassDefinition {
        void extracts(LevelTargetBundle targets, FramePass pass);

        void executes(LevelRenderState state);
    }
}
