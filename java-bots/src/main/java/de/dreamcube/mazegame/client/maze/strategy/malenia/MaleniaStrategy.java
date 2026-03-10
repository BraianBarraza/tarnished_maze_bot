package de.dreamcube.mazegame.client.maze.strategy.malenia;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.PlayerSnapshot;
import de.dreamcube.mazegame.client.maze.events.BaitEventListener;
import de.dreamcube.mazegame.client.maze.events.MazeEventListener;
import de.dreamcube.mazegame.client.maze.events.PlayerConnectionListener;
import de.dreamcube.mazegame.client.maze.events.PlayerMovementListener;
import de.dreamcube.mazegame.client.maze.strategy.Bot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.client.maze.strategy.VisualizationComponent;
import de.dreamcube.mazegame.client.maze.strategy.malenia.core.MaleniaEngine;
import de.dreamcube.mazegame.client.maze.strategy.malenia.ui.BotVisualization;
import de.dreamcube.mazegame.common.maze.PlayerPosition;
import de.dreamcube.mazegame.common.maze.TeleportType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static de.dreamcube.mazegame.client.maze.BaitKt.combineIntsToLong;

/**
 * Event-driven Malenia strategy.
 *
 * <p>All mutable game state is maintained from callbacks using {@link PlayerSnapshot} and copied into an
 * immutable {@link MaleniaEngine.WorldSnapshot} before each decision. This avoids mixing client reads from
 * different sources and keeps the thread-safety model easy to explain.</p>
 */
