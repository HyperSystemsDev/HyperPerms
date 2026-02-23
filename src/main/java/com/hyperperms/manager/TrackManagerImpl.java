package com.hyperperms.manager;

import com.hyperperms.api.HyperPermsAPI.TrackManager;
import com.hyperperms.api.events.EventBus;
import com.hyperperms.api.events.TrackCreateEvent;
import com.hyperperms.api.events.TrackDeleteEvent;
import com.hyperperms.api.events.TrackModifyEvent;
import com.hyperperms.model.Track;
import com.hyperperms.storage.StorageProvider;
import com.hyperperms.util.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of the track manager.
 */
public final class TrackManagerImpl implements TrackManager {

    private final StorageProvider storage;
    private final EventBus eventBus;
    private final Map<String, Track> loadedTracks = new ConcurrentHashMap<>();

    public TrackManagerImpl(@NotNull StorageProvider storage, @NotNull EventBus eventBus) {
        this.storage = storage;
        this.eventBus = eventBus;
    }

    @Override
    @Nullable
    public Track getTrack(@NotNull String name) {
        return loadedTracks.get(name.toLowerCase());
    }

    @Override
    public CompletableFuture<Optional<Track>> loadTrack(@NotNull String name) {
        String lowerName = name.toLowerCase();
        Track cached = loadedTracks.get(lowerName);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }

        return storage.loadTrack(lowerName).thenApply(opt -> {
            opt.ifPresent(track -> loadedTracks.put(lowerName, track));
            return opt;
        });
    }

    @Override
    @NotNull
    public Track createTrack(@NotNull String name) {
        String lowerName = name.toLowerCase();

        // Fire PRE event
        TrackCreateEvent preEvent = new TrackCreateEvent(lowerName);
        eventBus.fire(preEvent);
        if (preEvent.isCancelled()) {
            throw new IllegalStateException("Track creation cancelled by event handler: " + name);
        }

        Track track = new Track(lowerName);

        // putIfAbsent is atomic - prevents concurrent duplicate creation
        Track existing = loadedTracks.putIfAbsent(lowerName, track);
        if (existing != null) {
            throw new IllegalArgumentException("Track already exists: " + name);
        }

        storage.saveTrack(track);
        Logger.info("Created track: " + name);

        // Fire POST event
        eventBus.fire(new TrackCreateEvent(track));

        return track;
    }

    @Override
    public CompletableFuture<Void> deleteTrack(@NotNull String name) {
        String lowerName = name.toLowerCase();
        Track track = loadedTracks.get(lowerName);

        // Fire PRE event if track exists
        if (track != null) {
            TrackDeleteEvent preEvent = new TrackDeleteEvent(track);
            eventBus.fire(preEvent);
            if (preEvent.isCancelled()) {
                return CompletableFuture.completedFuture(null);
            }
        }

        loadedTracks.remove(lowerName);
        return storage.deleteTrack(lowerName).thenRun(() -> {
            // Fire POST event
            eventBus.fire(new TrackDeleteEvent(lowerName));
        });
    }

    @Override
    public CompletableFuture<Void> saveTrack(@NotNull Track track) {
        // Capture old groups for modify event
        Track existing = loadedTracks.get(track.getName());
        List<String> oldGroups = existing != null ? List.copyOf(existing.getGroups()) : List.of();

        loadedTracks.put(track.getName(), track);
        return storage.saveTrack(track).thenRun(() -> {
            // Fire modify event if groups changed
            List<String> newGroups = track.getGroups();
            if (!oldGroups.equals(newGroups)) {
                eventBus.fire(new TrackModifyEvent(track, oldGroups, newGroups));
            }
        });
    }

    @Override
    @NotNull
    public Set<Track> getLoadedTracks() {
        return Collections.unmodifiableSet(new HashSet<>(loadedTracks.values()));
    }

    @Override
    @NotNull
    public Set<String> getTrackNames() {
        return Collections.unmodifiableSet(new HashSet<>(loadedTracks.keySet()));
    }

    /**
     * Loads all tracks from storage.
     *
     * @return a future that completes when loaded
     */
    public CompletableFuture<Void> loadAll() {
        return storage.loadAllTracks().thenAccept(tracks -> {
            loadedTracks.putAll(tracks);
            Logger.info("Loaded %d tracks from storage", tracks.size());
        });
    }
}
