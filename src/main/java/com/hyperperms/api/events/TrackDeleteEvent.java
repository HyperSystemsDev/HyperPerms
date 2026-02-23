package com.hyperperms.api.events;

import com.hyperperms.model.Track;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired when a track is deleted.
 * <p>
 * This event can be cancelled to prevent the track from being deleted.
 * The PRE state fires before deletion, the POST state fires after.
 */
public final class TrackDeleteEvent implements HyperPermsEvent, Cancellable {

    /**
     * The state of the track deletion event.
     */
    public enum State {
        /**
         * Before the track is deleted. The event can be cancelled at this point.
         */
        PRE,

        /**
         * After the track has been deleted. Cancellation has no effect.
         */
        POST
    }

    private final String trackName;
    private final Track track;
    private final State state;
    private boolean cancelled;

    /**
     * Creates a PRE event for track deletion.
     *
     * @param track the track being deleted
     */
    public TrackDeleteEvent(@NotNull Track track) {
        this.trackName = track.getName();
        this.track = track;
        this.state = State.PRE;
        this.cancelled = false;
    }

    /**
     * Creates a POST event for track deletion.
     *
     * @param trackName the name of the deleted track
     */
    public TrackDeleteEvent(@NotNull String trackName) {
        this.trackName = trackName;
        this.track = null;
        this.state = State.POST;
        this.cancelled = false;
    }

    @Override
    public EventType getType() {
        return EventType.TRACK_DELETE;
    }

    /**
     * Gets the name of the track being deleted.
     *
     * @return the track name
     */
    @NotNull
    public String getTrackName() {
        return trackName;
    }

    /**
     * Gets the track being deleted.
     * <p>
     * This is only available in the PRE state. Returns null in POST state.
     *
     * @return the track, or null if POST state
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
        return "TrackDeleteEvent{trackName='" + trackName + "', state=" + state + ", cancelled=" + cancelled + "}";
    }
}
