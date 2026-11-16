package dev.jeditor.ui;

import dev.jeditor.compiler.run.ClasspathInspector.Entry;
import dev.jeditor.compiler.run.ClasspathInspector.Kind;
import dev.jeditor.compiler.run.ClasspathInspector.LibraryFolder;
import dev.jeditor.compiler.run.ClasspathInspector.Report;
import dev.jeditor.compiler.run.JavaRuntime;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Classpath Inspector": what the program in the current tab is compiled and run against.
 * Shows the classpath entries in search order, what each JAR is and contains, the folders that
 * JARs are picked up from, and anything that looks wrong; and lets a JAR be added.
 */
final class ClasspathPanel extends JPanel {

    /** One line of the tree. */
    private record Row(String text, String tooltip, Icon icon) {
    }

    private static final Icon JAR_ICON = Icons.badge("J", new Color(0xC27A3A), false);
    private static final Icon FOLDER_ICON = Icons.badge("D", new Color(0x6E8FB3), false);
    private static final Icon OWN_ICON = Icons.badge("P", new Color(0x3C9A4B), false);
    private static final Icon PACKAGE_ICON = Icons.badge("p", new Color(0x9A9A9A), false);

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JButton copyButton;
    private String classpathString = "";

    /**
     * @param onAddJar  called when the user asks to add a JAR to the library folder
     * @param onRefresh called when the user asks for the classpath to be read again
     */
    ClasspathPanel(Runnable onAddJar, Runnable onRefresh) {
        super(new BorderLayout());

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("Add JAR…", "Copy a JAR into the library folder, which puts it on the classpath", onAddJar));
        copyButton = button("Copy", "Copy the classpath, as for 'java -cp', to the clipboard", this::copyClasspath);
        bar.add(copyButton);
        bar.add(button("Refresh", "Read the classpath from disk again", onRefresh));
        add(bar, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0); // rows size themselves: warnings wrap over several lines
        tree.setCellRenderer(new RowRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        showMessage("Open a folder or a Java file to see its classpath.");
    }

    /** Shows a single explanatory line instead of a report. */
    void showMessage(String message) {
        classpathString = "";
        copyButton.setEnabled(false);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        root.add(node(message, null, null));
        tree.setModel(new DefaultTreeModel(root));
    }

    /**
     * @param runtime       the Java runtime programs are run on; may be null
     * @param projectFolder the opened folder, used to shorten paths; may be null
     */
    void show(Report report, JavaRuntime runtime, Path projectFolder) {
        classpathString = report.classpathString();
        copyButton.setEnabled(!classpathString.isEmpty());
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        List<DefaultMutableTreeNode> expanded = new ArrayList<>();

        root.add(node(programLine(report), report.file() == null ? null : report.file().toString(), null));
        if (runtime != null) {
            root.add(node("Runs on " + runtime.displayName(), runtime.home().toString(), null));
        }

        // -- the classpath itself
        int count = report.classpath().size();
        DefaultMutableTreeNode classpath = node("Classpath (" + count + (count == 1 ? " entry" : " entries") + ", in search order)",
                "When two entries contain the same class, the one listed first wins.", null);
        int position = 1;
        for (Entry entry : report.classpath()) {
            classpath.add(entryNode(position++, entry, projectFolder));
        }
        if (count == 0) {
            classpath.add(node("(empty: no libraries)", null, null));
        }
        root.add(classpath);
        expanded.add(classpath);

        // -- where JARs are picked up from
        DefaultMutableTreeNode libraries = node("Library folders", "Every .jar in these folders is on the classpath.", null);
        for (LibraryFolder folder : report.libraryFolders()) {
            libraries.add(node(libraryFolderLine(folder, projectFolder), folder.path().toString(), FOLDER_ICON));
        }
        if (report.libraryFolders().isEmpty()) {
            libraries.add(node("(none: open a folder to get one)", null, null));
        }
        root.add(libraries);
        expanded.add(libraries);

        // -- where sources of other classes come from
        if (!report.sourceFolders().isEmpty()) {
            DefaultMutableTreeNode sources = node("Source folders (" + report.sourceFolders().size() + ")",
                    "Other classes of the project are found and compiled from here.", null);
            report.sourceFolders().forEach(folder -> sources.add(node(shorten(folder, projectFolder), folder.toString(), FOLDER_ICON)));
            root.add(sources);
        }

        if (!report.warnings().isEmpty()) {
            DefaultMutableTreeNode warnings = node("Warnings (" + report.warnings().size() + ")", null, null);
            for (String warning : report.warnings()) {
                warnings.add(node("<html><body style='width:230px'>" + escape(warning) + "</body></html>", null,
                        Icons.of(dev.jeditor.compiler.Severity.WARNING)));
            }
            root.add(warnings);
            expanded.add(warnings);
        }

        tree.setModel(new DefaultTreeModel(root));
        expanded.forEach(node -> tree.expandPath(new TreePath(node.getPath())));
    }

