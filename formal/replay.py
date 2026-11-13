#!/usr/bin/env python3
"""Extract a scheduling witness from TLC, or check it against the current model.

Only action names and process ids are saved. The generated wrapper executes
the actual Lifecycle actions, including their guards; no saved states are
injected. Nondeterministic outcomes along the schedule are explored by TLC.
"""

import argparse
import json
from pathlib import Path
import re
import shutil


def extract(log: str, model: str) -> list[str]:
    states = re.split(r"^State \d+: <([^>\n]+)>\s*$", log, flags=re.MULTILINE)
    parameterized = set(re.findall(r"^(\w+)\(self\) ==", model, re.MULTILINE))
    previous = {}
    actions = []
    for header, body in zip(states[1::2], states[2::2]):
        pc = re.search(r"^/\\ pc = (.*?)(?=^/\\|\Z)", body, re.MULTILINE | re.DOTALL)
        if pc is None:
            raise ValueError("missing pc in TLC state")
        current = dict(re.findall(r'(\d+)\s*:>\s*"(\w+)"', pc.group(1)))
        if header != "Initial predicate":
            label = header.split()[0]
            if label in parameterized:
                candidates = [p for p, value in previous.items()
                              if value == label and current.get(p) != value]
                if len(candidates) != 1:
                    raise ValueError(f"cannot identify process for {label}: {candidates}")
                label += f"({candidates[0]})"
            actions.append(label)
        previous = current
    if not actions:
        raise ValueError("no counterexample states found")
    return actions


def prepare(config: Path, directory: Path) -> tuple[Path, Path]:
    source = config.read_text()
    witness = re.search(r"^\\\* REPLAY: (.+)$", source, re.MULTILINE)
    if witness is None:
        raise ValueError("config has no REPLAY")
    root = config.parent.parent
    actions = json.loads((root / witness.group(1)).read_text())
    if not isinstance(actions, list) or not actions or any(
        not isinstance(a, str) or not re.fullmatch(r"[A-Za-z]\w*(\([0-9]+\))?", a) for a in actions
    ):
        raise ValueError("invalid replay action")
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / "Lifecycle.tla", directory / "Lifecycle.tla")
    name = "Replay_" + config.stem.replace("-", "_")
    cases = "\n                         [] ".join(f"replayStep = {i} -> {a}" for i, a in enumerate(actions, 1))
    module = directory / f"{name}.tla"
    module.write_text(rf"""---------------- MODULE {name} ----------------
EXTENDS Lifecycle
VARIABLE replayStep
ReplayInit == Init /\ replayStep = 1
ReplayNext == \/ /\ replayStep <= {len(actions)}
                 /\ Next
                 /\ CASE {cases}
                 /\ replayStep' = replayStep + 1
              \/ /\ replayStep = {len(actions) + 1}
                 /\ UNCHANGED <<vars, replayStep>>
=============================================================================
""")
    source = re.sub(r"^(INIT Init\nNEXT NextQ|SPECIFICATION Spec)$",
                    "INIT ReplayInit\nNEXT ReplayNext", source, flags=re.MULTILINE)
    # Other nondeterministic branches can be incompatible with this schedule.
    # They are not deadlocks of the unrestricted design. A replay succeeds
    # only when TLC reaches the specifically expected property violation.
    source += "\nCHECK_DEADLOCK FALSE\n"
    cfg = directory / f"{name}.cfg"
    cfg.write_text(source)
    return module, cfg


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--extract", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--prepare", type=Path)
    parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    if args.extract and args.output:
        model = (Path(__file__).parent / "Lifecycle.tla").read_text()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(extract(args.extract.read_text(), model), indent=2) + "\n")
    elif args.prepare and args.directory:
        prepare(args.prepare.resolve(), args.directory.resolve())
    else:
        parser.error("use --extract LOG --output JSON or --prepare CFG --directory DIR")


if __name__ == "__main__":
    main()
