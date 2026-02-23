package com.hyperperms.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyperperms.HyperPerms;
import com.hyperperms.api.events.*;
import com.hyperperms.model.Group;
import com.hyperperms.model.Node;
import com.hyperperms.model.User;
import com.hyperperms.util.Logger;
import com.hyperperms.web.dto.Change;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket client for realtime bidirectional sync with the web editor
 * via the Cloudflare Durable Object relay.
 */
public final class WebSocketSessionClient {

    private static final Gson GSON = new GsonBuilder().create();

    private final HyperPerms hyperPerms;
    private final HttpClient httpClient;
    private final ChangeApplier changeApplier;
    private final String sessionId;
    private final String wsUrl;
    private final ScheduledExecutorService scheduler;

    private final int maxReconnectAttempts;
    private final int maxReconnectDelaySeconds;
    private final int pingTimeoutSeconds;

    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean intentionallyClosed = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile long lastPingReceived;
    private volatile ScheduledFuture<?> pingWatchdog;

    private final List<EventBus.Subscription> eventSubscriptions = new ArrayList<>();

    // For batch.apply results
    private final Map<String, CompletableFuture<BatchApplyResult>> pendingBatchApplies =
            Collections.synchronizedMap(new HashMap<>());
    private final AtomicInteger batchIdCounter = new AtomicInteger(0);

    // Buffer for partial WebSocket text messages
    private final StringBuilder messageBuffer = new StringBuilder();

    public WebSocketSessionClient(
            @NotNull HyperPerms hyperPerms,
            @NotNull HttpClient httpClient,
            @NotNull String sessionId,
            @NotNull String wsUrl,
            @NotNull ScheduledExecutorService scheduler
    ) {
        this.hyperPerms = hyperPerms;
        this.httpClient = httpClient;
        this.changeApplier = new ChangeApplier(hyperPerms);
        this.sessionId = sessionId;
        this.wsUrl = wsUrl;
        this.scheduler = scheduler;

        this.maxReconnectAttempts = hyperPerms.getConfig().getWebEditorWebsocketReconnectMaxAttempts();
        this.maxReconnectDelaySeconds = hyperPerms.getConfig().getWebEditorWebsocketReconnectMaxDelaySeconds();
        this.pingTimeoutSeconds = hyperPerms.getConfig().getWebEditorWebsocketPingTimeoutSeconds();
    }

    /**
     * Connects to the WebSocket relay.
     */
    public void connect() {
        intentionallyClosed.set(false);
        reconnectAttempts.set(0);
        doConnect();
    }

    private void doConnect() {
        if (intentionallyClosed.get()) return;

        String connectUrl = wsUrl + (wsUrl.contains("?") ? "&" : "?") + "source=plugin";
        Logger.info("[WebSocket] Connecting to: " + connectUrl);

        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(connectUrl), new WsListener())
                .thenAccept(ws -> {
                    webSocket.set(ws);
                    connected.set(true);
                    reconnectAttempts.set(0);
                    lastPingReceived = System.currentTimeMillis();
                    Logger.info("[WebSocket] Connected to session " + sessionId);
                    subscribeToEvents();
                    startPingWatchdog();
                })
                .exceptionally(e -> {
                    Logger.warn("[WebSocket] Connection failed: " + e.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    /**
     * Disconnects from the WebSocket relay.
     */
    public void disconnect() {
        intentionallyClosed.set(true);
        connected.set(false);
        unsubscribeFromEvents();
        stopPingWatchdog();

        WebSocket ws = webSocket.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disconnect");
            } catch (Exception e) {
                Logger.debug("[WebSocket] Error during close: " + e.getMessage());
            }
        }

        // Fail any pending batch applies
        pendingBatchApplies.values().forEach(f ->
                f.completeExceptionally(new RuntimeException("WebSocket disconnected")));
        pendingBatchApplies.clear();

        Logger.info("[WebSocket] Disconnected from session " + sessionId);
    }

    public boolean isConnected() {
        return connected.get();
    }

    // ==================== Outgoing: Send ops to editor ====================

    private void sendJson(JsonObject msg) {
        WebSocket ws = webSocket.get();
        if (ws != null && connected.get()) {
            String json = GSON.toJson(msg);
            ws.sendText(json, true);
        }
    }

    private void sendServerChange(String op, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "server.change");
        msg.addProperty("op", op);
        msg.add("data", data);
        sendJson(msg);
    }

