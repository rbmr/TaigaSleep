from __future__ import annotations

from pathlib import Path

import pytest

from taiga_worker.config import ConfigError, from_env

BASE = {
    "TAIGA_WORKER_EMAIL": "worker@example.com",
    "TAIGA_WORKER_PASSWORD": "secret",
}


def test_defaults() -> None:
    config = from_env(env=BASE, argv=[])
    assert config.api_url == "http://127.0.0.1:8090"
    assert config.email == "worker@example.com"
    assert config.poll_interval_s == 15.0
    assert config.lease_seconds == 1800
    assert config.job_memory_mb == 1024
    assert config.job_timeout_s == 3600.0
    assert not config.once
    assert config.algorithms is None


def test_env_overrides_and_url_is_normalized() -> None:
    env = dict(BASE)
    env.update(
        {
            "TAIGA_API_URL": "https://sleep.example.com/",
            "TAIGA_WORKER_ID": "worker-1",
            "TAIGA_POLL_INTERVAL_S": "2.5",
            "TAIGA_JOB_LEASE_S": "60",
            "TAIGA_JOB_MEMORY_MB": "512",
            "TAIGA_JOB_TIMEOUT_S": "120",
            "TAIGA_LOG_LEVEL": "debug",
        }
    )
    config = from_env(env=env, argv=[])
    assert config.api_url == "https://sleep.example.com"
    assert config.worker_id == "worker-1"
    assert config.poll_interval_s == 2.5
    assert config.lease_seconds == 60
    assert config.job_memory_mb == 512
    assert config.job_timeout_s == 120
    assert config.log_level == "DEBUG"


def test_cli_wins_over_env() -> None:
    env = dict(BASE)
    env["TAIGA_JOB_LEASE_S"] = "60"
    config = from_env(
        env=env,
        argv=["--api-url", "http://10.0.0.2:8090", "--lease-seconds", "90", "--once"],
    )
    assert config.api_url == "http://10.0.0.2:8090"
    assert config.lease_seconds == 90
    assert config.once


def test_algorithms_allowlist() -> None:
    env = dict(BASE)
    env["TAIGA_ALGORITHMS"] = "baseline, sleep-ecg"
    config = from_env(env=env, argv=[])
    assert config.algorithms == ("baseline", "sleep-ecg")

    config = from_env(env=env, argv=["--algorithms", "wav2sleep"])
    assert config.algorithms == ("wav2sleep",)


def test_password_file(tmp_path: Path) -> None:
    path = tmp_path / "worker.pass"
    path.write_text("from-file\n", encoding="utf-8")
    env = {
        "TAIGA_WORKER_EMAIL": "worker@example.com",
        "TAIGA_WORKER_PASSWORD_FILE": str(path),
    }
    config = from_env(env=env, argv=[])
    assert config.password == "from-file"


def test_missing_password_is_an_error() -> None:
    with pytest.raises(ConfigError):
        from_env(env={"TAIGA_WORKER_EMAIL": "worker@example.com"}, argv=[])


def test_missing_email_is_an_error() -> None:
    with pytest.raises(ConfigError):
        from_env(env={"TAIGA_WORKER_PASSWORD": "secret"}, argv=[])


@pytest.mark.parametrize(
    "extra",
    [
        {"TAIGA_API_URL": "ftp://example.com"},
        {"TAIGA_JOB_LEASE_S": "0"},
        {"TAIGA_JOB_LEASE_S": "86401"},
        {"TAIGA_POLL_INTERVAL_S": "0"},
        {"TAIGA_JOB_MEMORY_MB": "-1"},
        {"TAIGA_JOB_TIMEOUT_S": "0"},
        {"TAIGA_LOG_LEVEL": "LOUD"},
    ],
    ids=[
        "url-scheme",
        "lease-zero",
        "lease-too-long",
        "poll-zero",
        "memory-negative",
        "timeout-zero",
        "log-level",
    ],
)
def test_bad_values_are_errors(extra: dict[str, str]) -> None:
    with pytest.raises(ConfigError):
        from_env(env={**BASE, **extra}, argv=[])


def test_worker_id_default_contains_host_and_pid() -> None:
    config = from_env(env=BASE, argv=[])
    assert "-" in config.worker_id
