package com.example.mideditor.ui.transport;

import java.awt.FlowLayout;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.GridSettings;
import com.example.mideditor.ui.frame.StatusBar;
import com.example.mideditor.ui.theme.Icons;
import com.example.mideditor.ui.theme.Theme;

/** 子窗口底部传输条：播放控制 / BPM / 位置 / 网格 / 缩放。 */
public final class TransportBar extends JPanel {

    private final MidiDocumentView host;
    private final JButton playButton;
    private final JLabel positionLabel = new JLabel();
    private final JSpinner bpmSpinner;
    private final JComboBox<GridSettings.Resolution> gridBox;
    private final JSlider zoomSlider;
    private boolean updating;

    public TransportBar(MidiDocumentView host) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        this.host = host;
        setBackground(Theme.MENU_BACKGROUND);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        var registry = host.app().registry();
        playButton = actionButton(registry.action(AppCommandIds.PLAY_PLAY_PAUSE), Icons.play(), "播放 / 暂停（空格）");
        add(playButton);
        add(actionButton(registry.action(AppCommandIds.PLAY_STOP), Icons.stop(), "停止"));
        add(actionButton(registry.action(AppCommandIds.PLAY_REWIND), Icons.rewind(), "回到开头"));
        add(toggleButton(registry.action(AppCommandIds.PLAY_TOGGLE_LOOP), Icons.loop(), "循环播放"));
        add(toggleButton(registry.action(AppCommandIds.PLAY_TOGGLE_METRONOME), Icons.metronome(), "节拍器"));

        add(separator());
        positionLabel.setFont(Theme.MONO_FONT);
        positionLabel.setText("tick 0 · 00:00.000");
        positionLabel.setPreferredSize(new java.awt.Dimension(186, 22));
        add(positionLabel);

        add(separator());
        add(label("BPM"));
        bpmSpinner = new JSpinner(new SpinnerNumberModel(120, 20, 300, 1));
        bpmSpinner.setFont(Theme.BASE_FONT);
        bpmSpinner.setPreferredSize(new java.awt.Dimension(64, 24));
        bpmSpinner.addChangeListener(e -> {
            if (updating) {
                return;
            }
            host.setTempo(((Number) bpmSpinner.getValue()).doubleValue());
        });
        add(bpmSpinner);

        add(separator());
        add(label("网格"));
        gridBox = new JComboBox<>(GridSettings.Resolution.values());
        gridBox.setFont(Theme.SMALL_FONT);
        gridBox.setSelectedItem(host.grid().resolution());
        gridBox.addActionListener(e -> {
            if (updating) {
                return;
            }
            host.grid().setResolution((GridSettings.Resolution) gridBox.getSelectedItem());
            host.pianoRoll().repaint();
            host.app().refreshCommandStates();
        });
        add(gridBox);

        add(separator());
        add(label("缩放"));
        zoomSlider = new JSlider(25, 800, 100);
        zoomSlider.setPreferredSize(new java.awt.Dimension(120, 22));
        zoomSlider.addChangeListener(e -> {
            if (updating) {
                return;
            }
            host.pianoRoll().timeline().setZoom(zoomSlider.getValue() / 100.0);
            host.app().refreshCommandStates();
        });
        add(zoomSlider);

        refresh();
    }

    private static JButton actionButton(Action action, javax.swing.Icon icon, String tooltip) {
        JButton button = new JButton(action);
        button.setHideActionText(true);
        if (icon != null) {
            button.setIcon(icon);
        }
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(2, 6, 2, 6));
        return button;
    }

    private static JToggleButton toggleButton(Action action, javax.swing.Icon icon, String tooltip) {
        JToggleButton button = new JToggleButton(action);
        button.setHideActionText(true);
        if (icon != null) {
            button.setIcon(icon);
        }
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(2, 6, 2, 6));
        return button;
    }

    private static JLabel separator() {
        JLabel label = new JLabel("│");
        label.setForeground(Theme.BORDER);
        return label;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.SMALL_FONT);
        label.setForeground(new java.awt.Color(0x5C6875));
        return label;
    }

    /** 由文档视图统一刷新（播放状态、位置、速度、缩放）。 */
    public void refresh() {
        updating = true;
        try {
            boolean playing = host.app().playback().isPlaying() && host.app().playback().owner() == host;
            playButton.setIcon(playing ? Icons.pause() : Icons.play());
            long tick = host.playheadTick();
            long micros = host.document().tempoMap().microsAtTick(tick);
            positionLabel.setText("tick " + tick + " · " + StatusBar.formatMicros(micros));
            bpmSpinner.setValue((int) Math.round(host.document().tempoMap().bpmAt(0)));
            gridBox.setSelectedItem(host.grid().resolution());
            zoomSlider.setValue((int) Math.round(host.pianoRoll().timeline().zoom() * 100));
        } finally {
            updating = false;
        }
    }
}
