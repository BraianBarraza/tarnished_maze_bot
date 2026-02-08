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
 * An autonomous maze navigation strategy using oriented BFS pathfinding and value-based scoring.
 *
 * <p>The strategy consists of three main components:</p>
 * <ul>
 *     <li><strong>Pathfinding:</strong> Uses oriented BFS over (x, y, direction) states to find
 *         paths that minimize the number of actions (ticks) required to reach targets.</li>
 *     <li><strong>Target Selection:</strong> Evaluates potential targets using a scoring function:
 *         score = baitValue - (distancePenalty × steps). This balances the value of collecting
 *         a bait against the cost of traveling to it.</li>
 *     <li><strong>Stability:</strong> Implements hysteresis to prevent oscillation between targets.
 *         A new target must be significantly better before switching.</li>
 * </ul>
 *
 * <p>The strategy prioritizes paths that avoid known traps, but will traverse them if necessary
 * to reach valuable targets. As a last resort, trap baits may be targeted if no other options exist.</p>
 *
 * @see OrientedBfs
 * @see WorldState
 * @see MazeModel
 */
@Bot(value = "Tarnished", flavor = "Rise Tarnished")
public class BfsScoringStrategy extends Strategy implements BaitEventListener, MazeEventListener {

    private static final double DISTANCE_PENALTY_PER_STEP = 10.0;
    private static final double TARGET_IMPROVEMENT_MULTIPLIER = 1.2;

    private MazeModel mazeModel;
    private WorldState worldState;
    private OrientedBfs orientedBfs;
    private BotControlPanel controlPanel;
    private TargetVisualization visualization;

    /**
     * Initializes the strategy components including maze model, world state, pathfinding,
     * control panel, and visualization.
     */
    @Override
    protected void initializeStrategy() {
        mazeModel = new MazeModel();
        worldState = new WorldState();
        orientedBfs = new OrientedBfs(mazeModel);
        controlPanel = new BotControlPanel(worldState);
        visualization = new TargetVisualization(worldState);
    }

    /**
     * Determines the next move for the bot based on current game state.
     *
     * <p>The decision process follows these steps:</p>
     * <ol>
     *     <li>Check if bot is paused or maze is not yet received</li>
     *     <li>Attempt to find a reachable non-trap target while avoiding trap cells</li>
     *     <li>If no path exists without traversing traps, allow trap traversal</li>
     *     <li>If still no target is found, consider trap baits as last resort</li>
     *     <li>Apply hysteresis to prevent frequent target switching</li>
     *     <li>If no viable target exists, attempt a fallback exploratory move</li>
     * </ol>
     *
     * @return the next move to execute (TURN_L, TURN_R, STEP, or DO_NOTHING)
     */
    @NotNull
    @Override
    protected Move getNextMove() {
        if (worldState.isPaused() || !mazeModel.hasMaze()) {
            worldState.setCurrentPath(List.of());
            return Move.DO_NOTHING;
        }

        PlayerSnapshot ownPlayer = getMazeClient().getOwnPlayerSnapshot();
        int playerX = ownPlayer.getX();
        int playerY = ownPlayer.getY();
        ViewDirection playerDirection = ownPlayer.getViewDirection();

        List<Bait> availableBaits = worldState.getActiveBaits();

        boolean[][] trapCells = buildTrapCellMap(availableBaits);
        orientedBfs.computeFrom(playerX, playerY, playerDirection, trapCells);

        TargetCandidate previousCandidate = evaluatePreviousTarget(availableBaits);
        TargetCandidate bestCandidate = findBestNonTrapTarget(availableBaits);

        if (bestCandidate == null) {
            orientedBfs.computeFrom(playerX, playerY, playerDirection, null);
            bestCandidate = findBestNonTrapTarget(availableBaits);
        }

        if (bestCandidate == null) {
            bestCandidate = findBestAnyTarget(availableBaits);
        }

        TargetCandidate selectedTarget = selectTargetWithHysteresis(previousCandidate, bestCandidate);
        updateWorldStateWithTarget(selectedTarget);

        if (selectedTarget != null) {
            worldState.setCurrentPath(orientedBfs.getPathTo(selectedTarget.bait.getX(), selectedTarget.bait.getY()));
            Move move = orientedBfs.firstMoveTo(selectedTarget.bait.getX(), selectedTarget.bait.getY());
            if (move != Move.DO_NOTHING) {
                return move;
            }
        } else {
            worldState.setCurrentPath(List.of());
        }

        return calculateFallbackMove(playerX, playerY, playerDirection);
    }

