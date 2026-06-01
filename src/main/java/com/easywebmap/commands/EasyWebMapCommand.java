package com.easywebmap.commands;

import com.easywebmap.EasyWebMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
import java.awt.Color;
import java.time.Duration;
import java.time.Instant;

public class EasyWebMapCommand extends AbstractPlayerCommand {
    private static final Color GREEN = new Color(85, 255, 85);
    private static final Color YELLOW = new Color(255, 255, 85);
    private static final Color RED = new Color(255, 85, 85);
    private static final Color GRAY = new Color(170, 170, 170);
    private static final Color AQUA = new Color(85, 255, 255);
    private final EasyWebMap plugin;
    private final RequiredArg<String> subcommand;

    public EasyWebMapCommand(EasyWebMap plugin) {
        super("easywebmap", "EasyWebMap admin commands");
        this.plugin = plugin;
        this.subcommand = this.withRequiredArg("action", "status|reload|clearcache|pregenerate|renewssl", ArgTypes.STRING);
        this.requirePermission("easywebmap.admin");
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef, PlayerRef playerData, World world) {
        String action = this.subcommand.get(ctx);
        String[] parts = action.split(" ");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "status" -> this.showStatus(playerData);
            case "reload" -> this.reloadConfig(playerData);
            case "clearcache" -> this.clearCache(playerData);
            case "pregenerate" -> this.pregenerate(playerData, world, parts);
            case "renewssl" -> this.renewSsl(playerData);
            default -> this.showHelp(playerData);
        }
    }

    private void showStatus(PlayerRef player) {
        int connections = this.plugin.getPlayerTracker().getConnectionCount();
        int httpPort = this.plugin.getConfig().getHttpPort();
        int memoryCacheSize = this.plugin.getTileManager().getMemoryCacheSize();
        boolean diskCacheEnabled = this.plugin.getConfig().isUseDiskCache();
        boolean httpsEnabled = this.plugin.getConfig().isHttpsEnabled();

        player.sendMessage(Message.raw("=== EasyWebMap Status ===").color(YELLOW));
        player.sendMessage(Message.raw("HTTP server: Running on port " + httpPort).color(GREEN));
        player.sendMessage(Message.raw("WebSocket connections: " + connections).color(GREEN));
        player.sendMessage(Message.raw("Memory cache: " + memoryCacheSize + " tiles").color(GREEN));
        player.sendMessage(Message.raw("Disk cache: " + (diskCacheEnabled ? "Enabled" : "Disabled")).color(GREEN));

        if (httpsEnabled) {
            int httpsPort = this.plugin.getConfig().getHttpsPort();
            boolean httpsRunning = this.plugin.getWebServer().isHttpsRunning();

            if (httpsRunning && this.plugin.getAcmeManager() != null) {
                player.sendMessage(Message.raw("HTTPS server: Running on port " + httpsPort).color(GREEN));
                String domain = this.plugin.getConfig().getDomain();
                player.sendMessage(Message.raw("Domain: " + domain).color(AQUA));

                Instant expiry = this.plugin.getAcmeManager().getCertificateExpiry();
                if (expiry != null) {
                    long daysRemaining = Duration.between(Instant.now(), expiry).toDays();
                    Color expiryColor = daysRemaining > 30 ? GREEN : (daysRemaining > 7 ? YELLOW : RED);
                    player.sendMessage(Message.raw("Certificate expires in: " + daysRemaining + " days").color(expiryColor));
                }
                player.sendMessage(Message.raw("URL: https://" + domain + ":" + httpsPort).color(AQUA));
            } else {
                player.sendMessage(Message.raw("HTTPS server: Not running").color(RED));
            }
        } else {
            player.sendMessage(Message.raw("HTTPS: Disabled").color(GRAY));
        }

        player.sendMessage(Message.raw("HTTP URL: http://localhost:" + httpPort).color(GREEN));
    }

    private void reloadConfig(PlayerRef player) {
        this.plugin.getConfig().reload();
        player.sendMessage(Message.raw("Configuration reloaded!").color(GREEN));
    }

    private void clearCache(PlayerRef player) {
        this.plugin.getTileManager().clearCache();
        player.sendMessage(Message.raw("All caches cleared (memory + disk)!").color(GREEN));
    }

    private void pregenerate(PlayerRef player, World world, String[] parts) {
        int radius = 10; // default
        if (parts.length > 1) {
            try {
                radius = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Message.raw("Invalid radius. Usage: /easywebmap pregenerate <radius>").color(RED));
                return;
            }
        }

        if (radius < 1) {
            player.sendMessage(Message.raw("Radius must be at least 1.").color(RED));
            return;
        }

        // Get player position as center
        Transform transform = player.getTransform();
        if (transform == null) {
            player.sendMessage(Message.raw("Could not get your position.").color(RED));
            return;
        }
        Vector3d pos = transform.getPosition();
        int centerX = (int) pos.x >> 4; // Convert to chunk coordinates
        int centerZ = (int) pos.z >> 4;

        player.sendMessage(Message.raw("Starting pre-generation of " + ((radius * 2 + 1) * (radius * 2 + 1)) + " tiles...").color(YELLOW));
        player.sendMessage(Message.raw("This runs in the background. Check status with /easywebmap status").color(GRAY));

        int finalRadius = radius;
        this.plugin.getTileManager().pregenerateTiles(world.getName(), centerX, centerZ, radius)
            .thenAccept(count -> {
                player.sendMessage(Message.raw("Pre-generation complete! Generated " + count + " new tiles.").color(GREEN));
            });
    }

    private void renewSsl(PlayerRef player) {
        if (this.plugin.getAcmeManager() == null) {
            player.sendMessage(Message.raw("HTTPS is not enabled. Enable it in config.json").color(RED));
            return;
        }

        player.sendMessage(Message.raw("Requesting SSL certificate renewal...").color(YELLOW));

        this.plugin.getAcmeManager().renewNow().thenAccept(success -> {
            if (success) {
                player.sendMessage(Message.raw("SSL certificate renewed successfully!").color(GREEN));
            } else {
                player.sendMessage(Message.raw("SSL certificate renewal failed. Check server logs.").color(RED));
            }
        });
    }

    private void showHelp(PlayerRef player) {
        player.sendMessage(Message.raw("=== EasyWebMap Commands ===").color(YELLOW));
        player.sendMessage(Message.raw("/easywebmap status - Show server status").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap reload - Reload configuration").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap clearcache - Clear all tile caches").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap pregenerate <radius> - Pre-generate tiles around you").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap renewssl - Force SSL certificate renewal").color(GRAY));
    }
}
