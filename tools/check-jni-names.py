#!/usr/bin/env python3
"""Checks that every JNI method declared in Kotlin or Java has a native export, and every export a declaration.

    python tools/check-jni-names.py            (from the repository root; exit code 1 on any mismatch)

A JNI export is found by name only (`Java_<package>_<Class>_<method>`), so a renamed package, class or method
compiles and links on both sides and fails only at run time with `UnsatisfiedLinkError`, which no unit test sees.
This script reads the sources instead:

- declarations: `external fun` in Kotlin and `native` methods in Java, with the package and the enclosing class
  (an `object`, a `class`, a top-level function's `<File>Kt`; a companion object's `@JvmStatic` method belongs to the
  outer class);
- exports: every `Java_...(` in C and C++ files, including the ones built by a `#define X(RETURN_TYPE, NAME, ...)`
  macro that pastes `Java_<prefix>_##NAME` (Media3's FFmpeg extension does that);
- library names: every `System.loadLibrary("...")` and `const val LIBRARY[_NAME] = "..."` of a file that loads a
  library (`loadLibrary`, `System.load`, `mapLibraryName` or the desktop's `loadNative`), against the CMake `add_library(<name> SHARED ...)` targets and the prebuilt `jniLibs/*/lib<name>.so`.

It looks at tracked files only (`git ls-files`), so build outputs never count.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def tracked():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True).stdout.decode("utf-8")
    return [p for p in out.split("\0") if p]


def read(path):
    with open(os.path.join(ROOT, path), "rb") as f:
        return f.read().decode("utf-8", errors="replace").replace("\r\n", "\n")


def mangle(part):
    out = []
    for ch in part:
        if ch == "_":
            out.append("_1")
        elif ch == ";":
            out.append("_2")
        elif ch == "[":
            out.append("_3")
        elif ch in "./":
            out.append("_")
        elif ch == "$":
            out.append("_00024")
        elif ch.isascii() and (ch.isalnum()):
            out.append(ch)
        else:
            out.append("_0%04x" % ord(ch))
    return "".join(out)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


CLASS_RE = re.compile(r"\b(?:(companion)\s+object(?:\s+(\w+))?|(?:object|class|interface)\s+(\w+))")


def kotlin_declarations(path, text):
    """(binary class name, method, line) for each `external fun`."""
    package = (re.search(r"^\s*package\s+([\w.]+)", text, re.M) or [None, ""])[1]
    file_class = re.sub(r"\.kt$", "", os.path.basename(path)) + "Kt"
    jvm_name = re.search(r"@file:JvmName\(\"(\w+)\"\)", text)
    if jvm_name:
        file_class = jvm_name.group(1)
    code = strip_comments(text)
    # Blank out string literals so that braces inside them do not count.
    code = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', code)
    stack = []  # (name, depth at which its body opened, is_companion)
    depth = 0
    pending = None
    found = []
    for lineno, line in enumerate(code.split("\n"), 1):
        m = CLASS_RE.search(line)
        if m:
            if m.group(1):
                pending = (m.group(2) or "Companion", True)
            else:
                pending = (m.group(3), False)
        for decl in re.finditer(r"\bexternal\s+fun\s+(\w+)", line):
            jvm_static = "@JvmStatic" in line or "@JvmStatic" in code.split("\n")[lineno - 2] if lineno > 1 else False
            names = [s[0] for s in stack]
            if not names:
                owner = file_class
            else:
                if stack[-1][2] and jvm_static:
                    names = names[:-1]  # a @JvmStatic member of a companion is static on the outer class
                owner = "$".join(names) if names else file_class
            binary = (package + "." if package else "") + owner
            found.append((binary, decl.group(1), f"{path}:{lineno}"))
        for ch in line:
            if ch == "{":
                depth += 1
                if pending:
                    stack.append((pending[0], depth, pending[1]))
                    pending = None
            elif ch == "}":
                if stack and stack[-1][1] == depth:
                    stack.pop()
                depth -= 1
        if pending and line.rstrip().endswith(")") is False and "{" not in line and not line.strip().endswith(","):
            # `object Foo` with no body on this line and nothing that continues it: no body.
            if re.search(r"\b(object|class|interface)\s+\w+\s*$", line.strip()) is None:
                pending = None
    return found


def java_declarations(path, text):
    package = (re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M) or [None, ""])[1]
    code = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', strip_comments(text))
    stack, depth, pending, found = [], 0, None, []
    for lineno, line in enumerate(code.split("\n"), 1):
        m = re.search(r"\b(?:class|interface|enum)\s+(\w+)", line)
        if m:
            pending = m.group(1)
        for decl in re.finditer(r"\bnative\b[^;(]*?\b(\w+)\s*\(", line):
            owner = "$".join(s[0] for s in stack)
            found.append(((package + "." if package else "") + owner, decl.group(1), f"{path}:{lineno}"))
        for ch in line:
            if ch == "{":
                depth += 1
                if pending:
                    stack.append((pending, depth))
                    pending = None
            elif ch == "}":
                if stack and stack[-1][1] == depth:
                    stack.pop()
                depth -= 1
    return found


def native_exports(path, text):
    code = strip_comments(text)
    found = {}
    for m in re.finditer(r"\b(Java_[A-Za-z0-9_]+)\s*\(", code):
        if "##" not in code[m.start():m.end() + 2]:
            found.setdefault(m.group(1), f"{path}:{code.count(chr(10), 0, m.start()) + 1}")
    # Macros that paste the method name: #define NAME(RETURN_TYPE, METHOD, ...) ... Java_prefix_##METHOD
    for d in re.finditer(r"#define\s+(\w+)\s*\(\s*\w+\s*,\s*(\w+)[^)]*\)((?:[^\n]*\\\n)*[^\n]*)", code):
        macro, param, body = d.group(1), d.group(2), d.group(3)
        prefix = re.search(r"\b(Java_[A-Za-z0-9_]*?)_##\s*" + param + r"\b", body)
        if not prefix:
            continue
        for use in re.finditer(r"^\s*" + macro + r"\s*\(\s*[^,]+,\s*(\w+)", code, re.M):
            found.setdefault(f"{prefix.group(1)}_{use.group(1)}", f"{path}:{code.count(chr(10), 0, use.start()) + 1}")
    return found


def main():
    files = tracked()
    declared = []
    exports = {}
    loads = []
    targets = set()
    prebuilt = set()
    for path in files:
        if path.endswith(".kt"):
            text = read(path)
            if "external fun" in text:
                declared += kotlin_declarations(path, text)
        elif path.endswith(".java"):
            text = read(path)
            if re.search(r"\bnative\b", text):
                declared += java_declarations(path, text)
        elif re.search(r"\.(c|cc|cpp|h|hpp)$", path):
            text = read(path)
            if "Java_" in text:
                for name, where in native_exports(path, text).items():
                    exports.setdefault(name, where)
        if path.endswith((".kt", ".java")):
            text = read(path)
            loads_library = re.search(r"loadLibrary|mapLibraryName|System\.load\b|\bloadNative\(", text)
            for m in re.finditer(r"System\.loadLibrary\(\s*\"([\w.-]+)\"\s*\)", text):
                loads.append((m.group(1), path))
            if loads_library:
                for m in re.finditer(r"\bconst\s+val\s+LIBRARY(?:_NAME)?\s*=\s*\"([\w.-]+)\"", text):
                    loads.append((m.group(1), path))
        if os.path.basename(path) == "CMakeLists.txt":
            for m in re.finditer(r"add_library\s*\(\s*([\w-]+)\s+SHARED", strip_comments(read(path).replace("#", "//"))):
                targets.add(m.group(1))
        m = re.search(r"/jniLibs/[^/]+/lib([\w-]+)\.so$", path)
        if m:
            prebuilt.add(m.group(1))

    wanted = {}
    for binary, method, where in declared:
        wanted.setdefault("Java_" + mangle(binary.replace(".", "/")) + "_" + mangle(method), where)

    problems = 0
    print(f"JNI declarations: {len(wanted)}; native exports: {len(exports)}")
    for name in sorted(set(wanted) - set(exports)):
        problems += 1
        print(f"  declared, no export: {name}  ({wanted[name]})")
    for name in sorted(set(exports) - set(wanted)):
        problems += 1
        print(f"  exported, no declaration: {name}  ({exports[name]})")
    print(f"library names loaded: {sorted({n for n, _ in loads})}")
    print(f"CMake shared targets: {sorted(targets)}; prebuilt: {sorted(prebuilt)}")
    for name, path in sorted(set(loads)):
        if name not in targets and name not in prebuilt:
            problems += 1
            print(f"  loaded but neither a CMake target nor a prebuilt library: {name}  ({path})")
    for name in sorted(targets - {n for n, _ in loads}):
        print(f"  note: CMake target {name} is not loaded by name from Kotlin or Java")
    print("OK" if problems == 0 else f"{problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
