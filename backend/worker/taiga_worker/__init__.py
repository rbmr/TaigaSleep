"""Taiga Sleep analysis worker.

The scheduler is a deliberately small loop: authenticate to PocketBase, claim
one job, run it in a fresh subprocess, report the result, repeat. There is no
analysis code here yet; issue #10 adds it.
"""

__version__ = "0.1.0"
