package de.dreamcube.mazegame.client.maze.strategy.tarnished.ui;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.strategy.tarnished.model.WorldState;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Dimension;

/**
 * Minimal bot UI to pause/resume the strategy and show the current target.
 */
public final class BotControlPanel extends JPanel {

    private final WorldState worldState;

    private final JCheckBox pausedCheckBox;
    private final JCheckBox avoidCollisionsCheckBox;
    private final JLabel targetLabel;
    private final JLabel scoreLabel;

    public BotControlPanel(WorldState worldState) {
        this.worldState = worldState;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Tarnished Bot"));
        setPreferredSize(new Dimension(260, 120));

        pausedCheckBox = new JCheckBox("Paused");
        pausedCheckBox.addActionListener(e -> this.worldState.setPaused(pausedCheckBox.isSelected()));

        avoidCollisionsCheckBox = new JCheckBox("Avoid collisions");

        targetLabel = new JLabel("Target: (none)");
        scoreLabel = new JLabel("Score: -");

        add(pausedCheckBox);
        add(targetLabel);
        add(scoreLabel);

        // Small refresh loop so the UI reflects the current state without extra plumbing.
        new Timer(250, e -> refreshLabels()).start();
    }

    private void refreshLabels() {
        pausedCheckBox.setSelected(worldState.isPaused());

        Bait target = worldState.getCurrentTarget();
        if (target == null) {
            targetLabel.setText("Target: (none)");
            scoreLabel.setText("Score: -");
            return;
        }

        targetLabel.setText("Target: (" + target.getX() + "," + target.getY() + ")");
        scoreLabel.setText(String.format("Score: %.1f", worldState.getCurrentTargetScoreValue()));
    }
}
