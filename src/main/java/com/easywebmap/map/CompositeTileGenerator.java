package com.easywebmap.map;

import com.easywebmap.EasyWebMap;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Generates composite tiles at zoomed-out levels using raw pixel compositing.
 * Eliminates expensive PNG decode by caching raw RGB pixels.
 */
public class CompositeTileGenerator {
    private final EasyWebMap plugin;
    private final TileManager tileManager;
    private final ConcurrentHashMap<String, int[]> pixelCache;
    private static final int MAX_PIXEL_CACHE = 512;

    // Thread-local ImageWriter for fast PNG encoding
    private static final ThreadLocal<ImageWriter> PNG_WRITER = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        return writers.hasNext() ? writers.next() : null;
    });

    public CompositeTileGenerator(EasyWebMap plugin, TileManager tileManager) {
        this.plugin = plugin;
        this.tileManager = tileManager;
        this.pixelCache = new ConcurrentHashMap<>();
    }

    public int getChunksPerAxis(int zoom) {
        if (zoom >= 0) return 1;
        return 1 << (-zoom);
    }

    public CompletableFuture<byte[]> generateCompositeTile(String worldName, int zoom, int tileX, int tileZ) {
        if (zoom >= 0) {
            return this.tileManager.getBaseTile(worldName, tileX, tileZ);
        }

        int chunksPerAxis = getChunksPerAxis(zoom);
        int tileSize = this.plugin.getConfig().getTileSize();
        int baseChunkX = tileX * chunksPerAxis;
        int baseChunkZ = tileZ * chunksPerAxis;

        // Early bail-out: if no chunks in this area are explored, return empty tile immediately
        // This saves fetching 64+ base tiles for unexplored areas
        if (!this.tileManager.hasAnyExploredChunks(worldName, baseChunkX, baseChunkZ, chunksPerAxis)) {
            return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(tileSize));
        }

        // Fetch all base tiles with pixels in parallel
        List<CompletableFuture<TileWithPosition>> futures = new ArrayList<>();
        for (int dz = 0; dz < chunksPerAxis; dz++) {
            for (int dx = 0; dx < chunksPerAxis; dx++) {
                int chunkX = baseChunkX + dx;
                int chunkZ = baseChunkZ + dz;
                final int posX = dx;
                final int posZ = dz;

                CompletableFuture<TileWithPosition> tileFuture =
                    this.tileManager.getBaseTileWithPixels(worldName, chunkX, chunkZ)
                        .thenApply(data -> new TileWithPosition(data, posX, posZ));
                futures.add(tileFuture);
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<TileWithPosition> tiles = new ArrayList<>();
                for (CompletableFuture<TileWithPosition> future : futures) {
                    tiles.add(future.join());
                }
                return this.compositeFromPixels(tiles, chunksPerAxis, tileSize);
            });
    }

    /**
     * Composite using raw pixel arrays - no PNG decoding needed.
     */
    private byte[] compositeFromPixels(List<TileWithPosition> tiles, int chunksPerAxis, int outputSize) {
        int[] compositePixels = new int[outputSize * outputSize];
        int subTileSize = outputSize / chunksPerAxis;
        boolean hasAnyContent = false;

        for (TileWithPosition tile : tiles) {
            if (tile.data == null || tile.data.pixels == null) {
                continue;
            }

            int[] srcPixels = tile.data.pixels;
            int srcSize = tile.data.size;

            if (srcPixels.length == 0) continue;

            hasAnyContent = true;
            int destX = tile.posX * subTileSize;
            int destY = tile.posZ * subTileSize;

            // Scale and copy pixels directly
            float scale = (float) srcSize / subTileSize;
            for (int y = 0; y < subTileSize; y++) {
                int destRowStart = (destY + y) * outputSize + destX;
                int srcY = Math.min((int) (y * scale), srcSize - 1);
                int srcRowStart = srcY * srcSize;
                for (int x = 0; x < subTileSize; x++) {
                    int srcX = Math.min((int) (x * scale), srcSize - 1);
                    compositePixels[destRowStart + x] = srcPixels[srcRowStart + srcX];
                }
            }
        }

        if (!hasAnyContent) {
            return PngEncoder.encodeEmpty(outputSize);
        }

        // Use RGB (no alpha) - faster encoding
        BufferedImage composite = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_RGB);
        composite.setRGB(0, 0, outputSize, outputSize, compositePixels, 0, outputSize);

        return encodeFast(composite, outputSize);
    }

    /**
     * Fast PNG encoding with minimal compression for composites.
     */
    private byte[] encodeFast(BufferedImage image, int outputSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(outputSize * outputSize / 2);

        ImageWriter writer = PNG_WRITER.get();
        if (writer == null) {
            try {
                ImageIO.write(image, "png", out);
            } catch (IOException e) {
                return PngEncoder.encodeEmpty(outputSize);
            }
            return out.toByteArray();
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(1.0f); // Fastest
            }

            writer.write(null, new IIOImage(image, null, null), param);
            writer.reset();
        } catch (IOException e) {
            return PngEncoder.encodeEmpty(outputSize);
        }

        return out.toByteArray();
    }

    public void clearPixelCache() {
        this.pixelCache.clear();
    }

    public static class TileWithPosition {
        final PngEncoder.TileData data;
        final int posX;
        final int posZ;

        TileWithPosition(PngEncoder.TileData data, int posX, int posZ) {
            this.data = data;
            this.posX = posX;
            this.posZ = posZ;
        }
    }
}
