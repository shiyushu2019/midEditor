package com.example.mideditor.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 文档私有的撤销/重做栈。禁止跨文档共享（由 DocumentView 各持一份）。
 */
public final class EditStack {

    private final Deque<DocumentEdit> undoStack = new ArrayDeque<>();
    private final Deque<DocumentEdit> redoStack = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final int limit = 500;

    public void push(DocumentEdit edit) {
        if (edit == null) {
            return;
        }
        undoStack.push(edit);
        while (undoStack.size() > limit) {
            undoStack.removeLast();
        }
        redoStack.clear();
        fireChanged();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public String undoLabel() {
        return undoStack.isEmpty() ? "" : undoStack.peek().label();
    }

    public String redoLabel() {
        return redoStack.isEmpty() ? "" : redoStack.peek().label();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        DocumentEdit edit = undoStack.pop();
        edit.undo();
        redoStack.push(edit);
        fireChanged();
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        DocumentEdit edit = redoStack.pop();
        edit.redo();
        undoStack.push(edit);
        fireChanged();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        fireChanged();
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable r : List.copyOf(listeners)) {
            r.run();
        }
    }
}
