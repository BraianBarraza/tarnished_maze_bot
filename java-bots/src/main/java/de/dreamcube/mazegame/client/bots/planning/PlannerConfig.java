package de.dreamcube.mazegame.client.bots.planning;

/**
 * Tuning parameters for the reward planner.
 */
public final class PlannerConfig {

    private final int maxDepth;
    private final int maxExpansions;
    private final int candidateBaits;
    private final double moveCost;
    private final double trapStepPenalty;

    /**
     * Creates a new planner config.
     */
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

    /**
     * Returns the maximum search depth in moves.
     */
    public int maxDepth() {
        return maxDepth;
    }

    /**
     * Returns the maximum node expansions per tick.
     */
    public int maxExpansions() {
        return maxExpansions;
    }

    /**
     * Returns the maximum number of bait candidates.
     */
    public int candidateBaits() {
        return candidateBaits;
    }

    /**
     * Returns the move cost weight.
     */
    public double moveCost() {
        return moveCost;
    }

    /**
     * Returns the extra penalty per trap step.
     */
    public double trapStepPenalty() {
        return trapStepPenalty;
    }
}
