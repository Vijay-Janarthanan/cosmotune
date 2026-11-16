"""The cross-linker must match Android object files, even when a dependency enables C++."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("torrent_build", Path(__file__).with_name("build.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class CrossCompilerTest(unittest.TestCase):
    def test_android_uses_matching_cpp_target_and_a_self_contained_runtime(self):
        for arch, triplet in (("arm64", "aarch64-linux-android"), ("arm", "armv7a-linux-androideabi"), ("amd64", "x86_64-linux-android")):
            with self.subTest(arch=arch), tempfile.TemporaryDirectory() as folder, patch.object(builder.subprocess, "run") as run:
                cc = f'"/an ndk/bin/clang" --target={triplet}26'
                cxx = f'"/an ndk/bin/clang++" --target={triplet}26'
                env = builder.build("go", "android", arch, Path(folder) / "engine", cc, cxx)
                self.assertEqual(cc, env["CC"])
                self.assertEqual(cxx, env["CXX"])
                self.assertEqual("1", env["CGO_ENABLED"])
                self.assertEqual("7" if arch == "arm" else None, env.get("GOARM"))
                flags = next(arg for arg in run.call_args.args[0] if arg.startswith("-ldflags="))
                self.assertIn('"-extldflags=-Wl,-z,max-page-size=16384 -static-libstdc++"', flags)
                self.assertIn("-buildmode=pie", run.call_args.args[0])

    def test_a_partial_cross_toolchain_fails_before_go_can_choose_the_host_linker(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(builder.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                builder.build("go", "android", "arm64", Path(folder) / "engine", "target-clang")
            run.assert_not_called()

    def test_desktop_keeps_cgo_disabled(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(builder.subprocess, "run"):
            env = builder.build("go", "windows", "amd64", Path(folder) / "engine")
            self.assertEqual("0", env["CGO_ENABLED"])


if __name__ == "__main__":
    unittest.main()
