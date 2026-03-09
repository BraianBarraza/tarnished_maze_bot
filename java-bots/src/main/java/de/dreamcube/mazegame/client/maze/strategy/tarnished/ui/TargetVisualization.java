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
 * Renders visual overlays on the maze to show the bot's current target and planned path.
 *
 * <p>This visualization component draws:</p>
 * <ul>
 *     <li>A circular marker around the current target bait</li>
 *     <li>A line path showing the planned route from the bot to the target</li>
 * </ul>
 *
 * <p>All rendering respects the maze's current zoom level and offset, ensuring the overlay
 * moves and scales correctly with the maze view.</p>
 */
public final class TargetVisualization extends VisualizationComponent {

    private static final Color MARKER_COLOR = new Color(255, 0, 0, 200);
    private static final Color PATH_COLOR = new Color(255, 0, 0, 120);

    private final WorldState worldState;

    /**
     * Constructs a new target visualization bound to the given world state.
     *
     * @param worldState the world state containing target and path information
     */
    public TargetVisualization(WorldState worldState) {
        this.worldState = worldState;
    }

    /**
     * Paints the visualization overlay on the maze.
     *
     * @param graphics the graphics context to draw on
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);

        if (!getVisualizationEnabled()) {
            return;
        }

        WorldState.Snapshot snapshot = worldState.getSnapshot();
        Bait currentTarget = snapshot.currentTarget();
        if (currentTarget == null) {
            return;
        }

        int cellPixelSize = Math.max(1, getZoom());
        Point offset = getOffset();

        Graphics2D graphics2D = (Graphics2D) graphics;
        drawPlannedPath(graphics2D, snapshot.currentPath(), offset, cellPixelSize);
        drawTargetMarker(graphics2D, currentTarget, offset, cellPixelSize);
    }

    /**
     * Draws a circular marker around the current target bait.
     *
     * @param graphics2D the graphics context to draw on
     * @param target the target bait to mark
     * @param offset the maze rendering offset
     * @param cellPixelSize the size of one maze cell in pixels
     */
    private void drawTargetMarker(Graphics2D graphics2D, Bait target, Point offset, int cellPixelSize) {
        int pixelX = offset.x + target.getX() * cellPixelSize;
        int pixelY = offset.y + target.getY() * cellPixelSize;

        graphics2D.setColor(MARKER_COLOR);
        graphics2D.drawOval(pixelX, pixelY, cellPixelSize, cellPixelSize);
    }

    /**
     * Draws lines connecting the cells in the planned path.
     *
     * @param graphics2D the graphics context to draw on
     * @param path the path snapshot to draw
     * @param offset the maze rendering offset
     * @param cellPixelSize the size of one maze cell in pixels
     */
    private void drawPlannedPath(Graphics2D graphics2D, List<Point> path, Point offset, int cellPixelSize) {
        if (path.size() < 2) {
            return;
        }

        graphics2D.setColor(PATH_COLOR);
        int halfCell = cellPixelSize / 2;

        Point firstCell = path.get(0);
        int previousPixelX = offset.x + firstCell.x * cellPixelSize + halfCell;
        int previousPixelY = offset.y + firstCell.y * cellPixelSize + halfCell;

        for (int i = 1; i < path.size(); i++) {
            Point cell = path.get(i);
            int currentPixelX = offset.x + cell.x * cellPixelSize + halfCell;
            int currentPixelY = offset.y + cell.y * cellPixelSize + halfCell;

            graphics2D.drawLine(previousPixelX, previousPixelY, currentPixelX, currentPixelY);

            previousPixelX = currentPixelX;
            previousPixelY = currentPixelY;
        }
    }
}