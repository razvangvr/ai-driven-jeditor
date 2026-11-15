package dev.jeditor.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** The file viewer on the left: a lazily loaded tree of the opened folder. */
final class FileTreePanel extends JPanel {

    private final JTree tree = new JTree(new DefaultTreeModel(null));
    private final Consumer<Path> onOpenFile;

    FileTreePanel(Consumer<Path> onOpenFile) {
        super(new BorderLayout());
        this.onOpenFile = onOpenFile;

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
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        open(path);
                    }
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
        tree.setModel(new DefaultTreeModel(null));
    }

    void setRoot(Path folder) {
        FileNode root = new FileNode(folder);
        root.loadChildren();
        tree.setModel(new DefaultTreeModel(root, true));
    }

    private void open(TreePath path) {
        if (path.getLastPathComponent() instanceof FileNode node && !node.directory) {
            onOpenFile.accept(node.path);
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
