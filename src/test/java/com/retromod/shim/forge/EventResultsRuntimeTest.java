/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** EventResults keeps Forge 1.20.1's results on stand-ins for Forge 26.2 events, across every mod's copy. */
class EventResultsRuntimeTest {

    private static final String RESULTS = LegacyForgeEventResultBridge.EVENT_RESULTS;
    private static final String CANCEL = LegacyCancelBridge.CANCEL;
    private static final String FIRST = "com/retromod/embedded/first/";
    private static final String SECOND = "com/retromod/embedded/second/";
    private static final List<String> SHARED_SLOTS = List.of(
            "retromod.shared.forge.event.store.v1", "retromod.shared.forge.cancel.frames.v1");

    private ClassLoader host;
    private Class<?> first;
    private Class<?> second;

    @BeforeEach
    void twoMods(@TempDir Path dir) throws Exception {
        clearSharedSlots();
        // Only the stand-ins, so the copies below meet the host through their parent as mods do.
        host = hostClasses().loader(dir, RetromodTransformer.getInstance(), name -> false, Map.of());
        first = copy(FIRST).loadClass(dotted(FIRST + RESULTS));
        second = copy(SECOND).loadClass(dotted(SECOND + RESULTS));
    }

    @AfterEach
    void clearSharedSlots() {
        SHARED_SLOTS.forEach(System.getProperties()::remove);
    }

    @Test
    @DisplayName("An event that kept its result answers itself")
    void hostResultsAnswerThemselves() throws Exception {
        Object bonemeal = event("net.minecraftforge.event.entity.player.BonemealEvent");
        call(first, "setResult", bonemeal, result("ALLOW"));
        assertEquals("ALLOW", name(bonemeal.getClass().getMethod("getResult").invoke(bonemeal)), "the host reads it");
        assertEquals("ALLOW", name(call(second, "getResult", bonemeal)));
        assertEquals(true, call(first, "hasResult", bonemeal));
    }

    @Test
    @DisplayName("An event without a result starts at DEFAULT, and a result one mod sets reads back in another")
    void lostResultsCrossCopies() throws Exception {
        Object click = event("net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock",
                player(false), side("SERVER"));
        assertEquals("DEFAULT", name(call(second, "getResult", click)));
        call(first, "setResult", click, result("DENY"));
        assertEquals("DENY", name(call(second, "getResult", click)));
        assertEquals(false, call(first, "hasResult", click), "Forge 1.20.1 gave RightClickBlock no result");
    }

    @Test
    @DisplayName("Two equal EntityMountEvent records keep separate results")
    void equalRecordsKeepSeparateResults() throws Exception {
        Object mount = event("net.minecraftforge.event.entity.EntityMountEvent", "rider", "horse", true);
        Object same = event("net.minecraftforge.event.entity.EntityMountEvent", "rider", "horse", true);
        assertEquals(mount, same);
        call(first, "setResult", mount, result("DENY"));
        assertEquals("DENY", name(call(first, "getResult", mount)));
        assertEquals("DEFAULT", name(call(first, "getResult", same)));
    }

    @Test
    @DisplayName("A denied name tag loses its text and score, and a later DEFAULT puts back the text it started with")
    void nameTagDenyAndRestore() throws Exception {
        Object text = text("Steve");
        Object score = text("12");
        Object tag = event("net.minecraftforge.client.event.RenderNameTagEvent", text, score);
        call(first, "setResult", tag, result("DENY"));
        assertNull(read(tag, "getContent"));
        assertNull(read(tag, "getScoreContent"));
        assertEquals("DENY", name(call(second, "getResult", tag)));
        call(second, "setResult", tag, result("DEFAULT"));
        assertSame(text, read(tag, "getContent"), "another mod's DEFAULT after the denial");
        assertSame(score, read(tag, "getScoreContent"));

        // ALLOW with no denial before it leaves a text another listener chose.
        Object renamed = text("Alex");
        tag.getClass().getMethod("setContent", text.getClass().getInterfaces()[0]).invoke(tag, renamed);
        call(first, "setResult", tag, result("ALLOW"));
        assertSame(renamed, read(tag, "getContent"));
        assertEquals(true, call(first, "hasResult", tag), "Forge 1.20.1 marked the name tag event");
    }

    @Test
    @DisplayName("A denied left click cancels on the server outside creative, and nowhere else")
    void leftClickDenyCancelsOnlyInSurvivalOnTheServer() throws Exception {
        assertTrue(deniedClickCancels(false, "SERVER", "DENY"), "survival, server");
        assertFalse(deniedClickCancels(true, "SERVER", "DENY"), "creative, server");
        assertFalse(deniedClickCancels(false, "CLIENT", "DENY"), "Forge 1.20.1's client never read the result");
        assertFalse(deniedClickCancels(false, "SERVER", "DENY", "DEFAULT"), "a denial taken back in the same listener");
        assertFalse(deniedClickCancels(false, "SERVER", "ALLOW"));
    }

