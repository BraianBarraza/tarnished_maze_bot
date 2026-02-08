package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import de.dreamcube.mazegame.client.maze.Bait;
import lombok.Getter;
import lombok.Setter;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maintains the dynamic state of the bot including active baits, current target, and planned path.
 *
 * <p>This class serves as a shared data container between the strategy logic, control panel UI,
 * and visualization components. All fields are designed for safe concurrent access since UI
 * components and strategy callbacks may execute on different threads.</p>
 *
 * <p>Thread safety:</p>
 * <ul>
 *     <li>Primitive and reference fields are marked volatile for visibility across threads</li>
 *     <li>The bait list uses {@link CopyOnWriteArrayList} for thread-safe iteration</li>
 *     <li>Immutable objects ({@link List}, {@link Bait}) are used where possible</li>
 * </ul>
 */
public final class WorldState {

    @Setter
    @Getter
    private volatile boolean paused;

    @Setter
    @Getter
    private volatile boolean avoidCollisions;

    private final CopyOnWriteArrayList<Bait> activeBaits = new CopyOnWriteArrayList<>();

    @Getter
    private volatile Bait currentTarget;

    @Getter
    private volatile double currentTargetScoreValue = Double.NEGATIVE_INFINITY;

    @Getter
    private volatile List<Point> currentPath = List.of();

    /**
     * Returns a thread-safe view of all currently active baits in the maze.
     *
     * <p>The returned list is backed by a {@link CopyOnWriteArrayList}, which means:
     * <ul>
     *     <li>Iteration never throws {@link java.util.ConcurrentModificationException}</li>
     *     <li>Modifications from other threads may not be visible during iteration</li>
     *     <li>The list reflects a snapshot at the time iteration began</li>
     * </ul>
     *
     * @return a thread-safe list of active baits
     */
    public List<Bait> getActiveBaits() {
        return activeBaits;
    }

    /**
     * Adds a bait to the active baits list if it is not already present.
     *
     * <p>This method is idempotent; adding the same bait multiple times has no effect
     * beyond the first addition.</p>
     *
     * @param bait the bait to add, ignored if null
     */
    public void addBait(Bait bait) {
        if (bait != null) {
            activeBaits.addIfAbsent(bait);
        }
    }

    /**
     * Removes a bait from the active baits list.
     *
     * @param bait the bait to remove, ignored if null
     */
    public void removeBait(Bait bait) {
        if (bait != null) {
            activeBaits.remove(bait);
        }
    }

    /**
     * Sets the current target bait and its associated score value.
     *
     * <p>Both parameters are updated atomically from the perspective of other threads
     * due to the volatile declarations, though there is no guarantee they will be
     * read together.</p>
     *
     * @param target the new target bait, or null to clear the target
     * @param scoreValue the calculated score for this target
     */
    public void setCurrentTarget(Bait target, double scoreValue) {
        this.currentTarget = target;
        this.currentTargetScoreValue = scoreValue;
    }

    /**
     * Clears the current target if it matches the specified bait.
     *
     * <p>This method is typically called when a bait vanishes to ensure the bot
     * does not continue pursuing a target that no longer exists.</p>
     *
     * @param bait the bait to check against the current target
     */
    public void clearTargetIfEquals(Bait bait) {
        Bait target = this.currentTarget;
        if (target != null && target.equals(bait)) {
            setCurrentTarget(null, Double.NEGATIVE_INFINITY);
        }
    }

    /**
     * Sets the planned path to the current target.
     *
     * <p>The path is represented as an ordered list of maze cells from the bot's
     * current position to the target position.</p>
     *
     * @param path the new path, or null to clear the path (stored as empty list)
     */
    public void setCurrentPath(List<Point> path) {
        this.currentPath = (path == null) ? List.of() : path;
    }
}