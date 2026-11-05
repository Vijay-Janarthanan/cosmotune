"""Tests for check-tracker-evidence.py, on a temporary repository. Run: python tools/test_check_tracker_evidence.py"""
import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location('check', Path(__file__).with_name('check-tracker-evidence.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

HEADER = '| ID | Feature | Platform | Status | Evidence |\n| --- | --- | --- | --- | --- |\n'


class CheckTrackerEvidenceTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = Path(self.dir.name)
        self.write('shared/src/jvmTest/kotlin/a/QueueTest.kt',
                   'class QueueTest {\n  @Test fun keepsOrder() {}\n  @Test fun dropsTheOldestEntryWhenTheQueueIsFull() {}\n}\n')
        self.write('shared/src/jvmMain/kotlin/a/Queue.kt', 'class Queue\nclass ProductionOnlyTest\n')
        self.write('web/app/src/queue.test.ts', "test('a', () => {})\n")
        self.write('remote/build.gradle.kts', '')

    def tearDown(self):
        self.dir.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def run_check(self, rows):
        self.write('FEATURE_TRACKER.md', HEADER + rows)
        out = io.StringIO()
        with redirect_stdout(out):
            code = check.main(['--root', str(self.root)])
        return code, out.getvalue()

    def test_existing_tests_pass(self):
        code, out = self.run_check(
            '| XX-01 | Queue | Android | Implemented | `QueueTest` (2), `QueueTest.keepsOrder`, `queue.test.ts`, `:remote:jvmTest`, '
            '`dropsTheOldestEntryWhenTheQueueIsFull` |\n')
        self.assertEqual(0, code, out)
        self.assertIn('1 Implemented rows checked, 0 problems', out)

    def test_a_row_without_a_test_fails(self):
        code, out = self.run_check('| XX-02 | Queue | Android | Implemented | `Queue` compiles; checked by hand. |\n')
        self.assertEqual(1, code)
        self.assertIn('FAIL XX-02: the evidence names no test', out)

    def test_missing_classes_methods_files_and_tasks_fail(self):
        code, out = self.run_check(
            '| XX-03 | Queue | Android | Implemented | `QueueTest`, `RenamedQueueTest`, `QueueTest.notThere`, '
            '`ProductionOnlyTest`, `gone.test.ts`, `:nomodule:jvmTest` |\n')
        self.assertEqual(1, code)
        for name in ('test class RenamedQueueTest', 'test method QueueTest.notThere', 'test class ProductionOnlyTest',
                     'test file gone.test.ts', 'test task :nomodule:jvmTest'):
            self.assertIn(name, out)

    def test_rows_that_are_not_implemented_are_not_checked(self):
        code, out = self.run_check('| XX-04 | Queue | Android | In progress | nothing yet |\n'
                                   '| XX-05 | Queue | Android | Decision recorded | `MissingTest` |\n')
        self.assertEqual(0, code, out)
        self.assertIn('0 Implemented rows checked', out)

    def test_a_wrong_count_is_a_note_and_units_are_not_counts(self):
        code, out = self.run_check('| XX-06 | Queue | Android | Implemented | `QueueTest` (5), `QueueTest` (400 dp/1.5 font) |\n')
        self.assertEqual(0, code, out)
        self.assertIn('NOTE XX-06: QueueTest: evidence says 5, source declares 2', out)
        self.assertNotIn('says 400', out)


if __name__ == '__main__':
    unittest.main()
