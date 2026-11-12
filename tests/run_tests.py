# GeneralsX @feature Android security 12/11/2026
#
# A tiny, dependency-free test runner for the signing checks. The repository has no pytest
# configuration (the Android work is tested by the JVM suite, the scripts by the CI workflows), and
# the signing checks are pure standard-library Python, so this discovers `test_*.py` files under
# tests/ and runs every `test_*` function with plain asserts.
#
#   python3 tests/run_tests.py            # discover and run
#   python3 tests/run_tests.py security   # only tests/security/*.py
#
# It exits non-zero when any test fails and prints a summary.
import importlib.util
import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))


def discover(subdir=None):
    root = os.path.join(HERE, subdir) if subdir else HERE
    modules = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in sorted(filenames):
            if name.startswith("test_") and name.endswith(".py"):
                modules.append(os.path.join(dirpath, name))
    return sorted(modules)


def load(path):
    spec = importlib.util.spec_from_file_location("gx_test_" + os.path.basename(path)[:-3], path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    subdir = sys.argv[1] if len(sys.argv) > 1 else None
    files = discover(subdir)
    if not files:
        print("no test files found under tests/%s" % (subdir or ""))
        return 1

    passed = failed = 0
    for path in files:
        module = load(path)
        for name in sorted(dir(module)):
            if not name.startswith("test_"):
                continue
            fn = getattr(module, name)
            if not callable(fn):
                continue
            rel = os.path.relpath(path, HERE)
            try:
                fn()
                passed += 1
                print("PASS %s::%s" % (rel, name))
            except Exception:
                failed += 1
                print("FAIL %s::%s" % (rel, name))
                traceback.print_exc()
    print()
    print("%d passed, %d failed" % (passed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
