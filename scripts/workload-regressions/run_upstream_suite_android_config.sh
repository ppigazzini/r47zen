#!/bin/bash
# Run upstream's own test suite as the app builds the core: mini-gmp with the GMP names
# of r47zen/gmp_names.h, CALCMODEL=USER_R47, and the compiler and flags of the release
# library, read from its compile_commands.json: the NDK's clang, -O3, ThinLTO, the PGO
# profile when the build used one, _FORTIFY_SOURCE, NDEBUG, stack protection, and the
# firmware's -fsigned-char -fno-strict-aliasing. Upstream's own build of its suite uses
# the full GMP, C47, and -O0, so a wrong GMP stand-in passes it, and so does a result
# that is right only under the app's flags. Page 08 of android/docs/dev, "Upstream
# Suite On The App's Configuration", lists what it catches and what it cannot.
#
# Run it after a release build of the app (build_android.sh in CI, which leaves build.sim
# configured). It reads the release's database (R47_APP_COMPILE_DB, or the newest arm64
# RelWithDebInfo one under android/app/.cxx), has ninja build only the generated inputs
# of the suite's units and the test programs, compiles those units, and links an x86_64
# executable: the NDK's clang builds for the host, so the release's target, sysroot,
# -m flags, include paths, and defines other than _FORTIFY_SOURCE and NDEBUG are left
# out, -fno-math-errno is added when the release's target implies it, and the run first
# checks that the effective _FORTIFY_SOURCE, NDEBUG, math errno, char signedness, and
# whether it optimizes match the release's. The run fails unless the suite ends
# normally, with its summary, after every case its test files hold, with no GMP memory
# held, and with every failed case in KNOWN_FAILURES, mismatch for mismatch.
#
# R47_SUITE_TARGET=app builds the suite for the release's own target instead, so that
# the code under test is the arm64 code the app ships, which no other CI step runs:
# the release's target, sysroot, and -m flags are kept, the glue's GTK stand-ins
# (stubs/, android_mocks.h with HOST_TOOL_BUILD) and its clocks (glib_clock.c) take
# GTK's place, and the suite links static against bionic. It runs nothing: it packs
# the suite and the files it reads, paths relative to the project root, into
# android/build/upstream-suite-app-target/upstream-suite-app-target.tar.
# R47_SUITE_RUN=<suite> runs a suite built elsewhere from the project root, where that
# tar unpacks, and checks it as above. android-ci builds the tar on x86-64 and runs it
# on an arm64 runner.
#
# R47_SUITE_LOG=<log> R47_SUITE_STATUS=<exit status> checks a saved log and builds nothing.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

python3 -I - "$PROJECT_ROOT" "${R47_APP_COMPILE_DB:-}" "${R47_SUITE_LOG:-}" "${R47_SUITE_STATUS:-1}" \
    "${R47_SUITE_TARGET:-host}" "${R47_SUITE_RUN:-}" <<'PY'
import concurrent.futures, glob, json, os, re, shlex, shutil, subprocess, sys, tarfile
from pathlib import Path

root, app_db, saved_log, saved_status = Path(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4])
target, prebuilt = sys.argv[5], sys.argv[6]
if target not in ("host", "app"):
    print(f"FAIL: R47_SUITE_TARGET is host or app, not {target}", flush=True)
    sys.exit(1)
for_app = target == "app"
glue = root / "android/app/src/main/cpp/r47zen"
gmp_dir = root / "android/.staged-native/cpp/gmp"
names = glue / "gmp_names.h"
sim_dir = root / "build.sim"
build_dir = root / ("android/build/upstream-suite-app-target" if for_app
                    else "android/build/upstream-suite-android-config")
# What the suite reads from the project root, besides its test files' directory:
# res/testPgms/testPgms.bin through its HAL's ioPathTestPgms, and src/c47/items.h,
# which it reads to resolve ITM_ names.
read_at_run = ("res/testPgms/testPgms.bin", "src/testSuite/tests", "src/c47/items.h")

# Cases that fail on the app's configuration for a reason upstream owns: the file, the
# case's IN and OUT lines, which stay put when upstream inserts lines above them, and
# every mismatch it prints, so that any other wrong value in the same case still fails.
KNOWN_FAILURES = {
    # mini-gmp's exact mpz_sizeinbase changes the confidence ALGDEP reports.
    ("algdep_cov.txt",
     'IN: SD=6 FARG=0 RX=REAL:"9876543.000000101250002151561507749"',
     'OUT: EC=0 SD=6 RX=REMA:"M1,3[1,-9876543,-1]" RY=REAL:"10"',
     ("Register Y value should be 10", "but it is real34 13 none")),
}


