package com.example.mideditor.command;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.example.mideditor.util.Log;

/**
 * 命令路由：活动文档/音轨/选中的状态由上下文提供器生成，
 * 分发后统一刷新所有 Action 的启用态与勾选态。
 */
public final class CommandRouter {

    private final CommandRegistry registry;
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private Supplier<CommandContext> contextSupplier = () -> null;

    public CommandRouter(CommandRegistry registry) {
        this.registry = registry;
        registry.setRouter(this);
    }

    public void setContextSupplier(Supplier<CommandContext> supplier) {
        this.contextSupplier = supplier == null ? () -> null : supplier;
    }

    public CommandContext context() {
        return contextSupplier.get();
    }

    public void dispatch(String id) {
        dispatch(id, contextSupplier.get());
    }

    public void dispatch(String id, CommandContext context) {
        AppCommand command = registry.get(id);
        if (command == null) {
            return;
        }
        if (context == null || !command.isEnabled(context)) {
            return;
        }
        try {
            command.handler().accept(context);
        } catch (Throwable t) {
            // 包含 Error（例如 ECJ 生成的 Unresolved compilation problems），必须暴露而不是静默失败
            Log.error("命令 " + id + " 执行失败：" + t);
        }
        refreshStates();
    }

    /** 重新计算所有命令的可用/勾选状态。 */
    public void refreshStates() {
        CommandContext context = contextSupplier.get();
        if (context == null) {
            return;
        }
        for (AppCommand command : registry.all()) {
            boolean enabled;
            boolean checked;
            try {
                enabled = command.isEnabled(context);
                checked = command.isChecked(context);
            } catch (Exception e) {
                enabled = false;
                checked = false;
            }
            command.updateState(enabled, checked);
        }
        for (Runnable listener : stateListeners) {
            listener.run();
        }
    }

    public void addStateListener(Runnable listener) {
        stateListeners.add(listener);
    }
}
