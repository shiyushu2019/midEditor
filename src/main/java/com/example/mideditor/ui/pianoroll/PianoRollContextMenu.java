package com.example.mideditor.ui.pianoroll;

import java.awt.Point;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.document.MidiDocumentView;

/** 「平行线」右键菜单。 */
final class PianoRollContextMenu {

    private PianoRollContextMenu() {
    }

    static void show(PianoRollCanvas canvas, HitInfo hit, Point point) {
        MidiDocumentView host = canvas.view().host();
        var registry = host.app().registry();
        JPopupMenu menu = new JPopupMenu();

        if (hit.onNote()) {
            JMenuItem title = new JMenuItem("平行线");
            title.setEnabled(false);
            menu.add(title);
            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.NOTE_DELETE));
            menu.add(registry.action(AppCommandIds.NOTE_SPLIT));

            JMenu move = new JMenu("移动平行线");
            move.add(registry.action(AppCommandIds.NOTE_MOVE_UP));
            move.add(registry.action(AppCommandIds.NOTE_MOVE_DOWN));
            move.add(registry.action(AppCommandIds.NOTE_MOVE_LEFT));
            move.add(registry.action(AppCommandIds.NOTE_MOVE_RIGHT));
            menu.add(move);

            JMenu length = new JMenu("平行线长度");
            length.add(registry.action(AppCommandIds.NOTE_LENGTHEN));
            length.add(registry.action(AppCommandIds.NOTE_SHORTEN));
            menu.add(length);

            JMenu velocity = new JMenu("平行线力度（粗细）");
            velocity.add(registry.action(AppCommandIds.EDIT_VELOCITY_UP));
            velocity.add(registry.action(AppCommandIds.EDIT_VELOCITY_DOWN));
            menu.add(velocity);

            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.EDIT_CUT));
            menu.add(registry.action(AppCommandIds.EDIT_COPY));
            menu.add(registry.action(AppCommandIds.EDIT_QUANTIZE));
        } else {
            JMenuItem title = new JMenuItem("坐标空白处");
            title.setEnabled(false);
            menu.add(title);
            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.NOTE_ADD));
            menu.add(registry.action(AppCommandIds.REST_INSERT));
            menu.add(registry.action(AppCommandIds.REST_DELETE));
            menu.addSeparator();
            menu.add(registry.action(AppCommandIds.EDIT_PASTE));
            menu.add(registry.action(AppCommandIds.EDIT_SELECT_ALL));
        }
        menu.show(canvas, point.x, point.y);
    }
}
