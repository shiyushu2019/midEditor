package com.example.mideditor.mdi;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JInternalFrame;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.document.MidiDocumentView;

/**
 * MDI 子窗口（文档窗口）：一个文档 = 一个子窗口。
 * 关闭动作统一交给 DocumentManager 处理（未保存提示）。
 */
public final class MdiChildFrame extends JInternalFrame {

    private final DocumentSession session;
    private final AppContext app;

    public MdiChildFrame(DocumentSession session, AppContext app) {
        super(session.title(), true, true, true, true);
        this.session = session;
        this.app = app;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(480, 320));
        MidiDocumentView view = session.view();
        add(view, BorderLayout.CENTER);
        view.addViewListener(event -> {
            if ("title".equals(event.getPropertyName())) {
                setTitle(session.title());
                app.refreshApplicationState();
            }
        });
        addInternalFrameListener(new InternalFrameAdapter() {
            @Override
            public void internalFrameClosing(InternalFrameEvent e) {
                app.documents().requestClose(session);
            }

            @Override
            public void internalFrameActivated(InternalFrameEvent e) {
                app.documents().activateFromFrame(MdiChildFrame.this);
            }

            @Override
            public void internalFrameDeiconified(InternalFrameEvent e) {
                app.refreshApplicationState();
            }

            @Override
            public void internalFrameIconified(InternalFrameEvent e) {
                app.refreshApplicationState();
            }
        });
    }

    public DocumentSession session() {
        return session;
    }
}
