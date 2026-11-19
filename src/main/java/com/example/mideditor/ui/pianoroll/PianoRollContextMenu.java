package com.example.mideditor.ui.pianoroll;

import java.awt.Point;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.GridSettings;
import com.example.mideditor.ui.theme.Theme;

/** 「平行线」右键菜单。 */
final class PianoRollContextMenu {

    private PianoRollContextMenu() {
    }

    static void show(PianoRollCanvas canvas, HitInfo hit, Point point) {
        MidiDocumentView host = canvas.view().host();
        var registry = host.app().registry();
        JPopupMenu menu = new JPopupMenu();

        if (hit.onNote()) {
            menu.add(registry.action(AppCommandIds.EDIT_CUT));
            menu.add(registry.action(AppCommandIds.EDIT_COPY));
            menu.add(registry.action(AppCommandIds.NOTE_DELETE));
            menu.add(registry.action(AppCommandIds.NOTE_SPLIT));
            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.NOTE_LOCK));
            menu.add(registry.action(AppCommandIds.NOTE_UNLOCK));
            menu.addSeparator();

            JMenu move = new JMenu("移动");
            move.add(registry.action(AppCommandIds.NOTE_MOVE_UP));
            move.add(registry.action(AppCommandIds.NOTE_MOVE_DOWN));
            move.add(registry.action(AppCommandIds.NOTE_MOVE_LEFT));
            move.add(registry.action(AppCommandIds.NOTE_MOVE_RIGHT));
            menu.add(move);

            JMenu length = new JMenu("长度");
            length.add(registry.action(AppCommandIds.NOTE_LENGTHEN));
            length.add(registry.action(AppCommandIds.NOTE_SHORTEN));
            menu.add(length);

            JMenu velocity = new JMenu("力度");
            velocity.add(registry.action(AppCommandIds.EDIT_VELOCITY_UP));
            velocity.add(registry.action(AppCommandIds.EDIT_VELOCITY_DOWN));
            menu.add(velocity);

            menu.add(pitchAxisMenu(registry));

            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.EDIT_QUANTIZE));
        } else {
            menu.add(registry.action(AppCommandIds.NOTE_ADD));
            menu.add(noteLengthMenu(host));
            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.REST_INSERT));
            menu.add(registry.action(AppCommandIds.REST_DELETE));
            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.EDIT_PASTE));
            menu.add(registry.action(AppCommandIds.EDIT_SELECT_ALL));
            menu.addSeparator();
            menu.add(pitchAxisMenu(registry));
        }
        menu.show(canvas, point.x, point.y);
    }

    /** 音域：缩放、适配、显示全部。 */
    private static JMenu pitchAxisMenu(com.example.mideditor.command.CommandRegistry registry) {
        JMenu menu = new JMenu("音域");
        menu.setFont(Theme.BASE_FONT);
        menu.add(registry.action(AppCommandIds.VIEW_PITCH_ZOOM_IN));
        menu.add(registry.action(AppCommandIds.VIEW_PITCH_ZOOM_OUT));
        menu.addSeparator();
        menu.add(registry.action(AppCommandIds.VIEW_PITCH_FIT));
        menu.add(registry.action(AppCommandIds.VIEW_PITCH_SHOW_ALL));
        menu.add(registry.action(AppCommandIds.VIEW_PITCH_ZOOM_RESET));
        return menu;
    }

    /** 新建平行线默认长度（与「网格设置」面板同步）。 */
    private static JMenu noteLengthMenu(MidiDocumentView host) {
        JMenu menu = new JMenu("默认长度");
        menu.setFont(Theme.BASE_FONT);
        ButtonGroup group = new ButtonGroup();
        for (GridSettings.NoteLength length : GridSettings.NoteLength.values()) {
            if (length == GridSettings.NoteLength.CUSTOM) {
                continue;
            }
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(length.label(),
                    host.grid().noteLength() == length);
            item.setFont(Theme.BASE_FONT);
            item.addActionListener(e -> {
                host.grid().setNoteLength(length);
                host.app().refreshApplicationState();
            });
            group.add(item);
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem custom = new JMenuItem("自定义…");
        custom.setFont(Theme.BASE_FONT);
        custom.addActionListener(e -> {
            String input = JOptionPane.showInputDialog(host,
                    "长度（tick）", String.valueOf(host.grid().customNoteTicks()));
            if (input == null) {
                return;
            }
            try {
                long ticks = Long.parseLong(input.trim());
                host.grid().setCustomNoteTicks(ticks);
                host.grid().setNoteLength(GridSettings.NoteLength.CUSTOM);
                host.app().refreshApplicationState();
            } catch (NumberFormatException ignored) {
                // 忽略非法输入
            }
        });
        menu.add(custom);
        return menu;
    }
}
