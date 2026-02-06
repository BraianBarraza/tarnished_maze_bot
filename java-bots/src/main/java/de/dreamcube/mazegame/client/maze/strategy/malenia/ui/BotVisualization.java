package de.dreamcube.mazegame.client.maze.strategy.malenia.ui;

import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Visualizes bot-related information on top of the maze.
 */
public final class BotVisualization extends VisualizationComponent {

    /** Target cell in maze coordinates. If null, nothing is highlighted. */
    private volatile @Nullable Point targetCell;

    /** Planned path in maze coordinates (optional). */
    private volatile @NotNull List<Point> plannedPath = Collections.emptyList();

    /** Optional label rendered near the target. */
    private volatile @Nullable String targetLabel;

    /** Sets the current target to be highlighted. */
    public void setTarget(int x, int y, @Nullable String label) {
        this.targetCell = new Point(x, y);
        this.targetLabel = label;
        repaint();
    }

    /** Clears the current target highlight. */
    public void clearTarget() {
        this.targetCell = null;
        this.targetLabel = null;
        repaint();
    }

    /** Sets a path to be drawn (maze coordinates). The list is copied defensively. */
    public void setPlannedPath(@Nullable List<Point> path) {
        if (path == null || path.isEmpty()) {
            this.plannedPath = Collections.emptyList();
        } else {
            this.plannedPath = Collections.unmodifiableList(new ArrayList<>(path));
        }
        repaint();
    }

    @Override
    protected void paintComponent(@NotNull Graphics g) {
        super.paintComponent(g);

        if (!getVisualizationEnabled()) return;

        final Point target = this.targetCell;
        final List<Point> path = this.plannedPath;
        if (target == null && path.isEmpty()) return;

        final int cellSize = Math.max(2, getZoom());
        final Point off = getOffset();

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Integer selectedPlayerId = getSelectedPlayerId();
            Color base = (selectedPlayerId != null) ? getPlayerColor(selectedPlayerId) : null;
            if (base == null) base = Color.MAGENTA;

            // 1) Path
            if (path.size() >= 2) {
                g2.setColor(withAlpha(base, 120));
                float stroke = Math.max(1f, cellSize / 6f);
                g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                Point prev = path.get(0);
                for (int i = 1; i < path.size(); i++) {
                    Point cur = path.get(i);
                    g2.drawLine(
                            toScreenCenterX(prev.x, off.x, cellSize),
                            toScreenCenterY(prev.y, off.y, cellSize),
                            toScreenCenterX(cur.x, off.x, cellSize),
                            toScreenCenterY(cur.y, off.y, cellSize)
                    );
                    prev = cur;
                }
            }

            // 2) Target
            if (target != null) {
                int sx = toScreenX(target.x, off.x, cellSize);
                int sy = toScreenY(target.y, off.y, cellSize);

                g2.setColor(base);
                float ringStroke = Math.max(2f, cellSize / 4f);
                g2.setStroke(new BasicStroke(ringStroke));
                g2.drawOval(sx, sy, cellSize, cellSize);

                int cx = sx + cellSize / 2;
                int cy = sy + cellSize / 2;
                int r = Math.max(2, cellSize / 2);

                g2.setStroke(new BasicStroke(Math.max(1f, ringStroke / 2f)));
                g2.drawLine(cx - r, cy, cx + r, cy);
                g2.drawLine(cx, cy - r, cx, cy + r);

                String label = this.targetLabel;
                if (label != null && !label.isBlank()) {
                    g2.setColor(withAlpha(Color.BLACK, 160));
                    g2.fillRoundRect(sx + cellSize + 2, sy - 2,
                            Math.max(30, label.length() * 7 + 10), cellSize + 4, 8, 8);

                    g2.setColor(Color.WHITE);
                    g2.drawString(label, sx + cellSize + 8, sy + cellSize - 6);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private static int toScreenX(int mazeX, int offsetX, int cellSize) {
        return (mazeX * cellSize) + offsetX;
    }

    private static int toScreenY(int mazeY, int offsetY, int cellSize) {
        return (mazeY * cellSize) + offsetY;
    }

    private static int toScreenCenterX(int mazeX, int offsetX, int cellSize) {
        return toScreenX(mazeX, offsetX, cellSize) + (cellSize / 2);
    }

    private static int toScreenCenterY(int mazeY, int offsetY, int cellSize) {
        return toScreenY(mazeY, offsetY, cellSize) + (cellSize / 2);
    }

    private static Color withAlpha(Color c, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
