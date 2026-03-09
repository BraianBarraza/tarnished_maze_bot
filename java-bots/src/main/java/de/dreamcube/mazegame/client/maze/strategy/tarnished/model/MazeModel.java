package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import java.util.List;
import java.util.Objects;

/**
 * Represents the static structure of the maze as a grid of walkable and non-walkable cells.
 *
 * <p>The maze is received from the server as a list of text lines, where each character
 * represents a cell. Currently, only the dot character ('.') is considered walkable;
 * all other characters represent obstacles or walls.</p>
 *
 * <p>All public access is synchronized so that callbacks updating the maze and strategy logic
 * reading it cannot observe partially published state.</p>
 */
public final class MazeModel {

    private int width;
    private int height;
    private boolean[][] walkable;

    /**
     * Returns the current maze width.
     *
     * @return the maze width
     */
    public synchronized int getWidth() {
        return width;
    }

    /**
     * Returns the current maze height.
     *
     * @return the maze height
     */
    public synchronized int getHeight() {
        return height;
    }

    /**
     * Checks if the maze has been initialized with valid dimensions.
     *
     * @return true if the maze has been received and has positive dimensions
     */
    public synchronized boolean hasMaze() {
        return walkable != null && width > 0 && height > 0;
    }

    /**
     * Checks if the given coordinates lie within the maze boundaries.
     *
     * @param x the x-coordinate to check
     * @param y the y-coordinate to check
     * @return true if both coordinates are non-negative and within bounds
     */
    public synchronized boolean isWithinBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /**
     * Checks if a cell can be traversed by the bot.
     *
     * @param x the x-coordinate of the cell
     * @param y the y-coordinate of the cell
     * @return true if the cell is walkable, false otherwise
     */
    public synchronized boolean isWalkable(int x, int y) {
        return isWithinBounds(x, y) && walkable != null && walkable[x][y];
    }

    /**
     * Updates the maze structure from server-provided maze lines.
     *
     * @param width the horizontal size of the maze
     * @param height the vertical size of the maze
     * @param lines the maze structure, one string per row
     */
    public synchronized void updateFromMaze(int width, int height, List<String> lines) {
        if (width <= 0 || height <= 0 || lines == null) {
            clearMaze();
            return;
        }

        boolean[][] newWalkable = new boolean[width][height];

        int rowCount = Math.min(height, lines.size());
        for (int y = 0; y < rowCount; y++) {
            String line = Objects.toString(lines.get(y), "");
            int columnCount = Math.min(width, line.length());

            for (int x = 0; x < columnCount; x++) {
                newWalkable[x][y] = (line.charAt(x) == '.');
            }
        }

        this.width = width;
        this.height = height;
        this.walkable = newWalkable;
    }

    /**
     * Resets the maze to an uninitialized state.
     */
    private void clearMaze() {
        width = 0;
        height = 0;
        walkable = null;
    }
}