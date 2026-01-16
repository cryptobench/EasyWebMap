package com.easywebmap.map;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/**
 * Decodes PNG bytes back to raw pixel arrays for compositing.
 * This is faster than regenerating tiles from WorldMapManager when
 * tiles are already cached on disk.
 */
public class PngDecoder {

    /**
     * Decode PNG bytes to RGB pixel array.
     *
     * @param pngBytes The PNG image data
     * @param expectedSize The expected width/height of the image
     * @return RGB pixel array, or null if decoding fails
     */
    public static int[] decode(byte[] pngBytes, int expectedSize) {
        if (pngBytes == null || pngBytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(pngBytes)) {
            BufferedImage image = ImageIO.read(bais);
            if (image == null) {
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            // Ensure expected dimensions
            if (width != expectedSize || height != expectedSize) {
                // Scale if needed (shouldn't normally happen)
                BufferedImage scaled = new BufferedImage(expectedSize, expectedSize, BufferedImage.TYPE_INT_RGB);
                scaled.getGraphics().drawImage(image, 0, 0, expectedSize, expectedSize, null);
                image = scaled;
                width = expectedSize;
                height = expectedSize;
            }

            // Extract pixels
            int[] pixels = new int[width * height];
            image.getRGB(0, 0, width, height, pixels, 0, width);

            return pixels;
        } catch (Exception e) {
            return null;
        }
    }
}
