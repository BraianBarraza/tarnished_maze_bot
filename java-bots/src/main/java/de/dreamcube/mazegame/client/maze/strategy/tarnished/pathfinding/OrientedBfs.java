package de.dreamcube.mazegame.client.maze.strategy.tarnished.pathfinding;

import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.MazeModel;
import de.dreamcube.mazegame.common.maze.ViewDirection;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Performs oriented BFS over (x,y,direction) states.
 * Each action (TURN_L, TURN_R, STEP) has cost 1 tick.
 */
public class OrientedBfs {

    private final MazeModel mazeModel;

    private int mazeWidth;
    private int mazeHeight;
    private int[] distanceByStateIndex;
    private Move[] firstMoveByStateIndex;
    private int[] previousStateIndexByState;

    private static final ViewDirection[] DIRECTIONS_BY_INDEX = ViewDirection.values();

    public OrientedBfs(MazeModel mazeModel) {
        this.mazeModel = mazeModel;
    }

    /**
     * Returns the minimum oriented distance to a target cell, across all directions.
     */
    public int distanceTo(int targetX, int targetY) {
        if (!isWithinMazeBounds(targetX, targetY) || distanceByStateIndex == null) {
            return Integer.MAX_VALUE;
        }
        int baseStateIndex = ((targetY * mazeWidth) + targetX) * 4;
        int bestDistance = Integer.MAX_VALUE;
        for (int directionIndex = 0; directionIndex < 4; directionIndex++) {
            int candidateDistance = distanceByStateIndex[baseStateIndex + directionIndex];
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance;
            }
        }
        return bestDistance;
    }

    /**
     * Returns the first move of a shortest path to the target cell.
     */
    public Move firstMoveTo(int targetX, int targetY) {
        if (!isWithinMazeBounds(targetX, targetY) || firstMoveByStateIndex == null || distanceByStateIndex == null) {
            return Move.DO_NOTHING;
        }
        int baseStateIndex = ((targetY * mazeWidth) + targetX) * 4;
        int bestDistance = Integer.MAX_VALUE;
        Move bestMove = Move.DO_NOTHING;
        for (int directionIndex = 0; directionIndex < 4; directionIndex++) {
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
     * If blockedCells is provided, true cells are treated as non-walkable.
     */
    public void computeFrom(int startX, int startY, ViewDirection startDirection, boolean[][] blockedCells) {
        mazeWidth = mazeModel.width;
        mazeHeight = mazeModel.height;

        int totalStateCount = mazeWidth * mazeHeight * 4;
        distanceByStateIndex = new int[totalStateCount];
        firstMoveByStateIndex = new Move[totalStateCount];
        previousStateIndexByState = new int[totalStateCount];
        Arrays.fill(distanceByStateIndex, Integer.MAX_VALUE);
        Arrays.fill(firstMoveByStateIndex, Move.DO_NOTHING);
        Arrays.fill(previousStateIndexByState, -1);

        if (!isWithinMazeBounds(startX, startY) || mazeModel.walkable == null || !mazeModel.walkable[startX][startY]) {
            return;
        }

        int startStateIndex = toStateIndex(startX, startY, directionToIndex(startDirection));
        distanceByStateIndex[startStateIndex] = 0;

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startStateIndex);

        while (!queue.isEmpty()) {
            int currentStateIndex = queue.removeFirst();

            int cellIndex = currentStateIndex / 4;
            int directionIndex = currentStateIndex % 4;
            int x = cellIndex % mazeWidth;
            int y = cellIndex / mazeWidth;

            int nextDistance = distanceByStateIndex[currentStateIndex] + 1;
            Move inheritedFirstMove = firstMoveByStateIndex[currentStateIndex];
            ViewDirection currentDirection = DIRECTIONS_BY_INDEX[directionIndex];

            // TURN_L
            ViewDirection leftDirection = turnLeft(currentDirection);
            relaxNeighbor(queue, currentStateIndex, x, y, directionToIndex(leftDirection), nextDistance, inheritedFirstMove, Move.TURN_L);

            // TURN_R
            ViewDirection rightDirection = turnRight(currentDirection);
            relaxNeighbor(queue, currentStateIndex, x, y, directionToIndex(rightDirection), nextDistance, inheritedFirstMove, Move.TURN_R);

            // STEP
            int nextX = x + stepDeltaX(currentDirection);
            int nextY = y + stepDeltaY(currentDirection);
            if (isWithinMazeBounds(nextX, nextY)
                    && mazeModel.walkable[nextX][nextY]
                    && !isBlocked(blockedCells, nextX, nextY)) {
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

    /**
     * Returns a list of maze cells on the shortest path to the target.
     * Each entry is an int array: [x, y]. If unreachable, returns an empty list.
     */
    public java.util.List<int[]> getPathCellsTo(int targetX, int targetY) {
        if (!isWithinMazeBounds(targetX, targetY) || distanceByStateIndex == null || previousStateIndexByState == null) {
            return java.util.List.of();
        }
        int baseStateIndex = ((targetY * mazeWidth) + targetX) * 4;
        int bestDistance = Integer.MAX_VALUE;
        int bestStateIndex = -1;
        for (int directionIndex = 0; directionIndex < 4; directionIndex++) {
            int candidateDistance = distanceByStateIndex[baseStateIndex + directionIndex];
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance;
                bestStateIndex = baseStateIndex + directionIndex;
            }
        }
        if (bestStateIndex == -1 || bestDistance == Integer.MAX_VALUE) {
            return java.util.List.of();
        }

        java.util.ArrayList<int[]> reversedCells = new java.util.ArrayList<>();
        int currentStateIndex = bestStateIndex;
        int lastCellX = Integer.MIN_VALUE;
        int lastCellY = Integer.MIN_VALUE;
        while (currentStateIndex >= 0) {
            int cellIndex = currentStateIndex / 4;
            int cellX = cellIndex % mazeWidth;
            int cellY = cellIndex / mazeWidth;
            if (cellX != lastCellX || cellY != lastCellY) {
                reversedCells.add(new int[]{cellX, cellY});
                lastCellX = cellX;
                lastCellY = cellY;
            }
            currentStateIndex = previousStateIndexByState[currentStateIndex];
        }

        java.util.Collections.reverse(reversedCells);
        return reversedCells;
    }

    private int toStateIndex(int x, int y, int directionIndex) {
        return ((y * mazeWidth) + x) * 4 + directionIndex;
    }

    private boolean isWithinMazeBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < mazeWidth && y < mazeHeight;
    }

    private int directionToIndex(ViewDirection direction) {
        // Keeps the code robust even if the enum order changes.
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
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 0;
            case WEST -> -1;
        };
    }

    private int stepDeltaY(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> -1;
            case EAST -> 0;
            case SOUTH -> 1;
            case WEST -> 0;
        };
    }

    private boolean isBlocked(boolean[][] blockedCells, int x, int y) {
        return blockedCells != null && blockedCells[x][y];
    }
}
