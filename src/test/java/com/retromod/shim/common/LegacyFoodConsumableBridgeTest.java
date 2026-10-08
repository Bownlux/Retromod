/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.21.1 food that calls {@code FoodProperties.Builder.effect}, {@code fast} or
 * {@code usingConvertsTo} died with {@code NoSuchMethodError} on 26.1, which stopped Bountiful
 * Fares from registering anything after its first effect food. The bridge must carry those settings
 * into the item's consumable instead of dropping them.
 *
 * <p>The tests run the transformed mod code against small stand-ins for the 26.1 classes involved,
 * compiled at test time and loaded in an isolated class loader, so they check what the item ends up
 * with rather than the shape of the rewritten bytecode.
 */
class LegacyFoodConsumableBridgeTest {

    private static final String MOD_CLASS = "test/mod/ModFoods";
    private static final String MOD_PROPS = "test/mod/ModProps";
    private static final String SOUND_EVENTS = "net/minecraft/sounds/SoundEvents";
    private static final String SOUND_EVENT_DESC = "Lnet/minecraft/sounds/SoundEvent;";

    private RetromodTransformer transformer;

    @TempDir
    Path stubDir;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFoodConsumableBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("each old food effect becomes an apply-effects consume effect with its probability")
    void effectsMoveToTheConsumable() throws Exception {
        Object props = runModFood(true, false, false);

        Object consumable = field(props, "consumable");
        assertNotNull(consumable, "a food with effects must be given a consumable");
        assertEquals(List.of("regeneration@0.5", "nausea@1.0"), field(consumable, "effects"),
                "both effects must survive, in order, with their own probability");
        assertEquals(1.6F, field(consumable, "seconds"), "the default eating time is kept");
        assertEquals(4, field(field(props, "food"), "nutrition"));
    }

    @Test
    @DisplayName("fast() becomes the old 0.8 second eating time")
    void fastSetsTheOldEatingTime() throws Exception {
        Object props = runModFood(false, true, false);

        assertEquals(0.8F, field(field(props, "consumable"), "seconds"));
    }

    @Test
    @DisplayName("usingConvertsTo reaches the item properties as the leftover item")
    void leftoverItemReachesTheProperties() throws Exception {
        Object props = runModFood(false, false, true);

        assertEquals("bowl", field(field(props, "convertsTo"), "name"));
        assertNull(field(props, "consumable"),
                "a food without effects or fast() keeps the host's plain food(food) path");
    }

    @Test
    @DisplayName("a food built without removed calls takes the host's plain food(food)")
    void plainFoodIsUntouched() throws Exception {
        Object props = runModFood(false, false, false);

        assertNull(field(props, "consumable"));
        assertNull(field(props, "convertsTo"));
        assertEquals(4, field(field(props, "food"), "nutrition"));
    }

    @Test
    @DisplayName("an old SoundEvent read of a drink sound that became a holder unwraps it")
    void holderSoundIsUnwrapped() throws Exception {
        // The holder bridge owns these reads; Fabric registers it beside this bridge on 26.1+.
        LegacyHolderConstantBridge.registerRedirects(transformer,
                Map.of(SOUND_EVENTS, Map.of("HONEY_DRINK", "Lnet/minecraft/core/Holder$Reference;")));
        ClassLoader loader = loaderWithStubs(transformer.transformClass(soundReader(), MOD_CLASS));
        Method read = loader.loadClass(MOD_CLASS.replace('/', '.')).getMethod("sound");

        assertEquals("honey_drink", field(read.invoke(null), "name"));
    }

    @Test
    @DisplayName("a mod's food(food) override keeps its super call, so it does not recurse")
    void overrideSuperCallStillReachesTheHost() throws Exception {
        Map<String, byte[]> mod = Map.of(
                MOD_PROPS, transformer.transformClass(propertiesOverride(), MOD_PROPS),
                MOD_CLASS, transformer.transformClass(overrideFoodMaker(), MOD_CLASS));
        ClassLoader loader = loaderWithStubs(mod);

        Object props = loader.loadClass(MOD_CLASS.replace('/', '.')).getMethod("make").invoke(null);

        assertEquals(1, field(props, "overrideCalls"), "the override must run exactly once");
        assertEquals(4, field(field(props, "food"), "nutrition"));
    }

    /**
     * {@code new Item.Properties().food(new FoodProperties.Builder().nutrition(4)[.effect...]
     * [.fast()][.usingConvertsTo(bowl)].build())}, as a 1.21.1 mod compiled it.
     */
    private Object runModFood(boolean effects, boolean fast, boolean convertsTo) throws Exception {
        byte[] mod = transformer.transformClass(foodMaker(effects, fast, convertsTo), MOD_CLASS);
        ClassLoader loader = loaderWithStubs(mod);
        return loader.loadClass(MOD_CLASS.replace('/', '.')).getMethod("make").invoke(null);
    }

