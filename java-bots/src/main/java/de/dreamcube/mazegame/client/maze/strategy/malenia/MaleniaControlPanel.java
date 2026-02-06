package de.dreamcube.mazegame.client.maze.strategy.malenia;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple control panel for pausing and resuming the Malenia bot.
 */
public final class MaleniaControlPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final AtomicBoolean paused;
    private final JButton pauseButton;

    public MaleniaControlPanel(AtomicBoolean paused) {
        this.paused = paused;
        this.pauseButton = new JButton(labelFor(paused.get()));
        this.pauseButton.addActionListener(event -> {
            this.paused.set(!this.paused.get());
            this.pauseButton.setText(labelFor(this.paused.get()));
        });
        add(this.pauseButton);
    }

    private static String labelFor(boolean paused) {
        return paused ? "Resume" : "Pause";
    }
}
