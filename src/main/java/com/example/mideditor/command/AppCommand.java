package com.example.mideditor.command;

import java.awt.event.ActionEvent;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.KeyStroke;

/**
 * 命令实现。菜单、工具栏、快捷键共用同一个 Action 实例，
 * 因此启用态与行为天然一致，不存在「菜单是摆设」的问题。
 */
public final class AppCommand {

    private final String id;
    private final String label;
    private final CommandScope scope;
    private final Consumer<CommandContext> handler;
    private final Predicate<CommandContext> enabledWhen;
    private final Predicate<CommandContext> checkedWhen;
    private final Action action;
    private Consumer<String> dispatcher = ignored -> {
    };

    public AppCommand(String id, String label, CommandScope scope, KeyStroke accelerator,
            Consumer<CommandContext> handler,
            Predicate<CommandContext> enabledWhen,
            Predicate<CommandContext> checkedWhen) {
        this.id = id;
        this.label = label;
        this.scope = scope;
        this.handler = handler;
        this.enabledWhen = enabledWhen == null ? ctx -> true : enabledWhen;
        this.checkedWhen = checkedWhen == null ? ctx -> false : checkedWhen;
        this.action = new AbstractAction(label) {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispatcher.accept(id);
            }
        };
        if (accelerator != null) {
            action.putValue(Action.ACCELERATOR_KEY, accelerator);
        }
        action.setEnabled(false);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public CommandScope scope() {
        return scope;
    }

    public Action action() {
        return action;
    }

    public KeyStroke accelerator() {
        Object value = action.getValue(Action.ACCELERATOR_KEY);
        return value instanceof KeyStroke keystroke ? keystroke : null;
    }

    public Consumer<CommandContext> handler() {
        return handler;
    }

    public boolean isEnabled(CommandContext context) {
        return enabledWhen.test(context);
    }

    public boolean isChecked(CommandContext context) {
        return checkedWhen.test(context);
    }

    public void bindDispatcher(Consumer<String> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void putIcon(javax.swing.Icon icon) {
        action.putValue(Action.SMALL_ICON, icon);
    }

    public void putShortDescription(String text) {
        action.putValue(Action.SHORT_DESCRIPTION, text);
    }

    public void updateState(boolean enabled, boolean selected) {
        action.setEnabled(enabled);
        action.putValue(Action.SELECTED_KEY, selected);
    }
}
