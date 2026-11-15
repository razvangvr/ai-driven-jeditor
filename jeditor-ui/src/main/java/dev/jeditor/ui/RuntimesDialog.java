package dev.jeditor.ui;

import dev.jeditor.compiler.run.JavaRuntime;
import dev.jeditor.compiler.run.RuntimeRegistry;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.UncheckedIOException;

/** Lets the user add and remove the Java installations (JREs or JDKs) programs can be run on. */
final class RuntimesDialog extends JDialog {

    private final RuntimeRegistry registry;
    private final DefaultListModel<JavaRuntime> model = new DefaultListModel<>();
    private final JList<JavaRuntime> list = new JList<>(model);

    RuntimesDialog(JFrame owner, RuntimeRegistry registry) {
        super(owner, "Java Runtimes", true);
        this.registry = registry;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                JavaRuntime runtime = (JavaRuntime) value;
                setText("<html><b>" + runtime.displayName() + "</b> &nbsp; <font color='gray'>" + runtime.home() + "</font></html>");
                setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return this;
            }
        });

        JButton add = new JButton("Add…");
        JButton remove = new JButton("Remove");
        JButton close = new JButton("Close");
        add.addActionListener(e -> addRuntime());
        remove.addActionListener(e -> removeSelected());
        close.addActionListener(e -> dispose());
        remove.setEnabled(false);
        list.addListSelectionListener(e -> remove.setEnabled(list.getSelectedValue() != null));

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(add);
        buttons.add(remove);
        JPanel side = new JPanel(new BorderLayout());
        side.add(buttons, BorderLayout.NORTH);
        side.add(close, BorderLayout.SOUTH);

        JLabel hint = new JLabel("<html>Pick the installation folder of a JRE or JDK (the one that contains <code>bin/java</code>).<br>"
                + "Programs are compiled for, and run on, the runtime chosen in the toolbar.</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(hint, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(side, BorderLayout.LINE_END);
        setContentPane(content);

        reload();
        setPreferredSize(new Dimension(640, 320));
        pack();
        setLocationRelativeTo(owner);
    }

    private void reload() {
        model.clear();
        registry.runtimes().forEach(model::addElement);
    }

    private void addRuntime() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select JRE or JDK Folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            JavaRuntime added = registry.add(chooser.getSelectedFile().toPath());
            reload();
            list.setSelectedValue(added, true);
        } catch (IllegalArgumentException | UncheckedIOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Java Runtimes", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeSelected() {
        JavaRuntime runtime = list.getSelectedValue();
        if (runtime != null) {
            try {
                registry.remove(runtime);
            } catch (UncheckedIOException e) {
                JOptionPane.showMessageDialog(this, e.getMessage(), "Java Runtimes", JOptionPane.ERROR_MESSAGE);
            }
            reload();
        }
    }
}
