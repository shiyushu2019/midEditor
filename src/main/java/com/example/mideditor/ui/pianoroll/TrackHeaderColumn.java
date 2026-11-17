package com.example.mideditor.ui.pianoroll;

import java.awt.Component;

import javax.swing.BoxLayout;
import javax.swing.JPanel;

import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/** 音轨头列表（行头）。 */
public final class TrackHeaderColumn extends JPanel {

    private final PianoRollView view;

    public TrackHeaderColumn(PianoRollView view) {
        this.view = view;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Theme.MENU_BACKGROUND);
        setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));
    }

    public void rebuild() {
        removeAll();
        for (Track track : view.host().document().tracks()) {
            TrackHeaderRow row = new TrackHeaderRow(view, track);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(row);
        }
        revalidate();
        repaint();
    }

    void refreshRows() {
        for (Component component : getComponents()) {
            if (component instanceof TrackHeaderRow row) {
                row.refreshFromModel();
            }
        }
    }
}
