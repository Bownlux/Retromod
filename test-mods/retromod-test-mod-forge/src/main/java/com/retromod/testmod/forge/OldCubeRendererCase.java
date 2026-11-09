/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.LoggerFactory;

/** An old entity renderer that draws a cube as GeckoLib 4 does, driven as 26.x drives one. Client only. */
final class OldCubeRendererCase {

    private static final String DRAW = "An old entity renderer's cube, drawn with the 1.20.1 whole-vertex call and builder chain,"
            + " reaches the frame with all 24 vertices (first client tick in a world)";
    private static final String MEMBERS = "An old entity renderer keeps super.render from renderFinal, its render offset and its"
            + " name tag rule on 26.x (first client tick in a world)";
    private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/pig/pig.png");

    private static CubeRenderer built;
    private static boolean reported;

    private OldCubeRendererCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(OldCubeRendererCase::onRegisterRenderers);
            MinecraftForge.EVENT_BUS.addListener(OldCubeRendererCase::onClientTick);
        } catch (Throwable t) {
            log("Old cube renderer checks register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // The renderer is built with a real context when the host builds its renderers.
    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityTypeKeys.keyEntity(), CubeRenderer::new);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reported || Minecraft.getInstance().player == null) return;
        reported = true;
        if (built == null) {
            log(DRAW, "fail: the host never built the renderer");
            return;
        }
        String[] results = drive(built, Minecraft.getInstance().player);
        log(DRAW, results[0]);
        log(MEMBERS, results[1]);
    }

    // The steps 26.x takes for an entity each frame: a state, the extract, then the submit into the frame's collector.
    private static String[] drive(CubeRenderer renderer, Entity entity) {
        try {
            Method create = method(renderer.getClass(), "createRenderState", 0);
            Method extract = method(renderer.getClass(), "extractRenderState", 3);
            Method submit = method(renderer.getClass(), "submit", 4);
            if (create == null || extract == null || submit == null) return new String[]{"fail: no render state or submit", "fail: not adapted"};
            Object state = create.invoke(renderer);
            CubeRenderer.namedEntity = null;
            extract.invoke(renderer, entity, state, 0.5f);

            // Each custom geometry the draw hands the frame is replayed at once into a consumer that counts vertices.
            Class<?> collectorType = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector");
            Class<?> geometryType = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer");
            AtomicInteger vertices = new AtomicInteger();
            Object counter = Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                    (proxy, called, args) -> {
                        if (called.getName().equals("addVertex") && args.length == 3) vertices.incrementAndGet();
                        if (called.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, called, args);
                        return called.getReturnType() == VertexConsumer.class ? proxy : null;
                    });
            Method replay = method(geometryType, "render", 2);
            Object collector = Proxy.newProxyInstance(collectorType.getClassLoader(), new Class<?>[]{collectorType}, (proxy, called, args) -> {
                if (args != null) {
                    for (Object arg : args) {
                        if (geometryType.isInstance(arg)) replay.invoke(arg, new PoseStack().last(), counter);
                    }
                }
                return called.getReturnType() == boolean.class ? false : null;
            });
            submit.invoke(renderer, state, new PoseStack(), collector, camera());
            String draw = renderer.cubes != 1 ? "fail: the cube was drawn " + renderer.cubes + " times"
                    : vertices.get() == 24 ? "success" : "fail: " + vertices.get() + " vertices reached the frame";

            // super.render ran from renderFinal; the host reads the offset and the name rule through its own methods.
            CubeRenderer.namedEntity = entity;
            Object offset = method(renderer.getClass(), "getRenderOffset", 1, state).invoke(renderer, state);
            // The host's own shouldShowName(entity, distance) is protected, so it is looked up as declared.
            Method distanceRule = declared(renderer.getClass(), "shouldShowName", 2);
            if (distanceRule == null) return new String[]{draw, "fail: no shouldShowName(entity, distance) of its own"};
            distanceRule.setAccessible(true);
            Object named = distanceRule.invoke(renderer, entity, 4.0);
            CubeRenderer.namedEntity = null;
            String members = renderer.finals != 1 ? "fail: renderFinal finished " + renderer.finals + " times"
                    : !(offset instanceof Vec3 moved) || moved.y < 0.24 || moved.y > 0.26 ? "fail: render offset " + offset
                    : !Boolean.TRUE.equals(named) ? "fail: the old name rule was not asked" : "success";
            return new String[]{draw, members};
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String failure = "fail: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            return new String[]{failure, failure};
        }
    }

    private static Object camera() {
        try {
            return Class.forName("net.minecraft.client.renderer.state.level.CameraRenderState").getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, int parameters, Object... firstArgs) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameters) continue;
            if (firstArgs.length == 0 || method.getParameterTypes()[0].isInstance(firstArgs[0])) return method;
        }
        return null;
    }

    private static Method declared(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters && method.getParameterTypes()[1] == double.class) {
                return method;
            }
        }
        return null;
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** As GeckoLib 4's GeoEntityRenderer: render calls renderFinal, which draws the cube and asks for the base draw. */
    static final class CubeRenderer extends EntityRenderer<Entity> {
        static Entity namedEntity;
        int cubes;
        int finals;

        CubeRenderer(EntityRendererProvider.Context context) {
            super(context);
            built = this;
        }

        @Override
        public void render(Entity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
            renderFinal(entity, yaw, partialTick, poseStack, buffers, light);
        }

        public void renderFinal(Entity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
            cube(poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light);
            cubes++;
            super.render(entity, yaw, partialTick, poseStack, buffers, light);
            finals++;
        }

        @Override
        public Vec3 getRenderOffset(Entity entity, float partialTick) {
            return new Vec3(0, 0.25, 0);
        }

        @Override
        protected boolean shouldShowName(Entity entity) {
            return entity == namedEntity;
        }

        @Override
        public ResourceLocation getTextureLocation(Entity entity) {
            return TEXTURE;
        }

        // Five faces with the whole-vertex call on a position the mod moved itself, the top through the builder chain.
        private static void cube(PoseStack poseStack, VertexConsumer buffer, int light) {
            Matrix4f pose = poseStack.last().pose();
            Matrix3f normal = poseStack.last().normal();
            float[][][] faces = {
                    {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}, {0, 0, -1}},
                    {{0, 0, 1}, {0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}},
                    {{0, 0, 0}, {0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {-1, 0, 0}},
                    {{1, 0, 0}, {1, 0, 1}, {1, 1, 1}, {1, 1, 0}, {1, 0, 0}},
                    {{0, 0, 0}, {0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, -1, 0}}};
            for (float[][] face : faces) {
                for (int corner = 0; corner < 4; corner++) {
                    Vector4f at = pose.transform(new Vector4f(face[corner][0], face[corner][1], face[corner][2], 1f));
                    buffer.vertex(at.x(), at.y(), at.z(), 1f, 1f, 1f, 1f, corner / 2, corner % 2, OverlayTexture.NO_OVERLAY, light,
                            face[4][0], face[4][1], face[4][2]);
                }
            }
            float[][] top = {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
            for (float[] corner : top) {
                buffer.vertex(pose, corner[0], corner[1], corner[2]).color(255, 255, 255, 255).uv(corner[0], corner[2])
                        .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal, 0f, 1f, 0f).endVertex();
            }
        }
    }
}
