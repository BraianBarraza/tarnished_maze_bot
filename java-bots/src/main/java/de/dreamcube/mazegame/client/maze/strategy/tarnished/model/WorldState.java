package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import de.dreamcube.mazegame.client.maze.Bait;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintains the dynamic state of the bot including active baits, current target, and planned path.
 *
 * <p>This class is shared between strategy logic, control panel UI, and visualization components.
 * All access is synchronized to provide consistent cross-thread visibility and to make compound
 * updates atomic.</p>
 */
public final class WorldState {

    private boolean paused;
    private boolean avoidCollisions;

    private final List<Bait> activeBaits = new ArrayList<>();

    private Bait currentTarget;
    private double currentTargetScoreValue = Double.NEGATIVE_INFINITY;
    private List<Point> currentPath = List.of();

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized void setPaused(boolean paused) {
        this.paused = paused;
    }

    public synchronized boolean isAvoidCollisions() {
        return avoidCollisions;
    }

    public synchronized void setAvoidCollisions(boolean avoidCollisions) {
        this.avoidCollisions = avoidCollisions;
    }

    public synchronized Bait getCurrentTarget() {
        return currentTarget;
    }

    public synchronized double getCurrentTargetScoreValue() {
        return currentTargetScoreValue;
    }

    public synchronized List<Point> getCurrentPath() {
        return List.copyOf(currentPath);
    }

    /**
     * Returns a stable snapshot copy of all active baits.
     *
     * @return an immutable snapshot copy of the current bait list
     */
    public synchronized List<Bait> getActiveBaitsSnapshot() {
        return List.copyOf(activeBaits);
    }

    /**
     * Adds a bait to the active bait list if it is not already present.
     *
     * @param bait the bait to add, ignored if null
     */
    public synchronized void addBait(Bait bait) {
        if (bait != null && !activeBaits.contains(bait)) {
            activeBaits.add(bait);
        }
    }

    /**
     * Removes a bait from the active bait list.
     *
     * @param bait the bait to remove, ignored if null
     */
    public synchronized void removeBait(Bait bait) {
        if (bait != null) {
            activeBaits.remove(bait);
        }
    }

    /**
     * Sets the current target bait and score atomically.
     *
     * @param target the new target bait, or null to clear
     * @param scoreValue the score associated with the target
     */
    public synchronized void setCurrentTarget(Bait target, double scoreValue) {
        this.currentTarget = target;
        this.currentTargetScoreValue = scoreValue;
    }

    /**
     * Clears the current target if it matches the specified bait.
     *
     * @param bait the bait to compare with the current target
     */
    public synchronized void clearTargetIfEquals(Bait bait) {
        if (currentTarget != null && currentTarget.equals(bait)) {
            setCurrentTarget(null, Double.NEGATIVE_INFINITY);
        }
    }

    /**
     * Sets the currently planned path. A defensive immutable copy is stored.
     *
     * @param path the path to store, or null to clear
     */
    public synchronized void setCurrentPath(List<Point> path) {
        this.currentPath = (path == null) ? List.of() : List.copyOf(path);
    }

    /**
     * Returns a full immutable snapshot for UI consumers.
     *
     * @return a snapshot of the currently visible bot state
     */
    public synchronized Snapshot getSnapshot() {
        return new Snapshot(
                paused,
                avoidCollisions,
                currentTarget,
                currentTargetScoreValue,
                List.copyOf(currentPath)
        );
    }

    /**
     * Immutable snapshot of world state intended for UI rendering.
     *
     * @param paused whether the bot is paused
     * @param avoidCollisions whether collision avoidance is enabled
     * @param currentTarget the current target bait, may be null
     * @param currentTargetScoreValue the score of the current target
     * @param currentPath the current planned path
     */
    public record Snapshot(
            boolean paused,
            boolean avoidCollisions,
            Bait currentTarget,
            double currentTargetScoreValue,
            List<Point> currentPath
    ) {
    }
}