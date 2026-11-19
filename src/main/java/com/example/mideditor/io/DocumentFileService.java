package com.example.mideditor.io;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import javax.sound.midi.InvalidMidiDataException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.example.mideditor.document.MidiDocument;

/** 文件选择与读写入口。 */
public final class DocumentFileService {

    private static Path lastDirectory = Path.of(System.getProperty("user.home", "."));

    private DocumentFileService() {
    }

    public static Path chooseOpen(Component parent) {
        JFileChooser chooser = createChooser();
        chooser.setDialogTitle("打开 MIDI 文件");
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            lastDirectory = chooser.getSelectedFile().toPath().getParent();
            return chooser.getSelectedFile().toPath();
        }
        return null;
    }

    public static Path chooseSave(Component parent, String suggestedName) {
        JFileChooser chooser = createChooser();
        chooser.setDialogTitle("保存 MIDI 文件");
        String name = suggestedName == null ? "未命名.mid" : suggestedName;
        if (!name.toLowerCase().endsWith(".mid") && !name.toLowerCase().endsWith(".midi")) {
            name = name + ".mid";
        }
        chooser.setSelectedFile(new java.io.File(lastDirectory.toFile(), name));
        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            Path path = ensureMidiExtension(chooser.getSelectedFile().toPath());
            if (Files.isDirectory(path)) {
                JOptionPane.showMessageDialog(parent,
                        "请指定文件名。", "保存失败", JOptionPane.WARNING_MESSAGE);
                return null;
            }
            if (!ensureOverwriteAllowed(path, () -> confirmOverwrite(parent, path))) {
                return null;
            }
            lastDirectory = path.getParent();
            return path;
        }
        return null;
    }

    /** 补全 .mid 扩展名（返回值只读，保证 lambda 能捕获）。 */
    static Path ensureMidiExtension(Path path) {
        String file = path.getFileName().toString().toLowerCase();
        if (file.matches(".*\\.(mid|midi)$")) {
            return path;
        }
        return path.resolveSibling(path.getFileName() + ".mid");
    }

    /**
     * 覆盖确认：目标不存在直接允许；已存在则交由 confirm 决定。
     * 抽成 Supplier 便于单元测试，真实调用会弹出模态确认框。
     */
    static boolean ensureOverwriteAllowed(Path path, Supplier<Boolean> confirm) {
        if (path == null) {
            return false;
        }
        if (!Files.exists(path)) {
            return true;
        }
        return Boolean.TRUE.equals(confirm.get());
    }

    private static boolean confirmOverwrite(Component parent, Path path) {
        int choice = JOptionPane.showConfirmDialog(parent,
                "“" + path.getFileName() + "”已存在，是否替换？",
                "确认替换",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private static JFileChooser createChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("MIDI 文件 (*.mid, *.midi)", "mid", "midi"));
        if (Files.isDirectory(lastDirectory)) {
            chooser.setCurrentDirectory(lastDirectory.toFile());
        }
        return chooser;
    }

    public static MidiDocument open(Path path) throws IOException, InvalidMidiDataException {
        MidiDocument document = MidiFileCodec.read(path);
        lastDirectory = path.toAbsolutePath().getParent();
        return document;
    }

    public static void save(MidiDocument document, Path path) throws IOException, InvalidMidiDataException {
        MidiFileCodec.write(document, path);
        document.setFilePath(path);
        document.setName(path.getFileName().toString());
        document.setDirty(false);
        lastDirectory = path.toAbsolutePath().getParent();
    }

    public static Path lastDirectory() {
        return lastDirectory;
    }
}
