package de.dreamcube.mazegame.client.bots.runtime;

import de.dreamcube.mazegame.common.maze.ViewDirection;

/**
 * Immutable bot position + view direction.
 */
public final class BotState {

    private final int x;
    private final int y;
    private final ViewDirection direction;

    /**
     * Creates a new bot state.
     */
    public BotState(int x, int y, ViewDirection direction) {
        this.x = x;
        this.y = y;
        this.direction = direction;
    }

    /**
     * Returns the x coordinate.
     */
    public int x() {
        return x;
    }

    /**
     * Returns the y coordinate.
     */
    public int y() {
        return y;
    }

    /**
     * Returns the view direction.
     */
    public ViewDirection direction() {
        return direction;
    }
}
