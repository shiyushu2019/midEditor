package com.example.mideditor.command;

import com.example.mideditor.app.AppContext;

/** 统一安装全部命令。 */
public final class CommandInstaller {

    private CommandInstaller() {
    }

    public static void install(AppContext app) {
        FileCommands.install(app);
        EditCommands.install(app);
        PlaybackCommands.install(app);
        ViewCommands.install(app);
    }
}
