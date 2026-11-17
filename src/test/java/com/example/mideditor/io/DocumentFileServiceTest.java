package com.example.mideditor.io;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentFileServiceTest {

    @Test
    void existingFileRequiresConfirmation(@TempDir Path dir) throws Exception {
        Path existing = Files.writeString(dir.resolve("existing.mid"), "x");
        assertFalse(DocumentFileService.ensureOverwriteAllowed(existing, () -> false),
                "用户选择“否”时不得覆盖");
        assertTrue(DocumentFileService.ensureOverwriteAllowed(existing, () -> true),
                "用户确认后才允许覆盖");
    }

    @Test
    void newFileNeverAsks(@TempDir Path dir) {
        Path missing = dir.resolve("new.mid");
        assertTrue(DocumentFileService.ensureOverwriteAllowed(missing, () -> {
            throw new AssertionError("不存在的文件不应询问覆盖");
        }));
    }

    @Test
    void midiExtensionIsAppendedWhenMissing(@TempDir Path dir) {
        assertEquals(dir.resolve("a.mid"), DocumentFileService.ensureMidiExtension(dir.resolve("a")));
        assertEquals(dir.resolve("a.MID"), DocumentFileService.ensureMidiExtension(dir.resolve("a.MID")));
        assertEquals(dir.resolve("a.midi"), DocumentFileService.ensureMidiExtension(dir.resolve("a.midi")));
    }
}
