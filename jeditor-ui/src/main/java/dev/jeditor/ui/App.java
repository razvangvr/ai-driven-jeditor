package dev.jeditor.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point: {@code java -jar jeditor.jar [folder-or-file]}. */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                Path target = Path.of(args[0]).toAbsolutePath().normalize();
                if (Files.isDirectory(target)) {
                    frame.openFolder(target);
                } else if (Files.isRegularFile(target)) {
                    frame.openFolder(target.getParent());
                    frame.openFile(target);
                }
            } else {
                frame.openFolder(Path.of("").toAbsolutePath());
            }
        });
    }
}
