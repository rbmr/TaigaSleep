"""Signal helpers shared by every tracker's decoding and analysis.

A device supplies the samples; what counts as a saturated ECG or as movement is the
same decision for all of them, so it lives here instead of in each stager or decoder.
"""

from __future__ import annotations

import numpy as np

# The H10 saturates at about +-18.4 mV when nothing is picking up a heartbeat; a
# recording from another tracker uses the same rail so the quality result is comparable.
ECG_RAIL_MV = 18.0


def ecg_saturation(voltage_mv: np.ndarray, rail_mv: float = ECG_RAIL_MV) -> np.ndarray:
    """True where the ECG is pinned at the sensor's limits."""

    values = np.asarray(voltage_mv, dtype=np.float32)
    return np.abs(values) >= float(rail_mv)


def accelerometer_movement(xyz_g: np.ndarray) -> np.ndarray:
    """The magnitude of each accelerometer sample, in g."""

    values = np.asarray(xyz_g, dtype=np.float64)
    return np.sqrt(np.sum(values * values, axis=1))
