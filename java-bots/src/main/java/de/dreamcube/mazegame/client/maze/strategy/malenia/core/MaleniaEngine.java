package de.dreamcube.mazegame.client.maze.strategy.malenia.core;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.PlayerSnapshot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.common.maze.BaitType;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static de.dreamcube.mazegame.client.maze.BaitKt.combineIntsToLong;

/**
 * Decision engine for the Malenia bot.
 *
 * <p>The engine is intentionally state-light: it plans from an immutable world snapshot and returns
 * the next move plus a small target lock for short-term hysteresis. This keeps the thread-safety
 * story simple and avoids mixing client reads with event-driven state.</p>
 */
public final class MaleniaEngine {

    private static final PlannerConfig DEFAULT_CONFIG = new PlannerConfig(
            10.0,  // distancePenaltyPerStep
            40.0,  // trapTraversalPenalty
            60.0,  // contestedTargetPenalty
            1.18,  // targetSwitchMultiplier
            18.0,  // targetSwitchMargin
            3      // targetCommitmentTicks
    );

    private final PlannerConfig config;

    public MaleniaEngine() {
        this(DEFAULT_CONFIG);
    }

    public MaleniaEngine(@NotNull PlannerConfig config) {
        this.config = config;
    }

    public @Nullable Decision nextDecision(@NotNull WorldSnapshot world, @Nullable TargetLock previousLock) {
        MazeSnapshot maze = world.maze();
        PlayerState self = world.self();
        if (!maze.isReady() || self == null || !maze.isWalkable(self.x(), self.y())) {
            return null;
        }

        boolean[] occupied = buildOccupiedGrid(maze, world.others());
        boolean[] danger = buildDangerGrid(maze, world.others());
        boolean[] trapCells = buildTrapGrid(maze, world.baits());

        OrientedSearch safeSearch = new OrientedSearch(maze);
        safeSearch.computeFrom(self.x(), self.y(), self.direction(), mergeBlocked(danger, trapCells));

        TargetCandidate safePrevious = evaluateLockedTarget(previousLock, world, safeSearch, trapCells);
        TargetCandidate safeBest = findBestCandidate(world, safeSearch, trapCells, false);
        Selection safeSelection = selectCandidate(safePrevious, safeBest, previousLock);

        if (safeSelection != null) {
            return buildDecision(world.version(), safeSelection, self, maze, occupied);
        }

        OrientedSearch trapSearch = new OrientedSearch(maze);
        trapSearch.computeFrom(self.x(), self.y(), self.direction(), danger);

        TargetCandidate trapPrevious = evaluateLockedTarget(previousLock, world, trapSearch, trapCells);
        TargetCandidate trapBest = findBestCandidate(world, trapSearch, trapCells, true);
        Selection trapSelection = selectCandidate(trapPrevious, trapBest, previousLock);

        if (trapSelection != null) {
            return buildDecision(world.version(), trapSelection, self, maze, occupied);
        }

        return buildExplorationDecision(world.version(), self, maze, occupied);
    }

    private @Nullable TargetCandidate evaluateLockedTarget(@Nullable TargetLock previousLock,
                                                           @NotNull WorldSnapshot world,
                                                           @NotNull OrientedSearch search,
                                                           boolean[] trapCells) {
        if (previousLock == null) {
            return null;
        }

        for (Bait bait : world.baits()) {
            if (combineIntsToLong(bait.getX(), bait.getY()) != previousLock.baitId()) {
                continue;
            }
            if (bait.getType() == BaitType.TRAP) {
                return null;
            }
            return evaluateTargetCandidate(world, bait, search, trapCells);
        }
        return null;
    }

