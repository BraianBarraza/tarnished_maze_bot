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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Visualization bound to a single immutable render state.
 */
public final class BotVisualization extends VisualizationComponent {

    private final AtomicReference<RenderState> renderState = new AtomicReference<>(RenderState.empty());

    public void render(@Nullable Point target, @Nullable String label, @Nullable List<Point> path) {
        renderState.set(RenderState.of(target, label, path));
        repaint();
    }

    public void clear() {
        renderState.set(RenderState.empty());
        repaint();
    }

    @Override
    protected void paintComponent(@NotNull Graphics g) {
        super.paintComponent(g);

        if (!getVisualizationEnabled()) {
            return;
        }

        RenderState state = renderState.get();
        if (state.target() == null && state.path().isEmpty()) {
            return;
        }

        final int cellSize = Math.max(2, getZoom());
        final Point offset = getOffset();

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Integer selectedPlayerId = getSelectedPlayerId();
            Color base = selectedPlayerId != null ? getPlayerColor(selectedPlayerId) : null;
            if (base == null) {
                base = Color.MAGENTA;
            }

            if (state.path().size() >= 2) {
                g2.setColor(withAlpha(base, 120));
                g2.setStroke(new BasicStroke(Math.max(1f, cellSize / 6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                Point previous = state.path().get(0);
                for (int i = 1; i < state.path().size(); i++) {
                    Point current = state.path().get(i);
                    g2.drawLine(
                            toScreenCenterX(previous.x, offset.x, cellSize),
                            toScreenCenterY(previous.y, offset.y, cellSize),
                            toScreenCenterX(current.x, offset.x, cellSize),
                            toScreenCenterY(current.y, offset.y, cellSize)
                    );
                    previous = current;
                }
            }

            Point target = state.target();
            if (target != null) {
                int sx = toScreenX(target.x, offset.x, cellSize);
                int sy = toScreenY(target.y, offset.y, cellSize);

                g2.setColor(base);
                float ringStroke = Math.max(2f, cellSize / 4f);
                g2.setStroke(new BasicStroke(ringStroke));
                g2.drawOval(sx, sy, cellSize, cellSize);

                int cx = sx + cellSize / 2;
                int cy = sy + cellSize / 2;
                int radius = Math.max(2, cellSize / 2);

                g2.setStroke(new BasicStroke(Math.max(1f, ringStroke / 2f)));
                g2.drawLine(cx - radius, cy, cx + radius, cy);
                g2.drawLine(cx, cy - radius, cx, cy + radius);

                if (state.label() != null && !state.label().isBlank()) {
                    g2.setColor(withAlpha(Color.BLACK, 160));
                    g2.fillRoundRect(
                            sx + cellSize + 2,
                            sy - 2,
                            Math.max(30, state.label().length() * 7 + 10),
                            cellSize + 4,
                            8,
                            8
                    );

                    g2.setColor(Color.WHITE);
                    g2.drawString(state.label(), sx + cellSize + 8, sy + cellSize - 6);
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

    private static Color withAlpha(Color color, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private record RenderState(@Nullable Point target, @Nullable String label, @NotNull List<Point> path) {

        private static final RenderState EMPTY = new RenderState(null, null, List.of());

        private static @NotNull RenderState empty() {
            return EMPTY;
        }

        private static @NotNull RenderState of(@Nullable Point target, @Nullable String label, @Nullable List<Point> path) {
            Point targetCopy = target == null ? null : new Point(target);
            List<Point> pathCopy;
            if (path == null || path.isEmpty()) {
                pathCopy = List.of();
            } else {
                ArrayList<Point> copy = new ArrayList<>(path.size());
                for (Point point : path) {
                    copy.add(new Point(point));
                }
                pathCopy = Collections.unmodifiableList(copy);
            }
            return new RenderState(targetCopy, label, pathCopy);
        }
    }
}
