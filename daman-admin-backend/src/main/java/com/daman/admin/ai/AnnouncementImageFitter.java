package com.daman.admin.ai;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Squeezes a drawn picture into the size a till will actually download.
 *
 * <p><b>Why this exists.</b> The 512 KB cap in {@code AnnouncementService} is not an arbitrary
 * number to be raised when something does not fit: the picture travels inside the admin database
 * and then down to every shop's features page, several of them on a phone line. A drawing model,
 * meanwhile, returns a PNG of whatever size it liked — 834 KB for the first real one — and by
 * then the call has been billed. Refusing it costs money and gives the owner nothing. So the
 * bytes are re-encoded to fit, and only genuinely unusable ones are refused.
 *
 * <p>PNG is the wrong container for this picture anyway. These are illustrations destined for a
 * card on a features page; JPEG at a high quality is visually indistinguishable from the PNG and
 * a fraction of the size. The ladder below drops quality first and only then resolution, because
 * a slightly softer picture reads better on a small screen than a sharp smaller one scaled up.
 *
 * <p>Only PNG and JPEG are touched. Anything else passes straight through so that the refusal
 * the owner reads is still the one naming the two types we accept, rather than a vaguer
 * complaint from in here about bytes that could not be decoded.
 */
@Slf4j
public final class AnnouncementImageFitter {

    private AnnouncementImageFitter() {}

    private static final Set<String> REFITTABLE = Set.of("image/png", "image/jpeg");

    /**
     * Tried in order, first result that fits wins. Quality falls before width because softness
     * costs less here than pixels: this picture is shown a few hundred CSS pixels wide.
     */
    private static final float[] QUALITIES = {0.85f, 0.75f, 0.65f, 0.55f, 0.45f};
    private static final int[] WIDTHS = {1600, 1280, 1024, 800, 640};

    /**
     * The same picture when it already fits, a re-encoded one when it does not.
     *
     * @throws IllegalArgumentException if the bytes cannot be decoded, or cannot be squeezed
     *                                  down to {@code maxBytes} — the message keeps the original
     *                                  size-and-limit wording so the owner reads the real reason
     */
    public static Picture fit(byte[] bytes, String contentType, int maxBytes) {
        if (bytes == null || bytes.length == 0) return new Picture(bytes, contentType);
        if (bytes.length <= maxBytes) return new Picture(bytes, contentType);

        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!REFITTABLE.contains(type)) return new Picture(bytes, contentType);

        BufferedImage source = read(bytes);
        BufferedImage opaque = flattenOntoWhite(source);

        int lastWidth = Integer.MAX_VALUE;
        for (int step : WIDTHS) {
            // A picture already narrower than this step is not widened, and a step that repeats
            // the previous one is skipped rather than re-encoded to the same bytes twice.
            int width = Math.min(step, opaque.getWidth());
            if (width >= lastWidth) continue;
            lastWidth = width;

            BufferedImage scaled = width == opaque.getWidth() ? opaque : scaleToWidth(opaque, width);
            for (float quality : QUALITIES) {
                byte[] jpeg = toJpeg(scaled, quality);
                if (jpeg.length <= maxBytes) {
                    log.info("Announcement image refitted: {} bytes {} -> {} bytes JPEG {}x{} q{}",
                            bytes.length, type, jpeg.length, scaled.getWidth(), scaled.getHeight(), quality);
                    return new Picture(jpeg, "image/jpeg");
                }
            }
        }

        throw new IllegalArgumentException(
                "image is " + bytes.length + " bytes and could not be squeezed under the limit of "
                        + maxBytes);
    }

    /**
     * Widest edge we are willing to hold in memory, before any scaling.
     *
     * <p>A phone camera hands over 4000×3000 and up, and {@code ImageIO.read} would decode every
     * pixel into an ARGB buffer — 48 MB for a 12-megapixel photo, on a backend tuned to a few
     * hundred MB of heap. Nothing above {@link #WIDTHS}' largest entry survives the squeeze
     * anyway, so decoding more than twice that is work thrown away AND the thing most likely to
     * take the server down. Twice, not exactly: subsampling is a crude nearest-neighbour drop,
     * and leaving headroom lets the proper scaler below do the quality-sensitive step.
     */
    private static final int DECODE_MAX_EDGE = 3200;

    /**
     * Decodes the picture, subsampling anything enormous as it reads.
     *
     * <p>{@code setSourceSubsampling} makes the decoder skip pixels while reading rather than
     * after, so memory is bounded by the OUTPUT size whatever arrives — which is what makes it
     * safe to accept a photo straight off a phone.
     */
    private static BufferedImage read(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) throw new IllegalArgumentException("the picture could not be read");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                // ImageIO offers no reader rather than throwing when nothing recognises the bytes.
                throw new IllegalArgumentException("the picture could not be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int step = subsamplingFor(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                    log.debug("Decoding a {}x{} picture at every {}th pixel",
                            reader.getWidth(0), reader.getHeight(0), step);
                }
                BufferedImage img = reader.read(0, param);
                if (img == null) throw new IllegalArgumentException("the picture could not be read");
                return img;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("the picture could not be read", e);
        }
    }

    /**
     * How many pixels to step while decoding: 1 for anything reasonable, then powers of two.
     *
     * <p>Powers of two because that is what decoders handle cleanly, and because halving twice
     * is a predictable amount of work rather than an arbitrary ratio.
     */
    static int subsamplingFor(int width, int height) {
        int longest = Math.max(width, height);
        int step = 1;
        while (longest / step > DECODE_MAX_EDGE) step *= 2;
        return step;
    }

    /**
     * JPEG carries no alpha channel. Handing it an ARGB image drops the channel silently and
     * every transparent pixel comes out black, which turns a cut-out into a black slab on a
     * bright card — so transparency is composited onto white first, deliberately.
     */
    private static BufferedImage flattenOntoWhite(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB && !source.getColorModel().hasAlpha()) {
            return source;
        }
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaleToWidth(BufferedImage source, int width) {
        int height = Math.max(1, Math.round(source.getHeight() * (width / (float) source.getWidth())));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    private static byte[] toJpeg(BufferedImage img, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // No JPEG writer means no JVM we support; say so rather than returning the original
            // and letting the size check reject it with a misleading reason.
            throw new IllegalArgumentException("the picture could not be re-encoded on this server");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(img, null, null), params);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("the picture could not be re-encoded", e);
        } finally {
            writer.dispose();
        }
    }
}
