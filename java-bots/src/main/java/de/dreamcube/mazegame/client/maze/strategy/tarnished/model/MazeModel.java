package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * Stores a walkability grid of the maze for pathfinding.
 *
 * <p>The maze is received as a list of text lines. Currently, only '.' is considered walkable;
 * every other character is treated as blocked.</p>
 */
public final class MazeModel {

    @Getter
    private int width;
    @Getter
    private int height;
    private boolean[][] walkable;

    public boolean hasMaze() {
        return walkable != null && width > 0 && height > 0;
    }

    public boolean isWithinBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public boolean isWalkable(int x, int y) {
        return isWithinBounds(x, y) && walkable != null && walkable[x][y];
    }

    /**
     * Updates the maze grid from server-provided lines.
     *
     * @param width  maze width
     * @param height maze height
     * @param lines  maze rows (top to bottom)
     */
    public void updateFromMaze(int width, int height, List<String> lines) {
        if (width <= 0 || height <= 0 || lines == null) {
            clear();
            return;
        }

        this.width = width;
        this.height = height;

        boolean[][] nextWalkable = new boolean[width][height];

        int rowCount = Math.min(height, lines.size());
        for (int y = 0; y < rowCount; y++) {
            String line = Objects.toString(lines.get(y), "");
            int colCount = Math.min(width, line.length());
            for (int x = 0; x < colCount; x++) {
                nextWalkable[x][y] = (line.charAt(x) == '.');
            }
        }

        this.walkable = nextWalkable;
    }

    private void clear() {
        width = 0;
        height = 0;
        walkable = null;
    }
}
