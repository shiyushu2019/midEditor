package com.example.mideditor.ui.frame;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.ui.theme.Theme;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JToolBar;

/** 工具栏：与菜单、快捷键共享同一套命令 Action。 */
public final class ToolBarBuilder {

    private final AppContext app;

    public ToolBarBuilder(AppContext app) {
        this.app = app;
    }

    public JToolBar build() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.setFont(Theme.BASE_FONT);
        bar.setBackground(Theme.MENU_BACKGROUND);
        bar.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        add(bar, AppCommandIds.FILE_NEW);
        add(bar, AppCommandIds.FILE_OPEN);
        add(bar, AppCommandIds.FILE_SAVE);
        bar.addSeparator();
        add(bar, AppCommandIds.EDIT_UNDO);
        add(bar, AppCommandIds.EDIT_REDO);
        bar.addSeparator();
        add(bar, AppCommandIds.EDIT_CUT);
        add(bar, AppCommandIds.EDIT_COPY);
        add(bar, AppCommandIds.EDIT_PASTE);
        bar.addSeparator();
        add(bar, AppCommandIds.PLAY_PLAY_PAUSE);
        add(bar, AppCommandIds.PLAY_STOP);
        add(bar, AppCommandIds.PLAY_REWIND);
        add(bar, AppCommandIds.PLAY_TOGGLE_LOOP);
        add(bar, AppCommandIds.PLAY_TOGGLE_METRONOME);
        bar.addSeparator();
        add(bar, AppCommandIds.TRACK_ADD);
        add(bar, AppCommandIds.EDIT_QUANTIZE);
        return bar;
    }

    private void add(JToolBar bar, String commandId) {
        Action action = app.registry().action(commandId);
        if (action == null) {
            return;
        }
        JButton button = new JButton(action);
        button.setHideActionText(true);
        button.setFocusable(false);
        Object label = action.getValue(Action.NAME);
        button.setToolTipText(label == null ? commandId : label.toString());
        button.setMargin(new java.awt.Insets(2, 6, 2, 6));
        bar.add(button);
    }
}
