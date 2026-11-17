package com.example.mideditor.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/** 命令注册表：负责 Action 复用、快捷键映射与分发入口。 */
public final class CommandRegistry {

    private final Map<String, AppCommand> commands = new LinkedHashMap<>();
    private final InputMap inputMap;
    private final ActionMap actionMap;
    private CommandRouter router;

    public CommandRegistry(JComponent keyTarget) {
        this.inputMap = keyTarget.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        this.actionMap = keyTarget.getActionMap();
    }

    public void register(AppCommand command) {
        commands.put(command.id(), command);
        actionMap.put(command.id(), command.action());
        KeyStroke accelerator = command.accelerator();
        if (accelerator != null) {
            inputMap.put(accelerator, command.id());
        }
        command.bindDispatcher(this::dispatch);
    }

    public void registerAll(AppCommand... items) {
        for (AppCommand item : items) {
            register(item);
        }
    }

    void setRouter(CommandRouter router) {
        this.router = router;
    }

    private void dispatch(String id) {
        if (router != null) {
            router.dispatch(id);
        }
    }

    public AppCommand get(String id) {
        return commands.get(id);
    }

    public Action action(String id) {
        AppCommand command = commands.get(id);
        return command == null ? null : command.action();
    }

    public Collection<AppCommand> all() {
        return commands.values();
    }
}