    @Test
    @DisplayName("hasResult follows Forge 1.20.1: BreakEvent says no, the name tag event yes")
    void hasResultFollowsForge1201() throws Exception {
        assertEquals(false, call(first, "hasResult", event("net.minecraftforge.event.level.BlockEvent$BreakEvent")));
        assertEquals(false, call(first, "hasResult", event("net.minecraftforge.event.entity.EntityMountEvent", "a", "b", false)));
        assertEquals(true, call(first, "hasResult", event("net.minecraftforge.client.event.RenderNameTagEvent", null, null)));
        assertEquals(true, call(first, "hasResult", event("net.minecraftforge.event.entity.player.BonemealEvent")));
    }

    @Test
    @DisplayName("An old BreakEvent result stays beside the event, so the host's break check never sees it")
    void breakEventResultStaysBesideTheEvent() throws Exception {
        Object breaking = event("net.minecraftforge.event.level.BlockEvent$BreakEvent");
        call(first, "setResult", breaking, result("DENY"));
        assertEquals("DEFAULT", name(breaking.getClass().getMethod("getResult").invoke(breaking)));
        assertEquals("DENY", name(call(second, "getResult", breaking)));
    }

    @Test
    @DisplayName("Only NameTagResults names the client's RenderNameTagEvent, so a dedicated server never loads it")
    void onlyTheClientHelperNamesTheNameTagEvent() throws Exception {
        String nameTag = "net/minecraftforge/client/event/RenderNameTagEvent";
        String eventbus = RebuiltApiClasses.ROOT + "forge/eventbus/";
        for (String common : List.of(RESULTS, eventbus + "LegacyEventAccess", CANCEL)) {
            String pool = new String(readClass(common), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(pool.contains(nameTag), common + " names " + nameTag);
        }
        assertTrue(RebuiltApiClasses.referencedClasses(readClass(RESULTS)).contains(eventbus + "NameTagResults"));
        assertTrue(new String(readClass(eventbus + "NameTagResults"), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(nameTag), "the client helper is the one that does");
    }

    // ---- helpers ----

    /** Runs one listener on a left click: sets each result in turn and says whether the click ended cancelled. */
    private boolean deniedClickCancels(boolean creative, String side, String... results) throws Exception {
        Object click = event("net.minecraftforge.event.entity.player.PlayerInteractEvent$LeftClickBlock",
                player(creative), side(side));
        Class<?> cancel = first.getClassLoader().loadClass(dotted(FIRST + CANCEL));
        Object frame = call(cancel, "enter", click);
        for (String each : results) call(second, "setResult", click, result(each));
        return (boolean) call(cancel, "exit", frame);
    }

    private Object event(String name, Object... arguments) throws Exception {
        for (var constructor : host.loadClass(name).getConstructors()) {
            if (constructor.getParameterCount() == arguments.length) return constructor.newInstance(arguments);
        }
        throw new AssertionError(name + " has no constructor taking " + arguments.length);
    }

    private Object player(boolean creative) throws Exception {
        return event("net.minecraft.world.entity.player.Player", creative);
    }

    private Object text(String value) throws Exception {
        return event("net.minecraft.network.chat.Text", value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object side(String constant) throws Exception {
        return Enum.valueOf((Class) host.loadClass("net.minecraftforge.fml.LogicalSide"), constant);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object result(String constant) throws Exception {
        return Enum.valueOf((Class) host.loadClass("net.minecraftforge.common.util.Result"), constant);
    }

    private static Object read(Object target, String getter) throws Exception {
        return target.getClass().getMethod(getter).invoke(target);
    }

    private static String name(Object constant) {
        return ((Enum<?>) constant).name();
    }

    private static Object call(Class<?> copy, String name, Object... arguments) throws Exception {
        for (Method method : copy.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try {
                    return method.invoke(null, arguments);
                } catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw failure;
                }
            }
        }
        throw new AssertionError(copy.getName() + " has no " + name);
    }

    /** One mod's copy of EventResults and every Retromod class it names, relocated under {@code prefix} as the embedder does. */
    private ClassLoader copy(String prefix) throws IOException {
        Remapper relocate = new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith("com/retromod/") ? prefix + internalName : internalName;
            }
        };
        List<String> names = new ArrayList<>(List.of(RESULTS, LegacyLog.class.getName().replace('.', '/')));
        Map<String, byte[]> classes = new HashMap<>();
        while (!names.isEmpty()) {
            String name = names.remove(0);
            if (classes.containsKey(prefix + name)) continue;
            byte[] bytes = readClass(name);
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassRemapper(writer, relocate), 0);
            classes.put(prefix + name, writer.toByteArray());
            names.addAll(RebuiltApiClasses.referencedClasses(bytes));
        }
        return new LegacyEventRuntimeTest.Loader(host, classes);
    }

