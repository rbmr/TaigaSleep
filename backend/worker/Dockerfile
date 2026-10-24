FROM ghcr.io/astral-sh/uv:0.9.7-python3.12-bookworm-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1

RUN useradd --system --home-dir /app --shell /usr/sbin/nologin worker

WORKDIR /app

# Install the locked dependencies first so code changes do not invalidate the
# dependency layer.
COPY pyproject.toml uv.lock /app/
RUN uv sync --frozen --no-install-project --no-dev

COPY taiga_worker /app/taiga_worker
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"

USER worker

ENTRYPOINT ["python", "-m", "taiga_worker"]
