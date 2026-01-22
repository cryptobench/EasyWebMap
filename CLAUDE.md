# EasyMap Plugin - Development Context

## Project Overview
A Dynmap-like web map viewer for Hytale servers. Provides browser-based live map viewing with tile-based rendering, real-time player positions via WebSocket, and a LeafletJS frontend.

**Key Feature:** Uses Hytale's built-in `WorldMapManager.getImageAsync()` - the same system that powers the in-game map. This means live terrain updates as blocks are placed/broken.

## How to Explore the Hytale Server API

The Hytale server API is in `Server/HytaleServer.jar`. Since there's no official documentation, use these commands to discover available classes and methods:

### List All Classes in a Package
```bash
cd "/Users/golemgrid/Library/Application Support/Hytale/install/release/package/game/latest/Server"

# Find all event-related classes
jar -tf HytaleServer.jar | grep -i "event"

# Find map-related classes
jar -tf HytaleServer.jar | grep -i "map"

# Find netty-related classes
jar -tf HytaleServer.jar | grep -i "netty"

# Find all classes in a specific package
jar -tf HytaleServer.jar | grep "com/hypixel/hytale/server/core/event/events/"
```

### Inspect a Class (Methods, Fields, Signatures)
```bash
# Basic class inspection
javap -classpath HytaleServer.jar com.hypixel.hytale.server.core.universe.world.WorldMapManager

# With more detail (private members too)
javap -p -classpath HytaleServer.jar com.hypixel.hytale.server.core.universe.world.MapImage
```

### Key Packages to Explore
```
com.hypixel.hytale.server.core.plugin        # JavaPlugin, PluginBase
com.hypixel.hytale.server.core.event.events  # All events
com.hypixel.hytale.server.core.command       # Command system
com.hypixel.hytale.server.core.entity        # Entity/Player classes
com.hypixel.hytale.server.core.universe      # Universe, World, WorldMapManager
com.hypixel.hytale.component                 # ECS system (Store, Ref, Query)
com.hypixel.hytale.event                     # EventRegistry
com.hypixel.hytale.math.vector               # Vector3d, Vector3f, Vector3i
com.hypixel.hytale.netty                     # NettyUtil for HTTP/WebSocket
```

## Hytale Server Plugin Development

### Plugin Structure
```
plugins/EasyMap/
├── src/main/java/com/easymap/
│   ├── EasyMap.java                   # Main plugin (JavaPlugin)
│   ├── config/
│   │   └── MapConfig.java             # JSON config management
│   ├── commands/
│   │   └── EasyMapCommand.java        # /easymap admin commands
│   ├── web/
│   │   ├── WebServer.java             # Netty HTTP server
│   │   ├── HttpRequestHandler.java    # Request router
│   │   ├── WebSocketHandler.java      # Real-time connections
│   │   └── handlers/
│   │       ├── TileHandler.java       # GET /api/tiles/{world}/{z}/{x}/{y}.png
│   │       ├── PlayerHandler.java     # GET /api/players/{world}
│   │       └── StaticHandler.java     # Serve web frontend
│   ├── map/
│   │   ├── TileManager.java           # Tile generation & caching
│   │   ├── TileCache.java             # LRU memory + disk cache
│   │   └── PngEncoder.java            # MapImage -> PNG bytes
│   └── tracker/
│       └── PlayerTracker.java         # Broadcast player positions
├── src/main/resources/
│   ├── manifest.json
│   └── web/
│       ├── index.html
│       ├── css/map.css
│       └── js/map.js                  # LeafletJS integration
└── pom.xml
```

### manifest.json (PascalCase Required!)
```json
{
    "Group": "cryptobench",
    "Name": "EasyMap",
    "Version": "1.0.0",
    "Main": "com.easymap.EasyMap",
    "Description": "Dynmap-like web map viewer for Hytale",
    "Authors": [],
    "Dependencies": {}
}
```

