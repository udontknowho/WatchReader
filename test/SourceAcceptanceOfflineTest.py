#!/usr/bin/env python3
"""验收调度器离线回归。模拟子进程，不启动 JVM、不联网。"""
import contextlib
import importlib.util
import io
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('source_acceptance', pathlib.Path(__file__).with_name('SourceAcceptance.py'))
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


class AcceptanceOfflineTest(unittest.TestCase):
    def test_required_paths_and_worker_limit(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                acceptance.arguments([])
            with self.assertRaises(SystemExit):
                acceptance.arguments(['--candidates', 'a', '--out', 'b', '--workers', '7'])
            with self.assertRaises(SystemExit):
                acceptance.arguments(['--candidates', 'a', '--out', 'b', '--total-timeout', '0'])

    def test_arbitrary_candidates_timeout_and_evidence(self):
        with tempfile.TemporaryDirectory(prefix='watch-acceptance-') as tmp:
            root = pathlib.Path(tmp)
            candidates = root / 'custom.json'
            out = root / 'evidence'
            sources = [{'bookSourceName': 'fixture', 'bookSourceUrl': 'https://fixture', 'bookSourceType': 0,
                        'searchUrl': '/s', 'ruleSearch': {'name': 'a'}, 'ruleToc': {'chapterList': 'a'},
                        'ruleContent': {'content': 'p'}}, {'bookSourceType': 1}]
            candidates.write_text(json.dumps(sources))
            args = acceptance.arguments(['--candidates', str(candidates), '--out', str(out),
                                         '--classpath', 'fake.jar', '--workers', '1',
                                         '--first-timeout', '0.2', '--total-timeout', '1', '--no-retry'])
            with mock.patch.object(acceptance.subprocess, 'run', side_effect=subprocess.TimeoutExpired('fixture', 0.2, output=b'partial')) as run:
                with contextlib.redirect_stdout(io.StringIO()):
                    acceptance.run(args)
            self.assertEqual(run.call_count, 1)
            self.assertLessEqual(run.call_args.kwargs['timeout'], 0.2)
            self.assertEqual(run.call_args.args[0][-2:], [str(candidates), '0'])
            results = json.loads((out / 'latest-results.json').read_text())
            self.assertEqual({r['status'] for r in results}, {'budget_timeout', 'skipped_nontext_or_incomplete'})
            self.assertEqual(json.loads((out / 'passed.json').read_text()), [])
            self.assertEqual((out / 'probe-0-round-1.log').read_text(), 'partial')
            with self.assertRaises(ValueError):
                acceptance.run(args)

    def test_passed_and_retry_without_previous_snapshot(self):
        with tempfile.TemporaryDirectory(prefix='watch-acceptance-') as tmp:
            root = pathlib.Path(tmp)
            candidates = root / 'other.json'
            candidates.write_text(json.dumps([{'bookSourceName': 'fixture', 'bookSourceUrl': 'https://fixture',
                'searchUrl': '/s', 'ruleSearch': {'name': 'a'}, 'ruleToc': {'chapterList': 'a'}, 'ruleContent': {'content': 'p'}}]))
            args = acceptance.arguments(['--candidates', str(candidates), '--out', str(root / 'out'), '--classpath', 'fake'])
            outcomes = [subprocess.CompletedProcess([], 0, 'ACCEPTANCE {"status":"inconclusive_timeout"}\n'),
                        subprocess.CompletedProcess([], 0, 'ACCEPTANCE {"status":"passed"}\n')]
            with mock.patch.object(acceptance.subprocess, 'run', side_effect=outcomes) as run:
                with contextlib.redirect_stdout(io.StringIO()):
                    acceptance.run(args)
            self.assertEqual(run.call_count, 2)
            self.assertEqual(len(json.loads((root / 'out/passed.json').read_text())), 1)
            self.assertTrue((root / 'out/probe-0-round-1.log').is_file())
            self.assertTrue((root / 'out/probe-0-round-2.log').is_file())


if __name__ == '__main__':
    unittest.main()
