package dev.jeditor.ui;

import dev.jeditor.compiler.run.JvmInfo;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * The "JVM Info" tab: the memory of the program being run. How much RAM the machine has, how
 * much of it the program's JVM may use, how much it is using right now (updated once a second
 * while it runs), and what operating system it is on.
 */
final class JvmInfoPanel extends JPanel {

    static final String NOTHING_RUN_YET = """
            Nothing has been run yet.

            Run a main class (the green button, or Shift+F10) and this tab will show:

              - how much memory (RAM) this machine has
              - how much of it is reserved for the program: its maximum and initial heap
              - how much heap the program is actually using, updated every second
              - the operating system and processor architecture
            """;

    private final JTextArea text = new JTextArea(NOTHING_RUN_YET);

    JvmInfoPanel() {
        super(new BorderLayout());
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setBackground(UIManager.getColor("Tree.background"));
        text.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    /** Shows the latest information. Event thread only. */
    void show(JvmInfo info) {
        String description = info.describe();
        if (!description.equals(text.getText())) {
            // Keep the reader's place: the text is rewritten every second while a program runs.
            int caret = Math.min(text.getCaretPosition(), description.length());
            text.setText(description);
            text.setCaretPosition(caret);
        }
    }
}