    /**
     * Selects the target to pursue, applying hysteresis to prevent oscillation.
     *
     * <p>Hysteresis ensures that the bot doesn't constantly switch between similar-valued targets.
     * The new target must be significantly better (by {@link #TARGET_IMPROVEMENT_MULTIPLIER})
     * before a switch occurs.</p>
     *
     * @param previousCandidate the currently targeted candidate, may be null
     * @param newCandidate the potential new target candidate, may be null
     * @return the selected target candidate, or null if no valid targets exist
     */
    private TargetCandidate selectTargetWithHysteresis(TargetCandidate previousCandidate,
                                                       TargetCandidate newCandidate) {
        if (previousCandidate == null) {
            return newCandidate;
        }
        if (newCandidate == null) {
            return previousCandidate;
        }
        return shouldSwitchTarget(previousCandidate.score, newCandidate.score)
                ? newCandidate
                : previousCandidate;
    }

    /**
     * Determines whether to switch from the current target to a new candidate.
     *
     * <p>For positive scores, requires the new score to exceed the current by the improvement
     * multiplier. For non-positive scores, any improvement triggers a switch.</p>
     *
     * @param currentScore the score of the current target
     * @param candidateScore the score of the potential new target
     * @return true if a target switch should occur, false otherwise
     */
    private boolean shouldSwitchTarget(double currentScore, double candidateScore) {
        if (currentScore <= 0) {
            return candidateScore > currentScore;
        }
        return candidateScore > currentScore * TARGET_IMPROVEMENT_MULTIPLIER;
    }

    /**
     * Evaluates the current target to determine if it should remain the active target.
     *
     * @param availableBaits the list of currently visible baits
     * @return a candidate wrapper for the current target, or null if it's no longer valid
     */
    private TargetCandidate evaluatePreviousTarget(List<Bait> availableBaits) {
        Bait currentTarget = worldState.getCurrentTarget();
        if (currentTarget == null || !availableBaits.contains(currentTarget)) {
            return null;
        }

        int distance = orientedBfs.distanceTo(currentTarget.getX(), currentTarget.getY());
        if (distance == Integer.MAX_VALUE) {
            return null;
        }

        return new TargetCandidate(currentTarget, calculateScore(currentTarget, distance), distance);
    }

