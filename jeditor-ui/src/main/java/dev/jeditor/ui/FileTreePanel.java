package dev.jeditor.ui;

import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
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
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
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

    private static final System.Logger LOG = System.getLogger(FileTreePanel.class.getName());

    /** What the file viewer asks the application to do; it never touches files itself. */
    interface Actions {
        void open(Path file);

        void newJavaClass(Path directory);

        void newFile(Path directory);

        void newFolder(Path directory);

        void rename(Path fileOrDirectory);

        void delete(Path fileOrDirectory);

        /** A file or folder of the tree was dropped onto a folder of the tree. */
        void move(Path fileOrDirectory, Path directory);

        /** Files from outside the editor were dropped onto a folder of the tree. */
        void copyIn(List<Path> files, Path directory);
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
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new FileDropHandler());
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
            public void mousePressed(MouseEvent e) {
                // Open on the second *press*. A "click" event is only produced when the pointer does
                // not move at all between press and release, so with a trackpad or a slightly moving
                // mouse some double-clicks never arrived, and now that rows can be dragged such a
                // movement can also start a drag instead.
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = tree.getClosestRowForLocation(e.getX(), e.getY());
                    Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
                    if (bounds != null && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height) {
                        open(tree.getPathForRow(row));
                    }
                }
                if (SwingUtilities.isLeftMouseButton(e) && tree.getPathForLocation(e.getX(), e.getY()) == null) {
                    selectRowAt(e); // clicking beside a name selects its row too, not only clicking on it
                }
                maybeShowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowMenu(e); // the popup trigger is press on some platforms, release on others
            }
        });
        // "Delete" on a Mac keyboard is Backspace; accept both, with or without the menu modifier (Cmd+Delete).
        int menuKey = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        for (int key : new int[] {KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE}) {
            tree.getInputMap().put(KeyStroke.getKeyStroke(key, 0), "delete-file");
            tree.getInputMap().put(KeyStroke.getKeyStroke(key, menuKey), "delete-file");
        }
        tree.getActionMap().put("delete-file", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Path selected = selectedPath();
                if (selected != null) {
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

    /**
     * Drag and drop. Dragging a file or folder of the tree onto a folder moves it there; files
     * dragged in from outside the editor are copied. Dropping onto a file means "into its folder",
     * and dropping onto empty space means the opened folder.
     */
    private final class FileDropHandler extends TransferHandler {
        /** What is being dragged out of this tree; null when the drag started elsewhere. */
        private Path dragged;

        @Override
        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent component) {
            Path selected = selectedPath();
            if (selected == null || selected.equals(root)) {
                return null; // the opened folder itself cannot be dragged
            }
            dragged = selected;
            List<File> files = List.of(selected.toFile());
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {DataFlavor.javaFileListFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DataFlavor.javaFileListFlavor.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) {
                    return files;
                }
            };
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            dragged = null; // the move itself was done by importData, through the application
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (root == null || !support.isDrop() || !support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }
            Path directory = dropDirectory(support);
            // Refuse the obviously pointless drops up front, so the cursor shows "not here".
            return directory != null && (dragged == null
                    || (!directory.equals(dragged.getParent()) && !directory.startsWith(dragged)));
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Path directory = dropDirectory(support);
            Path source = dragged;
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = files.stream().map(File::toPath).toList();
                // Let the drag finish before any dialog opens.
                SwingUtilities.invokeLater(() -> {
                    if (source != null) {
                        actions.move(source, directory);
                    } else {
                        actions.copyIn(paths, directory);
                    }
                });
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read what was dropped onto " + directory, e);
                return false;
            }
        }

        private Path dropDirectory(TransferSupport support) {
            TreePath path = ((JTree.DropLocation) support.getDropLocation()).getPath();
            if (path == null) {
                return root;
            }
            if (path.getLastPathComponent() instanceof FileNode node) {
                return node.directory ? node.path : node.path.getParent();
            }
            return null;
        }
    }

    private void maybeShowMenu(MouseEvent e) {
        if (!e.isPopupTrigger() || root == null) {
            return;
        }
        selectRowAt(e); // right-click acts on the row under the mouse; elsewhere, on what is already selected
        Path selected = selectedPath();
        Path directory = targetDirectory();

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("New Java Class\u2026", () -> actions.newJavaClass(directory)));
        menu.add(item("New File\u2026", () -> actions.newFile(directory)));
        menu.add(item("New Folder\u2026", () -> actions.newFolder(directory)));
        if (selected != null) {
            menu.addSeparator();
            if (!selected.equals(root)) {
                menu.add(item("Rename\u2026", () -> actions.rename(selected)));
            }
            menu.add(item("Delete", () -> actions.delete(selected)));
        }
        menu.addSeparator();
        menu.add(item("Refresh", this::refresh));
        menu.show(tree, e.getX(), e.getY());
    }

    /**
     * Selects the row at the mouse's height, wherever on that row the click lands: the name, the
     * icon, or the empty space to the right of it. A click below the last row changes nothing.
     */
    private void selectRowAt(MouseEvent e) {
        int row = tree.getClosestRowForLocation(e.getX(), e.getY());
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds != null && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height) {
            tree.setSelectionRow(row);
        }
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
                // shows up as empty
                LOG.log(System.Logger.Level.WARNING, "Cannot list the contents of " + path, e);
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
