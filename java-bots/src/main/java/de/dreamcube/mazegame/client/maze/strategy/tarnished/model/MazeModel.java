package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Represents the static maze layout using a compact one-dimensional BitSet.
 *
 * <p>Each cell is mapped to a linear index using {@code index = y * width + x}.
 * A set bit indicates that the corresponding cell is walkable.</p>
 *
 * <p>All public access is synchronized so that callbacks updating the maze and strategy logic
 * reading it cannot observe partially published state.</p>
 */
public final class MazeModel {

    private int width;
    private int height;
    private BitSet walkableCells;

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
     * Returns the total number of cells in the maze.
     *
     * @return width * height, or 0 if the maze is not initialized
     */
    public synchronized int getCellCount() {
        return width * height;
    }

    /**
     * Checks if the maze has been initialized with valid dimensions.
     *
     * @return true if the maze has been received and has positive dimensions
     */
    public synchronized boolean hasMaze() {
        return walkableCells != null && width > 0 && height > 0;
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
     * Converts two-dimensional maze coordinates to a linear index.
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @return the linear index
     * @throws IndexOutOfBoundsException if the coordinates are outside the maze bounds
     */
    public synchronized int toIndex(int x, int y) {
        if (!isWithinBounds(x, y)) {
            throw new IndexOutOfBoundsException(
                    "Coordinates out of bounds: (" + x + "," + y + ") for maze " + width + "x" + height
            );
        }
        return (y * width) + x;
    }

    /**
     * Checks if a cell can be traversed by the bot.
     *
     * @param x the x-coordinate of the cell
     * @param y the y-coordinate of the cell
     * @return true if the cell is walkable, false otherwise
     */
    public synchronized boolean isWalkable(int x, int y) {
        return isWithinBounds(x, y) && walkableCells != null && walkableCells.get((y * width) + x);
    }

    /**
     * Checks if a cell represented by its linear index is walkable.
     *
     * @param cellIndex the linear cell index
     * @return true if the indexed cell is walkable, false otherwise
     */
    public synchronized boolean isWalkableIndex(int cellIndex) {
        return walkableCells != null && cellIndex >= 0 && cellIndex < width * height && walkableCells.get(cellIndex);
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

        BitSet newWalkableCells = new BitSet(width * height);

        int rowCount = Math.min(height, lines.size());
        for (int y = 0; y < rowCount; y++) {
            String line = Objects.toString(lines.get(y), "");
            int columnCount = Math.min(width, line.length());

            for (int x = 0; x < columnCount; x++) {
                if (line.charAt(x) == '.') {
                    newWalkableCells.set((y * width) + x);
                }
            }
        }

        this.width = width;
        this.height = height;
        this.walkableCells = newWalkableCells;
    }

    /**
     * Resets the maze to an uninitialized state.
     */
    private void clearMaze() {
        width = 0;
        height = 0;
        walkableCells = null;
    }
}