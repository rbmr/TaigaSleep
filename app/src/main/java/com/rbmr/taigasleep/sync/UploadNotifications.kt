package com.rbmr.taigasleep.sync

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import com.rbmr.taigasleep.notifications.Notifier

internal object UploadNotifications {
  /**
   * The app's launcher activity, which the manifest declares. It is named rather than imported
   * because the activity is app wiring, which no package may depend on.
   */
  internal const val APP_ACTIVITY = Notifier.APP_ACTIVITY

  fun foreground(context: Context): ForegroundInfo =
      ForegroundInfo(
          Notifier.UPLOAD_ID,
          Notifier.upload(context, APP_ACTIVITY),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
}
