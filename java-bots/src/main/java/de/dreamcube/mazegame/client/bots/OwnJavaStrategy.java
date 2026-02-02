package de.dreamcube.mazegame.client.bots;

import de.dreamcube.mazegame.client.bots.model.BaitTracker;
import de.dreamcube.mazegame.client.bots.model.MazeState;
import de.dreamcube.mazegame.client.bots.planning.PlannerConfig;
import de.dreamcube.mazegame.client.bots.planning.RewardPlanner;
import de.dreamcube.mazegame.client.bots.planning.RewardPlanner.PlanRequest;
import de.dreamcube.mazegame.client.bots.planning.RewardPlanner.PlanResult;
import de.dreamcube.mazegame.client.bots.runtime.BotState;
import de.dreamcube.mazegame.client.bots.runtime.ClientAccess;
import de.dreamcube.mazegame.client.bots.visualization.BotVisualization;
import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.events.BaitEventListener;
import de.dreamcube.mazegame.client.maze.events.MazeEventListener;
import de.dreamcube.mazegame.client.maze.strategy.Bot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

/**
 * Reward-driven bot strategy for the maze game.
 */
@Bot(value = "Malenia", flavor = "The Severed Blade")
public class OwnJavaStrategy extends Strategy implements MazeEventListener, BaitEventListener {

    private static final PlannerConfig PLANNER_CONFIG = new PlannerConfig(
            28,
            6000,
            24,
            6.0,
            250.0
    );

    private static final String FALLBACK_NICK = "Tarnished";

    private final MazeState maze = new MazeState();
    private final BaitTracker baitTracker = new BaitTracker();
    private final RewardPlanner planner = new RewardPlanner(PLANNER_CONFIG);
    private final BotVisualization visualization = new BotVisualization();
    private final ClientAccess clientAccess = new ClientAccess();

    /**
     * Initializes the strategy; no-op for this bot.
     */
    @Override
    public void initializeStrategy() {
    }

    /**
     * Computes the next move based on the current maze and bait state.
     */
    @Override
    public @NotNull Move getNextMove() {
        if (!maze.isReady()) {
            return Move.DO_NOTHING;
        }

        BotState me = clientAccess.readOwnState(this, FALLBACK_NICK);
        if (me == null) {
            return Move.DO_NOTHING;
        }

        if (baitTracker.isEmpty()) {
            clientAccess.refreshBaits(this, baitTracker);
        }

        Collection<Bait> baits = baitTracker.snapshot();
        if (baits.isEmpty()) {
            clearVisualization();
            return Move.DO_NOTHING;
        }

        PlanResult plan = planner.plan(new PlanRequest(maze, baits, me));
        if (plan == null || plan.firstMove() == null) {
            clearVisualization();
            return Move.DO_NOTHING;
        }

        updateVisualization(plan);
        return plan.firstMove();
    }

    /**
     * Updates the maze model when a new maze is received.
     */
    @Override
    public void onMazeReceived(int width, int height, @NotNull java.util.List<String> mazeLines) {
        maze.updateFromMazeLines(width, height, mazeLines);
    }

    /**
     * Tracks a newly appeared bait.
     */
    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        baitTracker.add(bait);
    }

    /**
     * Removes a vanished bait from tracking.
     */
    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        baitTracker.remove(bait);
    }

    /**
     * Returns the visualization component for the UI.
     */
    @Override
    public @Nullable VisualizationComponent getVisualizationComponent() {
        return visualization;
    }

    /**
     * Updates visualization based on a plan result.
     */
    private void updateVisualization(@NotNull PlanResult plan) {
        if (plan.target() != null) {
            visualization.setTarget(plan.target().x, plan.target().y, plan.targetLabel());
        } else {
            visualization.clearTarget();
        }
        visualization.setPlannedPath(plan.path());
    }

    /**
     * Clears the visualization state.
     */
    private void clearVisualization() {
        visualization.clearTarget();
        visualization.setPlannedPath(Collections.emptyList());
    }
}
