package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CodeBlock;
import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.compiler.run.JavaRuntime;
import dev.jeditor.compiler.run.RuntimeRegistry;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The editor window: file tree and structure on the left, editor tabs in the middle, problems and
 * the run console at the bottom, and a toolbar to run the current file on a chosen Java runtime.
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

    private final RuntimeRegistry runtimes = RuntimeRegistry.forCurrentUser();
    private final ConsolePanel console = new ConsolePanel();
    private final RunController runner = new RunController(console, analyzer, this::refreshRunActions);
    private final JTabbedPane bottomTabs = new JTabbedPane();
    private final JComboBox<JavaRuntime> runtimeChooser = new JComboBox<>();
    private final Action runAction = action("Run Main Class", KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), this::runCurrentFile);
    private final Action stopAction = action("Stop", KeyStroke.getKeyStroke(KeyEvent.VK_F2, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), runner::stop);
    private boolean updatingRuntimeChooser;

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
        bottomTabs.addTab("Problems", problems);
        bottomTabs.addTab("Console", console);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, bottomTabs);
        right.setResizeWeight(0.72);
        right.setBorder(null);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        main.setDividerLocation(270);
        main.setBorder(null);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.add(statusLeft, BorderLayout.CENTER);
        status.add(statusRight, BorderLayout.LINE_END);

        setJMenuBar(createMenuBar());
        add(createToolBar(), BorderLayout.NORTH);
        add(main, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        setSize(1280, 820);
        setLocationRelativeTo(null);
        left.setDividerLocation(430);

        if (!analyzer.isAvailable()) {
            statusLeft.setText("No Java compiler found: run JEditor on a JDK (not a JRE) to get error checking.");
        }

        // Out of the box, programs run on the Java the editor itself runs on.
        if (runtimes.runtimes().isEmpty()) {
            JavaRuntime.current().ifPresent(current -> {
                try {
                    runtimes.add(current.home());
                } catch (IllegalArgumentException | UncheckedIOException e) {
                    // not fatal: the user can add a runtime by hand
                }
            });
        }
        refreshRuntimeChooser();
        refreshRunActions();
    }

    // ---- running ------------------------------------------------------------------------------

    private JToolBar createToolBar() {
        runAction.putValue(Action.SMALL_ICON, Icons.run());
        runAction.putValue(Action.SHORT_DESCRIPTION, "Run the main class of the current file (Shift+F10)");
        stopAction.putValue(Action.SMALL_ICON, Icons.stop());
        stopAction.putValue(Action.SHORT_DESCRIPTION, "Stop the running program");

        runtimeChooser.setToolTipText("Java runtime used to run programs");
        runtimeChooser.setMaximumSize(new Dimension(260, 28));
        runtimeChooser.setPreferredSize(new Dimension(220, 28));
        runtimeChooser.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof JavaRuntime runtime) {
                    setText(runtime.displayName());
                    setToolTipText(runtime.home().toString());
                } else {
                    setText("No Java runtime");
                }
                return this;
            }
        });
        runtimeChooser.addActionListener(e -> {
            if (!updatingRuntimeChooser && runtimeChooser.getSelectedItem() instanceof JavaRuntime runtime) {
                try {
                    runtimes.select(runtime);
                } catch (UncheckedIOException ex) {
                    error(ex.getMessage());
                }
            }
        });

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(Box.createHorizontalGlue());
        bar.add(runtimeChooser);
        bar.addSeparator(new Dimension(6, 0));
        bar.add(new JButton(runAction)).setFocusable(false);
        bar.add(new JButton(stopAction)).setFocusable(false);
        bar.addSeparator(new Dimension(6, 0));
        for (Component component : bar.getComponents()) {
            if (component instanceof JButton button) {
                button.setHideActionText(true);
            }
        }
        return bar;
    }

    private void refreshRuntimeChooser() {
        updatingRuntimeChooser = true;
        runtimeChooser.setModel(new DefaultComboBoxModel<>(runtimes.runtimes().toArray(JavaRuntime[]::new)));
        runtimeChooser.setSelectedItem(runtimes.selected().orElse(null));
        updatingRuntimeChooser = false;
    }

    private void refreshRunActions() {
        runAction.setEnabled(!runner.isBusy());
        stopAction.setEnabled(runner.isBusy());
    }

    private void manageRuntimes() {
        new RuntimesDialog(this, runtimes).setVisible(true);
        refreshRuntimeChooser();
    }

    private void runCurrentFile() {
        EditorTab tab = currentTab();
        if (tab == null || !tab.isJava()) {
            error("Open the Java file whose main method you want to run.");
            return;
        }
        if (runtimes.selected().isEmpty()) {
            manageRuntimes();
            if (runtimes.selected().isEmpty()) {
                return;
            }
        }
        // Like any IDE: what runs is what is on disk, so save first.
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabAt(i).isDirty() && !save(tabAt(i))) {
                return;
            }
        }
        bottomTabs.setSelectedComponent(console);
        runner.run(tab.file(), folder != null ? folder : tab.file().getParent(), runtimes.selected().get());
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

        JMenu run = new JMenu("Run");
        run.setMnemonic('R');
        run.add(runAction);
        run.add(stopAction);
        run.addSeparator();
        run.add(action("Java Runtimes\u2026", null, this::manageRuntimes));

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(run);
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
        runner.stop();
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
