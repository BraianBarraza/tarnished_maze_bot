package de.dreamcube.mazegame.client.maze.strategy.tarnished.pathfinding;

import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.MazeModel;
import de.dreamcube.mazegame.common.maze.ViewDirection;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Performs oriented BFS over (x, y, direction) states.
 *
 * <p>Each action ({@link Move#TURN_L}, {@link Move#TURN_R}, {@link Move#STEP}) costs exactly 1 tick.
 * This means a standard BFS finds the shortest sequence of actions.</p>
 */
public final class OrientedBfs {

    private static final int DIRECTION_COUNT = 4;
    private static final ViewDirection[] DIRECTIONS_BY_INDEX = {
            ViewDirection.NORTH, ViewDirection.EAST, ViewDirection.SOUTH, ViewDirection.WEST
    };

    private final MazeModel mazeModel;

    private int mazeWidth;
    private int mazeHeight;

    private int[] distanceByStateIndex;
    private Move[] firstMoveByStateIndex;
    private int[] previousStateIndexByState;

    public OrientedBfs(MazeModel mazeModel) {
        this.mazeModel = mazeModel;
    }

    /**
     * Returns the minimum oriented distance (in ticks) to a target cell, across all directions.
     */
    public int distanceTo(int targetX, int targetY) {
        if (isReady() || isWithinMazeBounds(targetX, targetY)) {
            return Integer.MAX_VALUE;
        }

        int baseStateIndex = ((targetY * mazeWidth) + targetX) * DIRECTION_COUNT;
        int bestDistance = Integer.MAX_VALUE;
        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            bestDistance = Math.min(bestDistance, distanceByStateIndex[baseStateIndex + directionIndex]);
        }
        return bestDistance;
    }

    /**
     * Returns the first move of a shortest path to the target cell.
     */
    public Move firstMoveTo(int targetX, int targetY) {
        if (isReady() || isWithinMazeBounds(targetX, targetY)) {
            return Move.DO_NOTHING;
        }

        int baseStateIndex = ((targetY * mazeWidth) + targetX) * DIRECTION_COUNT;
        int bestDistance = Integer.MAX_VALUE;
        Move bestMove = Move.DO_NOTHING;
        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            int candidateDistance = distanceByStateIndex[baseStateIndex + directionIndex];
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance;
                bestMove = firstMoveByStateIndex[baseStateIndex + directionIndex];
            }
        }
        return bestMove;
    }

    /**
     * Computes oriented BFS distances and first moves from a starting state.
     */
    public void computeFrom(int startX, int startY, ViewDirection startDirection) {
        computeFrom(startX, startY, startDirection, null);
    }

    /**
     * Computes oriented BFS distances and first moves from a starting state.
     *
     * @param blockedCells if provided, cells with true are treated as non-walkable.
     */
    public void computeFrom(int startX, int startY, ViewDirection startDirection, boolean[][] blockedCells) {
        mazeWidth = mazeModel.getWidth();
        mazeHeight = mazeModel.getHeight();

        int totalStateCount = mazeWidth * mazeHeight * DIRECTION_COUNT;
        ensureCapacity(totalStateCount);

        Arrays.fill(distanceByStateIndex, Integer.MAX_VALUE);
        Arrays.fill(firstMoveByStateIndex, Move.DO_NOTHING);
        Arrays.fill(previousStateIndexByState, -1);

        if (!mazeModel.isWalkable(startX, startY)) {
            return;
        }

        int startStateIndex = toStateIndex(startX, startY, directionToIndex(startDirection));
        distanceByStateIndex[startStateIndex] = 0;

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startStateIndex);

        while (!queue.isEmpty()) {
            int currentStateIndex = queue.removeFirst();

            int cellIndex = currentStateIndex / DIRECTION_COUNT;
            int directionIndex = currentStateIndex % DIRECTION_COUNT;
            int x = cellIndex % mazeWidth;
            int y = cellIndex / mazeWidth;

            int nextDistance = distanceByStateIndex[currentStateIndex] + 1;
            Move inheritedFirstMove = firstMoveByStateIndex[currentStateIndex];
            ViewDirection currentDirection = DIRECTIONS_BY_INDEX[directionIndex];

            // TURN_L
            relaxNeighbor(queue, currentStateIndex, x, y, directionToIndex(turnLeft(currentDirection)), nextDistance, inheritedFirstMove, Move.TURN_L);

            // TURN_R
            relaxNeighbor(queue, currentStateIndex, x, y, directionToIndex(turnRight(currentDirection)), nextDistance, inheritedFirstMove, Move.TURN_R);

            // STEP
            int nextX = x + stepDeltaX(currentDirection);
            int nextY = y + stepDeltaY(currentDirection);
            if (mazeModel.isWalkable(nextX, nextY) && !isBlocked(blockedCells, nextX, nextY)) {
                int stepStateIndex = toStateIndex(nextX, nextY, directionIndex);
                if (distanceByStateIndex[stepStateIndex] == Integer.MAX_VALUE) {
                    distanceByStateIndex[stepStateIndex] = nextDistance;
                    firstMoveByStateIndex[stepStateIndex] =
                            distanceByStateIndex[currentStateIndex] == 0 ? Move.STEP : inheritedFirstMove;
                    previousStateIndexByState[stepStateIndex] = currentStateIndex;
                    queue.add(stepStateIndex);
                }
            }
        }
    }

    /**
     * Returns a list of maze cells on the shortest path to the target.
     * If unreachable, returns an empty list.
     */
    public List<Point> getPathTo(int targetX, int targetY) {
        if (isReady() || isWithinMazeBounds(targetX, targetY)) {
            return List.of();
        }

        int baseStateIndex = ((targetY * mazeWidth) + targetX) * DIRECTION_COUNT;
        int bestDistance = Integer.MAX_VALUE;
        int bestStateIndex = -1;
        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            int candidateDistance = distanceByStateIndex[baseStateIndex + directionIndex];
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance;
                bestStateIndex = baseStateIndex + directionIndex;
            }
        }
        if (bestStateIndex == -1 || bestDistance == Integer.MAX_VALUE) {
            return List.of();
        }

        ArrayList<Point> reversedCells = new ArrayList<>();
        int currentStateIndex = bestStateIndex;
        int lastCellX = Integer.MIN_VALUE;
        int lastCellY = Integer.MIN_VALUE;
        while (currentStateIndex >= 0) {
            int cellIndex = currentStateIndex / DIRECTION_COUNT;
            int cellX = cellIndex % mazeWidth;
            int cellY = cellIndex / mazeWidth;

            if (cellX != lastCellX || cellY != lastCellY) {
                reversedCells.add(new Point(cellX, cellY));
                lastCellX = cellX;
                lastCellY = cellY;
            }

            currentStateIndex = previousStateIndexByState[currentStateIndex];
        }

        Collections.reverse(reversedCells);
        return reversedCells;
    }

    private boolean isReady() {
        return distanceByStateIndex == null || firstMoveByStateIndex == null || previousStateIndexByState == null;
    }

    private void ensureCapacity(int totalStateCount) {
        if (distanceByStateIndex != null && distanceByStateIndex.length == totalStateCount) {
            return;
        }
        distanceByStateIndex = new int[totalStateCount];
        firstMoveByStateIndex = new Move[totalStateCount];
        previousStateIndexByState = new int[totalStateCount];
    }

    private void relaxNeighbor(
            ArrayDeque<Integer> queue,
            int currentStateIndex,
            int x,
            int y,
            int neighborDirectionIndex,
            int nextDistance,
            Move inheritedFirstMove,
            Move turnMove
    ) {
        int neighborStateIndex = toStateIndex(x, y, neighborDirectionIndex);
        if (distanceByStateIndex[neighborStateIndex] != Integer.MAX_VALUE) {
            return;
        }

        distanceByStateIndex[neighborStateIndex] = nextDistance;
        firstMoveByStateIndex[neighborStateIndex] =
                distanceByStateIndex[currentStateIndex] == 0 ? turnMove : inheritedFirstMove;
        previousStateIndexByState[neighborStateIndex] = currentStateIndex;
        queue.add(neighborStateIndex);
    }

    private int toStateIndex(int x, int y, int directionIndex) {
        return ((y * mazeWidth) + x) * DIRECTION_COUNT + directionIndex;
    }

    private boolean isWithinMazeBounds(int x, int y) {
        return x < 0 || y < 0 || x >= mazeWidth || y >= mazeHeight;
    }

    private int directionToIndex(ViewDirection direction) {
        // Robust mapping even if enum order changes.
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
        };
    }

    private ViewDirection turnLeft(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> ViewDirection.WEST;
            case WEST -> ViewDirection.SOUTH;
            case SOUTH -> ViewDirection.EAST;
            case EAST -> ViewDirection.NORTH;
        };
    }

    private ViewDirection turnRight(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> ViewDirection.EAST;
            case EAST -> ViewDirection.SOUTH;
            case SOUTH -> ViewDirection.WEST;
            case WEST -> ViewDirection.NORTH;
        };
    }

    private int stepDeltaX(ViewDirection direction) {
        return switch (direction) {
            case NORTH, SOUTH -> 0;
            case EAST -> 1;
            case WEST -> -1;
        };
    }

    private int stepDeltaY(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> -1;
            case EAST, WEST -> 0;
            case SOUTH -> 1;
        };
    }

    private boolean isBlocked(boolean[][] blockedCells, int x, int y) {
        return blockedCells != null
                && x >= 0 && y >= 0
                && x < blockedCells.length
                && y < blockedCells[x].length
                && blockedCells[x][y];
    }
}
