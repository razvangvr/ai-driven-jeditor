package dev.jeditor.ui;

import dev.jeditor.compiler.CodeBlock;
import dev.jeditor.compiler.Severity;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/** Small vector icons drawn in code, so the editor ships without image resources. */
final class Icons {

    private static final int SIZE = 14;

    private Icons() {
    }

    static Icon of(Severity severity) {
        return switch (severity) {
            case ERROR -> new Badge(new Color(0xD64545), "!", true);
            case WARNING -> new WarningTriangle();
            case NOTE -> new Badge(new Color(0x4A88C7), "i", true);
        };
    }

    static Icon of(CodeBlock.Kind kind) {
        return switch (kind) {
            case CLASS -> new Badge(new Color(0x3F7FBF), "C", true);
            case INTERFACE -> new Badge(new Color(0x4E9A51), "I", true);
            case ENUM -> new Badge(new Color(0x9C6ABF), "E", true);
            case RECORD -> new Badge(new Color(0x3F9FA6), "R", true);
            case ANNOTATION -> new Badge(new Color(0x8A8A2E), "@", true);
            case METHOD -> new Badge(new Color(0xC2578F), "m", false);
            case CONSTRUCTOR -> new Badge(new Color(0xC27A3A), "c", false);
            case FIELD -> new Badge(new Color(0xB8962E), "f", false);
            case ENUM_CONSTANT -> new Badge(new Color(0x9C6ABF), "e", false);
            case INITIALIZER -> new Badge(new Color(0x808080), "s", false);
        };
    }

    /** A small rounded badge with a letter, for kinds of things that have no icon of their own. */
    static Icon badge(String letter, Color color, boolean round) {
        return new Badge(color, letter, round);
    }

    /** Green "play" triangle. */
    static Icon run() {
        return new Base() {
            @Override
            void paint(Graphics2D g) {
                Path2D triangle = new Path2D.Float();
                triangle.moveTo(2.5f, 1f);
                triangle.lineTo(SIZE - 1.5f, SIZE / 2f);
                triangle.lineTo(2.5f, SIZE - 1f);
                triangle.closePath();
                g.setColor(new Color(0x3C9A4B));
                g.fill(triangle);
            }
        };
    }

    /** Red "stop" square. */
    static Icon stop() {
        return new Base() {
            @Override
            void paint(Graphics2D g) {
                g.setColor(new Color(0xD64545));
                g.fillRoundRect(2, 2, SIZE - 4, SIZE - 4, 3, 3);
            }
        };
    }

    private abstract static class Base implements Icon {
        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.translate(x, y);
            paint(g2);
            g2.dispose();
        }

        abstract void paint(Graphics2D g);

        static void centered(Graphics2D g, String text, Color color, float yShift) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            g.setColor(color);
            FontMetrics metrics = g.getFontMetrics();
            float tx = (SIZE - metrics.stringWidth(text)) / 2f;
            float ty = (SIZE - metrics.getHeight()) / 2f + metrics.getAscent() + yShift;
            g.drawString(text, tx, ty);
        }
    }

    /** A filled circle (types, severities) or rounded square (members) with a letter. */
    private static final class Badge extends Base {
        private final Color color;
        private final String letter;
        private final boolean round;

        Badge(Color color, String letter, boolean round) {
            this.color = color;
            this.letter = letter;
            this.round = round;
        }

        @Override
        void paint(Graphics2D g) {
            g.setColor(color);
            if (round) {
                g.fillOval(0, 0, SIZE, SIZE);
            } else {
                g.fillRoundRect(0, 0, SIZE, SIZE, 5, 5);
            }
            centered(g, letter, Color.WHITE, 0);
        }
    }

    private static final class WarningTriangle extends Base {
        @Override
        void paint(Graphics2D g) {
            Path2D triangle = new Path2D.Float();
            triangle.moveTo(SIZE / 2f, 0.5f);
            triangle.lineTo(SIZE - 0.5f, SIZE - 1f);
            triangle.lineTo(0.5f, SIZE - 1f);
            triangle.closePath();
            g.setColor(new Color(0xE9A700));
            g.fill(triangle);
            centered(g, "!", Color.BLACK, 1.5f);
        }
    }
}