### Main Plugin Class
```java
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public class EasyMap extends JavaPlugin {
    public EasyMap(JavaPluginInit init) { super(init); }

    @Override
    public void setup() {
        // Register commands, events, systems
        getCommandRegistry().registerCommand(new EasyMapCommand(this));
    }

    @Override
    public void start() {
        // Start web server and player tracker
    }

    @Override
    public void shutdown() {
        // Stop web server gracefully
    }
}
```

### Available Registries (from PluginBase)
```java
getEventRegistry()           // For IBaseEvent events (PlayerInteractEvent, etc.)
getEntityStoreRegistry()     // For ECS systems and components on entities
getChunkStoreRegistry()      // For ECS systems and components on chunks
getCommandRegistry()         // For commands
getBlockStateRegistry()      // For block states
getEntityRegistry()          // For entity types
getTaskRegistry()            // For scheduled tasks
getDataDirectory()           // Plugin data folder: mods/Group_PluginName/
```

## CRITICAL: Multi-Threaded Architecture & Thread Safety

**Hytale uses a multi-threaded server model. Understanding this is MANDATORY before writing any plugin code.**

### Core Architecture

| Component | Description |
|-----------|-------------|
| **HytaleServer** | Singleton root; owns `SCHEDULED_EXECUTOR` for background tasks |
| **Universe** | Singleton container for all worlds; thread-safe player lookups via `ConcurrentHashMap` |
| **World** | Each world runs on its **own dedicated thread** |

**Key Benefit:** Lag in "World A" does NOT cause lag in "World B" - worlds run in parallel.

### The Thread-Bound Rule (CRITICAL)

**The `EntityStore` and ALL ECS operations (`getComponent`, `addComponent`, `removeComponent`) are THREAD-BOUND.**

They can ONLY be accessed from their specific world's thread. Hytale uses `assertThread()` internally - accessing from the wrong thread throws `IllegalStateException` immediately to prevent silent data corruption.

```java
// WRONG - will crash if called from wrong thread
store.getComponent(playerRef, Player.getComponentType());

// CORRECT - ensures execution on world thread
world.execute(() -> {
    store.getComponent(playerRef, Player.getComponentType());
});
```

### The Bridge: `world.execute()`

To run code on a specific world's thread from an external thread (background task, different world, etc.), use `world.execute()`:

```java
// From a background task or different thread
world.execute(() -> {
    // This code runs safely on the world's thread
    Store<EntityStore> store = world.getEntityStore().getStore();
    // Now safe to access ECS components
});
```

### Thread-Safe vs Thread-Bound Operations

| Always Safe (Any Thread) | Unsafe (Requires `world.execute()`) |
|-------------------------|-------------------------------------|
| `Universe.get().getPlayer(uuid)` | `store.getComponent(ref, type)` |
| `playerRef.sendMessage(message)` | `store.addComponent(...)` |
| `HytaleServer.SCHEDULED_EXECUTOR.schedule(...)` | `store.removeComponent(...)` |
| `world.execute(runnable)` | Modifying entity position/health/inventory |

### Managing Shared Plugin State

When sharing data across multiple worlds (global state), use Java's thread-safe types:

```java
// Counters - use AtomicInteger
private final AtomicInteger globalKills = new AtomicInteger(0);
globalKills.incrementAndGet();

// Collections/Maps - use ConcurrentHashMap
private final ConcurrentHashMap<UUID, Integer> playerKills = new ConcurrentHashMap<>();
playerKills.merge(playerId, 1, Integer::sum);

// One-time initialization - use AtomicBoolean
private final AtomicBoolean initialized = new AtomicBoolean(false);
if (initialized.compareAndSet(false, true)) {
    // Initialize only once
}

// Simple flags - use volatile
private volatile boolean enabled = true;
```

### Common Mistakes & Patterns

#### The Executor Trap
`SCHEDULED_EXECUTOR` runs on its own background thread, NOT a world thread:
```java
// WRONG - crashes when touching entity
HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
    store.getComponent(ref, type);  // IllegalStateException!
}, 1, TimeUnit.SECONDS);

// CORRECT - bridge back to world thread
HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
    world.execute(() -> {
        store.getComponent(ref, type);  // Safe!
    });
}, 1, TimeUnit.SECONDS);
```

