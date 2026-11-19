package com.example.mideditor.ui.tools;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.Refreshable;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.document.SimpleEdit;
import com.example.mideditor.domain.GmProgram;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 音轨 / 乐器 / 混音器 面板。 */
final class TrackToolsPanels {

    private TrackToolsPanels() {
    }

    private static JButton button(String text, Runnable action) {
        return button(text, null, action);
    }

    private static JButton button(String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        button.setFont(Theme.SMALL_FONT);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(1, 6, 1, 6));
        if (tooltip != null) {
            button.setToolTipText(tooltip);
        }
        button.addActionListener(e -> action.run());
        return button;
    }

    /** 音轨列表：选择即切换活动音轨。 */
    static final class TracksPanel extends JPanel implements Refreshable {
        private final AppContext app;
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);
        private List<Track> snapshot = List.of();
        private boolean updating;

        TracksPanel(AppContext app) {
            super(new BorderLayout(0, 4));
            this.app = app;
            setOpaque(true);
            list.setFont(Theme.BASE_FONT);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addListSelectionListener(e -> {
                if (updating || e.getValueIsAdjusting()) {
                    return;
                }
                int index = list.getSelectedIndex();
                if (index >= 0 && index < snapshot.size()) {
                    MidiDocumentView view = app.activeView();
                    if (view != null) {
                        view.setActiveTrack(snapshot.get(index));
                    }
                }
            });
            add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
            buttons.add(button("＋", "添加音轨", () -> withView(MidiDocumentView::addTrack)));
            buttons.add(button("－", "删除音轨", () -> withView(v -> v.removeTrack(v.activeTrack()))));
            buttons.add(button("▲", "上移音轨", () -> withView(v -> v.moveTrack(v.activeTrack(), -1))));
            buttons.add(button("▼", "下移音轨", () -> withView(v -> v.moveTrack(v.activeTrack(), 1))));
            add(buttons, BorderLayout.SOUTH);
        }

        private void withView(Consumer<MidiDocumentView> action) {
            MidiDocumentView view = app.activeView();
            if (view != null) {
                action.accept(view);
            }
        }

        @Override
        public void refresh() {
            MidiDocumentView view = app.activeView();
            updating = true;
            try {
                if (view == null) {
                    snapshot = List.of();
                    model.clear();
                    return;
                }
                List<Track> tracks = new ArrayList<>(view.document().tracks());
                boolean same = tracks.size() == snapshot.size();
                if (same) {
                    for (int i = 0; i < tracks.size(); i++) {
                        if (tracks.get(i) != snapshot.get(i)) {
                            same = false;
                            break;
                        }
                    }
                }
                if (!same) {
                    snapshot = List.copyOf(tracks);
                    model.clear();
                    for (Track track : tracks) {
                        model.addElement(track.name() + "  [" + GmProgram.family(track.program()) + "]");
                    }
                }
                int index = snapshot.indexOf(view.activeTrack());
                if (index >= 0 && list.getSelectedIndex() != index) {
                    list.setSelectedIndex(index);
                }
            } finally {
                updating = false;
            }
        }
    }

    /** 乐器面板：GM 乐器族 + 具体音色。 */
    static final class InstrumentsPanel extends JPanel implements Refreshable {
        private final AppContext app;
        private final JComboBox<String> familyBox = new JComboBox<>();
        private final DefaultListModel<String> programModel = new DefaultListModel<>();
        private final JList<String> programList = new JList<>(programModel);
        private boolean updating;
        private Track lastTrack;
        private int lastProgram = -1;

        InstrumentsPanel(AppContext app) {
            super(new BorderLayout(0, 4));
            this.app = app;
            for (String family : GmProgram.familyNames()) {
                familyBox.addItem(family);
            }
            familyBox.setFont(Theme.SMALL_FONT);
            familyBox.addActionListener(e -> {
                if (updating) {
                    return;
                }
                reloadPrograms();
                programList.clearSelection();
            });
            add(familyBox, BorderLayout.NORTH);
            programList.setFont(Theme.SMALL_FONT);
            programList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            programList.addListSelectionListener(e -> {
                if (updating || e.getValueIsAdjusting()) {
                    return;
                }
                applyProgram();
            });
            add(new JScrollPane(programList), BorderLayout.CENTER);
            reloadPrograms();
        }

        private void reloadPrograms() {
            updating = true;
            try {
                programModel.clear();
                int start = Math.max(0, familyBox.getSelectedIndex()) * 8;
                for (int program = start; program < start + 8 && program < 128; program++) {
                    programModel.addElement(String.format("%3d · %s", program, GmProgram.name(program)));
                }
            } finally {
                updating = false;
            }
        }

        private void applyProgram() {
            MidiDocumentView view = app.activeView();
            if (view == null) {
                return;
            }
            Track track = view.activeTrack();
            int index = programList.getSelectedIndex();
            if (track == null || index < 0) {
                return;
            }
            int program = familyBox.getSelectedIndex() * 8 + index;
            int old = track.program();
            if (program == old) {
                return;
            }
            track.setProgram(program);
            view.pushEdit(SimpleEdit.value("选择乐器",
                    () -> track.setProgram(program), () -> track.setProgram(old)));
            view.audioSettingsChanged();
            view.pianoRoll().refreshPitchLabels();
        }

        private void syncSelection() {
            MidiDocumentView view = app.activeView();
            if (view == null || view.activeTrack() == null) {
                return;
            }
            Track track = view.activeTrack();
            updating = true;
            try {
                familyBox.setSelectedIndex(track.program() / 8);
                programList.setSelectedIndex(track.program() % 8);
            } finally {
                updating = false;
            }
        }

        @Override
        public void refresh() {
            MidiDocumentView view = app.activeView();
            Track track = view == null ? null : view.activeTrack();
            if (track == null) {
                return;
            }
            if (track != lastTrack || track.program() != lastProgram) {
                lastTrack = track;
                lastProgram = track.program();
                syncSelection();
            }
        }
    }

    /** 混音器：每个音轨音量 / 声像 / 静音 / 独奏。 */
    static final class MixerPanel extends JPanel implements Refreshable {
        private final AppContext app;
        private final JPanel rows = new JPanel();
        private int renderedTrackCount = -1;
        private final List<Row> rendered = new ArrayList<>();

        MixerPanel(AppContext app) {
            super(new BorderLayout());
            this.app = app;
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            JScrollPane scroll = new JScrollPane(rows);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            add(scroll, BorderLayout.CENTER);
        }

        private MidiDocumentView view() {
            return app.activeView();
        }

        @Override
        public void refresh() {
            MidiDocumentView view = view();
            if (view == null) {
                rows.removeAll();
                rendered.clear();
                renderedTrackCount = -1;
                rows.revalidate();
                return;
            }
            List<Track> tracks = view.document().tracks();
            if (tracks.size() != renderedTrackCount) {
                rebuild(tracks);
            }
            for (int i = 0; i < rendered.size() && i < tracks.size(); i++) {
                rendered.get(i).sync(tracks.get(i));
            }
        }

        private void rebuild(List<Track> tracks) {
            rows.removeAll();
            rendered.clear();
            for (Track track : tracks) {
                Row row = new Row(track);
                rendered.add(row);
                rows.add(row);
                rows.add(Box.createVerticalStrut(2));
            }
            renderedTrackCount = tracks.size();
            rows.revalidate();
            rows.repaint();
        }

        private final class Row extends JPanel {
            private final JLabel name = new JLabel();
            private final JPanel colorDot = new JPanel();
            private final JSlider volume = new JSlider(0, 127);
            private final JSlider pan = new JSlider(0, 127);
            private final JToggleButton mute = new JToggleButton("M");
            private final JToggleButton solo = new JToggleButton("S");
            private boolean updating;
            private Track track;

            Row(Track track) {
                super(new FlowLayout(FlowLayout.LEFT, 4, 1));
                setOpaque(false);
                this.track = track;
                name.setFont(Theme.SMALL_FONT);
                name.setPreferredSize(new Dimension(96, 18));
                colorDot.setPreferredSize(new Dimension(10, 10));
                colorDot.setOpaque(true);
                volume.setPreferredSize(new Dimension(96, 20));
                pan.setPreferredSize(new Dimension(96, 20));
                mute.setFont(Theme.SMALL_FONT);
                mute.setMargin(new java.awt.Insets(0, 2, 0, 2));
                solo.setFont(Theme.SMALL_FONT);
                solo.setMargin(new java.awt.Insets(0, 2, 0, 2));

                volume.addChangeListener(e -> {
                    if (updating || volume.getValueIsAdjusting()) {
                        return;
                    }
                    applyVolume(volume.getValue());
                });
                pan.addChangeListener(e -> {
                    if (updating || pan.getValueIsAdjusting()) {
                        return;
                    }
                    applyPan(pan.getValue());
                });
                mute.addActionListener(e -> {
                    if (!updating) {
                        applyMute(mute.isSelected());
                    }
                });
                solo.addActionListener(e -> {
                    if (!updating) {
                        applySolo(solo.isSelected());
                    }
                });
                add(colorDot);
                add(name);
                add(volume);
                add(pan);
                add(mute);
                add(solo);
                sync(track);
            }

            void sync(Track track) {
                this.track = track;
                updating = true;
                try {
                    name.setText(track.name());
                    name.setToolTipText(GmProgram.name(track.program()));
                    colorDot.setBackground(track.color());
                    volume.setValue(track.volume());
                    pan.setValue(track.pan());
                    mute.setSelected(track.mute());
                    solo.setSelected(track.solo());
                } finally {
                    updating = false;
                }
            }

            private void applyVolume(int value) {
                int old = track.volume();
                track.setVolume(value);
                view().pushEdit(SimpleEdit.value("音轨音量",
                        () -> track.setVolume(value), () -> track.setVolume(old)));
                view().audioSettingsChanged();
            }

            private void applyPan(int value) {
                int old = track.pan();
                track.setPan(value);
                view().pushEdit(SimpleEdit.value("音轨声像",
                        () -> track.setPan(value), () -> track.setPan(old)));
                view().audioSettingsChanged();
            }

            private void applyMute(boolean value) {
                boolean old = track.mute();
                track.setMute(value);
                view().pushEdit(SimpleEdit.value("静音音轨",
                        () -> track.setMute(value), () -> track.setMute(old)));
                view().audioSettingsChanged();
                view().pianoRoll().refreshPitchLabels();
            }

            private void applySolo(boolean value) {
                boolean old = track.solo();
                track.setSolo(value);
                view().pushEdit(SimpleEdit.value("独奏音轨",
                        () -> track.setSolo(value), () -> track.setSolo(old)));
                view().audioSettingsChanged();
                view().pianoRoll().refreshPitchLabels();
            }
        }
    }

    static Color muted(Color color) {
        return color.darker();
    }
}
