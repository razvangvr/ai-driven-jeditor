package dev.jeditor.ui;

import dev.jeditor.compiler.run.LaunchRecord;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * The "Launch Classpath" tab: a plain account of the most recent Run. Which class was started,
 * on which Java, with what on the classpath and why, the exact command, and how to repeat it by
 * hand. The text can be selected and copied.
 */
final class LaunchPanel extends JPanel {

    static final String NOTHING_RUN_YET = """
            Nothing has been run yet.

            Run a main class (the green button, or Shift+F10) and this tab will show exactly
            how it was launched:

              - the main class and the Java it ran on
              - every entry on the classpath, in order, and why it is there
              - the exact command the editor ran
              - how to run the same program yourself from a terminal
            """;

    private final JTextArea text = new JTextArea(NOTHING_RUN_YET);
    private final JButton copyCommand = button("Copy Command", "Copy the exact command the editor ran");
    private final JButton copyClasspath = button("Copy Classpath", "Copy the value that was passed to -cp");
    private LaunchRecord shown;

    LaunchPanel() {
        super(new BorderLayout());
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(copyCommand);
        bar.add(copyClasspath);
        add(bar, BorderLayout.NORTH);
        copyCommand.addActionListener(e -> copy(shown.commandLine()));
        copyClasspath.addActionListener(e -> copy(shown.classpathString()));
        copyCommand.setEnabled(false);
        copyClasspath.setEnabled(false);

        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setBackground(UIManager.getColor("Tree.background"));
        text.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    /** Shows a launch; call again with the finished record to add the result. Event thread only. */
    void show(LaunchRecord launch) {
        boolean sameRun = shown != null && shown.compiledClasses().equals(launch.compiledClasses())
                && shown.command().equals(launch.command());
        int scrollPosition = text.getCaretPosition();
        shown = launch;
        text.setText(launch.describe());
        // A new run starts at the top; the same run finishing keeps the reader where they were.
        text.setCaretPosition(sameRun ? Math.min(scrollPosition, text.getDocument().getLength()) : 0);
        copyCommand.setEnabled(true);
        copyClasspath.setEnabled(true);
    }

    private static void copy(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    private static JButton button(String label, String tooltip) {
        JButton button = new JButton(label);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return button;
    }
}
