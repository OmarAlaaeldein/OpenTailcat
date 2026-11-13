"""Soundness regressions for the checker harness; no Java or downloads needed."""

from pathlib import Path
import sys
import tempfile
import unittest

from tlc_runner import Result, classify, run


class RunnerTest(unittest.TestCase):
    progress = "Progress: 123 states checked.\n"
    complete = "Model checking completed. No error has been found.\n"
    violation = "Error: Invariant Ownership is violated.\n"

    def test_only_completed_exhaustive_search_is_checked(self):
        self.assertTrue(classify(Result(0), self.complete, "pass", False)[0])
        self.assertFalse(classify(Result(0), self.progress, "pass", False)[0])

    def test_external_kills_and_oom_are_failures(self):
        for code in (-9, 137, -15, 143):
            self.assertFalse(classify(Result(code), self.progress, "pass", True)[0])
        self.assertFalse(classify(Result(143, True), self.progress + "OutOfMemoryError", "pass", True)[0])
        self.assertFalse(classify(Result(137, True), self.progress, "pass", True)[0])

    def test_timeout_requires_progress_and_no_errors(self):
        self.assertTrue(classify(Result(143, True), self.progress, "pass", True)[0])
        self.assertFalse(classify(Result(143, True), "Starting...", "pass", True)[0])
        self.assertFalse(classify(Result(143, True), self.progress + self.violation, "pass", True)[0])

    def test_counterexample_requires_correct_property_and_model_error_exit(self):
        expect = "violation Invariant Ownership is violated"
        self.assertTrue(classify(Result(12), self.violation, expect, False)[0])
        for result in (Result(0), Result(137), Result(143, True)):
            self.assertFalse(classify(result, self.violation, expect, False)[0])
        self.assertFalse(classify(Result(12), self.violation, "violation Invariant Other is violated", False)[0])

    def test_temporal_counterexample(self):
        self.assertTrue(classify(Result(13), "Error: Temporal properties were violated.",
                                 "violation Temporal properties were violated", False)[0])

    def test_failures_cannot_hide_behind_success_text(self):
        for output in (self.complete + "Error: failure", self.complete + "java.lang.ArithmeticException"):
            self.assertFalse(classify(Result(0), output, "pass", False)[0])

    def test_real_process_exit_and_timer_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "run.log"
            killed = run([sys.executable, "-c", "import os, signal; os.kill(os.getpid(), signal.SIGKILL)"],
                         log, 2, 0)
            self.assertEqual(killed.code, -9)
            self.assertFalse(killed.timed_out)
            sampled = run([sys.executable, "-u", "-c",
                           "import time; print('Progress: 1 states checked.'); time.sleep(10)"], log, 0.1, 0)
            self.assertTrue(sampled.timed_out)
            self.assertTrue(classify(sampled, log.read_text(), "pass", True)[0])

    def test_disk_guard_is_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run([sys.executable, "-c", "import time; time.sleep(10)"],
                         Path(directory) / "run.log", None, 10**20)
            self.assertIn("disk guard", result.infrastructure_error)


if __name__ == "__main__":
    unittest.main()
