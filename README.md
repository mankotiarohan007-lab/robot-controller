# CampusBot X

CampusBot X is a small Android remote-control app for an ESP32-based robot. The ESP32 creates a local Wi-Fi access point, serves a deliberately limited HTTP API, drives the motors through an L298N, and can autonomously avoid obstacles using an HC-SR04 mounted on a servo.

## Project contents and requirements

- `app/`: native Java Android app.
- `firmware/RobotController/RobotController.ino`: Arduino sketch for ESP32 DevKit V1.
- Android Studio, JDK 17, and Android SDK Platform 35 with Build Tools 35.0.0.
- An ESP32 Arduino core and the `ESP32Servo` library for firmware compilation.

The Android project uses Android Gradle Plugin 8.6.1 / Gradle 8.7 and targets Android 15 (API 35). No AndroidX or third-party app libraries are required.

## Safety — read before powering the robot

**Lithium batteries can cause fire, burns, or explosion when damaged or used incorrectly.** Use a protected battery pack and a charger specifically made for that pack's chemistry, cell count, and voltage. Never short-circuit, reverse the polarity, overcharge, over-discharge, puncture, crush, disassemble, or expose cells to heat, flame, or water. Never solder directly to a cell. Do not mix cells of different types, ages, or charge levels. Inspect packs and wiring before each use; do not use, charge, or transport a swollen, dented, leaking, hot, or otherwise damaged battery. Charge only while attended, on a nonflammable surface, away from combustible materials, and disconnect the battery before changing wiring. Use an appropriately rated fuse and insulated connectors. Recycle damaged or spent batteries through an approved battery collection service; do not put lithium batteries in household trash.

Raise the robot so its wheels are clear of the floor during first tests. Keep hands, hair, cables, and loose objects away from wheels and linkages. Test the emergency stop before fitting the robot with a battery-powered drive supply. The emergency stop is a software command, not a physical safety disconnect; provide a reachable battery disconnect for bench and field use. The HC-SR04/servo obstacle avoidance is not a safety-rated collision-prevention system.

The ESP32 access point and HTTP endpoints are unauthenticated and unencrypted. Use them only on the robot's local network; never bridge or expose them to the internet or an untrusted network.

## Wiring (ESP32 DevKit V1)

Disconnect all batteries before wiring. Use a common ground between the ESP32, motor driver, sensors, and any separate servo supply.

| Module signal | ESP32 GPIO | Wiring notes |
|---|---:|---|
| L298N left motor IN1 / IN2 | 27 / 26 | L298N ENA jumper fitted (or enable tied high); connect left motor to OUT1/OUT2 |
| L298N right motor IN3 / IN4 | 25 / 33 | L298N ENB jumper fitted (or enable tied high); connect right motor to OUT3/OUT4 |
| HC-SR04 TRIG / ECHO | 18 / 19 | TRIG is 3.3 V logic. **Do not connect the sensor's 5 V ECHO directly to the ESP32.** Use a divider: ECHO to GPIO19 through 1 kΩ, and GPIO19 to GND through 2 kΩ (about 3.3 V from a 5 V echo). |
| Buzzer signal | 23 | Use a suitable transistor driver for a buzzer that draws more than a GPIO can safely supply. |
| Status / autopilot LEDs | 16 / 17 | Put a 220–1,000 Ω resistor in series with each LED; LED cathodes to GND. |
| Servo signal | 13 | Power the servo from a suitably rated regulated 5 V supply, **not** the ESP32 3.3 V pin; join supply ground to ESP32 ground. Mount the HC-SR04 so it scans with the servo. |

Power the motors from a supply appropriate for the motors through the L298N motor supply input. Do not power motors from the ESP32. Check the L298N board's regulator/jumper arrangement before connecting logic power. Keep motor supply noise and stall current within the ratings of the driver, battery, wires, and connectors. Do not connect two supplies in a way that back-feeds either supply.

## Firmware setup

1. Open `firmware/RobotController/RobotController.ino` in Arduino IDE or an ESP32-compatible Arduino build environment.
2. Select an ESP32 Dev Module / DevKit V1 board and install the **ESP32Servo** library (Library Manager: “ESP32Servo”).
3. Compile and upload, then open Serial Monitor at 115200 baud.
4. The robot advertises Wi-Fi **CampusBot** with password **campusbot** and serves HTTP on `192.168.4.1`.

