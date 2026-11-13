#!/usr/bin/env python3
"""Run TLC and distinguish completed checks, sampling, and infrastructure failure."""

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import shutil
import subprocess
import time


@dataclass
class Result:
    code: int
    timed_out: bool = False
    infrastructure_error: str = ""


def classify(result: Result, output: str, expect: str, sampling: bool) -> tuple[bool, str]:
    # A killed JVM is not evidence of a successful check. Only our own timer,
    # after actual simulation progress, may end an exploratory sampling run.
    errors = re.findall(r"^Error:.*", output, re.MULTILINE)
    fatal = re.search(r"Exception|OutOfMemory|out of memory|SIGSEGV|fatal error", output, re.I)
    if result.infrastructure_error or fatal:
        return False, result.infrastructure_error or "TLC exception or resource failure"
    if expect.startswith("violation "):
        pattern = expect.removeprefix("violation ")
        found = any(re.search(pattern, line) for line in errors)
        # TLC's documented model-error exit codes, not arbitrary JVM failures.
        return (found and result.code in {11, 12, 13} and not result.timed_out,
                "expected counterexample" if found else "expected counterexample was not found")
    if expect != "pass":
        return False, "invalid EXPECT"
    if errors:
        return False, errors[0]
    if sampling:
        progress = re.search(r"Progress: [1-9][0-9,]* states checked\.", output)
        if result.timed_out and result.code in {-15, 143} and progress:
            return True, "SAMPLED (time limit; no violation found; not exhaustive)"
        return False, "sampling did not reach its time limit with confirmed progress"
    completed = "Model checking completed. No error has been found." in output
    return (result.code == 0 and not result.timed_out and completed,
            "CHECKED (exhaustive within this configuration)" if completed else "TLC did not complete")


def run(command: list[str], log: Path, seconds: float | None, min_free_kb: int) -> Result:
    started = time.monotonic()
    with log.open("w") as stream:
        process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
        try:
            while True:
                try:
                    return Result(process.wait(timeout=0.5))
                except subprocess.TimeoutExpired:
                    if shutil.disk_usage(log.parent).free < min_free_kb * 1024:
                        reason = "disk guard: insufficient free space"
                    elif seconds is not None and time.monotonic() - started >= seconds:
                        reason = "timer"
                    else:
                        continue
                    process.terminate()
                    try:
                        code = process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        code = process.wait()
                        return Result(code, infrastructure_error="TLC ignored termination; forced kill")
                    return Result(code, timed_out=reason == "timer",
                                  infrastructure_error="" if reason == "timer" else reason)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--expect", required=True)
    parser.add_argument("--sample-seconds", type=float)
    parser.add_argument("--timeout-seconds", type=float)
    parser.add_argument("--min-free-kb", type=int, default=4194304)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or any(v is not None and v <= 0 for v in (args.sample_seconds, args.timeout_seconds)):
        parser.error("a command and positive time limits are required")
    result = run(command, args.log, args.sample_seconds or args.timeout_seconds, args.min_free_kb)
    output = args.log.read_text(errors="replace")
    ok, detail = classify(result, output, args.expect, args.sample_seconds is not None)
    print(f"{'OK' if ok else 'FAIL'} {detail}; exit={result.code}; log={args.log}")
    for line in output.splitlines():
        if re.match(r"(?:[0-9,]+ states generated|The depth of the complete state graph)", line):
            print(line)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
