package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import de.dreamcube.mazegame.client.maze.Bait;
import lombok.Getter;
import lombok.Setter;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds dynamic bot state that is shared between strategy, control panel and visualization.
 *
 * <p>Note: UI (Swing) and strategy callbacks may run on different threads. The fields are therefore
 * kept simple and thread-visible (volatile) and the bait list is thread-safe.</p>
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

    public List<Bait> getActiveBaits() {
        return activeBaits;
    }

    public void addBait(Bait bait) {
        if (bait != null) {
            activeBaits.addIfAbsent(bait);
        }
    }

    public void removeBait(Bait bait) {
        if (bait != null) {
            activeBaits.remove(bait);
        }
    }

    public void setCurrentTarget(Bait target, double scoreValue) {
        this.currentTarget = target;
        this.currentTargetScoreValue = scoreValue;
    }

    public void clearTargetIfEquals(Bait bait) {
        Bait target = this.currentTarget;
        if (target != null && target.equals(bait)) {
            setCurrentTarget(null, Double.NEGATIVE_INFINITY);
        }
    }

    public void setCurrentPath(List<Point> path) {
        this.currentPath = (path == null) ? List.of() : path;
    }
}
