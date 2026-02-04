package de.dreamcube.mazegame.client.maze.strategy.tarnished;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.PlayerSnapshot;
import de.dreamcube.mazegame.client.maze.events.BaitEventListener;
import de.dreamcube.mazegame.client.maze.events.MazeEventListener;
import de.dreamcube.mazegame.client.maze.strategy.Bot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.MazeModel;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.WorldState;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.pathfinding.OrientedBfs;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.ui.SimpleBotControlPanel;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.ui.TargetVisualization;
import de.dreamcube.mazegame.common.maze.BaitType;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.util.List;

/**
 * Simple and effective bot:
 * Oriented BFS for shortest actions + target selection by (baitValue - distancePenalty * steps).
 */
@Bot(value = "Tarnished", flavor = "Rise Tarnished")
public class BfsScoringStrategy extends Strategy implements BaitEventListener, MazeEventListener {

    /**
     * Lambda: how much one step (one tick) "costs" in score.
     * Tune this if the bot goes too greedy or too short-sighted.
     */
    private static final double DISTANCE_PENALTY_PER_STEP = 10.0;

    /**
     * Anti ping-pong: keep the current target unless a new one is clearly better.
     */
    private static final double TARGET_IMPROVEMENT_MULTIPLIER = 1.2;

    private MazeModel mazeModel;
    private WorldState worldState;
    private OrientedBfs orientedBfs;
    private SimpleBotControlPanel controlPanel;
    private TargetVisualization targetVisualization;

    public BfsScoringStrategy() {
    }

    @Override
    protected void initializeStrategy() {
        mazeModel = new MazeModel();
        worldState = new WorldState();
        orientedBfs = new OrientedBfs(mazeModel);
        controlPanel = new SimpleBotControlPanel(worldState);
        targetVisualization = new TargetVisualization(worldState);
    }

    @Override
    protected Move getNextMove() {
        if (worldState != null && worldState.paused) {
            return Move.DO_NOTHING;
        }
        if (mazeModel == null || mazeModel.walkable == null || mazeModel.width <= 0 || mazeModel.height <= 0) {
            return Move.DO_NOTHING;
        }

        PlayerSnapshot ownPlayerSnapshot = getMazeClient().getOwnPlayerSnapshot();
        int playerX = ownPlayerSnapshot.getX();
        int playerY = ownPlayerSnapshot.getY();
        ViewDirection playerDirection = ownPlayerSnapshot.getViewDirection();

        boolean[][] trapCells = buildTrapCellMap();
        orientedBfs.computeFrom(playerX, playerY, playerDirection, trapCells);

        // Keep previous target if still present and reachable.
        Bait previousTarget = worldState.currentTarget;
        double previousTargetScore = Double.NEGATIVE_INFINITY;
        if (previousTarget != null && worldState.activeBaits.contains(previousTarget)) {
            int previousTargetDistance = orientedBfs.distanceTo(previousTarget.getX(), previousTarget.getY());
            if (previousTargetDistance != Integer.MAX_VALUE) {
                previousTargetScore = scoreOf(previousTarget, previousTargetDistance);
            } else {
                previousTarget = null;
            }
        } else {
            previousTarget = null;
        }

        // Find best non-trap target without stepping on traps.
        Bait bestTarget = null;
        double bestTargetScore = Double.NEGATIVE_INFINITY;
        for (Bait bait : worldState.activeBaits) {
            if (bait.getType() == BaitType.TRAP) {
                continue;
            }
            int baitDistance = orientedBfs.distanceTo(bait.getX(), bait.getY());
            if (baitDistance == Integer.MAX_VALUE) {
                continue;
            }
            double baitScore = scoreOf(bait, baitDistance);
            if (baitScore > bestTargetScore) {
                bestTarget = bait;
                bestTargetScore = baitScore;
            }
        }

        if (bestTarget == null) {
            // No non-trap bait reachable without stepping on traps -> allow trap traversal.
            orientedBfs.computeFrom(playerX, playerY, playerDirection, null);
            for (Bait bait : worldState.activeBaits) {
                if (bait.getType() == BaitType.TRAP) {
                    continue;
                }
                int baitDistance = orientedBfs.distanceTo(bait.getX(), bait.getY());
                if (baitDistance == Integer.MAX_VALUE) {
                    continue;
                }
                double baitScore = scoreOf(bait, baitDistance);
                if (baitScore > bestTargetScore) {
                    bestTarget = bait;
                    bestTargetScore = baitScore;
                }
            }
        }

        if (bestTarget == null) {
            // Still no non-trap target -> allow trap targets as a last resort.
            for (Bait bait : worldState.activeBaits) {
                int baitDistance = orientedBfs.distanceTo(bait.getX(), bait.getY());
                if (baitDistance == Integer.MAX_VALUE) {
                    continue;
                }
                double baitScore = scoreOf(bait, baitDistance);
                if (baitScore > bestTargetScore) {
                    bestTarget = bait;
                    bestTargetScore = baitScore;
                }
            }
        }

        // Target selection with anti ping-pong.
        Bait selectedTarget = previousTarget;
        double selectedTargetScore = previousTargetScore;
        if (previousTarget == null) {
            selectedTarget = bestTarget;
            selectedTargetScore = bestTargetScore;
        } else if (bestTarget != null && bestTargetScore > previousTargetScore * TARGET_IMPROVEMENT_MULTIPLIER) {
            selectedTarget = bestTarget;
            selectedTargetScore = bestTargetScore;
        }

        worldState.currentTarget = selectedTarget;
        worldState.currentTargetScoreValue = selectedTargetScore;

        // Move towards target (only the first action of the shortest path).
        if (selectedTarget != null) {
            Move moveTowardsTarget = orientedBfs.firstMoveTo(selectedTarget.getX(), selectedTarget.getY());
            worldState.currentPathCells = orientedBfs.getPathCellsTo(selectedTarget.getX(), selectedTarget.getY());
            if (moveTowardsTarget != Move.DO_NOTHING) {
                return moveTowardsTarget;
            }
        } else {
            worldState.currentPathCells = java.util.List.of();
        }

        // Fallback: explore-ish movement so the bot does not look stuck.
        return fallbackMove(playerX, playerY, playerDirection);
    }

