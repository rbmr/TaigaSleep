package com.rbmr.taigasleep.sync

/** The History key of a recording that exists only on the server. */
fun remoteKey(recordingId: String): String = "$REMOTE_KEY_PREFIX$recordingId"

/** The recording id behind a recording key: the local id, or the id after the remote prefix. */
fun recordingIdOf(key: String): String = key.removePrefix(REMOTE_KEY_PREFIX)

/**
 * What a key of a recording that is only on the server starts with. The library query builds the
 * same keys in SQL, so it is passed to it instead of being written there twice.
 */
internal const val REMOTE_KEY_PREFIX = "remote-"
