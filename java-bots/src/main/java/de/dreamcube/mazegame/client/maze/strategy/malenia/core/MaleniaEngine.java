package de.dreamcube.mazegame.client.maze.strategy.malenia.core;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import static de.dreamcube.mazegame.client.maze.BaitKt.combineIntsToLong;

/**
 * Core runtime for the "Malenia" bot.
 * Bundles minimal state + planning logic to keep package/class count low (KISS/YAGNI).
 */
public final class MaleniaEngine {

    /** Default tuning values. */
    private static final PlannerConfig DEFAULT_CONFIG = new PlannerConfig(
            40,    // maxDepth
            6000,  // maxExpansions
            24,    // candidateBaits
            6.0,   // moveCost
            250.0  // trapStepPenalty
    );

    /** Server uses -128 for traps. Used when simulating a trap step. */
    private static final int TRAP_SCORE = -128;

    private final MazeModel maze = new MazeModel();
    private final ConcurrentHashMap<Long, Bait> baits = new ConcurrentHashMap<>();
    private final ClientAccess clientAccess = new ClientAccess();
    private final RewardPlanner planner;

    public MaleniaEngine() {
        this(DEFAULT_CONFIG);
    }

    public MaleniaEngine(@NotNull PlannerConfig config) {
        this.planner = new RewardPlanner(config);
    }

    public void updateMaze(int width, int height, @NotNull List<String> mazeLines) {
        maze.updateFromMazeLines(width, height, mazeLines);
    }

    public void onBaitAppeared(@NotNull Bait bait) {
        baits.put(combineIntsToLong(bait.getX(), bait.getY()), bait);
    }

    public void onBaitVanished(@NotNull Bait bait) {
        baits.remove(combineIntsToLong(bait.getX(), bait.getY()));
    }

    public @Nullable Decision nextDecision(@NotNull Strategy strategy, @NotNull String fallbackNick) {
        if (!maze.isReady()) return null;

        BotState me = clientAccess.readOwnState(strategy, fallbackNick);
        if (me == null) return null;

        // Defensive fallback: if event tracking didn't deliver baits yet, pull them once.
        if (baits.isEmpty()) {
            clientAccess.refreshBaits(strategy, baits);
        }

        ArrayList<Bait> baitSnapshot = new ArrayList<>(baits.values());
        if (baitSnapshot.isEmpty()) return null;

        List<PlayerState> others = clientAccess.readOtherPlayers(strategy, fallbackNick, me);
        boolean[] occupied = others.isEmpty() ? null : buildOccupiedGrid(maze, others);

        RewardPlanner.PlanResult plan = planner.plan(new RewardPlanner.PlanRequest(maze, baitSnapshot, me, occupied));
        if (plan == null || plan.firstMove() == null) return null;

        RewardPlanner.PlanResult picked = plan;

        // If another player is very close and approaching our target in a straight line,
        // switch target early to avoid wasting moves (and collisions near the bait).
        Point target = plan.target();
        if (target != null && isTargetContested(target, maze, occupied, others, 3)) {
            ArrayList<Bait> filtered = new ArrayList<>(baitSnapshot.size());
            for (Bait b : baitSnapshot) {
                if (b.getX() == target.x && b.getY() == target.y) continue;
                filtered.add(b);
            }

            RewardPlanner.PlanResult alt = planner.plan(new RewardPlanner.PlanRequest(maze, filtered, me, occupied));
            if (alt != null && alt.firstMove() != null) {
                picked = alt;
            }
        }

        Move move = avoidImmediateCollision(picked.firstMove(), me, others, maze, occupied);
        return new Decision(move, picked.utility(), picked.path(), picked.target(), picked.targetLabel());
    }

    /** Public result used by the strategy (for move + visualization). */
    public static final class Decision {
        private final Move firstMove;
        private final double utility;
        private final List<Point> path;
        private final Point target;
        private final String targetLabel;

        Decision(@NotNull Move firstMove,
                 double utility,
                 @NotNull List<Point> path,
                 @Nullable Point target,
                 @Nullable String targetLabel) {
            this.firstMove = firstMove;
            this.utility = utility;
            this.path = path;
            this.target = target;
            this.targetLabel = targetLabel;
        }

        public @NotNull Move firstMove() { return firstMove; }
        public double utility() { return utility; }
        public @NotNull List<Point> path() { return path; }
        public @Nullable Point target() { return target; }
        public @Nullable String targetLabel() { return targetLabel; }
    }

    // ---------------------------------------------------------------------
    // Dynamic player avoidance (cheap, no heavy re-planning / no oscillation logic)
    // ---------------------------------------------------------------------