The firmware configures the listed GPIOs directly. Confirm that no attached board peripheral conflicts with those pins on your specific DevKit.

## Android setup and build

1. Open the repository root in Android Studio.
2. Set the Gradle JDK to JDK 17 and install Android SDK Platform 35 / Build Tools 35.0.0 if prompted.
3. Build and run the `app` configuration on an Android device or emulator. On first connection, join the **CampusBot** Wi-Fi network using password `campusbot`. Android may report that this Wi-Fi has no internet; stay connected to it.
4. In the app, set the robot base URL (default `http://192.168.4.1`) and the ESP32-CAM stream URL. Tap **Save addresses**, then **Test connection**. The camera URL is independent; set it to the address/path your camera actually serves (for example, `http://192.168.4.1:81/stream` if the camera is on the same network).
5. Press and hold a direction to move; release to stop. **Stop** disables motion/autopilot, and **Emergency stop** latches the motors off until **Reset emergency** is tapped. Use the separate autopilot start/stop controls.

The app uses cleartext HTTP only on the local network and requests Internet/network-state permissions for HTTP and connection monitoring. It sends stop when the app leaves the foreground. Keep the app open while autopilot is running: the app renews an autopilot control lease, and firmware stops if that lease expires.

## HTTP API used by the app

All endpoints use the robot base URL, default `http://192.168.4.1`. Responses are JSON; successful requests return HTTP 200. The emergency latch rejects motion/autopilot start with HTTP 423 until reset. Unknown endpoints and invalid directions return an error and stop motion. No other app endpoints are used.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/status` | Read JSON live status including distance, motion, autopilot, and emergency latch. |
| `GET` | `/move?dir=forward` | Begin/refresh a manual movement lease. `dir` must be `forward`, `backward`, `left`, or `right`. The app repeats this while the button is held. |
| `POST` | `/stop` | Stop the motors and disable autopilot. |
| `POST` | `/emergency-stop` | Stop immediately and latch the emergency stop. |
| `POST` | `/emergency-reset` | Clear the emergency latch without starting motion. |
| `POST` | `/autopilot/start` | Enable obstacle-avoidance mode and start/refresh its lease. The app renews it while active. |
| `POST` | `/autopilot/stop` | Stop and disable obstacle avoidance. |

Example `/status` response:

```json
{"ok":true,"distanceCm":42,"moving":"forward","autopilot":false,"emergencyStop":false,"mode":"manual"}
```

The movement lease expires after about 1.2 seconds without a valid movement request; the autopilot lease expires after about 3 seconds without a start/renewal request. These watchdogs are additional safeguards, not substitutes for the physical disconnect.

## Troubleshooting

- **Test connection fails:** verify the phone is connected to `CampusBot`, not a mobile-data network; keep the phone on the Wi-Fi even if Android says it has no internet. Check the base URL, power, serial output, and that the ESP32 has booted.
- **Robot won't move:** check L298N enable jumpers, motor/logic supplies and common ground, GPIO wiring, emergency latch, motor polarity, and battery charge. Reset the emergency latch explicitly after an emergency stop.
- **Motors move in the wrong direction:** swap that motor's output wires or adjust the input polarity in the sketch.
- **Distance is always zero or unreliable:** verify TRIG/ECHO wiring, common ground, sensor orientation, and the ECHO voltage divider. Keep the sensor clear of obstructions and avoid soft or angled targets.
- **Autopilot turns the wrong way:** servo scan direction depends on how the servo and sensor are mounted; adjust the left/right angles in the sketch. Test with wheels raised and keep the path clear.
- **Camera is blank:** the camera stream is a separate device/service and may need its own IP and path. Configure the ESP32-CAM stream URL in the app; verify it opens on the phone's local Wi-Fi and use a stream endpoint supported by Android WebView. The app's **Launch camera** button opens that URL externally.
- **Android Studio cannot sync/build:** set Gradle JDK to 17 and install SDK Platform 35 and Build Tools 35.0.0. Check Gradle's reported dependency or SDK error before changing versions.
- **Board does not compile:** select an ESP32 board/core, install `ESP32Servo`, and verify the selected board package supports the APIs used by the sketch.