    static String programLine(Report report) {
        if (report.file() == null) {
            return "Libraries of the opened folder";
        }
        String name = report.file().getFileName().toString();
        return report.mainClasses().isEmpty()
                ? name + " — no main method to run"
                : "Runs " + report.mainClasses().get(0) + "  (" + name + ")";
    }

    static String libraryFolderLine(LibraryFolder folder, Path projectFolder) {
        String where = shorten(folder.path(), projectFolder);
        if (!folder.exists()) {
            return where + " — not created yet (Add JAR…)";
        }
        String jars = folder.jarCount() == 0 ? "no JARs yet" : folder.jarCount() == 1 ? "1 JAR" : folder.jarCount() + " JARs";
        return where + " — " + jars + (folder.forNewJars() ? "; new JARs go here" : "");
    }

    /**
     * The one-line summary of an entry. Kept short, because the panel is narrow: the name says
     * most of it, and the details are one level down.
     */
    static String entryLine(int position, Entry entry) {
        return position + ". " + entry.name();
    }

    /** The facts shown under an entry, one per line. */
    static List<String> entryDetails(Entry entry, Path projectFolder) {
        List<String> details = new ArrayList<>();
        if (entry.kind() == Kind.COMPILED_ON_RUN) {
            details.add("Compiled from the sources on each Run");
            return details;
        }
        if (entry.coordinates() != null) {
            details.add("Library: " + entry.coordinates());
        }
        if (entry.version() != null) {
            details.add("Version: " + entry.version());
        }
        String classes = entry.classCount() == 1 ? "1 class" : entry.classCount() + " classes";
        details.add("Contains: " + classes + (entry.kind() == Kind.JAR ? ", " + size(entry.sizeBytes()) : ""));
        details.add("Location: " + shorten(entry.path(), projectFolder));
        details.add("Here because: " + entry.origin());
        return details;
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** A path inside the opened folder is shown relative to it; anything else in full. */
    static String shorten(Path path, Path projectFolder) {
        if (projectFolder != null && path.startsWith(projectFolder) && !path.equals(projectFolder)) {
            return projectFolder.relativize(path).toString();
        }
        return path.toString();
    }

    private DefaultMutableTreeNode entryNode(int position, Entry entry, Path projectFolder) {
        Icon icon = switch (entry.kind()) {
            case COMPILED_ON_RUN -> OWN_ICON;
            case CLASSES_FOLDER -> FOLDER_ICON;
            case JAR -> JAR_ICON;
        };
        DefaultMutableTreeNode node = node(entryLine(position, entry),
                entry.path() == null ? entry.origin() : entry.path() + "  (" + entry.origin() + ")", icon);
        String fullPath = entry.path() == null ? null : entry.path().toString();
        entryDetails(entry, projectFolder).forEach(detail -> node.add(node(detail, fullPath, null)));
        if (!entry.packages().isEmpty()) {
            DefaultMutableTreeNode packages = node("Packages (" + entry.packages().size() + ")", null, null);
            entry.packages().forEach(name -> packages.add(node(name, null, PACKAGE_ICON)));
            node.add(packages);
        }
        return node;
    }

    private void copyClasspath() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(classpathString), null);
    }

    private static DefaultMutableTreeNode node(String text, String tooltip, Icon icon) {
        return new DefaultMutableTreeNode(new Row(text, tooltip, icon));
    }

    private static JButton button(String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class RowRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof Row line) {
                setText(line.text());
                setToolTipText(line.tooltip());
                setIcon(line.icon());
            }
            return this;
        }
    }
}
