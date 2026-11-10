# The default image runs the baseline stager and the wav2sleep ECG model.
# The model's PyTorch dependency and the pinned checkpoint make the image large,
# so `WITH_WAV2SLEEP=false` builds the previous small baseline-only image for
# constrained hosts. `git` is needed only to install the wav2sleep dependency
# from its pinned repository; the final stage does not carry build tools.

ARG WITH_WAV2SLEEP=true

FROM ghcr.io/astral-sh/uv:0.9.7-python3.12-bookworm-slim AS builder

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1

ARG WITH_WAV2SLEEP

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install the locked dependencies first so code changes do not invalidate the
# dependency layer.
COPY pyproject.toml uv.lock /app/
RUN if [ "$WITH_WAV2SLEEP" = "true" ]; then \
        uv sync --frozen --no-install-project --no-dev --extra wav2sleep; \
    else \
        uv sync --frozen --no-install-project --no-dev; \
    fi

COPY taiga_worker /app/taiga_worker
RUN if [ "$WITH_WAV2SLEEP" = "true" ]; then \
        uv sync --frozen --no-dev --extra wav2sleep; \
    else \
        uv sync --frozen --no-dev; \
    fi

# Fetch the pinned checkpoint once, at build time. The downloader verifies its
# SHA-256 before the image is produced, and the worker never downloads a model
# while a job runs. The directory exists in both variants so the final stage can
# copy it unconditionally.
RUN mkdir -p /app/models/wav2sleep \
    && if [ "$WITH_WAV2SLEEP" = "true" ]; then \
        /app/.venv/bin/python -m taiga_worker.checkpoint --dest /app/models/wav2sleep; \
    else \
        rmdir /app/models/wav2sleep; \
    fi


FROM ghcr.io/astral-sh/uv:0.9.7-python3.12-bookworm-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1 \
    HOME=/home/worker \
    NUMBA_CACHE_DIR=/home/worker/.cache/numba \
    TAIGA_WAV2SLEEP_CHECKPOINT=/app/models/wav2sleep \
    PATH="/app/.venv/bin:$PATH"

RUN useradd --system --home-dir /home/worker --shell /usr/sbin/nologin worker \
    && mkdir -p /home/worker/.cache/numba \
    && chown -R worker:worker /home/worker

WORKDIR /app

# Only the installed environment, the worker code and the checkpoint cross the
# stage boundary; git and the build toolchain stay in the builder.
COPY --from=builder --chown=worker:worker /app/.venv /app/.venv
COPY --from=builder --chown=worker:worker /app/taiga_worker /app/taiga_worker
COPY --from=builder --chown=worker:worker /app/models /app/models

USER worker

ENTRYPOINT ["python", "-m", "taiga_worker"]
