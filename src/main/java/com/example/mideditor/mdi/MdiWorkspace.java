package com.example.mideditor.mdi;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.SwingUtilities;

import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.ui.theme.Theme;

/**
 * MDI 客户区：经典 MDI 工作区，承载多个 MDI 子窗口（不是 Tab，也不是独立顶层窗口）。
 */
public final class MdiWorkspace extends JDesktopPane {

    private int cascadeOffset;

    public MdiWorkspace() {
        setBackground(Theme.WORKSPACE_BACKGROUND);
        setDragMode(OUTLINE_DRAG_MODE);
    }

    public MdiChildFrame addDocument(DocumentSession session) {
        MidiDocumentView view = session.view();
        MdiChildFrame frame = new MdiChildFrame(session, view.app());
        frame.setBounds(nextBounds());
        add(frame);
        frame.setVisible(true);
        try {
            frame.setSelected(true);
        } catch (Exception ignored) {
            // 忽略
        }
        // 文档窗口默认在 MDI 客户区内最大化（与旧版 Word/VS 的文档窗口行为一致）
        maximizeFrame(frame);
        return frame;
    }

    private void maximizeFrame(MdiChildFrame frame) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            // 桌面尚未完成布局（例如测试中窗口还未显示）：等首次布局后再最大化
            SwingUtilities.invokeLater(() -> {
                if (getWidth() > 0 && getHeight() > 0) {
                    safeMaximum(frame, true);
                }
            });
            return;
        }
        safeMaximum(frame, true);
    }

    private java.awt.Rectangle nextBounds() {
        Dimension size = getSize();
        int width = Math.max(560, (int) (size.width * 0.78));
        int height = Math.max(380, (int) (size.height * 0.8));
        int offset = cascadeOffset % 8;
        cascadeOffset += 26;
        int x = 16 + offset * 24;
        int y = 12 + offset * 22;
        if (x + width > size.width - 8) {
            x = Math.max(8, size.width - width - 8);
        }
        if (y + height > size.height - 8) {
            y = Math.max(8, size.height - height - 8);
        }
        return new java.awt.Rectangle(x, y, width, height);
    }

    public List<MdiChildFrame> documentFrames() {
        List<MdiChildFrame> frames = new ArrayList<>();
        for (JInternalFrame frame : getAllFrames()) {
            if (frame instanceof MdiChildFrame child && frame.isVisible()) {
                frames.add(child);
            }
        }
        return frames;
    }

    public void cascade() {
        int index = 0;
        for (MdiChildFrame frame : documentFrames()) {
            safeIcon(frame, false);
            safeMaximum(frame, false);
            int offset = (index++ % 10) * 26;
            frame.setBounds(12 + offset, 10 + offset,
                    Math.max(520, (int) (getWidth() * 0.72)),
                    Math.max(340, (int) (getHeight() * 0.74)));
        }
    }

    private static void safeIcon(MdiChildFrame frame, boolean icon) {
        try {
            frame.setIcon(icon);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private static void safeMaximum(MdiChildFrame frame, boolean maximum) {
        try {
            frame.setMaximum(maximum);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private static void safeSelect(MdiChildFrame frame) {
        try {
            frame.setSelected(true);
        } catch (Exception ignored) {
            // 忽略
        }
    }

    public void tile() {
        List<MdiChildFrame> frames = documentFrames();
        if (frames.isEmpty()) {
            return;
        }
        int count = frames.size();
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) columns);
        int cellWidth = Math.max(200, getWidth() / columns);
        int cellHeight = Math.max(160, getHeight() / rows);
        for (int i = 0; i < count; i++) {
            MdiChildFrame frame = frames.get(i);
            safeIcon(frame, false);
            safeMaximum(frame, false);
            int col = i % columns;
            int row = i / columns;
            frame.setBounds(col * cellWidth, row * cellHeight, cellWidth - 2, cellHeight - 2);
        }
    }

    public void minimizeAll() {
        for (MdiChildFrame frame : documentFrames()) {
            safeIcon(frame, true);
        }
    }

    public void restoreAll() {
        for (MdiChildFrame frame : documentFrames()) {
            safeIcon(frame, false);
            safeMaximum(frame, false);
        }
        List<MdiChildFrame> frames = documentFrames();
        if (!frames.isEmpty()) {
            safeSelect(frames.get(0));
        }
    }

    public MdiChildFrame activeFrame() {
        JInternalFrame selected = getSelectedFrame();
        return selected instanceof MdiChildFrame child ? child : null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!documentFrames().isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(new Color(0xFFFFFF));
            g2.setFont(getFont().deriveFont(Font.BOLD, 20f));
            String title = "多音轨 MIDI 编辑器";
            int titleWidth = g2.getFontMetrics().stringWidth(title);
            g2.drawString(title, (getWidth() - titleWidth) / 2, getHeight() / 2 - 26);

            g2.setFont(getFont().deriveFont(Font.PLAIN, 13f));
            g2.setColor(new Color(0xFFFFFF));
            String hint = "文件 → 新建 / 打开，创建 MDI 文档窗口（可同时打开多个）";
            int hintWidth = g2.getFontMetrics().stringWidth(hint);
            g2.drawString(hint, (getWidth() - hintWidth) / 2, getHeight() / 2 + 6);
        } finally {
            g2.dispose();
        }
    }
}
