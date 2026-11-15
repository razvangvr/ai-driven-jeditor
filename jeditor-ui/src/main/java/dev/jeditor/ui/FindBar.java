package dev.jeditor.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Find and replace within one editor: a bar under the text, hidden until asked for.
 * Enter finds the next match, Shift+Enter the previous one, Esc closes the bar. Searching wraps
 * around the ends of the file, and all matches are highlighted while the bar is open.
 */
final class FindBar extends JPanel {

    private final RSyntaxTextArea textArea;
    private final JTextField searchField = new JTextField(22);
    private final JTextField replaceField = new JTextField(22);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JCheckBox wholeWord = new JCheckBox("Words");
    private final JLabel status = new JLabel(" ");
    private final JPanel replaceRow = new JPanel();

    FindBar(RSyntaxTextArea textArea) {
        this.textArea = textArea;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JButton previous = button("Previous", () -> find(false));
        JButton next = button("Next", () -> find(true));
        JButton close = button("×", this::close);
        close.setToolTipText("Close (Esc)");

        JPanel findRow = new JPanel();
        findRow.setLayout(new BoxLayout(findRow, BoxLayout.X_AXIS));
        addAll(findRow, label("Find:"), searchField, gap(), previous, next, gap(), matchCase, wholeWord, gap(), status,
                Box.createHorizontalGlue(), close);

        replaceRow.setLayout(new BoxLayout(replaceRow, BoxLayout.X_AXIS));
        replaceRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        addAll(replaceRow, label("Replace:"), replaceField, gap(), button("Replace", this::replace),
                button("Replace All", this::replaceAll), Box.createHorizontalGlue());

        add(findRow);
        add(replaceRow);
        for (JTextField field : new JTextField[] {searchField, replaceField}) {
            field.setMaximumSize(new Dimension(320, field.getPreferredSize().height));
        }

        searchField.addActionListener(e -> find(true));
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "find-previous");
        searchField.getActionMap().put("find-previous", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                find(false);
            }
        });
        replaceField.addActionListener(e -> replace());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                highlightMatches();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                highlightMatches();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // plain text field: never fired for content
            }
        });
        matchCase.addActionListener(e -> highlightMatches());
        wholeWord.addActionListener(e -> highlightMatches());
        matchCase.setFocusable(false);
        wholeWord.setFocusable(false);
        registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        setVisible(false);
    }

    /** Shows the bar, pre-filled with the editor's selection when that is a single line. */
    void open(boolean withReplace) {
        String selection = textArea.getSelectedText();
        if (selection != null && !selection.isEmpty() && selection.indexOf('\n') < 0) {
            searchField.setText(selection);
        }
        replaceRow.setVisible(withReplace);
        setVisible(true);
        revalidate();
        searchField.requestFocusInWindow();
        searchField.selectAll();
        highlightMatches();
    }

    void close() {
        setVisible(false);
        SearchEngine.markAll(textArea, new SearchContext()); // clears the highlights
        textArea.requestFocusInWindow();
    }

    private SearchContext context(boolean forward) {
        SearchContext context = new SearchContext(searchField.getText(), matchCase.isSelected());
        context.setWholeWord(wholeWord.isSelected());
        context.setSearchForward(forward);
        context.setReplaceWith(replaceField.getText());
        context.setMarkAll(true);
        return context;
    }

    private void highlightMatches() {
        if (!isVisible()) {
            return;
        }
        if (searchField.getText().isEmpty()) {
            SearchEngine.markAll(textArea, new SearchContext());
            status.setText(" ");
            return;
        }
        showCount(SearchEngine.markAll(textArea, context(true)).getMarkedCount());
    }

    private void find(boolean forward) {
        if (searchField.getText().isEmpty()) {
            return;
        }
        SearchContext context = context(forward);
        SearchResult result = SearchEngine.find(textArea, context);
        if (!result.wasFound()) {
            // Wrap around: retry from the other end of the file.
            int caret = textArea.getCaretPosition();
            textArea.setCaretPosition(forward ? 0 : textArea.getDocument().getLength());
            result = SearchEngine.find(textArea, context);
            if (!result.wasFound()) {
                textArea.setCaretPosition(caret);
            }
        }
        showCount(result.getMarkedCount());
    }

    private void replace() {
        if (searchField.getText().isEmpty()) {
            return;
        }
        SearchResult result = SearchEngine.replace(textArea, context(true));
        if (!result.wasFound()) {
            // Nothing after the caret: wrap around and replace the first match in the file.
            int caret = textArea.getCaretPosition();
            textArea.setCaretPosition(0);
            if (!SearchEngine.replace(textArea, context(true)).wasFound()) {
                textArea.setCaretPosition(caret);
            }
        }
        highlightMatches();
    }

    private void replaceAll() {
        if (searchField.getText().isEmpty()) {
            return;
        }
        int count = SearchEngine.replaceAll(textArea, context(true)).getCount();
        highlightMatches();
        status.setText(count == 0 ? "No matches" : "Replaced " + count);
    }

    private void showCount(int count) {
        status.setText(count == 0 ? "No matches" : count == 1 ? "1 match" : count + " matches");
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setPreferredSize(new Dimension(62, label.getPreferredSize().height));
        label.setMinimumSize(label.getPreferredSize());
        label.setMaximumSize(label.getPreferredSize());
        return label;
    }

    private static java.awt.Component gap() {
        return Box.createHorizontalStrut(8);
    }

    private static void addAll(JPanel panel, java.awt.Component... components) {
        for (java.awt.Component component : components) {
            panel.add(component);
        }
    }
}
