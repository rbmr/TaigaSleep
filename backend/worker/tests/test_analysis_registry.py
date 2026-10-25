from __future__ import annotations

import unittest

from taiga_worker.analysis.contract import AnalysisError
from taiga_worker.analysis.registry import discover


class RegistryTest(unittest.TestCase):
    def test_discovers_the_builtin_baseline(self) -> None:
        registry = discover()
        self.assertEqual(registry.errors, [])
        selected = registry.select("baseline")
        self.assertEqual(selected.info.id, "baseline")
        self.assertEqual(selected.info.version, "1.0.0")
        self.assertEqual(selected.source, "builtin")

    def test_selects_an_exact_version(self) -> None:
        registry = discover()
        selected = registry.select("baseline", "1.0.0")
        self.assertEqual(selected.info.version, "1.0.0")

    def test_unknown_algorithm_is_a_clear_error(self) -> None:
        registry = discover()
        with self.assertRaises(AnalysisError) as context:
            registry.select("does-not-exist")
        self.assertEqual(context.exception.code, "unknown_algorithm")

        with self.assertRaises(AnalysisError) as context:
            registry.select("baseline", "9.9.9")
        self.assertEqual(context.exception.code, "unknown_algorithm")

    def test_capabilities_include_source_and_versions(self) -> None:
        capabilities = discover().capabilities()
        self.assertEqual(len(capabilities), 1)
        item = capabilities[0]
        self.assertEqual(item["id"], "baseline")
        self.assertEqual(item["source"], "builtin")
        self.assertEqual(item["available_versions"], ["1.0.0"])
        self.assertIn("needs", item)
        self.assertIn("supported_stages", item)

    def test_allowlist_filters_and_reports_missing_algorithms(self) -> None:
        registry = discover(allowlist=["baseline"])
        self.assertIn(("baseline", "1.0.0"), registry.algorithms)

        missing = discover(allowlist=["not-installed"])
        self.assertEqual(missing.algorithms, {})
        self.assertTrue(any("not installed" in error for error in missing.errors))


if __name__ == "__main__":
    unittest.main()
