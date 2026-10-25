"""Analysis contract, decoding, derivation and stager discovery."""

from .contract import (
    CONTRACT_VERSION,
    STAGES,
    AlgorithmInfo,
    AnalysisError,
    Recording,
    Stager,
    StagingResult,
    StreamNeed,
)
from .decode import load_recording
from .derive import QUALITY_WEIGHTS, QUALITY_WEIGHTS_VERSION, Derived, derive
from .pipeline import AnalysisOutput, params_hash, run_analysis
from .registry import Registry, discover

__all__ = [
    "CONTRACT_VERSION",
    "STAGES",
    "AlgorithmInfo",
    "AnalysisError",
    "AnalysisOutput",
    "Derived",
    "QUALITY_WEIGHTS",
    "QUALITY_WEIGHTS_VERSION",
    "Recording",
    "Registry",
    "Stager",
    "StagingResult",
    "StreamNeed",
    "derive",
    "discover",
    "load_recording",
    "params_hash",
    "run_analysis",
]
