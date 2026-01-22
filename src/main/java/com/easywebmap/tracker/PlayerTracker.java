package com.easywebmap.tracker;

import com.easywebmap.EasyWebMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerTracker {
    private static final Gson GSON = new GsonBuilder().create();
    private final EasyWebMap plugin;
    private final Set<Channel> connectedChannels;
    private ScheduledExecutorService scheduler;

    // Thread-safe cache of player chunk positions per world
    // Updated from world threads, read from any thread
    // Map: worldName -> Map<playerUuid, int[]{chunkX, chunkZ}>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, int[]>> playerChunkCache = new ConcurrentHashMap<>();

    public PlayerTracker(EasyWebMap plugin) {
        this.plugin = plugin;
        this.connectedChannels = ConcurrentHashMap.newKeySet();
    }

    /**
     * Gets cached player chunk positions for a world.
     * Thread-safe - can be called from any thread.
     */
    public Map<String, int[]> getPlayerChunksInWorld(String worldName) {
        ConcurrentHashMap<String, int[]> worldCache = this.playerChunkCache.get(worldName);
        if (worldCache == null) {
            return new HashMap<>();
        }
        return new HashMap<>(worldCache);
    }

    /**
     * Checks if any player is within radius of the given chunk.
     * Thread-safe - reads from cached positions.
     */
    public boolean isPlayerNearChunk(String worldName, int chunkX, int chunkZ, int radius) {
        Map<String, int[]> players = this.getPlayerChunksInWorld(worldName);
        for (int[] pos : players.values()) {
            int dx = Math.abs(pos[0] - chunkX);
            int dz = Math.abs(pos[1] - chunkZ);
            if (dx <= radius && dz <= radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if any player is within the given chunk area.
     * Thread-safe - reads from cached positions.
     */
    public boolean isPlayerInArea(String worldName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, int buffer) {
        Map<String, int[]> players = this.getPlayerChunksInWorld(worldName);
        for (int[] pos : players.values()) {
            if (pos[0] >= minChunkX - buffer && pos[0] <= maxChunkX + buffer &&
                pos[1] >= minChunkZ - buffer && pos[1] <= maxChunkZ + buffer) {
                return true;
            }
        }
        return false;
    }

    public void start() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "easywebmap-tracker");
            t.setDaemon(true);
            return t;
        });
        int intervalMs = this.plugin.getConfig().getUpdateIntervalMs();
        this.scheduler.scheduleAtFixedRate(this::broadcastPlayerPositions, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
            try {
                this.scheduler.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Channel channel : this.connectedChannels) {
            channel.close();
        }
        this.connectedChannels.clear();
    }

    public void addChannel(Channel channel) {
        this.connectedChannels.add(channel);
    }

    public void removeChannel(Channel channel) {
        this.connectedChannels.remove(channel);
    }

    public int getConnectionCount() {
        return this.connectedChannels.size();
    }

    /**
     * Cleans up caches for worlds that no longer exist.
     * Should be called periodically to prevent memory leaks.
     */
    public void cleanupStaleWorldCaches() {
        java.util.Set<String> activeWorlds = new java.util.HashSet<>();
        for (World world : Universe.get().getWorlds().values()) {
            activeWorlds.add(world.getName());
        }
        this.playerChunkCache.keySet().removeIf(worldName -> !activeWorlds.contains(worldName));
    }

    /**
     * Clears cache for a specific world.
     */
    public void clearWorldCache(String worldName) {
        this.playerChunkCache.remove(worldName);
    }

    private void broadcastPlayerPositions() {
        if (this.connectedChannels.isEmpty()) {
            return;
        }

        // Collect futures for each world's player data
        // We must access player transforms on each world's thread (thread-bound ECS data)
        Map<String, CompletableFuture<List<Map<String, Object>>>> worldFutures = new HashMap<>();

        for (World world : Universe.get().getWorlds().values()) {
            if (!this.plugin.getConfig().isWorldEnabled(world.getName())) {
                continue;
            }

            CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
            worldFutures.put(world.getName(), future);

            // Execute on the world's thread to safely access player transforms
            world.execute(() -> {
                try {
                    List<Map<String, Object>> players = this.getPlayersInWorldOnWorldThread(world);
                    future.complete(players);
                } catch (Exception e) {
                    future.complete(new ArrayList<>());
                }
            });

            // Add timeout to prevent memory leaks if world.execute() never runs
            // (e.g., world unloaded between check and execute)
            future.completeOnTimeout(new ArrayList<>(), 1, TimeUnit.SECONDS);
        }

        if (worldFutures.isEmpty()) {
            return;
        }

        // Wait for all worlds to respond, then broadcast
        // Use allOf to avoid blocking on individual futures
        CompletableFuture.allOf(worldFutures.values().toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                Map<String, List<Map<String, Object>>> worldPlayers = new HashMap<>();
                for (Map.Entry<String, CompletableFuture<List<Map<String, Object>>>> entry : worldFutures.entrySet()) {
                    try {
                        List<Map<String, Object>> players = entry.getValue().getNow(new ArrayList<>());
                        if (!players.isEmpty()) {
                            worldPlayers.put(entry.getKey(), players);
                        }
                    } catch (Exception e) {
                        // Skip this world
                    }
                }

                if (worldPlayers.isEmpty()) {
                    return;
                }

                Map<String, Object> message = new HashMap<>();
                message.put("type", "players");
                message.put("timestamp", System.currentTimeMillis());
                message.put("worlds", worldPlayers);
                String json = GSON.toJson(message);
                TextWebSocketFrame frame = new TextWebSocketFrame(json);
                for (Channel channel : this.connectedChannels) {
                    if (channel.isActive()) {
                        channel.writeAndFlush(frame.retainedDuplicate());
                    }
                }
                frame.release();
            });
    }

    /**
     * Gets player data from a world. MUST be called on the world's thread.
     * Accesses thread-bound ECS data (player transforms).
     * Also updates the player chunk cache for use by TileManager.
     */
    private List<Map<String, Object>> getPlayersInWorldOnWorldThread(World world) {
        List<Map<String, Object>> players = new ArrayList<>();
        String worldName = world.getName();

        // Prepare new cache for this world
        ConcurrentHashMap<String, int[]> newChunkCache = new ConcurrentHashMap<>();

        for (PlayerRef playerRef : world.getPlayerRefs()) {
            try {
                Transform transform = playerRef.getTransform();
                if (transform != null) {
                    Vector3d pos = transform.getPosition();
                    Vector3f rot = transform.getRotation();
                    Map<String, Object> playerData = new HashMap<>();
                    playerData.put("name", playerRef.getUsername());
                    playerData.put("uuid", playerRef.getUuid().toString());
                    playerData.put("x", pos.x);
                    playerData.put("y", pos.y);
                    playerData.put("z", pos.z);
                    playerData.put("yaw", rot != null ? rot.y : 0f);
                    players.add(playerData);

                    // Update chunk cache for TileManager
                    int chunkX = com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate((int) pos.x);
                    int chunkZ = com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate((int) pos.z);
                    newChunkCache.put(playerRef.getUuid().toString(), new int[]{chunkX, chunkZ});
                }
            } catch (Exception e) {
                // Player may have disconnected
            }
        }

        // Atomically update the chunk cache for this world
        this.playerChunkCache.put(worldName, newChunkCache);

        return players;
    }
}
