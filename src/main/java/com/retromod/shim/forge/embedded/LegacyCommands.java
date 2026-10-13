/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Runs 1.12.2 commands on Brigadier.
 *
 * <p>A 1.12 mod registered a {@code CommandBase} subclass from {@code FMLServerStartingEvent}, and
 * 1.13 replaced that command system with Brigadier. Each command becomes a literal for its name
 * and aliases that takes the rest of the line as one string, splits it on spaces, and calls the
 * mod's {@code execute(server, sender, args)}. The sender is the player or server behind the
 * command, which implements {@code CommandSource} as it implemented {@code ICommandSender}. A
 * {@code CommandException} becomes an error message to the sender, and the command's permission
 * level guards it as before. Tab completion is not offered.
 *
 * <p>Minecraft members are found by type rather than by name, because Forge 1.20.1 still names
 * them in SRG at runtime. Brigadier's own names never change.
 */
public final class LegacyCommands {

    private static final List<Object> COMMANDS = new ArrayList<>();
    private static final List<Object> CLIENT_COMMANDS = new ArrayList<>();

    private LegacyCommands() {}

    /** Remembers a command, so it is registered again when the dispatcher is rebuilt. */
    static synchronized void add(Object command) {
        if (!COMMANDS.contains(command)) COMMANDS.add(command);
    }

    /** Remembers a client command from {@code ClientCommandHandler}. */
    static synchronized void addClient(Object command) {
        if (!CLIENT_COMMANDS.contains(command)) CLIENT_COMMANDS.add(command);
    }

    /** Registers every remembered client command with the client's command dispatcher. */
    static synchronized void registerAllClient(Object dispatcher) {
        registerEach(dispatcher, CLIENT_COMMANDS);
    }

    /** Registers every remembered command with a Brigadier dispatcher. */
    static synchronized void registerAll(Object dispatcher) {
        registerEach(dispatcher, COMMANDS);
    }

    private static void registerEach(Object dispatcher, List<Object> commands) {
        for (Object command : commands) {
            try {
                register(dispatcher, command);
            } catch (Throwable failure) {
                LegacyForgeEvents.warn("command " + command.getClass().getName() + " could not be registered", failure);
            }
        }
    }

    private static void register(Object dispatcher, Object command) throws Exception {
        ClassLoader loader = dispatcher.getClass().getClassLoader();
        Class<?> literalBuilder = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder", true, loader);
        Class<?> requiredBuilder = Class.forName("com.mojang.brigadier.builder.RequiredArgumentBuilder", true, loader);
        Class<?> argumentBuilder = Class.forName("com.mojang.brigadier.builder.ArgumentBuilder", true, loader);
        Class<?> argumentType = Class.forName("com.mojang.brigadier.arguments.ArgumentType", true, loader);
        Class<?> commandType = Class.forName("com.mojang.brigadier.Command", true, loader);
        Object greedy = Class.forName("com.mojang.brigadier.arguments.StringArgumentType", true, loader)
                .getMethod("greedyString").invoke(null);
        int level = permissionLevel(command);
        Object run = Proxy.newProxyInstance(loader, new Class<?>[]{commandType}, (proxy, method, args) ->
                method.getName().equals("run") ? execute(command, args[0]) : defaultObjectMethod(proxy, method, args));
        Predicate<Object> allowed = source -> hasPermission(source, level);

        List<String> names = new ArrayList<>();
        names.add(String.valueOf(call(command, String.class, "getName", "func_71517_b")));
        Object aliases = call(command, Object.class, "getAliases", "func_71514_a");
        if (aliases instanceof Iterable<?> list) list.forEach(alias -> names.add(String.valueOf(alias)));
        for (String name : names) {
            Object literal = literalBuilder.getMethod("literal", String.class).invoke(null, name);
            Object argument = requiredBuilder.getMethod("argument", String.class, argumentType).invoke(null, "args", greedy);
            argumentBuilder.getMethod("executes", commandType).invoke(argument, run);
            argumentBuilder.getMethod("requires", Predicate.class).invoke(literal, allowed);
            argumentBuilder.getMethod("executes", commandType).invoke(literal, run);
            argumentBuilder.getMethod("then", argumentBuilder).invoke(literal, argument);
            dispatcher.getClass().getMethod("register", literalBuilder).invoke(dispatcher, literal);
        }
    }

