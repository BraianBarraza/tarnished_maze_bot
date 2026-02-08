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

    private static final int DIRECTION_COUNT = 4;
    private static final ViewDirection[] DIRECTIONS = {
            ViewDirection.NORTH,
            ViewDirection.EAST,
            ViewDirection.SOUTH,
            ViewDirection.WEST
    };

    private final MazeModel mazeModel;

    private int mazeWidth;
    private int mazeHeight;

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
     * <p>After this method completes, {@link #distanceTo(int, int)} and
     * {@link #firstMoveTo(int, int)} will return results relative to this start state.</p>
     *
     * @param startX the starting x-coordinate
     * @param startY the starting y-coordinate
     * @param startDirection the starting facing direction
     */
    public void computeFrom(int startX, int startY, ViewDirection startDirection) {
        computeFrom(startX, startY, startDirection, null);
    }

    /**
     * Computes oriented BFS distances and first moves from the given starting state,
     * optionally treating certain cells as blocked.
     *
     * <p>The blocked cells array allows temporary modification of the maze walkability
     * without mutating the underlying {@link MazeModel}. This is useful for path planning
     * that avoids known hazards.</p>
     *
     * @param startX the starting x-coordinate
     * @param startY the starting y-coordinate
     * @param startDirection the starting facing direction
     * @param blockedCells a 2D array where true indicates blocked cells, or null to use
     *                     only the maze model's walkability data
     */
    public void computeFrom(int startX, int startY, ViewDirection startDirection, boolean[][] blockedCells) {
        mazeWidth = mazeModel.getWidth();
        mazeHeight = mazeModel.getHeight();

        int totalStateCount = mazeWidth * mazeHeight * DIRECTION_COUNT;
        ensureStateArrayCapacity(totalStateCount);

        resetStateArrays();

        if (!mazeModel.isWalkable(startX, startY)) {
            return;
        }

        int startStateIndex = computeStateIndex(startX, startY, getDirectionIndex(startDirection));
        distanceByState[startStateIndex] = 0;

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startStateIndex);

        processSearchQueue(queue, blockedCells);
    }

    /**
     * Returns the sequence of maze cells forming the shortest path to the target.
     *
     * <p>The returned list begins with the starting cell and ends with the target cell.
     * Only cells are included; the orientations at each cell are not part of the result.</p>
     *
     * @param targetX the x-coordinate of the target cell
     * @param targetY the y-coordinate of the target cell
     * @return a list of points representing the path, or an empty list if the target
     *         is unreachable or coordinates are out of bounds
     */
    public List<Point> getPathTo(int targetX, int targetY) {
        if (!isSearchResultAvailable() || !isWithinMazeBounds(targetX, targetY)) {
            return List.of();
        }

        int optimalStateIndex = findOptimalArrivalState(targetX, targetY);
        if (optimalStateIndex == -1) {
            return List.of();
        }

        return reconstructPath(optimalStateIndex);
    }

    /**
     * Processes the BFS queue, exploring all reachable states from the start position.
     *
     * @param queue the queue of state indices to process
     * @param blockedCells optional array of blocked cells
     */
    private void processSearchQueue(ArrayDeque<Integer> queue, boolean[][] blockedCells) {
        while (!queue.isEmpty()) {
            int currentStateIndex = queue.removeFirst();

            int cellIndex = currentStateIndex / DIRECTION_COUNT;
            int directionIndex = currentStateIndex % DIRECTION_COUNT;
            int x = cellIndex % mazeWidth;
            int y = cellIndex / mazeWidth;

            int nextDistance = distanceByState[currentStateIndex] + 1;
            Move inheritedFirstMove = firstMoveByState[currentStateIndex];
            ViewDirection currentDirection = DIRECTIONS[directionIndex];

            exploreRotations(queue, currentStateIndex, x, y, currentDirection, nextDistance, inheritedFirstMove);
            exploreStepForward(queue, currentStateIndex, x, y, currentDirection, directionIndex,
                    nextDistance, inheritedFirstMove, blockedCells);
        }
    }

    /**
     * Explores left and right rotation transitions from the current state.
     *
     * @param queue the BFS queue
     * @param currentStateIndex the current state index
     * @param x the current x-coordinate
     * @param y the current y-coordinate
     * @param currentDirection the current facing direction
     * @param nextDistance the distance for successor states
     * @param inheritedFirstMove the first move to inherit for successor states
     */
    private void exploreRotations(ArrayDeque<Integer> queue, int currentStateIndex, int x, int y,
                                  ViewDirection currentDirection, int nextDistance, Move inheritedFirstMove) {
        relaxNeighborState(queue, currentStateIndex, x, y,
                getDirectionIndex(rotateLeft(currentDirection)),
                nextDistance, inheritedFirstMove, Move.TURN_L);

        relaxNeighborState(queue, currentStateIndex, x, y,
                getDirectionIndex(rotateRight(currentDirection)),
                nextDistance, inheritedFirstMove, Move.TURN_R);
    }

    /**
     * Explores the step-forward transition from the current state.
     *
     * @param queue the BFS queue
     * @param currentStateIndex the current state index
     * @param x the current x-coordinate
     * @param y the current y-coordinate
     * @param currentDirection the current facing direction
     * @param directionIndex the index of the current direction
     * @param nextDistance the distance for the successor state
     * @param inheritedFirstMove the first move to inherit for the successor state
     * @param blockedCells optional array of blocked cells
     */
    private void exploreStepForward(ArrayDeque<Integer> queue, int currentStateIndex, int x, int y,
                                    ViewDirection currentDirection, int directionIndex,
                                    int nextDistance, Move inheritedFirstMove, boolean[][] blockedCells) {
        int nextX = x + DirectionUtil.getDeltaX(currentDirection);
        int nextY = y + DirectionUtil.getDeltaY(currentDirection);

        if (mazeModel.isWalkable(nextX, nextY) && !isBlocked(blockedCells, nextX, nextY)) {
            int stepStateIndex = computeStateIndex(nextX, nextY, directionIndex);

            if (distanceByState[stepStateIndex] == Integer.MAX_VALUE) {
                distanceByState[stepStateIndex] = nextDistance;
                firstMoveByState[stepStateIndex] = determineFirstMove(currentStateIndex, inheritedFirstMove, Move.STEP);
                previousStateByState[stepStateIndex] = currentStateIndex;
                queue.add(stepStateIndex);
            }
        }
    }

    /**
     * Attempts to relax a neighbor state during BFS exploration.
     *
     * <p>If the neighbor state has not yet been visited, it is added to the queue
     * with updated distance and first-move information.</p>
     *
     * @param queue the BFS queue
     * @param currentStateIndex the current state index
     * @param x the x-coordinate (unchanged for rotations)
     * @param y the y-coordinate (unchanged for rotations)
     * @param neighborDirectionIndex the direction index of the neighbor state
     * @param nextDistance the distance value for the neighbor
     * @param inheritedFirstMove the first move to inherit if not at start
     * @param transitionMove the move that transitions to this neighbor
     */
    private void relaxNeighborState(ArrayDeque<Integer> queue, int currentStateIndex,
                                    int x, int y, int neighborDirectionIndex,
                                    int nextDistance, Move inheritedFirstMove, Move transitionMove) {
        int neighborStateIndex = computeStateIndex(x, y, neighborDirectionIndex);

        if (distanceByState[neighborStateIndex] != Integer.MAX_VALUE) {
            return;
        }

        distanceByState[neighborStateIndex] = nextDistance;
        firstMoveByState[neighborStateIndex] = determineFirstMove(currentStateIndex, inheritedFirstMove, transitionMove);
        previousStateByState[neighborStateIndex] = currentStateIndex;
        queue.add(neighborStateIndex);
    }

    /**
     * Determines the first move for a successor state based on distance from start.
     *
     * @param currentStateIndex the current state index
     * @param inheritedFirstMove the first move from the current state
     * @param transitionMove the move transitioning to the successor
     * @return the transition move if at start (distance 0), otherwise the inherited first move
     */
    private Move determineFirstMove(int currentStateIndex, Move inheritedFirstMove, Move transitionMove) {
        return distanceByState[currentStateIndex] == 0 ? transitionMove : inheritedFirstMove;
    }

    /**
     * Finds the state index at the target cell with minimum distance across all directions.
     *
     * @param targetX the target x-coordinate
     * @param targetY the target y-coordinate
     * @return the optimal state index, or -1 if the target is unreachable
     */
    private int findOptimalArrivalState(int targetX, int targetY) {
        int baseStateIndex = computeBaseStateIndex(targetX, targetY);
        int minimumDistance = Integer.MAX_VALUE;
        int optimalStateIndex = -1;

        for (int directionIndex = 0; directionIndex < DIRECTION_COUNT; directionIndex++) {
            int stateIndex = baseStateIndex + directionIndex;
            int stateDistance = distanceByState[stateIndex];

            if (stateDistance < minimumDistance) {
                minimumDistance = stateDistance;
                optimalStateIndex = stateIndex;
            }
        }

        return minimumDistance == Integer.MAX_VALUE ? -1 : optimalStateIndex;
    }

    /**
     * Reconstructs the path from start to the given state by following predecessor links.
     *
     * @param targetStateIndex the ending state index
     * @return a list of points from start to target
     */
    private List<Point> reconstructPath(int targetStateIndex) {
        ArrayList<Point> reversedPath = new ArrayList<>();
        int currentStateIndex = targetStateIndex;
        int lastCellX = Integer.MIN_VALUE;
        int lastCellY = Integer.MIN_VALUE;

        while (currentStateIndex >= 0) {
            int cellIndex = currentStateIndex / DIRECTION_COUNT;
            int cellX = cellIndex % mazeWidth;
            int cellY = cellIndex / mazeWidth;

            if (cellX != lastCellX || cellY != lastCellY) {
                reversedPath.add(new Point(cellX, cellY));
                lastCellX = cellX;
                lastCellY = cellY;
            }

            currentStateIndex = previousStateByState[currentStateIndex];
        }

        Collections.reverse(reversedPath);
        return reversedPath;
    }

    /**
     * Checks if search results are available for querying.
     *
     * @return true if a search has been performed and results are available
     */
    private boolean isSearchResultAvailable() {
        return distanceByState != null && firstMoveByState != null && previousStateByState != null;
    }

    /**
     * Ensures the state arrays have sufficient capacity for the given state count.
     *
     * @param totalStateCount the required capacity
     */
    private void ensureStateArrayCapacity(int totalStateCount) {
        if (distanceByState != null && distanceByState.length == totalStateCount) {
            return;
        }
        distanceByState = new int[totalStateCount];
        firstMoveByState = new Move[totalStateCount];
        previousStateByState = new int[totalStateCount];
    }

    /**
     * Resets all state arrays to their initial unvisited values.
     */
    private void resetStateArrays() {
        Arrays.fill(distanceByState, Integer.MAX_VALUE);
        Arrays.fill(firstMoveByState, Move.DO_NOTHING);
        Arrays.fill(previousStateByState, -1);
    }

    /**
     * Computes the state index for a given cell and direction.
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @param directionIndex the direction index (0-3)
     * @return the flattened state index
     */
    private int computeStateIndex(int x, int y, int directionIndex) {
        return ((y * mazeWidth) + x) * DIRECTION_COUNT + directionIndex;
    }

    /**
     * Computes the base state index for a given cell (direction-independent).
     *
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @return the base state index (direction 0)
     */
    private int computeBaseStateIndex(int x, int y) {
        return ((y * mazeWidth) + x) * DIRECTION_COUNT;
    }

    /**
     * Checks if coordinates are within maze bounds.
     *
     * @param x the x-coordinate to check
     * @param y the y-coordinate to check
     * @return true if coordinates are valid
     */
    private boolean isWithinMazeBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < mazeWidth && y < mazeHeight;
    }

    /**
     * Maps a view direction to its integer index.
     *
     * @param direction the view direction
     * @return the corresponding index (0=NORTH, 1=EAST, 2=SOUTH, 3=WEST)
     */
    private int getDirectionIndex(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
        };
    }

    /**
     * Rotates a direction 90 degrees counterclockwise.
     *
     * @param direction the current direction
     * @return the direction after rotating left
     */
    private ViewDirection rotateLeft(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> ViewDirection.WEST;
            case WEST -> ViewDirection.SOUTH;
            case SOUTH -> ViewDirection.EAST;
            case EAST -> ViewDirection.NORTH;
        };
    }

    /**
     * Rotates a direction 90 degrees clockwise.
     *
     * @param direction the current direction
     * @return the direction after rotating right
     */
    private ViewDirection rotateRight(ViewDirection direction) {
        return switch (direction) {
            case NORTH -> ViewDirection.EAST;
            case EAST -> ViewDirection.SOUTH;
            case SOUTH -> ViewDirection.WEST;
            case WEST -> ViewDirection.NORTH;
        };
    }

    /**
     * Checks if a cell is blocked according to the provided blocked cells array.
     *
     * @param blockedCells the array of blocked cells, or null
     * @param x the x-coordinate to check
     * @param y the y-coordinate to check
     * @return true if the cell is blocked
     */
    private boolean isBlocked(boolean[][] blockedCells, int x, int y) {
        return blockedCells != null
                && x >= 0 && y >= 0
                && x < blockedCells.length
                && y < blockedCells[x].length
                && blockedCells[x][y];
    }

    /**
     * Utility class for direction-related coordinate calculations.
     */
    private static final class DirectionUtil {

        /**
         * Returns the x-coordinate change when stepping in the given direction.
         *
         * @param direction the facing direction
         * @return 1 for EAST, -1 for WEST, 0 otherwise
         */
        static int getDeltaX(ViewDirection direction) {
            return switch (direction) {
                case NORTH, SOUTH -> 0;
                case EAST -> 1;
                case WEST -> -1;
            };
        }

        /**
         * Returns the y-coordinate change when stepping in the given direction.
         *
         * @param direction the facing direction
         * @return -1 for NORTH, 1 for SOUTH, 0 otherwise
         */
        static int getDeltaY(ViewDirection direction) {
            return switch (direction) {
                case NORTH -> -1;
                case EAST, WEST -> 0;
                case SOUTH -> 1;
            };
        }
    }
}