    private @Nullable TargetCandidate findBestCandidate(@NotNull WorldSnapshot world,
                                                        @NotNull OrientedSearch search,
                                                        boolean[] trapCells,
                                                        boolean allowTrapPaths) {
        TargetCandidate best = null;
        for (Bait bait : world.baits()) {
            if (bait.getType() == BaitType.TRAP) {
                continue;
            }

            TargetCandidate candidate = evaluateTargetCandidate(world, bait, search, trapCells);
            if (candidate == null) {
                continue;
            }
            if (!allowTrapPaths && candidate.trapSteps() > 0) {
                continue;
            }
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    private @Nullable TargetCandidate evaluateTargetCandidate(@NotNull WorldSnapshot world,
                                                              @NotNull Bait bait,
                                                              @NotNull OrientedSearch search,
                                                              boolean[] trapCells) {
        int distance = search.distanceTo(bait.getX(), bait.getY());
        if (distance == Integer.MAX_VALUE) {
            return null;
        }

        Move firstMove = search.firstMoveTo(bait.getX(), bait.getY());
        if (firstMove == Move.DO_NOTHING) {
            return null;
        }

        List<Point> path = search.getPathTo(bait.getX(), bait.getY());
        if (path.isEmpty()) {
            return null;
        }

        int trapSteps = countTrapSteps(path, world.maze(), trapCells);
        double score = bait.getScore() - (config.distancePenaltyPerStep() * distance);
        score -= config.trapTraversalPenalty() * trapSteps;
        score -= congestionPenalty(bait, world.others());
        score -= contestPenalty(bait, distance, world.maze(), world.others());

        return new TargetCandidate(bait, score, distance, trapSteps, firstMove, path);
    }

    private @Nullable Selection selectCandidate(@Nullable TargetCandidate previousCandidate,
                                                @Nullable TargetCandidate bestCandidate,
                                                @Nullable TargetLock previousLock) {
        if (previousCandidate == null) {
            return bestCandidate == null ? null : Selection.forNewTarget(bestCandidate, config.targetCommitmentTicks());
        }
        if (bestCandidate == null) {
            return Selection.forLockedTarget(previousCandidate, previousLock);
        }

        if (!shouldSwitchTarget(previousCandidate.score(), bestCandidate.score(), previousLock)) {
            return Selection.forLockedTarget(previousCandidate, previousLock);
        }
        return Selection.forNewTarget(bestCandidate, config.targetCommitmentTicks());
    }

    private boolean shouldSwitchTarget(double currentScore, double candidateScore, @Nullable TargetLock previousLock) {
        if (candidateScore <= currentScore) {
            return false;
        }

        if (previousLock == null || previousLock.remainingTicks() <= 0) {
            return true;
        }

        return candidateScore >= Math.max(
                currentScore + config.targetSwitchMargin(),
                currentScore * config.targetSwitchMultiplier()
        );
    }

    private @NotNull Decision buildDecision(long version,
                                            @NotNull Selection selection,
                                            @NotNull PlayerState self,
                                            @NotNull MazeSnapshot maze,
                                            @Nullable boolean[] occupied) {
        Move move = avoidImmediateCollision(selection.candidate().firstMove(), self, maze, occupied);
        List<Point> path = (move == selection.candidate().firstMove())
                ? selection.candidate().path()
                : List.of(new Point(self.x(), self.y()));

        Bait bait = selection.candidate().bait();
        Point target = new Point(bait.getX(), bait.getY());
        return new Decision(
                move,
                selection.candidate().score(),
                path,
                target,
                labelForScore(bait.getScore()),
                selection.nextLock(),
                version
        );
    }

    private @NotNull Decision buildExplorationDecision(long version,
                                                       @NotNull PlayerState self,
                                                       @NotNull MazeSnapshot maze,
                                                       @Nullable boolean[] occupied) {
        Move move = chooseExplorationMove(self, maze, occupied);
        List<Point> path = new ArrayList<>(2);
        path.add(new Point(self.x(), self.y()));
        if (move == Move.STEP) {
            path.add(new Point(forwardX(self.x(), self.direction()), forwardY(self.y(), self.direction())));
        }

        return new Decision(move, 0.0, Collections.unmodifiableList(path), null, null, null, version);
    }

    private static int countTrapSteps(@NotNull List<Point> path, @NotNull MazeSnapshot maze, boolean[] trapCells) {
        int trapSteps = 0;
        for (int i = 1; i < path.size(); i++) {
            Point p = path.get(i);
            if (maze.inBounds(p.x, p.y) && trapCells[(p.y * maze.width()) + p.x]) {
                trapSteps++;
            }
        }
        return trapSteps;
    }

    private static double congestionPenalty(@NotNull Bait bait, @NotNull List<PlayerState> others) {
        double penalty = 0.0;
        for (PlayerState other : others) {
            int distance = manhattan(other.x(), other.y(), bait.getX(), bait.getY());
            if (distance == 0) {
                penalty += 40.0;
            } else if (distance == 1) {
                penalty += 18.0;
            } else if (distance == 2) {
                penalty += 8.0;
            }
        }
        return penalty;
    }

    private double contestPenalty(@NotNull Bait bait,
                                  int ownDistance,
                                  @NotNull MazeSnapshot maze,
                                  @NotNull List<PlayerState> others) {
        double penalty = 0.0;
        for (PlayerState other : others) {
            int directSteps = directApproachSteps(other, bait.getX(), bait.getY(), maze);
            if (directSteps < 0) {
                continue;
            }
            if (directSteps <= ownDistance) {
                penalty = Math.max(penalty, config.contestedTargetPenalty());
            } else if (directSteps <= ownDistance + 1) {
                penalty = Math.max(penalty, config.contestedTargetPenalty() * 0.5);
            }
        }
        return penalty;
    }

    private static int directApproachSteps(@NotNull PlayerState player, int targetX, int targetY, @NotNull MazeSnapshot maze) {
        switch (player.direction()) {
            case NORTH:
                if (player.x() != targetX || targetY >= player.y()) return -1;
                for (int y = player.y() - 1; y >= targetY; y--) {
                    if (!maze.isWalkable(player.x(), y)) return -1;
                }
                return player.y() - targetY;
            case SOUTH:
                if (player.x() != targetX || targetY <= player.y()) return -1;
                for (int y = player.y() + 1; y <= targetY; y++) {
                    if (!maze.isWalkable(player.x(), y)) return -1;
                }
                return targetY - player.y();
            case EAST:
                if (player.y() != targetY || targetX <= player.x()) return -1;
                for (int x = player.x() + 1; x <= targetX; x++) {
                    if (!maze.isWalkable(x, player.y())) return -1;
                }
                return targetX - player.x();
            case WEST:
                if (player.y() != targetY || targetX >= player.x()) return -1;
                for (int x = player.x() - 1; x >= targetX; x--) {
                    if (!maze.isWalkable(x, player.y())) return -1;
                }
                return player.x() - targetX;
            default:
                return -1;
        }
    }

    private static @Nullable boolean[] buildOccupiedGrid(@NotNull MazeSnapshot maze, @NotNull List<PlayerState> players) {
        if (!maze.isReady()) {
            return null;
        }

        boolean[] occupied = new boolean[maze.width() * maze.height()];
        for (PlayerState player : players) {
            if (maze.inBounds(player.x(), player.y())) {
                occupied[(player.y() * maze.width()) + player.x()] = true;
            }
        }
        return occupied;
    }

    private static boolean[] buildDangerGrid(@NotNull MazeSnapshot maze, @NotNull List<PlayerState> players) {
        boolean[] danger = new boolean[Math.max(1, maze.width() * maze.height())];
        for (PlayerState player : players) {
            if (maze.inBounds(player.x(), player.y())) {
                danger[(player.y() * maze.width()) + player.x()] = true;
            }

            int nx = forwardX(player.x(), player.direction());
            int ny = forwardY(player.y(), player.direction());
            if (maze.isWalkable(nx, ny)) {
                danger[(ny * maze.width()) + nx] = true;
            }
        }
        return danger;
    }

    private static boolean[] buildTrapGrid(@NotNull MazeSnapshot maze, @NotNull List<Bait> baits) {
        boolean[] trapCells = new boolean[Math.max(1, maze.width() * maze.height())];
        for (Bait bait : baits) {
            if (bait.getType() == BaitType.TRAP && maze.inBounds(bait.getX(), bait.getY())) {
                trapCells[(bait.getY() * maze.width()) + bait.getX()] = true;
            }
        }
        return trapCells;
    }

    private static boolean[] mergeBlocked(@Nullable boolean[] first, @Nullable boolean[] second) {
        if (first == null) {
            return second == null ? null : Arrays.copyOf(second, second.length);
        }
        boolean[] merged = Arrays.copyOf(first, first.length);
        if (second != null) {
            int limit = Math.min(merged.length, second.length);
            for (int i = 0; i < limit; i++) {
                merged[i] = merged[i] || second[i];
            }
        }
        return merged;
    }

    private static @NotNull Move avoidImmediateCollision(@NotNull Move plannedMove,
                                                         @NotNull PlayerState self,
                                                         @NotNull MazeSnapshot maze,
                                                         @Nullable boolean[] occupied) {
        if (plannedMove != Move.STEP) {
            return plannedMove;
        }

        int nx = forwardX(self.x(), self.direction());
        int ny = forwardY(self.y(), self.direction());
        if (!maze.isWalkable(nx, ny)) {
            return plannedMove;
        }
        if (occupied != null && occupied[(ny * maze.width()) + nx]) {
            return chooseAvoidanceTurn(self, maze, occupied);
        }
        return plannedMove;
    }

    private static @NotNull Move chooseExplorationMove(@NotNull PlayerState self,
                                                       @NotNull MazeSnapshot maze,
                                                       @Nullable boolean[] occupied) {
        if (isFrontCellFree(self.x(), self.y(), self.direction(), maze, occupied)) {
            return Move.STEP;
        }
        return chooseAvoidanceTurn(self, maze, occupied);
    }

    private static @NotNull Move chooseAvoidanceTurn(@NotNull PlayerState self,
                                                     @NotNull MazeSnapshot maze,
                                                     @Nullable boolean[] occupied) {
        ViewDirection left = self.direction().turnLeft();
        ViewDirection right = self.direction().turnRight();

        boolean leftOk = isFrontCellFree(self.x(), self.y(), left, maze, occupied);
        boolean rightOk = isFrontCellFree(self.x(), self.y(), right, maze, occupied);

        if (leftOk && !rightOk) {
            return Move.TURN_L;
        }
        if (rightOk && !leftOk) {
            return Move.TURN_R;
        }
        return Move.TURN_L;
    }

    private static boolean isFrontCellFree(int x,
                                           int y,
                                           @NotNull ViewDirection direction,
                                           @NotNull MazeSnapshot maze,
                                           @Nullable boolean[] occupied) {
        int nx = forwardX(x, direction);
        int ny = forwardY(y, direction);
        if (!maze.isWalkable(nx, ny)) {
            return false;
        }
        return occupied == null || !occupied[(ny * maze.width()) + nx];
    }

    private static int forwardX(int x, @NotNull ViewDirection direction) {
        return switch (direction) {
            case EAST -> x + 1;
            case WEST -> x - 1;
            default -> x;
        };
    }

    private static int forwardY(int y, @NotNull ViewDirection direction) {
        return switch (direction) {
            case SOUTH -> y + 1;
            case NORTH -> y - 1;
            default -> y;
        };
    }

    private static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private static String labelForScore(int score) {
        if (score == 314) return "GEM";
        if (score == 42) return "COFFEE";
        if (score == 13) return "FOOD";
        return String.valueOf(score);
    }

    public record PlannerConfig(
            double distancePenaltyPerStep,
            double trapTraversalPenalty,
            double contestedTargetPenalty,
            double targetSwitchMultiplier,
            double targetSwitchMargin,
            int targetCommitmentTicks
    ) {
    }

    public record Decision(
            @NotNull Move firstMove,
            double utility,
            @NotNull List<Point> path,
            @Nullable Point target,
            @Nullable String targetLabel,
            @Nullable TargetLock nextTargetLock,
            long sourceVersion
    ) {
    }

    public record TargetLock(long baitId, int remainingTicks) {

        public static @NotNull TargetLock forBait(@NotNull Bait bait, int ticks) {
            return new TargetLock(combineIntsToLong(bait.getX(), bait.getY()), Math.max(0, ticks));
        }

        public @NotNull TargetLock decay() {
            return new TargetLock(baitId, Math.max(0, remainingTicks - 1));
        }
    }

    public record WorldSnapshot(
            long version,
            @NotNull MazeSnapshot maze,
            @Nullable PlayerState self,
            @NotNull List<PlayerState> others,
            @NotNull List<Bait> baits
    ) {
    }

    public record PlayerState(
            int id,
            @Nullable String nick,
            int x,
            int y,
            @NotNull ViewDirection direction,
            int score
    ) {
        public static @NotNull PlayerState fromSnapshot(@NotNull PlayerSnapshot snapshot) {
            return new PlayerState(
                    snapshot.getId(),
                    snapshot.getNick(),
                    snapshot.getX(),
                    snapshot.getY(),
                    snapshot.getViewDirection(),
                    snapshot.getScore()
            );
        }
    }

    public static final class MazeSnapshot {

        private static final MazeSnapshot EMPTY = new MazeSnapshot(0, 0, new boolean[0]);

        private final int width;
        private final int height;
        private final boolean[] walkable;

        private MazeSnapshot(int width, int height, boolean[] walkable) {
            this.width = width;
            this.height = height;
            this.walkable = walkable;
        }

        public static @NotNull MazeSnapshot empty() {
            return EMPTY;
        }

        public static @NotNull MazeSnapshot fromMazeLines(int width, int height, @NotNull List<String> mazeLines) {
            boolean[] walkable = new boolean[Math.max(0, width) * Math.max(0, height)];

            for (int y = 0; y < height && y < mazeLines.size(); y++) {
                String line = mazeLines.get(y);
                if (line == null) {
                    continue;
                }

                int perCell = 1;
                if (width > 0) {
                    if (line.length() == (width * 2) || line.length() == (width * 2 - 1)) {
                        perCell = 2;
                    } else if (line.length() >= width && line.length() % width == 0) {
                        perCell = Math.max(1, line.length() / width);
                    }
                }

                for (int x = 0; x < width; x++) {
                    int charIndex = x * perCell;
                    char cell = charIndex < line.length() ? line.charAt(charIndex) : '#';
                    walkable[(y * width) + x] = !isBlockedChar(cell);
                }
            }

            return new MazeSnapshot(width, height, walkable);
        }

        public boolean isReady() {
            return width > 0 && height > 0 && walkable.length == width * height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public boolean inBounds(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height;
        }

        public boolean isWalkable(int x, int y) {
            return inBounds(x, y) && walkable[(y * width) + x];
        }

        private static boolean isBlockedChar(char cell) {
            return cell == '#'
                    || cell == '-'
                    || cell == 'X'
                    || cell == 'W'
                    || cell == '█'
                    || cell == '■'
                    || cell == '?'
                    || cell == 'O'
                    || cell == 'o'
                    || cell == '1';
        }
    }

    private record TargetCandidate(
            @NotNull Bait bait,
            double score,
            int distance,
            int trapSteps,
            @NotNull Move firstMove,
            @NotNull List<Point> path
    ) {
    }

    private record Selection(@NotNull TargetCandidate candidate, @NotNull TargetLock nextLock) {

        private static @NotNull Selection forNewTarget(@NotNull TargetCandidate candidate, int ticks) {
            return new Selection(candidate, TargetLock.forBait(candidate.bait(), ticks));
        }

        private static @NotNull Selection forLockedTarget(@NotNull TargetCandidate candidate, @Nullable TargetLock currentLock) {
            if (currentLock == null) {
                return new Selection(candidate, TargetLock.forBait(candidate.bait(), 0));
            }
            return new Selection(candidate, currentLock.decay());
        }
    }

    /**
     * Minimal oriented BFS over (x, y, direction) states.
     */
    private static final class OrientedSearch {

        private static final int DIRECTION_COUNT = ViewDirection.values().length;

        private final MazeSnapshot maze;
        private final int[] distances;
        private final Move[] firstMoves;
        private final int[] previous;

        private OrientedSearch(@NotNull MazeSnapshot maze) {
            this.maze = maze;
            int stateCount = Math.max(1, maze.width() * maze.height() * DIRECTION_COUNT);
            this.distances = new int[stateCount];
            this.firstMoves = new Move[stateCount];
            this.previous = new int[stateCount];
        }

        void computeFrom(int startX, int startY, @NotNull ViewDirection startDirection, @Nullable boolean[] blockedCells) {
            Arrays.fill(distances, Integer.MAX_VALUE);
            Arrays.fill(firstMoves, Move.DO_NOTHING);
            Arrays.fill(previous, -1);

            if (!maze.isWalkable(startX, startY)) {
                return;
            }

            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int startState = toStateIndex(startX, startY, startDirection.ordinal());
            distances[startState] = 0;
            queue.add(startState);

            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int currentDistance = distances[current];
                int cellIndex = current / DIRECTION_COUNT;
                int directionIndex = current % DIRECTION_COUNT;
                int x = cellIndex % maze.width();
                int y = cellIndex / maze.width();

                enqueueTurn(queue, current, currentDistance, x, y, rotateLeft(directionIndex), Move.TURN_L);
                enqueueTurn(queue, current, currentDistance, x, y, rotateRight(directionIndex), Move.TURN_R);
                enqueueStep(queue, current, currentDistance, x, y, directionIndex, blockedCells);
            }
        }

        int distanceTo(int targetX, int targetY) {
            if (!maze.inBounds(targetX, targetY)) {
                return Integer.MAX_VALUE;
            }

            int base = toCellIndex(targetX, targetY) * DIRECTION_COUNT;
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < DIRECTION_COUNT; i++) {
                best = Math.min(best, distances[base + i]);
            }
            return best;
        }

        @NotNull Move firstMoveTo(int targetX, int targetY) {
            if (!maze.inBounds(targetX, targetY)) {
                return Move.DO_NOTHING;
            }

            int base = toCellIndex(targetX, targetY) * DIRECTION_COUNT;
            int bestDistance = Integer.MAX_VALUE;
            Move bestMove = Move.DO_NOTHING;
            for (int i = 0; i < DIRECTION_COUNT; i++) {
                int state = base + i;
                if (distances[state] < bestDistance) {
                    bestDistance = distances[state];
                    bestMove = firstMoves[state];
                }
            }
            return bestMove;
        }

        @NotNull List<Point> getPathTo(int targetX, int targetY) {
            int bestState = findBestArrivalState(targetX, targetY);
            if (bestState < 0) {
                return List.of();
            }

            ArrayList<Point> reversed = new ArrayList<>();
            int current = bestState;
            while (current >= 0) {
                int cellIndex = current / DIRECTION_COUNT;
                int x = cellIndex % maze.width();
                int y = cellIndex / maze.width();
                if (reversed.isEmpty()
                        || reversed.get(reversed.size() - 1).x != x
                        || reversed.get(reversed.size() - 1).y != y) {
                    reversed.add(new Point(x, y));
                }
                current = previous[current];
            }

            Collections.reverse(reversed);
            return Collections.unmodifiableList(reversed);
        }

        private void enqueueTurn(ArrayDeque<Integer> queue,
                                 int current,
                                 int currentDistance,
                                 int x,
                                 int y,
                                 int nextDirection,
                                 Move turnMove) {
            int next = toStateIndex(x, y, nextDirection);
            relax(queue, current, currentDistance, next, turnMove);
        }

        private void enqueueStep(ArrayDeque<Integer> queue,
                                 int current,
                                 int currentDistance,
                                 int x,
                                 int y,
                                 int directionIndex,
                                 @Nullable boolean[] blockedCells) {
            ViewDirection direction = ViewDirection.values()[directionIndex];
            int nextX = forwardX(x, direction);
            int nextY = forwardY(y, direction);

            if (!maze.isWalkable(nextX, nextY)) {
                return;
            }

            int nextCell = toCellIndex(nextX, nextY);
            if (blockedCells != null && nextCell < blockedCells.length && blockedCells[nextCell]) {
                return;
            }

            relax(queue, current, currentDistance, toStateIndex(nextX, nextY, directionIndex), Move.STEP);
        }

        private void relax(ArrayDeque<Integer> queue,
                           int current,
                           int currentDistance,
                           int next,
                           Move move) {
            if (distances[next] != Integer.MAX_VALUE) {
                return;
            }

            distances[next] = currentDistance + 1;
            previous[next] = current;
            firstMoves[next] = currentDistance == 0 ? move : firstMoves[current];
            queue.addLast(next);
        }

        private int findBestArrivalState(int targetX, int targetY) {
            if (!maze.inBounds(targetX, targetY)) {
                return -1;
            }

            int base = toCellIndex(targetX, targetY) * DIRECTION_COUNT;
            int bestState = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < DIRECTION_COUNT; i++) {
                int state = base + i;
                if (distances[state] < bestDistance) {
                    bestDistance = distances[state];
                    bestState = state;
                }
            }
            return bestDistance == Integer.MAX_VALUE ? -1 : bestState;
        }

        private int toCellIndex(int x, int y) {
            return (y * maze.width()) + x;
        }

        private int toStateIndex(int x, int y, int directionIndex) {
            return (toCellIndex(x, y) * DIRECTION_COUNT) + directionIndex;
        }

        private int rotateLeft(int directionIndex) {
            return (directionIndex + DIRECTION_COUNT - 1) % DIRECTION_COUNT;
        }

        private int rotateRight(int directionIndex) {
            return (directionIndex + 1) % DIRECTION_COUNT;
        }
    }
}
