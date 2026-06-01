package com.easywebmap.web.handlers;

import com.easywebmap.EasyWebMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerHandler {
    private static final Pattern PLAYERS_PATTERN = Pattern.compile("/api/players/([^/]+)");
    private static final Gson GSON = new GsonBuilder().create();
    private final EasyWebMap plugin;

    public PlayerHandler(EasyWebMap plugin) {
        this.plugin = plugin;
    }

    public void handlePlayers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }
        Matcher matcher = PLAYERS_PATTERN.matcher(req.uri());
        if (!matcher.matches()) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        String worldName = matcher.group(1);
        if (!this.plugin.getConfig().isWorldEnabled(worldName)) {
            this.sendError(ctx, HttpResponseStatus.FORBIDDEN);
            return;
        }
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            this.sendJson(ctx, new ArrayList<>());
            return;
        }
        List<Map<String, Object>> players = this.getPlayersInWorld(world);
        this.sendJson(ctx, players);
    }

    public void handleWorlds(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (World world : Universe.get().getWorlds().values()) {
            if (this.plugin.getConfig().isWorldEnabled(world.getName())) {
                Map<String, Object> worldInfo = new HashMap<>();
                worldInfo.put("name", world.getName());
                worlds.add(worldInfo);
            }
        }
        this.sendJson(ctx, worlds);
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
            } catch (Exception e) {
                // Player may have disconnected
            }
        }
        return players;
    }

    private void sendJson(ChannelHandlerContext ctx, Object data) {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(bytes)
        );
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