    /**
     * Sends a batch of changes to the editor for application via WebSocket.
     *
     * @param changes the changes to apply
     * @return a future that completes with the result
     */
    public CompletableFuture<BatchApplyResult> sendBatchApply(@NotNull List<Change> changes) {
        String batchId = "batch-" + batchIdCounter.incrementAndGet();

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "batch.apply");
        msg.addProperty("batchId", batchId);

        JsonArray opsArray = new JsonArray();
        for (Change change : changes) {
            opsArray.add(changeToJson(change));
        }
        msg.add("ops", opsArray);

        CompletableFuture<BatchApplyResult> future = new CompletableFuture<>();
        pendingBatchApplies.put(batchId, future);

        // Timeout after 30s
        scheduler.schedule(() -> {
            CompletableFuture<BatchApplyResult> pending = pendingBatchApplies.remove(batchId);
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new RuntimeException("batch.apply timed out"));
            }
        }, 30, TimeUnit.SECONDS);

        sendJson(msg);
        return future;
    }

    // ==================== Incoming: Handle messages from editor ====================

    private void handleMessage(String text) {
        try {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            switch (type) {
                case "ping" -> handlePing();
                case "batch.apply" -> handleBatchApply(msg);
                case "session.sync" -> handleSessionSync(msg);
                case "apply.result" -> handleApplyResult(msg);
                default -> Logger.debug("[WebSocket] Unknown message type: " + type);
            }
        } catch (Exception e) {
            Logger.warn("[WebSocket] Error handling message: " + e.getMessage());
        }
    }

    private void handlePing() {
        lastPingReceived = System.currentTimeMillis();
        JsonObject pong = new JsonObject();
        pong.addProperty("type", "pong");
        sendJson(pong);
    }

    private void handleBatchApply(JsonObject msg) {
        String batchId = msg.has("batchId") ? msg.get("batchId").getAsString() : null;

        try {
            JsonArray ops = msg.has("ops") ? msg.getAsJsonArray("ops") : new JsonArray();
            List<Change> changes = new ArrayList<>();

            for (var elem : ops) {
                Change change = jsonToChange(elem.getAsJsonObject());
                if (change != null) {
                    changes.add(change);
                }
            }

            ChangeApplier.ApplyResult result = changeApplier.applyChangesFromWebSocket(changes);

            // Send result back
            JsonObject response = new JsonObject();
            response.addProperty("type", "apply.result");
            if (batchId != null) response.addProperty("batchId", batchId);
            response.addProperty("success", result.getSuccessCount());
            response.addProperty("failed", result.getFailureCount());
            if (result.hasErrors()) {
                JsonArray errors = new JsonArray();
                result.getErrors().forEach(errors::add);
                response.add("errors", errors);
            }
            sendJson(response);

            Logger.info("[WebSocket] Applied batch: " + result.getSuccessCount() + " ok, " +
                    result.getFailureCount() + " failed");
        } catch (Exception e) {
            Logger.warn("[WebSocket] Error applying batch: " + e.getMessage());
            JsonObject response = new JsonObject();
            response.addProperty("type", "apply.result");
            if (batchId != null) response.addProperty("batchId", batchId);
            response.addProperty("success", 0);
            response.addProperty("failed", 1);
            JsonArray errors = new JsonArray();
            errors.add("Internal error: " + e.getMessage());
            response.add("errors", errors);
            sendJson(response);
        }
    }

    private void handleSessionSync(JsonObject msg) {
        Logger.debug("[WebSocket] Received session.sync request");
        try {
            int playerCount = hyperPerms.getUserManager().getLoadedUsers().size();
            SessionData data = SessionData.fromHyperPerms(hyperPerms, playerCount);
            String json = GSON.toJson(data);

            JsonObject response = new JsonObject();
            response.addProperty("type", "session.sync");
            response.add("data", JsonParser.parseString(json));
            sendJson(response);

            Logger.debug("[WebSocket] Sent session.sync response");
        } catch (Exception e) {
            Logger.warn("[WebSocket] Error serializing session sync: " + e.getMessage());
        }
    }

    private void handleApplyResult(JsonObject msg) {
        String batchId = msg.has("batchId") ? msg.get("batchId").getAsString() : null;
        if (batchId == null) return;

        CompletableFuture<BatchApplyResult> future = pendingBatchApplies.remove(batchId);
        if (future != null) {
            int success = msg.has("success") ? msg.get("success").getAsInt() : 0;
            int failed = msg.has("failed") ? msg.get("failed").getAsInt() : 0;
            String undoId = msg.has("undoId") ? msg.get("undoId").getAsString() : null;
            future.complete(new BatchApplyResult(success, failed, undoId));
        }
    }

    // ==================== Event subscriptions (plugin -> editor) ====================

    private void subscribeToEvents() {
        EventBus eventBus = hyperPerms.getEventBus();

        eventSubscriptions.add(eventBus.subscribe(PermissionChangeEvent.class, this::onPermissionChange));
        eventSubscriptions.add(eventBus.subscribe(GroupCreateEvent.class, this::onGroupCreate));
        eventSubscriptions.add(eventBus.subscribe(GroupDeleteEvent.class, this::onGroupDelete));
        eventSubscriptions.add(eventBus.subscribe(GroupModifyEvent.class, this::onGroupModify));
        eventSubscriptions.add(eventBus.subscribe(UserGroupChangeEvent.class, this::onUserGroupChange));
        eventSubscriptions.add(eventBus.subscribe(TrackCreateEvent.class, this::onTrackCreate));
        eventSubscriptions.add(eventBus.subscribe(TrackDeleteEvent.class, this::onTrackDelete));
        eventSubscriptions.add(eventBus.subscribe(TrackModifyEvent.class, this::onTrackModify));
    }

    private void unsubscribeFromEvents() {
        eventSubscriptions.forEach(EventBus.Subscription::unsubscribe);
        eventSubscriptions.clear();
    }

    private boolean shouldSkipEvent() {
        return ChangeApplier.isApplyingFromWebSocket() || !connected.get();
    }

    private void onPermissionChange(PermissionChangeEvent event) {
        if (shouldSkipEvent()) return;

        String op = switch (event.getChangeType()) {
            case ADD -> "permission.add";
            case REMOVE -> "permission.remove";
            case UPDATE -> "permission.set";
            default -> null;
        };
        if (op == null) return;

        JsonObject data = new JsonObject();
        data.addProperty("holder", event.getHolder().getIdentifier());
        data.addProperty("holderType", event.getHolder() instanceof Group ? "group" : "user");
        data.addProperty("node", event.getNode().getPermission());
        data.addProperty("value", event.getNode().getValue());

        if (!event.getNode().getContexts().isEmpty()) {
            JsonObject contexts = new JsonObject();
            for (var entry : event.getNode().getContexts().toSet()) {
                contexts.addProperty(entry.key(), entry.value());
            }
            data.add("contexts", contexts);
        }

        if (event.getNode().getExpiry() != null) {
            data.addProperty("expiry", event.getNode().getExpiry().toEpochMilli());
        }

        sendServerChange(op, data);
    }

    private void onGroupCreate(GroupCreateEvent event) {
        if (shouldSkipEvent()) return;
        if (event.getState() != GroupCreateEvent.State.POST) return;

        JsonObject data = new JsonObject();
        data.addProperty("name", event.getGroupName());
        sendServerChange("group.create", data);
    }

    private void onGroupDelete(GroupDeleteEvent event) {
        if (shouldSkipEvent()) return;
        if (event.getState() != GroupDeleteEvent.State.POST) return;

        JsonObject data = new JsonObject();
        data.addProperty("name", event.getGroupName());
        sendServerChange("group.delete", data);
    }

    private void onGroupModify(GroupModifyEvent event) {
        if (shouldSkipEvent()) return;

        switch (event.getModifyType()) {
            case PARENT_ADD -> {
                JsonObject data = new JsonObject();
                data.addProperty("group", event.getGroup().getName());
                data.addProperty("parent", String.valueOf(event.getNewValue()));
                sendServerChange("group.addParent", data);
            }
            case PARENT_REMOVE -> {
                JsonObject data = new JsonObject();
                data.addProperty("group", event.getGroup().getName());
                data.addProperty("parent", String.valueOf(event.getOldValue()));
                sendServerChange("group.removeParent", data);
            }
            default -> {
                // Meta changes (weight, prefix, suffix, etc.) - send generic modify
                JsonObject data = new JsonObject();
                data.addProperty("group", event.getGroup().getName());
                data.addProperty("property", event.getModifyType().name().toLowerCase());
                if (event.getOldValue() != null) {
                    data.addProperty("oldValue", String.valueOf(event.getOldValue()));
                }
                if (event.getNewValue() != null) {
                    data.addProperty("newValue", String.valueOf(event.getNewValue()));
                }
                sendServerChange("group.modify", data);
            }
        }
    }

    private void onUserGroupChange(UserGroupChangeEvent event) {
        if (shouldSkipEvent()) return;

        String op = switch (event.getChangeType()) {
            case ADD -> "user.addGroup";
            case REMOVE -> "user.removeGroup";
            default -> null;
        };
        if (op == null) return;

        JsonObject data = new JsonObject();
        data.addProperty("uuid", event.getUuid().toString());
        data.addProperty("group", event.getGroupName());
        sendServerChange(op, data);
    }

    private void onTrackCreate(TrackCreateEvent event) {
        if (shouldSkipEvent()) return;
        if (event.getState() != TrackCreateEvent.State.POST) return;

        JsonObject data = new JsonObject();
        data.addProperty("name", event.getTrackName());
        if (event.getTrack() != null) {
            JsonArray groups = new JsonArray();
            event.getTrack().getGroups().forEach(groups::add);
            data.add("groups", groups);
        }
        sendServerChange("track.create", data);
    }

    private void onTrackDelete(TrackDeleteEvent event) {
        if (shouldSkipEvent()) return;
        if (event.getState() != TrackDeleteEvent.State.POST) return;

        JsonObject data = new JsonObject();
        data.addProperty("name", event.getTrackName());
        sendServerChange("track.delete", data);
    }

    private void onTrackModify(TrackModifyEvent event) {
        if (shouldSkipEvent()) return;

        JsonObject data = new JsonObject();
        data.addProperty("name", event.getTrack().getName());
        JsonArray groups = new JsonArray();
        event.getNewGroups().forEach(groups::add);
        data.add("groups", groups);
        sendServerChange("track.modify", data);
    }

    // ==================== Change serialization ====================

    private JsonObject changeToJson(Change change) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", change.getType().name().toLowerCase());
        if (change.getTargetType() != null) obj.addProperty("targetType", change.getTargetType());
        if (change.getTarget() != null) obj.addProperty("target", change.getTarget());
        if (change.getNode() != null) obj.addProperty("node", change.getNode());
        if (change.getValue() != null) obj.addProperty("value", change.getValue());
        if (change.getGroupName() != null) obj.addProperty("groupName", change.getGroupName());
        if (change.getParent() != null) obj.addProperty("parent", change.getParent());
        if (change.getTrackName() != null) obj.addProperty("trackName", change.getTrackName());
        if (change.getNewGroups() != null) {
            JsonArray arr = new JsonArray();
            change.getNewGroups().forEach(arr::add);
            obj.add("newGroups", arr);
        }
        return obj;
    }

    @Nullable
    private Change jsonToChange(JsonObject obj) {
        String typeStr = obj.has("type") ? obj.get("type").getAsString() : null;
        if (typeStr == null) return null;

        // Map op-style types to Change.Type
        Change.Type type = switch (typeStr) {
            case "permission.add", "permission_added" -> Change.Type.PERMISSION_ADDED;
            case "permission.remove", "permission_removed" -> Change.Type.PERMISSION_REMOVED;
            case "permission.set", "permission_modified" -> Change.Type.PERMISSION_MODIFIED;
            case "group.create", "group_created" -> Change.Type.GROUP_CREATED;
            case "group.delete", "group_deleted" -> Change.Type.GROUP_DELETED;
            case "group.addParent", "parent_added" -> Change.Type.PARENT_ADDED;
            case "group.removeParent", "parent_removed" -> Change.Type.PARENT_REMOVED;
            case "group.modify", "meta_changed" -> Change.Type.META_CHANGED;
            case "weight_changed" -> Change.Type.WEIGHT_CHANGED;
            case "track.create", "track_created" -> Change.Type.TRACK_CREATED;
            case "track.delete", "track_deleted" -> Change.Type.TRACK_DELETED;
            case "track.modify", "track_modified" -> Change.Type.TRACK_MODIFIED;
            default -> null;
        };
        if (type == null) {
            Logger.warn("[WebSocket] Unknown change type: " + typeStr);
            return null;
        }

        Change.Builder builder = Change.builder(type);

        if (obj.has("targetType")) builder.targetType(obj.get("targetType").getAsString());
        if (obj.has("target")) builder.target(obj.get("target").getAsString());
        if (obj.has("node")) builder.node(obj.get("node").getAsString());
        if (obj.has("value") && !obj.get("value").isJsonNull()) builder.value(obj.get("value").getAsBoolean());
        if (obj.has("groupName")) builder.groupName(obj.get("groupName").getAsString());
        if (obj.has("parent")) builder.parent(obj.get("parent").getAsString());
        if (obj.has("trackName")) builder.trackName(obj.get("trackName").getAsString());
        if (obj.has("key")) builder.key(obj.get("key").getAsString());
        if (obj.has("newValue") && obj.get("newValue").isJsonPrimitive()) {
            if (obj.get("newValue").getAsJsonPrimitive().isString()) {
                builder.metaNewValue(obj.get("newValue").getAsString());
            } else if (obj.get("newValue").getAsJsonPrimitive().isBoolean()) {
                builder.newValue(obj.get("newValue").getAsBoolean());
            }
        }
        if (obj.has("newGroups") && obj.get("newGroups").isJsonArray()) {
            List<String> groups = new ArrayList<>();
            obj.getAsJsonArray("newGroups").forEach(e -> groups.add(e.getAsString()));
            builder.newGroups(groups);
        }
        if (obj.has("contexts") && obj.get("contexts").isJsonObject()) {
            Map<String, String> contexts = new HashMap<>();
            obj.getAsJsonObject("contexts").entrySet().forEach(e ->
                    contexts.put(e.getKey(), e.getValue().getAsString()));
            builder.contexts(contexts);
        }
        if (obj.has("expiry") && !obj.get("expiry").isJsonNull()) {
            builder.expiry(obj.get("expiry").getAsLong());
        }
        if (obj.has("group") && obj.get("group").isJsonObject()) {
            JsonObject groupObj = obj.getAsJsonObject("group");
            builder.group(parseGroupData(groupObj));
        }
        if (obj.has("track") && obj.get("track").isJsonObject()) {
            JsonObject trackObj = obj.getAsJsonObject("track");
            String name = trackObj.has("name") ? trackObj.get("name").getAsString() : "";
            List<String> groups = new ArrayList<>();
            if (trackObj.has("groups") && trackObj.get("groups").isJsonArray()) {
                trackObj.getAsJsonArray("groups").forEach(e -> groups.add(e.getAsString()));
            }
            builder.track(new Change.TrackData(name, groups));
        }

        return builder.build();
    }

    private Change.GroupData parseGroupData(JsonObject obj) {
        String name = obj.has("name") ? obj.get("name").getAsString() : "";
        String displayName = obj.has("displayName") ? obj.get("displayName").getAsString() : null;
        int weight = obj.has("weight") ? obj.get("weight").getAsInt() : 0;
        String prefix = obj.has("prefix") ? obj.get("prefix").getAsString() : null;
        String suffix = obj.has("suffix") ? obj.get("suffix").getAsString() : null;
        int prefixPriority = obj.has("prefixPriority") ? obj.get("prefixPriority").getAsInt() : 0;
        int suffixPriority = obj.has("suffixPriority") ? obj.get("suffixPriority").getAsInt() : 0;

        List<Change.PermissionNode> perms = new ArrayList<>();
        if (obj.has("permissions") && obj.get("permissions").isJsonArray()) {
            for (var elem : obj.getAsJsonArray("permissions")) {
                JsonObject p = elem.getAsJsonObject();
                String node = p.has("node") ? p.get("node").getAsString() : null;
                if (node == null) continue;
                boolean value = !p.has("value") || p.get("value").getAsBoolean();
                Map<String, String> ctx = Collections.emptyMap();
                if (p.has("contexts") && p.get("contexts").isJsonObject()) {
                    ctx = new HashMap<>();
                    for (var e : p.getAsJsonObject("contexts").entrySet()) {
                        ctx.put(e.getKey(), e.getValue().getAsString());
                    }
                }
                Long expiry = p.has("expiry") && !p.get("expiry").isJsonNull()
                        ? p.get("expiry").getAsLong() : null;
                perms.add(new Change.PermissionNode(node, value, ctx, expiry));
            }
        }

        List<String> parents = new ArrayList<>();
        if (obj.has("parents") && obj.get("parents").isJsonArray()) {
            obj.getAsJsonArray("parents").forEach(e -> parents.add(e.getAsString()));
        }

        return new Change.GroupData(name, displayName, weight, prefix, suffix,
                prefixPriority, suffixPriority, perms, parents);
    }

    // ==================== Reconnection ====================

    private void scheduleReconnect() {
        if (intentionallyClosed.get()) return;

        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > maxReconnectAttempts) {
            Logger.warn("[WebSocket] Max reconnect attempts (" + maxReconnectAttempts + ") reached. Giving up.");
            connected.set(false);
            return;
        }

        // Exponential backoff: 1s, 2s, 4s, 8s... capped at maxReconnectDelaySeconds
        long delaySec = Math.min((long) Math.pow(2, attempt - 1), maxReconnectDelaySeconds);
        Logger.info("[WebSocket] Reconnecting in " + delaySec + "s (attempt " + attempt + "/" + maxReconnectAttempts + ")");

        scheduler.schedule(this::doConnect, delaySec, TimeUnit.SECONDS);
    }

    // ==================== Ping watchdog ====================

    private void startPingWatchdog() {
        stopPingWatchdog();
        pingWatchdog = scheduler.scheduleAtFixedRate(() -> {
            if (!connected.get() || intentionallyClosed.get()) return;

            long elapsed = System.currentTimeMillis() - lastPingReceived;
            if (elapsed > pingTimeoutSeconds * 1000L) {
                Logger.warn("[WebSocket] No ping for " + (elapsed / 1000) + "s, reconnecting...");
                WebSocket ws = webSocket.getAndSet(null);
                connected.set(false);
                if (ws != null) {
                    try {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Ping timeout");
                    } catch (Exception ignored) {}
                }
                unsubscribeFromEvents();
                scheduleReconnect();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopPingWatchdog() {
        if (pingWatchdog != null) {
            pingWatchdog.cancel(false);
            pingWatchdog = null;
        }
    }

    // ==================== WebSocket listener ====================

    private class WsListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            Logger.debug("[WebSocket] Connection opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                String fullMessage = messageBuffer.toString();
                messageBuffer.setLength(0);
                handleMessage(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastPingReceived = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            Logger.info("[WebSocket] Connection closed: " + statusCode + " " + reason);
            connected.set(false);
            WebSocketSessionClient.this.webSocket.set(null);
            unsubscribeFromEvents();
            stopPingWatchdog();

            if (!intentionallyClosed.get()) {
                scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Logger.warn("[WebSocket] Error: " + error.getMessage());
            connected.set(false);
            WebSocketSessionClient.this.webSocket.set(null);
            unsubscribeFromEvents();
            stopPingWatchdog();

            if (!intentionallyClosed.get()) {
                scheduleReconnect();
            }
        }
    }

    // ==================== Result types ====================

    /**
     * Result of a batch apply operation sent over WebSocket.
     */
    public static final class BatchApplyResult {
        private final int successCount;
        private final int failedCount;
        private final String undoId;

        public BatchApplyResult(int successCount, int failedCount, @Nullable String undoId) {
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.undoId = undoId;
        }

        public int getSuccessCount() { return successCount; }
        public int getFailedCount() { return failedCount; }
        @Nullable public String getUndoId() { return undoId; }
        public boolean hasErrors() { return failedCount > 0; }
    }
}
