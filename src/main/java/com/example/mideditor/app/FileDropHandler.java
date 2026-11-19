package com.example.mideditor.app;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JOptionPane;
import javax.swing.TransferHandler;

import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.util.Log;

/**
 * 把 .mid / .midi 文件拖进窗口即可打开（可一次拖多个）。
 * 其它类型的文件会被忽略并提示，不会打断已打开的文档。
 */
final class FileDropHandler extends TransferHandler {

    private static final String[] MIDI_EXTENSIONS = { ".mid", ".midi", ".kar", ".rmi" };

    private final AppContext app;

    FileDropHandler(AppContext app) {
        this.app = app;
    }

    /** 只有拖进来的是文件列表才接受。 */
    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        List<File> files = new ArrayList<>();
        try {
            Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            if (data instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof File file) {
                        files.add(file);
                    }
                }
            }
        } catch (java.io.IOException | java.awt.datatransfer.UnsupportedFlavorException e) {
            Log.error("拖拽内容解析失败：" + e);
            return false;
        }
        return openFiles(files) > 0;
    }

    /**
     * 打开拖入的文件，返回成功打开的文档数。
     * 非 .mid 文件会被忽略并给出一次提示。
     */
    int openFiles(List<File> candidates) {
        List<Path> midi = midiFiles(candidates);
        if (midi.isEmpty()) {
            if (candidates != null && !candidates.isEmpty()) {
                JOptionPane.showMessageDialog(app.mainFrame(),
                        "请拖入 MIDI 文件（" + String.join(" / ", MIDI_EXTENSIONS) + "）。",
                        "无法打开", JOptionPane.WARNING_MESSAGE);
            }
            return 0;
        }
        int opened = 0;
        for (Path path : midi) {
            DocumentSession session = app.documents().openPath(path);
            if (session != null) {
                opened++;
            }
        }
        if (opened > 0) {
            app.mainFrame().toFront();
            Log.info("拖拽打开 " + opened + " 个文件");
        }
        return opened;
    }

    /** 过滤出可打开的 MIDI 文件（忽略目录与其它扩展名）。 */
    static List<Path> midiFiles(List<File> candidates) {
        List<Path> result = new ArrayList<>();
        if (candidates == null) {
            return result;
        }
        for (File file : candidates) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            for (String extension : MIDI_EXTENSIONS) {
                if (name.endsWith(extension)) {
                    result.add(file.toPath());
                    break;
                }
            }
        }
        return result;
    }
}
