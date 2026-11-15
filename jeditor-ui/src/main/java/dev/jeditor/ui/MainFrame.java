package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CodeBlock;
import dev.jeditor.compiler.JavaSourceAnalyzer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The editor window: file tree and structure on the left, editor tabs in the middle, problems at
 * the bottom.
 */
final class MainFrame extends JFrame {

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();
    private final ExecutorService compilerThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jeditor-compiler");
        thread.setDaemon(true);
        return thread;
    });

    private final FileTreePanel fileTree = new FileTreePanel(this::openFile);
    private final JTabbedPane tabs = new JTabbedPane();
    private final StructurePanel structure = new StructurePanel(block -> withCurrentTab(tab -> tab.goTo(block.startOffset())));
    private final ProblemsPanel problems = new ProblemsPanel(d -> withCurrentTab(tab -> tab.goTo(d.startOffset())));
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");

    private Path folder;

    MainFrame() {
        super("JEditor");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tabs);

        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(e -> {
            refreshViews();
            withCurrentTab(EditorTab::focusEditor);
        });

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fileTree, structure);
        left.setResizeWeight(0.6);
        left.setBorder(null);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, problems);
        right.setResizeWeight(0.78);
        right.setBorder(null);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        main.setDividerLocation(270);
        main.setBorder(null);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.add(statusLeft, BorderLayout.CENTER);
        status.add(statusRight, BorderLayout.LINE_END);

        setJMenuBar(createMenuBar());
        add(main, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        setSize(1280, 820);
        setLocationRelativeTo(null);
        left.setDividerLocation(430);

        if (!analyzer.isAvailable()) {
            statusLeft.setText("No Java compiler found: run JEditor on a JDK (not a JRE) to get error checking.");
        }
    }

    // ---- opening ------------------------------------------------------------------------------

    void openFolder(Path newFolder) {
        folder = newFolder;
        fileTree.setRoot(newFolder);
        setTitle("JEditor — " + newFolder);
    }

    void openFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabAt(i).file().equals(normalized)) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        try {
            if (Files.size(normalized) > MAX_FILE_BYTES) {
                error("File is too large to open (over 5 MB):\n" + normalized);
                return;
            }
            EditorTab tab = new EditorTab(normalized, analyzer, compilerThread);
            tab.addChangeListener(() -> tabChanged(tab));
            tabs.addTab(null, null, tab, normalized.toString());
            int index = tabs.indexOfComponent(tab);
            tabs.setTabComponentAt(index, new TabHeader(tab));
            tabs.setSelectedIndex(index);
            tab.focusEditor();
        } catch (IOException e) {
            error("Cannot open " + normalized + "\n" + e.getMessage());
        }
    }

    // ---- menu ---------------------------------------------------------------------------------

    private JMenuBar createMenuBar() {
        int menuKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(action("Open Folder…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menuKey | InputEvent.SHIFT_DOWN_MASK), this::chooseFolder));
        file.add(action("Open File…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menuKey), this::chooseFile));
        file.addSeparator();
        file.add(action("Save", KeyStroke.getKeyStroke(KeyEvent.VK_S, menuKey), () -> withCurrentTab(this::save)));
        file.add(action("Save All", KeyStroke.getKeyStroke(KeyEvent.VK_S, menuKey | InputEvent.SHIFT_DOWN_MASK), this::saveAll));
        file.add(action("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, menuKey), () -> withCurrentTab(this::close)));
        file.addSeparator();
        file.add(action("Refresh File Tree", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), () -> {
            if (folder != null) {
                fileTree.setRoot(folder);
            }
        }));
        file.addSeparator();
        file.add(action("Exit", null, this::exit));

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        return bar;
    }

    private static Action action(String name, KeyStroke shortcut, Runnable body) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                body.run();
            }
        };
        action.putValue(Action.ACCELERATOR_KEY, shortcut);
        return action;
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(folder == null ? null : folder.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Open Folder");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFolder(chooser.getSelectedFile().toPath());
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(folder == null ? null : folder.toFile());
        chooser.setDialogTitle("Open File");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    // ---- saving and closing -------------------------------------------------------------------

    private boolean save(EditorTab tab) {
        try {
            tab.save();
            return true;
        } catch (IOException e) {
            error("Cannot save " + tab.file() + "\n" + e.getMessage());
            return false;
        }
    }

    private void saveAll() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabAt(i).isDirty()) {
                save(tabAt(i));
            }
        }
    }

    /** Closes the tab, asking first if it has unsaved changes. Returns false if the user cancelled. */
    private boolean close(EditorTab tab) {
        if (tab.isDirty()) {
            tabs.setSelectedComponent(tab);
            int choice = JOptionPane.showConfirmDialog(this,
                    "Save changes to " + tab.file().getFileName() + "?", "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION
                    || (choice == JOptionPane.YES_OPTION && !save(tab))) {
                return false;
            }
        }
        tab.dispose();
        tabs.remove(tab);
        refreshViews();
        return true;
    }

    private void exit() {
        while (tabs.getTabCount() > 0) {
            if (!close(tabAt(tabs.getTabCount() - 1))) {
                return;
            }
        }
        compilerThread.shutdownNow();
        dispose();
        System.exit(0);
    }

    // ---- keeping the views in sync ------------------------------------------------------------

    private void tabChanged(EditorTab tab) {
        int index = tabs.indexOfComponent(tab);
        if (index >= 0 && tabs.getTabComponentAt(index) instanceof TabHeader header) {
            header.refresh();
        }
        if (tab == currentTab()) {
            refreshViews();
        }
    }

    private void refreshViews() {
        EditorTab tab = currentTab();
        if (tab == null) {
            structure.show(List.of());
            problems.show(null, AnalysisResult.EMPTY, false);
            statusRight.setText(" ");
            if (analyzer.isAvailable()) {
                statusLeft.setText(" ");
            }
            return;
        }
        AnalysisResult result = tab.result();
        structure.show(result.structure());
        problems.show(tab.isJava() ? tab.file().getFileName().toString() : null, result, tab.isAnalysisPending());
        statusRight.setText("Ln " + tab.caretLine() + ", Col " + tab.caretColumn());
        if (analyzer.isAvailable()) {
            statusLeft.setText(breadcrumb(CodeBlock.pathTo(result.structure(), tab.caretOffset())));
        }
    }

    /** "Account › deposit(int) : void  (lines 12–20)" for the block the caret is in. */
    static String breadcrumb(List<CodeBlock> path) {
        if (path.isEmpty()) {
            return " ";
        }
        CodeBlock innermost = path.get(path.size() - 1);
        return path.stream().map(CodeBlock::name).collect(Collectors.joining("  ›  "))
                + "   (lines " + innermost.startLine() + "–" + innermost.endLine() + ")";
    }

    private EditorTab currentTab() {
        return (EditorTab) tabs.getSelectedComponent();
    }

    private EditorTab tabAt(int index) {
        return (EditorTab) tabs.getComponentAt(index);
    }

    private void withCurrentTab(Consumer<EditorTab> action) {
        EditorTab tab = currentTab();
        if (tab != null) {
            action.accept(tab);
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "JEditor", JOptionPane.ERROR_MESSAGE);
    }

    /** Tab title with an unsaved-changes marker, an error indicator and a close button. */
    private final class TabHeader extends JPanel {
        private final EditorTab tab;
        private final JLabel title = new JLabel();

        TabHeader(EditorTab tab) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            this.tab = tab;
            setOpaque(false);
            JButton closeButton = new JButton("×");
            closeButton.setMargin(new Insets(0, 4, 0, 4));
            closeButton.setBorderPainted(false);
            closeButton.setContentAreaFilled(false);
            closeButton.setFocusable(false);
            closeButton.setToolTipText("Close");
            closeButton.setPreferredSize(new Dimension(20, 18));
            closeButton.addActionListener(e -> close(tab));
            add(title);
            add(closeButton);
            refresh();
        }

        void refresh() {
            title.setText((tab.isDirty() ? "• " : "") + tab.file().getFileName());
            title.setIcon(tab.result().hasErrors() ? Icons.of(dev.jeditor.compiler.Severity.ERROR) : null);
        }
    }
}
