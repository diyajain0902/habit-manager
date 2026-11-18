package habitmanager.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;

/**
 * Draws the bubble mascot (the same design as web/bubble.svg) for the tray icon.
 * AWT cannot load SVG, so it is painted with Java2D on a 64x64 canvas and scaled.
 */
final class BubbleIcon {

    private static final Color INK = new Color(0x3f4370);

    private BubbleIcon() {
    }

    static BufferedImage create(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double scale = size / 64.0;
        g.scale(scale, scale);
        paintBody(g);
        paintFace(g);
        g.dispose();
        return image;
    }

    private static void paintBody(Graphics2D g) {
        // At tray size the big bubble fills the icon; the tiny side bubbles would just be noise.
        Ellipse2D body = new Ellipse2D.Double(6, 6, 52, 52);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(26, 22), 40,
                new float[] {0f, 0.35f, 0.75f, 1f},
                new Color[] {Color.WHITE, new Color(0xe6e9fd), new Color(0xc9d0f7), new Color(0xbcaaf0)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(body);
        g.setPaint(new GradientPaint(6, 6, new Color(0x8fc3ec), 58, 58, new Color(0xa98be4)));
        g.setStroke(new BasicStroke(3f));
        g.draw(body);

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(13, 13, 30, 30, 110, 60, Arc2D.OPEN));
    }

    private static void paintFace(Graphics2D g) {
        g.setColor(INK);
        g.fill(new Ellipse2D.Double(21, 29, 6, 7.5));
        g.fill(new Ellipse2D.Double(37, 29, 6, 7.5));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(23.2, 30, 2, 2));
        g.fill(new Ellipse2D.Double(39.2, 30, 2, 2));

        g.setColor(new Color(0xe3, 0xb6, 0xf0, 230));
        g.fill(new Ellipse2D.Double(14, 37.5, 8, 5));
        g.fill(new Ellipse2D.Double(42, 37.5, 8, 5));

        g.setColor(INK);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new QuadCurve2D.Double(28.5, 39.5, 32, 43.5, 35.5, 39.5));
    }
}
