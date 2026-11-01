# OBD Test

Android application for testing OBD interfaces and comparing command-response times, with a particular focus on communication with the Lotus EBC430 ABS ECU over K-Line/KWP2000.

The project is separate from [Lotus ABS Bleeding App](https://github.com/fabcor81-sys/Lotus-ABS-Bleeding-app), which focuses on ABS diagnostics and bleeding procedures.

## Purpose

OBD Test helps compare adapters such as Bluetooth ELM327-compatible devices and USB KKL interfaces. It is intended to distinguish adapter command handling from actual ECU response times and make performance comparisons repeatable.

## Benchmarking

The project includes Bluetooth and USB communication, benchmark profiles for adapter AT commands, generic OBD requests and Lotus ABS communication, response-time statistics including P50/P95/P99, and a communication log.

Results depend on the adapter, protocol, ECU, command and test configuration. A measured response time should not be treated as a universal limit of Bluetooth or K-Line communication. Receiving a reply also does not necessarily mean that the ECU accepted the requested service: negative responses must be interpreted separately.

## Development

The app is written in Kotlin using Jetpack Compose. Open the project in Android Studio and use the included Gradle wrapper to build it:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

## Related Project

- [Lotus ABS Bleeding App](https://github.com/fabcor81-sys/Lotus-ABS-Bleeding-app)
