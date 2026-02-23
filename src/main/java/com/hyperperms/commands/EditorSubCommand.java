package com.hyperperms.commands;

import com.hyperperms.HyperPerms;
import com.hyperperms.util.Logger;
import com.hyperperms.web.WebEditorService;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import java.util.concurrent.CompletableFuture;

/**
 * Subcommand for /hp editor.
 * Creates a web editor session and returns a clickable URL.
 */
public class EditorSubCommand extends AbstractCommand {

    private final HyperPerms hyperPerms;
    private final WebEditorService webEditorService;

    public EditorSubCommand(HyperPerms hyperPerms, WebEditorService webEditorService) {
        super("editor", "Open the web editor to manage permissions");
        this.hyperPerms = hyperPerms;
        this.webEditorService = webEditorService;
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext ctx) {
        ctx.sender().sendMessage(Message.raw("Creating web editor session..."));

        // Get current player count (loaded users as approximation)
        int playerCount = hyperPerms.getUserManager().getLoadedUsers().size();

        // Create session asynchronously
        webEditorService.createSession(playerCount)
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        // Fix URL if server returned localhost
                        String editorUrl = response.getUrl();
                        String configuredBase = hyperPerms.getConfig().getWebEditorUrl();
                        if (editorUrl != null && editorUrl.contains("localhost")) {
                            editorUrl = editorUrl.replaceFirst("https?://localhost(:\\d+)?", configuredBase);
                        }
                        
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("=== Web Editor Ready ==="));
                        ctx.sender().sendMessage(Message.raw(""));

                        // Clickable link for in-game players
                        ctx.sender().sendMessage(
                            Message.raw("[Click here to open the editor]")
                                .color(new java.awt.Color(0x55FF55))
                                .bold(true)
                                .link(editorUrl)
                        );
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("Session ID: " + response.getSessionId()));
                        if (response.getExpiresAt() != null && !response.getExpiresAt().isEmpty()) {
                            ctx.sender().sendMessage(Message.raw("Expires at: " + response.getExpiresAt()));
                        }
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("After making changes, use /hp apply <session-id>"));
                        ctx.sender().sendMessage(Message.raw(""));

                        // Alternative manual entry option with plain URL for console users
                        String manualUrl = hyperPerms.getConfig().getWebEditorUrl() + "/editor";
                        ctx.sender().sendMessage(
                            Message.raw("Or visit ")
                                .insert(Message.raw("hyperperms.com/editor")
                                    .color(new java.awt.Color(0x5555FF))
                                    .link(manualUrl))
                                .insert(Message.raw(" and enter your session ID manually."))
                        );

                        // Connect WebSocket if available and enabled
                        if (response.getWsUrl() != null && hyperPerms.getConfig().isWebEditorWebsocketEnabled()) {
                            webEditorService.connectWebSocket(response.getSessionId(), response.getWsUrl());
                            ctx.sender().sendMessage(Message.raw("Live connection established - changes sync in realtime.")
                                    .color(new java.awt.Color(0x55FFFF)));
                        }

                        // Log full URL to console for easy copying
                        Logger.info("Web editor session created: " + response.getSessionId());
                        Logger.info("Editor URL: " + editorUrl);
                    } else {
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("Failed to create editor session: " + response.getError()));
                        ctx.sender().sendMessage(Message.raw("Please check the web editor URL in config.json"));
                    }
                })
                .exceptionally(e -> {
                    ctx.sender().sendMessage(Message.raw("Error creating editor session: " + e.getMessage()));
                    Logger.warn("Failed to create editor session: " + e.getMessage());
                    return null;
                });

        // Return immediately, response will be sent async
        return CompletableFuture.completedFuture(null);
    }
}
