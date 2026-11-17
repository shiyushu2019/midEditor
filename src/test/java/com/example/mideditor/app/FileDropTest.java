package com.example.mideditor.app;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.io.DocumentFileService;

/** 拖拽 .mid 文件到窗口打开。 */
class FileDropTest {

    @Test
    void midiFilesFilterAcceptsOnlyMidiFiles() throws Exception {
        Path dir = Files.createTempDirectory("mideditor-drop");
        try {
            Path mid = Files.createFile(dir.resolve("song.mid"));
            Path upper = Files.createFile(dir.resolve("SONG2.MIDI"));
            Path text = Files.createFile(dir.resolve("notes.txt"));
            Path folder = Files.createDirectory(dir.resolve("fake.mid"));

            List<Path> accepted = FileDropHandler.midiFiles(List.of(
                    mid.toFile(), upper.toFile(), text.toFile(), folder.toFile()));
            assertEquals(List.of(mid, upper), accepted, "只接受 .mid/.midi 文件，忽略目录与其它扩展名");

            assertTrue(FileDropHandler.midiFiles(null).isEmpty());
            assertTrue(FileDropHandler.midiFiles(List.of()).isEmpty());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void droppingMidiFileOpensADocument() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        Path file = Files.createTempFile("mideditor-drop", ".mid");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
            });
            DocumentSession session = app.documents().newDocument();
            MidiDocumentView view = session.view();
            SwingUtilities.invokeAndWait(() -> view.addNoteAt(view.activeTrack(), 0, 60));
            DocumentFileService.save(view.document(), file);

            int before = app.documents().sessionCount();
            FileDropHandler handler = new FileDropHandler(app);
            int[] opened = new int[1];
            SwingUtilities.invokeAndWait(() -> opened[0] = handler.openFiles(List.of(file.toFile())));

            assertEquals(1, opened[0], "拖入一个 .mid 应打开一个文档");
            assertEquals(before + 1, app.documents().sessionCount());
            assertTrue(app.documents().active() != null);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                if (holder[0] != null) {
                    holder[0].dispose();
                }
                app.shutdown();
            });
            Files.deleteIfExists(file);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 清理失败不影响测试结论
                }
            });
        }
    }
}
