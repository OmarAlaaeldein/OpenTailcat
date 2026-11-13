import json
from pathlib import Path
import tempfile
import unittest

from replay import extract, prepare


class ReplayTest(unittest.TestCase):
    def test_extract_uses_action_and_process_not_saved_values(self):
        log = '''State 1: <Initial predicate>
/\\ pc = (1 :> "Step" @@ 2 :> "Step")
/\\ value = "arbitrary saved data"
State 2: <Step line 1, col 1 to line 2, col 2 of module Lifecycle>
/\\ pc = (1 :> "Step" @@ 2 :> "Done")
/\\ value = "different saved data"
'''
        self.assertEqual(["Step(2)"], extract(log, "Step(self) == TRUE"))

    def test_incomplete_or_ambiguous_trace_is_rejected(self):
        with self.assertRaises(ValueError):
            extract("TLC crashed before computing initial states", "")
        with self.assertRaises(ValueError):
            extract('State 1: <Initial predicate>\n/\\ pc = (1 :> "Step")\n'
                    'State 2: <Step line 1>\n/\\ pc = (1 :> "Step")\n', "Step(self) == TRUE")

    def test_wrapper_runs_current_actions_and_keeps_property(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "configs").mkdir()
            (root / "replays").mkdir()
            (root / "Lifecycle.tla").write_text("current source, not saved states")
            witness = root / "replays" / "bug.json"
            witness.write_text(json.dumps(["Step(2)"]))
            cfg = root / "configs" / "bug.cfg"
            cfg.write_text('\\* REPLAY: replays/bug.json\nINIT Init\nNEXT NextQ\nINVARIANT Ownership\n')
            module, generated_cfg = prepare(cfg, root / "output")
            self.assertIn("-> Step(2)", module.read_text())
            self.assertIn("/\\ Next", module.read_text())
            self.assertIn("Init /\\ replayStep = 1", module.read_text())
            self.assertIn("INVARIANT Ownership", generated_cfg.read_text())
            self.assertIn("NEXT ReplayNext", generated_cfg.read_text())
            self.assertEqual("current source, not saved states", (module.parent / "Lifecycle.tla").read_text())
            witness.write_text(json.dumps(["Step(2) /\\ TRUE"]))
            with self.assertRaises(ValueError):
                prepare(cfg, root / "output")


if __name__ == "__main__":
    unittest.main()
