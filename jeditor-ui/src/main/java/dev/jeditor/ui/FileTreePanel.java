package dev.jeditor.ui;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The file viewer on the left: a lazily loaded tree of the opened folder, with a right-click menu
 * for creating, renaming and deleting files.
 */
final class FileTreePanel extends JPanel {

    /** What the file viewer asks the application to do; it never touches files itself. */
    interface Actions {
        void open(Path file);

        void newJavaClass(Path directory);

        void newFile(Path directory);

        void newFolder(Path directory);

        void rename(Path fileOrDirectory);

        void delete(Path fileOrDirectory);
    }

    private final JTree tree = new JTree(new DefaultTreeModel(null));
    private final Actions actions;
    private Path root;

    FileTreePanel(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new FileRenderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof FileNode node && node.loadChildren()) {
                    ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // nothing to do
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        open(path);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowMenu(e); // the popup trigger is press on some platforms, release on others
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-file");
        tree.getActionMap().put("delete-file", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Path selected = selectedPath();
                if (selected != null && !selected.equals(root)) {
                    actions.delete(selected);
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename-file");
        tree.getActionMap().put("rename-file", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Path selected = selectedPath();
                if (selected != null && !selected.equals(root)) {
                    actions.rename(selected);
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-file");
        tree.getActionMap().put("open-file", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (tree.getSelectionPath() != null) {
                    open(tree.getSelectionPath());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    /** Empties the tree (no folder open). */
    void clear() {
        root = null;
        tree.setModel(new DefaultTreeModel(null));
    }

    void setRoot(Path folder) {
        root = folder.toAbsolutePath().normalize();
        FileNode rootNode = new FileNode(root);
        rootNode.loadChildren();
        tree.setModel(new DefaultTreeModel(rootNode, true));
    }

    /** Re-reads the folder from disk, keeping expanded folders expanded and the selection in place. */
    void refresh() {
        if (root == null) {
            return;
        }
        List<Path> expanded = new ArrayList<>();
        Enumeration<TreePath> descendants = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
        while (descendants != null && descendants.hasMoreElements()) {
            if (descendants.nextElement().getLastPathComponent() instanceof FileNode node) {
                expanded.add(node.path);
            }
        }
        Path selected = selectedPath();
        setRoot(root);
        expanded.sort(Comparator.comparingInt(Path::getNameCount)); // parents before children
        for (Path directory : expanded) {
            TreePath path = treePathOf(directory);
            if (path != null) {
                tree.expandPath(path);
            }
        }
        if (selected != null) {
            TreePath path = treePathOf(selected);
            if (path != null) {
                tree.setSelectionPath(path);
            }
        }
    }

    /** Selects a file or folder and scrolls it into view, expanding its parents. */
    void reveal(Path target) {
        TreePath path = treePathOf(target.toAbsolutePath().normalize());
        if (path != null) {
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    /** The selected file or folder, or null. */
    Path selectedPath() {
        TreePath selection = tree.getSelectionPath();
        return selection != null && selection.getLastPathComponent() instanceof FileNode node ? node.path : null;
    }

    /**
     * Where a newly created file should go: the selected folder, the folder of the selected file,
     * or the opened folder when nothing is selected. Null when no folder is open.
     */
    Path targetDirectory() {
        Path selected = selectedPath();
        if (selected == null) {
            return root;
        }
        return Files.isDirectory(selected) ? selected : selected.getParent();
    }

    /** Walks from the root to {@code target}, loading folders on the way; null if it is not in the tree. */
    private TreePath treePathOf(Path target) {
        if (root == null || !target.startsWith(root) || !(tree.getModel().getRoot() instanceof FileNode node)) {
            return null;
        }
        TreePath path = new TreePath(node);
        for (Path segment : root.relativize(target)) {
            if (segment.toString().isEmpty()) {
                break; // target is the root itself
            }
            node.loadChildren();
            FileNode next = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof FileNode child && child.path.getFileName().equals(segment)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            node = next;
            path = path.pathByAddingChild(node);
        }
        return path;
    }

    private void maybeShowMenu(MouseEvent e) {
        if (!e.isPopupTrigger() || root == null) {
            return;
        }
        TreePath under = tree.getPathForLocation(e.getX(), e.getY());
        tree.setSelectionPath(under); // right-click acts on what is under the mouse; null clears the selection
        Path selected = selectedPath();
        Path directory = targetDirectory();

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("New Java Class\u2026", () -> actions.newJavaClass(directory)));
        menu.add(item("New File\u2026", () -> actions.newFile(directory)));
        menu.add(item("New Folder\u2026", () -> actions.newFolder(directory)));
        if (selected != null && !selected.equals(root)) {
            menu.addSeparator();
            menu.add(item("Rename\u2026", () -> actions.rename(selected)));
            menu.add(item("Delete", () -> actions.delete(selected)));
        }
        menu.addSeparator();
        menu.add(item("Refresh", this::refresh));
        menu.show(tree, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void open(TreePath path) {
        if (path.getLastPathComponent() instanceof FileNode node && !node.directory) {
            actions.open(node.path);
        }
    }

    /** A file or directory; directories read their children the first time they are expanded. */
    private static final class FileNode extends DefaultMutableTreeNode {
        private static final Comparator<Path> DIRECTORIES_FIRST = Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));

        final Path path;
        final boolean directory;
        private boolean loaded;

        FileNode(Path path) {
            super(path);
            this.path = path;
            this.directory = Files.isDirectory(path);
            setAllowsChildren(directory);
        }

        /** Returns true if children were (re)read from disk. */
        boolean loadChildren() {
            if (!directory || loaded) {
                return false;
            }
            loaded = true;
            try (Stream<Path> children = Files.list(path)) {
                List<Path> sorted = children
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .sorted(DIRECTORIES_FIRST)
                        .toList();
                sorted.forEach(child -> add(new FileNode(child)));
            } catch (IOException e) {
                // unreadable directory shows up as empty
            }
            return true;
        }

        @Override
        public boolean isLeaf() {
            return !directory;
        }

        @Override
        public String toString() {
            Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }
    }

    private static final class FileRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof FileNode node) {
                setToolTipText(node.path.toString());
            }
            return this;
        }
    }
}
