# OBD Test — UX/UI, safety and navigation changes

## New navigation
- **Device**: select USB KKL/FTDI, ELM327 Classic or BLE adapter. USB K-Line works even when Bluetooth permissions are denied or Bluetooth is disabled.
- **Benchmark**: choose valid profile and sample count (25, 50, 100, 250). Only compatible profiles are displayed; configuration cannot change while a test is active.
- **Results**: large latency/success metrics, expandable per-command results, session-only comparison baseline and CSV export.
- Collapsible **Console** is visible across all three pages.
- **Back** (in-app and Android) moves Results → Benchmark → Device while idle.
- During any running/connecting/initializing test, tab switching and Android Back are blocked; **STOP BENCHMARK** remains visible.

## Commands and profiles
| Profile | Benchmark commands |
| --- | --- |
| AT-only | ATI |
| Generic OBD2 | ATI, 0100 |
| Lotus EBC430 ABS (Bluetooth) | ATI, 1A80, 2101, 2102 |
| Lotus EBC430 ABS + **explicit optional Test 6** | Above, then **one** 30020700 Pump OFF |
| USB K-Line diagnostic | One 1A80 |
| USB K-Line Lotus ABS | 1A80, 2101 |

ABS Pump OFF is never part of Generic OBD2 or AT-only. For the Lotus Bluetooth benchmark, it is a one-shot opt-in with confirmation and the authorization resets when Start is pressed. Do not use actuator commands while driving or braking. USB diagnostics do not send actuator commands.

## Comparison validity
Only show relative P50/throughput changes when both sessions are complete, have successful replies, use the same profile, transport, measured command, sample count, optional actuator test flag, and actual per-test command list. Otherwise show only raw metrics with a warning. Interrupted benchmarks cannot be saved as baselines.

## Cases to validate
1. Deny Bluetooth permission, connect USB OTG KKL, confirm Device → Benchmark is usable.
2. Disconnect USB during setup and during benchmark: verify error/Stop and no crash.
3. Permission request for USB: press STOP before granting, then grant. **Test must not restart**.
4. Switch Classic/BLE/USB adapters and confirm incompatible profiles are corrected.
5. Start an AT-only benchmark and verify there is exactly one ATI series.
6. Generic OBD2 must not issue Lotus 1A/21 commands or 30 actuator commands.
7. Lotus ABS without opt-in runs no 30020700. With explicit opt-in, 30020700 occurs once at the end; opt-in resets on next run.
8. During an active benchmark, tapping Back or tabs cannot hide STOP.
9. Benchmark with zero responses and interrupted sessions must not enable Save Baseline.
10. Compare Norauto/vLinker under identical settings and confirm P50 deltas. Change settings and confirm “not directly comparable”.
11. Export CSV and verify Android sharesheet works.
12. Rotate device, test with large system font, ensure primary controls remain accessible.
13. Inspect actual ECU command acknowledgements, timeout classification, stop/cancel race conditions and raw CSV outside UI before trusting benchmark metrics.

## Tests added
- Pure JUnit: BenchmarkUiPolicyTest, BenchmarkTestPlanTest.
- Android Compose instrumented smoke test: BenchmarkNavigationTest (requires a device/emulator).
- Unit test and APK build through a PR-only GitHub Actions workflow.

**Hardware behavior remains unverified** until tested with EBC430 and adapters on a stationary vehicle.
