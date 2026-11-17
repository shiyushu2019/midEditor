package com.example.mideditor.ui.pianoroll;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.document.SimpleEdit;
import com.example.mideditor.domain.GmProgram;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/** 音轨头：名称 / GM 乐器 / 音量 / 声像 / 静音 / 独奏 / 颜色 / 音域滚动。 */
public final class TrackHeaderRow extends JPanel {

    private final PianoRollView view;
    private final Track track;
    private final JTextField nameField;
    private final JComboBox<String> programBox;
    private final JSlider volumeSlider;
    private final JSlider panSlider;
    private final JToggleButton muteButton;
    private final JToggleButton soloButton;
    private final JButton colorButton;
    private final JLabel rangeLabel;
    private boolean updating;

    public TrackHeaderRow(PianoRollView view, Track track) {
        this.view = view;
        this.track = track;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(0xF7F8FA));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        setPreferredSize(new Dimension(Theme.HEADER_WIDTH, Theme.LANE_HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.LANE_HEIGHT));

        colorButton = new JButton();
        colorButton.setPreferredSize(new Dimension(14, 14));
        colorButton.setToolTipText("音轨颜色");
        colorButton.setBackground(track.color());
        colorButton.setOpaque(true);
        colorButton.addActionListener(e -> chooseColor());

