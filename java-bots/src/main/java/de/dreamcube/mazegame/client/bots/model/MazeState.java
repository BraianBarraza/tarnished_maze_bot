package de.dreamcube.mazegame.client.bots.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Immutable-sized maze snapshot with a walkable grid.
 */
public final class MazeState {

    private int width;
    private int height;
    private boolean[] walkable;

    /**
     * Updates the maze grid from server lines.
     *
     * @param width maze width
     * @param height maze height
     * @param mazeLines raw maze lines
     */
    public void updateFromMazeLines(int width, int height, @NotNull List<String> mazeLines) {
        this.width = width;
        this.height = height;

        boolean[] w = new boolean[width * height];

        for (int y = 0; y < height && y < mazeLines.size(); y++) {
            String line = mazeLines.get(y);
            if (line == null) continue;

            int perCell = 1;
            if (width > 0 && line.length() >= width && line.length() % width == 0) {
                perCell = Math.max(1, line.length() / width);
            }

            for (int x = 0; x < width; x++) {
                int idx = x * perCell;
                char c = (idx < line.length()) ? line.charAt(idx) : '#';
                w[y * width + x] = !isBlockedChar(c);
            }
        }

        this.walkable = w;
    }

    /**
     * Returns true when a valid maze grid is available.
     */
    public boolean isReady() {
        return walkable != null && width > 0 && height > 0;
    }

    /**
     * Checks if a cell is inside bounds.
     */
    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /**
     * Checks if a cell is walkable.
     */
    public boolean isWalkable(int x, int y) {
        return inBounds(x, y) && walkable[y * width + x];
    }

    /**
     * Returns the internal walkable array.
     */
    public boolean[] walkableGrid() {
        return walkable;
    }

    /**
     * Returns maze width.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns maze height.
     */
    public int getHeight() {
        return height;
    }

    private static boolean isBlockedChar(char c) {
        return c == '#'
                || c == 'X'
                || c == 'W'
                || c == '█'
                || c == '■'
                || c == '?'
                || c == 'O'
                || c == 'o';
    }
}
