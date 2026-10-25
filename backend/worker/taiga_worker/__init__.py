"""Taiga Sleep analysis worker.

The scheduler is a deliberately small loop: authenticate to PocketBase, claim
one job, run it in a fresh subprocess, report the result, repeat. The analysis
library lives in :mod:`taiga_worker.analysis`; the stager contract is documented
in ``docs/analysis-contract.md``.
"""

__version__ = "0.1.0"
