package com.hyperperms.web.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Response from the web editor API when creating a session.
 */
public final class SessionCreateResponse {

    private final String sessionId;
    private final String url;
    private final String expiresAt;
    private final String wsUrl;
    private final String error;

    public SessionCreateResponse(
            @NotNull String sessionId,
            @NotNull String url,
            @NotNull String expiresAt,
            @Nullable String wsUrl
    ) {
        this.sessionId = sessionId;
        this.url = url;
        this.expiresAt = expiresAt;
        this.wsUrl = wsUrl;
        this.error = null;
    }

    private SessionCreateResponse(@NotNull String error) {
        this.sessionId = null;
        this.url = null;
        this.expiresAt = null;
        this.wsUrl = null;
        this.error = error;
    }

    /**
     * Creates an error response.
     */
    public static SessionCreateResponse error(@NotNull String message) {
        return new SessionCreateResponse(message);
    }

    @Nullable
    public String getSessionId() {
        return sessionId;
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    @Nullable
    public String getExpiresAt() {
        return expiresAt;
    }

    /**
     * Gets the WebSocket URL for realtime sync.
     *
     * @return the WebSocket URL, or null if not available
     */
    @Nullable
    public String getWsUrl() {
        return wsUrl;
    }

    @Nullable
    public String getError() {
        return error;
    }

    public boolean isSuccess() {
        return error == null && sessionId != null;
    }
}
