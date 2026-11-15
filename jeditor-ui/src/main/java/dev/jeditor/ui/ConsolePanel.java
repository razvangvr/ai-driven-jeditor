package dev.jeditor.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the output of the running program and lets the user type its standard input.
 * Text may be appended from any thread.
 */
final class ConsolePanel extends JPanel {

    enum Style {
        /** What the program printed. */
        OUTPUT(null),
        /** What the user typed. */
        INPUT(new Color(0x2E7D32)),
        /** Messages from the editor itself (command line, exit code). */
        INFO(new Color(0x7A7A7A)),
        /** Build failures and launch problems. */
        ERROR(new Color(0xC62828));

        private final SimpleAttributeSet attributes = new SimpleAttributeSet();

        Style(Color color) {
            if (color != null) {
                StyleConstants.setForeground(attributes, color);
            }
        }
    }

    private record Segment(Style style, StringBuilder text) {
    }

    /** Keeps memory bounded when a program prints without end. */
    private static final int MAX_CHARS = 500_000;
    private static final int MAX_PENDING_CHARS = 200_000;

    private final JTextPane output = new JTextPane();
    private final JTextField input = new JTextField();

    private final Object lock = new Object();
    private final List<Segment> pending = new ArrayList<>();
    private int pendingChars;
    private boolean flushScheduled;
    private OutputStream programInput;

    ConsolePanel() {
        super(new BorderLayout());
        output.setEditable(false);
        output.setBackground(UIManager.getColor("Tree.background")); // read-only panes default to grey
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        inputRow.add(new JLabel("Input:"), BorderLayout.LINE_START);
        inputRow.add(input, BorderLayout.CENTER);
        add(inputRow, BorderLayout.SOUTH);
        input.setFont(output.getFont());
        input.setEnabled(false);
        input.setToolTipText("Type a line and press Enter to send it to the running program");
        input.addActionListener(e -> sendInput());
    }

    void clear() {
        synchronized (lock) {
            pending.clear();
            pendingChars = 0;
        }
        output.setText("");
    }

    /**
     * Appends text; safe from any thread. Bursts are coalesced into few UI updates, and a thread
     * that produces output faster than it can be shown is briefly held back.
     */
    void append(Style style, String text) {
        boolean throttle;
        synchronized (lock) {
            if (!pending.isEmpty() && pending.get(pending.size() - 1).style == style) {
                pending.get(pending.size() - 1).text.append(text);
            } else {
                pending.add(new Segment(style, new StringBuilder(text)));
            }
            pendingChars += text.length();
            throttle = pendingChars > MAX_PENDING_CHARS && !SwingUtilities.isEventDispatchThread();
            if (!flushScheduled) {
                flushScheduled = true;
                SwingUtilities.invokeLater(this::flush);
            }
        }
        if (throttle) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Connects the input field to a program's standard input; {@code null} disconnects it. */
    void setProgramInput(OutputStream stream) {
        SwingUtilities.invokeLater(() -> {
            programInput = stream;
            input.setEnabled(stream != null);
            if (stream == null) {
                input.setText("");
            }
        });
    }

    void focusInput() {
        input.requestFocusInWindow();
    }

    private void sendInput() {
        OutputStream target = programInput;
        if (target == null) {
            return;
        }
        String line = input.getText() + "\n";
        input.setText("");
        append(Style.INPUT, line);
        try {
            target.write(line.getBytes(StandardCharsets.UTF_8));
            target.flush();
        } catch (IOException e) {
            // the program has exited or closed its input; nothing to deliver to
        }
    }

    private void flush() {
        List<Segment> batch;
        synchronized (lock) {
            batch = new ArrayList<>(pending);
            pending.clear();
            pendingChars = 0;
            flushScheduled = false;
        }
        StyledDocument document = output.getStyledDocument();
        try {
            for (Segment segment : batch) {
                document.insertString(document.getLength(), segment.text.toString(), segment.style.attributes);
            }
            if (document.getLength() > MAX_CHARS) {
                document.remove(0, document.getLength() - MAX_CHARS);
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e); // offsets are derived from the document itself
        }
        output.setCaretPosition(document.getLength());
    }
}
