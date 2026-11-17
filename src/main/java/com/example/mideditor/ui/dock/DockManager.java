package com.example.mideditor.ui.dock;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.example.mideditor.ui.theme.Theme;

/**
 * 轻量停靠管理器：左 / 右 / 底三个停靠区，支持显示/隐藏与浮动。
 */
public final class DockManager extends JPanel {

    public enum Region {
        LEFT, RIGHT, BOTTOM
    }

    private final Map<String, DockPanel> panels = new LinkedHashMap<>();
    private final Map<String, Region> regions = new HashMap<>();
    private final Map<String, JDialog> dialogs = new HashMap<>();
    private final JPanel left = regionPanel();
    private final JPanel right = regionPanel();
    private final JPanel bottom = regionPanel();
    private final JPanel center = new JPanel(new BorderLayout());

    public DockManager() {
        super(new BorderLayout());
        left.setPreferredSize(new Dimension(250, 100));
        right.setPreferredSize(new Dimension(260, 100));
        bottom.setPreferredSize(new Dimension(100, 180));
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        updateRegionVisibility();
    }

    private static JPanel regionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Theme.MENU_BACKGROUND);
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));
        return panel;
    }

    /** 主工作区（MDI 桌面）放入中央。 */
    public void setCenter(JComponent component) {
        center.removeAll();
        center.add(component, BorderLayout.CENTER);
        center.revalidate();
    }

    public DockPanel register(String id, String title, JComponent content, Region region, boolean visible) {
        DockPanel panel = new DockPanel(this, id, title, content);
        panels.put(id, panel);
        regions.put(id, region);
        if (visible) {
            show(panel);
        }
        updateRegionVisibility();
        return panel;
    }

    public DockPanel panel(String id) {
        return panels.get(id);
    }

    public boolean isShowing(String id) {
        DockPanel panel = panels.get(id);
        if (panel == null) {
            return false;
        }
        JPanel container = containerOf(id);
        return container.getComponentCount() > 0 && panel.getParent() == container;
    }

    public void toggle(String id) {
        DockPanel panel = panels.get(id);
        if (panel != null) {
            toggle(panel);
        }
    }

    public void toggle(DockPanel panel) {
        if (isShowing(panel.id())) {
            hide(panel);
        } else {
            show(panel);
        }
    }

    public void show(DockPanel panel) {
        closeDialog(panel.id());
        JPanel container = containerOf(panel.id());
        if (panel.getParent() != container) {
            if (panel.getParent() != null) {
                panel.getParent().remove(panel);
            }
            container.add(panel);
        }
        panel.setVisible(true);
        updateRegionVisibility();
        revalidate();
        repaint();
    }

    public void hide(DockPanel panel) {
        closeDialog(panel.id());
        java.awt.Container parent = panel.getParent();
        if (parent != null) {
            parent.remove(panel);
        }
        panel.setVisible(false);
        updateRegionVisibility();
        revalidate();
        repaint();
    }

    public void floatPanel(DockPanel panel) {
        if (dialogs.containsKey(panel.id())) {
            closeDialog(panel.id());
            show(panel);
            return;
        }
        if (panel.getParent() != null) {
            panel.getParent().remove(panel);
        }
        panel.setVisible(false);
        updateRegionVisibility();

        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, panel.title(), JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());
        dialog.add(panel.content(), BorderLayout.CENTER);
        dialog.setSize(320, 420);
        dialog.setLocationRelativeTo(owner);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                dialogs.remove(panel.id());
            }
        });
        dialogs.put(panel.id(), dialog);
        dialog.setVisible(true);
    }

    private void closeDialog(String id) {
        JDialog dialog = dialogs.remove(id);
        if (dialog != null) {
            DockPanel panel = panels.get(id);
            if (panel != null) {
                dialog.remove(panel.content());
            }
            dialog.dispose();
        }
    }

    private JPanel containerOf(String id) {
        Region region = regions.getOrDefault(id, Region.LEFT);
        return switch (region) {
            case LEFT -> left;
            case RIGHT -> right;
            case BOTTOM -> bottom;
        };
    }

    private void updateRegionVisibility() {
        left.setVisible(left.getComponentCount() > 0);
        right.setVisible(right.getComponentCount() > 0);
        bottom.setVisible(bottom.getComponentCount() > 0);
    }
}
