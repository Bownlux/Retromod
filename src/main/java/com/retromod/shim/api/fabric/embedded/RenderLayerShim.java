package com.retromod.shim.api.fabric.embedded;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
public class RenderLayerShim {
    public static void putBlock(Object block, Object renderLayer) {
        try {
            Class<?> map = Class.forName("net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap");
            Object inst = map.getField("INSTANCE").get(null);
            Method m = LegacyPublicMethods.method(inst.getClass(), "putBlock", Class.forName("net.minecraft.block.Block"), Class.forName("net.minecraft.client.render.RenderLayer"));
            m.invoke(inst, block, renderLayer);
        } catch (Exception e) { }
    }
}
