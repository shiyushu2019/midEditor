package com.example.mideditor.io;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.midi.InvalidMidiDataException;
import javax.swing.JFileChooser;
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
            Path path = chooser.getSelectedFile().toPath();
            lastDirectory = path.getParent();
            if (!path.getFileName().toString().toLowerCase().matches(".*\\.(mid|midi)$")) {
                path = path.resolveSibling(path.getFileName() + ".mid");
            }
            return path;
        }
        return null;
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
