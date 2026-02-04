package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import de.dreamcube.mazegame.client.maze.Bait;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds dynamic game state (baits, target, pause flag).
 */
public class WorldState {

    /**
     * When true, the bot should not issue movement commands.
     */
    public boolean paused;

    /**
     * Snapshot list of currently known baits.
     */
    public List<Bait> activeBaits = new ArrayList<>();

    /**
     * Currently selected target bait.
     */
    public Bait currentTarget;

    /**
     * Cached score value for the current target.
     */
    public double currentTargetScoreValue;

    /**
     * Cached path cells from the current player position to the target.
     * Each entry is an int array: [x, y].
     */
    public List<int[]> currentPathCells = new ArrayList<>();

    public WorldState() {
    }
}
