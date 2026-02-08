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
 * Provides a minimal control panel UI for pausing and monitoring the bot.
 *
 * <p>This panel displays:</p>
 * <ul>
 *     <li>A checkbox to pause/resume the bot's decision-making</li>
 *     <li>The coordinates of the current target bait</li>
 *     <li>The calculated score value for the current target</li>
 * </ul>
 *
 * <p>The panel automatically refreshes at 250ms intervals to reflect the current state
 * without requiring explicit event notification from the strategy.</p>
 */
public final class BotControlPanel extends JPanel {

    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 120;
    private static final int REFRESH_INTERVAL_MS = 250;
    private static final String PANEL_TITLE = "Tarnished Bot";

    private final WorldState worldState;

    private JCheckBox pausedCheckBox;
    private JLabel targetLabel;
    private JLabel scoreLabel;

    /**
     * Constructs a new bot control panel bound to the given world state.
     *
     * @param worldState the world state to monitor and control
     */
    public BotControlPanel(WorldState worldState) {
        this.worldState = worldState;

        initializeLayout();
        initializeComponents();
        startRefreshTimer();
    }

    /**
     * Configures the panel's layout and visual properties.
     */
    private void initializeLayout() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(PANEL_TITLE));
        setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
    }

    /**
     * Creates and adds UI components to the panel.
     */
    private void initializeComponents() {
        pausedCheckBox = new JCheckBox("Paused");
        pausedCheckBox.addActionListener(e -> worldState.setPaused(pausedCheckBox.isSelected()));

        targetLabel = new JLabel("Target: (none)");
        scoreLabel = new JLabel("Score: -");

        add(pausedCheckBox);
        add(targetLabel);
        add(scoreLabel);
    }

    /**
     * Starts a periodic timer to refresh the displayed information.
     */
    private void startRefreshTimer() {
        new Timer(REFRESH_INTERVAL_MS, e -> refreshLabels()).start();
    }

    /**
     * Updates the displayed labels to reflect the current world state.
     *
     * <p>This method is called periodically by the refresh timer. It updates the pause
     * checkbox state, target coordinates, and score value based on the current world state.</p>
     */
    private void refreshLabels() {
        pausedCheckBox.setSelected(worldState.isPaused());

        Bait currentTarget = worldState.getCurrentTarget();
        if (currentTarget == null) {
            targetLabel.setText("Target: (none)");
            scoreLabel.setText("Score: -");
            return;
        }

        targetLabel.setText(String.format("Target: (%d,%d)", currentTarget.getX(), currentTarget.getY()));
        scoreLabel.setText(String.format("Score: %.1f", worldState.getCurrentTargetScoreValue()));
    }
}