package de.dreamcube.mazegame.client.maze.strategy.tarnished.ui;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.WorldState;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.List;

/**
 * Draws a marker for the current target bait.
 * The marker respects maze offset and zoom.
 */
public class TargetVisualization extends VisualizationComponent {

    private final WorldState worldState;

    public TargetVisualization(WorldState worldState) {
        this.worldState = worldState;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);

        if (!getVisualizationEnabled()) {
            return;
        }

        Bait currentTarget = worldState.currentTarget;
        if (currentTarget == null) {
            return;
        }

        int cellPixelSize = Math.max(1, getZoom());
        Point offset = getOffset();
        int pixelX = offset.x + currentTarget.getX() * cellPixelSize;
        int pixelY = offset.y + currentTarget.getY() * cellPixelSize;

        Graphics2D graphics2D = (Graphics2D) graphics;
        drawPath(graphics2D, offset, cellPixelSize);
        graphics2D.setColor(Color.RED);
        graphics2D.drawOval(pixelX, pixelY, cellPixelSize, cellPixelSize);
    }

    private void drawPath(Graphics2D graphics2D, Point offset, int cellPixelSize) {
        List<int[]> pathCells = worldState.currentPathCells;
        if (pathCells == null || pathCells.size() < 2) {
            return;
        }
        graphics2D.setColor(new Color(255, 0, 0, 140));
        int halfCell = cellPixelSize / 2;
        int[] firstCell = pathCells.get(0);
        int lastPixelX = offset.x + firstCell[0] * cellPixelSize + halfCell;
        int lastPixelY = offset.y + firstCell[1] * cellPixelSize + halfCell;
        for (int i = 1; i < pathCells.size(); i++) {
            int[] cell = pathCells.get(i);
            int pixelX = offset.x + cell[0] * cellPixelSize + halfCell;
            int pixelY = offset.y + cell[1] * cellPixelSize + halfCell;
            graphics2D.drawLine(lastPixelX, lastPixelY, pixelX, pixelY);
            lastPixelX = pixelX;
            lastPixelY = pixelY;
        }
    }
}
