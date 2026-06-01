package com.easywebmap.tracker;

import com.easywebmap.EasyWebMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerTracker {
    private static final Gson GSON = new GsonBuilder().create();
    private final EasyWebMap plugin;
    private final Set<Channel> connectedChannels;
    private ScheduledExecutorService scheduler;

    public PlayerTracker(EasyWebMap plugin) {
        this.plugin = plugin;
        this.connectedChannels = ConcurrentHashMap.newKeySet();
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

    private void broadcastPlayerPositions() {
        if (this.connectedChannels.isEmpty()) {
            return;
        }
        Map<String, List<Map<String, Object>>> worldPlayers = new HashMap<>();
        for (World world : Universe.get().getWorlds().values()) {
            if (!this.plugin.getConfig().isWorldEnabled(world.getName())) {
                continue;
            }
            List<Map<String, Object>> players = this.getPlayersInWorld(world);
            if (!players.isEmpty()) {
                worldPlayers.put(world.getName(), players);
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
    }

    private List<Map<String, Object>> getPlayersInWorld(World world) {
        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            try {
                Transform transform = playerRef.getTransform();
                if (transform != null) {
                    Vector3d pos = transform.getPosition();
                    Rotation3f rot = transform.getRotation();
                    Map<String, Object> playerData = new HashMap<>();
                    playerData.put("name", playerRef.getUsername());
                    playerData.put("uuid", playerRef.getUuid().toString());
                    playerData.put("x", pos.x);
                    playerData.put("y", pos.y);
                    playerData.put("z", pos.z);
                    playerData.put("yaw", rot != null ? rot.yaw() : 0f);
                    players.add(playerData);
                }
            } catch (Throwable e) {
                // Player may have disconnected, or a future API drift threw an
                // Error — swallow per-player so one bad read can't kill the
                // scheduled broadcast task (which would silently stop all updates).
            }
        }
        return players;
    }
}
