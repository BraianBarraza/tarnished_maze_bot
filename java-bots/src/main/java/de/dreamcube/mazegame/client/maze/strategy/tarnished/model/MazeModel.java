package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * Represents the static structure of the maze as a grid of walkable and non-walkable cells.
 *
 * <p>The maze is received from the server as a list of text lines, where each character
 * represents a cell. Currently, only the dot character ('.') is considered walkable;
 * all other characters represent obstacles or walls.</p>
 *
 * <p>Coordinate system:</p>
 * <ul>
 *     <li>x: horizontal coordinate, increasing from left to right (0 to width-1)</li>
 *     <li>y: vertical coordinate, increasing from top to bottom (0 to height-1)</li>
 * </ul>
 *
 * <p>This class is not thread-safe. If accessed from multiple threads, external
 * synchronization is required.</p>
 */
public final class MazeModel {

    @Getter
    private int width;

    @Getter
    private int height;

    private boolean[][] walkable;

    /**
     * Checks if the maze has been initialized with valid dimensions.
     *
     * @return true if the maze has been received and has positive dimensions
     */
    public boolean hasMaze() {
        return walkable != null && width > 0 && height > 0;
    }

    /**
     * Checks if the given coordinates lie within the maze boundaries.
     *
     * @param x the x-coordinate to check
     * @param y the y-coordinate to check
     * @return true if both coordinates are non-negative and within bounds
     */
    public boolean isWithinBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /**
     * Checks if a cell can be traversed by the bot.
     *
     * <p>A cell is walkable if it is within bounds, the maze has been initialized,
     * and the cell is marked as walkable in the grid.</p>
     *
     * @param x the x-coordinate of the cell
     * @param y the y-coordinate of the cell
     * @return true if the cell is walkable, false otherwise
     */
    public boolean isWalkable(int x, int y) {
        return isWithinBounds(x, y) && walkable != null && walkable[x][y];
    }

    /**
     * Updates the maze structure from server-provided maze lines.
     *
     * <p>Each line represents a row of the maze from top to bottom. Within each line,
     * characters are processed from left to right. A dot ('.') indicates a walkable
     * cell; any other character indicates an obstacle.</p>
     *
     * <p>If the input is invalid (non-positive dimensions or null lines), the maze
     * is cleared to an uninitialized state.</p>
     *
     * @param width the horizontal size of the maze
     * @param height the vertical size of the maze
     * @param lines the maze structure, one string per row
     */
    public void updateFromMaze(int width, int height, List<String> lines) {
        if (width <= 0 || height <= 0 || lines == null) {
            clearMaze();
            return;
        }

        this.width = width;
        this.height = height;

        boolean[][] newWalkable = new boolean[width][height];

        int rowCount = Math.min(height, lines.size());
        for (int y = 0; y < rowCount; y++) {
            String line = Objects.toString(lines.get(y), "");
            int columnCount = Math.min(width, line.length());

            for (int x = 0; x < columnCount; x++) {
                newWalkable[x][y] = (line.charAt(x) == '.');
            }
        }

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