package dev.jeditor.ui;

import dev.jeditor.compiler.CodeBlock;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Outline of the current Java file: types and members, with the lines each one spans. */
final class StructurePanel extends JPanel {

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private List<CodeBlock> shown = List.of();

    StructurePanel(Consumer<CodeBlock> onNavigate) {
        super(new BorderLayout());

        JLabel header = new JLabel("Structure");
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(header, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new BlockRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof CodeBlock block) {
                    onNavigate.accept(block);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    void show(List<CodeBlock> structure) {
        if (structure.equals(shown)) {
            return; // keep selection and scroll position when nothing changed
        }
        shown = structure;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        structure.forEach(block -> root.add(node(block)));
        tree.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    private static DefaultMutableTreeNode node(CodeBlock block) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(block);
        block.children().forEach(child -> node.add(node(child)));
        return node;
    }

    private static final class BlockRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof CodeBlock block) {
                setText(block.name());
                setIcon(Icons.of(block.kind()));
                setToolTipText(block.startLine() == block.endLine()
                        ? "line " + block.startLine()
                        : "lines " + block.startLine() + "–" + block.endLine());
            }
            return this;
        }
    }
}
