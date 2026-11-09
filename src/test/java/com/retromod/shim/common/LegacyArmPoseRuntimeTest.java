/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyArmPoseBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 mod's arm poses, translated and run on stand-ins for Forge 26.2's render-state arm poses. */
public class LegacyArmPoseRuntimeTest {

    private static final String POSE = "net.minecraft.client.model.HumanoidModel$ArmPose";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void forge() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Each create reaches Forge 26.2's create with the offhand flag set from twoHanded")
    void createsThroughTheHostCreate() throws Throwable {
        BridgeHarness.Run run = run();
        run.loadClass("mod.Poses").getField("FLIP").get(null);

        List<?> created = created(run);
        assertEquals(List.of("FLIP false false", "BOTH true true", "BROKEN false false", "THROWS false false"),
                created.stream().map(LegacyArmPoseRuntimeTest::describe).toList(),
                "a two-handed pose also covers the other arm, as the bow does");
    }

    @Test
    @DisplayName("The host's render-state call poses the arm from the one entity whose position and age made the state")
    void posesFromTheEntityTheStateCameFrom() throws Throwable {
        BridgeHarness.Run run = run();
        run.loadClass("mod.Poses").getField("FLIP").get(null);
        Object level = newInstance(run, "net.minecraft.client.multiplayer.ClientLevel");
        Object player = type(run, "player");
        // A pig on the spot, a player standing closer to it, and a player flying four blocks a tick through it.
        addEntity(run, level, type(run, "pig"), "9", 9, 100, 10, 64, 10, 10, 64, 10);
        addEntity(run, level, player, "1", 1, 100, 10.2, 64, 10, 10.2, 64, 10);
        addEntity(run, level, player, "5", 5, 100, 12, 64, 10, 8, 64, 10);
        setLevel(run, level);

        Object model = newInstance(run, "net.minecraft.client.model.HumanoidModel");
        apply(run, pose(run, 0), model, state(run, player, 10, 64, 10, 100.5f), "RIGHT");

        assertEquals(5.25f, part(model, "rightArm"), "the flier drew here, halfway through its tick; the frame time"
                + " is read through the delta tracker");
        assertEquals(0f, part(model, "leftArm"));
        assertEquals(1, run.loadClass("mod.FlipTransformer").getField("calls").get(null));
    }

    @Test
    @DisplayName("A player's state names its entity by id, even where two players fit its position")
    void playerStateNamesItsEntity() throws Throwable {
        BridgeHarness.Run run = run();
        run.loadClass("mod.Poses").getField("FLIP").get(null);
        Object level = newInstance(run, "net.minecraft.client.multiplayer.ClientLevel");
        Object player = type(run, "player");
        addEntity(run, level, player, "3", 3, 40, 0, 64, 0, 0, 64, 0);
        addEntity(run, level, player, "7", 7, 40, 0, 64, 0, 0, 64, 0);
        setLevel(run, level);

        Object model = newInstance(run, "net.minecraft.client.model.HumanoidModel");
        Object state = state(run, "net.minecraft.client.renderer.entity.state.AvatarRenderState", player, 0, 64, 0, 40f);
        state.getClass().getField("id").setInt(state, 7);
        apply(run, pose(run, 0), model, state, "LEFT");

        assertEquals(7.25f, part(model, "leftArm"));
    }

    @Test
    @DisplayName("A state two entities fit equally, one drawn outside the world, or no level, leaves the arm alone")
    void noSingleHolderNoCall() throws Throwable {
        BridgeHarness.Run run = run();
        run.loadClass("mod.Poses").getField("FLIP").get(null);
        Object model = newInstance(run, "net.minecraft.client.model.HumanoidModel");
        Object player = type(run, "player");
        apply(run, pose(run, 0), model, state(run, player, 0, 64, 0, 40f), "LEFT");

        Object level = newInstance(run, "net.minecraft.client.multiplayer.ClientLevel");
        // Two mobs pressed into one block cannot be told apart.
        addEntity(run, level, player, "3", 3, 40, 0, 64, 0, 0, 64, 0);
        addEntity(run, level, player, "4", 4, 40, 0, 64, 0, 0, 64, 0);
        setLevel(run, level);
        apply(run, pose(run, 0), model, state(run, player, 0, 64, 0, 40f), "LEFT");
        // A preview figure beside the player, as a mod's screen draws one, matches no entity.
        apply(run, pose(run, 0), model, state(run, player, 0.5, 64, 0, 40f), "LEFT");

        assertEquals(0, run.loadClass("mod.FlipTransformer").getField("calls").get(null));
        assertEquals(0f, part(model, "leftArm"));
    }

