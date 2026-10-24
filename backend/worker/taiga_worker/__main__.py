from __future__ import annotations

import logging
import signal
import sys

from . import __version__
from .api import ApiClient, ApiError
from .config import ConfigError, from_env
from .runner import JobRunner
from .worker import Worker


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else list(argv)
    if "--version" in args:
        print(f"taiga-worker {__version__}")
        return 0

    try:
        config = from_env(argv=args)
    except ConfigError as exc:
        print(f"taiga-worker: {exc}", file=sys.stderr)
        return 2

    logging.basicConfig(
        level=getattr(logging, config.log_level),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    log = logging.getLogger("taiga_worker")

    api = ApiClient(config.api_url)
    runner = JobRunner(memory_mb=config.job_memory_mb, timeout_s=config.job_timeout_s)
    worker = Worker(config, api, runner, logger=log)

    def stop(signum: int, _frame: object) -> None:
        log.info("received signal %s; stopping", signum)
        worker.request_stop()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    try:
        if config.once:
            worker.login()
            processed = worker.run_once()
            log.info("one-shot run finished (processed job: %s)", processed)
            return 0
        worker.run_forever()
        return 0
    except ApiError as exc:
        log.error("cannot authenticate to the API: %s", exc)
        return 1
    except KeyboardInterrupt:
        worker.request_stop()
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
