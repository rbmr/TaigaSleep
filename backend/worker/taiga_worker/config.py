from __future__ import annotations

import argparse
import os
import socket
from dataclasses import dataclass
from typing import Iterable, Mapping


class ConfigError(ValueError):
    """Raised when the worker configuration is incomplete or invalid."""


@dataclass(frozen=True)
class Config:
    api_url: str
    email: str
    password: str
    worker_id: str
    poll_interval_s: float
    lease_seconds: int
    job_memory_mb: int
    job_timeout_s: float
    once: bool
    log_level: str


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="taiga-worker",
        description="Taiga Sleep analysis worker supervisor",
    )
    parser.add_argument(
        "--api-url",
        help="PocketBase base URL (env: TAIGA_API_URL, default: http://127.0.0.1:8090)",
    )
    parser.add_argument(
        "--email",
        help="Worker service account email (env: TAIGA_WORKER_EMAIL)",
    )
    parser.add_argument(
        "--password",
        help="Worker service account password. Prefer TAIGA_WORKER_PASSWORD.",
    )
    parser.add_argument(
        "--password-file",
        help="Read the worker password from this file",
    )
    parser.add_argument(
        "--worker-id",
        help="Stable worker name recorded on leases (env: TAIGA_WORKER_ID)",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        help="Seconds to sleep when the queue is empty (env: TAIGA_POLL_INTERVAL_S)",
    )
    parser.add_argument(
        "--lease-seconds",
        type=int,
        help="Job lease length in seconds (env: TAIGA_JOB_LEASE_S)",
    )
    parser.add_argument(
        "--memory-mb",
        type=int,
        help="Address-space limit for the analysis subprocess (env: TAIGA_JOB_MEMORY_MB, 0 disables)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        help="Wall-clock limit for one job in seconds (env: TAIGA_JOB_TIMEOUT_S)",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Claim and process at most one job, then exit (useful for tests)",
    )
    parser.add_argument(
        "--log-level",
        help="Python log level (env: TAIGA_LOG_LEVEL, default: INFO)",
    )
    return parser


def _env(env: Mapping[str, str], name: str, default: str | None = None) -> str | None:
    value = env.get(name)
    if value is None or value.strip() == "":
        return default
    return value.strip()


def _int(value: str | None, default: int, name: str) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer, got {value!r}") from exc


def _float(value: str | None, default: float, name: str) -> float:
    if value is None:
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ConfigError(f"{name} must be a number, got {value!r}") from exc


def _password(args: argparse.Namespace, env: Mapping[str, str]) -> str:
    if args.password:
        return args.password

    password_file = args.password_file or _env(env, "TAIGA_WORKER_PASSWORD_FILE")
    if password_file:
        try:
            with open(password_file, encoding="utf-8") as handle:
                value = handle.read().strip()
        except OSError as exc:
            raise ConfigError(f"cannot read password file: {exc}") from exc
        if value:
            return value
        raise ConfigError("password file is empty")

    value = _env(env, "TAIGA_WORKER_PASSWORD")
    if value:
        return value

    raise ConfigError(
        "worker password is required (TAIGA_WORKER_PASSWORD, "
        "TAIGA_WORKER_PASSWORD_FILE or --password)"
    )


def from_env(
    env: Mapping[str, str] | None = None,
    argv: Iterable[str] | None = None,
) -> Config:
    """Build a Config from environment variables and command-line arguments.

    Command-line arguments win over the environment, which wins over defaults.
    """

    env = os.environ if env is None else env
    args = _build_parser().parse_args(list(argv) if argv is not None else None)

    api_url = (args.api_url or _env(env, "TAIGA_API_URL") or "http://127.0.0.1:8090").rstrip("/")
    if not api_url.startswith(("http://", "https://")):
        raise ConfigError("api URL must start with http:// or https://")

    email = args.email or _env(env, "TAIGA_WORKER_EMAIL")
    if not email:
        raise ConfigError("worker email is required (TAIGA_WORKER_EMAIL or --email)")

    worker_id = args.worker_id or _env(env, "TAIGA_WORKER_ID") or f"{socket.gethostname()}-{os.getpid()}"

    poll_interval = _float(
        str(args.poll_interval) if args.poll_interval is not None else _env(env, "TAIGA_POLL_INTERVAL_S"),
        15.0,
        "poll interval",
    )
    lease_seconds = _int(
        str(args.lease_seconds) if args.lease_seconds is not None else _env(env, "TAIGA_JOB_LEASE_S"),
        1800,
        "lease seconds",
    )
    memory_mb = _int(
        str(args.memory_mb) if args.memory_mb is not None else _env(env, "TAIGA_JOB_MEMORY_MB"),
        1024,
        "memory limit",
    )
    timeout_s = _float(
        str(args.timeout) if args.timeout is not None else _env(env, "TAIGA_JOB_TIMEOUT_S"),
        3600.0,
        "job timeout",
    )
    log_level = (args.log_level or _env(env, "TAIGA_LOG_LEVEL") or "INFO").upper()

    if poll_interval < 0.1:
        raise ConfigError("poll interval must be at least 0.1 seconds")
    if not 1 <= lease_seconds <= 86400:
        raise ConfigError("lease seconds must be between 1 and 86400")
    if memory_mb < 0:
        raise ConfigError("memory limit must be >= 0 (0 disables the limit)")
    if timeout_s <= 0:
        raise ConfigError("job timeout must be > 0")
    if log_level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
        raise ConfigError(f"invalid log level: {log_level}")

    return Config(
        api_url=api_url,
        email=email,
        password=_password(args, env),
        worker_id=worker_id,
        poll_interval_s=poll_interval,
        lease_seconds=lease_seconds,
        job_memory_mb=memory_mb,
        job_timeout_s=timeout_s,
        once=bool(args.once),
        log_level=log_level,
    )