def fail(message):
    print(f"FAIL: {message}", flush=True)
    sys.exit(1)


def expected_cases():
    """The cases the listed test files hold: one for each OUT line."""
    tests = root / "src/testSuite/tests"
    names = [line.split(";")[0].strip() for line in (tests / "testSuiteList.txt").read_text().splitlines()]
    return sum(line.split(";")[0].strip().lower().startswith("out:")
               for name in names if name
               for line in (tests / f"{name}.txt").read_text(errors="replace").splitlines())


def check(text, status, log):
    # testSuite returns 1 for a failed case or GMP memory left over, and 0 otherwise; an
    # early exit(-1) or a signal is neither, and leaves no summary.
    summary = re.search(r"NUMBER OF TESTS +(\d+).*?(\d+) TESTS? +PASSED.*?(\d+) TESTS? +FAILED",
                        text, re.DOTALL)
    if status not in (0, 1) or not summary:
        fail(f"the suite stopped early (status {status}, summary {'found' if summary else 'missing'}); see {log}")
    total, passed, failed = map(int, summary.groups())
    print(f"{total} cases, {passed} passed, {failed} failed")
    if total != expected_cases() or passed + failed != total:
        fail(f"the suite ran {total} cases, and its test files hold {expected_cases()}; see {log}")

    # abortTest prints a record for each wrong value of a case, each ending in the case's
    # input and current lines, its file and line, and a dashed line. A record's mismatch
    # lines are what it printed since the dashed line or file header before it.
    cases = {}
    for block in re.split(r"\n-{20,}\n", text):
        block = block.split("Performing tests from file")[-1]
        record = re.search(r"(?:^|\n)([^\n]*)\n([^\n]*)\nin file (\S+) line (\d+)\s*$", block)
        if record:
            mismatches = {line.strip() for line in block.splitlines()
                          if " should be " in line or line.startswith("but it is ")}
            name, _, _, old = cases.get((record[3], int(record[4])), (record[3], "", "", ()))
            cases[(record[3], int(record[4]))] = (name, record[1], record[2],
                                                  tuple(sorted(mismatches | set(old))))
    known = {case for case in cases.values() if case in KNOWN_FAILURES}
    for case in sorted(known):
        print(f"known failure: {case[0]}: {case[1]}")
    for case in sorted(KNOWN_FAILURES - known):
        print(f"NOTE: a known failure no longer fails as listed; update KNOWN_FAILURES: {case[0]}: {case[1]}")
    unexpected = sorted(where for where, case in cases.items() if case not in KNOWN_FAILURES)
    for name, line in unexpected:
        _, case_in, case_out, mismatches = cases[(name, line)]
        print(f"unexpected failure: {name} line {line}: {case_in} {case_out} {' / '.join(mismatches)}")
    if len(cases) != failed:
        fail(f"the suite counted {failed} failed cases and printed {len(cases)}; see {log}")
    if not re.search(r"End of testsuite\nC47 owns +\d+ bytes and GMP owns +0 bytes", text):
        fail(f"GMP memory was left over at the end of the suite; see {log}")
    if unexpected:
        fail(f"{len(unexpected)} upstream cases fail on the app's configuration; see {log}")
    print("OK: upstream's suite on the app's configuration: every failure is a known one.")


def run_and_check(suite):
    # Upstream's suite runs from the project root, where res/testPgms and the test files
    # are. Its stderr apart, so that nothing it prints there lands inside a record.
    log, errors = suite.parent / "testSuite.log", suite.parent / "testSuite.stderr.log"
    with log.open("w") as out, errors.open("w") as err:
        try:
            status = subprocess.run([str(suite), "src/testSuite/tests/testSuiteList.txt"],
                                    cwd=root, stdout=out, stderr=err, timeout=1800).returncode
        except subprocess.TimeoutExpired:
            fail(f"the suite ran for 30 minutes; see {log}")
        except OSError as error:
            fail(f"{suite} does not run here: {error}")
    if errors.stat().st_size:
        print(f"NOTE: the suite wrote to stderr; see {errors}")
    check(log.read_text(errors="replace"), status, log)


if saved_log:
    check(Path(saved_log).read_text(errors="replace"), saved_status, saved_log)
    sys.exit(0)

if prebuilt:
    suite = Path(prebuilt).resolve()
    for needed in (suite, *(root / path for path in read_at_run)):
        if not needed.exists():
            fail(f"no {needed}: unpack the suite's tar in the project root first.")
    run_and_check(suite)
    sys.exit(0)

