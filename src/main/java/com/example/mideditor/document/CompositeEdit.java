package com.example.mideditor.document;

import java.util.ArrayList;
import java.util.List;

/** 一次操作影响多条音轨时使用（例如跨轨批量移动）。 */
public final class CompositeEdit implements DocumentEdit {

    private final String label;
    private final List<DocumentEdit> edits = new ArrayList<>();

    public CompositeEdit(String label) {
        this.label = label;
    }

    public CompositeEdit add(DocumentEdit edit) {
        edits.add(edit);
        return this;
    }

    public int size() {
        return edits.size();
    }

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void undo() {
        for (int i = edits.size() - 1; i >= 0; i--) {
            edits.get(i).undo();
        }
    }

    @Override
    public void redo() {
        for (DocumentEdit edit : edits) {
            edit.redo();
        }
    }
}
