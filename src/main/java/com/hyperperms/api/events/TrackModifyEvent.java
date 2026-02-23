package com.hyperperms.api.events;

import com.hyperperms.model.Track;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Event fired when a track's groups are modified.
 * <p>
 * This event is fired after a track is saved with updated groups.
 */
public final class TrackModifyEvent implements HyperPermsEvent {

    private final Track track;
    private final List<String> oldGroups;
    private final List<String> newGroups;

    /**
     * Creates a new track modify event.
     *
     * @param track     the modified track
     * @param oldGroups the previous group list
     * @param newGroups the new group list
     */
    public TrackModifyEvent(@NotNull Track track, @NotNull List<String> oldGroups, @NotNull List<String> newGroups) {
        this.track = track;
        this.oldGroups = List.copyOf(oldGroups);
        this.newGroups = List.copyOf(newGroups);
    }

    @Override
    public EventType getType() {
        return EventType.TRACK_MODIFY;
    }

    /**
     * Gets the modified track.
     *
     * @return the track
     */
    @NotNull
    public Track getTrack() {
        return track;
    }

    /**
     * Gets the previous group list.
     *
     * @return the old groups
     */
    @NotNull
    public List<String> getOldGroups() {
        return oldGroups;
    }

    /**
     * Gets the new group list.
     *
     * @return the new groups
     */
    @NotNull
    public List<String> getNewGroups() {
        return newGroups;
    }

    @Override
    public String toString() {
        return "TrackModifyEvent{track='" + track.getName() + "', oldGroups=" + oldGroups + ", newGroups=" + newGroups + "}";
    }
}
