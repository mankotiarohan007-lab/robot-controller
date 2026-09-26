#include <ESP32Servo.h>
#include <WebServer.h>
#include <WiFi.h>

namespace {
constexpr char AP_SSID[] = "CampusBot";
constexpr char AP_PASSWORD[] = "campusbot";
constexpr uint8_t LEFT_IN1 = 27;
constexpr uint8_t LEFT_IN2 = 26;
constexpr uint8_t RIGHT_IN1 = 25;
constexpr uint8_t RIGHT_IN2 = 33;
constexpr uint8_t SONAR_TRIG = 18;
constexpr uint8_t SONAR_ECHO = 19;
constexpr uint8_t BUZZER_PIN = 23;
constexpr uint8_t STATUS_LED = 16;
constexpr uint8_t AUTOPILOT_LED = 17;
constexpr uint8_t SERVO_PIN = 13;
constexpr uint32_t MANUAL_LEASE_MS = 1200;
constexpr uint32_t AUTOPILOT_LEASE_MS = 3200;
constexpr uint32_t SENSOR_INTERVAL_MS = 120;
constexpr uint32_t SERVO_SETTLE_MS = 350;
constexpr uint32_t TURN_DURATION_MS = 480;
constexpr uint8_t OBSTACLE_DISTANCE_CM = 25;
constexpr uint32_t SONAR_TIMEOUT_US = 25000;
constexpr uint8_t SERVO_CENTER = 90;
constexpr uint8_t SERVO_LEFT = 150;
constexpr uint8_t SERVO_RIGHT = 30;

WebServer server(80);
Servo scanner;

enum class Motion : uint8_t { STOPPED, FORWARD, BACKWARD, LEFT, RIGHT };
enum class AutoState : uint8_t { DRIVE, SCAN_LEFT, SCAN_RIGHT, TURN };

Motion motion = Motion::STOPPED;
AutoState autoState = AutoState::DRIVE;
bool autopilot = false;
bool emergencyStopLatched = false;
int distanceCm = -1;
int leftDistanceCm = -1;
int rightDistanceCm = -1;
uint32_t lastControlAt = 0;
uint32_t lastSensorAt = 0;
uint32_t autoStateStartedAt = 0;
uint32_t buzzerOffAt = 0;
bool turnLeftNext = true;

const char *motionName() {
  switch (motion) {
    case Motion::FORWARD: return "forward";
    case Motion::BACKWARD: return "backward";
    case Motion::LEFT: return "left";
    case Motion::RIGHT: return "right";
    default: return "stopped";
  }
}

void setMotion(Motion next) {
  motion = next;
  switch (next) {
    case Motion::FORWARD:
      digitalWrite(LEFT_IN1, HIGH);
      digitalWrite(LEFT_IN2, LOW);
      digitalWrite(RIGHT_IN1, HIGH);
      digitalWrite(RIGHT_IN2, LOW);
      break;
    case Motion::BACKWARD:
      digitalWrite(LEFT_IN1, LOW);
      digitalWrite(LEFT_IN2, HIGH);
      digitalWrite(RIGHT_IN1, LOW);
      digitalWrite(RIGHT_IN2, HIGH);
      break;
    case Motion::LEFT:
      digitalWrite(LEFT_IN1, LOW);
      digitalWrite(LEFT_IN2, HIGH);
      digitalWrite(RIGHT_IN1, HIGH);
      digitalWrite(RIGHT_IN2, LOW);
      break;
    case Motion::RIGHT:
      digitalWrite(LEFT_IN1, HIGH);
      digitalWrite(LEFT_IN2, LOW);
      digitalWrite(RIGHT_IN1, LOW);
      digitalWrite(RIGHT_IN2, HIGH);
      break;
    default:
      digitalWrite(LEFT_IN1, LOW);
      digitalWrite(LEFT_IN2, LOW);
      digitalWrite(RIGHT_IN1, LOW);
      digitalWrite(RIGHT_IN2, LOW);
      break;
  }
}

void sendJson(int statusCode, const String &json) {
  server.sendHeader("Cache-Control", "no-store");
  server.send(statusCode, "application/json", json);
}

void stopRobot() {
  autopilot = false;
  autoState = AutoState::DRIVE;
  setMotion(Motion::STOPPED);
  scanner.write(SERVO_CENTER);
  digitalWrite(AUTOPILOT_LED, LOW);
}

void rejectCommand(const char *message, int statusCode = 400) {
  stopRobot();
  String body = "{\"ok\":false,\"error\":\"";
  body += message;
  body += "\"}";
  sendJson(statusCode, body);
}

int readDistanceCm() {
  digitalWrite(SONAR_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(SONAR_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(SONAR_TRIG, LOW);
  const unsigned long duration = pulseIn(SONAR_ECHO, HIGH, SONAR_TIMEOUT_US);
  if (duration == 0) {
    return -1;
  }
  return static_cast<int>(duration / 58UL);
}

void handleStatus() {
  String json = "{\"ok\":true,\"distanceCm\":";
  json += (distanceCm < 0) ? "null" : String(distanceCm);
  json += ",\"moving\":\"";
  json += motionName();
  json += "\",\"autopilot\":";
  json += autopilot ? "true" : "false";
  json += ",\"emergencyStop\":";
  json += emergencyStopLatched ? "true" : "false";
  json += ",\"mode\":\"";
  json += emergencyStopLatched ? "emergency-stop" : (autopilot ? "autopilot" : "manual");
  json += "\"}";
  sendJson(200, json);
}

void handleMove() {
  if (emergencyStopLatched) {
    sendJson(423, "{\"ok\":false,\"error\":\"Emergency stop is latched.\"}");
    return;
  }
  if (server.args() != 1 || !server.hasArg("dir")) {
    rejectCommand("Expected exactly one dir parameter.");
    return;
  }

  const String direction = server.arg("dir");
  Motion requested = Motion::STOPPED;
  if (direction == "forward") requested = Motion::FORWARD;
  else if (direction == "backward") requested = Motion::BACKWARD;
  else if (direction == "left") requested = Motion::LEFT;
  else if (direction == "right") requested = Motion::RIGHT;
  else {
    rejectCommand("Invalid direction.");
    return;
  }

  autopilot = false;
  autoState = AutoState::DRIVE;
  lastControlAt = millis();
  digitalWrite(AUTOPILOT_LED, LOW);
  setMotion(requested);
  sendJson(200, "{\"ok\":true}");
}

void handleStop() {
  stopRobot();
  lastControlAt = millis();
  sendJson(200, "{\"ok\":true,\"moving\":\"stopped\"}");
}

void handleEmergencyStop() {
  stopRobot();
  emergencyStopLatched = true;
  digitalWrite(BUZZER_PIN, HIGH);
  buzzerOffAt = millis() + 120;
  lastControlAt = millis();
  sendJson(200, "{\"ok\":true,\"emergencyStop\":true}");
}

void handleEmergencyReset() {
  emergencyStopLatched = false;
  digitalWrite(BUZZER_PIN, LOW);
  buzzerOffAt = 0;
  setMotion(Motion::STOPPED);
  sendJson(200, "{\"ok\":true,\"emergencyStop\":false}");
}

void handleAutopilotStart() {
  if (emergencyStopLatched) {
    sendJson(423, "{\"ok\":false,\"error\":\"Emergency stop is latched.\"}");
    return;
  }
  lastControlAt = millis();
  if (!autopilot) {
    autopilot = true;
    autoState = AutoState::DRIVE;
    scanner.write(SERVO_CENTER);
    lastSensorAt = millis() - SENSOR_INTERVAL_MS;
    setMotion(Motion::STOPPED);
  }
  digitalWrite(AUTOPILOT_LED, HIGH);
  sendJson(200, "{\"ok\":true,\"autopilot\":true}");
}

void handleAutopilotStop() {
  stopRobot();
  lastControlAt = millis();
  sendJson(200, "{\"ok\":true,\"autopilot\":false,\"moving\":\"stopped\"}");
}

void handleUnknownRequest() {
  rejectCommand("Unknown endpoint or HTTP method.", 404);
}

void disableAutopilotOnSensorFailure() {
  stopRobot();
}

void serviceAutopilot(uint32_t now) {
  if (static_cast<uint32_t>(now - lastControlAt) > AUTOPILOT_LEASE_MS) {
    stopRobot();
    return;
  }

  if (autoState == AutoState::DRIVE) {
    if (static_cast<uint32_t>(now - lastSensorAt) < SENSOR_INTERVAL_MS) {
      return;
    }
    lastSensorAt = now;
    distanceCm = readDistanceCm();
    if (distanceCm < 0) {
      disableAutopilotOnSensorFailure();
      return;
    }
    if (distanceCm <= OBSTACLE_DISTANCE_CM) {
      setMotion(Motion::STOPPED);
      scanner.write(SERVO_LEFT);
      autoState = AutoState::SCAN_LEFT;
      autoStateStartedAt = now;
      return;
    }
    setMotion(Motion::FORWARD);
    return;
  }

  if (static_cast<uint32_t>(now - autoStateStartedAt) < SERVO_SETTLE_MS) {
    return;
  }
  if (autoState == AutoState::SCAN_LEFT) {
    leftDistanceCm = readDistanceCm();
    scanner.write(SERVO_RIGHT);
    autoState = AutoState::SCAN_RIGHT;
    autoStateStartedAt = millis();
    return;
  }
  if (autoState == AutoState::SCAN_RIGHT) {
    rightDistanceCm = readDistanceCm();
    distanceCm = readDistanceCm();
    if (leftDistanceCm < 0 || rightDistanceCm < 0 || distanceCm < 0) {
      disableAutopilotOnSensorFailure();
      return;
    }
    turnLeftNext = leftDistanceCm == rightDistanceCm ? !turnLeftNext
                                                     : leftDistanceCm > rightDistanceCm;
    setMotion(turnLeftNext ? Motion::LEFT : Motion::RIGHT);
    autoState = AutoState::TURN;
    autoStateStartedAt = millis();
    return;
  }
  if (autoState == AutoState::TURN
      && static_cast<uint32_t>(now - autoStateStartedAt) >= TURN_DURATION_MS) {
    setMotion(Motion::STOPPED);
    scanner.write(SERVO_CENTER);
    autoState = AutoState::DRIVE;
    lastSensorAt = millis() - SENSOR_INTERVAL_MS;
  }
}

void serviceManualWatchdog(uint32_t now) {
  if (motion != Motion::STOPPED
      && static_cast<uint32_t>(now - lastControlAt) > MANUAL_LEASE_MS) {
    setMotion(Motion::STOPPED);
  }
}

void setupServer() {
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/move", HTTP_GET, handleMove);
  server.on("/stop", HTTP_POST, handleStop);
  server.on("/emergency-stop", HTTP_POST, handleEmergencyStop);
  server.on("/emergency-reset", HTTP_POST, handleEmergencyReset);
  server.on("/autopilot/start", HTTP_POST, handleAutopilotStart);
  server.on("/autopilot/stop", HTTP_POST, handleAutopilotStop);
  server.onNotFound(handleUnknownRequest);
  server.begin();
}
}  // namespace

void setup() {
  Serial.begin(115200);
  pinMode(LEFT_IN1, OUTPUT);
  pinMode(LEFT_IN2, OUTPUT);
  pinMode(RIGHT_IN1, OUTPUT);
  pinMode(RIGHT_IN2, OUTPUT);
  pinMode(SONAR_TRIG, OUTPUT);
  pinMode(SONAR_ECHO, INPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(STATUS_LED, OUTPUT);
  pinMode(AUTOPILOT_LED, OUTPUT);
  digitalWrite(SONAR_TRIG, LOW);
  digitalWrite(BUZZER_PIN, LOW);
  digitalWrite(STATUS_LED, LOW);
  digitalWrite(AUTOPILOT_LED, LOW);

  setMotion(Motion::STOPPED);
  scanner.setPeriodHertz(50);
  scanner.attach(SERVO_PIN, 500, 2400);
  scanner.write(SERVO_CENTER);

  WiFi.mode(WIFI_AP);
  const IPAddress robotIp(192, 168, 4, 1);
  WiFi.softAPConfig(robotIp, robotIp, IPAddress(255, 255, 255, 0));
  if (!WiFi.softAP(AP_SSID, AP_PASSWORD)) {
    Serial.println("Failed to start CampusBot access point.");
    return;
  }
  digitalWrite(STATUS_LED, HIGH);
  Serial.print("CampusBot AP ready at ");
  Serial.println(WiFi.softAPIP());
  setupServer();
}

void loop() {
  server.handleClient();
  const uint32_t now = millis();
  if (buzzerOffAt != 0 && static_cast<int32_t>(now - buzzerOffAt) >= 0) {
    digitalWrite(BUZZER_PIN, LOW);
    buzzerOffAt = 0;
  }
  if (emergencyStopLatched) {
    setMotion(Motion::STOPPED);
    return;
  }
  if (autopilot) {
    serviceAutopilot(now);
    return;
  }
  serviceManualWatchdog(now);
  if (static_cast<uint32_t>(now - lastSensorAt) >= SENSOR_INTERVAL_MS) {
    lastSensorAt = now;
    distanceCm = readDistanceCm();
  }
}