    @Test
    @DisplayName("A transformer that reaches a removed API, or throws, stops posing after one failure")
    void failingTransformerStopsThatPose() throws Throwable {
        BridgeHarness.Run run = run();
        run.loadClass("mod.Poses").getField("FLIP").get(null);
        Object level = newInstance(run, "net.minecraft.client.multiplayer.ClientLevel");
        Object player = type(run, "player");
        addEntity(run, level, player, "2", 2, 10, 0, 64, 0, 0, 64, 0);
        setLevel(run, level);
        Object model = newInstance(run, "net.minecraft.client.model.HumanoidModel");

        for (int frame = 0; frame < 2; frame++) {
            apply(run, pose(run, 2), model, state(run, player, 0, 64, 0, 10f), "RIGHT");
            apply(run, pose(run, 3), model, state(run, player, 0, 64, 0, 10f), "RIGHT");
        }
        assertEquals(1, run.loadClass("mod.BrokenTransformer").getField("calls").get(null),
                "the second frame skips the transformer that met a missing method");
        assertEquals(1, run.loadClass("mod.ThrowingTransformer").getField("calls").get(null),
                "and the one that threw");
    }

    @Test
    @DisplayName("A host whose enum extender did not run answers the plain item pose")
    void unextendedHostGivesTheItemPose() throws Throwable {
        BridgeHarness.Run run = run();
        setStatic(run, POSE, "refuse", true);
        Object flip = run.loadClass("mod.Poses").getField("FLIP").get(null);
        assertSame(run.loadClass(POSE).getField("ITEM").get(null), flip);
    }

    /** The old mod's classes, compiled against Forge 1.20.1's shapes, for the path parity test. */
    public static Map<String, byte[]> oldModClasses(Path dir) throws Exception {
        return BridgeHarness.only(BridgeHarness.compile(dir, oldSources()), "mod/");
    }

