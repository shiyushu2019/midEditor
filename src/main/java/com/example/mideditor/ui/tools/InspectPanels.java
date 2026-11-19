package com.example.mideditor.ui.tools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.Refreshable;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.GridSettings;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.engine.PlaybackEngine;
import com.example.mideditor.ui.theme.Theme;
import com.example.mideditor.util.Log;

/** 输出 / 诊断 / 属性 / 网格设置 面板。 */
final class InspectPanels {

    private InspectPanels() {
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.SMALL_FONT);
        label.setForeground(new Color(0x5C6875));
        return label;
    }

    /** 输出面板：日志。 */
    static final class OutputPanel extends JPanel implements Refreshable {
        private final JTextArea area = new JTextArea();
        private final Consumer<String> listener = line -> SwingUtilities.invokeLater(() -> appendLine(line));

        OutputPanel() {
            super(new BorderLayout());
            area.setEditable(false);
            area.setFont(Theme.BASE_FONT);
            area.setBackground(new Color(0xFBFCFD));
            for (String line : Log.lines()) {
                area.append(line + "\n");
            }
            Log.addListener(listener);
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            add(scroll, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton clear = new JButton("清空");
            clear.setFont(Theme.SMALL_FONT);
            clear.addActionListener(e -> area.setText(""));
            buttons.add(clear);
            add(buttons, BorderLayout.SOUTH);
        }

        private void appendLine(String line) {
            area.append(line + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        }

        @Override
        public void refresh() {
            // 日志通过监听实时追加
        }
    }

    /** 诊断面板：音频后端、音色库、文档统计。 */
    static final class DiagnosticsPanel extends JPanel implements Refreshable {
        private final AppContext app;
        private final JTextArea area = new JTextArea();

        DiagnosticsPanel(AppContext app) {
            super(new BorderLayout());
            this.app = app;
            area.setEditable(false);
            area.setFont(Theme.BASE_FONT);
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            add(scroll, BorderLayout.CENTER);
        }

        @Override
        public void refresh() {
            StringBuilder text = new StringBuilder();
            text.append("音频后端：").append(app.synth().description()).append('\n');
            text.append("可用状态：").append(app.synth().available() ? "就绪" : "不可用").append('\n');
            Path gm = Path.of("C:\\Windows\\System32\\drivers\\gm.dls");
            text.append("gm.dls：").append(Files.isRegularFile(gm) ? "存在" : "缺失").append('\n');
            Path soundfonts = Path.of("soundfonts");
            if (Files.isDirectory(soundfonts)) {
                try (var stream = Files.list(soundfonts)) {
                    List<String> files = stream.map(p -> p.getFileName().toString()).sorted().toList();
                    text.append("soundfonts：").append(files.isEmpty() ? "（空）" : String.join(", ", files)).append('\n');
                } catch (Exception e) {
                    text.append("soundfonts：读取失败\n");
                }
            } else {
                text.append("soundfonts：目录不存在（可选）\n");
            }

            PlaybackEngine engine = app.playback();
            text.append("音频时钟：").append(app.synth().clockAvailable()
                    ? "可用（采样级时间戳调度）"
                    : "不可用（8ms 精确回退调度）").append('\n');
            text.append("播放状态：").append(engine.state()).append('\n');
            text.append("已发送音符事件：").append(app.synth().noteEventsSent())
                    .append(" · 最后时间戳：").append(app.synth().lastTimestamp()).append('\n');
            if (engine.plan() != null) {
                text.append("调度事件：").append(engine.plan().events().size())
                        .append(" · 结束 tick ").append(engine.plan().endTick()).append('\n');
            }

            MidiDocumentView view = app.activeView();
            if (view == null) {
                text.append("文档：无\n");
            } else {
                var doc = view.document();
                text.append("文档：").append(doc.displayName())
                        .append(doc.dirty() ? "（未保存）" : "（已保存）").append('\n');
                text.append("PPQ：").append(doc.ppq())
                        .append(" · 音轨：").append(doc.tracks().size())
                        .append(" · 平行线：").append(doc.totalNoteCount()).append('\n');
                text.append("撤销：").append(view.editStack().canUndo() ? view.editStack().undoLabel() : "无")
                        .append(" · 重做：").append(view.editStack().canRedo() ? view.editStack().redoLabel() : "无")
                        .append('\n');
                text.append("缩放：").append(Math.round(view.pianoRoll().timeline().zoom() * 100)).append("%")
                        .append(" · 网格：").append(view.grid().resolution().label())
                        .append(" · 活动音轨：")
                        .append(view.activeTrack() == null ? "-" : view.activeTrack().name())
                        .append('\n');
            }
            if (!text.toString().equals(area.getText())) {
                area.setText(text.toString());
            }
        }
    }

    /** 属性面板：选中平行线信息 + 力度调整。 */
    static final class PropertiesPanel extends JPanel implements Refreshable {
        private final AppContext app;
        private final JLabel countLabel = new JLabel("-");
        private final JLabel pitchLabel = new JLabel("-");
        private final JLabel rangeLabel = new JLabel("-");
        private final JLabel velocityLabel = new JLabel("-");
        private final JSpinner velocitySpinner = new JSpinner(new SpinnerNumberModel(100, 1, 127, 1));
        private boolean updating;

        PropertiesPanel(AppContext app) {
            super();
            this.app = app;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

            add(row("选中平行线", countLabel));
            add(row("音高", pitchLabel));
            add(row("时间范围", rangeLabel));
            add(row("力度", velocityLabel));

            JPanel applyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            applyRow.setOpaque(false);
            applyRow.add(caption("设置力度"));
            velocitySpinner.setPreferredSize(new Dimension(70, 22));
            applyRow.add(velocitySpinner);
            JButton apply = new JButton("应用");
            apply.setFont(Theme.SMALL_FONT);
            apply.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null && !view.selection().isEmpty()) {
                    view.setSelectionVelocity(((Number) velocitySpinner.getValue()).intValue());
                }
            });
            applyRow.add(apply);
            add(applyRow);
            add(javax.swing.Box.createVerticalGlue());
        }

        private JPanel row(String title, JLabel value) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            panel.setOpaque(false);
            panel.add(caption(title));
            value.setFont(Theme.BASE_FONT);
            panel.add(value);
            return panel;
        }

        @Override
        public void refresh() {
            MidiDocumentView view = app.activeView();
            updating = true;
            try {
                if (view == null || view.selection().isEmpty()) {
                    countLabel.setText("0");
                    pitchLabel.setText("-");
                    rangeLabel.setText("-");
                    velocityLabel.setText("-");
                    return;
                }
                List<Note> notes = view.selection().resolve(view.document());
                int minPitch = 127;
                int maxPitch = 0;
                int minVelocity = 127;
                int maxVelocity = 0;
                for (Note note : notes) {
                    minPitch = Math.min(minPitch, note.pitch());
                    maxPitch = Math.max(maxPitch, note.pitch());
                    minVelocity = Math.min(minVelocity, note.velocity());
                    maxVelocity = Math.max(maxVelocity, note.velocity());
                }
                countLabel.setText(String.valueOf(notes.size()));
                pitchLabel.setText(minPitch == maxPitch ? String.valueOf(minPitch) : minPitch + " - " + maxPitch);
                rangeLabel.setText("tick " + view.selection().minStartTick(view.document())
                        + " → " + view.selection().maxEndTick(view.document()));
                velocityLabel.setText(minVelocity == maxVelocity
                        ? String.valueOf(minVelocity)
                        : minVelocity + " - " + maxVelocity);
                velocitySpinner.setValue(maxVelocity);
            } finally {
                updating = false;
            }
        }
    }

    /** 网格设置面板。 */
    static final class GridPanel extends JPanel implements Refreshable {
        private final AppContext app;
        private final JComboBox<GridSettings.Resolution> resolutionBox = new JComboBox<>(
                GridSettings.Resolution.values());
        private final JCheckBox visibleBox = new JCheckBox("显示网格");
        private final JCheckBox snapBox = new JCheckBox("吸附到网格");
        private final JCheckBox keyboardRulerBox = new JCheckBox("键盘标尺");
        private final JCheckBox cHighlightBox = new JCheckBox("C 高亮");
        private final JComboBox<GridSettings.NoteLength> noteLengthBox = new JComboBox<>(
                GridSettings.NoteLength.values());
        private final JSpinner customTicksSpinner = new JSpinner(new SpinnerNumberModel(240, 1, 15360, 10));
        private final JSpinner pitchRowsSpinner = new JSpinner(new SpinnerNumberModel(
                Track.VISIBLE_ROWS, Track.MIN_PITCH_ROWS, Track.MAX_PITCH_ROWS, 1));
        private final JCheckBox pitchAllTracksBox = new JCheckBox("应用到全部音轨");
        private final JButton pitchFitButton = new JButton("适配平行线");
        private final JLabel stepLabel = caption("步长：-");
        private boolean updating;

        GridPanel(AppContext app) {
            super();
            this.app = app;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            resolutionBox.setFont(Theme.SMALL_FONT);
            resolutionBox.setToolTipText("网格分辨率");
            visibleBox.setFont(Theme.SMALL_FONT);
            visibleBox.setOpaque(false);
            visibleBox.setToolTipText("显示网格（Ctrl+G）");
            snapBox.setFont(Theme.SMALL_FONT);
            snapBox.setOpaque(false);
            snapBox.setToolTipText("吸附到网格");
            resolutionBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null) {
                    view.grid().setResolution((GridSettings.Resolution) resolutionBox.getSelectedItem());
                    view.pianoRoll().repaint();
                    refresh();
                }
            });
            visibleBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null) {
                    view.grid().setVisible(visibleBox.isSelected());
                    view.pianoRoll().repaint();
                    app.refreshCommandStates();
                }
            });
            snapBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null) {
                    view.grid().setSnapEnabled(snapBox.isSelected());
                }
            });

            keyboardRulerBox.setFont(Theme.SMALL_FONT);
            keyboardRulerBox.setOpaque(false);
            keyboardRulerBox.setToolTipText("键盘标尺（Ctrl+K）");
            keyboardRulerBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null) {
                    view.setKeyboardRulerVisible(keyboardRulerBox.isSelected());
                    app.refreshCommandStates();
                }
            });

            cHighlightBox.setFont(Theme.SMALL_FONT);
            cHighlightBox.setOpaque(false);
            cHighlightBox.setToolTipText("C 高亮（Ctrl+Shift+K）");
            cHighlightBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null && view.cHighlightVisible() != cHighlightBox.isSelected()) {
                    view.toggleCHighlight();
                    app.refreshCommandStates();
                }
            });

            noteLengthBox.setFont(Theme.SMALL_FONT);
            noteLengthBox.setToolTipText("新平行线默认长度");
            noteLengthBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null) {
                    view.grid().setNoteLength((GridSettings.NoteLength) noteLengthBox.getSelectedItem());
                    refresh();
                }
            });
            customTicksSpinner.setFont(Theme.SMALL_FONT);
            customTicksSpinner.setToolTipText("长度（tick）");
            customTicksSpinner.addChangeListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null && view.grid().noteLength() == GridSettings.NoteLength.CUSTOM) {
                    view.grid().setCustomNoteTicks(((Number) customTicksSpinner.getValue()).longValue());
                }
            });

            add(resolutionBox);
            add(visibleBox);
            add(snapBox);
            add(stepLabel);
            add(javax.swing.Box.createVerticalStrut(6));
            add(caption("新建平行线默认长度"));
            add(noteLengthBox);
            JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            customRow.setOpaque(false);
            customRow.add(caption("自定义 tick"));
            customTicksSpinner.setPreferredSize(new Dimension(78, 22));
            customRow.add(customTicksSpinner);
            add(customRow);
            add(javax.swing.Box.createVerticalStrut(6));
            add(keyboardRulerBox);
            add(cHighlightBox);

            add(javax.swing.Box.createVerticalStrut(6));
            add(caption("频率轴（每轨可见半音数）"));
            JPanel pitchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            pitchRow.setOpaque(false);
            pitchRowsSpinner.setFont(Theme.SMALL_FONT);
            pitchRowsSpinner.setPreferredSize(new Dimension(72, 22));
            pitchRowsSpinner.setToolTipText("可见半音数");
            pitchRowsSpinner.addChangeListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view == null) {
                    return;
                }
                int rows = ((Number) pitchRowsSpinner.getValue()).intValue();
                if (pitchAllTracksBox.isSelected()) {
                    view.applyPitchRowsToAllTracks(rows);
                } else {
                    view.setPitchRows(rows);
                }
            });
            pitchRow.add(pitchRowsSpinner);
            add(pitchRow);
            pitchAllTracksBox.setFont(Theme.SMALL_FONT);
            pitchAllTracksBox.setOpaque(false);
            pitchAllTracksBox.setToolTipText("应用到所有音轨");
            pitchAllTracksBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                MidiDocumentView view = app.activeView();
                if (view != null && pitchAllTracksBox.isSelected()) {
                    view.applyPitchRowsToAllTracks(((Number) pitchRowsSpinner.getValue()).intValue());
                }
            });
            add(pitchAllTracksBox);
            pitchFitButton.setFont(Theme.SMALL_FONT);
            pitchFitButton.setToolTipText("适配平行线");
            pitchFitButton.addActionListener(e -> {
                MidiDocumentView view = app.activeView();
                if (view != null) {
                    view.pitchFitToNotes();
                    refresh();
                }
            });
            add(pitchFitButton);
        }

        @Override
        public void refresh() {
            MidiDocumentView view = app.activeView();
            updating = true;
            try {
                boolean enabled = view != null;
                resolutionBox.setEnabled(enabled);
                visibleBox.setEnabled(enabled);
                snapBox.setEnabled(enabled);
                keyboardRulerBox.setEnabled(enabled);
                cHighlightBox.setEnabled(enabled);
                noteLengthBox.setEnabled(enabled);
                customTicksSpinner.setEnabled(enabled);
                pitchRowsSpinner.setEnabled(enabled);
                pitchAllTracksBox.setEnabled(enabled);
                pitchFitButton.setEnabled(enabled);
                if (view != null) {
                    resolutionBox.setSelectedItem(view.grid().resolution());
                    visibleBox.setSelected(view.grid().visible());
                    snapBox.setSelected(view.grid().snapEnabled());
                    keyboardRulerBox.setSelected(view.keyboardRulerVisible());
                    cHighlightBox.setSelected(view.cHighlightVisible());
                    noteLengthBox.setSelectedItem(view.grid().noteLength());
                    customTicksSpinner.setValue(view.grid().customNoteTicks());
                    customTicksSpinner.setEnabled(view.grid().noteLength() == GridSettings.NoteLength.CUSTOM);
                    long step = view.grid().step(view.document().ppq());
                    stepLabel.setText(step > 0 ? "步长：" + step + " ticks" : "步长：关闭");
                    int pitchRows = view.activePitchRows();
                    if (pitchRows > 0) {
                        pitchRowsSpinner.setValue(pitchRows);
                    }
                } else {
                    stepLabel.setText("步长：-");
                }
            } finally {
                updating = false;
            }
        }
    }
}
