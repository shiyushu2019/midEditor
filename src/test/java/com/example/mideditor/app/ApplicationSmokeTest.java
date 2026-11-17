package com.example.mideditor.app;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.document.DocumentSession;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** MDI 启动为空、多文档路由与撤销隔离的端到端冒烟测试。 */
class ApplicationSmokeTest {

    @Test
    void emptyStartThenDocumentsRouteAndUndoInIsolation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");

        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainFrame(app);
            holder[0].setVisible(false);
        });
        MainFrame frame = holder[0];
        try {
            assertEquals(0, frame.workspace().getAllFrames().length, "启动时不应有任何文档窗口");

            SwingUtilities.invokeAndWait(app.documents()::newDocument);
            SwingUtilities.invokeAndWait(app.documents()::newDocument);
            assertEquals(2, frame.workspace().getAllFrames().length, "新建后应出现 MDI 子窗口");
            assertEquals(2, app.documents().sessionCount());

            DocumentSession first = app.documents().sessions().get(0);
            DocumentSession second = app.documents().sessions().get(1);

            SwingUtilities.invokeAndWait(() -> {
                app.documents().activate(second);
                second.view().setPendingAdd(0, 60);
                app.router().dispatch(AppCommandIds.NOTE_ADD, app.currentContext());
            });
            assertEquals(1, second.document().totalNoteCount());
            assertEquals(0, first.document().totalNoteCount());
            assertTrue(second.view().editStack().canUndo());
            assertFalse(first.view().editStack().canUndo(), "撤销栈必须按文档隔离");

            SwingUtilities.invokeAndWait(() -> {
                app.documents().activate(first);
                first.view().setPendingAdd(480, 64);
                app.router().dispatch(AppCommandIds.NOTE_ADD, app.currentContext());
            });
            assertEquals(1, first.document().totalNoteCount());
            assertEquals(1, second.document().totalNoteCount());

            SwingUtilities.invokeAndWait(() -> app.router().dispatch(AppCommandIds.EDIT_UNDO, app.currentContext()));
            assertEquals(0, first.document().totalNoteCount(), "撤销只作用于活动文档");
            assertEquals(1, second.document().totalNoteCount());
            assertTrue(second.view().editStack().canUndo());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                frame.dispose();
                app.shutdown();
            });
        }
    }
}