if not app_db:
    found = glob.glob(str(root / "android/app/.cxx/RelWithDebInfo/*/arm64-v8a/compile_commands.json"))
    app_db = max(found, key=os.path.getmtime, default="")
for needed, hint in ((app_db, "build the release first"),
                     (sim_dir / "compile_commands.json", "build the simulator first"),
                     (gmp_dir / "mini-gmp.c", "stage the native inputs first")):
    if not needed or not Path(needed).is_file():
        fail(f"no {needed or 'release compile_commands.json'}: {hint}.")

# The release's compile of decNumber.c, every flag kept in order but those that name the
# app's tree: its include paths, forced include, defines, and output; for the host, the
# flags that name the target go too.
release = next(c for c in json.loads(Path(app_db).read_text())
               if c["file"].endswith("/decNumberICU/decNumber.c"))
release_argv = shlex.split(release["command"]) if "command" in release else release["arguments"]
if Path(release_argv[0]).name in ("ccache", "sccache"):
    release_argv = release_argv[1:]
cc = release_argv[0]
target_flag = r"--target=.*|--sysroot=.*|-m.*|-no-canonical-prefixes"
not_built = (r"-fuse-ld=.*|-fsanitize.*|-fprofile-(generate|instr-generate|update).*"
             r"|-W(?!p,).*")
left_out = re.compile(not_built if for_app else f"{target_flag}|{not_built}")
kept_macro = re.compile(r"(-D|-U|-Wp,-D|-Wp,-U)(_FORTIFY_SOURCE|NDEBUG)\b")
app_flags, release_probe, tokens = [], [cc], iter(release_argv[1:])
for token in tokens:
    if token in ("-o", "-include", "-isystem", "-I", "-MF", "-MT", "-MQ"):
        value = next(tokens)
        if token in ("-include", "-isystem", "-I"):
            release_probe += [token, value]
    elif token == "-c" or token == release["file"]:
        pass
    elif token.startswith(("-I", "-D", "-U", "-Wp,-D", "-Wp,-U")):
        release_probe.append(token)
        if kept_macro.match(token):
            app_flags.append(token)
    else:
        release_probe.append(token)
        if not left_out.fullmatch(token):
            app_flags.append(token)