        nameField = new JTextField(track.name());
        nameField.setFont(Theme.BOLD_FONT);
        nameField.setBorder(BorderFactory.createEmptyBorder(1, 3, 1, 3));
        nameField.setPreferredSize(new Dimension(120, 22));
        nameField.setEditable(false);
        nameField.setFocusable(false);
        nameField.setToolTipText("单击切换音轨，双击重命名");
        nameField.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                host().setActiveTrack(track);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    startRename();
                }
            }
        });
        nameField.addActionListener(e -> commitName());
        nameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitName();
            }
        });

        muteButton = smallToggle("M", "静音", track.mute());
        soloButton = smallToggle("S", "独奏", track.solo());
        muteButton.addActionListener(e -> {
            if (updating) {
                return;
            }
            boolean value = muteButton.isSelected();
            boolean old = track.mute();
            track.setMute(value);
            host().pushEdit(SimpleEdit.value("静音音轨", () -> track.setMute(value), () -> track.setMute(old)));
            host().audioSettingsChanged();
        });
        soloButton.addActionListener(e -> {
            if (updating) {
                return;
            }
            boolean value = soloButton.isSelected();
            boolean old = track.solo();
            track.setSolo(value);
            host().pushEdit(SimpleEdit.value("独奏音轨", () -> track.setSolo(value), () -> track.setSolo(old)));
            host().audioSettingsChanged();
            view.refreshPitchLabels();
        });

        JPanel row1 = row();
        row1.add(colorButton);
        row1.add(nameField);
        row1.add(muteButton);
        row1.add(soloButton);

        programBox = new JComboBox<>();
        programBox.setFont(Theme.SMALL_FONT);
        programBox.setMaximumRowCount(20);
        for (int program = 0; program < 128; program++) {
            programBox.addItem(String.format("%3d · %s", program, GmProgram.name(program)));
        }
        programBox.setSelectedIndex(track.program());
        programBox.addActionListener(e -> {
            if (updating) {
                return;
            }
            int program = programBox.getSelectedIndex();
            int old = track.program();
            if (program == old) {
                return;
            }
            track.setProgram(program);
            host().pushEdit(SimpleEdit.value("选择乐器", () -> track.setProgram(program), () -> track.setProgram(old)));
            host().audioSettingsChanged();
        });
        JPanel row2 = row();
        JLabel programLabel = smallLabel("乐器");
        row2.add(programLabel);
        row2.add(programBox);

        volumeSlider = new JSlider(0, 127, track.volume());
        volumeSlider.setPreferredSize(new Dimension(150, 20));
        volumeSlider.addChangeListener(e -> {
            if (updating || volumeSlider.getValueIsAdjusting()) {
                return;
            }
            int value = volumeSlider.getValue();
            int old = track.volume();
            if (value == old) {
                return;
            }
            track.setVolume(value);
            host().pushEdit(SimpleEdit.value("音轨音量", () -> track.setVolume(value), () -> track.setVolume(old)));
            host().audioSettingsChanged();
        });
        JPanel row3 = row();
        row3.add(smallLabel("音量"));
        row3.add(volumeSlider);

        panSlider = new JSlider(0, 127, track.pan());
        panSlider.setPreferredSize(new Dimension(150, 20));
        panSlider.addChangeListener(e -> {
            if (updating || panSlider.getValueIsAdjusting()) {
                return;
            }
            int value = panSlider.getValue();
            int old = track.pan();
            if (value == old) {
                return;
            }
            track.setPan(value);
            host().pushEdit(SimpleEdit.value("音轨声像", () -> track.setPan(value), () -> track.setPan(old)));
            host().audioSettingsChanged();
        });
        JPanel row4 = row();
        row4.add(smallLabel("声像"));
        row4.add(panSlider);

        JButton up = tinyButton("▲", "音域上移一个半音");
        JButton down = tinyButton("▼", "音域下移一个半音");
        JButton zoomIn = tinyButton("＋", "放大音域：显示更少半音、每行更高");
        JButton zoomOut = tinyButton("－", "缩小音域：显示更多半音，最多 128（全音域）");
        up.addActionListener(e -> shiftRange(1));
        down.addActionListener(e -> shiftRange(-1));
        zoomIn.addActionListener(e -> zoomRange(true));
        zoomOut.addActionListener(e -> zoomRange(false));
        rangeLabel = smallLabel("");
        rangeLabel.setToolTipText("当前音轨可见音域");
        JPanel row5 = row();
        row5.add(up);
        row5.add(down);
        row5.add(zoomIn);
        row5.add(zoomOut);
        row5.add(rangeLabel);

        add(row1);
        add(row2);
        add(row3);
        add(row4);
        add(row5);
        add(Box.createVerticalGlue());

        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                host().setActiveTrack(track);
            }
        });
        installSelectionOnClick();
        refreshFromModel();
    }

    /** 除可交互控件外，单击音轨头任意位置都切换为活动音轨（否则用户很难发现要“点空白处”）。 */
    private void installSelectionOnClick() {
        installSelectionFor(this);
    }

    private void installSelectionFor(java.awt.Component component) {
        component.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                host().setActiveTrack(track);
            }
        });
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (child instanceof JButton || child instanceof JToggleButton
                        || child instanceof JSlider || child instanceof JComboBox) {
                    continue;
                }
                if (child instanceof JTextField) {
                    continue;
                }
                installSelectionFor(child);
            }
        }
    }

    private void startRename() {
        if (nameField.isEditable()) {
            return;
        }
        nameField.setFocusable(true);
        nameField.setEditable(true);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private MidiDocumentView host() {
        return view.host();
    }

    private static JPanel row() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private static JLabel smallLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.SMALL_FONT);
        label.setForeground(new Color(0x5C6875));
        return label;
    }

    private static JToggleButton smallToggle(String text, String tooltip, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setFont(Theme.SMALL_FONT);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(0, 2, 0, 2));
        button.setPreferredSize(new Dimension(24, 20));
        return button;
    }

    private static JButton tinyButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFont(Theme.SMALL_FONT);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(0, 2, 0, 2));
        button.setPreferredSize(new Dimension(22, 18));
        return button;
    }

    private void chooseColor() {
        Color chosen = JColorChooser.showDialog(this, "音轨颜色", track.color());
        if (chosen == null) {
            return;
        }
        Color old = track.color();
        track.setColor(chosen);
        host().pushEdit(SimpleEdit.value("音轨颜色", () -> track.setColor(chosen), () -> track.setColor(old)));
        colorButton.setBackground(chosen);
        view.repaint();
    }

    private void commitName() {
        if (updating) {
            return;
        }
        if (!nameField.isEditable()) {
            return;
        }
        nameField.setEditable(false);
        nameField.setFocusable(false);
        String value = nameField.getText().trim();
        if (value.isEmpty() || value.equals(track.name())) {
            nameField.setText(track.name());
            return;
        }
        String old = track.name();
        track.setName(value);
        host().pushEdit(SimpleEdit.value("重命名音轨", () -> track.setName(value), () -> track.setName(old)));
    }

    private void shiftRange(int delta) {
        track.shiftPitchRange(delta);
        refreshFromModel();
        view.repaint();
    }

    /** ＋＝放大（行数减少），－＝缩小（行数增加）。 */
    private void zoomRange(boolean in) {
        view.zoomPitch(track, in);
        refreshFromModel();
        host().app().refreshApplicationState();
    }

    void refreshFromModel() {
        updating = true;
        try {
            nameField.setText(track.name());
            muteButton.setSelected(track.mute());
            soloButton.setSelected(track.solo());
            colorButton.setBackground(track.color());
            programBox.setSelectedIndex(track.program());
            volumeSlider.setValue(track.volume());
            panSlider.setValue(track.pan());
            rangeLabel.setText(track.yRange().low() + "-" + (track.yRange().high() - 1)
                    + " · " + track.pitchRows() + "行");
            rangeLabel.setToolTipText("Ch" + (track.channel() + 1) + " 音域 "
                    + track.yRange().low() + "-" + (track.yRange().high() - 1)
                    + "（共 " + track.pitchRows() + " 个半音）");
            boolean active = host().activeTrack() == track;
            setBackground(active ? new Color(0xB9D2FE) : new Color(0xF7F8FA));
            setBorder(active
                    ? BorderFactory.createMatteBorder(0, 3, 1, 0, Theme.ACCENT)
                    : BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        } finally {
            updating = false;
        }
    }
}
