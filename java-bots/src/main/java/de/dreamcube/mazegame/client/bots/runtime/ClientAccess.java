package de.dreamcube.mazegame.client.bots.runtime;

import de.dreamcube.mazegame.client.bots.model.BaitTracker;
import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reflection-based access to the current client state.
 */
public final class ClientAccess {

    /**
     * Reads the bot's own state using MazeClient accessors or fallbacks.
     */
    public @Nullable BotState readOwnState(@NotNull Strategy strategy, @NotNull String fallbackNick) {
        Object client = getMazeClient(strategy);
        if (client == null) return null;

        Object me =
                invokeNoArg(client, "getOwnPlayerSnapshot");
        if (me == null) me = invokeNoArg(client, "getOwnPlayerView");
        if (me == null) me = invokeNoArg(client, "getOwnPlayer");
        if (me == null) me = readField(client, "ownPlayer");
        if (me == null) me = invokeNoArg(client, "getMe");
        if (me != null) {
            BotState s = stateFromPlayerLike(me);
            if (s != null) return s;
        }

        Object playersObj = invokeNoArg(client, "getPlayers");
        if (playersObj instanceof List<?>) {
            List<?> players = (List<?>) playersObj;
            for (Object p : players) {
                String nick = readString(p, "getNick", "nick");
                if (nick != null && nick.equalsIgnoreCase(fallbackNick)) {
                    return stateFromPlayerLike(p);
                }
            }
            if (!players.isEmpty()) {
                return stateFromPlayerLike(players.get(0));
            }
        }

        return null;
    }

    /**
     * Refreshes bait tracker from client list if available.
     */
    public void refreshBaits(@NotNull Strategy strategy, @NotNull BaitTracker tracker) {
        Object client = getMazeClient(strategy);
        if (client == null) return;

        Object baitsObj = invokeNoArg(client, "getBaits");
        if (baitsObj instanceof List<?>) {
            for (Object o : (List<?>) baitsObj) {
                if (o instanceof Bait) {
                    tracker.add((Bait) o);
                }
            }
        }
    }

    private @Nullable BotState stateFromPlayerLike(@Nullable Object p) {
        if (p == null) return null;

        int x = readInt(p, "getX", "x");
        int y = readInt(p, "getY", "y");

        Object dirObj = invokeNoArg(p, "getViewDirection");
        ViewDirection dir = null;
        if (dirObj instanceof ViewDirection) dir = (ViewDirection) dirObj;

        if (dir == null) {
            Object dirField = readField(p, "viewDirection");
            if (dirField instanceof ViewDirection) dir = (ViewDirection) dirField;
        }

        if (dir == null) return null;
        return new BotState(x, y, dir);
    }

    private @Nullable Object getMazeClient(@NotNull Strategy strategy) {
        Class<?> c = strategy.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("mazeClient");
                f.setAccessible(true);
                return f.get(strategy);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable Object invokeNoArg(@Nullable Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable Object readField(@Nullable Object target, String fieldName) {
        if (target == null) return null;

        try {
            Field f = target.getClass().getField(fieldName);
            return f.get(target);
        } catch (Exception ignored) {
        }

        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception ignored) {
        }

        return null;
    }

    private static int readInt(@Nullable Object target, String getterName, String fieldName) {
        if (target == null) return 0;

        try {
            Method m = target.getClass().getMethod(getterName);
            Object v = m.invoke(target);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) {
        }

        try {
            Field f = target.getClass().getField(fieldName);
            Object v = f.get(target);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) {
        }

        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(target);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) {
        }

        return 0;
    }

    private static @Nullable String readString(@Nullable Object target, String getterName, String fieldName) {
        if (target == null) return null;

        try {
            Method m = target.getClass().getMethod(getterName);
            Object v = m.invoke(target);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {
        }

        try {
            Field f = target.getClass().getField(fieldName);
            Object v = f.get(target);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {
        }

        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(target);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {
        }

        return null;
    }
}