    /** Translates the old mod for Forge 26.2 and loads it over the new host's stand-ins. */
    private BridgeHarness.Run run() throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldSources());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), hostSources());
        Map<String, byte[]> mod = BridgeHarness.transform("26.2", t -> {
            LegacyArmPoseBridge.registerForge(t);
            LegacyFrameTimeBridge.register(t);
        }, BridgeHarness.only(old, "mod/"));
        return new BridgeHarness.Run(host, mod);
    }

    /** The old mod: Farmer's Delight's skillet pose shape, a lambda pose, and one whose transformer calls a removed method. */
    public static Map<String, String> oldModSources() {
        Map<String, String> sources = new HashMap<>();
        sources.put("mod/Poses.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                public final class Poses {
                    public static final HumanoidModel.ArmPose FLIP = HumanoidModel.ArmPose.create("FLIP", false, new FlipTransformer());
                    public static final HumanoidModel.ArmPose BOTH = HumanoidModel.ArmPose.create("BOTH", true,
                            (model, entity, arm) -> model.leftArm.xRot = -1);
                    public static final HumanoidModel.ArmPose BROKEN = HumanoidModel.ArmPose.create("BROKEN", false, new BrokenTransformer());
                    public static final HumanoidModel.ArmPose THROWS = HumanoidModel.ArmPose.create("THROWS", false, new ThrowingTransformer());
                }""");
        sources.put("mod/ThrowingTransformer.java", """
                package mod;
                public final class ThrowingTransformer implements net.minecraftforge.client.IArmPoseTransformer {
                    public static int calls;
                    public void applyTransform(net.minecraft.client.model.HumanoidModel<?> model,
                            net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.entity.HumanoidArm arm) {
                        calls++;
                        throw new IllegalArgumentException("a bug in the mod's own pose code");
                    }
                }""");
        sources.put("mod/FlipTransformer.java", """
                package mod;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.client.model.geom.ModelPart;
                import net.minecraft.world.entity.HumanoidArm;
                import net.minecraft.world.entity.LivingEntity;
                public final class FlipTransformer implements net.minecraftforge.client.IArmPoseTransformer {
                    public static int calls;
                    public void applyTransform(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
                        calls++;
                        ModelPart part = arm == HumanoidArm.LEFT ? model.leftArm : model.rightArm;
                        part.xRot = Float.parseFloat(entity.tag) + Minecraft.getInstance().getFrameTime();
                    }
                }""");
        sources.put("mod/BrokenTransformer.java", """
                package mod;
                public final class BrokenTransformer implements net.minecraftforge.client.IArmPoseTransformer {
                    public static int calls;
                    public void applyTransform(net.minecraft.client.model.HumanoidModel<?> model,
                            net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.entity.HumanoidArm arm) {
                        calls++;
                        net.minecraft.client.Minecraft.getInstance().retromodTestRemoved();
                    }
                }""");
        return sources;
    }

    /** Forge 1.20.1's shapes the old mod compiles against. */
    public static Map<String, String> oldSources() {
        Map<String, String> sources = new HashMap<>(shared());
        sources.putAll(oldModSources());
        sources.put("net/minecraftforge/client/IArmPoseTransformer.java", """
                package net.minecraftforge.client;
                public interface IArmPoseTransformer {
                    void applyTransform(net.minecraft.client.model.HumanoidModel<?> model,
                            net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.entity.HumanoidArm arm);
                }""");
        sources.put("net/minecraft/client/model/HumanoidModel.java", """
                package net.minecraft.client.model;
                public class HumanoidModel<T> {
                    public final net.minecraft.client.model.geom.ModelPart rightArm = new net.minecraft.client.model.geom.ModelPart();
                    public final net.minecraft.client.model.geom.ModelPart leftArm = new net.minecraft.client.model.geom.ModelPart();
                    public static final class ArmPose {
                        public static ArmPose create(String name, boolean twoHanded, net.minecraftforge.client.IArmPoseTransformer t) {
                            throw new IllegalStateException();
                        }
                    }
                }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public float getFrameTime() { return 0; }
                    public void retromodTestRemoved() {}
                }""");
        return sources;
    }

    /** Forge 26.2's shapes: a render-state transformer, a create that also takes the offhand flag, and the delta tracker. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(shared());
        sources.put("net/minecraft/client/renderer/entity/state/EntityRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class EntityRenderState {
                    public net.minecraft.world.entity.EntityType<?> entityType;
                    public double x;
                    public double y;
                    public double z;
                    public float ageInTicks;
                }""");
        sources.put("net/minecraft/client/renderer/entity/state/AvatarRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class AvatarRenderState extends HumanoidRenderState { public int id; }");
        sources.put("net/minecraft/client/renderer/entity/state/LivingEntityRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class LivingEntityRenderState extends EntityRenderState {}");
        sources.put("net/minecraft/client/renderer/entity/state/ArmedEntityRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class ArmedEntityRenderState extends LivingEntityRenderState {}");
        sources.put("net/minecraft/client/renderer/entity/state/HumanoidRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class HumanoidRenderState extends ArmedEntityRenderState {}");
        sources.put("net/minecraftforge/client/IArmPoseTransformer.java", """
                package net.minecraftforge.client;
                public interface IArmPoseTransformer {
                    void applyTransform(net.minecraft.client.model.HumanoidModel<? extends net.minecraft.client.renderer.entity.state.HumanoidRenderState> model,
                            net.minecraft.client.renderer.entity.state.HumanoidRenderState state, net.minecraft.world.entity.HumanoidArm arm);
                }""");
        sources.put("net/minecraft/client/model/HumanoidModel.java", """
                package net.minecraft.client.model;
                public class HumanoidModel<T extends net.minecraft.client.renderer.entity.state.HumanoidRenderState> {
                    public final net.minecraft.client.model.geom.ModelPart rightArm = new net.minecraft.client.model.geom.ModelPart();
                    public final net.minecraft.client.model.geom.ModelPart leftArm = new net.minecraft.client.model.geom.ModelPart();
                    public static final class ArmPose {
                        public static final ArmPose ITEM = new ArmPose("ITEM", false, false, null);
                        public static final java.util.List<ArmPose> CREATED = new java.util.ArrayList<>();
                        public static boolean refuse;
                        public final String name;
                        public final boolean twoHanded;
                        public final boolean affectsOffhandPose;
                        private final net.minecraftforge.client.IArmPoseTransformer transformer;
                        private ArmPose(String name, boolean twoHanded, boolean affectsOffhandPose,
                                net.minecraftforge.client.IArmPoseTransformer transformer) {
                            this.name = name;
                            this.twoHanded = twoHanded;
                            this.affectsOffhandPose = affectsOffhandPose;
                            this.transformer = transformer;
                        }
                        public static ArmPose create(String name, boolean twoHanded, boolean affectsOffhandPose,
                                net.minecraftforge.client.IArmPoseTransformer transformer) {
                            if (refuse) throw new IllegalStateException("Enum not extended");
                            ArmPose pose = new ArmPose(name, twoHanded, affectsOffhandPose, transformer);
                            CREATED.add(pose);
                            return pose;
                        }
                        public void applyTransform(HumanoidModel<?> model,
                                net.minecraft.client.renderer.entity.state.HumanoidRenderState state, net.minecraft.world.entity.HumanoidArm arm) {
                            if (transformer != null) transformer.applyTransform(model, state, arm);
                        }
                    }
                }""");
        sources.put("net/minecraft/world/phys/AABB.java", """
                package net.minecraft.world.phys;
                public class AABB {
                    final double minX, minY, minZ, maxX, maxY, maxZ;
                    public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
                        this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
                    }
                    public boolean contains(double x, double y, double z) {
                        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
                    }
                }""");
        sources.put("net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                import net.minecraft.world.entity.Entity;
                public class Level {
                    public final java.util.List<Entity> entities = new java.util.ArrayList<>();
                    public java.util.List<Entity> getEntities(Entity except, net.minecraft.world.phys.AABB area,
                            java.util.function.Predicate<? super Entity> filter) {
                        java.util.List<Entity> found = new java.util.ArrayList<>();
                        for (Entity entity : entities) {
                            if (entity != except && area.contains(entity.getX(), entity.getY(), entity.getZ()) && filter.test(entity)) found.add(entity);
                        }
                        return found;
                    }
                    public Entity getEntity(int id) {
                        for (Entity entity : entities) if (entity.id == id) return entity;
                        return null;
                    }
                }""");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java",
                "package net.minecraft.client.multiplayer; public class ClientLevel extends net.minecraft.world.level.Level {}");
        sources.put("net/minecraft/client/DeltaTracker.java", """
                package net.minecraft.client;
                public interface DeltaTracker { float getGameTimeDeltaPartialTick(boolean runsNormally); }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public net.minecraft.client.multiplayer.ClientLevel level;
                    public static Minecraft getInstance() { return INSTANCE; }
                    public DeltaTracker getDeltaTracker() { return runsNormally -> runsNormally ? 0.25f : 1f; }
                }""");
        return sources;
    }

    /** Classes both versions share. */
    private static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/client/model/geom/ModelPart.java",
                "package net.minecraft.client.model.geom; public class ModelPart { public float xRot; }");
        sources.put("net/minecraft/world/entity/HumanoidArm.java",
                "package net.minecraft.world.entity; public enum HumanoidArm { LEFT, RIGHT }");
        sources.put("net/minecraft/world/entity/EntityType.java", """
                package net.minecraft.world.entity;
                public class EntityType<T> {
                    public final String id;
                    public EntityType(String id) { this.id = id; }
                }""");
        sources.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public double xOld, yOld, zOld;
                    public int tickCount;
                    public int id;
                    private double x, y, z;
                    private final EntityType<?> type;
                    public final String tag;
                    public Entity(EntityType<?> type, String tag) { this.type = type; this.tag = tag; }
                    public void moveTo(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
                    public final double getX() { return x; }
                    public final double getY() { return y; }
                    public final double getZ() { return z; }
                    public EntityType<?> getType() { return type; }
                }""");
        sources.put("net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                public class LivingEntity extends Entity {
                    public LivingEntity(EntityType<?> type, String tag) { super(type, tag); }
                }""");
        return sources;
    }

    // ---- reflection over the stand-ins ----

    private static List<?> created(BridgeHarness.Run run) throws Exception {
        return (List<?>) run.loadClass(POSE).getField("CREATED").get(null);
    }

    private static Object pose(BridgeHarness.Run run, int index) throws Exception {
        return created(run).get(index);
    }

    private static String describe(Object pose) {
        try {
            Class<?> type = pose.getClass();
            return type.getField("name").get(pose) + " " + type.getField("twoHanded").get(pose) + " "
                    + type.getField("affectsOffhandPose").get(pose);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object newInstance(BridgeHarness.Run run, String name) throws Exception {
        return run.loadClass(name).getConstructor().newInstance();
    }

    private static Object type(BridgeHarness.Run run, String id) throws Exception {
        return run.loadClass("net.minecraft.world.entity.EntityType").getConstructor(String.class).newInstance(id);
    }

    private static void addEntity(BridgeHarness.Run run, Object level, Object type, String tag, int id, int tickCount,
            double x, double y, double z, double oldX, double oldY, double oldZ) throws Exception {
        Class<?> living = run.loadClass("net.minecraft.world.entity.LivingEntity");
        Object entity = living.getConstructor(run.loadClass("net.minecraft.world.entity.EntityType"), String.class)
                .newInstance(type, tag);
        living.getMethod("moveTo", double.class, double.class, double.class).invoke(entity, x, y, z);
        Class<?> base = run.loadClass("net.minecraft.world.entity.Entity");
        base.getField("xOld").set(entity, oldX);
        base.getField("yOld").set(entity, oldY);
        base.getField("zOld").set(entity, oldZ);
        base.getField("id").set(entity, id);
        base.getField("tickCount").set(entity, tickCount);
        @SuppressWarnings("unchecked")
        List<Object> entities = (List<Object>) level.getClass().getField("entities").get(level);
        entities.add(entity);
    }

    private static Object state(BridgeHarness.Run run, Object type, double x, double y, double z, float age) throws Exception {
        return state(run, "net.minecraft.client.renderer.entity.state.HumanoidRenderState", type, x, y, z, age);
    }

    private static Object state(BridgeHarness.Run run, String stateClass, Object type, double x, double y, double z,
            float age) throws Exception {
        Object state = newInstance(run, stateClass);
        Class<?> base = run.loadClass("net.minecraft.client.renderer.entity.state.EntityRenderState");
        base.getField("entityType").set(state, type);
        base.getField("x").set(state, x);
        base.getField("y").set(state, y);
        base.getField("z").set(state, z);
        base.getField("ageInTicks").set(state, age);
        return state;
    }

    private static void apply(BridgeHarness.Run run, Object pose, Object model, Object state, String arm) throws Exception {
        Class<?> arms = run.loadClass("net.minecraft.world.entity.HumanoidArm");
        Object side = arms.getField(arm).get(null);
        for (Method method : pose.getClass().getMethods()) {
            if (method.getName().equals("applyTransform")) {
                method.invoke(pose, model, state, side);
                return;
            }
        }
        throw new AssertionError("the stand-in pose has no applyTransform");
    }

    private static float part(Object model, String name) throws Exception {
        Object part = model.getClass().getField(name).get(model);
        return part.getClass().getField("xRot").getFloat(part);
    }

    private static void setLevel(BridgeHarness.Run run, Object level) throws Exception {
        Class<?> minecraft = run.loadClass("net.minecraft.client.Minecraft");
        Object instance = minecraft.getMethod("getInstance").invoke(null);
        minecraft.getField("level").set(instance, level);
    }

    private static void setStatic(BridgeHarness.Run run, String owner, String name, Object value) throws Exception {
        Field field = run.loadClass(owner).getField(name);
        field.set(null, value);
    }
}
