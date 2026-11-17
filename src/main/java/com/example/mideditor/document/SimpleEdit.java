package com.example.mideditor.document;

/** 用 Runnable 表达的通用编辑（音轨属性、BPM 等）。 */
public final class SimpleEdit implements DocumentEdit {

    private final String label;
    private final Runnable redo;
    private final Runnable undo;

    public SimpleEdit(String label, Runnable redo, Runnable undo) {
        this.label = label;
        this.redo = redo;
        this.undo = undo;
    }

    public static SimpleEdit value(String label, Runnable apply, Runnable revert) {
        return new SimpleEdit(label, apply, revert);
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void undo() {
        undo.run();
    }

    @Override
    public void redo() {
        redo.run();
    }
}
