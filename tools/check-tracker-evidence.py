#!/usr/bin/env python3
"""Check that every Implemented row of FEATURE_TRACKER.md names tests that exist.

For each row whose status is `Implemented`, the evidence column's backticked names are read:
  * `SomethingTest` / `SomethingTests` (optionally `.method`): a test class in a test source set;
  * `name.test.ts`, `name_test.go`, `test_name.py`: a test file;
  * a long lower-camel name (30+ characters): a test method, if it is declared in a test source;
  * `:module:...Test` Gradle tasks: a test task of an existing module.
A row fails when it names no test at all, or names a test class/file/method that is not in the repository.
Counts written as `SomethingTest` (6) are compared with the class's @Test methods and reported as notes
(a note does not fail the check: parameterised and inherited tests make the count approximate).

Usage: python tools/check-tracker-evidence.py [--root DIR] [--tracker FILE]
Exit code 0 when every Implemented row passes, 1 otherwise.
"""
import argparse
import os
import re
import sys
from pathlib import Path

SKIPPED_DIRS = {'.git', '.gradle', 'build', 'node_modules', '.idea', '.kotlin', '.cxx', 'dist', '.output', '.wxt'}
TEST_DIR_MARKERS = ('/src/test/', '/src/jvmTest/', '/src/androidTest/', '/src/commonTest/', '/src/androidUnitTest/',
                    '/src/desktopTest/', '/src/androidInstrumentedTest/', '/src/jvmSharedTest/')
TEST_CLASS = re.compile(r'^([A-Z][A-Za-z0-9_]*Tests?)(?:\.([A-Za-z_][A-Za-z0-9_]*))?$')
TEST_FILE = re.compile(r'(?:^|/)([A-Za-z0-9_.-]+\.test\.tsx?|[A-Za-z0-9_]+_test\.go|test_[A-Za-z0-9_]+\.py)$')
TEST_TASK = re.compile(r'^:([A-Za-z0-9_-]+):[A-Za-z]*[Tt]est[A-Za-z]*$')
METHOD = re.compile(r'^[a-z][A-Za-z0-9]{29,}$')
# Only "(6)", "(6, ...", "(6: ..." and "(6; ..." are counts; "(400 dp" or "(4 real ..." are not.
COUNTED = re.compile(r'`([A-Z][A-Za-z0-9_]*Tests?)` \((\d+)[),:;]')


def is_test_path(rel: str) -> bool:
    rel = '/' + rel
    return any(marker in rel for marker in TEST_DIR_MARKERS) or rel.startswith('/uiTests/') \
        or rel.endswith(('.test.ts', '.test.tsx', '_test.go')) or Path(rel).name.startswith('test_')


class Repository:
    """The test sources of a checkout: class name to files, file names, and the module folders."""

    def __init__(self, root: Path):
        self.root = root
        self.classes: dict[str, list[Path]] = {}
        self.test_file_names: set[str] = set()
        self.test_texts: dict[Path, str] = {}
        for folder, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
            for name in files:
                path = Path(folder, name)
                rel = path.relative_to(root).as_posix()
                if not is_test_path(rel):
                    continue
                self.test_file_names.add(name)
                if path.suffix not in ('.kt', '.java', '.ts', '.tsx', '.go', '.py'):
                    continue
                text = path.read_text(encoding='utf-8', errors='replace')
                self.test_texts[path] = text
                declared = set(re.findall(r'\b(?:class|object)\s+([A-Za-z_][A-Za-z0-9_]*)', text))
                declared.add(path.stem)
                for cls in declared:
                    self.classes.setdefault(cls, []).append(path)

    def module_exists(self, module: str) -> bool:
        return (self.root / module).is_dir()

    def has_method(self, method: str, files=None) -> bool:
        pattern = re.compile(r'fun\s+`?' + re.escape(method) + r'\b|\b' + re.escape(method) + r'\s*\(|' + re.escape(method) + '`')
        texts = (self.test_texts.get(f, '') for f in files) if files is not None else self.test_texts.values()
        return any(pattern.search(text) for text in texts)

    def test_count(self, cls: str):
        files = self.classes.get(cls)
        if not files:
            return None
        if any(re.search(r'@RunWith\(Parameterized|@ParameterizedTest|\.each\(', self.test_texts.get(f, '')) for f in files):
            return None  # one declared test runs once per parameter
        return sum(len(re.findall(r'@Test\b|\bfun\s+Test[A-Z]\w*\(|\b(?:it|test)\(', self.test_texts.get(f, ''))) for f in files)


def tracker_rows(text: str):
    """(id, status, evidence) for every feature row of the tracker tables."""
    for line in text.splitlines():
        match = re.match(r'^\|\s*([A-Z]{2}-\d+)\s*\|', line)
        if not match:
            continue
        cells = [cell.strip() for cell in line.strip().strip('|').split('|')]
        if len(cells) < 5:
            continue
        yield cells[0], cells[3], ' | '.join(cells[4:])


def check_row(evidence: str, repo: Repository):
    """(test references found, missing references, count notes) for one evidence column."""
    found, missing, notes = [], [], []
    for token in re.findall(r'`([^`]+)`', evidence):
        token = token.strip().removesuffix('()')
        cls = TEST_CLASS.match(token)
        if cls:
            files = repo.classes.get(cls.group(1))
            if not files:
                missing.append(f'test class {cls.group(1)}')
            elif cls.group(2) and not repo.has_method(cls.group(2), files):
                missing.append(f'test method {token}')
            else:
                found.append(token)
            continue
        file = TEST_FILE.search(token)
        if file:
            (found if file.group(1) in repo.test_file_names else missing).append(
                token if file.group(1) in repo.test_file_names else f'test file {token}')
            continue
        task = TEST_TASK.match(token)
        if task:
            (found if repo.module_exists(task.group(1)) else missing).append(
                token if repo.module_exists(task.group(1)) else f'test task {token}')
            continue
        if METHOD.match(token) and repo.has_method(token):
            found.append(token)
    for cls, claimed in COUNTED.findall(evidence):
        actual = repo.test_count(cls)
        if actual is not None and actual != int(claimed):
            notes.append(f'{cls}: evidence says {claimed}, source declares {actual}')
    return found, missing, notes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--tracker', type=Path)
    args = parser.parse_args(argv)
    tracker = args.tracker or args.root / 'FEATURE_TRACKER.md'
    repo = Repository(args.root)
    failures = 0
    checked = 0
    for row_id, status, evidence in tracker_rows(tracker.read_text(encoding='utf-8')):
        if status != 'Implemented':
            continue
        checked += 1
        found, missing, notes = check_row(evidence, repo)
        if not found:
            failures += 1
            print(f'FAIL {row_id}: the evidence names no test')
        if missing:
            failures += 1
            print(f'FAIL {row_id}: not in the repository: ' + ', '.join(missing))
        for note in notes:
            print(f'NOTE {row_id}: {note}')
    print(f'{checked} Implemented rows checked, {failures} problems')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
