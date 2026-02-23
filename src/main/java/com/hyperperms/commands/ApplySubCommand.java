package com.hyperperms.commands;

import com.hyperperms.HyperPerms;
import com.hyperperms.util.Logger;
import com.hyperperms.web.ChangeApplier;
import com.hyperperms.web.WebEditorService;
import com.hyperperms.web.WebSocketSessionClient;
import com.hyperperms.web.dto.Change;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Subcommand for /hp apply <code>.
 * Applies changes from the web editor using the provided apply code.
 */
public class ApplySubCommand extends AbstractCommand {

    private final HyperPerms hyperPerms;
    private final WebEditorService webEditorService;
    private final ChangeApplier changeApplier;
    private final RequiredArg<String> codeArg;

    @SuppressWarnings("this-escape")
    public ApplySubCommand(HyperPerms hyperPerms, WebEditorService webEditorService) {
        super("apply", "Apply changes from the web editor");
        this.hyperPerms = hyperPerms;
        this.webEditorService = webEditorService;
        this.changeApplier = new ChangeApplier(hyperPerms);
        this.codeArg = withRequiredArg("sessionId", "The session ID from the web editor", ArgTypes.STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext ctx) {
        String sessionId = ctx.get(codeArg);

        if (sessionId == null || sessionId.isEmpty()) {
            ctx.sender().sendMessage(Message.raw("Usage: /hp apply <session-id>"));
            ctx.sender().sendMessage(Message.raw("Get the session ID from the web editor after making changes."));
            return CompletableFuture.completedFuture(null);
        }

        // Try WebSocket apply if connected, otherwise fall back to REST
        WebSocketSessionClient wsClient = webEditorService.getActiveWsClient();
        if (wsClient != null && wsClient.isConnected()) {
            ctx.sender().sendMessage(Message.raw("Fetching changes via live connection..."));

            webEditorService.fetchChanges(sessionId)
                    .thenCompose(result -> {
                        if (!result.isSuccess()) {
                            ctx.sender().sendMessage(Message.raw("Failed to fetch changes: " + result.getError()));
                            return CompletableFuture.completedFuture((Void) null);
                        }

                        List<Change> changes = result.getChanges();
                        if (changes == null || changes.isEmpty()) {
                            ctx.sender().sendMessage(Message.raw("No changes found for this session."));
                            return CompletableFuture.completedFuture((Void) null);
                        }

                        ctx.sender().sendMessage(Message.raw("Found " + changes.size() + " change(s). Applying via WebSocket..."));

                        return wsClient.sendBatchApply(changes)
                                .thenAccept(batchResult -> {
                                    ctx.sender().sendMessage(Message.raw(""));
                                    ctx.sender().sendMessage(Message.raw("=== Apply Results (WebSocket) ==="));
                                    ctx.sender().sendMessage(Message.raw("Successful: " + batchResult.getSuccessCount()));
                                    ctx.sender().sendMessage(Message.raw("Failed: " + batchResult.getFailedCount()));
                                    if (batchResult.getSuccessCount() > 0) {
                                        ctx.sender().sendMessage(Message.raw(""));
                                        ctx.sender().sendMessage(Message.raw("Changes applied and synced."));
                                        Logger.info("Applied " + batchResult.getSuccessCount() + " changes via WebSocket for session " + sessionId);
                                    }
                                });
                    })
                    .exceptionally(e -> {
                        ctx.sender().sendMessage(Message.raw("WebSocket apply failed, falling back to REST: " + e.getMessage()));
                        Logger.warn("WebSocket apply failed: " + e.getMessage());
                        applyViaRest(ctx, sessionId);
                        return null;
                    });
        } else {
            applyViaRest(ctx, sessionId);
        }

        return CompletableFuture.completedFuture(null);
    }

    private void applyViaRest(CommandContext ctx, String sessionId) {
        ctx.sender().sendMessage(Message.raw("Fetching changes from web editor..."));

        webEditorService.fetchChanges(sessionId)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        ctx.sender().sendMessage(Message.raw("Failed to fetch changes: " + result.getError()));
                        return;
                    }

                    List<Change> changes = result.getChanges();
                    if (changes == null || changes.isEmpty()) {
                        ctx.sender().sendMessage(Message.raw("No changes found for this session."));
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("This can happen if:"));
                        ctx.sender().sendMessage(Message.raw("  - No edits were made in the web editor"));
                        ctx.sender().sendMessage(Message.raw("  - The session expired (sessions last 24 hours)"));
                        ctx.sender().sendMessage(Message.raw("  - The session ID was copied incorrectly"));
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("Check server logs for 'Changes API response' for details."));
                        return;
                    }

                    ctx.sender().sendMessage(Message.raw("Found " + changes.size() + " change(s). Applying..."));

                    ChangeApplier.ApplyResult applyResult = changeApplier.applyChanges(changes);

                    ctx.sender().sendMessage(Message.raw(""));
                    ctx.sender().sendMessage(Message.raw("=== Apply Results ==="));
                    ctx.sender().sendMessage(Message.raw("Successful: " + applyResult.getSuccessCount()));
                    ctx.sender().sendMessage(Message.raw("Failed: " + applyResult.getFailureCount()));

                    if (applyResult.hasErrors()) {
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("Errors:"));
                        for (String error : applyResult.getErrors()) {
                            ctx.sender().sendMessage(Message.raw("  - " + error));
                        }
                    }

                    if (applyResult.getSuccessCount() > 0) {
                        ctx.sender().sendMessage(Message.raw(""));
                        ctx.sender().sendMessage(Message.raw("Changes have been applied and saved."));
                        Logger.info("Applied " + applyResult.getSuccessCount() + " changes from web editor session " + sessionId);
                    }
                })
                .exceptionally(e -> {
                    ctx.sender().sendMessage(Message.raw("Error applying changes: " + e.getMessage()));
                    Logger.warn("Failed to apply changes: " + e.getMessage());
                    return null;
                });
    }
}
