package de.dreamcube.mazegame.client.maze.strategy.tarnished.model;

import java.util.List;

/**
 * Stores the maze grid for pathfinding.
 */
public class MazeModel {

    public int width;
    public int height;
    public boolean[][] walkable;

    public MazeModel() {
    }

    /**
     * Updates the maze grid from server-provided lines.
     * Walkable: '.', everything else is treated as blocked.
     */
    public void updateFromMaze(int width, int height, List<String> lines) {
        this.width = width;
        this.height = height;
        this.walkable = new boolean[width][height];

        for (int rowIndex = 0; rowIndex < height; rowIndex++) {
            String line = lines.get(rowIndex);
            for (int columnIndex = 0; columnIndex < width; columnIndex++) {
                char cellChar = line.charAt(columnIndex);
                walkable[columnIndex][rowIndex] = (cellChar == '.');
            }
        }
    }
}
