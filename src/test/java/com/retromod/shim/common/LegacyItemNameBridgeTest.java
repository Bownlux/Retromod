/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyItemNameBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.21.1 item's name call through each Forge-family chain, and the helper's two answers on 26.x. */
class LegacyItemNameBridgeTest {

    private static final String HELPER_CALL = HELPER + ".name" + HELPER_DESC;

    @TempDir
    static Path stubDir;

    /** 26.x-shaped stand-ins for the members the helper calls, by internal name. */
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(Map.of(
                "net/minecraft/network/chat/Component.java", """
                        package net.minecraft.network.chat;
                        public interface Component {
                            static MutableComponent translatable(String key) { return new MutableComponent(key); }
                        }""",
                "net/minecraft/network/chat/MutableComponent.java", """
                        package net.minecraft.network.chat;
                        public class MutableComponent implements Component {
                            public final String key;
                            public MutableComponent(String key) { this.key = key; }
                        }""",
                "net/minecraft/network/chat/CommonComponents.java", """
                        package net.minecraft.network.chat;
                        public class CommonComponents { public static final Component EMPTY = new MutableComponent(""); }""",
                "net/minecraft/core/component/DataComponentType.java", """
                        package net.minecraft.core.component;
                        public class DataComponentType<T> {}""",
                "net/minecraft/core/component/DataComponents.java", """
                        package net.minecraft.core.component;
                        import net.minecraft.network.chat.Component;
                        public class DataComponents {
                            public static final DataComponentType<Component> ITEM_NAME = new DataComponentType<>();
                        }""",
                "net/minecraft/core/component/DataComponentGetter.java", """
                        package net.minecraft.core.component;
                        public interface DataComponentGetter {
                            <T> T get(DataComponentType<? extends T> type);
                            default <T> T getOrDefault(DataComponentType<? extends T> type, T fallback) {
                                T value = get(type);
                                return value != null ? value : fallback;
                            }
                        }""",
                "net/minecraft/core/component/DataComponentMap.java", """
                        package net.minecraft.core.component;
                        public interface DataComponentMap extends DataComponentGetter {}""",
                "net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        public interface Holder<T> {
                            class Reference<T> implements Holder<T> {
                                public boolean bound;
                                public boolean areComponentsBound() { return bound; }
                            }
                        }""",
                "net/minecraft/world/item/Item.java", """
                        package net.minecraft.world.item;
                        import net.minecraft.core.Holder;
                        import net.minecraft.core.component.*;
                        import net.minecraft.network.chat.Component;
                        public class Item {
                            public final Holder.Reference<Item> holder = new Holder.Reference<>();
                            public Component itemName;
                            public Holder.Reference<Item> builtInRegistryHolder() { return holder; }
                            public DataComponentMap components() {
                                if (!holder.bound) throw new NullPointerException("Components not bound yet");
                                return new DataComponentMap() {
                                    @SuppressWarnings("unchecked")
                                    public <T> T get(DataComponentType<? extends T> type) {
                                        return type == DataComponents.ITEM_NAME ? (T) itemName : null;
                                    }
                                };
                            }
                            public final String getDescriptionId() { return "item.testmod.ruby"; }
                        }"""));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void neoForgeChainReachesTheHelperOn26x() {
        assertEquals(HELPER_CALL, transformCall("26.3", new NeoForge_1_21_1_to_1_21_2(), new NeoForge_1_21_11_to_26_1()));
        // The rename and the helper converge whichever registers last.
        assertEquals(HELPER_CALL, transformCall("26.3", new NeoForge_1_21_11_to_26_1(), new NeoForge_1_21_1_to_1_21_2()));
    }

    @Test
    void forgeChainReachesTheHelperOn26x() {
        assertEquals(HELPER_CALL, transformCall("26.3", new Forge_1_21_1_to_1_21_2(), new Forge_1_21_11_to_26_1()));
        assertEquals(HELPER_CALL, transformCall("26.3", new Forge_1_21_11_to_26_1(), new Forge_1_21_1_to_1_21_2()));
    }

    @Test
    void aForge1211HostTakesTheGetNameRename() {
        assertEquals(ITEM + ".getName" + OLD_DESC, transformCall("1.21.11", new Forge_1_21_1_to_1_21_2()),
                "Forge 1.21.2 to 1.21.11 still has the no-argument getName()");
    }

    @Test
    void beforeComponentsAreBoundTheHelperTranslatesTheDescriptionId() throws Exception {
        Run run = new Run();
        Object item = run.newItem();

        Object name = run.name(item);

        assertEquals("item.testmod.ruby", name.getClass().getField("key").get(name),
                "reading components this early throws on 26.x, so the old description is built instead");
    }

    @Test
    void onceBoundTheHelperAnswersTheItemNameComponent() throws Exception {
        Run run = new Run();
        Object item = run.newItem();
        Object holder = item.getClass().getField("holder").get(item);
        holder.getClass().getField("bound").set(holder, true);
        Object custom = run.loadClass("net.minecraft.network.chat.MutableComponent")
                .getConstructor(String.class).newInstance("Ruby");
        item.getClass().getField("itemName").set(item, custom);

        assertSame(custom, run.name(item), "the old getName() returned the item_name component");
        item.getClass().getField("itemName").set(item, null);
        assertSame(run.loadClass("net.minecraft.network.chat.CommonComponents").getField("EMPTY").get(null),
                run.name(item), "an item without the component answers the empty name");
    }

    /** The call a 1.21.1 mod's {@code item.getDescription()} becomes after the given shims. */
    private static String transformCall(String target, VersionShim... shims) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : shims) shim.registerRedirects(transformer);
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(describeCaller(), "mod/Describe.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        assertEquals(1, calls.size(), "one call expected: " + calls);
        return calls.get(0);
    }

    private static byte[] describeCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Describe", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "describe",
                "(L" + ITEM + ";)L" + COMPONENT + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM, "getDescription", OLD_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The generated helper and the stand-ins in one loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Run() {
            super(LegacyItemNameBridgeTest.class.getClassLoader());
            host.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            classes.put(HELPER.replace('/', '.'), generateHelper());
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Object newItem() throws Exception {
            return loadClass("net.minecraft.world.item.Item").getConstructor().newInstance();
        }

        Object name(Object item) throws Exception {
            return loadClass(HELPER.replace('/', '.')).getMethod("name", item.getClass()).invoke(null, item);
        }
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.x stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 26.x stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
