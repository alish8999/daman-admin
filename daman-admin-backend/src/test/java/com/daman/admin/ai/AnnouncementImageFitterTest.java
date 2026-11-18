package com.daman.admin.ai;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The picture a drawing model returns is whatever size it felt like, and the cap it has to fit
 * is not ours to move: it is what a till on a phone line downloads. The call has already been
 * billed by the time the bytes arrive, so throwing them away for being 60% over is paying for
 * nothing — the fitter re-encodes them instead, and only refuses what it genuinely cannot use.
 *
 * <p>The cap is a parameter precisely so these tests can use a small one: a PNG big enough to
 * breach 512 KB has to be noise, and noise re-encodes nothing like an illustration does.
 */
class AnnouncementImageFitterTest {

    private static final int CAP = 8 * 1024;

    /** A gradient, which survives JPEG honestly and is comfortably over an 8 KB cap as a PNG. */
    private static byte[] gradientPng(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, new Color(x * 255 / w, y * 255 / h, (x + y) * 255 / (w + h)).getRGB());
            }
        }
        return encode(img, "png");
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aPictureAlreadyWithinTheCapIsHandedBackUntouched() {
        byte[] small = gradientPng(8, 8);
        assertThat(small.length).isLessThan(CAP);

        Picture fitted = AnnouncementImageFitter.fit(small, "image/png", CAP);

        assertThat(fitted.bytes()).isSameAs(small);
        assertThat(fitted.contentType()).isEqualTo("image/png");
    }

    @Test
    void anOversizedPictureComesBackUnderTheCapAsJpeg() {
        byte[] big = gradientPng(600, 400);
        assertThat(big.length).isGreaterThan(CAP);

        Picture fitted = AnnouncementImageFitter.fit(big, "image/png", CAP);

        assertThat(fitted.bytes().length).isLessThanOrEqualTo(CAP);
        assertThat(fitted.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void theRefittedPictureKeepsItsShape() {
        byte[] big = gradientPng(600, 400);

        BufferedImage out = decode(AnnouncementImageFitter.fit(big, "image/png", CAP).bytes());

        assertThat((double) out.getWidth() / out.getHeight()).isCloseTo(600.0 / 400.0, org.assertj.core.data.Offset.offset(0.02));
    }

    /**
     * JPEG has no alpha channel. Dropping it without a thought paints every transparent pixel
     * black, which turns a logo-shaped cut-out into a black slab on a bright features card.
     */
    @Test
    void transparencyIsFlattenedOntoWhiteRatherThanBlack() {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 600, 400);
        // A smooth gradient over the left half only, so the picture is still too big to pass
        // through untouched and the right half stays fully transparent. Smooth rather than
        // noise: noise does not re-encode small, and the squeeze ladder is not what is on
        // trial here — what happens to the alpha channel is.
        for (int x = 0; x < 300; x++) {
            for (int y = 0; y < 400; y++) {
                img.setRGB(x, y, new Color(x * 255 / 300, y * 255 / 400, 128).getRGB());
            }
        }
        g.dispose();
        byte[] withAlpha = encode(img, "png");
        assertThat(withAlpha.length).isGreaterThan(CAP);

        BufferedImage out = decode(AnnouncementImageFitter.fit(withAlpha, "image/png", CAP).bytes());

        Color corner = new Color(out.getRGB(out.getWidth() - 2, out.getHeight() - 2));
        assertThat(corner.getRed()).isGreaterThan(200);
        assertThat(corner.getGreen()).isGreaterThan(200);
        assertThat(corner.getBlue()).isGreaterThan(200);
    }

    /**
     * A type the fitter has no business re-encoding passes straight through, so the refusal the
     * owner reads is still the one that names PNG and JPEG rather than a vaguer one from here.
     */
    @Test
    void anUnsupportedTypeIsLeftForTheTypeCheckToRefuse() {
        byte[] bytes = new byte[CAP + 1];

        Picture fitted = AnnouncementImageFitter.fit(bytes, "image/webp", CAP);

        assertThat(fitted.bytes()).isSameAs(bytes);
        assertThat(fitted.contentType()).isEqualTo("image/webp");
    }

    @Test
    void bytesThatAreNotAPictureAtAllAreRefused() {
        byte[] junk = new byte[CAP + 1];
        java.util.Arrays.fill(junk, (byte) 7);

        assertThatThrownBy(() -> AnnouncementImageFitter.fit(junk, "image/png", CAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be read");
    }

    /**
     * The cap cannot be met for every input, and when it cannot the owner needs the original
     * complaint — the size and the limit — not a claim that the bytes were unreadable.
     */
    @Test
    void aPictureThatCannotBeSqueezedFarEnoughStillReportsTheSize() {
        byte[] big = gradientPng(600, 400);

        assertThatThrownBy(() -> AnnouncementImageFitter.fit(big, "image/png", 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @Test
    void noPictureIsNotAProblem() {
        assertThat(AnnouncementImageFitter.fit(null, "image/png", CAP).bytes()).isNull();
        assertThat(AnnouncementImageFitter.fit(new byte[0], "image/png", CAP).bytes()).isEmpty();
    }
}