    /** Runs the mod's {@code execute} for one Brigadier invocation. */
    private static Object execute(Object command, Object context) throws Exception {
        try {
            return run(command, context);
        } catch (Exception | LinkageError failure) {
            LegacyForgeEvents.warn("command " + command.getClass().getName() + " could not run", failure);
            throw new IllegalStateException(failure);
        }
    }

    private static Object run(Object command, Object context) throws Exception {
        Object source = context.getClass().getMethod("getSource").invoke(context);
        String line = "";
        try {
            line = (String) context.getClass().getMethod("getArgument", String.class, Class.class)
                    .invoke(context, "args", String.class);
        } catch (java.lang.reflect.InvocationTargetException noArguments) {
            // The bare command name was typed.
        }
        String[] args = line.isBlank() ? new String[0] : line.trim().split(" +");
        Object sender = sender(source);
        Object server = fieldOfType(source, "net.minecraft.server.MinecraftServer");
        Method execute = executeMethod(command);
        try {
            execute.setAccessible(true);
            execute.invoke(command, server, sender, args);
            return 1;
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof LegacyCommandException failed) {
                message(sender, "\u00a7c" + failed.getMessage());
                return 0;
            }
            LegacyForgeEvents.warn("command " + command.getClass().getName() + " failed", cause);
            message(sender, "\u00a7cThis command failed: " + cause);
            return 0;
        }
    }

    /** The 1.12 {@code execute(MinecraftServer, ICommandSender, String[])}, found by its shape. */
    private static Method executeMethod(Object command) throws NoSuchMethodException {
        for (Class<?> type = command.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 3 && p[2] == String[].class && method.getReturnType() == void.class
                        && !Modifier.isStatic(method.getModifiers())) {
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(command.getClass().getName() + ".execute");
    }

    private static int permissionLevel(Object command) {
        Object level = call(command, Object.class, "func_82362_a", "getRequiredPermissionLevel");
        return level instanceof Integer number ? number : 4;
    }

    /** {@code CommandSourceStack.hasPermission(int)}, the only method of that shape. */
    private static boolean hasPermission(Object source, int level) {
        for (Method method : source.getClass().getMethods()) {
            if (method.getReturnType() == boolean.class && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class) {
                try {
                    return (Boolean) method.invoke(source, level);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            }
        }
        return level <= 0;
    }

    /** The player or server behind a command, the object 1.12 called the sender. */
    private static Object sender(Object source) {
        Object sender = fieldOfType(source, "net.minecraft.commands.CommandSource");
        return sender != null ? sender : fieldOfType(source, "net.minecraft.server.MinecraftServer");
    }

    static boolean isPlayer(Object sender) {
        for (Class<?> type = sender == null ? null : sender.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("net.minecraft.world.entity.player.Player")) return true;
        }
        return false;
    }

    /** An online player by name, through the server's player list. */
    static Object onlinePlayer(Object server, String name) {
        try {
            Object players = fieldOfType(server, "net.minecraft.server.players.PlayerList");
            if (players == null) return null;
            for (Field field : players.getClass().getSuperclass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                for (Object player : (List<?>) field.get(players)) {
                    if (isPlayer(player) && name.equalsIgnoreCase(senderName(player))) return player;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------ old ICommandSender methods

    /** {@code sender.sendMessage(component)} or an error line, through {@code sendSystemMessage}. */
    static void message(Object sender, Object textOrComponent) {
        try {
            Class<?> component = Class.forName("net.minecraft.network.chat.Component", false, sender.getClass().getClassLoader());
            Object value = component.isInstance(textOrComponent) ? textOrComponent : translatable(component,
                    String.valueOf(textOrComponent));
            for (Method method : interfaceMethods(sender, "net.minecraft.commands.CommandSource")) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == component
                        && method.getReturnType() == void.class) {
                    method.invoke(sender, value);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A sender that cannot receive messages, such as a command block that is gone.
        }
    }

    /** {@code getName()}: a player's name, or "Server". */
    public static String senderName(Object sender) {
        if (!isPlayer(sender)) return "Server";
        try {
            Object profile = null;
            for (Method method : sender.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType().getName().equals("com.mojang.authlib.GameProfile")) {
                    profile = method.invoke(sender);
                    break;
                }
            }
            for (Method method : profile.getClass().getMethods()) {
                if ((method.getName().equals("getName") || method.getName().equals("name"))
                        && method.getParameterCount() == 0) {
                    return (String) method.invoke(profile);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "Player";
        }
        return "Player";
    }

    /** {@code sendMessage(component)}. */
    public static void sendMessage(Object sender, Object component) {
        message(sender, component);
    }

    /** {@code getCommandSenderEntity()}: the sender when it is an entity. */
    public static Object senderEntity(Object sender) {
        for (Class<?> type = sender == null ? null : sender.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("net.minecraft.world.entity.Entity")) return sender;
        }
        return null;
    }

    /** {@code getServer()}: the server, from the sender or its level. */
    public static Object senderServer(Object sender) {
        if (sender != null && sender.getClass().getName().contains("Server") && !isPlayer(sender)) return sender;
        Object level = senderLevel(sender);
        return level == null ? null : fieldOfType(level, "net.minecraft.server.MinecraftServer");
    }

    /** {@code getEntityWorld()}: the entity's level, or the overworld for the server. */
    public static Object senderLevel(Object sender) {
        Object entity = senderEntity(sender);
        if (entity != null) return fieldOfType(entity, "net.minecraft.world.level.Level");
        try {
            for (Method method : sender.getClass().getMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType().getName().equals("net.minecraft.server.level.ServerLevel")) {
                    return method.invoke(sender);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** {@code canUseCommand(level, name)}: operators for players, always for the server. */
    public static boolean canUseCommand(Object sender, int level, Object name) {
        if (!isPlayer(sender)) return true;
        Object server = senderServer(sender);
        if (server == null) return level <= 0;
        try {
            Object profile = null;
            for (Method method : sender.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType().getName().equals("com.mojang.authlib.GameProfile")) {
                    profile = method.invoke(sender);
                }
            }
            for (Method method : server.getClass().getMethods()) {
                if (method.getReturnType() == int.class && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].getName().equals("com.mojang.authlib.GameProfile")) {
                    return (Integer) method.invoke(server, profile) >= level;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        return false;
    }

    private static Object translatable(Class<?> component, String text) throws ReflectiveOperationException {
        // Shown as the text itself when it is not a translation key, which is what 1.12 showed.
        for (Method method : component.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class && component.isAssignableFrom(method.getReturnType())) {
                return method.invoke(null, text);
            }
        }
        throw new NoSuchMethodException("Component(String)");
    }

    private static List<Method> interfaceMethods(Object target, String interfaceName) {
        List<Method> out = new ArrayList<>();
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) collect(iface, interfaceName, out);
        }
        return out;
    }

    private static void collect(Class<?> iface, String wanted, List<Method> out) {
        if (iface.getName().equals(wanted)) {
            out.addAll(List.of(iface.getMethods()));
            return;
        }
        for (Class<?> parent : iface.getInterfaces()) collect(parent, wanted, out);
    }

    private static Object fieldOfType(Object owner, String typeName) {
        for (Class<?> type = owner == null ? null : owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!assignableTo(field.getType(), typeName)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null) return value;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Try the next field of that type.
                }
            }
        }
        return null;
    }

    private static boolean assignableTo(Class<?> type, String name) {
        if (type.getName().equals(name)) return true;
        for (Class<?> iface : type.getInterfaces()) if (assignableTo(iface, name)) return true;
        return type.getSuperclass() != null && assignableTo(type.getSuperclass(), name);
    }

    private static Object call(Object target, Class<?> ignored, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException next) {
                // Try the other spelling.
            }
        }
        return null;
    }

    private static Object defaultObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Retromod legacy command";
            default -> null;
        };
    }
}
