package dev.jeditor.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Recent Files" switcher: a keyboard-first list of the most recently used files.
 * Up/Down to choose, Enter or double-click to open, Esc to dismiss.
 */
final class RecentFilesDialog extends JDialog {

    /**
     * @param files   newest first
     * @param current the file in the active tab, or null; if it heads the list, the entry after it
     *                is preselected so that the shortcut followed by Enter jumps to the previous file
     * @param folder  the open folder, or null; paths inside it are shown relative to it
     */
    RecentFilesDialog(JFrame owner, List<Path> files, Path current, Path folder, Consumer<Path> onOpen) {
        super(owner, "Recent Files", true);

        JList<Path> list = new JList<>(files.toArray(Path[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                Path file = (Path) value;
                setText("<html><b>" + escape(file.getFileName().toString()) + "</b> &nbsp; <font color='gray'>"
                        + escape(locationOf(file, folder)) + "</font></html>");
                setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
                return this;
            }
        });
        if (!files.isEmpty()) {
            list.setSelectedIndex(files.size() > 1 && files.get(0).equals(current) ? 1 : 0);
        }

        Runnable open = () -> {
            Path chosen = list.getSelectedValue();
            dispose();
            if (chosen != null) {
                onOpen.accept(chosen);
            }
        };
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                open.run();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.locationToIndex(e.getPoint()) >= 0) {
                    open.run();
                }
            }
        });
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        if (files.isEmpty()) {
            JLabel empty = new JLabel("No recent files yet", SwingConstants.CENTER);
            empty.setEnabled(false);
            add(empty, BorderLayout.CENTER);
        } else {
            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            add(scroll, BorderLayout.CENTER);
        }
        setPreferredSize(new Dimension(620, Math.max(90, 46 + 28 * Math.max(1, files.size()))));
        pack();
        setLocationRelativeTo(owner);
    }

    /** The file's directory: relative to the open folder when inside it, absolute otherwise. */
    static String locationOf(Path file, Path folder) {
        Path parent = file.getParent();
        if (parent == null) {
            return "";
        }
        if (folder != null && parent.startsWith(folder)) {
            String relative = folder.relativize(parent).toString();
            return relative.isEmpty() ? "." : relative;
        }
        return parent.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
