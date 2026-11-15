package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CodeBlock;
import dev.jeditor.compiler.DeclarationTarget;
import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.compiler.JavaTypeTemplate;
import dev.jeditor.compiler.run.JavaRuntime;
import dev.jeditor.compiler.run.RuntimeRegistry;
import dev.jeditor.format.JavaFormatter;

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
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.WindowConstants;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.text.JTextComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private final FileTreePanel fileTree = new FileTreePanel(new FileTreeActions());
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
    private final Action newClassAction = action("New Java Class\u2026",
            KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
            () -> newJavaClass(fileTree.targetDirectory()));
    private final Action newFileAction = action("New File\u2026", null, () -> newFile(fileTree.targetDirectory()));
    private final Action newFolderAction = action("New Folder\u2026", null, () -> newFolder(fileTree.targetDirectory()));
    private final Action saveAction = action("Save",
            KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
            () -> withCurrentTab(this::save));
    private final Action closeFolderAction = action("Close Folder", null, this::closeFolder);
    private final Action compileFileAction = action("Compile Current File",
            KeyStroke.getKeyStroke(KeyEvent.VK_F9, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK),
            this::compileCurrentFile);
    private final Action buildProjectAction = action("Build Project",
            KeyStroke.getKeyStroke(KeyEvent.VK_F9, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), this::buildProject);
    private boolean updatingRuntimeChooser;

    private final JavaFormatter formatter = new JavaFormatter();
    /** Until this {@link System#nanoTime()}, the status bar shows a message instead of the caret's code block. */
    private long statusMessageUntil;
    private final javax.swing.Timer statusMessageTimer = new javax.swing.Timer(4000, e -> refreshViews());

    /** Recent files across all folders, with the folder each was used in (stored in the user's home). */
    private final RecentFiles allRecentFiles = RecentFiles.forCurrentUser();
    /** Recent files of the open folder (stored inside that folder); null while no folder is open. */
    private RecentFiles folderRecentFiles;
    /** True while tabs are being closed in bulk, when tab selection changes are not the user's doing. */
    private boolean closingAllTabs;

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
            if (!closingAllTabs) {
                withCurrentTab(tab -> touchRecent(tab.file()));
            }
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
        right.setDividerLocation(520); // explicit: with no tab open yet the editor area would otherwise collapse

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
        closeFolderAction.setEnabled(false);
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
        newClassAction.putValue(Action.SMALL_ICON, Icons.of(CodeBlock.Kind.CLASS));
        newClassAction.putValue(Action.SHORT_DESCRIPTION, "Create a new Java class in the selected folder");
        newFileAction.putValue(Action.SHORT_DESCRIPTION, "Create a new file in the selected folder");
        saveAction.putValue(Action.SHORT_DESCRIPTION, "Save the current file");
        bar.addSeparator(new Dimension(6, 0));
        for (Action action : List.of(newClassAction, newFileAction, saveAction)) {
            JButton button = new JButton(action);
            button.setFocusable(false);
            button.setText(((String) action.getValue(Action.NAME)).replace("\u2026", ""));
            bar.add(button);
        }
        bar.add(Box.createHorizontalGlue());
        bar.add(runtimeChooser);
        bar.addSeparator(new Dimension(6, 0));
        buildProjectAction.putValue(Action.SHORT_DESCRIPTION, "Compile all sources of the folder into its output directory");
        JButton buildButton = new JButton(buildProjectAction);
        buildButton.setText("Build");
        buildButton.setFocusable(false);
        bar.add(buildButton);
        bar.add(new JButton(runAction)).setFocusable(false);
        bar.add(new JButton(stopAction)).setFocusable(false);
        bar.addSeparator(new Dimension(6, 0));
        for (Component component : bar.getComponents()) {
            if (component instanceof JButton button && (button.getAction() == runAction || button.getAction() == stopAction)) {
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
        boolean busy = runner.isBusy();
        runAction.setEnabled(!busy);
        compileFileAction.setEnabled(!busy);
        buildProjectAction.setEnabled(!busy);
        stopAction.setEnabled(busy);
        if (!busy && buildRunning) {
            buildRunning = false;
            fileTree.refresh(); // show the freshly written output directory
        }
    }

    /** True while the job in progress is a build, whose output the file viewer should pick up when it ends. */
    private boolean buildRunning;

    /** Saves everything, like any IDE before compiling: what is built is what is on disk. False if a save failed. */
    private boolean saveAllForBuild() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabAt(i).isDirty() && !save(tabAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Builds target the selected runtime's Java version, so the classes run on it; else the editor's own. */
    private int buildRelease() {
        return runtimes.selected().map(JavaRuntime::featureVersion).orElse(Runtime.version().feature());
    }

    private void compileCurrentFile() {
        EditorTab tab = currentTab();
        if (tab == null || !tab.isJava()) {
            error("Open the Java file you want to compile.");
            return;
        }
        if (saveAllForBuild()) {
            bottomTabs.setSelectedComponent(console);
            buildRunning = true;
            runner.compileFile(tab.file(), folder, buildRelease());
        }
    }

    private void buildProject() {
        if (folder == null) {
            error("Open a folder first (File \u2192 Open Folder\u2026).");
            return;
        }
        if (saveAllForBuild()) {
            bottomTabs.setSelectedComponent(console);
            buildRunning = true;
            runner.buildProject(folder, buildRelease());
        }
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
        if (!saveAllForBuild()) {
            return;
        }
        bottomTabs.setSelectedComponent(console);
        runner.run(tab.file(), folder != null ? folder : tab.file().getParent(), runtimes.selected().get());
    }

    // ---- opening ------------------------------------------------------------------------------

    void openFolder(Path newFolder) {
        folder = newFolder.toAbsolutePath().normalize();
        folderRecentFiles = RecentFiles.forFolder(folder);
        fileTree.setRoot(newFolder);
        setTitle("JEditor — " + newFolder);
        closeFolderAction.setEnabled(true);
    }

    /**
     * Closes the current folder: its open files (asking about unsaved changes), any running
     * program, and the file tree. Does nothing if the user cancels on an unsaved file.
     */
    private void closeFolder() {
        if (!closeAllTabs()) {
            return;
        }
        runner.stop();
        folder = null;
        folderRecentFiles = null;
        fileTree.clear();
        setTitle("JEditor");
        closeFolderAction.setEnabled(false);
    }

    /** Opens the file in a tab, or selects its tab if already open. Returns null if it could not be opened. */
    EditorTab openFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabAt(i).file().equals(normalized)) {
                tabs.setSelectedIndex(i);
                return tabAt(i);
            }
        }
        try {
            if (Files.size(normalized) > MAX_FILE_BYTES) {
                error("File is too large to open (over 5 MB):\n" + normalized);
                return null;
            }
            EditorTab tab = new EditorTab(normalized, analyzer, compilerThread, this::navigateTo);
            tab.addChangeListener(() -> tabChanged(tab));
            tabs.addTab(null, null, tab, normalized.toString());
            int index = tabs.indexOfComponent(tab);
            tabs.setTabComponentAt(index, new TabHeader(tab));
            tabs.setSelectedIndex(index);
            tab.focusEditor();
            return tab;
        } catch (IOException e) {
            error("Cannot open " + normalized + "\n" + e.getMessage());
            return null;
        }
    }

    /** Outcome of a "go to declaration" request: open the declaring file at the declared name. */
    private void navigateTo(Optional<DeclarationTarget> declaration) {
        if (declaration.isEmpty()) {
            showStatusMessage("Nothing to navigate to here.");
            return;
        }
        DeclarationTarget target = declaration.get();
        if (!target.hasSource()) {
            showStatusMessage("No source available for " + target.name() + " (it comes from a library or the JDK).");
            return;
        }
        EditorTab tab = openFile(target.file().get());
        if (tab != null) {
            tab.goTo(target.offset());
        }
    }

    // ---- menu ---------------------------------------------------------------------------------

    private JMenuBar createMenuBar() {
        int menuKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(newClassAction);
        file.add(newFileAction);
        file.add(newFolderAction);
        file.addSeparator();
        file.add(action("Rename Selected\u2026", null, () -> withSelectedInFileViewer(this::rename)));
        file.add(action("Delete Selected", null, () -> withSelectedInFileViewer(this::delete)));
        file.addSeparator();
        file.add(action("Open Folder…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menuKey | InputEvent.SHIFT_DOWN_MASK), this::chooseFolder));
        file.add(action("Open File…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menuKey), this::chooseFile));
        file.add(action("Recent Files\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_E, menuKey), this::showRecentFiles));
        file.add(closeFolderAction);
        file.addSeparator();
        file.add(saveAction);
        file.add(action("Save All", KeyStroke.getKeyStroke(KeyEvent.VK_S, menuKey | InputEvent.SHIFT_DOWN_MASK), this::saveAll));
        file.add(action("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, menuKey), () -> withCurrentTab(this::close)));
        file.addSeparator();
        file.add(action("Refresh File Tree", KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), fileTree::refresh));
        file.addSeparator();
        file.add(action("Exit", null, this::exit));

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        edit.add(action("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey), () -> editCommand(EditorTab::undo, null)));
        edit.add(action("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey | InputEvent.SHIFT_DOWN_MASK), () -> editCommand(EditorTab::redo, null)));
        edit.addSeparator();
        edit.add(action("Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, menuKey), () -> editCommand(EditorTab::cut, JTextComponent::cut)));
        edit.add(action("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, menuKey), () -> editCommand(EditorTab::copy, JTextComponent::copy)));
        edit.add(action("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, menuKey), () -> editCommand(EditorTab::paste, JTextComponent::paste)));
        edit.add(action("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, menuKey), () -> editCommand(EditorTab::selectAll, JTextComponent::selectAll)));
        edit.addSeparator();
        edit.add(action("Reformat Code", KeyStroke.getKeyStroke(KeyEvent.VK_L, menuKey | InputEvent.ALT_DOWN_MASK), this::reformatCode));
        edit.addSeparator();
        edit.add(action("Find\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_F, menuKey), () -> withCurrentTab(tab -> tab.showFind(false))));
        edit.add(action("Replace\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuKey), () -> withCurrentTab(tab -> tab.showFind(true))));

        JMenu navigate = new JMenu("Navigate");
        navigate.setMnemonic('N');
        navigate.add(action("Go to Line\u2026", KeyStroke.getKeyStroke(KeyEvent.VK_G, menuKey), this::goToLine));
        navigate.add(action("Go to Declaration", KeyStroke.getKeyStroke(KeyEvent.VK_B, menuKey),
                () -> withCurrentTab(EditorTab::goToDeclarationAtCaret)));

        JMenu build = new JMenu("Build");
        build.setMnemonic('B');
        build.add(compileFileAction);
        build.add(buildProjectAction);

        JMenu run = new JMenu("Run");
        run.setMnemonic('R');
        run.add(runAction);
        run.add(stopAction);
        run.addSeparator();
        run.add(action("Java Runtimes\u2026", null, this::manageRuntimes));

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(edit);
        bar.add(navigate);
        bar.add(build);
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

    // ---- editing ------------------------------------------------------------------------------

    /**
     * Routes an Edit-menu command to whatever has the keyboard focus: the editor, or another text
     * field (find bar, console input) for the commands that make sense there.
     *
     * @param onField what to do when a plain text field is focused; null if the command is editor-only
     */
    private void editCommand(Consumer<EditorTab> onEditor, Consumer<JTextComponent> onField) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (focus instanceof JTextComponent field && !(focus instanceof RSyntaxTextArea)) {
            if (onField != null) {
                onField.accept(field);
            }
            return;
        }
        withCurrentTab(onEditor);
    }

    /** Reformats the selection, or the whole current file, to the standard Java code conventions. */
    private void reformatCode() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return;
        }
        String name = tab.file().getFileName().toString();
        tab.reformat(formatter, outcome -> showStatusMessage(switch (outcome) {
            case REFORMATTED -> "Reformatted " + name + " (undo with " + (isMac() ? "Cmd" : "Ctrl") + "+Z).";
            case ALREADY_FORMATTED -> name + " is already formatted.";
            case CANNOT_FORMAT -> "Cannot reformat " + name + ": it has syntax errors the formatter cannot get past.";
            case NOT_JAVA -> "Reformat Code works on Java files only.";
        }));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Shows a message in the status bar for a few seconds, in place of the caret's code block. */
    private void showStatusMessage(String message) {
        statusMessageUntil = System.nanoTime() + 3_900_000_000L;
        statusLeft.setText(message);
        statusMessageTimer.setRepeats(false);
        statusMessageTimer.restart();
    }

    private void goToLine() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return;
        }
        String input = (String) JOptionPane.showInputDialog(this, "Line[:column]  (1–" + tab.lineCount() + ")",
                "Go to Line", JOptionPane.PLAIN_MESSAGE, null, null, tab.caretLine() + ":" + tab.caretColumn());
        if (input == null) {
            return;
        }
        int[] position = parseLineColumn(input);
        if (position == null) {
            error("'" + input.strip() + "' is not a line number.");
        } else {
            tab.goToLine(position[0], position[1]);
        }
    }

    /** Parses "12" or "12:5" into {line, column}; null if it is neither. */
    static int[] parseLineColumn(String input) {
        String[] parts = input.strip().split("\\s*[:,]\\s*", -1);
        try {
            if (parts.length == 1) {
                return new int[] {Math.max(1, Integer.parseInt(parts[0])), 1};
            }
            if (parts.length == 2) {
                return new int[] {Math.max(1, Integer.parseInt(parts[0])), Math.max(1, Integer.parseInt(parts[1]))};
            }
        } catch (NumberFormatException e) {
            // falls through
        }
        return null;
    }

    // ---- creating, renaming, deleting files ---------------------------------------------------

    private final class FileTreeActions implements FileTreePanel.Actions {
        @Override
        public void open(Path file) {
            openFile(file);
        }

        @Override
        public void newJavaClass(Path directory) {
            MainFrame.this.newJavaClass(directory);
        }

        @Override
        public void newFile(Path directory) {
            MainFrame.this.newFile(directory);
        }

        @Override
        public void newFolder(Path directory) {
            MainFrame.this.newFolder(directory);
        }

        @Override
        public void rename(Path target) {
            MainFrame.this.rename(target);
        }

        @Override
        public void delete(Path target) {
            MainFrame.this.delete(target);
        }

        @Override
        public void move(Path source, Path directory) {
            MainFrame.this.move(source, directory);
        }

        @Override
        public void copyIn(List<Path> sources, Path directory) {
            MainFrame.this.copyIn(sources, directory);
        }
    }

    private void newJavaClass(Path directory) {
        if (directory == null) {
            error("Open a folder first (File → Open Folder…), then create the class in it.");
            return;
        }
        JTextField name = new JTextField(24);
        JComboBox<JavaTypeTemplate.Kind> kind = new JComboBox<>(JavaTypeTemplate.Kind.values());
        kind.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(((JavaTypeTemplate.Kind) value).label());
                return this;
            }
        });
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Name (e.g. Invoice, or billing.Invoice to also create the package):"));
        form.add(name);
        form.add(new JLabel("Kind:"));
        form.add(kind);
        form.add(new JLabel("<html><font color='gray'>In: " + directory + "</font></html>"));
        focusWhenShown(name);

        while (JOptionPane.showConfirmDialog(this, form, "New Java Class", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                JavaTypeTemplate.NewType type = JavaTypeTemplate.plan(directory, folder, name.getText(),
                        (JavaTypeTemplate.Kind) kind.getSelectedItem());
                Path relative = directory.relativize(type.file());
                Path created = FileOperations.createFile(directory, relative.toString(), type.source());
                showCreated(created, type.source().indexOf('{') + 2);
                return;
            } catch (IllegalArgumentException e) {
                error(e.getMessage()); // and ask again, keeping what was typed
            } catch (IOException e) {
                error("Cannot create the class:\n" + e.getMessage());
                return;
            }
        }
    }

    private void newFile(Path directory) {
        createByName(directory, "New File", "File name (e.g. notes.txt, or docs/notes.txt):", true);
    }

    private void newFolder(Path directory) {
        createByName(directory, "New Folder", "Folder name:", false);
    }

    private void createByName(Path directory, String title, String prompt, boolean file) {
        if (directory == null) {
            error("Open a folder first (File → Open Folder…).");
            return;
        }
        String name = "";
        while ((name = (String) JOptionPane.showInputDialog(this, prompt + "\nIn: " + directory, title,
                JOptionPane.PLAIN_MESSAGE, null, null, name)) != null) {
            try {
                if (file) {
                    showCreated(FileOperations.createFile(directory, name, ""), 0);
                } else {
                    Path created = FileOperations.createFolder(directory, name);
                    fileTree.refresh();
                    fileTree.reveal(created);
                }
                return;
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
            } catch (IOException e) {
                error("Cannot create '" + name + "':\n" + e.getMessage());
                return;
            }
        }
    }

    private void showCreated(Path file, int caretOffset) {
        fileTree.refresh();
        fileTree.reveal(file);
        EditorTab tab = openFile(file);
        if (tab != null) {
            tab.goTo(caretOffset);
        }
    }

    private void rename(Path target) {
        if (target.equals(folder)) {
            error("The opened folder itself cannot be renamed from inside the editor.");
            return;
        }
        String current = target.getFileName().toString();
        String name = current;
        while ((name = (String) JOptionPane.showInputDialog(this, "New name for '" + current + "':", "Rename",
                JOptionPane.PLAIN_MESSAGE, null, null, name)) != null) {
            try {
                relocate(target, FileOperations.renameDestination(target, name));
                return;
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
            } catch (IOException e) {
                error("Cannot rename '" + current + "':\n" + e.getMessage());
                fileTree.refresh();
                return;
            }
        }
    }

    /** Applies a file-viewer command from the menu bar to the file or folder selected in the file viewer. */
    private void withSelectedInFileViewer(Consumer<Path> command) {
        Path selected = fileTree.selectedPath();
        if (selected == null) {
            error("Select a file or folder in the file viewer first.");
        } else {
            command.accept(selected);
        }
    }

    /** Drag and drop within the file viewer: moves a file or folder into another folder, after asking. */
    private void move(Path source, Path directory) {
        try {
            Path destination = FileOperations.moveDestination(source, directory);
            if (destination.equals(source.toAbsolutePath().normalize())) {
                return; // dropped where it already is
            }
            String question = "Move '" + source.getFileName() + "' to '" + directory.getFileName() + "'?";
            if (JOptionPane.showConfirmDialog(this, question, "Move", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION) {
                relocate(source, destination);
            }
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
        } catch (IOException e) {
            error("Cannot move '" + source.getFileName() + "':\n" + e.getMessage());
            fileTree.refresh();
        }
    }

    /** Files dragged in from outside the editor (the desktop, a file manager) are copied into the folder. */
    private void copyIn(List<Path> sources, Path directory) {
        Path last = null;
        for (Path source : sources) {
            try {
                last = FileOperations.copyInto(source, directory);
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
            } catch (IOException e) {
                error("Cannot copy '" + source.getFileName() + "':\n" + e.getMessage());
            }
        }
        fileTree.refresh();
        if (last != null) {
            fileTree.reveal(last);
        }
    }

    /**
     * Moves or renames {@code source} to the already validated {@code destination}. Open tabs of
     * the file (or of files in the folder) are closed, saving on request, and reopened at their
     * new location.
     */
    private void relocate(Path source, Path destination) throws IOException {
        if (destination.equals(source)) {
            return;
        }
        List<Path> open = openFilesUnder(source);
        Path selected = currentTab() == null ? null : currentTab().file();
        for (EditorTab tab : tabsUnder(source)) {
            if (!close(tab)) {
                return; // cancelled on an unsaved file: nothing has been moved
            }
        }
        Files.move(source, destination);
        fileTree.refresh();
        fileTree.reveal(destination);
        for (Path file : open) {
            openFile(FileOperations.relocated(file, source, destination));
        }
        if (selected != null && Files.exists(FileOperations.relocated(selected, source, destination))) {
            openFile(FileOperations.relocated(selected, source, destination)); // back to the tab the user was on
        }
    }

    /**
     * Deletes whatever is selected in the file viewer: a file, or a folder with all its
     * subfolders and files, however deep. Asks first.
     */
    private void delete(Path target) {
        boolean directory = Files.isDirectory(target);
        boolean openedFolder = target.equals(folder);
        String name = "'" + target.getFileName() + "'";
        String what = openedFolder ? "the opened folder " + name + " and everything in it"
                : directory ? "the folder " + name + " and everything in it"
                : name;
        String question = (FileOperations.deletesToTrash()
                ? "Move " + what + " to the trash?"
                : "Permanently delete " + what + "?\nThis cannot be undone.")
                + deletionSummary(target);
        if (JOptionPane.showConfirmDialog(this, question, "Delete", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        for (EditorTab tab : tabsUnder(target)) { // their content is being deleted: no point asking to save
            tab.dispose();
            tabs.remove(tab);
        }
        try {
            FileOperations.delete(target);
        } catch (IOException e) {
            error("Cannot delete " + name + ":\n" + e.getMessage());
        }
        if (openedFolder && !Files.exists(target)) {
            closeFolder(); // nothing left to show
            return;
        }
        fileTree.refresh();
        refreshViews();
    }

    /** For a folder, how much is about to go: "\n\nContains 12 files in 4 folders." Empty for a file. */
    private static String deletionSummary(Path target) {
        if (!Files.isDirectory(target)) {
            return "";
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(target)) {
            long[] counts = new long[2];
            paths.filter(path -> !path.equals(target)).forEach(path -> counts[Files.isDirectory(path) ? 1 : 0]++);
            return "\n\n" + describeContents(counts[0], counts[1]);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static String describeContents(long files, long folders) {
        if (files == 0 && folders == 0) {
            return "The folder is empty.";
        }
        String fileText = files + (files == 1 ? " file" : " files");
        String folderText = folders + (folders == 1 ? " subfolder" : " subfolders");
        return "Contains " + (folders == 0 ? fileText : files == 0 ? folderText : fileText + " in " + folderText) + ".";
    }

    private List<EditorTab> tabsUnder(Path fileOrDirectory) {
        List<EditorTab> result = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabAt(i).file().startsWith(fileOrDirectory)) {
                result.add(tabAt(i));
            }
        }
        return result;
    }

    private List<Path> openFilesUnder(Path fileOrDirectory) {
        return tabsUnder(fileOrDirectory).stream().map(EditorTab::file).toList();
    }

    /** Gives a dialog's field the keyboard focus as soon as the dialog appears. */
    private static void focusWhenShown(JTextField field) {
        field.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                // The dialog is not focusable yet at this point, so ask again once its window has the
                // focus; otherwise the OK button keeps it and typing goes nowhere.
                java.awt.Window window = SwingUtilities.getWindowAncestor(field);
                if (window != null) {
                    window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                        @Override
                        public void windowGainedFocus(WindowEvent e) {
                            field.requestFocusInWindow();
                            window.removeWindowFocusListener(this);
                        }
                    });
                }
                field.requestFocusInWindow();
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
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

    /** Closes every tab; stops and returns false as soon as the user cancels one. */
    private boolean closeAllTabs() {
        closingAllTabs = true;
        try {
            while (tabs.getTabCount() > 0) {
                if (!close(tabAt(tabs.getTabCount() - 1))) {
                    return false;
                }
            }
            return true;
        } finally {
            closingAllTabs = false;
        }
    }

    private void touchRecent(Path file) {
        allRecentFiles.touch(file, folder);
        if (folderRecentFiles != null && file.startsWith(folder)) {
            folderRecentFiles.touch(file);
        }
    }

    /**
     * Shows the most recently used files: those of the open folder first, then files of other
     * folders. Choosing one opens it, together with its folder if that is not the one open.
     */
    private void showRecentFiles() {
        EditorTab current = currentTab();
        List<Path> files = folderRecentFiles == null
                ? allRecentFiles.list()
                : RecentFiles.merged(folderRecentFiles.list(), allRecentFiles.list(), RecentFiles.LIMIT);
        new RecentFilesDialog(this, files, current == null ? null : current.file(), folder, this::openRecent)
                .setVisible(true);
    }

    /** Opens a recent file; if it belongs to another folder than the one open, that folder is opened first. */
    private void openRecent(Path file) {
        if (folder == null || !file.startsWith(folder)) {
            Path target = allRecentFiles.folderOf(file);
            if (target != null && !target.equals(folder)) {
                if (!closeAllTabs()) {
                    return; // the user cancelled on an unsaved file: stay where we are
                }
                runner.stop();
                openFolder(target);
            }
        }
        if (openFile(file) != null) {
            fileTree.reveal(file);
        }
    }

    private void exit() {
        if (!closeAllTabs()) {
            return;
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
        if (analyzer.isAvailable() && System.nanoTime() >= statusMessageUntil) {
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