#### Avoid Blocking World Threads
Never call `.join()` or `.get()` on a `CompletableFuture` inside a world thread - it blocks the entire world tick:
```java
// WRONG - blocks world tick
CompletableFuture<Data> future = fetchDataAsync();
Data data = future.get();  // DON'T DO THIS

// CORRECT - use callbacks
fetchDataAsync().thenAccept(data -> {
    world.execute(() -> {
        // Process data on world thread
    });
});
```

#### Race Conditions
Remember that `counter++` is secretly three operations (read, increment, write):
```java
// WRONG - race condition
private int counter = 0;
counter++;  // Lost updates!

// CORRECT - atomic operation
private final AtomicInteger counter = new AtomicInteger(0);
counter.incrementAndGet();
```

### Technical Specifications

| Spec | Value | Notes |
|------|-------|-------|
| **Tick Rate** | 30 TPS | 33.3ms per tick (vs Minecraft's 20 TPS) |
| **Tick Budget** | 33ms | Heavy logic (>33ms) lags the entire world |
| **Scaling** | Per-core | More CPU cores = more parallel worlds |

### Performance Best Practices

1. **Offload Heavy Work:** Move expensive operations (pathfinding, database I/O, HTTP requests) to `SCHEDULED_EXECUTOR` or `CompletableFuture.runAsync()`
2. **Avoid Object Creation in Ticks:** Reuse objects where possible to reduce GC pressure
3. **Use `world.execute()` Sparingly:** Queue minimal work back to world threads

### Local vs Global Events

| Event Type | Thread Context | Example |
|------------|---------------|---------|
| **Local Events** | Fires on the World Thread | `PlayerInteractEvent`, `BreakBlockEvent` - safe to touch ECS directly |
| **Global Events** | May fire on different thread | Server-wide events - must use `world.execute()` before touching entities |

### The Golden Rule

> **"Always assume you are on the wrong thread unless you are inside a standard World System or event handler. If you touch `store`, verify you are thread-bound or wrapped in `world.execute()`."**

### Debugging Thread Issues

If you see:
- `IllegalStateException: Assert not in thread!` → You're accessing ECS from wrong thread
- `IllegalStateException: Store is currently processing!` → You're modifying during iteration
- Random crashes or data corruption → Race condition, use atomic types

**First debug step:** "Is this code touching a Store/Component while running on an Executor thread?"

## EasyMap-Specific APIs

### Tile Generation Flow
```java
// TileManager.getTile()
WorldMapManager mgr = world.getWorldMapManager();
CompletableFuture<MapImage> future = mgr.getImageAsync(chunkX, chunkZ);
future.thenApply(img -> PngEncoder.encode(img, tileSize));
```

### MapImage to PNG Conversion
```java
// MapImage contains int[] data - RGBA pixel array
public class PngEncoder {
    public static byte[] encode(MapImage img, int tileSize) {
        BufferedImage buffered = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, tileSize, tileSize, img.getData(), 0, tileSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }
}
```

### Player Position Access
```java
// On world thread
world.execute(() -> {
    Store<EntityStore> store = world.getEntityStore();
    TransformComponent t = store.getComponent(playerRef, TransformComponent.getComponentType());
    Vector3d pos = t.getPosition();
});
```

### Netty HTTP Server Setup
```java
ServerBootstrap bootstrap = new ServerBootstrap()
    .group(NettyUtil.getEventLoopGroup(1, "boss"), NettyUtil.getEventLoopGroup(4, "worker"))
    .channel(NettyUtil.getServerChannel())
    .childHandler(new ChannelInitializer<>() {
        void initChannel(Channel ch) {
            ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(new HttpRequestHandler(plugin));
        }
    });
bootstrap.bind(port).sync();
```

### WebSocket Upgrade
```java
// In HttpRequestHandler, detect upgrade request
if (req.headers().get(HttpHeaderNames.UPGRADE) != null) {
    WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
        "ws://" + req.headers().get(HttpHeaderNames.HOST) + "/ws", null, false);
    WebSocketServerHandshaker handshaker = factory.newHandshaker(req);
    handshaker.handshake(ctx.channel(), req);
}
```

## API Endpoints

| Endpoint | Method | Response | Description |
|----------|--------|----------|-------------|
| `/api/tiles/{world}/{z}/{x}/{y}.png` | GET | image/png | Map tile at zoom z, coords x,y |
| `/api/players/{world}` | GET | JSON | Player positions in world |
| `/api/worlds` | GET | JSON | List of enabled worlds |
| `/ws` | WebSocket | JSON frames | Real-time player updates |
| `/*` | GET | HTML/CSS/JS | Static web frontend |

## Commands

```java
public class EasyMapCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> subcommand;

    public EasyMapCommand(EasyMap plugin) {
        super("easymap", "EasyMap admin commands");
        this.subcommand = withRequiredArg("action", "status|reload|clearcache", ArgTypes.STRING);
        requirePermission("easymap.admin");
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store,
                          Ref<EntityStore> playerRef, PlayerRef playerData, World world) {
        String action = subcommand.get(ctx);
        switch (action) {
            case "status" -> // Show connection count
            case "reload" -> // Reload config
            case "clearcache" -> // Clear tile cache
        }
    }
}
```

## Messages (No Minecraft Color Codes!)
```java
import com.hypixel.hytale.server.core.Message;
import java.awt.Color;

// Correct
playerData.sendMessage(Message.raw("Success!").color(new Color(85, 255, 85)));

// WRONG - shows literal "§a"
playerData.sendMessage(Message.raw("§aSuccess!"));

// Common colors
Color GREEN = new Color(85, 255, 85);
Color RED = new Color(255, 85, 85);
Color YELLOW = new Color(255, 255, 85);
Color GOLD = new Color(255, 170, 0);
Color GRAY = new Color(170, 170, 170);
```

## Configuration
```java
public class MapConfig {
    private int httpPort = 8080;
    private int updateIntervalMs = 1000;
    private int tileCacheSize = 500;
    private List<String> enabledWorlds = new ArrayList<>();  // empty = all
    private int tileSize = 256;
    private int maxZoom = 4;
}
```

## Data Storage
```java
Path dataDir = getDataDirectory();  // mods/cryptobench_EasyMap/
Gson gson = new GsonBuilder().setPrettyPrinting().create();  // Gson provided by Hytale
```

## Building & Installation
```bash
cd /Users/golemgrid/Documents/GitHub.nosync/EasyMap
mvn clean package
# Copy target/EasyMap-1.0.0.jar to Server/mods/
```

## Verification
1. Build: `mvn clean package`
2. Install: Copy JAR to `Server/mods/`
3. Start server, check console for "EasyMap web server started on port 8080"
4. Open browser to `http://localhost:8080`
5. Verify:
   - Map tiles load and display terrain
   - Player markers appear and update in real-time
   - `/easymap status` shows connection count
   - `/easymap clearcache` clears tiles

## Key Imports
```java
// Plugin
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

// Events
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.*;

// ECS
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

// Commands
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;

// Entity/Player
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapManager;

// Math
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;

// Messages
import com.hypixel.hytale.server.core.Message;

// Netty (for HTTP/WebSocket)
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import com.hypixel.hytale.netty.NettyUtil;
```

## Reference Files

Pattern sources from existing plugins:
- `/Users/golemgrid/Documents/GitHub.nosync/EasyTPA/src/main/java/com/easytpa/EasyTPA.java` - Plugin lifecycle
- `/Users/golemgrid/Documents/GitHub.nosync/EasyTPA/src/main/java/com/easytpa/config/TpaConfig.java` - JSON config
- `/Users/golemgrid/Documents/GitHub.nosync/EasyClaims/src/main/java/com/easyclaims/map/ClaimImageBuilder.java` - Async image generation
- `/Users/golemgrid/Documents/GitHub.nosync/EasyTPA/pom.xml` - Maven build setup
