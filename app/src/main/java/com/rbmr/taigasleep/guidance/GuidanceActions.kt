package com.rbmr.taigasleep.guidance

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

object GuidanceActions {
  // Screens to try in order; the last one (app info) always exists.
  fun fallbackChain(kind: GuidanceAction, canRequestExemption: Boolean): List<GuidanceAction> =
      when (kind) {
        GuidanceAction.REQUEST_EXEMPTION ->
            if (canRequestExemption) {
              listOf(
                  GuidanceAction.REQUEST_EXEMPTION,
                  GuidanceAction.BATTERY_SETTINGS,
                  GuidanceAction.APP_DETAILS,
              )
            } else {
              listOf(GuidanceAction.BATTERY_SETTINGS, GuidanceAction.APP_DETAILS)
            }
        GuidanceAction.BATTERY_SETTINGS ->
            listOf(GuidanceAction.BATTERY_SETTINGS, GuidanceAction.APP_DETAILS)
        GuidanceAction.APP_DETAILS -> listOf(GuidanceAction.APP_DETAILS)
      }

  // Opens the screen for [kind], falling back when it is missing or not allowed.
  // Returns false if nothing could be opened; the manual instructions stay visible either way.
  fun open(context: Context, kind: GuidanceAction): Boolean {
    val canRequest =
        context.checkSelfPermission(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) ==
            PackageManager.PERMISSION_GRANTED
    return fallbackChain(kind, canRequest).any { start(context, intentFor(context, it)) }
  }

  private fun intentFor(context: Context, kind: GuidanceAction): Intent {
    val packageUri = Uri.parse("package:${context.packageName}")
    return when (kind) {
      GuidanceAction.REQUEST_EXEMPTION ->
          Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
      GuidanceAction.BATTERY_SETTINGS -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
      GuidanceAction.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }
  }

  private fun start(context: Context, intent: Intent): Boolean =
      try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
      } catch (_: ActivityNotFoundException) {
        false
      } catch (_: SecurityException) {
        false
      }
}
