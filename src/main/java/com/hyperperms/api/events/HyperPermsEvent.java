package com.hyperperms.api.events;

/**
 * Base interface for all HyperPerms events.
 */
public interface HyperPermsEvent {

    /**
     * Gets the event type.
     *
     * @return the type
     */
    EventType getType();

    /**
     * Types of events fired by HyperPerms.
     */
    enum EventType {
        /**
         * Fired when a permission is checked.
         */
        PERMISSION_CHECK,

        /**
         * Fired when a permission is changed.
         */
        PERMISSION_CHANGE,

        /**
         * Fired when a permission is registered with the registry.
         */
        PERMISSION_REGISTER,

        /**
         * Fired when a group is created.
         */
        GROUP_CREATE,

        /**
         * Fired when a group is deleted.
         */
        GROUP_DELETE,

        /**
         * Fired when a group's properties are modified.
         */
        GROUP_MODIFY,

        /**
         * Fired when a user's group membership changes.
         */
        USER_GROUP_CHANGE,

        /**
         * Fired when a user is loaded from storage.
         */
        USER_LOAD,

        /**
         * Fired when a user is unloaded from the cache.
         */
        USER_UNLOAD,

        /**
         * Fired when a player's context changes (world, gamemode, etc.).
         */
        CONTEXT_CHANGE,

        /**
         * Fired when data is reloaded.
         */
        DATA_RELOAD,

        /**
         * Fired when a track is created.
         */
        TRACK_CREATE,

        /**
         * Fired when a track is deleted.
         */
        TRACK_DELETE,

        /**
         * Fired when a track is modified.
         */
        TRACK_MODIFY,

        /**
         * Fired when a user is promoted along a track.
         */
        TRACK_PROMOTION,

        /**
         * Fired when a user is demoted along a track.
         */
        TRACK_DEMOTION
    }
}
