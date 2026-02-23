# Realtime WebSocket Editor Design

**Date:** 2026-02-22
**Status:** Approved
**Scope:** HyperPerms plugin, HyperPermsWeb editor, new CF Workers relay

## Problem

The web editor at hyperperms.com uses a polling/REST model: the plugin POSTs session
data to the API, the user edits in the browser, then runs `/hp apply <session>` to pull
changes back. This is slow, manual, and one-directional.

## Goal

Two-way realtime sync between the browser editor and the Hytale plugin via WebSocket,
so edits flow in both directions without manual commands.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Direction | Two-way full sync | Editor pushes to server, server pushes to editor |
| Transport | WebSocket via Cloudflare Durable Objects | Already on CF Workers, no extra vendor, ~$5/month |
| Relay model | API-side (CF DO) | Both plugin and browser connect as WS clients to CF |
| Default apply mode | Batch with confirm | Safety: changes accumulate, user clicks Apply |
| Live mode | Toggle in editor UI | Opt-in immediate apply for power users |
| Undo | Server-side undo history (last 20 ops) | Reversible batch applies |
| Message format | Incremental diffs (operations) | Small payloads, natural undo, efficient |
| Conflict handling | Auto-merge non-conflicting, UI for conflicts | Server changes to different entities merge silently |

## Architecture

```
Browser (Next.js)              CF Durable Object            Hytale Plugin (Java)
     |                        (1 DO per session)                    |
     |--- WS connect -------->|                                     |
     |                         |<-------- WS connect ---------------|
     |                         |                                     |
     |-- editor.change ------->|  (stores op in pending list)       |
     |                         |                                     |
     |-- batch.apply --------->|                                     |
     |                         |--- batch.apply ------------------->|
     |                         |                    [applies to live server]
     |                         |<--- apply.result ------------------|
     |<-- apply.result --------|                                     |
     |                         |                                     |
     |                         |<--- server.change -----------------|
     |<-- server.change -------|                    [in-game command ran]
```

### Session Lifecycle

1. Player runs `/hp editor` in-game
2. Plugin creates session via existing REST API (POST /api/session/create)
3. API returns session ID + editor URL (unchanged)
4. Plugin opens WebSocket to `wss://ws.hyperperms.com/session/<id>` as `plugin` role
5. Player opens editor in browser, editor opens WebSocket to same URL as `editor` role
6. DO has both connections -- relay begins
7. On disconnect/timeout, DO hibernates (cost-efficient)
8. Session expires after 24h (same as current TTL)

### Why CF Durable Objects

- CF Workers already in use (no new vendor)
- DOs can hold persistent WebSocket connections (unlike Vercel serverless)
- Built-in hibernation API saves costs when idle
- Native WebSocket support with `acceptWebSocket()` API
- Cost: ~$0.15/M requests + $0.50/GB-month -- effectively free at our volume
- Stays within Cloudflare ecosystem

## Message Protocol

### Operation Types

All messages are JSON with a `type` field. Operations are the atomic unit of change.

```typescript
// === Editor -> DO -> Plugin ===

// Individual changes (accumulated in batch mode, applied immediately in live mode)
{ type: "op", op: "permission.add", target: "group:admin", node: "server.kick", value: true }
{ type: "op", op: "permission.remove", target: "group:admin", node: "server.kick" }
{ type: "op", op: "permission.set", target: "user:<uuid>", node: "chat.color", value: false }
{ type: "op", op: "group.create", name: "moderator", weight: 50, parents: ["default"] }
{ type: "op", op: "group.delete", name: "moderator" }
{ type: "op", op: "group.setMeta", target: "admin", key: "prefix", value: "&c[Admin] " }
{ type: "op", op: "group.setWeight", target: "admin", weight: 100 }
{ type: "op", op: "group.addParent", target: "moderator", parent: "default" }
{ type: "op", op: "group.removeParent", target: "moderator", parent: "default" }
{ type: "op", op: "user.addGroup", target: "<uuid>", group: "admin" }
{ type: "op", op: "user.removeGroup", target: "<uuid>", group: "admin" }
{ type: "op", op: "track.create", name: "staff", groups: ["default", "helper", "mod", "admin"] }
{ type: "op", op: "track.delete", name: "staff" }
{ type: "op", op: "track.addGroup", target: "staff", group: "moderator", position: 2 }
{ type: "op", op: "track.removeGroup", target: "staff", group: "moderator" }

// Batch apply (batch mode only)
{ type: "batch.apply" }

// Mode switch
{ type: "session.mode", mode: "live" | "batch" }

// === Plugin -> DO -> Editor ===

// Server-side change (in-game command, API, another plugin)
{ type: "server.change", op: "permission.add", target: "group:admin", node: "fly.use",
  value: true, source: "console", timestamp: 1708617600000 }

// Apply result
{ type: "apply.result", success: true, applied: 5, failed: 0,
  errors: [], undoId: "abc123" }

// Undo result
{ type: "undo.result", success: true, undoId: "abc123", reverted: 5 }

// === Control messages (both directions) ===

{ type: "session.sync", data: { groups: [...], users: [...], tracks: [...] } }
{ type: "session.ping" }
{ type: "session.pong" }
{ type: "session.error", code: "CONFLICT", message: "...", details: {...} }
```

