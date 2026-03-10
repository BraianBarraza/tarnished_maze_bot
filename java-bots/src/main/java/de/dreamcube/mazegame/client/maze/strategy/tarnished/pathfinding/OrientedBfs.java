package de.dreamcube.mazegame.client.maze.strategy.tarnished.pathfinding;

import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.MazeModel;
import de.dreamcube.mazegame.common.maze.ViewDirection;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * Performs oriented breadth-first search over three-dimensional state space (x, y, direction).
 *
 * <p>In the maze game, each action (turn left, turn right, or step forward) consumes exactly
 * one tick. This pathfinding algorithm finds the shortest sequence of actions to reach any
 * target cell from a given starting position and orientation.</p>
 *
 * <p>The state space consists of:</p>
 * <ul>
 *     <li><strong>x:</strong> horizontal position in the maze (0 to width-1)</li>
 *     <li><strong>y:</strong> vertical position in the maze (0 to height-1)</li>
 *     <li><strong>direction:</strong> facing direction (NORTH, EAST, SOUTH, or WEST)</li>
 * </ul>
 *
 * <p>From any state, three transitions are possible:</p>
 * <ul>
 *     <li>{@link Move#TURN_L}: rotate 90° counterclockwise (same cell, new direction)</li>
 *     <li>{@link Move#TURN_R}: rotate 90° clockwise (same cell, new direction)</li>
 *     <li>{@link Move#STEP}: move forward one cell in the current direction</li>
 * </ul>
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization is required
 * if instances are shared between threads.</p>
 *
 * @see MazeModel
 */
public final class OrientedBfs {

    private static final int DIRECTION_COUNT = ViewDirection.values().length;

    private final MazeModel mazeModel;

    private int mazeWidth;
    private int mazeHeight;
    private int mazeCellCount;

    private int[] distanceByState;
    private Move[] firstMoveByState;
    private int[] previousStateByState;

    /**
     * Constructs a new oriented BFS pathfinder for the given maze model.
     *
     * @param mazeModel the maze model providing walkability information
     */
    public OrientedBfs(MazeModel mazeModel) {
        this.mazeModel = mazeModel;
    }

    /**
     * Returns the minimum number of actions required to reach the target cell from the
     * most recent search start position.
     *
     * <p>The returned distance is the minimum across all possible arrival directions
     * at the target cell.</p>
     *
     * @param targetX the x-coordinate of the target cell
     * @param targetY the y-coordinate of the target cell
     * @return the minimum action count to reach the target, or {@link Integer#MAX_VALUE}
     *         if the target is unreachable or coordinates are out of bounds
     */
    public int distanceTo(int targetX, int targetY) {
        if (!isSearchResultAvailable() || !isWithinMazeBounds(targetX, targetY)) {
            return Integer.MAX_VALUE;
        }

        int baseStateIndex = computeBaseStateIndex(targetX, targetY);
        int minimumDistance = Integer.MAX_VALUE;

        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            int stateDistance = distanceByState[baseStateIndex + directionIndex];
            minimumDistance = Math.min(minimumDistance, stateDistance);
        }

        return minimumDistance;
    }

    /**
     * Returns the first move of the shortest path to the target cell from the most recent
     * search start position.
     *
     * <p>If multiple paths exist with equal length, one is chosen arbitrarily. The move
     * is selected from the path that arrives at the target with any orientation.</p>
     *
     * @param targetX the x-coordinate of the target cell
     * @param targetY the y-coordinate of the target cell
     * @return the first move of a shortest path, or {@link Move#DO_NOTHING} if the target
     *         is unreachable or coordinates are out of bounds
     */
    public Move firstMoveTo(int targetX, int targetY) {
        if (!isSearchResultAvailable() || !isWithinMazeBounds(targetX, targetY)) {
            return Move.DO_NOTHING;
        }

        int baseStateIndex = computeBaseStateIndex(targetX, targetY);
        int minimumDistance = Integer.MAX_VALUE;
        Move optimalMove = Move.DO_NOTHING;

        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            int stateIndex = baseStateIndex + directionIndex;
            int stateDistance = distanceByState[stateIndex];

            if (stateDistance < minimumDistance) {
                minimumDistance = stateDistance;
                optimalMove = firstMoveByState[stateIndex];
            }
        }

        return optimalMove;
    }

    /**
     * Computes oriented BFS distances and first moves from the given starting state.
     *
     * @param startX the starting x-coordinate
     * @param startY the starting y-coordinate
     * @param startDirection the starting facing direction
     * @param blockedCells optional bit set of temporarily blocked cells, may be null
     */
    public void computeFrom(int startX, int startY, ViewDirection startDirection, BitSet blockedCells) {
        mazeWidth = mazeModel.getWidth();
        mazeHeight = mazeModel.getHeight();
        mazeCellCount = mazeWidth * mazeHeight;

        if (mazeCellCount <= 0) {
            clearSearchResults();
            return;
        }

        int stateCount = mazeCellCount * DIRECTION_COUNT;
        ensureCapacity(stateCount);

        Arrays.fill(distanceByState, 0, stateCount, Integer.MAX_VALUE);
        Arrays.fill(firstMoveByState, 0, stateCount, Move.DO_NOTHING);
        Arrays.fill(previousStateByState, 0, stateCount, -1);

        if (!isWithinMazeBounds(startX, startY) || !mazeModel.isWalkable(startX, startY)) {
            return;
        }

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int startStateIndex = toStateIndex(startX, startY, startDirection.ordinal());

        distanceByState[startStateIndex] = 0;
        queue.add(startStateIndex);

        while (!queue.isEmpty()) {
            int currentStateIndex = queue.removeFirst();
            int currentDistance = distanceByState[currentStateIndex];

            int currentCellIndex = currentStateIndex / DIRECTION_COUNT;
            int currentDirectionIndex = currentStateIndex % DIRECTION_COUNT;
            int currentX = currentCellIndex % mazeWidth;
            int currentY = currentCellIndex / mazeWidth;

            enqueueTurnTransition(queue, currentStateIndex, currentDistance, currentX, currentY,
                    currentDirectionIndex, rotateLeft(currentDirectionIndex), Move.TURN_L);
            enqueueTurnTransition(queue, currentStateIndex, currentDistance, currentX, currentY,
                    currentDirectionIndex, rotateRight(currentDirectionIndex), Move.TURN_R);
            enqueueStepTransition(queue, currentStateIndex, currentDistance, currentX, currentY,
                    currentDirectionIndex, blockedCells);
        }
    }

    /**
     * Reconstructs the path to the target cell from the most recent search start position.
     *
     * @param targetX the x-coordinate of the target cell
     * @param targetY the y-coordinate of the target cell
     * @return an immutable list of visited cells from start to target, or empty if unreachable
     */
    public List<Point> getPathTo(int targetX, int targetY) {
        if (!isSearchResultAvailable() || !isWithinMazeBounds(targetX, targetY)) {
            return List.of();
        }

        int bestStateIndex = findBestArrivalStateIndex(targetX, targetY);
        if (bestStateIndex < 0) {
            return List.of();
        }

        ArrayList<Point> reversedPath = new ArrayList<>();
        int currentStateIndex = bestStateIndex;

        while (currentStateIndex >= 0) {
            int cellIndex = currentStateIndex / DIRECTION_COUNT;
            int x = cellIndex % mazeWidth;
            int y = cellIndex / mazeWidth;

            if (reversedPath.isEmpty()
                    || reversedPath.get(reversedPath.size() - 1).x != x
                    || reversedPath.get(reversedPath.size() - 1).y != y) {
                reversedPath.add(new Point(x, y));
            }

            currentStateIndex = previousStateByState[currentStateIndex];
        }

        Collections.reverse(reversedPath);
        return List.copyOf(reversedPath);
    }

    private void enqueueTurnTransition(ArrayDeque<Integer> queue,
                                       int currentStateIndex,
                                       int currentDistance,
                                       int currentX,
                                       int currentY,
                                       int currentDirectionIndex,
                                       int nextDirectionIndex,
                                       Move transitionMove) {
        int nextStateIndex = toStateIndex(currentX, currentY, nextDirectionIndex);
        relaxTransition(queue, currentStateIndex, currentDistance, currentDirectionIndex, nextStateIndex, transitionMove);
    }

    private void enqueueStepTransition(ArrayDeque<Integer> queue,
                                       int currentStateIndex,
                                       int currentDistance,
                                       int currentX,
                                       int currentY,
                                       int currentDirectionIndex,
                                       BitSet blockedCells) {
        ViewDirection direction = ViewDirection.values()[currentDirectionIndex];
        int nextX = currentX + deltaX(direction);
        int nextY = currentY + deltaY(direction);

        if (!isWithinMazeBounds(nextX, nextY) || !mazeModel.isWalkable(nextX, nextY)) {
            return;
        }

        int nextCellIndex = toCellIndex(nextX, nextY);
        if (blockedCells != null && blockedCells.get(nextCellIndex)) {
            return;
        }

        int nextStateIndex = toStateIndex(nextX, nextY, currentDirectionIndex);
        relaxTransition(queue, currentStateIndex, currentDistance, currentDirectionIndex, nextStateIndex, Move.STEP);
    }

    private void relaxTransition(ArrayDeque<Integer> queue,
                                 int currentStateIndex,
                                 int currentDistance,
                                 int currentDirectionIndex,
                                 int nextStateIndex,
                                 Move transitionMove) {
        if (distanceByState[nextStateIndex] != Integer.MAX_VALUE) {
            return;
        }

        distanceByState[nextStateIndex] = currentDistance + 1;
        previousStateByState[nextStateIndex] = currentStateIndex;
        firstMoveByState[nextStateIndex] = (currentDistance == 0)
                ? transitionMove
                : firstMoveByState[currentStateIndex];

        queue.addLast(nextStateIndex);
    }

    private int findBestArrivalStateIndex(int targetX, int targetY) {
        int baseStateIndex = computeBaseStateIndex(targetX, targetY);
        int bestStateIndex = -1;
        int minimumDistance = Integer.MAX_VALUE;

        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            int stateIndex = baseStateIndex + directionIndex;
            int stateDistance = distanceByState[stateIndex];

            if (stateDistance < minimumDistance) {
                minimumDistance = stateDistance;
                bestStateIndex = stateIndex;
            }
        }

        return minimumDistance == Integer.MAX_VALUE ? -1 : bestStateIndex;
    }

    private void ensureCapacity(int stateCount) {
        if (distanceByState == null || distanceByState.length < stateCount) {
            distanceByState = new int[stateCount];
            firstMoveByState = new Move[stateCount];
            previousStateByState = new int[stateCount];
        }
    }

    private void clearSearchResults() {
        mazeWidth = 0;
        mazeHeight = 0;
        mazeCellCount = 0;
        distanceByState = null;
        firstMoveByState = null;
        previousStateByState = null;
    }

    private boolean isSearchResultAvailable() {
        return distanceByState != null && mazeWidth > 0 && mazeHeight > 0;
    }

    private boolean isWithinMazeBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < mazeWidth && y < mazeHeight;
    }

    private int toCellIndex(int x, int y) {
        return (y * mazeWidth) + x;
    }

    private int computeBaseStateIndex(int x, int y) {
        return toCellIndex(x, y) * DIRECTION_COUNT;
    }

    private int toStateIndex(int x, int y, int directionIndex) {
        return computeBaseStateIndex(x, y) + directionIndex;
    }

    private int rotateLeft(int directionIndex) {
        return (directionIndex + DIRECTION_COUNT - 1) % DIRECTION_COUNT;
    }

    private int rotateRight(int directionIndex) {
        return (directionIndex + 1) % DIRECTION_COUNT;
    }

    private int deltaX(ViewDirection direction) {
        return switch (direction) {
            case NORTH, SOUTH -> 0;
            case EAST -> 1;
            case WEST -> -1;
        };
    }

    private int deltaY(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> -1;
            case EAST, WEST -> 0;
            case SOUTH -> 1;
        };
    }
}