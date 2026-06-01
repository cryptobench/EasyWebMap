package com.easywebmap.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * High-performance PNG encoder with minimal compression for reduced CPU usage.
 */
public class PngEncoder {
    // Cache empty tiles by size - they're always identical
    private static final ConcurrentHashMap<Integer, byte[]> EMPTY_TILE_CACHE = new ConcurrentHashMap<>();

    // Thread-local ImageWriter to avoid repeated lookups
    private static final ThreadLocal<ImageWriter> PNG_WRITER = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        return writers.hasNext() ? writers.next() : null;
    });

    /**
     * Reconstruct a flat colour int[] (length width*height) from the 2026-05
     * MapImage wire format. The old flat {@code int[] data} field was removed;
     * pixels are now a {@code palette} indexed by {@code bitsPerIndex}-bit
     * indices bit-packed (LSB-first) into {@code packedIndices}.
     *
     * NOTE: the per-channel extraction in encode()/encodeWithPixels() still
     * treats each colour as 0xRRGGBBAA (the old data layout). If terrain renders
     * with swapped channels, the palette is 0xAARRGGBB — flip the shifts there
     * to (>>16,>>8,&0xFF). If tiles render as noise, the index bit-packing is
     * MSB-first — reverse the bit offset here.
     */
    private static int[] toArgb(MapImage img) {
        int count = img.width * img.height;
        int[] out = new int[count];
        int[] palette = img.palette;
        byte[] packed = img.packedIndices;
        int bits = img.bitsPerIndex & 0xFF;
        if (count <= 0 || palette == null || packed == null || bits <= 0) {
            return out; // nothing to draw
        }
        int mask = (1 << bits) - 1;
        for (int i = 0; i < count; i++) {
            int bitPos = i * bits;
            int byteIdx = bitPos >> 3;
            int bitOff = bitPos & 7;
            int raw = packed[byteIdx] & 0xFF;
            if (byteIdx + 1 < packed.length) raw |= (packed[byteIdx + 1] & 0xFF) << 8;
            if (byteIdx + 2 < packed.length) raw |= (packed[byteIdx + 2] & 0xFF) << 16;
            int idx = (raw >> bitOff) & mask;
            out[i] = (idx >= 0 && idx < palette.length) ? palette[idx] : 0;
        }
        return out;
    }

    public static byte[] encode(MapImage mapImage, int outputSize) {
        int srcWidth = mapImage.width;
        int srcHeight = mapImage.height;
        int[] srcData = toArgb(mapImage);

        // Pre-allocate output pixel array for bulk setRGB
        int[] destData = new int[outputSize * outputSize];
        float scaleX = (float) srcWidth / outputSize;
        float scaleY = (float) srcHeight / outputSize;

        // Batch process all pixels - convert RGBA to RGB (drop alpha, use opaque)
        for (int y = 0; y < outputSize; y++) {
            int destRowStart = y * outputSize;
            int srcY = Math.min((int) (y * scaleY), srcHeight - 1);
            int srcRowStart = srcY * srcWidth;
            for (int x = 0; x < outputSize; x++) {
                int srcX = Math.min((int) (x * scaleX), srcWidth - 1);
                int rgba = srcData[srcRowStart + srcX];
                // Convert RGBA to RGB (opaque)
                int r = (rgba >> 24) & 0xFF;
                int g = (rgba >> 16) & 0xFF;
                int b = (rgba >> 8) & 0xFF;
                destData[destRowStart + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        // ARGB: rendered pixels are forced opaque above; this keeps the format
        // consistent with composites/empty tiles so transparency works.
        BufferedImage buffered = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, outputSize, outputSize, destData, 0, outputSize);

        return encodeFast(buffered, outputSize);
    }

    /**
     * Encode MapImage and return both PNG bytes and raw RGB pixels for compositing.
     */
    public static TileData encodeWithPixels(MapImage mapImage, int outputSize) {
        int srcWidth = mapImage.width;
        int srcHeight = mapImage.height;
        int[] srcData = toArgb(mapImage);

        int[] destData = new int[outputSize * outputSize];
        float scaleX = (float) srcWidth / outputSize;
        float scaleY = (float) srcHeight / outputSize;

        for (int y = 0; y < outputSize; y++) {
            int destRowStart = y * outputSize;
            int srcY = Math.min((int) (y * scaleY), srcHeight - 1);
            int srcRowStart = srcY * srcWidth;
            for (int x = 0; x < outputSize; x++) {
                int srcX = Math.min((int) (x * scaleX), srcWidth - 1);
                int rgba = srcData[srcRowStart + srcX];
                int r = (rgba >> 24) & 0xFF;
                int g = (rgba >> 16) & 0xFF;
                int b = (rgba >> 8) & 0xFF;
                destData[destRowStart + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage buffered = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, outputSize, outputSize, destData, 0, outputSize);

        byte[] pngBytes = encodeFast(buffered, outputSize);
        return new TileData(pngBytes, destData, outputSize);
    }

    /**
     * Fast PNG encoding with minimal compression.
     * Uses compression level 1 (fastest) instead of default ~6.
     */
    private static byte[] encodeFast(BufferedImage image, int outputSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(outputSize * outputSize / 2);

        ImageWriter writer = PNG_WRITER.get();
        if (writer == null) {
            // Fallback to standard ImageIO
            try {
                ImageIO.write(image, "png", out);
            } catch (IOException e) {
                return new byte[0];
            }
            return out.toByteArray();
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                // Compression quality 1.0 = fastest (least compression)
                // This is counterintuitive but for PNG: 1.0 = less compression = faster
                param.setCompressionQuality(1.0f);
            }

            writer.write(null, new IIOImage(image, null, null), param);
            writer.reset();
        } catch (IOException e) {
            return new byte[0];
        }

        return out.toByteArray();
    }

    /**
     * Get cached empty tile - generates once per size, reuses forever.
     */
    public static byte[] encodeEmpty(int size) {
        return EMPTY_TILE_CACHE.computeIfAbsent(size, s -> {
            BufferedImage buffered = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            return encodeFast(buffered, s);
        });
    }

    /**
     * True if {@code png} is byte-identical to the blank placeholder for
     * {@code size} (i.e. an empty/unrendered/black tile). This is the reliable
     * empty check: a byte-length threshold does NOT work because minimal PNG
     * compression (quality 1.0) makes even a solid-black tile ~197 KB.
     */
    public static boolean isEmptyTile(byte[] png, int size) {
        return png != null && java.util.Arrays.equals(png, encodeEmpty(size));
    }

    public static class TileData {
        public final byte[] pngBytes;
        public final int[] pixels;
        public final int size;

        public TileData(byte[] pngBytes, int[] pixels, int size) {
            this.pngBytes = pngBytes;
            this.pixels = pixels;
            this.size = size;
        }

        public boolean isEmpty() {
            // The empty/black-placeholder path sets pixels to an empty array.
            // Byte length is unreliable here: minimal PNG compression makes even
            // a solid-black tile ~197 KB, so length never flags it as empty.
            return pngBytes == null || pixels == null || pixels.length == 0;
        }
    }
}