    /**
     * Finds the best non-trap bait to target from the available baits.
     *
     * @param availableBaits the list of currently visible baits
     * @return the best non-trap target candidate, or null if none are reachable
     */
    private TargetCandidate findBestNonTrapTarget(List<Bait> availableBaits) {
        TargetCandidate bestCandidate = null;
        for (Bait bait : availableBaits) {
            if (bait.getType() == BaitType.TRAP) {
                continue;
            }
            TargetCandidate candidate = evaluateTargetCandidate(bait);
            if (candidate != null && (bestCandidate == null || candidate.score > bestCandidate.score)) {
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    /**
     * Finds the best target from all available baits, including traps.
     *
     * <p>This method is used as a last resort when no non-trap targets are reachable.</p>
     *
     * @param availableBaits the list of currently visible baits
     * @return the best target candidate of any type, or null if none are reachable
     */
    private TargetCandidate findBestAnyTarget(List<Bait> availableBaits) {
        TargetCandidate bestCandidate = null;
        for (Bait bait : availableBaits) {
            TargetCandidate candidate = evaluateTargetCandidate(bait);
            if (candidate != null && (bestCandidate == null || candidate.score > bestCandidate.score)) {
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    /**
     * Evaluates a single bait as a potential target candidate.
     *
     * @param bait the bait to evaluate
     * @return a candidate wrapper with calculated score, or null if unreachable
     */
    private TargetCandidate evaluateTargetCandidate(Bait bait) {
        int distance = orientedBfs.distanceTo(bait.getX(), bait.getY());
        if (distance == Integer.MAX_VALUE) {
            return null;
        }
        return new TargetCandidate(bait, calculateScore(bait, distance), distance);
    }

    /**
     * Calculates the attractiveness score for a bait considering its value and distance.
     *
     * <p>The scoring formula is: score = baitValue - (distancePenalty × steps)</p>
     *
     * @param bait the bait to score
     * @param distanceSteps the number of steps required to reach the bait
     * @return the calculated score (can be negative for distant or low-value baits)
     */
    private double calculateScore(Bait bait, int distanceSteps) {
        return bait.getScore() - (DISTANCE_PENALTY_PER_STEP * distanceSteps);
    }

    /**
     * Constructs a 2D boolean array marking all cells containing trap baits.
     *
     * @param availableBaits the list of currently visible baits
     * @return a 2D array where true indicates a trap cell
     */
    private boolean[][] buildTrapCellMap(List<Bait> availableBaits) {
        int width = mazeModel.getWidth();
        int height = mazeModel.getHeight();
        boolean[][] trapCells = new boolean[width][height];

        for (Bait bait : availableBaits) {
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

    /**
     * Updates the world state with the selected target information.
     *
     * @param selectedTarget the target candidate to set, or null to clear the current target
     */
    private void updateWorldStateWithTarget(TargetCandidate selectedTarget) {
        if (selectedTarget != null) {
            worldState.setCurrentTarget(selectedTarget.bait, selectedTarget.score);
        } else {
            worldState.setCurrentTarget(null, Double.NEGATIVE_INFINITY);
        }
    }

    /**
     * Calculates a fallback move when no viable target exists.
     *
     * <p>The fallback strategy attempts to step forward if possible; otherwise, turns left
     * to continue exploring.</p>
     *
     * @param playerX the player's current x-coordinate
     * @param playerY the player's current y-coordinate
     * @param playerDirection the player's current facing direction
     * @return STEP if forward cell is walkable, otherwise TURN_L
     */
    private Move calculateFallbackMove(int playerX, int playerY, ViewDirection playerDirection) {
        int nextX = playerX + DirectionUtil.getDeltaX(playerDirection);
        int nextY = playerY + DirectionUtil.getDeltaY(playerDirection);
        if (mazeModel.isWalkable(nextX, nextY)) {
            return Move.STEP;
        }
        return Move.TURN_L;
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
     * Internal representation of a potential target with its associated metadata.
     *
     * @param bait the bait being considered as a target
     * @param score the calculated attractiveness score for this target
     * @param distance the number of steps required to reach this target
     */
    private record TargetCandidate(Bait bait, double score, int distance) {
    }

    /**
     * Utility class for direction-related calculations to eliminate code duplication.
     */
    private static final class DirectionUtil {

        /**
         * Calculates the x-coordinate delta for stepping in the given direction.
         *
         * @param direction the facing direction
         * @return 1 for EAST, -1 for WEST, 0 for NORTH or SOUTH
         */
        static int getDeltaX(ViewDirection direction) {
            return switch (direction) {
                case NORTH, SOUTH -> 0;
                case EAST -> 1;
                case WEST -> -1;
            };
        }

        /**
         * Calculates the y-coordinate delta for stepping in the given direction.
         *
         * @param direction the facing direction
         * @return -1 for NORTH, 1 for SOUTH, 0 for EAST or WEST
         */
        static int getDeltaY(ViewDirection direction) {
            return switch (direction) {
                case NORTH -> -1;
                case EAST, WEST -> 0;
                case SOUTH -> 1;
            };
        }
    }
}