    private double scoreOf(Bait bait, int distanceSteps) {
        return bait.getScore() - (DISTANCE_PENALTY_PER_STEP * distanceSteps);
    }

    private boolean[][] buildTrapCellMap() {
        boolean[][] trapCells = new boolean[mazeModel.width][mazeModel.height];
        for (Bait bait : worldState.activeBaits) {
            if (bait.getType() == BaitType.TRAP
                    && bait.getX() >= 0 && bait.getY() >= 0
                    && bait.getX() < mazeModel.width && bait.getY() < mazeModel.height) {
                trapCells[bait.getX()][bait.getY()] = true;
            }
        }
        return trapCells;
    }

    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        if (worldState != null) {
            worldState.activeBaits.add(bait);
        }
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        if (worldState != null) {
            worldState.activeBaits.remove(bait);
            if (bait.equals(worldState.currentTarget)) {
                worldState.currentTarget = null;
                worldState.currentTargetScoreValue = Double.NEGATIVE_INFINITY;
            }
        }
    }

    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        if (mazeModel != null) {
            mazeModel.updateFromMaze(width, height, mazeLines);
        }
    }

    @Override
    public JPanel getControlPanel() {
        return controlPanel;
    }

    @Override
    public VisualizationComponent getVisualizationComponent() {
        return targetVisualization;
    }

    @Override
    public void beforeGoodbye() {
        // No cleanup needed.
    }

    /**
     * Basic fallback if no reachable positive bait exists.
     * Try stepping forward if possible; otherwise keep turning.
     */
    private Move fallbackMove(int playerX, int playerY, ViewDirection playerDirection) {
        int nextX = playerX + getStepDeltaX(playerDirection);
        int nextY = playerY + getStepDeltaY(playerDirection);
        if (isWithinMazeBounds(nextX, nextY) && mazeModel.walkable[nextX][nextY]) {
            return Move.STEP;
        }
        return Move.TURN_L;
    }

    private boolean isWithinMazeBounds(int positionX, int positionY) {
        return positionX >= 0 && positionY >= 0 && positionX < mazeModel.width && positionY < mazeModel.height;
    }

    private int getStepDeltaX(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 0;
            case WEST -> -1;
        };
    }

    private int getStepDeltaY(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> -1;
            case EAST -> 0;
            case SOUTH -> 1;
            case WEST -> 0;
        };
    }
}
