package com.easywebmap.map;

import com.easywebmap.EasyWebMap;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class TileManager {
    private final EasyWebMap plugin;
    private final TileCache memoryCache;
    private final DiskTileCache diskCache;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests;
    private final ConcurrentHashMap<String, CompletableFuture<PngEncoder.TileData>> pendingPixelRequests;
    private final ConcurrentHashMap<String, CachedChunkIndexes> chunkIndexCache;
    private final ConcurrentHashMap<String, PngEncoder.TileData> pixelCache;
    private CompositeTileGenerator compositeTileGenerator;
    // Increased from 512 to 2048 to prevent cache thrashing at negative zoom levels
    // (A single zoom -4 tile requires 256 base tile pixels)
    private static final int MAX_PIXEL_CACHE = 2048;
    // Limit concurrent tile generations to prevent CPU spikes
    // Increased from 4 to 16 for faster composite tile generation at negative zoom levels
    private static final int MAX_CONCURRENT_GENERATIONS = 16;
    private final Semaphore generationSemaphore = new Semaphore(MAX_CONCURRENT_GENERATIONS);
    // Empty tiles are ~270 bytes, real tiles are 10KB+
    private static final int EMPTY_TILE_THRESHOLD = 500;

    public TileManager(EasyWebMap plugin) {
        this.plugin = plugin;
        this.memoryCache = new TileCache(plugin.getConfig().getTileCacheSize());
        this.diskCache = new DiskTileCache(plugin.getDataDirectory());
        this.pendingRequests = new ConcurrentHashMap<>();
        this.pendingPixelRequests = new ConcurrentHashMap<>();
        this.chunkIndexCache = new ConcurrentHashMap<>();
        this.pixelCache = new ConcurrentHashMap<>();
        this.compositeTileGenerator = new CompositeTileGenerator(plugin, this);
    }

    public CompletableFuture<byte[]> getTile(String worldName, int zoom, int tileX, int tileZ) {
        // Route negative zoom levels to composite tile generator
        if (zoom < 0 && this.plugin.getConfig().isEnableTilePyramids()) {
            return this.getCompositeTile(worldName, zoom, tileX, tileZ);
        }

        // For zoom >= 0, use base tile logic
        return this.getBaseTile(worldName, tileX, tileZ);
    }

    /**
     * Get a composite tile at a negative zoom level.
     * Composite tiles combine multiple base tiles into one.
     */
    private CompletableFuture<byte[]> getCompositeTile(String worldName, int zoom, int tileX, int tileZ) {
        String cacheKey = TileCache.createKey(worldName, zoom, tileX, tileZ);

        // 1. Check memory cache first
        byte[] memoryCached = this.memoryCache.get(cacheKey);
        if (memoryCached != null) {
            return CompletableFuture.completedFuture(memoryCached);
        }

        // 2. Check if already generating
        CompletableFuture<byte[]> pending = this.pendingRequests.get(cacheKey);
        if (pending != null) {
            return pending;
        }

        // 3. Check disk cache
        if (this.plugin.getConfig().isUseDiskCache()) {
            byte[] diskCached = this.diskCache.get(worldName, zoom, tileX, tileZ);
            if (diskCached != null) {
                long tileAge = this.diskCache.getTileAge(worldName, zoom, tileX, tileZ);
                // Use longer refresh interval for composite tiles (they're more expensive)
                long refreshInterval = this.plugin.getConfig().getTileRefreshIntervalMs() * 2;

                if (tileAge < refreshInterval) {
                    this.memoryCache.put(cacheKey, diskCached);
                    return CompletableFuture.completedFuture(diskCached);
                }

                // Check if any players are in the area covered by this composite tile
                int chunksPerAxis = this.compositeTileGenerator.getChunksPerAxis(zoom);
                int baseChunkX = tileX * chunksPerAxis;
                int baseChunkZ = tileZ * chunksPerAxis;
                World world = Universe.get().getWorld(worldName);

                if (world == null || !this.arePlayersInArea(world, baseChunkX, baseChunkZ, chunksPerAxis)) {
                    this.memoryCache.put(cacheKey, diskCached);
                    return CompletableFuture.completedFuture(diskCached);
                }
            }
        }

        // 4. Generate composite tile
        CompletableFuture<byte[]> future = this.compositeTileGenerator.generateCompositeTile(worldName, zoom, tileX, tileZ);
        this.pendingRequests.put(cacheKey, future);
        future.whenComplete((data, ex) -> {
            this.pendingRequests.remove(cacheKey);
            if (data != null && data.length > EMPTY_TILE_THRESHOLD && ex == null) {
                this.memoryCache.put(cacheKey, data);
                if (this.plugin.getConfig().isUseDiskCache()) {
                    this.diskCache.putAsync(worldName, zoom, tileX, tileZ, data);
                }
            }
        });
        return future;
    }

    /**
     * Get a base tile (zoom level 0) for a single chunk.
     * This is called by the composite tile generator.
     */
    public CompletableFuture<byte[]> getBaseTile(String worldName, int tileX, int tileZ) {
        String cacheKey = TileCache.createKey(worldName, 0, tileX, tileZ);

        // 1. Check memory cache first (fastest)
        byte[] memoryCached = this.memoryCache.get(cacheKey);
        if (memoryCached != null) {
            return CompletableFuture.completedFuture(memoryCached);
        }

        // 2. Check if already generating
        CompletableFuture<byte[]> pending = this.pendingRequests.get(cacheKey);
        if (pending != null) {
            return pending;
        }

        // 3. Check disk cache if enabled
        if (this.plugin.getConfig().isUseDiskCache()) {
            byte[] diskCached = this.diskCache.get(worldName, 0, tileX, tileZ);
            if (diskCached != null) {
                long tileAge = this.diskCache.getTileAge(worldName, 0, tileX, tileZ);
                long refreshInterval = this.plugin.getConfig().getTileRefreshIntervalMs();

                // If tile is fresh enough, serve from disk
                if (tileAge < refreshInterval) {
                    this.memoryCache.put(cacheKey, diskCached);
                    return CompletableFuture.completedFuture(diskCached);
                }

                // Tile is old - check if players are nearby
                World world = Universe.get().getWorld(worldName);
                if (world == null || !this.arePlayersNearby(world, tileX, tileZ)) {
                    // No players nearby - terrain can't have changed, serve cached
                    this.memoryCache.put(cacheKey, diskCached);
                    return CompletableFuture.completedFuture(diskCached);
                }

                // Players nearby and tile is old - regenerate
            }
        }

        // 4. Generate new tile
        CompletableFuture<byte[]> future = this.generateTile(worldName, 0, tileX, tileZ);
        this.pendingRequests.put(cacheKey, future);
        future.whenComplete((data, ex) -> {
            this.pendingRequests.remove(cacheKey);
            // Don't cache empty tiles - they should regenerate when chunk gets explored
            if (data != null && data.length > EMPTY_TILE_THRESHOLD && ex == null) {
                this.memoryCache.put(cacheKey, data);
                if (this.plugin.getConfig().isUseDiskCache()) {
                    this.diskCache.putAsync(worldName, 0, tileX, tileZ, data);
                }
            }
        });
        return future;
    }

    /**
     * Get a base tile with raw pixels for compositing.
     * Returns TileData containing both PNG bytes and raw ARGB pixels.
     */
    public CompletableFuture<PngEncoder.TileData> getBaseTileWithPixels(String worldName, int tileX, int tileZ) {
        String cacheKey = TileCache.createKey(worldName, 0, tileX, tileZ);
        int tileSize = this.plugin.getConfig().getTileSize();

        // 1. Check pixel cache
        PngEncoder.TileData cached = this.pixelCache.get(cacheKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        // 2. Check pending
        CompletableFuture<PngEncoder.TileData> pending = this.pendingPixelRequests.get(cacheKey);
        if (pending != null) {
            return pending;
        }

        // 3. Check disk cache and decode PNG to pixels (faster than regenerating)
        if (this.plugin.getConfig().isUseDiskCache()) {
            byte[] diskCached = this.diskCache.get(worldName, 0, tileX, tileZ);
            if (diskCached != null && diskCached.length > EMPTY_TILE_THRESHOLD) {
                try {
                    int[] pixels = PngDecoder.decode(diskCached, tileSize);
                    if (pixels != null) {
                        PngEncoder.TileData tileData = new PngEncoder.TileData(diskCached, pixels, tileSize);
                        // Cache the pixels for future compositing
                        if (this.pixelCache.size() < MAX_PIXEL_CACHE) {
                            this.pixelCache.put(cacheKey, tileData);
                        }
                        return CompletableFuture.completedFuture(tileData);
                    }
                } catch (Exception e) {
                    // Failed to decode, will regenerate
                }
            }
        }

        // 4. Generate with pixels
        CompletableFuture<PngEncoder.TileData> future = this.generateTileWithPixels(worldName, tileX, tileZ);
        this.pendingPixelRequests.put(cacheKey, future);
        future.whenComplete((data, ex) -> {
            this.pendingPixelRequests.remove(cacheKey);
            // Don't cache empty tiles - they should regenerate when chunk gets explored
            if (data != null && !data.isEmpty() && data.pngBytes.length > EMPTY_TILE_THRESHOLD && ex == null) {
                // Cache pixels for compositing, evict if too many
                if (this.pixelCache.size() < MAX_PIXEL_CACHE) {
                    this.pixelCache.put(cacheKey, data);
                }
                // Also cache PNG bytes
                this.memoryCache.put(cacheKey, data.pngBytes);
                if (this.plugin.getConfig().isUseDiskCache()) {
                    this.diskCache.putAsync(worldName, 0, tileX, tileZ, data.pngBytes);
                }
            }
        });
        return future;
    }

    private CompletableFuture<PngEncoder.TileData> generateTileWithPixels(String worldName, int tileX, int tileZ) {
        World world = Universe.get().getWorld(worldName);
        int tileSize = this.plugin.getConfig().getTileSize();
        if (world == null) {
            return CompletableFuture.completedFuture(new PngEncoder.TileData(
                PngEncoder.encodeEmpty(tileSize), new int[0], tileSize));
        }

        if (this.plugin.getConfig().isRenderExploredChunksOnly()) {
            if (!this.isChunkExplored(world, tileX, tileZ)) {
                return CompletableFuture.completedFuture(new PngEncoder.TileData(
                    PngEncoder.encodeEmpty(tileSize), new int[0], tileSize));
            }
        }

        WorldMapManager mapManager = world.getWorldMapManager();

        // Acquire semaphore to limit concurrent generations
        try {
            this.generationSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(new PngEncoder.TileData(
                PngEncoder.encodeEmpty(tileSize), new int[0], tileSize));
        }

        return mapManager.getImageAsync(tileX, tileZ)
                .thenApply(mapImage -> {
                    if (mapImage == null) {
                        return new PngEncoder.TileData(PngEncoder.encodeEmpty(tileSize), new int[0], tileSize);
                    }
                    return PngEncoder.encodeWithPixels(mapImage, tileSize);
                })
                .exceptionally(ex -> {
                    System.err.println("[EasyWebMap] Failed to generate tile: " + ex.getMessage());
                    return new PngEncoder.TileData(PngEncoder.encodeEmpty(tileSize), new int[0], tileSize);
                })
                .whenComplete((result, ex) -> this.generationSemaphore.release());
    }

    private boolean arePlayersNearby(World world, int tileX, int tileZ) {
        int radius = this.plugin.getConfig().getTileRefreshRadius();

        for (PlayerRef playerRef : world.getPlayerRefs()) {
            try {
                Transform transform = playerRef.getTransform();
                if (transform == null) continue;

                Vector3d pos = transform.getPosition();
                int playerChunkX = ChunkUtil.chunkCoordinate((int) pos.x);
                int playerChunkZ = ChunkUtil.chunkCoordinate((int) pos.z);

                int dx = Math.abs(playerChunkX - tileX);
                int dz = Math.abs(playerChunkZ - tileZ);

                if (dx <= radius && dz <= radius) {
                    return true;
                }
            } catch (Exception e) {
                // Skip this player
            }
        }
        return false;
    }

    /**
     * Check if any players are within the area covered by a composite tile.
     */
    private boolean arePlayersInArea(World world, int baseChunkX, int baseChunkZ, int chunksPerAxis) {
        int radius = this.plugin.getConfig().getTileRefreshRadius();

        for (PlayerRef playerRef : world.getPlayerRefs()) {
            try {
                Transform transform = playerRef.getTransform();
                if (transform == null) continue;

                Vector3d pos = transform.getPosition();
                int playerChunkX = ChunkUtil.chunkCoordinate((int) pos.x);
                int playerChunkZ = ChunkUtil.chunkCoordinate((int) pos.z);

                // Check if player is within the area (with buffer for refresh radius)
                if (playerChunkX >= baseChunkX - radius &&
                    playerChunkX < baseChunkX + chunksPerAxis + radius &&
                    playerChunkZ >= baseChunkZ - radius &&
                    playerChunkZ < baseChunkZ + chunksPerAxis + radius) {
                    return true;
                }
            } catch (Exception e) {
                // Skip this player
            }
        }
        return false;
    }

    private CompletableFuture<byte[]> generateTile(String worldName, int zoom, int tileX, int tileZ) {
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize()));
        }

        // Check if we should only render explored chunks
        if (this.plugin.getConfig().isRenderExploredChunksOnly()) {
            if (!this.isChunkExplored(world, tileX, tileZ)) {
                return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize()));
            }
        }

        WorldMapManager mapManager = world.getWorldMapManager();

        // Acquire semaphore to limit concurrent generations
        try {
            this.generationSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize()));
        }

        return mapManager.getImageAsync(tileX, tileZ)
                .thenApply(mapImage -> {
                    if (mapImage == null) {
                        return PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize());
                    }
                    return PngEncoder.encode(mapImage, this.plugin.getConfig().getTileSize());
                })
                .exceptionally(ex -> {
                    System.err.println("[EasyWebMap] Failed to generate tile: " + ex.getMessage());
                    return PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize());
                })
                .whenComplete((result, ex) -> this.generationSemaphore.release());
    }

    private boolean isChunkExplored(World world, int chunkX, int chunkZ) {
        try {
            LongSet indexes = this.getCachedChunkIndexes(world);
            if (indexes == null) {
                return true; // Fail open if we can't get indexes
            }
            long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
            return indexes.contains(chunkIndex);
        } catch (Exception e) {
            // If we can't check, allow rendering (fail open for usability)
            return true;
        }
    }

    /**
     * Check if any chunks in the given area are explored.
     * Used for early bail-out on composite tiles over unexplored areas.
     */
    public boolean hasAnyExploredChunks(String worldName, int baseChunkX, int baseChunkZ, int chunksPerAxis) {
        if (!this.plugin.getConfig().isRenderExploredChunksOnly()) {
            return true; // If we're not filtering, assume there's content
        }

        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return false;
        }

        try {
            LongSet indexes = this.getCachedChunkIndexes(world);
            if (indexes == null) {
                return true; // Fail open
            }

            // Quick sampling - check corners and center first
            int[][] samplePoints = {
                {0, 0}, // top-left
                {chunksPerAxis - 1, 0}, // top-right
                {0, chunksPerAxis - 1}, // bottom-left
                {chunksPerAxis - 1, chunksPerAxis - 1}, // bottom-right
                {chunksPerAxis / 2, chunksPerAxis / 2} // center
            };

            for (int[] point : samplePoints) {
                long idx = ChunkUtil.indexChunk(baseChunkX + point[0], baseChunkZ + point[1]);
                if (indexes.contains(idx)) {
                    return true;
                }
            }

            // If samples show nothing, do a full scan (but this is rare)
            for (int dz = 0; dz < chunksPerAxis; dz++) {
                for (int dx = 0; dx < chunksPerAxis; dx++) {
                    long idx = ChunkUtil.indexChunk(baseChunkX + dx, baseChunkZ + dz);
                    if (indexes.contains(idx)) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            return true; // Fail open
        }
    }

    private LongSet getCachedChunkIndexes(World world) {
        String worldName = world.getName();
        CachedChunkIndexes cached = this.chunkIndexCache.get(worldName);
        long now = System.currentTimeMillis();

        if (cached != null && (now - cached.timestamp) < this.plugin.getConfig().getChunkIndexCacheMs()) {
            return cached.indexes;
        }

        // Refresh the cache
        try {
            ChunkStore chunkStore = world.getChunkStore();
            IChunkLoader loader = chunkStore.getLoader();
            if (loader == null) {
                return null;
            }
            LongSet indexes = loader.getIndexes();
            this.chunkIndexCache.put(worldName, new CachedChunkIndexes(indexes, now));
            return indexes;
        } catch (Exception e) {
            return cached != null ? cached.indexes : null;
        }
    }

    public CompletableFuture<Integer> pregenerateTiles(String worldName, int centerX, int centerZ, int radius) {
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(0);
        }

        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    // Skip if already cached on disk
                    if (this.diskCache.exists(worldName, 0, x, z)) {
                        continue;
                    }

                    // Skip unexplored chunks
                    if (this.plugin.getConfig().isRenderExploredChunksOnly()) {
                        if (!this.isChunkExplored(world, x, z)) {
                            continue;
                        }
                    }

                    try {
                        // Generate and wait
                        byte[] tile = this.generateTile(worldName, 0, x, z).join();
                        if (tile != null && tile.length > 100) {
                            this.diskCache.put(worldName, 0, x, z, tile);
                            count++;
                        }
                        // Small delay to not overload
                        Thread.sleep(50);
                    } catch (Exception e) {
                        // Continue with next tile
                    }
                }
            }
            return count;
        });
    }

    public void clearCache() {
        this.memoryCache.clear();
        this.pixelCache.clear();
        this.diskCache.clear();
        this.chunkIndexCache.clear();
    }

    public void clearMemoryCache() {
        this.memoryCache.clear();
        this.pixelCache.clear();
    }

    public void shutdown() {
        this.diskCache.shutdown();
    }

    public int getMemoryCacheSize() {
        return this.memoryCache.size();
    }

    public DiskTileCache getDiskCache() {
        return this.diskCache;
    }

    private static class CachedChunkIndexes {
        final LongSet indexes;
        final long timestamp;

        CachedChunkIndexes(LongSet indexes, long timestamp) {
            this.indexes = indexes;
            this.timestamp = timestamp;
        }
    }
}
