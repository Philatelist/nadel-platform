package app.nadel.documents.internal.nbu;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

/**
 * The source of the hryvnia sign drawn in the background circle of the NBU QR code (Annex 1 items 10, 12 and 14: its
 * form is set by the National Bank). The resolution has the sign only inside raster example images, so the standard
 * source is the glyph U+20B4 of the embedded IBM Plex Sans SemiBold ({@link GlyphHryvniaSign}, owner decision of
 * 28.10.2026). When the owner supplies the official drawing ({@code docs/domain/nbu-hryvnia-sign.svg}), another
 * implementation of this interface reads it and {@link #standard()} returns that one; nothing else changes
 * ({@code documents/README.md}).
 */
interface HryvniaSign {

    /** The source the QR images use. */
    static HryvniaSign standard() {
        return GlyphHryvniaSign.INSTANCE;
    }

    /** The filled outline of the sign in any units, the y axis pointing down (as in an image). */
    Shape outline();

    /**
     * The outline scaled so that its bounding box is inscribed in a circle of the diameter (Annex 1 item 12) centred
     * on {@code (centreX, centreY)}, in the same units, the y axis pointing down.
     */
    default Shape inscribed(double centreX, double centreY, double diameter) {
        Shape outline = outline();
        Rectangle2D bounds = outline.getBounds2D();
        double scale = diameter / Math.hypot(bounds.getWidth(), bounds.getHeight());
        AffineTransform transform = new AffineTransform();
        transform.translate(centreX, centreY);
        transform.scale(scale, scale);
        transform.translate(-bounds.getCenterX(), -bounds.getCenterY());
        return transform.createTransformedShape(outline);
    }
}