    private static byte[] readClass(String internalName) throws IOException {
        try (InputStream in = EventResultsRuntimeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing class " + internalName);
            return in.readAllBytes();
        }
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Stand-ins with the shapes and behavior of the Forge 26.2 members EventResults reaches. */
    private static ForgeHostStandIns hostClasses() {
        return new ForgeHostStandIns()
                .add("net/minecraftforge/common/util/Result.java", """
                        package net.minecraftforge.common.util;
                        public enum Result { ALLOW, DEFAULT, DENY }
                        """)
                .add("net/minecraftforge/common/util/HasResult.java", """
                        package net.minecraftforge.common.util;
                        public interface HasResult {
                            Result getResult();
                            void setResult(Result result);
                        }
                        """)
                .add("net/minecraftforge/fml/LogicalSide.java", """
                        package net.minecraftforge.fml;
                        public enum LogicalSide { CLIENT, SERVER }
                        """)
                .add("net/minecraft/world/entity/player/Player.java", """
                        package net.minecraft.world.entity.player;
                        public class Player {
                            private final boolean creative;
                            public Player(boolean creative) { this.creative = creative; }
                            public boolean isCreative() { return creative; }
                        }
                        """)
                .add("net/minecraftforge/event/entity/player/PlayerInteractEvent.java", """
                        package net.minecraftforge.event.entity.player;
                        import net.minecraft.world.entity.player.Player;
                        import net.minecraftforge.fml.LogicalSide;
                        public abstract class PlayerInteractEvent {
                            private final Player player;
                            private final LogicalSide side;
                            PlayerInteractEvent(Player player, LogicalSide side) { this.player = player; this.side = side; }
                            public Player getEntity() { return player; }
                            public LogicalSide getSide() { return side; }
                            public static final class LeftClickBlock extends PlayerInteractEvent {
                                public LeftClickBlock(Player player, LogicalSide side) { super(player, side); }
                            }
                            public static final class RightClickBlock extends PlayerInteractEvent {
                                public RightClickBlock(Player player, LogicalSide side) { super(player, side); }
                            }
                        }
                        """)
                .add("net/minecraftforge/event/entity/player/BonemealEvent.java", """
                        package net.minecraftforge.event.entity.player;
                        import net.minecraftforge.common.util.HasResult;
                        import net.minecraftforge.common.util.Result;
                        public final class BonemealEvent implements HasResult {
                            private Result result = Result.DEFAULT;
                            public Result getResult() { return result; }
                            public void setResult(Result value) { result = value; }
                        }
                        """)
                .add("net/minecraftforge/event/level/BlockEvent.java", """
                        package net.minecraftforge.event.level;
                        import net.minecraftforge.common.util.HasResult;
                        import net.minecraftforge.common.util.Result;
                        public interface BlockEvent {
                            final class BreakEvent implements BlockEvent, HasResult {
                                private Result result = Result.DEFAULT;
                                public BreakEvent() {}
                                public Result getResult() { return result; }
                                public void setResult(Result value) { result = value; }
                            }
                        }
                        """)
                .add("net/minecraftforge/event/entity/EntityMountEvent.java", """
                        package net.minecraftforge.event.entity;
                        public record EntityMountEvent(Object mounting, Object mounted, boolean isMounting) {}
                        """)
                .add("net/minecraft/network/chat/Component.java", """
                        package net.minecraft.network.chat;
                        public interface Component {}
                        """)
                .add("net/minecraft/network/chat/Text.java", """
                        package net.minecraft.network.chat;
                        public record Text(String value) implements Component {}
                        """)
                .add("net/minecraftforge/client/event/RenderNameTagEvent.java", """
                        package net.minecraftforge.client.event;
                        import net.minecraft.network.chat.Component;
                        public final class RenderNameTagEvent {
                            private final Component originalContent;
                            private final Component originalScore;
                            private Component content;
                            private Component score;
                            public RenderNameTagEvent(Component content, Component score) {
                                this.originalContent = content;
                                this.originalScore = score;
                                this.content = content;
                                this.score = score;
                            }
                            public void setContent(Component value) { content = value; }
                            public Component getContent() { return content; }
                            public void setScoreContent(Component value) { score = value; }
                            public Component getScoreContent() { return score; }
                            public Component getOriginalContent() { return originalContent; }
                            public Component getOriginalScore() { return originalScore; }
                        }
                        """);
    }
}
