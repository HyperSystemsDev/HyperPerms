package com.hyperperms.api.events;

import com.hyperperms.model.Track;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired when a track is created.
 * <p>
 * This event can be cancelled to prevent the track from being created.
 * The PRE state fires before creation, the POST state fires after.
 */
public final class TrackCreateEvent implements HyperPermsEvent, Cancellable {

    /**
     * The state of the track creation event.
     */
    public enum State {
        /**
         * Before the track is created. The event can be cancelled at this point.
         */
        PRE,

        /**
         * After the track has been created. Cancellation has no effect.
         */
        POST
    }

    private final String trackName;
    private final Track track;
    private final State state;
    private boolean cancelled;

    /**
     * Creates a PRE event for track creation.
     *
     * @param trackName the name of the track being created
     */
    public TrackCreateEvent(@NotNull String trackName) {
        this.trackName = trackName;
        this.track = null;
        this.state = State.PRE;
        this.cancelled = false;
    }

    /**
     * Creates a POST event for track creation.
     *
     * @param track the created track
     */
    public TrackCreateEvent(@NotNull Track track) {
        this.trackName = track.getName();
        this.track = track;
        this.state = State.POST;
        this.cancelled = false;
    }

    @Override
    public EventType getType() {
        return EventType.TRACK_CREATE;
    }

    /**
     * Gets the name of the track being created.
     *
     * @return the track name
     */
    @NotNull
    public String getTrackName() {
        return trackName;
    }

    /**
     * Gets the created track.
     * <p>
     * This is only available in the POST state. Returns null in PRE state.
     *
     * @return the track, or null if PRE state
     */
    @Nullable
    public Track getTrack() {
        return track;
    }

    /**
     * Gets the state of this event.
     *
     * @return the state
     */
    @NotNull
    public State getState() {
        return state;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        if (state == State.PRE) {
            this.cancelled = cancelled;
        }
    }

    @Override
    public String toString() {
        return "TrackCreateEvent{trackName='" + trackName + "', state=" + state + ", cancelled=" + cancelled + "}";
    }
}