@Bot(value = "Malenia", flavor = "The Severed Blade")
public final class MaleniaStrategy extends Strategy
        implements MazeEventListener, BaitEventListener, PlayerMovementListener, PlayerConnectionListener {

    private final Object stateLock = new Object();
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private final LinkedHashMap<Long, Bait> baitsByCell = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, MaleniaEngine.PlayerState> playersById = new LinkedHashMap<>();

    private final MaleniaEngine engine = new MaleniaEngine();
    private final BotVisualization visualization = new BotVisualization();
    private final MaleniaControlPanel controlPanel = new MaleniaControlPanel(paused);

    private MaleniaEngine.MazeSnapshot mazeSnapshot = MaleniaEngine.MazeSnapshot.empty();
    private @Nullable Integer ownPlayerId;
    private @Nullable MaleniaEngine.TargetLock targetLock;
    private long worldVersion;

    @Override
    public void initializeStrategy() {
        // no-op
    }

    @Override
    public @NotNull Move getNextMove() {
        MaleniaEngine.WorldSnapshot worldSnapshot;
        MaleniaEngine.TargetLock previousLock;

        synchronized (stateLock) {
            if (paused.get()) {
                targetLock = null;
                visualization.clear();
                return Move.DO_NOTHING;
            }

            worldSnapshot = createWorldSnapshotLocked();
            previousLock = targetLock;
        }

        if (worldSnapshot.self() == null || !worldSnapshot.maze().isReady()) {
            clearDecisionState();
            return Move.DO_NOTHING;
        }

        MaleniaEngine.Decision decision = engine.nextDecision(worldSnapshot, previousLock);
        if (decision == null) {
            clearDecisionState();
            return Move.DO_NOTHING;
        }

        boolean publishDecision;
        synchronized (stateLock) {
            if (paused.get()) {
                targetLock = null;
                visualization.clear();
                return Move.DO_NOTHING;
            }
            publishDecision = decision.sourceVersion() == worldVersion;
            targetLock = publishDecision ? decision.nextTargetLock() : null;
        }

        if (publishDecision) {
            visualization.render(decision.target(), decision.targetLabel(), decision.path());
        } else {
            visualization.clear();
        }
        return decision.firstMove();
    }

    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        synchronized (stateLock) {
            mazeSnapshot = MaleniaEngine.MazeSnapshot.fromMazeLines(width, height, mazeLines);
            baitsByCell.clear();
            playersById.clear();
            ownPlayerId = null;
            targetLock = null;
            worldVersion++;
        }
        visualization.clear();
    }

    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        synchronized (stateLock) {
            baitsByCell.put(combineIntsToLong(bait.getX(), bait.getY()), bait);
            worldVersion++;
        }
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        boolean clearedTarget = false;
        synchronized (stateLock) {
            long baitId = combineIntsToLong(bait.getX(), bait.getY());
            baitsByCell.remove(baitId);
            if (targetLock != null && targetLock.baitId() == baitId) {
                targetLock = null;
                clearedTarget = true;
            }
            worldVersion++;
        }
        if (clearedTarget) {
            visualization.clear();
        }
    }

    @Override
    public void onOwnPlayerLogin(@NotNull PlayerSnapshot playerSnapshot) {
        synchronized (stateLock) {
            ownPlayerId = playerSnapshot.getId();
            upsertPlayerLocked(playerSnapshot);
            worldVersion++;
        }
    }

    @Override
    public void onPlayerAppear(@NotNull PlayerSnapshot playerSnapshot) {
        synchronized (stateLock) {
            upsertPlayerLocked(playerSnapshot);
            worldVersion++;
        }
    }

    @Override
    public void onPlayerStep(@NotNull PlayerPosition oldPosition, @NotNull PlayerSnapshot newPlayerSnapshot) {
        synchronized (stateLock) {
            upsertPlayerLocked(newPlayerSnapshot);
            worldVersion++;
        }
    }

    @Override
    public void onPlayerTurn(@NotNull PlayerPosition oldPosition, @NotNull PlayerSnapshot newPlayerSnapshot) {
        synchronized (stateLock) {
            upsertPlayerLocked(newPlayerSnapshot);
            worldVersion++;
        }
    }

    @Override
    public void onPlayerTeleport(@NotNull PlayerPosition oldPosition,
                                 @NotNull PlayerSnapshot newPlayerSnapshot,
                                 @Nullable TeleportType teleportType,
                                 @Nullable Integer causingPlayerId) {
        boolean ownTeleport = false;
        synchronized (stateLock) {
            upsertPlayerLocked(newPlayerSnapshot);
            if (ownPlayerId != null && ownPlayerId == newPlayerSnapshot.getId()) {
                targetLock = null;
                ownTeleport = true;
            }
            worldVersion++;
        }
        if (ownTeleport) {
            visualization.clear();
        }
    }

    @Override
    public void onPlayerVanish(@NotNull PlayerSnapshot playerSnapshot) {
        boolean ownPlayerGone = false;
        synchronized (stateLock) {
            playersById.remove(playerSnapshot.getId());
            if (ownPlayerId != null && ownPlayerId == playerSnapshot.getId()) {
                ownPlayerId = null;
                targetLock = null;
                ownPlayerGone = true;
            }
            worldVersion++;
        }
        if (ownPlayerGone) {
            visualization.clear();
        }
    }

    @Override
    public void onPlayerLogout(@NotNull PlayerSnapshot playerSnapshot) {
        boolean ownPlayerGone = false;
        synchronized (stateLock) {
            playersById.remove(playerSnapshot.getId());
            if (ownPlayerId != null && ownPlayerId == playerSnapshot.getId()) {
                ownPlayerId = null;
                targetLock = null;
                ownPlayerGone = true;
            }
            worldVersion++;
        }
        if (ownPlayerGone) {
            visualization.clear();
        }
    }

    @Override
    public @Nullable VisualizationComponent getVisualizationComponent() {
        return visualization;
    }

    @Override
    public @Nullable JPanel getControlPanel() {
        return controlPanel;
    }

    private void clearDecisionState() {
        synchronized (stateLock) {
            targetLock = null;
        }
        visualization.clear();
    }

    private void upsertPlayerLocked(@NotNull PlayerSnapshot playerSnapshot) {
        playersById.put(playerSnapshot.getId(), MaleniaEngine.PlayerState.fromSnapshot(playerSnapshot));
    }

    private @NotNull MaleniaEngine.WorldSnapshot createWorldSnapshotLocked() {
        MaleniaEngine.PlayerState self = ownPlayerId == null ? null : playersById.get(ownPlayerId);
        ArrayList<MaleniaEngine.PlayerState> others = new ArrayList<>(playersById.size());
        for (MaleniaEngine.PlayerState player : playersById.values()) {
            if (self != null && player.id() == self.id()) {
                continue;
            }
            others.add(player);
        }

        return new MaleniaEngine.WorldSnapshot(
                worldVersion,
                mazeSnapshot,
                self,
                List.copyOf(others),
                List.copyOf(new ArrayList<>(baitsByCell.values()))
        );
    }
}
