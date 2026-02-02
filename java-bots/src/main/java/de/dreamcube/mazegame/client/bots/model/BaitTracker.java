package de.dreamcube.mazegame.client.bots.model;

import de.dreamcube.mazegame.client.maze.Bait;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static de.dreamcube.mazegame.client.maze.BaitKt.combineIntsToLong;

/**
 * Thread-safe bait storage keyed by position.
 */
public final class BaitTracker {

    private final Map<Long, Bait> baits = new ConcurrentHashMap<>();

    /**
     * Adds or updates a bait entry.
     */
    public void add(@NotNull Bait bait) {
        baits.put(combineIntsToLong(bait.getX(), bait.getY()), bait);
    }

    /**
     * Removes a bait entry.
     */
    public void remove(@NotNull Bait bait) {
        baits.remove(combineIntsToLong(bait.getX(), bait.getY()));
    }

    /**
     * Returns true when no baits are currently tracked.
     */
    public boolean isEmpty() {
        return baits.isEmpty();
    }

    /**
     * Returns a stable snapshot of all tracked baits.
     */
    public @NotNull Collection<Bait> snapshot() {
        return new ArrayList<>(baits.values());
    }

    /**
     * Refreshes the tracker from a given list.
     */
    public void refreshFromList(@NotNull Collection<Bait> list) {
        for (Bait bait : list) {
            add(bait);
        }
    }
}