    private static @Nullable boolean[] buildOccupiedGrid(@NotNull MazeModel maze, @NotNull List<PlayerState> players) {
        int w = maze.getWidth();
        int h = maze.getHeight();
        if (w <= 0 || h <= 0) return null;

        boolean[] occupied = new boolean[w * h];
        for (PlayerState p : players) {
            if (p == null) continue;
            if (!maze.inBounds(p.x(), p.y())) continue;
            occupied[p.y() * w + p.x()] = true;
        }
        return occupied;
    }

    private static @NotNull Move avoidImmediateCollision(@NotNull Move plannedMove,
                                                         @NotNull BotState me,
                                                         @NotNull List<PlayerState> others,
                                                         @NotNull MazeModel maze,
                                                         @Nullable boolean[] occupied) {
        if (plannedMove != Move.STEP) return plannedMove;

        int w = maze.getWidth();
        int nx = forwardX(me.x(), me.direction());
        int ny = forwardY(me.y(), me.direction());

        if (!maze.inBounds(nx, ny) || !maze.isWalkable(nx, ny)) return plannedMove;

        // Avoid stepping into a cell occupied right now.
        if (occupied != null && occupied[ny * w + nx]) {
            return chooseAvoidanceTurn(me, maze, occupied);
        }

        // Avoid stepping into a cell another bot is very likely to step into on the same tick.
        for (PlayerState p : others) {
            if (p == null) continue;
            int px = forwardX(p.x(), p.direction());
            int py = forwardY(p.y(), p.direction());
            if (px == nx && py == ny) {
                return chooseAvoidanceTurn(me, maze, occupied);
            }
        }

        return plannedMove;
    }

    private static @NotNull Move chooseAvoidanceTurn(@NotNull BotState me, @NotNull MazeModel maze, @Nullable boolean[] occupied) {
        ViewDirection left = turnLeft(me.direction());
        ViewDirection right = turnRight(me.direction());

        boolean leftOk = isFrontCellFree(me.x(), me.y(), left, maze, occupied);
        boolean rightOk = isFrontCellFree(me.x(), me.y(), right, maze, occupied);

        if (leftOk && !rightOk) return Move.TURN_L;
        if (rightOk && !leftOk) return Move.TURN_R;

        // If both are ok or both blocked, prefer a deterministic choice to keep behaviour stable.
        return Move.TURN_L;
    }

    private static boolean isFrontCellFree(int x, int y, @NotNull ViewDirection dir, @NotNull MazeModel maze, @Nullable boolean[] occupied) {
        int nx = forwardX(x, dir);
        int ny = forwardY(y, dir);

        if (!maze.isWalkable(nx, ny)) return false;
        if (occupied == null) return true;

        int w = maze.getWidth();
        return maze.inBounds(nx, ny) && !occupied[ny * w + nx];
    }

