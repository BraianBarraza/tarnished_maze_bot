package de.dreamcube.mazegame.client.maze.strategy.malenia;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.events.BaitEventListener;
import de.dreamcube.mazegame.client.maze.events.MazeEventListener;
import de.dreamcube.mazegame.client.maze.strategy.Bot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import de.dreamcube.mazegame.client.maze.strategy.malenia.core.MaleniaEngine;
import de.dreamcube.mazegame.client.maze.strategy.malenia.ui.BotVisualization;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Malenia" is a reward-driven strategy:
 * <ul>
 *     <li>Maze grid is parsed via {@link MazeEventListener}.</li>
 *     <li>Baits are tracked via {@link BaitEventListener}.</li>
 *     <li>Each tick, the engine computes the best next move (TURN_L / TURN_R / STEP) to maximize reward.</li>
 * </ul>
 */
@Bot(value = "Malenia", flavor = "The Severed Blade")
public final class MaleniaStrategy extends Strategy implements MazeEventListener, BaitEventListener {

    private static final String FALLBACK_NICK = "Tarnished";

    private final AtomicBoolean paused = new AtomicBoolean(false);

    private final MaleniaEngine engine = new MaleniaEngine();
    private final BotVisualization visualization = new BotVisualization();
    private final MaleniaControlPanel controlPanel = new MaleniaControlPanel(paused);

    @Override
    public void initializeStrategy() {
        // no-op
    }

    @Override
    public @NotNull Move getNextMove() {
        if (paused.get()) {
            return Move.DO_NOTHING;
        }

        MaleniaEngine.Decision decision = engine.nextDecision(this, FALLBACK_NICK);
        if (decision == null) {
            clearVisualization();
            return Move.DO_NOTHING;
        }

        updateVisualization(decision);
        return decision.firstMove();
    }

    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        engine.updateMaze(width, height, mazeLines);
    }

    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        engine.onBaitAppeared(bait);
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        engine.onBaitVanished(bait);
    }

    @Override
    public @Nullable VisualizationComponent getVisualizationComponent() {
        return visualization;
    }

    @Override
    public @Nullable JPanel getControlPanel() {
        return controlPanel;
    }

    private void updateVisualization(@NotNull MaleniaEngine.Decision decision) {
        if (decision.target() != null) {
            visualization.setTarget(decision.target().x, decision.target().y, decision.targetLabel());
        } else {
            visualization.clearTarget();
        }
        visualization.setPlannedPath(decision.path());
    }

    private void clearVisualization() {
        visualization.clearTarget();
        visualization.setPlannedPath(Collections.emptyList());
    }
}
