package de.dreamcube.mazegame.client.bots;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.PlayerSnapshot;
import de.dreamcube.mazegame.client.maze.events.*;
import de.dreamcube.mazegame.client.maze.strategy.Bot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.common.maze.PlayerPosition;
import de.dreamcube.mazegame.common.maze.TeleportType;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Competitive maze bot strategy (multiplayer friendly).
 *
 * <p>Main ideas:</p>
 * <ul>
 *   <li><b>Tick-accurate path planning</b> using BFS on state (x,y,dir). Turns cost ticks, so we plan with them.</li>
 *   <li><b>Race-aware target selection</b>: estimates if an opponent will reach a bait earlier and lowers its value.</li>
 *   <li><b>Hysteresis (commit window)</b>: avoids switching target every tick when the world changes.</li>
 *   <li><b>Trap learning</b>: remembers cells that triggered a TRAP teleport for a limited time.</li>
 *   <li><b>Collision avoidance</b>: avoids stepping into cells occupied by other players and (optionally) their front cell.</li>
 * </ul>
 */
@Bot(value = "Tarnished", flavor = "Darkness cannot drive out darkness: only light can do that")
public class OwnJavaStrategy extends Strategy
        implements MazeEventListener,
        BaitEventListener,
        PlayerMovementListener,
        PlayerConnectionListener {

    /** Max gem search range in plain grid steps (cheap filter to ignore very far gems). */
    private static final int GEM_MAX_DISTANCE_STEPS = 18;

    /** Small detour radius (in steps) when already targeting COFFEE/FOOD. */
    private static final int OPPORTUNISTIC_RADIUS_STEPS = 2;

    /** How long a trap-trigger cell stays in the danger memory (ticks). */
    private static final long DANGER_TTL_TICKS = 250;

    /**
     * Target commit window in ticks.
     * While commit is active, we only switch target if the new one is clearly better.
     */
    private static final long TARGET_COMMIT_TICKS = 22;

    /**
     * Minimum relative improvement (in percent) required to switch target during the commit window.
     * Example: 25 means: newScore must be >= oldScore * 1.25 to switch.
     */
    private static final int SWITCH_IMPROVEMENT_PERCENT = 25;

    /** Extra safety: avoid stepping into an opponent's front cell to reduce head-on teleports. */
    private static final boolean AVOID_OPPONENT_FRONT_CELLS = true;

    // =========================
    // Maze state
    // =========================

    private int width, height;

    /**
     * Walkable cells map: true = floor '.'.
     * Index: idx = y * width + x.
     */
    private boolean[] walkable;

    /** Baits known by the bot, stored by coordinate key (x,y). */
    private final Map<Long, Bait> baits = new ConcurrentHashMap<>();

    /** Other players (and myself) as snapshots. */
    private final Map<Integer, PlayerSnapshot> players = new ConcurrentHashMap<>();

    // =========================
    // Own state
    // =========================

    private Integer myPlayerId = null;
    private volatile PlayerSnapshot me;

    /** Current planned move queue. */
    private final Deque<Move> plan = new ArrayDeque<>();

    /** Mode used to build the current plan (important for validating planned STEPs). */
    private volatile PlanMode planMode = PlanMode.SAFE;

    private volatile boolean paused = false;
    private long tick = 0;

    /** Danger memory: cellKey -> expiryTick. */
    private final Map<Long, Long> dangerUntilTick = new ConcurrentHashMap<>();

    /** Target hysteresis: last chosen target key and commit end tick. */
    private volatile Long currentTargetKey = null;
    private volatile long commitUntilTick = 0;

    // =========================
    // Events
    // =========================

    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        this.width = width;
        this.height = height;
        this.walkable = new boolean[width * height];

        // '.' is walkable; everything else is treated as wall.
        for (int y = 0; y < height; y++) {
            String line = mazeLines.get(y);
            for (int x = 0; x < width; x++) {
                char c = line.charAt(x);
                walkable[idx(x, y)] = (c == '.');
            }
        }

        clearPlan();
    }

    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        baits.put(key(bait.getX(), bait.getY()), bait);
        // Re-plan quickly when the world changes.
        clearPlan();
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        baits.remove(key(bait.getX(), bait.getY()));
        // If our current target vanished, drop commit.
        if (Objects.equals(currentTargetKey, key(bait.getX(), bait.getY()))) {
            currentTargetKey = null;
            commitUntilTick = 0;
        }
        clearPlan();
    }

    @Override
    public void onOwnPlayerLogin(@NotNull PlayerSnapshot snap) {
        myPlayerId = snap.getId();
        me = snap;
        players.put(snap.getId(), snap);
        clearPlan();
    }

    @Override
    public void onPlayerAppear(@NotNull PlayerSnapshot snap) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);
    }

    @Override
    public void onPlayerVanish(@NotNull PlayerSnapshot snap) {
        players.remove(snap.getId());
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            me = null;
            myPlayerId = null;
        }
        clearPlan();
    }

    @Override
    public void onPlayerStep(@NotNull PlayerPosition oldPos, @NotNull PlayerSnapshot snap) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);
    }

    @Override
    public void onPlayerTurn(@NotNull PlayerPosition oldPos, @NotNull PlayerSnapshot snap) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);
    }

    @Override
    public void onPlayerTeleport(
            @NotNull PlayerPosition oldPos,
            @NotNull PlayerSnapshot snap,
            TeleportType teleportType,
            Integer causingPlayerId
    ) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);

        // If I got teleported: clear plan and learn danger if it was a TRAP teleport.
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            clearPlan();

            if (teleportType == TeleportType.TRAP) {
                // Mark the old position as dangerous.
                markDanger(oldPos.getX(), oldPos.getY());

                // Also mark the cell in front (some traps feel "edge-triggered").
                int[] front = frontCell(oldPos.getX(), oldPos.getY(), oldPos.getViewDirection());
                if (front != null) markDanger(front[0], front[1]);
            }
        }
    }

    private void updateMeIfOwn(PlayerSnapshot snap) {
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            me = snap;
        }
    }

    // =========================
    // Main loop
    // =========================

    /**
     * Called each tick by the engine to get the next move.
     *
     * <p>We validate the next planned STEP. If the cell is no longer allowed (player moved / etc),
     * we drop the plan and re-plan.</p>
     */
    @Nullable
    @Override
    protected Move getNextMove() {
        tick++;

        if (paused || walkable == null || me == null || myPlayerId == null) {
            return Move.DO_NOTHING;
        }

        // Validate only the first planned STEP (cheap).
        if (!plan.isEmpty() && plan.peekFirst() == Move.STEP) {
            if (!canStepForward(me, planMode)) {
                clearPlan();
            }
        }

        if (plan.isEmpty()) {
            boolean planned = planToBestTarget();
            if (!planned) {
                // Fallback: walk forward if possible, otherwise rotate.
                return canStepForward(me, PlanMode.SAFE) ? Move.STEP : Move.TURN_R;
            }
        }

        return plan.pollFirst();
    }

    // =========================
    // Planning modes
    // =========================

    /**
     * Planning modes.
     *
     * <ul>
     *   <li>SAFE: avoids danger memory and avoids TRAP cells.</li>
     *   <li>RELAXED: ignores danger memory, still avoids TRAP cells.</li>
     *   <li>ESCAPE: ignores danger memory and allows stepping on TRAP cells (only used when stuck).</li>
     * </ul>
     */
    private enum PlanMode { SAFE, RELAXED, ESCAPE }

    /** High-level bait groups used for priorities. */
    private enum Kind { GEM, LETTER, COFFEE, FOOD, OTHER, TRAP }

    /**
     * Creates a plan to the best target in a competitive way.
     *
     * <p>Fallback order:</p>
     * <ol>
     *   <li>SAFE</li>
     *   <li>RELAXED</li>
     *   <li>ESCAPE (only if stuck)</li>
     * </ol>
     */
    private boolean planToBestTarget() {
        // Hard-block: current occupied cells by others.
        Set<Long> occupied = currentOccupiedCellsByOthers();

        // Optional: also block opponent front-cells (to reduce head-on teleports).
        Set<Long> avoidCells = new HashSet<>(occupied);
        if (AVOID_OPPONENT_FRONT_CELLS) {
            avoidCells.addAll(predictedOpponentFrontCells());
        }

        // Precompute cheap step distances from me (grid BFS without direction) for gem range filter.
        int[] stepDistFromMeSafe = bfsGridSteps(me.getX(), me.getY(), true, false, avoidCells);

        // Precompute opponent tick distances (direction-aware BFS) once.
        List<int[]> opponentsTickDists = computeOpponentTickDistances();

        // 1) SAFE
        BfsStateResult safe = bfsStateFrom(me.getX(), me.getY(), me.getViewDirection(), true, false, avoidCells);
        ScoredTarget safeBest = chooseBestTargetCompetitive(safe, opponentsTickDists, stepDistFromMeSafe);
        safeBest = maybeOpportunisticCoffeeFood(safeBest, safe, stepDistFromMeSafe);

        if (safeBest != null && buildPlanFromStateBfs(safe, safeBest.bait, PlanMode.SAFE)) {
            setCommitTarget(safeBest.bait);
            return true;
        }

        // 2) RELAXED (ignore learned danger)
        int[] stepDistFromMeRelaxed = bfsGridSteps(me.getX(), me.getY(), false, false, avoidCells);
        BfsStateResult relaxed = bfsStateFrom(me.getX(), me.getY(), me.getViewDirection(), false, false, avoidCells);
        ScoredTarget relaxedBest = chooseBestTargetCompetitive(relaxed, opponentsTickDists, stepDistFromMeRelaxed);
        relaxedBest = maybeOpportunisticCoffeeFood(relaxedBest, relaxed, stepDistFromMeRelaxed);

        if (relaxedBest != null && buildPlanFromStateBfs(relaxed, relaxedBest.bait, PlanMode.RELAXED)) {
            setCommitTarget(relaxedBest.bait);
            return true;
        }

        // 3) ESCAPE: only if we have no safe exit
        if (isStuck(avoidCells)) {
            // In ESCAPE mode we still avoid occupied cells (teleport risk), but we allow TRAP tiles.
            BfsStateResult escape = bfsStateFrom(me.getX(), me.getY(), me.getViewDirection(), false, true, occupied);
            int[] stepDistFromMeEscape = bfsGridSteps(me.getX(), me.getY(), false, true, occupied);

            ScoredTarget escapeBest = chooseBestTargetCompetitive(escape, opponentsTickDists, stepDistFromMeEscape);
            escapeBest = maybeOpportunisticCoffeeFood(escapeBest, escape, stepDistFromMeEscape);

            if (escapeBest != null && buildPlanFromStateBfs(escape, escapeBest.bait, PlanMode.ESCAPE)) {
                setCommitTarget(escapeBest.bait);
                return true;
            }
        }

        return false;
    }

    /**
     * Stores a short commit window for the chosen target.
     * This reduces oscillation when baits appear/vanish frequently.
     */
    private void setCommitTarget(Bait bait) {
        if (bait == null) return;
        currentTargetKey = key(bait.getX(), bait.getY());
        commitUntilTick = tick + TARGET_COMMIT_TICKS;
    }

    /**
     * Returns true if the bot has no adjacent cell it can enter in SAFE mode.
     * We use it to allow ESCAPE mode only when needed.
     */
    private boolean isStuck(Set<Long> blocked) {
        int x = me.getX();
        int y = me.getY();

        int[][] nbs = new int[][]{
                {x + 1, y},
                {x - 1, y},
                {x, y + 1},
                {x, y - 1}
        };

        for (int[] nb : nbs) {
            if (isCellAllowed(nb[0], nb[1], true, false, blocked)) {
                return false;
            }
        }
        return true;
    }

    // =========================
    // Competitive scoring
    // =========================

    /**
     * Small wrapper that keeps a bait and its computed utility score.
     */
    private static final class ScoredTarget {
        final Bait bait;
        final long score;

        ScoredTarget(Bait bait, long score) {
            this.bait = bait;
            this.score = score;
        }
    }

    /**
     * Picks the best bait using:
     * <ul>
     *   <li>my tick-accurate distance (BFS in (x,y,dir))</li>
     *   <li>opponents tick distance estimate (who arrives first)</li>
     *   <li>bait category priority</li>
     *   <li>danger memory penalty</li>
     * </ul>
     *
     * <p>Important: this function also applies target hysteresis (commit window).</p>
     */
    private ScoredTarget chooseBestTargetCompetitive(
            BfsStateResult myBfs,
            List<int[]> opponentsTickDists,
            int[] myStepDistForGemFilter
    ) {
        if (myBfs == null || myBfs.dist == null) return null;

        // If commit is active and current target still exists, keep it unless a much better option appears.
        ScoredTarget committed = null;
        if (currentTargetKey != null && tick < commitUntilTick) {
            Bait committedBait = baits.get(currentTargetKey);
            if (committedBait != null && classify(committedBait) != Kind.TRAP) {
                long committedScore = computeUtility(committedBait, myBfs, opponentsTickDists, myStepDistForGemFilter);
                // Only keep if it's reachable.
                if (committedScore > Long.MIN_VALUE / 2) {
                    committed = new ScoredTarget(committedBait, committedScore);
                }
            } else {
                currentTargetKey = null;
                commitUntilTick = 0;
            }
        }

        ScoredTarget best = committed;
        for (Bait bait : baits.values()) {
            if (bait == null) continue;

            long util = computeUtility(bait, myBfs, opponentsTickDists, myStepDistForGemFilter);
            if (util <= Long.MIN_VALUE / 2) continue;

            if (best == null || util > best.score) {
                best = new ScoredTarget(bait, util);
            }
        }

        // If commit is active and we found a different target, require a clear improvement.
        if (committed != null && best != null && best.bait != committed.bait) {
            long need = committed.score + (committed.score * SWITCH_IMPROVEMENT_PERCENT) / 100L;
            if (best.score < need) {
                return committed;
            }
        }

        return best;
    }

    /**
     * Computes a utility score for a bait.
     *
     * <p>Returns a very negative value for "invalid" targets (unreachable, trap, too far gem, etc.).</p>
     */
    private long computeUtility(
            Bait bait,
            BfsStateResult myBfs,
            List<int[]> opponentsTickDists,
            int[] myStepDistForGemFilter
    ) {
        Kind kind = classify(bait);
        if (kind == Kind.TRAP) return Long.MIN_VALUE;

        // Reachability (my ticks)
        int myTicks = minTicksToCell(myBfs.dist, bait.getX(), bait.getY());
        if (myTicks < 0) return Long.MIN_VALUE;

        // GEM range filter based on grid steps (cheap and stable)
        if (kind == Kind.GEM && myStepDistForGemFilter != null) {
            int bi = idx(bait.getX(), bait.getY());
            int steps = (bi >= 0 && bi < myStepDistForGemFilter.length) ? myStepDistForGemFilter[bi] : -1;
            if (steps < 0 || steps > GEM_MAX_DISTANCE_STEPS) {
                return Long.MIN_VALUE;
            }
        }

        // Opponent best tick distance
        int oppTicks = minOpponentTicksToCell(opponentsTickDists, bait.getX(), bait.getY());

        // Win probability (very simple discrete model)
        int pWinScaled = winProbabilityScaled(myTicks, oppTicks); // 0..100

        // Danger penalty (only if the target cell is remembered dangerous)
        long dangerPenalty = isDanger(bait.getX(), bait.getY()) ? 1_000_000L : 0;

        // Base priority by kind
        long classBase = switch (kind) {
            case GEM -> 3_000_000L;
            case LETTER -> 2_000_000L;
            case COFFEE -> 1_000_000L;
            case FOOD -> 500_000L;
            default -> 100_000L;
        };

        // Raw value for the bait
        long rawValue = classBase + ((long) bait.getScore()) * 1000L;

        // Expected value (scaled by P(win))
        long expected = (rawValue * pWinScaled) / 100L;

        // Tick cost penalty (planning uses real ticks)
        long tickCost = (long) myTicks * 10L;

        return expected - tickCost - dangerPenalty;
    }

    /**
     * Returns a probability (0..100) to win the race for a bait.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>If no opponent can reach it: 100%</li>
     *   <li>If we arrive strictly earlier: 100%</li>
     *   <li>If we tie: 35% (ties often cause collision/teleport or the other gets it first)</li>
     *   <li>If we arrive later: 0%</li>
     * </ul>
     */
    private int winProbabilityScaled(int myTicks, int oppTicks) {
        if (oppTicks < 0) return 100;
        if (myTicks + 1 < oppTicks) return 100;
        if (myTicks == oppTicks) return 35;
        if (myTicks < oppTicks) return 60; // small edge
        return 0;
    }

    /**
     * Opportunistic detour:
     * If the selected target is COFFEE or FOOD, check if another COFFEE/FOOD is very close,
     * and temporarily take the closer one.
     *
     * <p>This is intentionally simple and local.</p>
     */
    private ScoredTarget maybeOpportunisticCoffeeFood(
            @Nullable ScoredTarget current,
            BfsStateResult myBfs,
            int[] stepDistFromMe
    ) {
        if (current == null || current.bait == null) return current;

        Kind targetKind = classify(current.bait);
        if (targetKind != Kind.COFFEE && targetKind != Kind.FOOD) {
            return current;
        }

        Bait bestNear = null;
        int bestSteps = Integer.MAX_VALUE;
        int bestScore = Integer.MIN_VALUE;

        for (Bait bait : baits.values()) {
            if (bait == null || bait == current.bait) continue;

            Kind k = classify(bait);
            if (k != Kind.COFFEE && k != Kind.FOOD) continue;

            int bi = idx(bait.getX(), bait.getY());
            if (stepDistFromMe == null || bi < 0 || bi >= stepDistFromMe.length) continue;

            int steps = stepDistFromMe[bi];
            if (steps <= 0) continue;
            if (steps > OPPORTUNISTIC_RADIUS_STEPS) continue;

            if (steps < bestSteps || (steps == bestSteps && bait.getScore() > bestScore)) {
                bestSteps = steps;
                bestScore = bait.getScore();
                bestNear = bait;
            }
        }

        if (bestNear == null) return current;

        // Re-score the near target using the same logic (race-aware, tick-aware)
        // Note: reuse computeUtility rather than guessing.
        long nearUtil = computeUtility(bestNear, myBfs, computeOpponentTickDistances(), stepDistFromMe);
        if (nearUtil <= Long.MIN_VALUE / 2) return current;

        return new ScoredTarget(bestNear, nearUtil);
    }

    // =========================
    // Direction-aware BFS (tick-accurate)
    // =========================

    /**
     * Result of direction-aware BFS:
     * <ul>
     *   <li>dist[state] = minimal ticks from start to this (x,y,dir)</li>
     *   <li>parent[state] = previous state index</li>
     *   <li>parentMove[state] = move used to enter this state</li>
     * </ul>
     */
    private static final class BfsStateResult {
        final int[] dist;
        final int[] parent;
        final byte[] parentMove;

        BfsStateResult(int[] dist, int[] parent, byte[] parentMove) {
            this.dist = dist;
            this.parent = parent;
            this.parentMove = parentMove;
        }
    }

    /**
     * Runs BFS on the state graph (x,y,dir) with unit cost edges (each move costs 1 tick).
     *
     * @param avoidDanger    if true, cells in danger memory are treated as blocked.
     * @param allowTrapCells if true, trap bait cells are allowed (ESCAPE mode).
     * @param blockedCells   cells treated as blocked (occupied by others, etc.). Can be null.
     */
    private BfsStateResult bfsStateFrom(
            int startX,
            int startY,
            ViewDirection startDir,
            boolean avoidDanger,
            boolean allowTrapCells,
            @Nullable Set<Long> blockedCells
    ) {
        int cellCount = width * height;
        int stateCount = cellCount * 4;

        int[] dist = new int[stateCount];
        int[] parent = new int[stateCount];
        byte[] parentMove = new byte[stateCount];

        Arrays.fill(dist, -1);
        Arrays.fill(parent, -1);

        int startCell = idx(startX, startY);
        int startDirOrd = dirToOrd(startDir);
        if (startCell < 0 || startCell >= cellCount || startDirOrd < 0) {
            return new BfsStateResult(dist, parent, parentMove);
        }

        int startState = stateIndex(startCell, startDirOrd);

        int[] queue = new int[stateCount];
        int head = 0, tail = 0;

        dist[startState] = 0;
        queue[tail++] = startState;

        while (head < tail) {
            int cur = queue[head++];
            int curDist = dist[cur];

            int curDir = cur / cellCount;
            int curCell = cur - (curDir * cellCount);
            int cx = curCell % width;
            int cy = curCell / width;

            // TURN_L
            int leftDir = (curDir + 3) & 3;
            int leftState = stateIndex(curCell, leftDir);
            if (dist[leftState] == -1) {
                dist[leftState] = curDist + 1;
                parent[leftState] = cur;
                parentMove[leftState] = moveToByte(Move.TURN_L);
                queue[tail++] = leftState;
            }

            // TURN_R
            int rightDir = (curDir + 1) & 3;
            int rightState = stateIndex(curCell, rightDir);
            if (dist[rightState] == -1) {
                dist[rightState] = curDist + 1;
                parent[rightState] = cur;
                parentMove[rightState] = moveToByte(Move.TURN_R);
                queue[tail++] = rightState;
            }

            // STEP
            int[] front = frontCell(cx, cy, ordToDir(curDir));
            if (front != null) {
                int nx = front[0];
                int ny = front[1];
                if (isCellAllowed(nx, ny, avoidDanger, allowTrapCells, blockedCells)) {
                    int nextCell = idx(nx, ny);
                    int stepState = stateIndex(nextCell, curDir);
                    if (dist[stepState] == -1) {
                        dist[stepState] = curDist + 1;
                        parent[stepState] = cur;
                        parentMove[stepState] = moveToByte(Move.STEP);
                        queue[tail++] = stepState;
                    }
                }
            }
        }

        return new BfsStateResult(dist, parent, parentMove);
    }

    /**
     * Builds the move plan by reconstructing moves from the state BFS parents.
     * The goal is any direction on the bait cell (we pick the minimal tick one).
     */
    private boolean buildPlanFromStateBfs(BfsStateResult bfs, Bait target, PlanMode mode) {
        int cellCount = width * height;
        int goalCell = idx(target.getX(), target.getY());
        if (goalCell < 0 || goalCell >= cellCount) return false;

        int startCell = idx(me.getX(), me.getY());
        int startDir = dirToOrd(me.getViewDirection());
        int startState = stateIndex(startCell, startDir);

        // Pick best goal state among all directions on the goal cell.
        int bestGoalState = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            int s = stateIndex(goalCell, d);
            int ds = bfs.dist[s];
            if (ds >= 0 && ds < bestDist) {
                bestDist = ds;
                bestGoalState = s;
            }
        }
        if (bestGoalState < 0) return false;

        // Reconstruct moves (reverse), then reverse into plan.
        ArrayDeque<Move> rev = new ArrayDeque<>();
        int cur = bestGoalState;

        while (cur != startState) {
            int p = bfs.parent[cur];
            if (p < 0) return false;
            Move m = byteToMove(bfs.parentMove[cur]);
            if (m == null) return false;
            rev.addLast(m);
            cur = p;
        }

        plan.clear();
        while (!rev.isEmpty()) {
            plan.addLast(rev.removeLast());
        }

        if (!plan.isEmpty()) {
            planMode = mode;
            return true;
        }
        return false;
    }

    /**
     * Returns minimal ticks to reach a cell (x,y) from a direction-aware dist array.
     * We take the best direction at that cell.
     */
    private int minTicksToCell(int[] distState, int x, int y) {
        int cellCount = width * height;
        int cell = idx(x, y);
        if (cell < 0 || cell >= cellCount) return -1;

        int best = Integer.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            int s = stateIndex(cell, d);
            int v = distState[s];
            if (v >= 0 && v < best) best = v;
        }
        return (best == Integer.MAX_VALUE) ? -1 : best;
    }

    /**
     * Calculates the minimum opponent ticks to reach a cell (x,y).
     */
    private int minOpponentTicksToCell(List<int[]> oppDistStates, int x, int y) {
        if (oppDistStates == null || oppDistStates.isEmpty()) return -1;

        int best = Integer.MAX_VALUE;
        for (int[] dist : oppDistStates) {
            if (dist == null) continue;
            int v = minTicksToCell(dist, x, y);
            if (v >= 0 && v < best) best = v;
        }
        return (best == Integer.MAX_VALUE) ? -1 : best;
    }

    /**
     * Computes direction-aware distance arrays for each opponent (not including myself).
     *
     * <p>We assume opponents avoid traps and do not use our danger memory.</p>
     */
    private List<int[]> computeOpponentTickDistances() {
        if (myPlayerId == null || me == null) return List.of();

        List<int[]> res = new ArrayList<>();
        for (PlayerSnapshot p : players.values()) {
            if (p == null) continue;
            if (p.getId() == myPlayerId) continue;

            // Quick sanity
            if (isOutOfBounds(p.getX(), p.getY())) continue;
            if (!walkable[idx(p.getX(), p.getY())]) continue;

            BfsStateResult bfs = bfsStateFrom(p.getX(), p.getY(), p.getViewDirection(),
                    false, false, null);
            res.add(bfs.dist);
        }
        return res;
    }

    // =========================
    // Cheap grid BFS (steps only) - used for gem range filter
    // =========================

    /**
     * Grid BFS that counts only steps (ignores direction and turn ticks).
     * Useful for cheap range filters (like ignoring far gems).
     */
    private int[] bfsGridSteps(int startX, int startY, boolean avoidDanger, boolean allowTrapCells, @Nullable Set<Long> blockedCells) {
        int n = width * height;
        int[] dist = new int[n];
        Arrays.fill(dist, -1);

        int start = idx(startX, startY);
        if (start < 0 || start >= n) return dist;

        int[] queue = new int[n];
        int head = 0, tail = 0;

        dist[start] = 0;
        queue[tail++] = start;

        final int[] dx = {1, -1, 0, 0};
        final int[] dy = {0, 0, 1, -1};

        while (head < tail) {
            int cur = queue[head++];
            int cx = cur % width;
            int cy = cur / width;

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];
                if (isOutOfBounds(nx, ny)) continue;

                int ni = idx(nx, ny);
                if (dist[ni] != -1) continue;

                if (!isCellAllowed(nx, ny, avoidDanger, allowTrapCells, blockedCells)) continue;

                dist[ni] = dist[cur] + 1;
                queue[tail++] = ni;
            }
        }

        return dist;
    }

    // =========================
    // Movement validation and collision avoidance
    // =========================

    /**
     * Checks whether a STEP forward is possible under the same policy used to create the current plan.
     * This prevents discarding ESCAPE plans (which may require stepping onto a TRAP).
     */
    private boolean canStepForward(PlayerSnapshot snap, PlanMode mode) {
        int[] front = frontCell(snap.getX(), snap.getY(), snap.getViewDirection());
        if (front == null) return false;

        Set<Long> occupied = currentOccupiedCellsByOthers();
        Set<Long> blocked = new HashSet<>(occupied);
        if (AVOID_OPPONENT_FRONT_CELLS && mode != PlanMode.ESCAPE) {
            blocked.addAll(predictedOpponentFrontCells());
        }

        boolean avoidDanger = (mode == PlanMode.SAFE);
        boolean allowTrapCells = (mode == PlanMode.ESCAPE);

        return isCellAllowed(front[0], front[1], avoidDanger, allowTrapCells, blocked);
    }

    /**
     * Returns cells currently occupied by other players (not including myself).
     */
    private Set<Long> currentOccupiedCellsByOthers() {
        if (myPlayerId == null) return Set.of();
        Set<Long> occ = new HashSet<>();
        for (PlayerSnapshot p : players.values()) {
            if (p == null) continue;
            if (p.getId() == myPlayerId) continue;
            occ.add(key(p.getX(), p.getY()));
        }
        return occ;
    }

    /**
     * Returns a set of "front cells" of opponents (one cell ahead of their view direction).
     * This is a small prediction to reduce head-on collisions.
     */
    private Set<Long> predictedOpponentFrontCells() {
        if (myPlayerId == null) return Set.of();
        Set<Long> res = new HashSet<>();

        for (PlayerSnapshot p : players.values()) {
            if (p == null) continue;
            if (p.getId() == myPlayerId) continue;

            int[] f = frontCell(p.getX(), p.getY(), p.getViewDirection());
            if (f == null) continue;

            // Only add if it is a valid walkable cell (otherwise irrelevant).
            if (!isOutOfBounds(f[0], f[1]) && walkable[idx(f[0], f[1])]) {
                res.add(key(f[0], f[1]));
            }
        }
        return res;
    }

    // =========================
    // Cell rules
    // =========================

    /**
     * Checks if the bot is allowed to enter a specific cell under the given policy.
     */
    private boolean isCellAllowed(int x, int y, boolean avoidDanger, boolean allowTrapCells, @Nullable Set<Long> blockedCells) {
        if (isOutOfBounds(x, y)) return false;

        int i = idx(x, y);
        if (!walkable[i]) return false;

        // TRAP handling (dynamic)
        Bait b = baits.get(key(x, y));
        if (!allowTrapCells && b != null && classify(b) == Kind.TRAP) {
            return false;
        }

        // Avoid players (and predicted front cells if provided)
        if (blockedCells != null && blockedCells.contains(key(x, y))) return false;

        // Avoid learned danger zones in SAFE mode
        if (avoidDanger && isDanger(x, y)) return false;

        return true;
    }

    /**
     * Attempts to classify a bait.
     * We try to read a type string if available; otherwise we fall back to score heuristics.
     */
    private Kind classify(Bait bait) {
        try {
            Object type = bait.getType();
            if (type != null) {
                String t = type.toString().toUpperCase(Locale.ROOT);
                if (t.contains("TRAP")) return Kind.TRAP;
                if (t.contains("GEM")) return Kind.GEM;
                if (t.contains("COFFEE")) return Kind.COFFEE;
                if (t.contains("FOOD")) return Kind.FOOD;
                if (t.contains("LETTER") || t.contains("CHAR")) return Kind.LETTER;
            }
        } catch (Exception ignored) {
            // Some versions might not have getType(). Then we fall back to score.
        }

        // Fallback by score (common patterns):
        // - negative score -> trap
        // - 0 -> letter
        // - very high score -> gem
        int s = bait.getScore();
        if (s < 0) return Kind.TRAP;
        if (s == 0) return Kind.LETTER;
        if (s >= 300) return Kind.GEM;
        if (s >= 40) return Kind.COFFEE;
        if (s > 0) return Kind.FOOD;
        return Kind.OTHER;
    }

    // =========================
    // Danger memory
    // =========================

    /**
     * Marks a cell as dangerous for {@link #DANGER_TTL_TICKS} ticks.
     * Used after we get teleported by a trap.
     */
    private void markDanger(int x, int y) {
        if (isOutOfBounds(x, y)) return;
        dangerUntilTick.put(key(x, y), tick + DANGER_TTL_TICKS);
    }

    /**
     * Returns true if a cell is currently remembered as dangerous.
     */
    private boolean isDanger(int x, int y) {
        Long until = dangerUntilTick.get(key(x, y));
        if (until == null) return false;
        if (tick >= until) {
            dangerUntilTick.remove(key(x, y));
            return false;
        }
        return true;
    }

    /** Clears the current plan and resets plan mode back to SAFE. */
    private void clearPlan() {
        plan.clear();
        planMode = PlanMode.SAFE;
    }

    // =========================
    // Direction helpers + indexing
    // =========================

    /**
     * Returns the coordinates of the cell in front of (x,y) in the given direction,
     * or null if that cell is outside the maze.
     */
    private int[] frontCell(int x, int y, ViewDirection dir) {
        int dx = 0, dy = 0;

        if (dir == ViewDirection.NORTH) dy = -1;
        else if (dir == ViewDirection.SOUTH) dy = 1;
        else if (dir == ViewDirection.EAST) dx = 1;
        else if (dir == ViewDirection.WEST) dx = -1;
        else return null;

        int nx = x + dx;
        int ny = y + dy;

        if (isOutOfBounds(nx, ny)) return null;
        return new int[]{nx, ny};
    }

    /**
     * @return true if the (x,y) coordinates are outside the maze bounds.
     */
    private boolean isOutOfBounds(int x, int y) {
        return x < 0 || y < 0 || x >= width || y >= height;
    }

    /**
     * Converts (x,y) to a linear index in {@link #walkable}.
     */
    private int idx(int x, int y) {
        return y * width + x;
    }

    /**
     * Builds a unique long key for a cell coordinate.
     * High 32 bits are x, low 32 bits are y.
     */
    private long key(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }

    /**
     * Converts a ViewDirection into a small ordinal (0..3).
     * We use: 0=N, 1=E, 2=S, 3=W.
     */
    private int dirToOrd(ViewDirection d) {
        if (d == ViewDirection.NORTH) return 0;
        if (d == ViewDirection.EAST) return 1;
        if (d == ViewDirection.SOUTH) return 2;
        if (d == ViewDirection.WEST) return 3;
        return -1;
    }

    /**
     * Converts an ordinal (0..3) back to ViewDirection.
     */
    private ViewDirection ordToDir(int ord) {
        return switch (ord & 3) {
            case 0 -> ViewDirection.NORTH;
            case 1 -> ViewDirection.EAST;
            case 2 -> ViewDirection.SOUTH;
            default -> ViewDirection.WEST;
        };
    }

    /**
     * Encodes a (cellIndex, dirOrd) into a state index.
     */
    private int stateIndex(int cellIdx, int dirOrd) {
        return dirOrd * (width * height) + cellIdx;
    }

    /**
     * Encodes a Move into a small byte to store it in arrays.
     */
    private byte moveToByte(Move m) {
        if (m == Move.TURN_L) return 1;
        if (m == Move.TURN_R) return 2;
        if (m == Move.STEP) return 3;
        return 0;
    }

    /**
     * Decodes a stored byte back to a Move.
     */
    private Move byteToMove(byte b) {
        return switch (b) {
            case 1 -> Move.TURN_L;
            case 2 -> Move.TURN_R;
            case 3 -> Move.STEP;
            default -> null;
        };
    }
}
