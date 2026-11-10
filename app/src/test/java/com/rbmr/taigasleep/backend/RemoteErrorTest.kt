package com.rbmr.taigasleep.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored error label has to survive a round trip, including the two API revisions. */
class RemoteErrorTest {
  @Test
  fun `every error keeps its identity through the stored label`() {
    val errors =
        listOf(
            RemoteError.InvalidUrl,
            RemoteError.UnknownHost,
            RemoteError.InsecureUrl,
            RemoteError.MissingCredentials,
            RemoteError.Unreachable,
            RemoteError.NotTaiga,
            RemoteError.BadCredentials,
            RemoteError.SessionExpired,
            RemoteError.StorageFull,
            RemoteError.MalformedResponse,
            RemoteError.Http(500),
            RemoteError.IncompatibleApi(required = 3, server = 2),
        )

    for (error in errors) {
      assertEquals(error, remoteErrorFromLabel(error.label))
    }
  }

  @Test
  fun `a full server quota maps to its own reason`() {
    assertEquals(RemoteError.StorageFull, remoteErrorForCode(ApiCode.STORAGE_QUOTA_EXCEEDED))
    assertEquals(RemoteError.StorageFull, remoteErrorFromLabel(RemoteError.StorageFull.label))
  }
}
