/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An old entity renderer on 26.x keeps its base call from any method, its render offset and its name tag rule. */
class LegacyEntityRendererBaseMembersTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Drawing on 26.1: super.render from renderFinal asks for the base draw, and offsets and name tags follow the old overrides")
    void drawingRendererKeepsItsOldBaseMembers(@TempDir Path dir) throws Throwable {
        // A 26.1 host, which still has MultiBufferSource, so the helper takes the host's own.
        Map<String, byte[]> mod = BridgeHarness.transform("26.1", t -> {
            RebuiltApiClasses.registerTree(t, LegacyEntityRendererAdapter.HELPER, HostBuffers.names(name -> new org.objectweb.asm.tree.ClassNode()));
            withAnyRedirect(t);
        }, oldMod(dir), newHost(dir));
        for (byte[] bytes : mod.values()) {
            assertDoesNotThrow(() -> new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0));
        }
        assertTrue(BridgeHarness.calls(mod.get("mod/Geo"), "renderFinal").contains(LegacyEntityRendererAdapter.HELPER + ".superRender("
                + "Ljava/lang/Object;Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V"), "renderFinal's super.render goes to the helper");

        BridgeHarness.Run run = new BridgeHarness.Run(newHostClasses, mod);
        Object geo = run.loadClass("mod.Geo").getConstructor().newInstance();
        Object offset = run.loadClass("mod.Offset").getConstructor().newInstance();
        Object entity = entity(run, true, true);
        Object state = state(run, geo, entity);

        // The old render's helper calls this.getRenderOffset(entity, partialTick), which the class now declares as the old base did.
        assertEquals("0.0,0.0,0.0", vec(invoke(geo, "leash", entity, 0.5f)));
        assertEquals("1.0,2.0,3.0", vec(invoke(offset, "leash", entity, 0.5f)));
        // The host asks for the offset with the state: the host's own, plus the old override's where the class made one.
        assertEquals("0.0,0.0,7.0", vec(invoke(geo, "getRenderOffset", state)));
        assertEquals("1.0,2.0,10.0", vec(invoke(offset, "getRenderOffset", state(run, offset, entity))));
        assertFalse(declares(mod.get("mod/Geo"), "getRenderOffset",
                "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/world/phys/Vec3;"),
                "a renderer that never overrode the offset keeps the host's");
        // A renderer that never overrode the name rule keeps 26.x's, which shows this visible but unnamed entity.
        assertFalse(declares(mod.get("mod/Offset"), "shouldShowName", "(Lnet/minecraft/world/entity/Entity;D)Z"));
        assertEquals(true, invoke(offset, "shouldShowName", entity(run, true, false), 4.0));
        // The host asks whether to show the name with a distance; the old rule answers, its super call 1.20.1's base.
        assertEquals(true, invoke(geo, "shouldShowName", entity, 4.0));
        assertEquals(false, invoke(geo, "shouldShowName", entity(run, false, false), 4.0));
        assertEquals(true, invoke(geo, "shouldShowName", entity(run, false, true), 4.0), "the mod's own rule shows any custom name");
        assertEquals(1, invoke(geo, "renderTwice", entity, 0.5f), "renderFinal ran with its base call routed");
    }

    @Test
    @DisplayName("Not drawing: the class still loads and its old base calls answer as nothing draws")
    void loadOnlyRendererDropsItsBaseCalls(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> mod = BridgeHarness.transform("26.2", LegacyEntityRendererBaseMembersTest::withAnyRedirect, oldMod(dir), newHost(dir));
        assertFalse(BridgeHarness.calls(mod.get("mod/Geo"), "renderFinal").stream().anyMatch(c -> c.contains(".render(")),
                "renderFinal's super.render is dropped");
        BridgeHarness.Run run = new BridgeHarness.Run(newHostClasses, mod);
        Object geo = run.loadClass("mod.Geo").getConstructor().newInstance();
        Object entity = entity(run, true, true);
        assertEquals("0.0,0.0,0.0", vec(invoke(geo, "leash", entity, 0.5f)));
        assertEquals(1, invoke(geo, "renderTwice", entity, 0.5f));
        assertEquals(true, invoke(geo, "shouldShowName", entity), "only the base half of the old rule is gone");
    }

    private Map<String, byte[]> newHostClasses;

    // A running game always registers redirects; with none the transformer leaves a class untouched.
    private static void withAnyRedirect(RetromodTransformer t) {
        t.registerClassRedirect("test/Unused", "test/UnusedToo");
    }

    private static Map<String, byte[]> oldMod(Path dir) throws Exception {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public boolean shouldShowName() { return false; }
                    public boolean hasCustomName() { return false; }
                }""");
        s.put("net/minecraft/world/phys/Vec3.java", """
                package net.minecraft.world.phys;
                public class Vec3 {
                    public static final Vec3 ZERO = new Vec3(0, 0, 0);
                    public final double x, y, z;
                    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
                }""");
        s.put("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}");
        s.put("net/minecraft/client/renderer/MultiBufferSource.java", "package net.minecraft.client.renderer; public interface MultiBufferSource {}");
        s.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.phys.Vec3;
                public abstract class EntityRenderer<T extends Entity> {
                    public void render(T entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {}
                    public Vec3 getRenderOffset(T entity, float partialTick) { return Vec3.ZERO; }
                    protected boolean shouldShowName(T entity) { return entity.shouldShowName() && entity.hasCustomName(); }
                }""");
        // As GeckoLib 4's GeoEntityRenderer: the base draw from renderFinal, the offset read in its own code, its own name rule.
        s.put("mod/Geo.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.client.renderer.entity.EntityRenderer;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.phys.Vec3;
                public class Geo extends EntityRenderer<Entity> {
                    private int finals;
                    public void render(Entity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
                        renderFinal(entity, yaw, partialTick, pose, buffers, light);
                    }
                    public void renderFinal(Entity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
                        super.render(entity, yaw, partialTick, pose, buffers, light);
                        finals++;
                    }
                    public int renderTwice(Entity entity, float partialTick) {
                        renderFinal(entity, 0f, partialTick, null, null, 0);
                        return finals;
                    }
                    public Vec3 leash(Entity entity, float partialTick) { return getRenderOffset(entity, partialTick); }
                    public boolean shouldShowName(Entity entity) { return super.shouldShowName(entity) || entity.hasCustomName(); }
                }""");
        // A renderer that overrides the old offset and leaves the name rule to the base.
        s.put("mod/Offset.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.client.renderer.entity.EntityRenderer;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.phys.Vec3;
                public class Offset extends EntityRenderer<Entity> {
                    public void render(Entity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {}
                    public Vec3 getRenderOffset(Entity entity, float partialTick) { return new Vec3(1, 2, 3); }
                    public Vec3 leash(Entity entity, float partialTick) { return getRenderOffset(entity, partialTick); }
                }""");
        return BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), s), "mod/");
    }

    private Map<String, byte[]> newHost(Path dir) throws Exception {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public float yRotO;
                    private final boolean visible, named;
                    public Entity(boolean visible, boolean named) { this.visible = visible; this.named = named; }
                    public float getYRot() { return 0f; }
                    public boolean shouldShowName() { return visible; }
                    public boolean hasCustomName() { return named; }
                }""");
        s.put("net/minecraft/world/phys/Vec3.java", """
                package net.minecraft.world.phys;
                public class Vec3 {
                    public static final Vec3 ZERO = new Vec3(0, 0, 0);
                    public final double x, y, z;
                    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
                    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
                    public String toString() { return x + "," + y + "," + z; }
                }""");
        s.put("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}");
        s.put("net/minecraft/client/renderer/MultiBufferSource.java", "package net.minecraft.client.renderer; public interface MultiBufferSource {}");
        s.put("net/minecraft/client/renderer/SubmitNodeCollector.java", "package net.minecraft.client.renderer; public interface SubmitNodeCollector {}");
        s.put("net/minecraft/client/renderer/state/level/CameraRenderState.java",
                "package net.minecraft.client.renderer.state.level; public class CameraRenderState {}");
        s.put("net/minecraft/client/renderer/entity/state/EntityRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class EntityRenderState {
                    public int lightCoords;
                    public net.minecraft.world.phys.Vec3 passengerOffset = new net.minecraft.world.phys.Vec3(0, 0, 7);
                }""");
        s.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.renderer.SubmitNodeCollector;
                import net.minecraft.client.renderer.entity.state.EntityRenderState;
                import net.minecraft.client.renderer.state.level.CameraRenderState;
                import net.minecraft.world.entity.Entity;
                import net.minecraft.world.phys.Vec3;
                public abstract class EntityRenderer<T extends Entity, S extends EntityRenderState> {
                    public abstract S createRenderState();
                    public void extractRenderState(T entity, S state, float partialTick) {}
                    public void submit(S state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {}
                    public Vec3 getRenderOffset(S state) { return state.passengerOffset != null ? state.passengerOffset : Vec3.ZERO; }
                    protected boolean shouldShowName(T entity, double distanceToCameraSq) { return entity.shouldShowName(); }
                }""");
        newHostClasses = BridgeHarness.compile(dir.resolve("new"), s);
        return newHostClasses;
    }

    private static Object entity(BridgeHarness.Run run, boolean visible, boolean named) throws Exception {
        return run.loadClass("net.minecraft.world.entity.Entity").getConstructor(boolean.class, boolean.class).newInstance(visible, named);
    }

    // The state the host builds: the renderer's own, filled by its extractRenderState.
    private static Object state(BridgeHarness.Run run, Object renderer, Object entity) throws Exception {
        Object state = invoke(renderer, "createRenderState");
        Method extract = renderer.getClass().getMethod("extractRenderState", run.loadClass("net.minecraft.world.entity.Entity"),
                run.loadClass("net.minecraft.client.renderer.entity.state.EntityRenderState"), float.class);
        extract.invoke(renderer, entity, state, 0.5f);
        return state;
    }

    private static boolean declares(byte[] bytes, String name, String desc) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static String vec(Object vec3) {
        return vec3.toString();
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length && matches(method, args)) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
        }
        throw new AssertionError("no method " + name + List.of(args));
    }

    private static boolean matches(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            Class<?> type = types[i];
            if (type == float.class) type = Float.class;
            if (type == double.class) type = Double.class;
            if (!type.isInstance(args[i])) return false;
        }
        return true;
    }
}
