package com.example.mideditor.document;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

import javax.swing.JPanel;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.domain.Track;

/**
 * 文档视图基类：一个视图对应一个文档，持有独立撤销栈、选区、脏状态。
 */
public abstract class DocumentView extends JPanel {

    private final AppContext app;
    private final MidiDocument document;
    private final EditStack editStack = new EditStack();
    private final DocumentSelection selection = new DocumentSelection();
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);

    protected DocumentView(AppContext app, MidiDocument document) {
        super(new java.awt.BorderLayout());
        this.app = app;
        this.document = document;
        editStack.addChangeListener(() -> {
            if (!document.dirty()) {
                document.setDirty(true);
            }
            changes.firePropertyChange("dirty", null, true);
            changes.firePropertyChange("title", null, title());
        });
        selection.addListener(() -> changes.firePropertyChange("selection", null, selection.size()));
    }

    public AppContext app() {
        return app;
    }

    public MidiDocument document() {
        return document;
    }

    public EditStack editStack() {
        return editStack;
    }

    public DocumentSelection selection() {
        return selection;
    }

    public boolean isDirty() {
        return document.dirty();
    }

    public void markSaved() {
        document.setDirty(false);
        changes.firePropertyChange("dirty", null, false);
        changes.firePropertyChange("title", null, title());
    }

    public String title() {
        return document.displayName() + (document.dirty() ? " *" : "");
    }

    public void addViewListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    public void removeViewListener(PropertyChangeListener listener) {
        changes.removePropertyChangeListener(listener);
    }

    protected void fireTitleChanged() {
        changes.firePropertyChange("title", null, title());
    }

    public abstract Track activeTrack();

    public abstract void setActiveTrack(Track track);

    public abstract void onActivated();

    public abstract void stopPlayback();

    public void disposeView() {
        stopPlayback();
    }
}
