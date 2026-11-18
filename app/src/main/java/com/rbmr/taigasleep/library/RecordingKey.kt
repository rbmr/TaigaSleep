package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.sync.remoteKey

/**
 * The History key of a recording: the phone's own id when the phone has it, the remote key
 * otherwise. Null when neither is known.
 */
fun recordingKey(localId: String?, remoteId: String?): String? =
    localId ?: remoteId?.let(::remoteKey)
