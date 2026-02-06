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
 * Draws a marker for the current target bait and (optionally) the planned path.
 * The marker respects maze offset and zoom.
 */
public final class TargetVisualization extends VisualizationComponent {

    private static final Color MARKER_COLOR = new Color(255, 0, 0, 200);
    private static final Color PATH_COLOR = new Color(255, 0, 0, 120);

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

        Bait currentTarget = worldState.getCurrentTarget();
        if (currentTarget == null) {
            return;
        }

        int cellPixelSize = Math.max(1, getZoom());
        Point offset = getOffset();
        int pixelX = offset.x + currentTarget.getX() * cellPixelSize;
        int pixelY = offset.y + currentTarget.getY() * cellPixelSize;

        Graphics2D graphics2D = (Graphics2D) graphics;
        drawPath(graphics2D, offset, cellPixelSize);

        graphics2D.setColor(MARKER_COLOR);
        graphics2D.drawOval(pixelX, pixelY, cellPixelSize, cellPixelSize);
    }

    private void drawPath(Graphics2D graphics2D, Point offset, int cellPixelSize) {
        List<Point> path = worldState.getCurrentPath();
        if (path == null || path.size() < 2) {
            return;
        }

        graphics2D.setColor(PATH_COLOR);
        int halfCell = cellPixelSize / 2;

        Point first = path.getFirst();
        int lastPixelX = offset.x + first.x * cellPixelSize + halfCell;
        int lastPixelY = offset.y + first.y * cellPixelSize + halfCell;

        for (int i = 1; i < path.size(); i++) {
            Point cell = path.get(i);
            int pixelX = offset.x + cell.x * cellPixelSize + halfCell;
            int pixelY = offset.y + cell.y * cellPixelSize + halfCell;
            graphics2D.drawLine(lastPixelX, lastPixelY, pixelX, pixelY);
            lastPixelX = pixelX;
            lastPixelY = pixelY;
        }
    }
}
