(function() {
    'use strict';

    // ============================================
    // BatchTileLayer - Custom LeafletJS tile layer
    // Batches multiple tile requests into single HTTP requests
    // ============================================
    L.TileLayer.Batch = L.TileLayer.extend({
        options: {
            batchDelay: 150,           // Base delay for zoom >= 0
            batchDelayNegative: 400,   // Longer delay for negative zoom (composite tiles)
            maxBatchSize: 2000,
            batchEndpoint: '/api/tiles/batch'
        },

        initialize: function(urlTemplate, options) {
            L.TileLayer.prototype.initialize.call(this, urlTemplate, options);
            this._pendingTiles = new Map();
            this._batchTimer = null;
            this._emptyTileUrl = null;
            this._worldName = 'world';
            this._isSending = false;
            this._queuedWhileSending = new Map();
            this._currentZoom = 0;
            this._abortController = null;
            this._isZooming = false;
            this._zoomEndTimer = null;
        },

        setWorld: function(worldName) {
            this._worldName = worldName;
        },

        // Called when layer is added to map
        onAdd: function(map) {
            L.TileLayer.prototype.onAdd.call(this, map);

            // Listen for zoom events to cancel obsolete requests
            map.on('zoomstart', this._onZoomStart, this);
            map.on('zoomend', this._onZoomEnd, this);
        },

        onRemove: function(map) {
            map.off('zoomstart', this._onZoomStart, this);
            map.off('zoomend', this._onZoomEnd, this);
            this._cancelAllRequests();
            L.TileLayer.prototype.onRemove.call(this, map);
        },

        _onZoomStart: function() {
            this._isZooming = true;
            // Cancel pending batches during zoom - they'll be obsolete
            if (this._batchTimer) {
                clearTimeout(this._batchTimer);
                this._batchTimer = null;
            }
        },

        _onZoomEnd: function() {
            // Small delay after zoom ends to let tiles settle
            if (this._zoomEndTimer) clearTimeout(this._zoomEndTimer);
            this._zoomEndTimer = setTimeout(() => {
                this._isZooming = false;
                // Now start collecting and sending tiles
                if (this._pendingTiles.size > 0) {
                    const delay = this._getBatchDelay();
                    this._batchTimer = setTimeout(() => this._sendBatch(), delay);
                }
            }, 100);
        },

        _cancelAllRequests: function() {
            // Abort in-flight requests
            if (this._abortController) {
                this._abortController.abort();
                this._abortController = null;
            }
            // Clear pending tiles
            this._pendingTiles.clear();
            this._queuedWhileSending.clear();
            if (this._batchTimer) {
                clearTimeout(this._batchTimer);
                this._batchTimer = null;
            }
            this._isSending = false;
        },

        _getBatchDelay: function() {
            // Use longer delay for negative zoom levels (composite tiles are expensive)
            const zoom = this._map ? Math.floor(this._map.getZoom()) : 0;
            return zoom < 0 ? this.options.batchDelayNegative : this.options.batchDelay;
        },

        createTile: function(coords, done) {
            const tile = document.createElement('img');
            tile.alt = '';
            tile.setAttribute('role', 'presentation');

            // Use actual zoom level for tile pyramid support
            // At zoom < 0, server provides composite tiles
            const zoom = Math.min(coords.z, 0);  // Clamp to 0 max (server handles -3 to 0)
            const key = `${zoom}/${coords.x}/${coords.y}`;
            this._queueTileRequest(key, coords, tile, done);

            return tile;
        },

        _queueTileRequest: function(key, coords, tile, done) {
            // If we're currently sending, queue for next batch
            const targetMap = this._isSending ? this._queuedWhileSending : this._pendingTiles;

            targetMap.set(key, {
                tile: tile,
                done: done,
                coords: coords
            });

            // Don't schedule batches while zooming
            if (this._isZooming) return;

            if (this._batchTimer) {
                clearTimeout(this._batchTimer);
            }

            // Only auto-send if not currently sending and we hit a huge limit
            if (!this._isSending && this._pendingTiles.size >= this.options.maxBatchSize) {
                this._sendBatch();
            } else if (!this._isSending) {
                const delay = this._getBatchDelay();
                this._batchTimer = setTimeout(() => this._sendBatch(), delay);
            }
        },

        _sendBatch: function() {
            if (this._pendingTiles.size === 0) return;
            // Don't send while zooming
            if (this._isZooming) return;

            this._isSending = true;
            const allTiles = new Map(this._pendingTiles);
            this._pendingTiles.clear();
            this._batchTimer = null;

            // Create AbortController for this batch
            this._abortController = new AbortController();
            const signal = this._abortController.signal;

            // Split into chunks of 200 tiles max
            const CHUNK_SIZE = 200;
            const chunks = [];
            let currentChunk = new Map();

            for (const [key, value] of allTiles) {
                currentChunk.set(key, value);
                if (currentChunk.size >= CHUNK_SIZE) {
                    chunks.push(currentChunk);
                    currentChunk = new Map();
                }
            }
            if (currentChunk.size > 0) {
                chunks.push(currentChunk);
            }

            console.log(`Sending ${allTiles.size} tiles in ${chunks.length} batch(es)`);

            // Send all chunks in parallel
            const chunkPromises = chunks.map(chunk => this._sendChunk(chunk, signal));

            Promise.all(chunkPromises).finally(() => {
                this._abortController = null;
                this._isSending = false;
                // Process any tiles that were queued while we were sending
                if (this._queuedWhileSending.size > 0 && !this._isZooming) {
                    for (const [key, value] of this._queuedWhileSending) {
                        this._pendingTiles.set(key, value);
                    }
                    this._queuedWhileSending.clear();
                    // Schedule next batch with appropriate delay
                    const delay = this._getBatchDelay();
                    this._batchTimer = setTimeout(() => this._sendBatch(), delay);
                }
            });
        },

        _sendChunk: function(batch, signal) {
            const tiles = [];
            for (const [key, request] of batch) {
                const [z, x, y] = key.split('/').map(Number);
                tiles.push({ z, x, y });
            }

            const requestBody = {
                world: this._worldName,
                tiles: tiles
            };

            return fetch(this.options.batchEndpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(requestBody),
                signal: signal
            })
            .then(response => {
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                return response.json();
            })
            .then(data => {
                for (const [key, tileData] of Object.entries(data.tiles)) {
                    const request = batch.get(key);
                    if (!request) continue;

                    if (tileData.empty) {
                        this._setEmptyTile(request.tile, request.done);
                    } else if (tileData.data) {
                        // Use Blob URL instead of Base64 for better memory/performance
                        const binary = atob(tileData.data);
                        const bytes = new Uint8Array(binary.length);
                        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                        const blob = new Blob([bytes], { type: 'image/png' });
                        const url = URL.createObjectURL(blob);
                        request.tile.onload = () => {
                            URL.revokeObjectURL(url);
                            request.done(null, request.tile);
                        };
                        request.tile.onerror = () => {
                            URL.revokeObjectURL(url);
                            request.done(new Error('Image load failed'), request.tile);
                        };
                        request.tile.src = url;
                    } else if (tileData.error) {
                        request.done(new Error(tileData.error), request.tile);
                    }
                }
            })
            .catch(error => {
                // Don't log abort errors - they're intentional
                if (error.name !== 'AbortError') {
                    console.error('Batch chunk failed:', error);
                }
                for (const [key, request] of batch) {
                    // For aborted requests, just mark as failed silently
                    request.done(error, request.tile);
                }
            });
        },

        _setEmptyTile: function(tile, done) {
            if (!this._emptyTileUrl) {
                this._emptyTileUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
            }
            tile.src = this._emptyTileUrl;
            done(null, tile);
        }
    });

    L.tileLayer.batch = function(urlTemplate, options) {
        return new L.TileLayer.Batch(urlTemplate, options);
    };

    // Config - 1 tile = 1 chunk = 32 blocks
    const CHUNK_SIZE = 32;
    const TILE_SIZE = 256;
    const SCALE = TILE_SIZE / CHUNK_SIZE;  // 8 - Leaflet units per block

    // State
    let map = null;
    let tileLayer = null;
    let currentWorld = 'world';
    let websocket = null;
    let playerMarkers = {};
    let playerData = {};  // Store player data for list
    let reconnectTimer = null;
    let playerListCollapsed = false;
    let initialPositionSet = false;  // Track if we've set initial map position

    function initMap() {
        // Using CRS.Simple: 1 unit = 1 pixel at zoom 0
        // We want 1 unit = 1 block, so we need to scale tiles
        // Set large bounds to allow zooming out
        const worldBounds = L.latLngBounds(
            L.latLng(-100000, -100000),
            L.latLng(100000, 100000)
        );

        map = L.map('map', {
            crs: L.CRS.Simple,
            minZoom: -4,
            maxZoom: 4,
            zoomSnap: 0.5,
            zoomDelta: 0.5,
            maxBounds: worldBounds,
            maxBoundsViscosity: 1.0
        });

        // Start at origin
        map.setView([0, 0], 0);

        updateTileLayer();

        // Throttled mousemove for coordinate display (~60fps max)
        let lastMoveTime = 0;
        map.on('mousemove', function(e) {
            const now = performance.now();
            if (now - lastMoveTime < 16) return;
            lastMoveTime = now;
            const x = Math.round(e.latlng.lng / SCALE);
            const z = Math.round(-e.latlng.lat / SCALE);
            document.getElementById('coords-display').textContent = `X: ${x}, Z: ${z}`;
        });

        map.attributionControl.addAttribution('EasyMap');
    }

    function updateTileLayer() {
        if (tileLayer) {
            map.removeLayer(tileLayer);
        }

        // Batch tile layer - reduces HTTP requests by batching multiple tiles per request
        // minNativeZoom: -3 means server provides composite tiles at negative zoom levels
        // Using -3 instead of -4 reduces base tiles per composite from 256 to 64 (4x faster)
        // Users can still zoom out to -4, tiles will be scaled from -3
        tileLayer = L.tileLayer.batch('/api/tiles/' + currentWorld + '/{z}/{x}/{y}.png', {
            tileSize: TILE_SIZE,
            minNativeZoom: -3,  // Server provides tiles from -3 to 0 (64 base tiles max)
            maxNativeZoom: 0,   // Max native zoom is 0 (single chunk per tile)
            minZoom: -4,        // User can still zoom out to -4 (scaled from -3)
            maxZoom: 4,
            noWrap: true,
            bounds: [[-100000, -100000], [100000, 100000]],
            batchDelay: 150,            // Fast batching at zoom >= 0
            batchDelayNegative: 400,    // Slower batching at negative zoom (composite tiles)
            maxBatchSize: 2000,
            batchEndpoint: '/api/tiles/batch'
        });

        tileLayer.setWorld(currentWorld);
        tileLayer.addTo(map);
    }

    // Convert world coords to LatLng
    function worldToLatLng(x, z) {
        // X -> lng, Z -> -lat (north is up, Z increases south)
        // Multiply by SCALE since Leaflet uses tile-pixel coords (256px per 32-block chunk)
        return L.latLng(-z * SCALE, x * SCALE);
    }

    async function loadWorlds() {
        try {
            const response = await fetch('/api/worlds');
            const worlds = await response.json();

            const select = document.getElementById('world-select');
            select.innerHTML = '';

            worlds.forEach(world => {
                const option = document.createElement('option');
                option.value = world.name;
                option.textContent = world.name;
                if (world.name === currentWorld) option.selected = true;
                select.appendChild(option);
            });

            if (worlds.length > 0 && !worlds.find(w => w.name === currentWorld)) {
                currentWorld = worlds[0].name;
                updateTileLayer();
            }
        } catch (e) {
            console.error('Failed to load worlds:', e);
        }
    }

    function onWorldChange(e) {
        currentWorld = e.target.value;
        updateTileLayer();
        clearPlayerMarkers();
        updatePlayerList();
    }

    function clearPlayerMarkers() {
        Object.values(playerMarkers).forEach(m => {
            m.unbindTooltip();
            map.removeLayer(m);
        });
        playerMarkers = {};
        playerData = {};
    }

    // Convert radians to degrees
    function radToDeg(rad) {
        return rad * (180 / Math.PI);
    }

    // Create arrow icon with rotation
    function createArrowIcon(yawRadians) {
        // Yaw is in radians, convert to degrees
        // Arrow points up (north) by default, adjust so it points in facing direction
        const yawDeg = radToDeg(yawRadians);
        const rotation = yawDeg + 180;

        return L.divIcon({
            className: 'player-marker',
            html: `<div class="player-arrow" style="transform: rotate(${rotation}deg);"></div>`,
            iconSize: [20, 20],
            iconAnchor: [10, 10]
        });
    }

    // Update arrow rotation directly on DOM element
    function updateArrowRotation(marker, yawRadians) {
        const el = marker.getElement();
        if (el) {
            const arrow = el.querySelector('.player-arrow');
            if (arrow) {
                const yawDeg = radToDeg(yawRadians);
                const rotation = yawDeg + 180;
                arrow.style.transform = `rotate(${rotation}deg)`;
            }
        }
    }

    // WebSocket
    function connectWebSocket() {
        const statusEl = document.getElementById('connection-status');
        statusEl.textContent = 'Connecting...';
        statusEl.className = 'connecting';

        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        websocket = new WebSocket(`${protocol}//${location.host}/ws`);

        websocket.onopen = () => {
            statusEl.textContent = 'Connected';
            statusEl.className = 'connected';
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
        };

        websocket.onmessage = (e) => {
            try {
                const data = JSON.parse(e.data);
                if (data.type === 'players') updatePlayers(data.worlds);
            } catch (err) {}
        };

        websocket.onclose = () => {
            statusEl.textContent = 'Disconnected';
            statusEl.className = 'disconnected';
            if (!reconnectTimer) {
                reconnectTimer = setTimeout(connectWebSocket, 3000);
            }
        };
    }

    function updatePlayers(worldsData) {
        const players = worldsData[currentWorld] || [];
        const seen = new Set();
        let count = 0;

        // Update player data store
        playerData = {};

        players.forEach(p => {
            seen.add(p.uuid);
            count++;
            const pos = worldToLatLng(p.x, p.z);
            const yaw = p.yaw || 0;

            // Store player data
            playerData[p.uuid] = {
                name: p.name,
                uuid: p.uuid,
                x: Math.round(p.x),
                y: Math.round(p.y),
                z: Math.round(p.z),
                yaw: yaw
            };

            if (playerMarkers[p.uuid]) {
                playerMarkers[p.uuid].setLatLng(pos);
                updateArrowRotation(playerMarkers[p.uuid], yaw);
            } else {
                const marker = L.marker(pos, {
                    icon: createArrowIcon(yaw)
                });
                marker.bindTooltip(p.name, {
                    permanent: false,
                    direction: 'top',
                    offset: [0, -12],
                    className: 'player-tooltip'
                });
                marker.addTo(map);
                playerMarkers[p.uuid] = marker;
            }
        });

        Object.keys(playerMarkers).forEach(uuid => {
            if (!seen.has(uuid)) {
                playerMarkers[uuid].unbindTooltip();
                map.removeLayer(playerMarkers[uuid]);
                delete playerMarkers[uuid];
            }
        });

        document.getElementById('player-count-display').textContent = `Players: ${count}`;
        updatePlayerList();

        // On first load, if exactly 1 player online, center map on them
        if (!initialPositionSet && count === 1) {
            const player = players[0];
            const pos = worldToLatLng(player.x, player.z);
            map.setView(pos, 0);
            initialPositionSet = true;
        } else if (!initialPositionSet && count > 0) {
            // Multiple players - just mark as set, keep default position
            initialPositionSet = true;
        }
    }

    function updatePlayerList() {
        const listEl = document.getElementById('player-list');
        const players = Object.values(playerData);

        if (players.length === 0) {
            listEl.innerHTML = '<li class="player-list-empty">No players online</li>';
            return;
        }

        // Sort by name
        players.sort((a, b) => a.name.localeCompare(b.name));

        // Differential update - only change what's needed
        const existingItems = new Map();
        listEl.querySelectorAll('li[data-uuid]').forEach(li => {
            existingItems.set(li.dataset.uuid, li);
        });

        const currentUuids = new Set(players.map(p => p.uuid));

        // Remove empty message if present
        const emptyMsg = listEl.querySelector('.player-list-empty');
        if (emptyMsg) emptyMsg.remove();

        // Remove players no longer online
        existingItems.forEach((li, uuid) => {
            if (!currentUuids.has(uuid)) li.remove();
        });

        // Update or add players
        let prevElement = null;
        for (const p of players) {
            let li = existingItems.get(p.uuid);
            if (li) {
                // Update coords only if changed
                const coordsEl = li.querySelector('.player-coords');
                const newCoords = `${p.x}, ${p.z}`;
                if (coordsEl.textContent !== newCoords) {
                    coordsEl.textContent = newCoords;
                }
            } else {
                // Create new element
                li = document.createElement('li');
                li.dataset.uuid = p.uuid;
                li.onclick = () => window.focusPlayer(p.uuid);
                li.innerHTML = `
                    <span class="player-icon"></span>
                    <span class="player-name">${escapeHtml(p.name)}</span>
                    <span class="player-coords">${p.x}, ${p.z}</span>
                `;
            }
            // Ensure correct order
            if (prevElement) {
                if (li.previousElementSibling !== prevElement) {
                    prevElement.after(li);
                }
            } else if (li.parentElement !== listEl || li !== listEl.firstElementChild) {
                listEl.prepend(li);
            }
            prevElement = li;
        }
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Focus map on player
    window.focusPlayer = function(uuid) {
        const p = playerData[uuid];
        if (p) {
            const pos = worldToLatLng(p.x, p.z);
            map.setView(pos, 0);  // Zoom level 0 for good detail
        }
    };

    function initPlayerListToggle() {
        const toggleBtn = document.getElementById('player-list-toggle');
        const content = document.getElementById('player-list-content');

        toggleBtn.addEventListener('click', () => {
            playerListCollapsed = !playerListCollapsed;
            if (playerListCollapsed) {
                content.classList.add('collapsed');
                toggleBtn.textContent = '+';
            } else {
                content.classList.remove('collapsed');
                toggleBtn.textContent = '-';
            }
        });
    }

    // Init
    document.addEventListener('DOMContentLoaded', () => {
        initMap();
        loadWorlds();
        connectWebSocket();
        initPlayerListToggle();
        document.getElementById('world-select').addEventListener('change', onWorldChange);
        setInterval(loadWorlds, 30000);
    });
})();
