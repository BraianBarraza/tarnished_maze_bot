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
import de.dreamcube.mazegame.client.maze.strategy.tarnished.ui.BotControlPanel;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.ui.TargetVisualization;
import de.dreamcube.mazegame.common.maze.BaitType;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.util.List;

/**
 * A simple but effective bot:
 * <ul>
 *     <li>Pathfinding: oriented BFS over (x,y,dir) to minimize number of actions (ticks).</li>
 *     <li>Decision: choose a target by score = baitValue - distancePenalty * steps.</li>
 *     <li>Stability: keep the current target unless a new one is clearly better (anti ping-pong).</li>
 * </ul>
 */
@Bot(value = "Tarnished", flavor = "Rise Tarnished")
public class BfsScoringStrategy extends Strategy implements BaitEventListener, MazeEventListener {

    /**
     * How much one step (one tick) "costs" in score.
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
    private BotControlPanel controlPanel;
    private TargetVisualization visualization;

    @Override
    protected void initializeStrategy() {
        mazeModel = new MazeModel();
        worldState = new WorldState();
        orientedBfs = new OrientedBfs(mazeModel);
        controlPanel = new BotControlPanel(worldState);
        visualization = new TargetVisualization(worldState);
    }

    @NotNull
    @Override
    protected Move getNextMove() {
        if (worldState.isPaused() || !mazeModel.hasMaze()) {
            worldState.setCurrentPath(List.of());
            return Move.DO_NOTHING;
        }

        PlayerSnapshot own = getMazeClient().getOwnPlayerSnapshot();
        int playerX = own.getX();
        int playerY = own.getY();
        ViewDirection playerDirection = own.getViewDirection();

        List<Bait> baits = worldState.getActiveBaits();

        // 1) Prefer paths that do NOT step on known traps.
        boolean[][] trapCells = buildTrapCellMap(baits);
        orientedBfs.computeFrom(playerX, playerY, playerDirection, trapCells);

        TargetCandidate previousCandidate = evaluatePreviousTargetCandidate(baits);
        TargetCandidate bestCandidate = findBestNonTrapCandidate(baits);

        // 2) If no reachable non-trap exists WITHOUT stepping on traps, allow trap traversal.
        if (bestCandidate == null) {
            orientedBfs.computeFrom(playerX, playerY, playerDirection, null);
            bestCandidate = findBestNonTrapCandidate(baits);
        }

        // 3) Still nothing? As a last resort, allow trap targets (better than standing still forever).
        if (bestCandidate == null) {
            bestCandidate = findBestAnyCandidate(baits);
        }

        TargetCandidate selected = selectTargetWithHysteresis(previousCandidate, bestCandidate);
        worldState.setCurrentTarget(selected == null ? null : selected.bait, selected == null ? Double.NEGATIVE_INFINITY : selected.score);

        if (selected != null) {
            worldState.setCurrentPath(orientedBfs.getPathTo(selected.bait.getX(), selected.bait.getY()));
            Move move = orientedBfs.firstMoveTo(selected.bait.getX(), selected.bait.getY());
            if (move != Move.DO_NOTHING) {
                return move;
            }
        } else {
            worldState.setCurrentPath(List.of());
        }

        // Fallback: avoid looking "stuck" if no good target exists.
        return fallbackMove(playerX, playerY, playerDirection);
    }

    private TargetCandidate selectTargetWithHysteresis(TargetCandidate previous, TargetCandidate best) {
        if (previous == null) {
            return best;
        }
        if (best == null) {
            return previous;
        }
        return shouldSwitchTarget(previous.score, best.score) ? best : previous;
    }

    private boolean shouldSwitchTarget(double currentScore, double candidateScore) {
        // For positive scores, require a clear improvement. For <= 0, only switch if strictly better.
        if (currentScore <= 0) {
            return candidateScore > currentScore;
        }
        return candidateScore > currentScore * TARGET_IMPROVEMENT_MULTIPLIER;
    }

    private TargetCandidate evaluatePreviousTargetCandidate(List<Bait> baits) {
        Bait previous = worldState.getCurrentTarget();
        if (previous == null || !baits.contains(previous)) {
            return null;
        }

        int distance = orientedBfs.distanceTo(previous.getX(), previous.getY());
        if (distance == Integer.MAX_VALUE) {
            return null;
        }

        return new TargetCandidate(previous, scoreOf(previous, distance), distance);
    }

    private TargetCandidate findBestNonTrapCandidate(List<Bait> baits) {
        TargetCandidate best = null;
        for (Bait bait : baits) {
            if (bait.getType() == BaitType.TRAP) {
                continue;
            }
            TargetCandidate candidate = evaluateCandidate(bait);
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
            }
        }
        return best;
    }

    private TargetCandidate findBestAnyCandidate(List<Bait> baits) {
        TargetCandidate best = null;
        for (Bait bait : baits) {
            TargetCandidate candidate = evaluateCandidate(bait);
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
            }
        }
        return best;
    }

    private TargetCandidate evaluateCandidate(Bait bait) {
        int distance = orientedBfs.distanceTo(bait.getX(), bait.getY());
        if (distance == Integer.MAX_VALUE) {
            return null;
        }
        return new TargetCandidate(bait, scoreOf(bait, distance), distance);
    }

    private double scoreOf(Bait bait, int distanceSteps) {
        return bait.getScore() - (DISTANCE_PENALTY_PER_STEP * distanceSteps);
    }


    private boolean[][] buildTrapCellMap(List<Bait> baits) {
        int width = mazeModel.getWidth();
        int height = mazeModel.getHeight();
        boolean[][] trapCells = new boolean[width][height];

        for (Bait bait : baits) {
            if (bait.getType() != BaitType.TRAP) {
                continue;
            }
            int x = bait.getX();
            int y = bait.getY();
            if (x >= 0 && y >= 0 && x < width && y < height) {
                trapCells[x][y] = true;
            }
        }
        return trapCells;
    }

    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        worldState.addBait(bait);
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        worldState.removeBait(bait);
        worldState.clearTargetIfEquals(bait);
    }

    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        mazeModel.updateFromMaze(width, height, mazeLines);
    }

    @Override
    public JPanel getControlPanel() {
        return controlPanel;
    }

    @Override
    public VisualizationComponent getVisualizationComponent() {
        return visualization;
    }

    /**
     * Basic fallback if no reachable target exists.
     * Try stepping forward if possible; otherwise keep turning.
     */
    private Move fallbackMove(int playerX, int playerY, ViewDirection playerDirection) {
        int nextX = playerX + stepDeltaX(playerDirection);
        int nextY = playerY + stepDeltaY(playerDirection);
        if (mazeModel.isWalkable(nextX, nextY)) {
            return Move.STEP;
        }
        return Move.TURN_L;
    }

    private int stepDeltaX(ViewDirection direction) {
        return switch (direction) {
            case NORTH, SOUTH -> 0;
            case EAST -> 1;
            case WEST -> -1;
        };
    }

    private int stepDeltaY(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> -1;
            case EAST, WEST -> 0;
            case SOUTH -> 1;
        };
    }

    private record TargetCandidate(Bait bait, double score, int distance) {
    }
}