    /**
     * Returns true if a different player is within {@code maxStraightSteps} and approaching the bait
     * in a straight line (same row/col + direction points towards the bait) with a clear corridor.
     */
    private static boolean isTargetContested(@NotNull Point target,
                                             @NotNull MazeModel maze,
                                             @Nullable boolean[] occupied,
                                             @NotNull List<PlayerState> others,
                                             int maxStraightSteps) {
        for (PlayerState p : others) {
            if (p == null) continue;
            if (isDirectApproachWithin(p, target, maze, occupied, maxStraightSteps)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirectApproachWithin(@NotNull PlayerState p,
                                                  @NotNull Point target,
                                                  @NotNull MazeModel maze,
                                                  @Nullable boolean[] occupied,
                                                  int maxSteps) {
        int w = maze.getWidth();

        switch (p.direction()) {
            case NORTH:
                if (p.x() != target.x) return false;
                if (target.y >= p.y()) return false;
                if ((p.y() - target.y) > maxSteps) return false;
                for (int y = p.y() - 1; y >= target.y; y--) {
                    if (!maze.isWalkable(p.x(), y)) return false;
                    if (occupied != null && occupied[y * w + p.x()]) return false;
                }
                return true;

            case SOUTH:
                if (p.x() != target.x) return false;
                if (target.y <= p.y()) return false;
                if ((target.y - p.y()) > maxSteps) return false;
                for (int y = p.y() + 1; y <= target.y; y++) {
                    if (!maze.isWalkable(p.x(), y)) return false;
                    if (occupied != null && occupied[y * w + p.x()]) return false;
                }
                return true;

            case EAST:
                if (p.y() != target.y) return false;
                if (target.x <= p.x()) return false;
                if ((target.x - p.x()) > maxSteps) return false;
                for (int x = p.x() + 1; x <= target.x; x++) {
                    if (!maze.isWalkable(x, p.y())) return false;
                    if (occupied != null && occupied[p.y() * w + x]) return false;
                }
                return true;

            case WEST:
                if (p.y() != target.y) return false;
                if (target.x >= p.x()) return false;
                if ((p.x() - target.x) > maxSteps) return false;
                for (int x = p.x() - 1; x >= target.x; x--) {
                    if (!maze.isWalkable(x, p.y())) return false;
                    if (occupied != null && occupied[p.y() * w + x]) return false;
                }
                return true;

            default:
                return false;
        }
    }

    private static int forwardX(int x, @NotNull ViewDirection dir) {
        switch (dir) {
            case EAST:  return x + 1;
            case WEST:  return x - 1;
            default:    return x;
        }
    }

    private static int forwardY(int y, @NotNull ViewDirection dir) {
        switch (dir) {
            case SOUTH: return y + 1;
            case NORTH: return y - 1;
            default:    return y;
        }
    }

    private static @NotNull ViewDirection turnLeft(@NotNull ViewDirection d) {
        switch (d) {
            case NORTH: return ViewDirection.WEST;
            case EAST:  return ViewDirection.NORTH;
            case SOUTH: return ViewDirection.EAST;
            case WEST:  return ViewDirection.SOUTH;
            default:    return d;
        }
    }

    private static @NotNull ViewDirection turnRight(@NotNull ViewDirection d) {
        switch (d) {
            case NORTH: return ViewDirection.EAST;
            case EAST:  return ViewDirection.SOUTH;
            case SOUTH: return ViewDirection.WEST;
            case WEST:  return ViewDirection.NORTH;
            default:    return d;
        }
    }

    // ---------------------------------------------------------------------
    // Internal model
    // ---------------------------------------------------------------------

    private static final class BotState {
        private final int x;
        private final int y;
        private final ViewDirection direction;

        BotState(int x, int y, @NotNull ViewDirection direction) {
            this.x = x;
            this.y = y;
            this.direction = direction;
        }

        int x() { return x; }
        int y() { return y; }
        @NotNull ViewDirection direction() { return direction; }
    }

    private static final class PlayerState {
        private final int x;
        private final int y;
        private final ViewDirection direction;
        private final @Nullable String nick;

        PlayerState(int x, int y, @NotNull ViewDirection direction, @Nullable String nick) {
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.nick = nick;
        }

        int x() { return x; }
        int y() { return y; }
        @NotNull ViewDirection direction() { return direction; }
        @Nullable String nick() { return nick; }
    }

    /**
     * Maze snapshot with a walkable grid.
     * Thread-safe by publication: build a new array then assign reference.
     */
    private static final class MazeModel {
        private volatile int width;
        private volatile int height;
        private volatile boolean[] walkable;

        void updateFromMazeLines(int width, int height, @NotNull List<String> mazeLines) {
            boolean[] w = new boolean[Math.max(0, width) * Math.max(0, height)];

            for (int y = 0; y < height && y < mazeLines.size(); y++) {
                String line = mazeLines.get(y);
                if (line == null) continue;

                int perCell = 1;
                if (width > 0) {
                    // Common encodings:
                    //  - width chars (one per cell)
                    //  - 2*width or 2*width-1 chars (one char per cell + separator)
                    //  - N*width chars (fixed-width multi-char cells)
                    if (line.length() == (width * 2) || line.length() == (width * 2 - 1)) {
                        perCell = 2;
                    } else if (line.length() >= width && line.length() % width == 0) {
                        perCell = Math.max(1, line.length() / width);
                    }
                }

                for (int x = 0; x < width; x++) {
                    int idx = x * perCell;
                    char c = (idx < line.length()) ? line.charAt(idx) : '#';
                    w[y * width + x] = !isBlockedChar(c);
                }
            }

            this.width = width;
            this.height = height;
            this.walkable = w;
        }

        boolean isReady() {
            return walkable != null && width > 0 && height > 0;
        }

        boolean inBounds(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }

        boolean isWalkable(int x, int y) {
            boolean[] w = walkable;
            return w != null && inBounds(x, y) && w[y * width + x];
        }

        int getWidth() { return width; }
        int getHeight() { return height; }
        boolean[] walkableGrid() { return walkable; }

        private static boolean isBlockedChar(char c) {
            return c == '#'
                    || c == 'X'
                    || c == 'W'
                    || c == '█'
                    || c == '■'
                    || c == '?'
                    || c == 'O'
                    || c == 'o'
                    || c == '1';
        }
    }

    /** Tuning parameters for the reward planner. */
    public static final class PlannerConfig {
        private final int maxDepth;
        private final int maxExpansions;
        private final int candidateBaits;
        private final double moveCost;
        private final double trapStepPenalty;

        public PlannerConfig(int maxDepth,
                             int maxExpansions,
                             int candidateBaits,
                             double moveCost,
                             double trapStepPenalty) {
            this.maxDepth = maxDepth;
            this.maxExpansions = maxExpansions;
            this.candidateBaits = candidateBaits;
            this.moveCost = moveCost;
            this.trapStepPenalty = trapStepPenalty;
        }

        public int maxDepth() { return maxDepth; }
        public int maxExpansions() { return maxExpansions; }
        public int candidateBaits() { return candidateBaits; }
        public double moveCost() { return moveCost; }
        public double trapStepPenalty() { return trapStepPenalty; }
    }

    // ---------------------------------------------------------------------
    // Reflection-based client access (kept to avoid relying on unstable API)
    // ---------------------------------------------------------------------

    private static final class ClientAccess {
        private volatile boolean mazeClientFieldResolved;
        private volatile @Nullable Field mazeClientField;

        @Nullable BotState readOwnState(@NotNull Strategy strategy, @NotNull String fallbackNick) {
            Object client = getMazeClient(strategy);
            if (client == null) return null;

            Object me = invokeNoArg(client, "getOwnPlayerSnapshot");
            if (me == null) me = invokeNoArg(client, "getOwnPlayerView");
            if (me == null) me = invokeNoArg(client, "getOwnPlayer");
            if (me == null) me = readField(client, "ownPlayer");
            if (me == null) me = invokeNoArg(client, "getMe");
            if (me != null) {
                BotState s = stateFromPlayerLike(me);
                if (s != null) return s;
            }

            Object playersObj = invokeNoArg(client, "getPlayers");
            if (playersObj instanceof List<?>) {
                List<?> players = (List<?>) playersObj;
                for (Object p : players) {
                    String nick = readString(p, "getNick", "nick");
                    if (nick != null && nick.equalsIgnoreCase(fallbackNick)) {
                        return stateFromPlayerLike(p);
                    }
                }
                if (!players.isEmpty()) {
                    return stateFromPlayerLike(players.get(0));
                }
            }

            return null;
        }

        @NotNull List<PlayerState> readOtherPlayers(@NotNull Strategy strategy,
                                                    @NotNull String fallbackNick,
                                                    @NotNull BotState me) {
            Object client = getMazeClient(strategy);
            if (client == null) return Collections.emptyList();

            Object playersObj = invokeNoArg(client, "getPlayers");
            if (!(playersObj instanceof List<?>)) return Collections.emptyList();

            ArrayList<PlayerState> out = new ArrayList<>();
            for (Object p : (List<?>) playersObj) {
                if (p == null) continue;

                String nick = readString(p, "getNick", "nick");
                if (nick != null && nick.equalsIgnoreCase(fallbackNick)) continue;

                BotState s = stateFromPlayerLike(p);
                if (s == null) continue;

                if (s.x() == me.x() && s.y() == me.y()) continue;

                out.add(new PlayerState(s.x(), s.y(), s.direction(), nick));
            }

            return out.isEmpty() ? Collections.emptyList() : out;
        }

        void refreshBaits(@NotNull Strategy strategy, @NotNull ConcurrentHashMap<Long, Bait> into) {
            Object client = getMazeClient(strategy);
            if (client == null) return;

            Object baitsObj = invokeNoArg(client, "getBaits");
            if (baitsObj instanceof List<?>) {
                for (Object o : (List<?>) baitsObj) {
                    if (o instanceof Bait) {
                        Bait b = (Bait) o;
                        into.put(combineIntsToLong(b.getX(), b.getY()), b);
                    }
                }
            }
        }

        private @Nullable BotState stateFromPlayerLike(@Nullable Object p) {
            if (p == null) return null;

            int x = readInt(p, "getX", "x");
            int y = readInt(p, "getY", "y");

            Object dirObj = invokeNoArg(p, "getViewDirection");
            ViewDirection dir = null;
            if (dirObj instanceof ViewDirection) dir = (ViewDirection) dirObj;

            if (dir == null) {
                Object dirField = readField(p, "viewDirection");
                if (dirField instanceof ViewDirection) dir = (ViewDirection) dirField;
            }

            if (dir == null) return null;
            return new BotState(x, y, dir);
        }

        private @Nullable Object getMazeClient(@NotNull Strategy strategy) {
            if (!mazeClientFieldResolved) {
                mazeClientFieldResolved = true;
                mazeClientField = resolveMazeClientField(strategy.getClass());
            }

            Field f = mazeClientField;
            if (f == null) return null;
            try {
                return f.get(strategy);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static @Nullable Field resolveMazeClientField(@NotNull Class<?> type) {
            Class<?> c = type;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("mazeClient");
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private static @Nullable Object invokeNoArg(@Nullable Object target, @NotNull String methodName) {
            if (target == null) return null;
            try {
                Method m = target.getClass().getMethod(methodName);
                return m.invoke(target);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static @Nullable Object readField(@Nullable Object target, @NotNull String fieldName) {
            if (target == null) return null;

            try {
                Field f = target.getClass().getField(fieldName);
                return f.get(target);
            } catch (Exception ignored) {
            }

            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static int readInt(@Nullable Object target, @NotNull String getterName, @NotNull String fieldName) {
            if (target == null) return 0;

            try {
                Method m = target.getClass().getMethod(getterName);
                Object v = m.invoke(target);
                if (v instanceof Integer) return (Integer) v;
            } catch (Exception ignored) {
            }

            try {
                Field f = target.getClass().getField(fieldName);
                Object v = f.get(target);
                if (v instanceof Integer) return (Integer) v;
            } catch (Exception ignored) {
            }

            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Integer) return (Integer) v;
            } catch (Exception ignored) {
                return 0;
            }

            return 0;
        }

        private static @Nullable String readString(@Nullable Object target, @NotNull String getterName, @NotNull String fieldName) {
            if (target == null) return null;

            try {
                Method m = target.getClass().getMethod(getterName);
                Object v = m.invoke(target);
                if (v instanceof String) return (String) v;
            } catch (Exception ignored) {
            }

            try {
                Field f = target.getClass().getField(fieldName);
                Object v = f.get(target);
                if (v instanceof String) return (String) v;
            } catch (Exception ignored) {
            }

            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof String) return (String) v;
            } catch (Exception ignored) {
                return null;
            }

            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Planner (same behaviour, simplified types + less plumbing)
    // ---------------------------------------------------------------------

    private static final class RewardPlanner {

        private static final long MAX_NANOS = 8_000_000L;
        private final PlannerConfig config;

        RewardPlanner(@NotNull PlannerConfig config) {
            this.config = config;
        }

        @Nullable PlanResult plan(@NotNull PlanRequest request) {
            PlanResult safePlan = planInternal(request, true);
            if (safePlan != null && safePlan.utility() > 0.0) return safePlan;
            return planInternal(request, false);
        }

        private @Nullable PlanResult planInternal(@NotNull PlanRequest request, boolean forbidTraps) {
            MazeModel maze = request.maze;
            if (!maze.isReady()) return null;

            PlanInput input = buildPlanInput(request, forbidTraps);
            if (input.candidates.length == 0) return null;

            long deadline = System.nanoTime() + MAX_NANOS;

            int k = input.candidates.length;
            long[] candId = new long[k];
            int[] candScore = new int[k];
            for (int i = 0; i < k; i++) {
                Bait b = input.candidates[i];
                candId[i] = combineIntsToLong(b.getX(), b.getY());
                candScore[i] = b.getScore();
            }

            int[] sortedScores = Arrays.copyOf(candScore, k);
            Arrays.sort(sortedScores);

            PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
                int c = Double.compare(b.bound, a.bound);
                if (c != 0) return c;
                return Integer.compare(a.tie, b.tie);
            });

            HashMap<StateKey, Double> bestSeen = new HashMap<>(4096);

            Node startNode = new Node(
                    request.start.x(), request.start.y(), request.start.direction(),
                    0, 0, 0, 0L,
                    null,
                    null,
                    0.0
            );
            startNode.utility = computeUtility(startNode.reward, startNode.moves, startNode.trapSteps, forbidTraps);
            startNode.bound = startNode.utility + optimisticRemaining(sortedScores, startNode.collectedMask, k, config.maxDepth());
            startNode.tie = 1;

            open.add(startNode);
            bestSeen.put(new StateKey(startNode), startNode.utility);

            Node best = null;
            int expansions = 0;

            while (!open.isEmpty() && expansions < config.maxExpansions()) {
                if (System.nanoTime() > deadline) break;
                Node cur = open.poll();
                expansions++;

                if (cur.moves > config.maxDepth()) continue;

                if (best == null || cur.utility > best.utility + 1e-9) {
                    if (cur.reward > 0 && cur.firstMove != null) {
                        best = cur;
                    }
                }

                expandTurn(cur, true, input, k, sortedScores, bestSeen, open, forbidTraps);
                expandTurn(cur, false, input, k, sortedScores, bestSeen, open, forbidTraps);
                expandStep(cur, input, candId, candScore, k, sortedScores, bestSeen, open, forbidTraps);
            }

            if (best == null) return null;

            PlanVisual visual = buildVisual(best, candId, candScore);
            return new PlanResult(best.firstMove, best.utility, visual.path, visual.target, visual.label);
        }

        private PlanInput buildPlanInput(@NotNull PlanRequest request, boolean forbidTraps) {
            MazeModel maze = request.maze;
            int width = maze.getWidth();
            int height = maze.getHeight();

            boolean[] trapCell = new boolean[width * height];
            boolean[] occupiedCell = request.occupiedCell;
            if (occupiedCell != null && occupiedCell.length != (width * height)) {
                occupiedCell = null;
            }
            ArrayList<Bait> positives = new ArrayList<>();

            int[] dist = computeDistances(maze, request.start.x(), request.start.y());

            for (Bait b : request.baits) {
                if (b == null) continue;
                int x = b.getX();
                int y = b.getY();
                if (!maze.inBounds(x, y)) continue;

                if (b.getScore() < 0) {
                    trapCell[y * width + x] = true;
                    continue;
                }

                if (b.getScore() > 0) {
                    int d = dist[y * width + x];
                    if (d >= 0) positives.add(b);
                }
            }

            if (forbidTraps && positives.isEmpty()) {
                return new PlanInput(width, height, maze.walkableGrid(), trapCell, occupiedCell, new Bait[0]);
            }

            positives.sort((a, b) -> Double.compare(
                    baitRankScore(b, dist, width),
                    baitRankScore(a, dist, width)
            ));

            int k = Math.min(config.candidateBaits(), positives.size());
            Bait[] cand = new Bait[k];
            for (int i = 0; i < k; i++) cand[i] = positives.get(i);

            return new PlanInput(width, height, maze.walkableGrid(), trapCell, occupiedCell, cand);
        }

        private static double baitRankScore(Bait bait, int[] dist, int width) {
            int d = dist[bait.getY() * width + bait.getX()];
            return (double) bait.getScore() / (double) (d + 2);
        }

        private static int[] computeDistances(MazeModel maze, int startX, int startY) {
            int width = maze.getWidth();
            int height = maze.getHeight();
            int[] dist = new int[width * height];
            Arrays.fill(dist, -1);

            if (!maze.inBounds(startX, startY) || !maze.isWalkable(startX, startY)) return dist;

            int[] qx = new int[width * height];
            int[] qy = new int[width * height];
            int head = 0;
            int tail = 0;

            dist[startY * width + startX] = 0;
            qx[tail] = startX;
            qy[tail] = startY;
            tail++;

            while (head < tail) {
                int x = qx[head];
                int y = qy[head];
                head++;

                int next = dist[y * width + x] + 1;

                if (maze.isWalkable(x + 1, y) && dist[y * width + x + 1] < 0) {
                    dist[y * width + x + 1] = next;
                    qx[tail] = x + 1; qy[tail] = y; tail++;
                }
                if (maze.isWalkable(x - 1, y) && dist[y * width + x - 1] < 0) {
                    dist[y * width + x - 1] = next;
                    qx[tail] = x - 1; qy[tail] = y; tail++;
                }
                if (maze.isWalkable(x, y + 1) && dist[(y + 1) * width + x] < 0) {
                    dist[(y + 1) * width + x] = next;
                    qx[tail] = x; qy[tail] = y + 1; tail++;
                }
                if (maze.isWalkable(x, y - 1) && dist[(y - 1) * width + x] < 0) {
                    dist[(y - 1) * width + x] = next;
                    qx[tail] = x; qy[tail] = y - 1; tail++;
                }
            }

            return dist;
        }

        private void expandTurn(Node cur,
                                boolean left,
                                PlanInput input,
                                int k,
                                int[] sortedScores,
                                HashMap<StateKey, Double> bestSeen,
                                PriorityQueue<Node> open,
                                boolean forbidTraps) {
            ViewDirection nextDir = left ? turnLeft(cur.dir) : turnRight(cur.dir);
            Move move = left ? Move.TURN_L : Move.TURN_R;

            Node next = new Node(
                    cur.x, cur.y, nextDir,
                    cur.moves + 1,
                    cur.reward,
                    cur.trapSteps,
                    cur.collectedMask,
                    cur.firstMove != null ? cur.firstMove : move,
                    cur,
                    0.0
            );

            pushIfBetter(next, input, k, sortedScores, bestSeen, open, forbidTraps, 2);
        }

        private void expandStep(Node cur,
                                PlanInput input,
                                long[] candId,
                                int[] candScore,
                                int k,
                                int[] sortedScores,
                                HashMap<StateKey, Double> bestSeen,
                                PriorityQueue<Node> open,
                                boolean forbidTraps) {
            int nx = cur.x;
            int ny = cur.y;

            switch (cur.dir) {
                case NORTH: ny -= 1; break;
                case EAST:  nx += 1; break;
                case SOUTH: ny += 1; break;
                case WEST:  nx -= 1; break;
            }

            if (!input.inBounds(nx, ny)) return;
            if (!input.walkable[ny * input.w + nx]) return;
            if (input.occupiedCell != null && input.occupiedCell[ny * input.w + nx]) return;

            boolean isTrap = input.trapCell[ny * input.w + nx];
            if (forbidTraps && isTrap) return;

            int reward = cur.reward;
            int trapSteps = cur.trapSteps;
            long mask = cur.collectedMask;

            if (isTrap) {
                reward += TRAP_SCORE;
                trapSteps += 1;
            }

            long id = combineIntsToLong(nx, ny);
            for (int i = 0; i < k; i++) {
                if (candId[i] == id) {
                    long bit = (1L << i);
                    if ((mask & bit) == 0L) {
                        mask |= bit;
                        reward += candScore[i];
                    }
                    break;
                }
            }

            Node next = new Node(
                    nx, ny, cur.dir,
                    cur.moves + 1,
                    reward,
                    trapSteps,
                    mask,
                    cur.firstMove != null ? cur.firstMove : Move.STEP,
                    cur,
                    0.0
            );

            pushIfBetter(next, input, k, sortedScores, bestSeen, open, forbidTraps, 0);
        }

        private void pushIfBetter(Node next,
                                  PlanInput input,
                                  int k,
                                  int[] sortedScores,
                                  HashMap<StateKey, Double> bestSeen,
                                  PriorityQueue<Node> open,
                                  boolean forbidTraps,
                                  int tie) {
            if (next.moves > config.maxDepth()) return;

            next.utility = computeUtility(next.reward, next.moves, next.trapSteps, forbidTraps);
            next.bound = next.utility + optimisticRemaining(sortedScores, next.collectedMask, k, config.maxDepth() - next.moves);
            next.tie = tie;

            StateKey key = new StateKey(next);
            Double bestU = bestSeen.get(key);

            if (bestU == null || next.utility > bestU + 1e-9) {
                bestSeen.put(key, next.utility);
                open.add(next);
            }
        }

        private double computeUtility(int reward, int moves, int trapSteps, boolean forbidTraps) {
            double utility = reward - (config.moveCost() * moves);
            if (!forbidTraps) {
                utility -= (config.trapStepPenalty() * trapSteps);
            }
            return utility;
        }

        private static double optimisticRemaining(int[] sortedScoresAsc, long mask, int k, int remainingMoves) {
            if (remainingMoves <= 0 || k == 0) return 0.0;

            int maxPicks = Math.min(remainingMoves, k);
            double sum = 0.0;
            int picked = 0;

            for (int i = k - 1; i >= 0 && picked < maxPicks; i--) {
                int score = sortedScoresAsc[i];
                if (score <= 0) break;
                sum += score;
                picked++;
            }
            return sum;
        }

        private static ViewDirection turnLeft(ViewDirection d) {
            switch (d) {
                case NORTH: return ViewDirection.WEST;
                case EAST:  return ViewDirection.NORTH;
                case SOUTH: return ViewDirection.EAST;
                case WEST:  return ViewDirection.SOUTH;
                default:    return d;
            }
        }

        private static ViewDirection turnRight(ViewDirection d) {
            switch (d) {
                case NORTH: return ViewDirection.EAST;
                case EAST:  return ViewDirection.SOUTH;
                case SOUTH: return ViewDirection.WEST;
                case WEST:  return ViewDirection.NORTH;
                default:    return d;
            }
        }

        private static PlanVisual buildVisual(Node best, long[] candId, int[] candScore) {
            ArrayList<Point> reversed = new ArrayList<>();
            Node n = best;
            while (n != null) {
                reversed.add(new Point(n.x, n.y));
                n = n.parent;
            }

            ArrayList<Point> path = new ArrayList<>(reversed.size());
            for (int i = reversed.size() - 1; i >= 0; i--) {
                Point p = reversed.get(i);
                if (path.isEmpty()) {
                    path.add(p);
                } else {
                    Point last = path.get(path.size() - 1);
                    if (last.x != p.x || last.y != p.y) {
                        path.add(p);
                    }
                }
            }

            Point target = null;
            String label = null;
            for (Point p : path) {
                long id = combineIntsToLong(p.x, p.y);
                for (int i = 0; i < candId.length; i++) {
                    if (candId[i] == id) {
                        target = p;
                        label = labelForScore(candScore[i]);
                        break;
                    }
                }
                if (target != null) break;
            }

            return new PlanVisual(Collections.unmodifiableList(path), target, label);
        }

        private static String labelForScore(int score) {
            if (score == 314) return "GEM";
            if (score == 42) return "COFFEE";
            if (score == 13) return "FOOD";
            if (score == TRAP_SCORE) return "TRAP";
            return String.valueOf(score);
        }

        static final class PlanRequest {
            final MazeModel maze;
            final Collection<Bait> baits;
            final BotState start;
            final @Nullable boolean[] occupiedCell;

            PlanRequest(@NotNull MazeModel maze,
                        @NotNull Collection<Bait> baits,
                        @NotNull BotState start,
                        @Nullable boolean[] occupiedCell) {
                this.maze = maze;
                this.baits = baits;
                this.start = start;
                this.occupiedCell = occupiedCell;
            }
        }

        static final class PlanResult {
            private final Move firstMove;
            private final double utility;
            private final List<Point> path;
            private final Point target;
            private final String targetLabel;

            PlanResult(@NotNull Move firstMove,
                       double utility,
                       @NotNull List<Point> path,
                       @Nullable Point target,
                       @Nullable String targetLabel) {
                this.firstMove = firstMove;
                this.utility = utility;
                this.path = path;
                this.target = target;
                this.targetLabel = targetLabel;
            }

            Move firstMove() { return firstMove; }
            double utility() { return utility; }
            List<Point> path() { return path; }
            @Nullable Point target() { return target; }
            @Nullable String targetLabel() { return targetLabel; }
        }

        private static final class PlanInput {
            final int w;
            final int h;
            final boolean[] walkable;
            final boolean[] trapCell;
            final @Nullable boolean[] occupiedCell;
            final Bait[] candidates;

            PlanInput(int w,
                      int h,
                      boolean[] walkable,
                      boolean[] trapCell,
                      @Nullable boolean[] occupiedCell,
                      Bait[] candidates) {
                this.w = w;
                this.h = h;
                this.walkable = walkable;
                this.trapCell = trapCell;
                this.occupiedCell = occupiedCell;
                this.candidates = candidates;
            }

            boolean inBounds(int x, int y) {
                return x >= 0 && y >= 0 && x < w && y < h;
            }
        }

        private static final class Node {
            final int x;
            final int y;
            final ViewDirection dir;
            final int moves;
            final int reward;
            final int trapSteps;
            final long collectedMask;
            final Move firstMove;
            final Node parent;

            double utility;
            double bound;
            int tie;

            Node(int x, int y, ViewDirection dir,
                 int moves, int reward, int trapSteps,
                 long collectedMask, Move firstMove,
                 Node parent, double utility) {
                this.x = x;
                this.y = y;
                this.dir = dir;
                this.moves = moves;
                this.reward = reward;
                this.trapSteps = trapSteps;
                this.collectedMask = collectedMask;
                this.firstMove = firstMove;
                this.parent = parent;
                this.utility = utility;
            }
        }

        private static final class StateKey {
            final int x;
            final int y;
            final int d;
            final long mask;

            StateKey(Node n) {
                this.x = n.x;
                this.y = n.y;
                this.d = n.dir.ordinal();
                this.mask = n.collectedMask;
            }

            @Override public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof StateKey)) return false;
                StateKey k = (StateKey) o;
                return x == k.x && y == k.y && d == k.d && mask == k.mask;
            }

            @Override public int hashCode() {
                return 31 * (31 * (31 * x + y) + d) + (int) (mask ^ (mask >>> 32));
            }
        }

        private static final class PlanVisual {
            final List<Point> path;
            final Point target;
            final String label;

            PlanVisual(List<Point> path, Point target, String label) {
                this.path = path;
                this.target = target;
                this.label = label;
            }
        }
    }
}
