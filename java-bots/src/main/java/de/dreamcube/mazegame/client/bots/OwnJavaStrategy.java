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
 * A simple maze bot strategy that:
 *
 * <ul>
 *   <li>Plans paths with BFS (shortest path on a grid).</li>
 *   <li>Avoids known-danger cells (learned after TRAP teleports) for a limited time.</li>
 *   <li>Avoids TRAP cells normally, but can step onto a trap if it is stuck (surrounded / no safe exit).</li>
 *   <li>Limits GEM hunting to a maximum number of steps.</li>
 *   <li>Optionally "detours" to pick a nearby COFFEE/FOOD (within 2 steps) when already targeting COFFEE/FOOD.</li>
 * </ul>
 *
 * <p>Important note about naming: in this code {@link #isOutOfBounds(int, int)} returns {@code true} if
 * the coordinates are outside the maze.</p>
 */
@Bot("Tarnished")
public class OwnJavaStrategy extends Strategy
        implements MazeEventListener,
        BaitEventListener,
        PlayerMovementListener,
        PlayerConnectionListener {

    /** Maximum search range for gems (in BFS steps). Gems farther than this are ignored. */
    private static final int GEM_MAX_DISTANCE_STEPS = 18;

    /**
     * When the bot is targeting COFFEE or FOOD, it may take a short detour for another COFFEE/FOOD
     * that is reachable within this many steps from the current position.
     */
    private static final int OPPORTUNISTIC_RADIUS_STEPS = 2;

    /**
     * How long (in ticks) a cell stays in the "danger memory" after we got teleported by a TRAP.
     * The bot avoids these cells in SAFE mode.
     */
    private static final long DANGER_TTL_TICKS = 250;

    // =========================
    // Maze state
    // =========================

    private int width, height;

    /**
     * Walkable cells map: {@code true} means the maze tile is floor and can be entered.
     * This does NOT include dynamic hazards like TRAP baits; those are handled separately.
     *
     * <p>Indexing is {@code idx = y * width + x}.</p>
     */
    private boolean[] walkable;

    /**
     * Baits known by the bot, keyed by coordinate (x,y) combined into a {@code long}.
     * We store baits by position because multiple IDs are irrelevant for pathfinding.
     */
    private final Map<Long, Bait> baits = new ConcurrentHashMap<>();

    /** Other players, used only to avoid stepping into their current cell. */
    private final Map<Integer, PlayerSnapshot> players = new ConcurrentHashMap<>();

    // =========================
    // Own player state
    // =========================

    private Integer myPlayerId = null;
    private volatile PlayerSnapshot me;

    /**
     * Current planned action queue. When empty we compute a new plan.
     * We clear this plan on any significant world change (bait appear/vanish, teleport, etc.).
     */
    private final Deque<Move> plan = new ArrayDeque<>();

    /** The policy used to build the current plan (important for validating STEP moves). */
    private volatile PlanMode planMode = PlanMode.SAFE;

    private volatile boolean paused = false;
    private long tick = 0;

    /**
     * "Danger memory": cellKey -> expiryTick.
     * When we get teleported by a TRAP, we mark the trigger cell (and sometimes the cell in front)
     * as dangerous for a while.
     */
    private final Map<Long, Long> dangerUntilTick = new ConcurrentHashMap<>();

    // =========================
    // Events: Maze / Baits / Players
    // =========================

    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        this.width = width;
        this.height = height;
        this.walkable = new boolean[width * height];

        // '.' is walkable; '#', '-' and other chars are treated as walls
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
        clearPlan(); // re-plan quickly when new bait appears
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        baits.remove(key(bait.getX(), bait.getY()));
        clearPlan(); // re-plan quickly when bait is gone
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

                // Also mark the cell in front of old position (some traps trigger on enter).
                int[] front = frontCell(oldPos.getX(), oldPos.getY(), oldPos.getViewDirection());
                if (front != null) {
                    markDanger(front[0], front[1]);
                }
            }
        }
    }

    private void updateMeIfOwn(PlayerSnapshot snap) {
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            me = snap;
        }
    }

    /**
     * Called every tick. Returns the next move.
     *
     * <p>We validate planned STEP moves: if the next tile is no longer allowed (e.g. another player moved),
     * we drop the plan and re-plan.</p>
     */
    @Nullable
    @Override
    protected Move getNextMove() {
        tick++;

        if (paused || walkable == null || me == null || myPlayerId == null) {
            return Move.DO_NOTHING;
        }

        // Validate the first planned STEP (only the first one).
        if (!plan.isEmpty() && plan.peekFirst() == Move.STEP) {
            if (!canStepForward(me, planMode)) {
                clearPlan();
            }
        }

        if (plan.isEmpty()) {
            boolean planned = planToBestTarget();
            if (!planned) {
                // Simple fallback: try to walk forward if possible, otherwise rotate.
                return canStepForward(me, PlanMode.SAFE) ? Move.STEP : Move.TURN_R;
            }
        }

        return plan.pollFirst();
    }

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

    /**
     * Creates a plan to the "best" target based on reachable distance and a simple utility function.
     *
     * <p>Fallback order:</p>
     * <ol>
     *   <li>SAFE: avoid danger + avoid traps</li>
     *   <li>RELAXED: ignore danger + avoid traps</li>
     *   <li>ESCAPE: allow traps ONLY if stuck (no safe exit)</li>
     * </ol>
     */
    private boolean planToBestTarget() {
        Set<Long> occupied = currentOccupiedCellsByOthers();

        // 1) SAFE
        BfsResult safe = bfsFrom(me.getX(), me.getY(), true, false, occupied);
        Bait safeTarget = chooseBestTarget(safe.dist);
        safeTarget = maybeOpportunisticCoffeeFood(safeTarget, safe.dist);

        if (safeTarget != null && buildPlanFromBfs(safe, safeTarget, PlanMode.SAFE)) {
            return true;
        }

        // 2) RELAXED (ignore learned danger)
        BfsResult relaxed = bfsFrom(me.getX(), me.getY(), false, false, occupied);
        Bait relaxedTarget = chooseBestTarget(relaxed.dist);
        relaxedTarget = maybeOpportunisticCoffeeFood(relaxedTarget, relaxed.dist);

        if (relaxedTarget != null && buildPlanFromBfs(relaxed, relaxedTarget, PlanMode.RELAXED)) {
            return true;
        }

        // 3) ESCAPE: only if we have no safe exit (surrounded / blocked)
        if (isStuck(occupied)) {
            BfsResult escape = bfsFrom(me.getX(), me.getY(), false, true, occupied);
            Bait escapeTarget = chooseBestTarget(escape.dist); // still never choose TRAP as a target
            escapeTarget = maybeOpportunisticCoffeeFood(escapeTarget, escape.dist);

            if (escapeTarget != null && buildPlanFromBfs(escape, escapeTarget, PlanMode.ESCAPE)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Builds the move plan from a BFS result (parents array).
     * Sets {@link #planMode} to match the policy used to create the plan.
     */
    private boolean buildPlanFromBfs(BfsResult bfs, Bait target, PlanMode mode) {
        int startIdx = idx(me.getX(), me.getY());
        int goalIdx = idx(target.getX(), target.getY());

        if (goalIdx < 0 || goalIdx >= bfs.dist.length) return false;
        if (bfs.dist[goalIdx] < 0) return false;

        List<Integer> path = reconstructPath(startIdx, goalIdx, bfs.parent);
        if (path.isEmpty()) return false;

        plan.clear();
        appendMovesForPath(plan, path, me.getX(), me.getY(), me.getViewDirection());

        if (!plan.isEmpty()) {
            planMode = mode;
            return true;
        }

        return false;
    }

    /**
     * Returns {@code true} if the bot has no adjacent cell it can enter in SAFE mode.
     * This is our "stuck" detection (e.g. surrounded by traps or remembered-danger zones).
     */
    private boolean isStuck(Set<Long> occupiedByOthers) {
        int x = me.getX();
        int y = me.getY();

        int[][] nbs = new int[][]{
                {x + 1, y},
                {x - 1, y},
                {x, y + 1},
                {x, y - 1}
        };

        for (int[] nb : nbs) {
            if (isCellAllowed(nb[0], nb[1], true, false, occupiedByOthers)) {
                return false;
            }
        }
        return true;
    }

    /** High-level bait groups used for priorities. */
    private enum Kind { GEM, LETTER, COFFEE, FOOD, OTHER, TRAP }

    /**
     * Chooses the best target bait given a distance array.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Never target TRAP.</li>
     *   <li>Ignore unreachable cells ({@code dist < 0}).</li>
     *   <li>Ignore gems beyond {@link #GEM_MAX_DISTANCE_STEPS}.</li>
     *   <li>Use a simple utility score: class priority + bait score - distance penalty - danger penalty.</li>
     * </ul>
     */
    private Bait chooseBestTarget(int[] dist) {
        if (dist == null || dist.length == 0) return null;

        Bait best = null;
        long bestScore = Long.MIN_VALUE;

        for (Bait bait : baits.values()) {
            Kind kind = classify(bait);

            // Never go *for* a trap.
            if (kind == Kind.TRAP) continue;

            int bi = idx(bait.getX(), bait.getY());
            if (bi < 0 || bi >= dist.length) continue;

            int d = dist[bi];
            if (d < 0) continue; // unreachable

            // Limit GEM range
            if (kind == Kind.GEM && d > GEM_MAX_DISTANCE_STEPS) {
                continue;
            }

            // Penalize if the target cell is currently in danger memory
            long dangerPenalty = isDanger(bait.getX(), bait.getY()) ? 1_000_000L : 0;

            // Priority by type (adjust as you like)
            long classBase = switch (kind) {
                case GEM -> 3_000_000L;
                case LETTER -> 2_000_000L;
                case COFFEE -> 1_000_000L;
                case FOOD -> 500_000L;
                default -> 100_000L;
            };

            // Simple utility
            long util = classBase
                    + ((long) bait.getScore()) * 1000L
                    - (long) d * 10L
                    - dangerPenalty;

            if (util > bestScore) {
                bestScore = util;
                best = bait;
            }
        }

        return best;
    }

    /**
     * Opportunistic detour:
     * If we are currently targeting COFFEE or FOOD, and there is another COFFEE/FOOD reachable within
     * {@link #OPPORTUNISTIC_RADIUS_STEPS} from the current position, we temporarily target the nearer one.
     *
     * <p>This is a simple approximation of "on the way". It only checks the local neighborhood around the bot.</p>
     */
    private Bait maybeOpportunisticCoffeeFood(@Nullable Bait currentTarget, int[] distFromMe) {
        if (currentTarget == null || distFromMe == null) return currentTarget;

        Kind targetKind = classify(currentTarget);
        if (targetKind != Kind.COFFEE && targetKind != Kind.FOOD) {
            return currentTarget;
        }

        Bait bestNear = null;
        int bestDist = Integer.MAX_VALUE;
        int bestScore = Integer.MIN_VALUE;

        for (Bait bait : baits.values()) {
            if (bait == currentTarget) continue;

            Kind k = classify(bait);
            if (k != Kind.COFFEE && k != Kind.FOOD) continue; // only C/F

            int bi = idx(bait.getX(), bait.getY());
            if (bi < 0 || bi >= distFromMe.length) continue;

            int d = distFromMe[bi];
            if (d <= 0) continue; // ignore current cell or unreachable
            if (d > OPPORTUNISTIC_RADIUS_STEPS) continue;

            // Prefer closer; tie-break with bait score
            if (d < bestDist || (d == bestDist && bait.getScore() > bestScore)) {
                bestDist = d;
                bestScore = bait.getScore();
                bestNear = bait;
            }
        }

        return (bestNear != null) ? bestNear : currentTarget;
    }

    /**
     * Attempts to classify a bait. We try to read a type string if available;
     * if not, we fall back to heuristics based on score.
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
            // If Bait has no getType(), we just use fallback.
        }

        // Fallback by score:
        // - negative score -> trap
        // - 0 -> letter
        // - high score -> gem
        int s = bait.getScore();
        if (s < 0) return Kind.TRAP;
        if (s == 0) return Kind.LETTER;
        if (s >= 300) return Kind.GEM;
        if (s >= 40) return Kind.COFFEE;
        if (s > 0) return Kind.FOOD;
        return Kind.OTHER;
    }
    /**
     * Result of a BFS run:
     * <ul>
     *   <li>{@code dist[i]} = distance from start to cell i, or -1 if unreachable</li>
     *   <li>{@code parent[i]} = previous cell on the shortest path (for reconstruction)</li>
     * </ul>
     */
    private static final class BfsResult {
        final int[] dist;
        final int[] parent;

        BfsResult(int[] dist, int[] parent) {
            this.dist = dist;
            this.parent = parent;
        }
    }

    /**
     * Runs BFS from the given start cell.
     *
     * @param avoidDanger      if true, cells in {@link #dangerUntilTick} are treated as blocked.
     * @param allowTrapCells   if true, trap bait cells are allowed (used only in ESCAPE mode).
     * @param occupiedByOthers cells occupied by other players are treated as blocked.
     */
    private BfsResult bfsFrom(int startX, int startY, boolean avoidDanger, boolean allowTrapCells, Set<Long> occupiedByOthers) {
        int n = width * height;
        int[] dist = new int[n];
        int[] parent = new int[n];
        Arrays.fill(dist, -1);
        Arrays.fill(parent, -1);

        int start = idx(startX, startY);
        if (start < 0 || start >= n) return new BfsResult(dist, parent);

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

                if (!isCellAllowed(nx, ny, avoidDanger, allowTrapCells, occupiedByOthers)) continue;

                dist[ni] = dist[cur] + 1;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
        }

        return new BfsResult(dist, parent);
    }

    /**
     * Checks if the bot is allowed to enter a specific cell under the given policy.
     */
    private boolean isCellAllowed(int x, int y, boolean avoidDanger, boolean allowTrapCells, Set<Long> occupiedByOthers) {
        if (isOutOfBounds(x, y)) return false;

        int i = idx(x, y);
        if (!walkable[i]) return false;

        // TRAP handling (dynamic)
        Bait b = baits.get(key(x, y));
        if (!allowTrapCells && b != null && classify(b) == Kind.TRAP) {
            return false;
        }

        // Avoid other players
        if (occupiedByOthers != null && occupiedByOthers.contains(key(x, y))) return false;

        // Avoid learned danger zones in SAFE mode
        if (avoidDanger && isDanger(x, y)) return false;

        return true;
    }

    /**
     * Reconstructs a path from start->goal using the BFS parent array.
     * Returns a list of cell indices excluding the start cell (so the first entry is the next cell to enter).
     */
    private List<Integer> reconstructPath(int startIdx, int goalIdx, int[] parent) {
        List<Integer> rev = new ArrayList<>();
        int cur = goalIdx;

        while (cur != -1 && cur != startIdx) {
            rev.add(cur);
            cur = parent[cur];
        }

        if (cur != startIdx) return List.of(); // no path
        Collections.reverse(rev);
        return rev;
    }

    /**
     * Converts a path of cell indices into a sequence of moves (turns + steps),
     * starting from the current position and view direction.
     */
    private void appendMovesForPath(Deque<Move> out, List<Integer> path, int startX, int startY, ViewDirection startDir) {
        int cx = startX;
        int cy = startY;
        ViewDirection dir = startDir;

        for (int cellIdx : path) {
            int nx = cellIdx % width;
            int ny = cellIdx / width;

            int dx = nx - cx;
            int dy = ny - cy;

            ViewDirection desired = directionFromDelta(dx, dy);
            if (desired == null) {
                out.clear();
                return;
            }

            // Turn as little as possible to face the desired direction
            List<Move> turns = minimalTurns(dir, desired);
            for (Move m : turns) {
                out.addLast(m);
                dir = applyTurn(dir, m);
            }

            out.addLast(Move.STEP);

            cx = nx;
            cy = ny;
        }
    }

    private ViewDirection applyTurn(ViewDirection d, Move m) {
        if (m == Move.TURN_R) return d.turnRight();
        if (m == Move.TURN_L) return d.turnLeft();
        return d;
    }

    /**
     * Returns the minimal set of turns from one direction to another.
     * Uses either N right turns or N left turns, whichever is smaller.
     */
    private List<Move> minimalTurns(ViewDirection from, ViewDirection to) {
        int right = 0;
        ViewDirection d = from;
        while (!d.equals(to) && right < 4) {
            d = d.turnRight();
            right++;
        }

        int left = 0;
        d = from;
        while (!d.equals(to) && left < 4) {
            d = d.turnLeft();
            left++;
        }

        if (right <= left) {
            List<Move> res = new ArrayList<>(right);
            for (int i = 0; i < right; i++) res.add(Move.TURN_R);
            return res;
        } else {
            List<Move> res = new ArrayList<>(left);
            for (int i = 0; i < left; i++) res.add(Move.TURN_L);
            return res;
        }
    }

    /**
     * Converts a (dx,dy) delta into a cardinal direction.
     */
    private ViewDirection directionFromDelta(int dx, int dy) {
        if (dx == 1 && dy == 0) return ViewDirection.EAST;
        if (dx == -1 && dy == 0) return ViewDirection.WEST;
        if (dx == 0 && dy == 1) return ViewDirection.SOUTH;
        if (dx == 0 && dy == -1) return ViewDirection.NORTH;
        return null;
    }
    /**
     * Checks whether a STEP forward is possible under the same policy that created the current plan.
     * This prevents the bot from discarding ESCAPE plans (which may need to step on a TRAP).
     */
    private boolean canStepForward(PlayerSnapshot snap, PlanMode mode) {
        int[] front = frontCell(snap.getX(), snap.getY(), snap.getViewDirection());
        if (front == null) return false;

        Set<Long> occupied = currentOccupiedCellsByOthers();

        boolean avoidDanger = (mode == PlanMode.SAFE);
        boolean allowTrapCells = (mode == PlanMode.ESCAPE);

        return isCellAllowed(front[0], front[1], avoidDanger, allowTrapCells, occupied);
    }

    /**
     * Returns the coordinates of the cell in front of (x,y) in the given direction,
     * or {@code null} if that cell is outside the maze.
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
     * Returns a set of cells currently occupied by other players (not including myself).
     * We treat these as blocked to reduce collisions.
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
     * Marks a cell as dangerous for {@link #DANGER_TTL_TICKS} ticks.
     * We only use this after we got teleported by a trap.
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
}
