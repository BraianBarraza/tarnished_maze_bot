package de.dreamcube.mazegame.client.bots.planning;

import de.dreamcube.mazegame.client.bots.model.BaitType;
import de.dreamcube.mazegame.client.bots.model.MazeState;
import de.dreamcube.mazegame.client.bots.runtime.BotState;
import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

import static de.dreamcube.mazegame.client.maze.BaitKt.combineIntsToLong;

/**
 * Reward-driven best-first planner with trap avoidance.
 */
public final class RewardPlanner {

    private static final long MAX_NANOS = 8_000_000L;

    private final PlannerConfig config;

    /**
     * Creates a planner with the given configuration.
     */
    public RewardPlanner(@NotNull PlannerConfig config) {
        this.config = config;
    }

    /**
     * Plans a move using a two-phase search (avoid traps first, allow if needed).
     */
    public @Nullable PlanResult plan(@NotNull PlanRequest request) {
        PlanResult safePlan = planInternal(request, true);
        if (safePlan != null && safePlan.utility() > 0.0) {
            return safePlan;
        }
        return planInternal(request, false);
    }

    private @Nullable PlanResult planInternal(@NotNull PlanRequest request, boolean forbidTraps) {
        MazeState maze = request.maze;
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
        MazeState maze = request.maze;
        int width = maze.getWidth();
        int height = maze.getHeight();

        boolean[] trapCell = new boolean[width * height];
        ArrayList<Bait> positives = new ArrayList<>();

        int[] dist = computeDistances(maze, request.start.x(), request.start.y());

        for (Bait b : request.baits) {
            if (b == null) continue;
            int x = b.getX();
            int y = b.getY();
            if (!maze.inBounds(x, y)) continue;

            BaitType type = BaitType.fromScore(b.getScore());
            boolean isTrap = type != null && type.isTrap();
            if (isTrap) {
                trapCell[y * width + x] = true;
                continue;
            }

            if (b.getScore() > 0) {
                int d = dist[y * width + x];
                if (d >= 0) positives.add(b);
            }
        }

        if (forbidTraps && positives.isEmpty()) {
            return new PlanInput(width, height, maze.walkableGrid(), trapCell, new Bait[0]);
        }

        positives.sort((a, b) -> Double.compare(
                baitRankScore(b, dist, width),
                baitRankScore(a, dist, width)
        ));

        int k = Math.min(config.candidateBaits(), positives.size());
        Bait[] cand = new Bait[k];
        for (int i = 0; i < k; i++) cand[i] = positives.get(i);

        return new PlanInput(width, height, maze.walkableGrid(), trapCell, cand);
    }

    private static double baitRankScore(Bait bait, int[] dist, int width) {
        int d = dist[bait.getY() * width + bait.getX()];
        return (double) bait.getScore() / (double) (d + 2);
    }

    private static int[] computeDistances(MazeState maze, int startX, int startY) {
        int width = maze.getWidth();
        int height = maze.getHeight();
        int[] dist = new int[width * height];
        Arrays.fill(dist, -1);

        if (!maze.inBounds(startX, startY) || !maze.isWalkable(startX, startY)) {
            return dist;
        }

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
                qx[tail] = x + 1;
                qy[tail] = y;
                tail++;
            }
            if (maze.isWalkable(x - 1, y) && dist[y * width + x - 1] < 0) {
                dist[y * width + x - 1] = next;
                qx[tail] = x - 1;
                qy[tail] = y;
                tail++;
            }
            if (maze.isWalkable(x, y + 1) && dist[(y + 1) * width + x] < 0) {
                dist[(y + 1) * width + x] = next;
                qx[tail] = x;
                qy[tail] = y + 1;
                tail++;
            }
            if (maze.isWalkable(x, y - 1) && dist[(y - 1) * width + x] < 0) {
                dist[(y - 1) * width + x] = next;
                qx[tail] = x;
                qy[tail] = y - 1;
                tail++;
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

        boolean isTrap = input.trapCell[ny * input.w + nx];
        if (forbidTraps && isTrap) return;

        int reward = cur.reward;
        int trapSteps = cur.trapSteps;
        long mask = cur.collectedMask;

        if (isTrap) {
            reward += BaitType.TRAP.score();
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
        BaitType type = BaitType.fromScore(score);
        return type != null ? type.label() : String.valueOf(score);
    }

    /**
     * Input for a planning call.
     */
    public static final class PlanRequest {
        private final MazeState maze;
        private final Collection<Bait> baits;
        private final BotState start;

        /**
         * Creates a new planning request.
         */
        public PlanRequest(@NotNull MazeState maze, @NotNull Collection<Bait> baits, @NotNull BotState start) {
            this.maze = maze;
            this.baits = baits;
            this.start = start;
        }
    }

    /**
     * Result of a planning call.
     */
    public static final class PlanResult {
        private final Move firstMove;
        private final double utility;
        private final List<Point> path;
        private final Point target;
        private final String targetLabel;

        /**
         * Creates a plan result.
         */
        public PlanResult(@NotNull Move firstMove,
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

        /**
         * Returns the first move of the plan.
         */
        public Move firstMove() {
            return firstMove;
        }

        /**
         * Returns the total utility estimate.
         */
        public double utility() {
            return utility;
        }

        /**
         * Returns the path for visualization.
         */
        public List<Point> path() {
            return path;
        }

        /**
         * Returns the target cell for visualization.
         */
        public @Nullable Point target() {
            return target;
        }

        /**
         * Returns a label for the target bait.
         */
        public @Nullable String targetLabel() {
            return targetLabel;
        }
    }

    private static final class PlanInput {
        final int w;
        final int h;
        final boolean[] walkable;
        final boolean[] trapCell;
        final Bait[] candidates;

        PlanInput(int w, int h, boolean[] walkable, boolean[] trapCell, Bait[] candidates) {
            this.w = w;
            this.h = h;
            this.walkable = walkable;
            this.trapCell = trapCell;
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
            int h = 31 * (31 * (31 * x + y) + d) + (int) (mask ^ (mask >>> 32));
            return h;
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
