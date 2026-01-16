package com.easywebmap.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async disk cache for tiles - prevents blocking Netty event loop.
 */
public class DiskTileCache {
    private final Path cacheDirectory;
    private final ConcurrentHashMap<String, Long> tileTimestamps;
    private final ExecutorService diskExecutor;

    public DiskTileCache(Path dataDirectory) {
        this.cacheDirectory = dataDirectory.resolve("tilecache");
        this.tileTimestamps = new ConcurrentHashMap<>();
        // Increased from 2 to 6 threads for faster parallel disk reads during composite tile generation
        this.diskExecutor = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "EasyWebMap-DiskIO");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(this.cacheDirectory);
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to create tile cache directory: " + e.getMessage());
        }
    }

    /**
     * Synchronous get - for use in cached paths where blocking is acceptable.
     */
    public byte[] get(String worldName, int zoom, int x, int z) {
        Path tilePath = getTilePath(worldName, zoom, x, z);
        if (!Files.exists(tilePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(tilePath);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Async get - prevents blocking Netty threads.
     */
    public CompletableFuture<byte[]> getAsync(String worldName, int zoom, int x, int z) {
        return CompletableFuture.supplyAsync(() -> get(worldName, zoom, x, z), this.diskExecutor);
    }

    /**
     * Async put - fire and forget disk write.
     */
    public void putAsync(String worldName, int zoom, int x, int z, byte[] data) {
        String key = createKey(worldName, zoom, x, z);
        this.tileTimestamps.put(key, System.currentTimeMillis());
        this.diskExecutor.execute(() -> putSync(worldName, zoom, x, z, data));
    }

    /**
     * Synchronous put - for direct calls.
     */
    public void put(String worldName, int zoom, int x, int z, byte[] data) {
        putSync(worldName, zoom, x, z, data);
        String key = createKey(worldName, zoom, x, z);
        this.tileTimestamps.put(key, System.currentTimeMillis());
    }

    private void putSync(String worldName, int zoom, int x, int z, byte[] data) {
        Path tilePath = getTilePath(worldName, zoom, x, z);
        try {
            Files.createDirectories(tilePath.getParent());
            Files.write(tilePath, data);
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to cache tile: " + e.getMessage());
        }
    }

    public long getTileAge(String worldName, int zoom, int x, int z) {
        String key = createKey(worldName, zoom, x, z);
        Long cachedTimestamp = this.tileTimestamps.get(key);

        if (cachedTimestamp != null) {
            return System.currentTimeMillis() - cachedTimestamp;
        }

        Path tilePath = getTilePath(worldName, zoom, x, z);
        if (!Files.exists(tilePath)) {
            return Long.MAX_VALUE;
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(tilePath, BasicFileAttributes.class);
            long fileTime = attrs.lastModifiedTime().toMillis();
            this.tileTimestamps.put(key, fileTime);
            return System.currentTimeMillis() - fileTime;
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    public boolean exists(String worldName, int zoom, int x, int z) {
        return Files.exists(getTilePath(worldName, zoom, x, z));
    }

    public void clear() {
        this.tileTimestamps.clear();
        try {
            if (Files.exists(this.cacheDirectory)) {
                Files.walk(this.cacheDirectory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {}
                    });
                Files.createDirectories(this.cacheDirectory);
            }
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to clear tile cache: " + e.getMessage());
        }
    }

    public void clearWorld(String worldName) {
        Path worldDir = this.cacheDirectory.resolve(worldName);
        if (!Files.exists(worldDir)) {
            return;
        }
        try {
            Files.walk(worldDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to clear world cache: " + e.getMessage());
        }
        this.tileTimestamps.entrySet().removeIf(entry -> entry.getKey().startsWith(worldName + "/"));
    }

    public void shutdown() {
        this.diskExecutor.shutdown();
    }

    private Path getTilePath(String worldName, int zoom, int x, int z) {
        return this.cacheDirectory.resolve(worldName).resolve(String.valueOf(zoom))
                .resolve(x + "_" + z + ".png");
    }

    private String createKey(String worldName, int zoom, int x, int z) {
        return worldName + "/" + zoom + "/" + x + "/" + z;
    }

    public Path getCacheDirectory() {
        return this.cacheDirectory;
    }
}
