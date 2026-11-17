package com.example.mideditor.document;

import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.JOptionPane;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.domain.Track;
import com.example.mideditor.domain.TrackPalette;
import com.example.mideditor.io.DocumentFileService;
import com.example.mideditor.mdi.MdiChildFrame;
import com.example.mideditor.util.Log;

/**
 * 文档管理：新建/打开/保存/关闭/退出、活动文档维护。
 */
public final class DocumentManager {

    private final AppContext app;
    private final List<DocumentSession> sessions = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private DocumentSession active;
    private int untitledCounter;
    private boolean detaching;

    public DocumentManager(AppContext app) {
        this.app = app;
    }

    public List<DocumentSession> sessions() {
        return List.copyOf(sessions);
    }

    public int sessionCount() {
        return sessions.size();
    }

    public DocumentSession active() {
        return active;
    }

    public MidiDocumentView activeView() {
        return active == null ? null : active.view();
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public DocumentSession newDocument() {
        MidiDocument document = new MidiDocument(480);
        document.setName("未命名 " + (++untitledCounter));
        Track track = new Track("音轨 1", 0, 0, TrackPalette.at(0));
        document.addTrack(track);
        DocumentSession session = register(document);
        Log.info("新建文档：" + document.displayName());
        return session;
    }

    public void openDocument() {
        Component parent = app.mainFrame();
        Path path = DocumentFileService.chooseOpen(parent);
        if (path != null) {
            openPath(path);
        }
    }

    public DocumentSession openPath(Path path) {
        try {
            DocumentSession session = register(DocumentFileService.open(path));
            Log.info("打开文档：" + path);
            return session;
        } catch (java.io.IOException | javax.sound.midi.InvalidMidiDataException e) {
            Log.error("打开失败：" + e.getMessage());
            JOptionPane.showMessageDialog(app.mainFrame(),
                    "无法打开文件：\n" + path + "\n\n" + e.getMessage(),
                    "打开失败", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private DocumentSession register(MidiDocument document) {
        MidiDocumentView view = new MidiDocumentView(app, document);
        DocumentSession session = new DocumentSession(document, view);
        MdiChildFrame frame = app.mainFrame().workspace().addDocument(session);
        session.setFrame(frame);
        sessions.add(session);
        activate(session);
        app.refreshApplicationState();
        return session;
    }

    public void activate(DocumentSession session) {
        if (active == session) {
            app.refreshApplicationState();
            return;
        }
        active = session;
        if (session != null) {
            session.view().onActivated();
            if (session.frame() != null && !session.frame().isSelected()) {
                try {
                    session.frame().setSelected(true);
                } catch (java.beans.PropertyVetoException ignored) {
                    // 选中被否决时忽略
                }
            }
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
        app.refreshApplicationState();
    }

    public void activateFromFrame(MdiChildFrame frame) {
        DocumentSession session = sessionOfFrame(frame);
        if (session != null) {
            activate(session);
        }
    }

    public DocumentSession sessionOf(MidiDocumentView view) {
        for (DocumentSession session : sessions) {
            if (session.view() == view) {
                return session;
            }
        }
        return null;
    }

    public DocumentSession sessionOfFrame(MdiChildFrame frame) {
        for (DocumentSession session : sessions) {
            if (session.frame() == frame) {
                return session;
            }
        }
        return null;
    }

    public boolean save(DocumentSession session) {
        return saveInternal(session, false);
    }

    public boolean saveAs(DocumentSession session) {
        return saveInternal(session, true);
    }

    private boolean saveInternal(DocumentSession session, boolean forceChooser) {
        if (session == null) {
            return false;
        }
        Path path = session.document().filePath();
        if (forceChooser || path == null) {
            path = DocumentFileService.chooseSave(app.mainFrame(), session.document().displayName());
            if (path == null) {
                return false;
            }
        }
        try {
            DocumentFileService.save(session.document(), path);
            session.view().markSaved();
            Log.info("已保存：" + path);
            app.refreshApplicationState();
            return true;
        } catch (java.io.IOException | javax.sound.midi.InvalidMidiDataException e) {
            Log.error("保存失败：" + e.getMessage());
            JOptionPane.showMessageDialog(app.mainFrame(),
                    "保存失败：\n" + e.getMessage(), "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    public void exportAs(DocumentSession session) {
        if (session == null) {
            return;
        }
        Path path = DocumentFileService.chooseSave(app.mainFrame(),
                session.document().displayName().replace(".mid", "") + "-export.mid");
        if (path == null) {
            return;
        }
        try {
            com.example.mideditor.io.MidiFileCodec.write(session.document(), path);
            Log.info("已导出：" + path);
        } catch (java.io.IOException | javax.sound.midi.InvalidMidiDataException e) {
            Log.error("导出失败：" + e.getMessage());
            JOptionPane.showMessageDialog(app.mainFrame(),
                    "导出失败：\n" + e.getMessage(), "导出失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void requestClose(DocumentSession session) {
        if (session == null || !sessions.contains(session)) {
            return;
        }
        if (confirmDiscard(session)) {
            detach(session);
        }
    }

    /** 退出前检查所有未保存文档；返回 false 表示用户取消。 */
    public boolean requestExit() {
        for (DocumentSession session : List.copyOf(sessions)) {
            if (!confirmDiscard(session)) {
                return false;
            }
        }
        detaching = true;
        try {
            for (DocumentSession session : List.copyOf(sessions)) {
                detach(session);
            }
        } finally {
            detaching = false;
        }
        return true;
    }

    private void detach(DocumentSession session) {
        boolean wasActive = active == session;
        sessions.remove(session);
        session.view().disposeView();
        if (session.frame() != null) {
            session.frame().dispose();
        }
        if (wasActive) {
            active = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
            if (active != null && active.frame() != null && !active.frame().isIcon()) {
                try {
                    active.frame().setSelected(true);
                } catch (java.beans.PropertyVetoException ignored) {
                    // 选中被否决时忽略
                }
            }
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
        app.refreshApplicationState();
    }

    private boolean confirmDiscard(DocumentSession session) {
        if (!session.view().isDirty()) {
            return true;
        }
        Object[] options = { "保存", "不保存", "取消" };
        int choice = JOptionPane.showOptionDialog(app.mainFrame(),
                "文档「" + session.document().displayName() + "」有未保存的修改。是否保存？",
                "未保存的更改",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            return save(session);
        }
        return choice == 1;
    }

    public boolean isDetaching() {
        return detaching;
    }
}