    private static byte[] foodMaker(boolean effects, boolean fast, boolean convertsTo) {
        String builder = LegacyFoodConsumableBridge.BUILDER;
        String lBuilder = "L" + builder + ";";
        String props = LegacyFoodConsumableBridge.ITEM_PROPS;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "()L" + props + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, props);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, props, "<init>", "()V", false);
        mv.visitTypeInsn(Opcodes.NEW, builder);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, builder, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "nutrition", "(I)" + lBuilder, false);
        if (effects) {
            addEffect(mv, "regeneration", 0.5F);
            addEffect(mv, "nausea", 1.0F);
        }
        if (fast) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "fast", "()" + lBuilder, false);
        }
        if (convertsTo) {
            String item = LegacyFoodConsumableBridge.ITEM;
            mv.visitTypeInsn(Opcodes.NEW, item);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("bowl");
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, item, "<init>", "(Ljava/lang/String;)V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "usingConvertsTo",
                    LegacyFoodConsumableBridge.CONVERTS_DESC, false);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "build",
                "()L" + LegacyFoodConsumableBridge.FOOD + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, props, "food",
                LegacyFoodConsumableBridge.FOOD_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addEffect(MethodVisitor mv, String name, float probability) {
        String instance = LegacyFoodConsumableBridge.EFFECT_INSTANCE;
        mv.visitTypeInsn(Opcodes.NEW, instance);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, instance, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitLdcInsn(probability);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyFoodConsumableBridge.BUILDER, "effect",
                LegacyFoodConsumableBridge.EFFECT_DESC, false);
    }

    /**
     * {@code class ModProps extends Item.Properties}, whose {@code food(food)} override counts its
     * calls and returns {@code super.food(food)}.
     */
    private static byte[] propertiesOverride() {
        String props = LegacyFoodConsumableBridge.ITEM_PROPS;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_PROPS, null, props, null);
        cw.visitField(Opcodes.ACC_PUBLIC, "overrideCalls", "I", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, props, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor food = cw.visitMethod(Opcodes.ACC_PUBLIC, "food",
                LegacyFoodConsumableBridge.FOOD_DESC, null, null);
        food.visitCode();
        food.visitVarInsn(Opcodes.ALOAD, 0);
        food.visitInsn(Opcodes.DUP);
        food.visitFieldInsn(Opcodes.GETFIELD, MOD_PROPS, "overrideCalls", "I");
        food.visitInsn(Opcodes.ICONST_1);
        food.visitInsn(Opcodes.IADD);
        food.visitFieldInsn(Opcodes.PUTFIELD, MOD_PROPS, "overrideCalls", "I");
        food.visitVarInsn(Opcodes.ALOAD, 0);
        food.visitVarInsn(Opcodes.ALOAD, 1);
        food.visitMethodInsn(Opcodes.INVOKESPECIAL, props, "food",
                LegacyFoodConsumableBridge.FOOD_DESC, false);
        food.visitInsn(Opcodes.ARETURN);
        food.visitMaxs(0, 0);
        food.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code Item.Properties p = new ModProps();}
     * {@code return p.food(new FoodProperties.Builder().nutrition(4).build());}
     */
    private static byte[] overrideFoodMaker() {
        String builder = LegacyFoodConsumableBridge.BUILDER;
        String props = LegacyFoodConsumableBridge.ITEM_PROPS;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                "()L" + props + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, MOD_PROPS);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_PROPS, "<init>", "()V", false);
        mv.visitTypeInsn(Opcodes.NEW, builder);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, builder, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "nutrition", "(I)L" + builder + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "build",
                "()L" + LegacyFoodConsumableBridge.FOOD + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, props, "food",
                LegacyFoodConsumableBridge.FOOD_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static SoundEvent sound() { return SoundEvents.HONEY_DRINK; }} with the 1.21.1 type. */
    private static byte[] soundReader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sound",
                "()" + SOUND_EVENT_DESC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, SOUND_EVENTS, "HONEY_DRINK", SOUND_EVENT_DESC);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassLoader loaderWithStubs(byte[] modClass) throws IOException {
        return loaderWithStubs(Map.of(MOD_CLASS, modClass));
    }

    /** The transformed mod classes, the bridges, and compiled 26.1 stand-ins, in an isolated loader. */
    private ClassLoader loaderWithStubs(Map<String, byte[]> modClasses) throws IOException {
        Map<String, byte[]> classes = new HashMap<>(compileStubs());
        modClasses.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        transformer.getSyntheticClasses().forEach(
                (name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    return ClassLoader.getPlatformClassLoader().loadClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private Map<String, byte[]> compileStubs() throws IOException {
        Map<String, String> sources = Map.of(
                "net/minecraft/world/food/FoodProperties.java", """
                        package net.minecraft.world.food;
                        public class FoodProperties {
                            public final int nutrition;
                            FoodProperties(int nutrition) { this.nutrition = nutrition; }
                            public static class Builder {
                                private int nutrition;
                                public Builder nutrition(int n) { nutrition = n; return this; }
                                public FoodProperties build() { return new FoodProperties(nutrition); }
                            }
                        }""",
                "net/minecraft/world/effect/MobEffectInstance.java", """
                        package net.minecraft.world.effect;
                        public class MobEffectInstance {
                            public final String name;
                            public MobEffectInstance(String name) { this.name = name; }
                        }""",
                "net/minecraft/world/item/consume_effects/ConsumeEffect.java", """
                        package net.minecraft.world.item.consume_effects;
                        public interface ConsumeEffect { String describe(); }""",
                "net/minecraft/world/item/consume_effects/ApplyStatusEffectsConsumeEffect.java", """
                        package net.minecraft.world.item.consume_effects;
                        import net.minecraft.world.effect.MobEffectInstance;
                        public record ApplyStatusEffectsConsumeEffect(MobEffectInstance effect, float probability)
                                implements ConsumeEffect {
                            public String describe() { return effect.name + "@" + probability; }
                        }""",
                "net/minecraft/world/item/component/Consumable.java", """
                        package net.minecraft.world.item.component;
                        import java.util.ArrayList;
                        import java.util.List;
                        import net.minecraft.world.item.consume_effects.ConsumeEffect;
                        public class Consumable {
                            public final float seconds;
                            public final List<String> effects;
                            Consumable(float seconds, List<String> effects) {
                                this.seconds = seconds; this.effects = effects;
                            }
                            public static class Builder {
                                private float seconds = 1.6F;
                                private final List<String> effects = new ArrayList<>();
                                public Builder consumeSeconds(float s) { seconds = s; return this; }
                                public Builder onConsume(ConsumeEffect e) { effects.add(e.describe()); return this; }
                                public Consumable build() { return new Consumable(seconds, List.copyOf(effects)); }
                            }
                        }""",
                "net/minecraft/world/item/component/Consumables.java", """
                        package net.minecraft.world.item.component;
                        public class Consumables {
                            public static Consumable.Builder defaultFood() { return new Consumable.Builder(); }
                        }""",
                "net/minecraft/world/level/ItemLike.java", """
                        package net.minecraft.world.level;
                        public interface ItemLike { net.minecraft.world.item.Item asItem(); }""",
                "net/minecraft/world/item/Item.java", """
                        package net.minecraft.world.item;
                        import net.minecraft.world.food.FoodProperties;
                        import net.minecraft.world.item.component.Consumable;
                        public class Item implements net.minecraft.world.level.ItemLike {
                            public final String name;
                            public Item(String name) { this.name = name; }
                            public Item asItem() { return this; }
                            public static class Properties {
                                public FoodProperties food;
                                public Consumable consumable;
                                public Item convertsTo;
                                public Properties food(FoodProperties f) { food = f; return this; }
                                public Properties food(FoodProperties f, Consumable c) {
                                    food = f; consumable = c; return this;
                                }
                                public Properties usingConvertsTo(Item i) { convertsTo = i; return this; }
                            }
                        }""",
                "net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        public interface Holder<T> {
                            T value();
                            class Reference<T> implements Holder<T> {
                                private final T value;
                                public Reference(T value) { this.value = value; }
                                public T value() { return value; }
                            }
                        }""",
                "net/minecraft/sounds/SoundEvent.java", """
                        package net.minecraft.sounds;
                        public class SoundEvent {
                            public final String name;
                            public SoundEvent(String name) { this.name = name; }
                        }""");
        Map<String, String> withSounds = new HashMap<>(sources);
        withSounds.put("net/minecraft/sounds/SoundEvents.java", """
                package net.minecraft.sounds;
                import net.minecraft.core.Holder;
                public class SoundEvents {
                    public static final Holder.Reference<SoundEvent> GENERIC_EAT =
                            new Holder.Reference<>(new SoundEvent("generic_eat"));
                    public static final Holder.Reference<SoundEvent> GENERIC_DRINK =
                            new Holder.Reference<>(new SoundEvent("generic_drink"));
                    public static final Holder.Reference<SoundEvent> HONEY_DRINK =
                            new Holder.Reference<>(new SoundEvent("honey_drink"));
                }""");

        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : withSounds.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.1 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the 26.1 stand-ins must compile");

        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }
}
