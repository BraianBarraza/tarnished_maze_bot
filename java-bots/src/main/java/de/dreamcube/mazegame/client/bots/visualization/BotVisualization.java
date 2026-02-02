package de.dreamcube.mazegame.client.bots.visualization;

import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Visualizes bot-related information on top of the maze.
 *
 * Requirements covered (Projekt.pdf):
 * - Draws something relative to the maze using {@code offset}.
 * - Reacts to zoom using {@code zoom}.
 * - Has a clear relation to the game: highlights the current bot target (goal) and optionally the planned path.
 *
 * How to use from your Strategy:
 * - Keep one instance of BotVisualization in your Strategy.
 * - Return it via Strategy#getVisualizationComponent().
 * - Whenever you compute a new target/path, call setTarget(...) / setPlannedPath(...).
 */

public class BotVisualization extends VisualizationComponent {

    /**
     * Target cell in maze coordinates. If null, nothing is highlighted.
     */
    private volatile @Nullable Point targetCell;

    /**
     * Planned path in maze coordinates (optional).
     * The first entry should usually be the current position, last entry the target.
     */
    private volatile @NotNull List<Point> plannedPath = Collections.emptyList();

    /**
     * Optional small info text rendered near the target.
     */
    private volatile @Nullable String targetLabel;

    /**
     * Sets the current target to be highlighted.
     *
     * @param x maze x coordinate
     * @param y maze y coordinate
     * @param label optional label (e.g. "GEM", "utility=123.4", ...)
     */
    public void setTarget(int x, int y, @Nullable String label) {
        this.targetCell = new Point(x, y);
        this.targetLabel = label;
        repaint(); // request redraw
    }

    /**
     * Clears the current target highlight.
     */
    public void clearTarget() {
        this.targetCell = null;
        this.targetLabel = null;
        repaint();
    }

    /**
     * Sets a path to be drawn (maze coordinates).
     * The list is copied to avoid concurrent modifications.
     *
     * @param path list of maze cells, may be null/empty
     */
    public void setPlannedPath(@Nullable List<Point> path) {
        if (path == null || path.isEmpty()) {
            this.plannedPath = Collections.emptyList();
        } else {
            // defensive copy (also ensures paint is stable if strategy modifies the list)
            this.plannedPath = Collections.unmodifiableList(new ArrayList<>(path));
        }
        repaint();
    }

    @Override
    protected void paintComponent(@NotNull Graphics g) {
        super.paintComponent(g);

        if (!getVisualizationEnabled()) {
            return;
        }

        final Point target = this.targetCell;
        final List<Point> path = this.plannedPath;

        if (target == null && path.isEmpty()) {
            return;
        }

        final int cellSize = Math.max(2, getZoom());
        final Point off = getOffset();

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Integer selectedPlayerId = getSelectedPlayerId();
            Color base = (selectedPlayerId != null) ? getPlayerColor(selectedPlayerId) : null;
            if (base == null) base = Color.MAGENTA;

            // 1) Draw path (optional)
            if (path.size() >= 2) {
                Color pathColor = withAlpha(base, 120);
                g2.setColor(pathColor);

                float stroke = Math.max(1f, cellSize / 6f);
                g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                Point prev = path.get(0);
                for (int i = 1; i < path.size(); i++) {
                    Point cur = path.get(i);

                    int x1 = toScreenCenterX(prev.x, off.x, cellSize);
                    int y1 = toScreenCenterY(prev.y, off.y, cellSize);
                    int x2 = toScreenCenterX(cur.x, off.x, cellSize);
                    int y2 = toScreenCenterY(cur.y, off.y, cellSize);

                    g2.drawLine(x1, y1, x2, y2);
                    prev = cur;
                }
            }

            // 2) Draw target highlight (required for the grading)
            if (target != null) {
                int sx = toScreenX(target.x, off.x, cellSize);
                int sy = toScreenY(target.y, off.y, cellSize);

                // outer ring
                g2.setColor(base);
                float ringStroke = Math.max(2f, cellSize / 4f);
                g2.setStroke(new BasicStroke(ringStroke));
                g2.drawOval(sx, sy, cellSize, cellSize);

                // crosshair
                int cx = sx + cellSize / 2;
                int cy = sy + cellSize / 2;
                int r = Math.max(2, cellSize / 2);

                g2.setStroke(new BasicStroke(Math.max(1f, ringStroke / 2f)));
                g2.drawLine(cx - r, cy, cx + r, cy);
                g2.drawLine(cx, cy - r, cx, cy + r);

                // label (optional)
                if (targetLabel != null && !targetLabel.isBlank()) {
                    g2.setFont(g2.getFont().deriveFont(Math.max(10f, cellSize * 0.8f)));
                    g2.setColor(withAlpha(Color.BLACK, 160));
                    g2.fillRoundRect(sx + cellSize + 4, sy, textWidth(g2, targetLabel) + 10, cellSize, 8, 8);

                    g2.setColor(Color.WHITE);
                    g2.drawString(targetLabel, sx + cellSize + 9, sy + (cellSize * 3 / 4));
                }
            }
        } finally {
            g2.dispose();
        }
    }

    // === Coordinate mapping: maze (x,y) -> screen (px,py) using offset + zoom ===

    private static int toScreenX(int mazeX, int offsetX, int cellSize) {
        return offsetX + (mazeX * cellSize);
    }

    private static int toScreenY(int mazeY, int offsetY, int cellSize) {
        return offsetY + (mazeY * cellSize);
    }

    private static int toScreenCenterX(int mazeX, int offsetX, int cellSize) {
        return toScreenX(mazeX, offsetX, cellSize) + cellSize / 2;
    }

    private static int toScreenCenterY(int mazeY, int offsetY, int cellSize) {
        return toScreenY(mazeY, offsetY, cellSize) + cellSize / 2;
    }

    private static Color withAlpha(Color c, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static int textWidth(Graphics2D g2, String text) {
        FontMetrics fm = g2.getFontMetrics();
        return fm.stringWidth(text);
    }
}