# The macros that decide what the code means, as the release and as one suite unit see
# them: the lane must not test a configuration the release does not build.
def macros(directory, command):
    probe = [t for t in command if t not in ("-c", "-w") and not t.endswith((".c", ".o"))]
    probe = [t for i, t in enumerate(probe) if t != "-o" and (i == 0 or probe[i - 1] != "-o")]
    result = subprocess.run([*probe, "-dM", "-E", "-x", "c", "/dev/null"], cwd=directory,
                            capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        fail(f"the compiler could not print its macros: {shlex.join(probe)}")
    found = dict(re.findall(r"#define (\S+) ?(.*)", result.stdout))
    return {name: found.get(name) for name in
            ("_FORTIFY_SOURCE", "NDEBUG", "__NO_MATH_ERRNO__", "__CHAR_UNSIGNED__", "__OPTIMIZE__")}


release_macros = macros(release["directory"], release_probe)
if release_macros["__NO_MATH_ERRNO__"] and not for_app:
    app_flags.append("-fno-math-errno")  # Android's target default; the host's is math errno
link_flags = [t for t in app_flags if t.startswith(("-flto", "-fprofile-use", "--target=", "--sysroot="))]
if any(t.startswith("-flto") for t in link_flags):
    link_flags.append("-fuse-ld=lld")
launcher = ["ccache"] if shutil.which("ccache") else []
print(f"database: {app_db}\ncompiler: {cc}\napp flags: {shlex.join(app_flags)}\n"
      f"link flags: {shlex.join(link_flags)}", flush=True)

# The suite's units, rewritten for the app: the staged mini-gmp ahead of the system's
# GMP, the GMP names, R47, and the release's flags last, so they win over the suite's.
# For the app's target, the host's include paths, machine flags, PulseAudio, and
# -pthread go too, and the glue's GTK stand-ins come first.
units = [c for c in json.loads((sim_dir / "compile_commands.json").read_text())
         if "src/testSuite/testSuite.p/" in c["output"]]
if not units:
    fail("no suite units in build.sim/compile_commands.json.")
dropped = re.compile(r"-(MD|c|O.*|g.*|W.*|fdiagnostics-color.*|fsanitize.*|DCALCMODEL=.*)"
                     + (r"|-(I/usr/.*|m.*|DWITH_PULSEAUDIO|pthread)" if for_app else ""))
stand_ins = [f"-I{glue}/stubs", f"-I{glue}", "-DHOST_TOOL_BUILD"] if for_app else []
jobs = []
for index, unit in enumerate(units):
    # Every word before the first flag is the compiler, after a launcher meson may
    # put first: "ccache cc" on a runner, "cc" here.
    argv = shlex.split(unit["command"])
    while argv and not argv[0].startswith("-"):
        argv.pop(0)
    kept, tokens = [], iter(argv)
    for token in tokens:
        if token in ("-MQ", "-MF", "-o"):
            next(tokens)
        elif token != unit["file"] and not dropped.fullmatch(token):
            kept.append(token)
    jobs.append((unit["directory"], [
        *launcher, cc, *stand_ins, f"-I{gmp_dir}", "-include", str(names), *kept, *app_flags,
        "-DCALCMODEL=USER_R47", "-w", "-c", unit["file"],
        "-o", str(build_dir / f"objects/{index:03d}.o")]))
jobs.append((str(root), [*launcher, cc, *app_flags, "-w", f"-I{gmp_dir}", "-c",
                         str(gmp_dir / "mini-gmp.c"), "-o", str(build_dir / "objects/mini-gmp.o")]))
if for_app:  # every glue unit gets android_mocks.h through -include, as in the app
    jobs.append((str(root), [*launcher, cc, *app_flags, *stand_ins, f"-I{gmp_dir}", "-include",
                             str(glue / "android_mocks.h"), "-w", "-c", str(glue / "glib_clock.c"),
                             "-o", str(build_dir / "objects/glib_clock.o")]))
lane_macros = macros(jobs[0][0], [t for t in jobs[0][1] if t not in launcher])
if release_macros != lane_macros:
    fail(f"the lane would build {lane_macros}, the release builds {release_macros}.")
print(f"macros as the release: {release_macros}", flush=True)

# The generated files those units read and the test programs, built in build.sim and
# staged where the suite reads them, as build_sim_assets.sh --run-tests does.
query = subprocess.run(["ninja", "-C", str(sim_dir), "-t", "query", *(u["output"] for u in units)],
                       capture_output=True, text=True, check=True).stdout
generated, inputs = set(), False
for line in query.splitlines():
    entry = line.strip()
    if entry == "outputs:" or not line.startswith(" "):
        inputs = False
    elif entry.startswith("input:"):
        inputs = True
    elif inputs:
        path = entry.removeprefix("|| ").removeprefix("| ")
        if not path.startswith(("../", "/")):
            generated.add(path)
subprocess.run(["ninja", "-C", str(sim_dir), "testPgms", *sorted(generated)], check=True)
(root / "res/testPgms").mkdir(parents=True, exist_ok=True)
shutil.copy(sim_dir / "src/generateTestPgms/testPgms.bin", root / "res/testPgms/")


def compile_one(job):
    directory, command = job
    return subprocess.run(command, cwd=directory, capture_output=True, text=True)


shutil.rmtree(build_dir, ignore_errors=True)
(build_dir / "objects").mkdir(parents=True)
with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
    for result in pool.map(compile_one, jobs):
        if result.returncode != 0:
            print(result.stderr, file=sys.stderr)
            fail(f"a compile failed: {shlex.join(result.args)}")
objects = sorted(map(str, (build_dir / "objects").glob("*.o")))
suite = build_dir / "testSuite"
if for_app:
    # Static, so the suite runs on an arm64 Linux with no Android userland. Its libc and
    # libm are the NDK's static bionic, then, not a device's libc.so and libm.so.
    subprocess.run([cc, *objects, *link_flags, "-static", "-lm", "-o", str(suite)], check=True)
    bundle = build_dir / "upstream-suite-app-target.tar"
    with tarfile.open(bundle, "w") as tar:
        for path in (suite, *(root / path for path in read_at_run)):
            tar.add(path, arcname=str(path.relative_to(root)))
    print(f"OK: packed upstream's suite for the app's target: {bundle}")
    sys.exit(0)
gtk = shlex.split(subprocess.run(["pkg-config", "--libs", "gtk+-3.0"], capture_output=True,
                                 text=True, check=True).stdout)
subprocess.run([cc, *objects, *link_flags, *gtk, "-lm", "-pthread", "-o", str(suite)], check=True)
run_and_check(suite)

PY
