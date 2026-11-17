package com.example.mideditor.command;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.KeyStroke;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.ui.theme.Icons;

/** 文件菜单命令。 */
final class FileCommands {

    private FileCommands() {
    }

    static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    static DocumentSession sessionOf(CommandContext ctx) {
        return ctx.app().documents().sessionOf(ctx.document());
    }

    static void install(AppContext app) {
        var registry = app.registry();

        AppCommand newDoc = new AppCommand(AppCommandIds.FILE_NEW, "新建", CommandScope.APPLICATION,
                key(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.app().documents().newDocument(), ctx -> true, null);
        newDoc.putIcon(Icons.newDoc());
        registry.register(newDoc);

        AppCommand open = new AppCommand(AppCommandIds.FILE_OPEN, "打开...", CommandScope.APPLICATION,
                key(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.app().documents().openDocument(), ctx -> true, null);
        open.putIcon(Icons.open());
        registry.register(open);

        AppCommand save = new AppCommand(AppCommandIds.FILE_SAVE, "保存", CommandScope.DOCUMENT,
                key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.app().documents().save(sessionOf(ctx)), CommandContext::hasDocument, null);
        save.putIcon(Icons.save());
        registry.register(save);

        AppCommand saveAs = new AppCommand(AppCommandIds.FILE_SAVE_AS, "另存为...", CommandScope.DOCUMENT,
                key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.app().documents().saveAs(sessionOf(ctx)), CommandContext::hasDocument, null);
        registry.register(saveAs);
        // Word 习惯：F12 = 另存为
        registry.bindAlias(AppCommandIds.FILE_SAVE_AS, KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0));

        registry.register(new AppCommand(AppCommandIds.FILE_EXPORT, "导出 .mid...", CommandScope.DOCUMENT,
                null, ctx -> ctx.app().documents().exportAs(sessionOf(ctx)),
                CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.FILE_CLOSE, "关闭", CommandScope.DOCUMENT,
                key(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.app().documents().requestClose(sessionOf(ctx)),
                CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.FILE_EXIT, "退出", CommandScope.APPLICATION,
                key(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.app().mainFrame().exitApplication(), ctx -> true, null));
    }
}