### Operation Inversion (for undo)

Each operation has a natural inverse stored in the undo history:

| Operation | Inverse |
|---|---|
| `permission.add` | `permission.remove` (same target/node) |
| `permission.remove` | `permission.add` (with original value) |
| `group.create` | `group.delete` |
| `group.delete` | `group.create` (with full group data snapshot) |
| `group.setWeight` | `group.setWeight` (with previous weight) |
| `user.addGroup` | `user.removeGroup` |
| `user.removeGroup` | `user.addGroup` |
| `track.addGroup` | `track.removeGroup` |

## Conflict Detection

### Non-conflicting (auto-merge)

Server change to entity A while editor has pending changes to entity B:
- Apply server change to editor state silently
- Show subtle indicator that server state updated (e.g. pulse animation on the changed entity)

### Conflicting

Server change to entity A while editor has pending changes to entity A:
- Mark the entity with a conflict indicator in the UI
- Show conflict banner: "Server changed [group: admin] -- your local changes may conflict"
- Options: "Keep mine", "Accept server", "View diff"
- Conflict state clears when user resolves

### Detection Logic

Conflict = same `target` value in both a pending editor op and an incoming server change.
Tracked per-entity, not per-field (simple and predictable).

## Undo History

- Stored in the Durable Object's transactional storage
- Last 20 batch applies, each with its inverse operations
- Each undo entry: `{ undoId, timestamp, ops: Operation[], inverseOps: Operation[] }`
- Undo request: `{ type: "undo", undoId: "abc123" }` -> sends inverse ops to plugin
- History clears on session expiry

## Three Codebases

### 1. CF Workers (new Durable Object)

**Location:** New worker in existing CF project or standalone
**Responsibilities:**
- Accept WebSocket connections from editor and plugin
- Route messages between them based on type
- Store pending operations (batch mode)
- Maintain undo history
- Handle hibernation for cost efficiency
- Session authentication (validate session ID against Upstash Redis)

**Key files:**
- `src/session-do.ts` -- Durable Object class with WebSocket handlers
- `src/index.ts` -- Worker entry point, routes `/session/:id` to DO
- `wrangler.toml` -- DO binding configuration

### 2. HyperPermsWeb (Next.js editor)

**Changes:**
- New WebSocket hook: `useRealtimeSession(sessionId)` -- manages WS connection, reconnection, message handling
- Editor state: track pending operations, conflict state
- UI: "Live" toggle switch, Apply button (batch mode), conflict banners, undo button
- Connection status indicator (connected/reconnecting/disconnected)
- Replace current `PUT /api/session` save flow with WebSocket ops

**Key files:**
- `src/hooks/useRealtimeSession.ts` -- WebSocket connection + state management
- `src/components/editor/RealtimeStatus.tsx` -- Connection indicator
- `src/components/editor/ConflictBanner.tsx` -- Conflict resolution UI
- `src/components/editor/LiveModeToggle.tsx` -- Live/batch toggle
- Modifications to existing editor components to dispatch ops instead of direct state mutation

### 3. HyperPerms (Java plugin)

**Changes:**
- New `WebSocketClient` class using Java 11+ `HttpClient` WebSocket API
- Change listener: hook into existing EventBus to detect in-game permission changes
- Apply handler: receive ops from editor, apply them through existing manager layer
- Undo support: store pre-apply snapshots for revert
- Auto-reconnect on connection loss
- New config options: `webEditor.realtimeEnabled`, `webEditor.wsUrl`

**Key files:**
- `src/main/java/com/hyperperms/web/RealtimeClient.java` -- WebSocket client
- `src/main/java/com/hyperperms/web/RealtimeMessageHandler.java` -- Message routing
- `src/main/java/com/hyperperms/web/OperationApplier.java` -- Applies ops to live state
- `src/main/java/com/hyperperms/web/ChangeListener.java` -- Detects in-game changes, sends to WS
- Modifications to `WebEditorService.java` -- Start WS after session creation

## Security

- Session ID serves as auth token (same as current model)
- DO validates session exists in Redis before accepting WS upgrade
- Plugin identifies as `role: plugin` on connect, editor as `role: editor`
- Only one plugin connection per session (reject duplicates)
- Rate limiting on operations (prevent abuse from editor)
- All traffic over WSS (TLS)

## Migration / Backwards Compatibility

- Existing REST flow continues to work (no breaking changes)
- WebSocket is additive -- if WS connection fails, editor falls back to REST save/load
- Plugin config: `webEditor.realtimeEnabled: true` (default true for new installs)
- `/hp apply` command still works as fallback
