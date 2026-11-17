package com.example.mideditor.document;

import java.nio.file.Path;

import com.example.mideditor.mdi.MdiChildFrame;

/** 文档会话：模型 + 视图 + 子窗口三元组。 */
public final class DocumentSession {

    private final MidiDocument document;
    private final MidiDocumentView view;
    private MdiChildFrame frame;

    public DocumentSession(MidiDocument document, MidiDocumentView view) {
        this.document = document;
        this.view = view;
    }

    public MidiDocument document() {
        return document;
    }

    public MidiDocumentView view() {
        return view;
    }

    public MdiChildFrame frame() {
        return frame;
    }

    public void setFrame(MdiChildFrame frame) {
        this.frame = frame;
    }

    public Path filePath() {
        return document.filePath();
    }

    public String title() {
        return view.title();
    }
}
