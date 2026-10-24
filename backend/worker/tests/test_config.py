from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from taiga_worker.config import ConfigError, from_env


BASE = {
    "TAIGA_WORKER_EMAIL": "worker@example.com",
    "TAIGA_WORKER_PASSWORD": "secret",
}


class ConfigTest(unittest.TestCase):
    def test_defaults(self) -> None:
        config = from_env(env=BASE, argv=[])
        self.assertEqual(config.api_url, "http://127.0.0.1:8090")
        self.assertEqual(config.email, "worker@example.com")
        self.assertEqual(config.poll_interval_s, 15.0)
        self.assertEqual(config.lease_seconds, 1800)
        self.assertEqual(config.job_memory_mb, 1024)
        self.assertEqual(config.job_timeout_s, 3600.0)
        self.assertFalse(config.once)

    def test_env_overrides_and_url_is_normalized(self) -> None:
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
        self.assertEqual(config.api_url, "https://sleep.example.com")
        self.assertEqual(config.worker_id, "worker-1")
        self.assertEqual(config.poll_interval_s, 2.5)
        self.assertEqual(config.lease_seconds, 60)
        self.assertEqual(config.job_memory_mb, 512)
        self.assertEqual(config.job_timeout_s, 120)
        self.assertEqual(config.log_level, "DEBUG")

    def test_cli_wins_over_env(self) -> None:
        env = dict(BASE)
        env["TAIGA_JOB_LEASE_S"] = "60"
        config = from_env(
            env=env,
            argv=["--api-url", "http://10.0.0.2:8090", "--lease-seconds", "90", "--once"],
        )
        self.assertEqual(config.api_url, "http://10.0.0.2:8090")
        self.assertEqual(config.lease_seconds, 90)
        self.assertTrue(config.once)

    def test_password_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "worker.pass"
            path.write_text("from-file\n", encoding="utf-8")
            env = {
                "TAIGA_WORKER_EMAIL": "worker@example.com",
                "TAIGA_WORKER_PASSWORD_FILE": str(path),
            }
            config = from_env(env=env, argv=[])
            self.assertEqual(config.password, "from-file")

    def test_missing_password_is_an_error(self) -> None:
        with self.assertRaises(ConfigError):
            from_env(env={"TAIGA_WORKER_EMAIL": "worker@example.com"}, argv=[])

    def test_missing_email_is_an_error(self) -> None:
        with self.assertRaises(ConfigError):
            from_env(env={"TAIGA_WORKER_PASSWORD": "secret"}, argv=[])

    def test_bad_values_are_errors(self) -> None:
        for extra in (
            {"TAIGA_API_URL": "ftp://example.com"},
            {"TAIGA_JOB_LEASE_S": "0"},
            {"TAIGA_JOB_LEASE_S": "86401"},
            {"TAIGA_POLL_INTERVAL_S": "0"},
            {"TAIGA_JOB_MEMORY_MB": "-1"},
            {"TAIGA_JOB_TIMEOUT_S": "0"},
            {"TAIGA_LOG_LEVEL": "LOUD"},
        ):
            with self.subTest(extra=extra):
                with self.assertRaises(ConfigError):
                    from_env(env={**BASE, **extra}, argv=[])

    def test_worker_id_default_contains_host_and_pid(self) -> None:
        config = from_env(env=BASE, argv=[])
        self.assertIn("-", config.worker_id)


if __name__ == "__main__":
    unittest.main()
