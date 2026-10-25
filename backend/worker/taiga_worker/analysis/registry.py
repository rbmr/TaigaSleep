"""Algorithm discovery.

Built-in stagers live in ``taiga_worker.analysis.algorithms``. Third-party
packages expose a stager through the ``taiga_sleep.stagers`` entry-point group::

    [project.entry-points."taiga_sleep.stagers"]
    my_algo = "my_package.module:STAGER"

The worker reports the resulting list to the API so the app can offer the
algorithms that are actually installed on a worker.
"""

from __future__ import annotations

import importlib
import re
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping

from .contract import AnalysisError, RegisteredAlgorithm, Stager

ENTRY_POINT_GROUP = "taiga_sleep.stagers"
BUILTIN_MODULES = (
    "taiga_worker.analysis.algorithms.baseline",
    "taiga_worker.analysis.algorithms.wav2sleep",
)


@dataclass
class Registry:
    algorithms: dict[tuple[str, str], RegisteredAlgorithm] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

    def latest_by_id(self) -> dict[str, RegisteredAlgorithm]:
        latest: dict[str, RegisteredAlgorithm] = {}
        for registered in self.algorithms.values():
            current = latest.get(registered.info.id)
            if current is None or _version_key(registered.info.version) > _version_key(
                current.info.version
            ):
                latest[registered.info.id] = registered
        return latest

    def select(self, algorithm_id: str, version: str | None = None) -> RegisteredAlgorithm:
        if version:
            registered = self.algorithms.get((algorithm_id, version))
            if registered is None:
                raise AnalysisError(
                    "unknown_algorithm",
                    f"algorithm {algorithm_id!r} version {version!r} is not installed",
                )
            return registered
        registered = self.latest_by_id().get(algorithm_id)
        if registered is None:
            installed = ", ".join(sorted(self.latest_by_id())) or "none"
            raise AnalysisError(
                "unknown_algorithm",
                f"algorithm {algorithm_id!r} is not installed (installed: {installed})",
            )
        return registered

    def capabilities(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        versions: dict[str, list[str]] = {}
        for registered in self.algorithms.values():
            versions.setdefault(registered.info.id, []).append(registered.info.version)
        for algorithm_id, registered in sorted(self.latest_by_id().items()):
            item = registered.info.to_dict()
            item["source"] = registered.source
            item["available_versions"] = sorted(
                versions.get(algorithm_id, []), key=_version_key, reverse=True
            )
            result.append(item)
        return result

    def to_dict(self) -> dict[str, Any]:
        return {"algorithms": self.capabilities(), "errors": list(self.errors)}


def _version_key(version: str) -> tuple[int, ...]:
    parts = tuple(int(part) for part in re.findall(r"\d+", version))
    return parts or (0,)


def _coerce_stager(value: Any, source: str) -> Stager:
    if isinstance(value, type):
        value = value()
    if isinstance(value, Stager):
        return value

    # An entry point may point at a module exporting STAGER rather than at the
    # instance itself.
    module = value if hasattr(value, "__name__") else None
    if module is not None:
        candidate = getattr(importlib.import_module(module.__name__), "STAGER", None)
        if isinstance(candidate, type):
            candidate = candidate()
        if isinstance(candidate, Stager):
            return candidate

    raise TypeError(
        f"{source} must provide a Stager subclass instance "
        f"(got {type(value).__name__})"
    )


def _load_builtins(registry: Registry) -> Iterable[tuple[str, Stager]]:
    for module_name in BUILTIN_MODULES:
        try:
            module = importlib.import_module(module_name)
            probe = getattr(module, "availability", None)
            if probe is not None:
                available, reason = probe()
                if not available:
                    # A missing optional extra is expected on a base worker and
                    # stays silent; a configured-but-broken extra is reported.
                    if reason:
                        registry.errors.append(f"{module_name}: {reason}")
                    continue
            stager = getattr(module, "STAGER", None)
            if stager is None:
                raise ImportError(f"{module_name} does not export STAGER")
        except Exception as exc:  # noqa: BLE001 - one bad builtin must not hide the rest
            registry.errors.append(f"{module_name}: {exc}")
            continue
        yield module_name, stager


def _entry_points() -> Iterable[Any]:
    from importlib.metadata import entry_points

    discovered = entry_points()
    if hasattr(discovered, "select"):
        return discovered.select(group=ENTRY_POINT_GROUP)
    return discovered.get(ENTRY_POINT_GROUP, [])  # type: ignore[union-attr]


def discover(allowlist: Iterable[str] | None = None) -> Registry:
    allowed = {item.strip() for item in allowlist or [] if item.strip()}
    registry = Registry()

    def add(stager: Stager, source: str) -> None:
        info = stager.info
        if allowed and info.id not in allowed:
            return
        key = (info.id, info.version)
        if key in registry.algorithms:
            registry.errors.append(
                f"duplicate algorithm {info.id} {info.version} from {source}; keeping the first"
            )
            return
        registry.algorithms[key] = RegisteredAlgorithm(info=info, stager=stager, source=source)

    for module_name, stager in _load_builtins(registry):
        try:
            add(_coerce_stager(stager, module_name), "builtin")
        except Exception as exc:  # noqa: BLE001 - one bad builtin must not hide the rest
            registry.errors.append(f"{module_name}: {exc}")

    for entry_point in _entry_points():
        try:
            value = entry_point.load()
            add(_coerce_stager(value, entry_point.value), f"entry-point:{entry_point.value}")
        except Exception as exc:  # noqa: BLE001 - report, do not crash the worker
            registry.errors.append(f"{getattr(entry_point, 'value', entry_point)}: {exc}")

    if allowed:
        installed = {info_id for info_id, _version in registry.algorithms}
        for missing in sorted(allowed - installed):
            registry.errors.append(f"allowed algorithm {missing!r} is not installed")

    return registry


def capabilities_from_env(value: str | None) -> tuple[str, ...] | None:
    if not value:
        return None
    items = tuple(item.strip() for item in value.split(",") if item.strip())
    return items or None


__all__ = [
    "BUILTIN_MODULES",
    "ENTRY_POINT_GROUP",
    "Registry",
    "capabilities_from_env",
    "discover",
]
