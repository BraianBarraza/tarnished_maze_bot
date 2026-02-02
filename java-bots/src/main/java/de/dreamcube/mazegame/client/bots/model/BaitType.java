package de.dreamcube.mazegame.client.bots.model;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

/**
 * Known bait types and their scores.
 */
public enum BaitType {
    GEM(314, "G", false),
    COFFEE(42, "C", false),
    FOOD(13, "F", false),
    TRAP(-128, "T", true);

    private final int score;
    private final String label;
    /**
     * -- GETTER --
     *  Returns true when this bait is a trap.
     */
    @Getter
    private final boolean trap;

    BaitType(int score, String label, boolean trap) {
        this.score = score;
        this.label = label;
        this.trap = trap;
    }

    /**
     * Returns the score associated with this bait type.
     */
    public int score() {
        return score;
    }

    /**
     * Returns a short label for visualization.
     */
    public String label() {
        return label;
    }

    /**
     * Maps a score value to a known bait type.
     */
    public static @Nullable BaitType fromScore(int score) {
        for (BaitType type : values()) {
            if (type.score == score) return type;
        }
        return null;
    }
}
