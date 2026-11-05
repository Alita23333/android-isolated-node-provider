# Cancellable startup follow-up — 2026-10-18

Product facade now supports a cancellation supplier; exact product copy is compiled into probe.
Set PROBE_STARTUP_CANCEL=1 to cancel after real service start announcement and before a test-only
3000ms delayed Node entry. Driver checks actual Binder death, readyCount0 and common device elapsed
clock age below3000ms. Default mode retains normal JSON-RPC/permission/running-cancel checks.
Evidence provider-startup1 final pair1792298603781943800/1792298608451768900 passes on Android17.
Product Application graph guard is not exercised by the standalone service; source/Robolectric
proof is recorded separately. Product candidate remains uninstalled, imported admission disabled.
See ../../docs/handoff/provider-startup1-android17-20261018.md.

# Process facade follow-up — 2026-10-18

The experiment now compiles the exact product execution/IsolatedProviderProcess.java via the
preparation script into build/product-source. It does not maintain a second Process facade.
Set PROBE_EVIDENCE_DIR to a new evidence directory for follow-up runs; earlier frozen evidence
is historical. The device driver pins source/driver hashes and requires matching copied source.

Final run1792298042836113300: facade waitFor/death/cancel status PASS, cancel17ms observed,
private/network/digest checks retained. Product remains C51. Full80 JVM0fail/errors/skips,
build and fresh lint0errors/28warnings. Product candidate7D1C449F is uninstalled. BinderNodeEndpoint
is experimental, not a product registration or model-accessible service. See
../../docs/handoff/provider-process1-android17-20261018.md for precise limits and next steps.

# Standalone provider isolation probe

This is an experiment APK (`dev.alita.providerprobe`), not part of `:app`, not a new Agent Core,
Gateway or local-provider protocol. It has no production credentials/configuration, no root
integration and no model-facing tool. Production remains last accepted C51DB531….

The design tests Android's documented `isolatedProcess` service, which has no permissions of
its own: [Android service manifest reference](https://developer.android.com/guide/topics/manifest/service-element#isolated).
Only read-only runtime-library descriptors and stdio pipes cross Binder. Loading existing exact
ELF bytes by descriptor uses the public NDK
[android_dlopen_ext / ANDROID_DLEXT_USE_LIBRARY_FD API](https://android.googlesource.com/platform/bionic/+/main/libc/include/android/dlext.h).
Neither FD transport nor the documentation alone proves successful execution or containment;
the actual target results below are required.

## Reproduction

Resolve Python/JDK17/Android Clang/ADB using C:\DevTools. Set ANDROID_CLANG and run
`tools/phase9/prepare-provider-isolation-probe.py`, then the existing wrapper with
`-p experiments/provider-isolation assembleDebug`. The preparation script verifies each byte
against the accepted runtime payload SHA manifest, packages ten existing libraries as native .so
files and builds a first-party JNI shim with 16KB LOAD alignment. No new third-party dependency
or runtime binary patch is introduced. Generated runtime copies live only under build/payload.
Existing runtime licensing/source metadata remains applicable; this experiment does not grant
additional distribution rights.

With ANDROID_ADB resolved, run `tools/phase9/provider-isolation-device-probe.py`. It hard-checks
DEVICE_ANDROID17 / Android17 /MODEL_ANDROID17 and the installed production APK C51DB531…. It installs ONLY the
separate probe, runs a fixed synthetic fixture and stops ONLY that probe afterward. New app
installation may need explicit phone confirmation; INSTALL_FAILED_USER_RESTRICTED is retained,
never bypassed with root. No system security/SELinux/file-mode changes.

The shim loads exact libraries in dependency order, calls the measured `_ZN4node5StartEiPPc`
export in Node24.18.0, and gives Node stdio pipes. This entry ABI is tied to the fixed runtime
hash/version and is not claimed stable across Node upgrades. A missing export/load failure is
fatal; no fallback to owner UID is permitted. Library filenames ending in .so can be extracted
normally; others have APK-compatible wrapper names with original bytes/SONAME retained. sqlite3
has no SONAME and must retain its actual basename `libsqlite3.so` for dependency resolution.

## Measured Android17 result — 2026-10-18

Successful run1792297091188177800, probe APK
BA05BD39BE3C93062796251338C265AD62E895370011FC3ADE7A7D66EC3C6BC3:

- Owner UID10370, isolated Node UID99229/PID20079; Node v24.18.0.
- JSON-RPC2 initialize/version1.0 and a tools/call response decoded and checked; SHA-256 of
  synthetic input matches the host. This Java client is not the production Kotlin transport.
- Reading and writing the existing owner-private sentinel path both fail with ENOENT in the
  isolated process's view; owner confirms actual original bytes remain unchanged. This is a
  synthetic fixture, not a read attempt against actual product credentials.
- Owner TCP connection to its listening loopback socket succeeds; isolated Node connection to
  that same live socket fails with EACCES. No external network request is made.
- During a synthetic long request, cancellation kills the isolated service; Binder death observed
  in15ms. One observation, not a latency SLA or proof of all race/descendant cases.
- Main product hash remains C51DB531 before/after. Probe remains installed but stopped.

Private app-data library FDs failed executable mapping in an earlier run. The APK-native-file
route works without changing permissions or bytes. Two install denials, the first Java API build
failure, stale-result collection error and missing-sqlite-basename failure remain in evidence.
Unique run IDs now prevent a previous result being counted as a current run.

## Before production integration

Implement a Process/pipe adapter behind the existing stdio provider factory, strict hash-bound
admission, non-executing manifest discovery, host-controlled bounded artifact conversion, complete
cancel/error/death/resource budgets and exact existing Gateway operation completion. Test real
model-issued calls and session/update/restart lifecycles. Do not call this prototype full generic
provider acceptance, full sandbox verification, Python/root/Shizuku support or Android15 compatibility.
Do not copy it into production without integrating the existing validation and Gateway boundary.
