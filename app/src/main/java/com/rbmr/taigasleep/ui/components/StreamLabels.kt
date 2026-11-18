package com.rbmr.taigasleep.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.tracker.StreamType

/**
 * The name the app gives a stream wherever it names one: the summary on Home, the streams a
 * recording holds and the banner of a stream that stopped. One mapping is what makes a stream read
 * the same on every screen.
 */
@Composable
fun streamLabel(type: StreamType): String =
    stringResource(
        when (type) {
          StreamType.HEART_RATE -> R.string.rec_active_hr
          StreamType.RR_INTERVAL -> R.string.rec_config_rr
          StreamType.ECG -> R.string.rec_active_ecg
          StreamType.ACCELEROMETER -> R.string.rec_active_acc
          StreamType.PPG -> R.string.rec_config_ppg
          StreamType.PULSE_INTERVAL -> R.string.rec_config_ppi
          StreamType.GYROSCOPE -> R.string.rec_config_gyro
          StreamType.MAGNETOMETER -> R.string.rec_config_mag
        }
    )
