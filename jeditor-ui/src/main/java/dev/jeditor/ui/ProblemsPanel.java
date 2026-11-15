package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CompilationDiagnostic;
import dev.jeditor.compiler.Severity;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** The classic "Problems" view: every compiler diagnostic of the current file, one per row. */
final class ProblemsPanel extends JPanel {

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel header = new JLabel();

    ProblemsPanel(Consumer<CompilationDiagnostic> onNavigate) {
        super(new BorderLayout());

        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(header, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(28);
        table.getColumnModel().getColumn(0).setMinWidth(28);
        table.getColumnModel().getColumn(2).setMaxWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(t, "", selected, focus, row, column);
                setIcon(Icons.of((Severity) value));
                setHorizontalAlignment(CENTER);
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && row >= 0) {
                    onNavigate.accept(model.diagnostics.get(row));
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        show(null, AnalysisResult.EMPTY, false);
    }

    /**
     * @param fileName name of the current file, or null when the current tab cannot be compiled
     * @param pending  true while a fresh analysis is still running
     */
    void show(String fileName, AnalysisResult result, boolean pending) {
        model.diagnostics = result.diagnostics();
        model.fireTableDataChanged();
        header.setText(headerText(fileName, result, pending));
    }

    static String headerText(String fileName, AnalysisResult result, boolean pending) {
        if (fileName == null) {
            return "Problems";
        }
        long errors = result.count(Severity.ERROR);
        long warnings = result.count(Severity.WARNING);
        String summary;
        if (pending && result.diagnostics().isEmpty()) {
            summary = "compiling…";
        } else if (result.diagnostics().isEmpty()) {
            summary = "no problems";
        } else {
            summary = plural(errors, "error") + ", " + plural(warnings, "warning");
        }
        return "Problems — " + fileName + ": " + summary;
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static final class Model extends AbstractTableModel {
        private static final String[] COLUMNS = {"", "Description", "Location"};
        private List<CompilationDiagnostic> diagnostics = List.of();

        @Override
        public int getRowCount() {
            return diagnostics.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            CompilationDiagnostic d = diagnostics.get(row);
            return switch (column) {
                case 0 -> d.severity();
                case 1 -> d.message().replaceAll("\\s*\\n\\s*", "  ·  ");
                default -> "line " + d.line() + ":" + d.column();
            };
        }
    }
}
