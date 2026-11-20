#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#include <ESPmDNS.h>
#include <WiFi.h>
#include <WebSocketsServer.h>
#include <esp_camera.h>
#include <driver/i2s.h>

#include <algorithm>
#include <cstring>
#include <cmath>

#if __has_include("secrets.h")
#include "secrets.h"
#endif

#ifndef DOORBELL_WIFI_SSID
#define DOORBELL_WIFI_SSID ""
#endif

#ifndef DOORBELL_WIFI_PASSWORD
#define DOORBELL_WIFI_PASSWORD ""
#endif

#ifndef DOORBELL_ACCESS_TOKEN
#define DOORBELL_ACCESS_TOKEN ""
#endif

namespace {

constexpr gpio_num_t kMicDataPin = GPIO_NUM_14;
constexpr gpio_num_t kI2sBitClockPin = GPIO_NUM_1;
constexpr gpio_num_t kI2sWordSelectPin = GPIO_NUM_2;
constexpr gpio_num_t kDacDataPin = GPIO_NUM_21;
constexpr uint8_t kTouchPin = 47;
constexpr uint8_t kLedRingPin = 48;
constexpr uint8_t kLedRingCount = 8;
constexpr i2s_port_t kI2sPort = I2S_NUM_0;

constexpr int kCameraXclkPin = 15;
constexpr int kCameraSccbSdaPin = 4;
constexpr int kCameraSccbSclPin = 5;
constexpr int kCameraY2Pin = 11;
constexpr int kCameraY3Pin = 9;
constexpr int kCameraY4Pin = 8;
constexpr int kCameraY5Pin = 10;
constexpr int kCameraY6Pin = 12;
constexpr int kCameraY7Pin = 18;
constexpr int kCameraY8Pin = 17;
constexpr int kCameraY9Pin = 16;
constexpr int kCameraVsyncPin = 6;
constexpr int kCameraHrefPin = 7;
constexpr int kCameraPclkPin = 13;

constexpr uint32_t kSampleRate = 16000;
constexpr size_t kSamplesPerFrame = 320;
constexpr size_t kPcmFrameBytes = kSamplesPerFrame * sizeof(int16_t);
constexpr uint32_t kTouchDebounceMs = 50;
constexpr uint16_t kCameraPort = 82;
constexpr uint8_t kLedBrightness = 36;
constexpr uint32_t kLedAnimationIntervalMs = 100;
constexpr uint32_t kStandbySparkMinIntervalMs = 2500;
constexpr uint32_t kStandbySparkMaxIntervalMs = 5500;
constexpr uint32_t kStandbySparkDurationMs = 180;
constexpr uint8_t kStandbySparkValue = 64;
constexpr uint8_t kSpeakerGainPercent = 20;
constexpr int32_t kSpeakerGainQ16 =
    65536 * kSpeakerGainPercent / 100;
constexpr uint32_t kDacDiagnosticToneHz = 440;
constexpr int16_t kDacDiagnosticToneAmplitude = 8192;
constexpr uint32_t kDacDiagnosticToneDurationMs = 2000;

#ifndef DOORBELL_DAC_TEST_TONE
#define DOORBELL_DAC_TEST_TONE 0
#endif

struct AudioFrame {
  int16_t samples[kSamplesPerFrame];
};

WebSocketsServer webSocket(81);
WiFiServer cameraServer(kCameraPort);
Adafruit_NeoPixel ledRing(kLedRingCount, kLedRingPin, NEO_GRB + NEO_KHZ800);
QueueHandle_t playbackQueue = nullptr;

enum class LedMode {
  BootRainbow,
  StandbySparkles,
  Ringing,
  Connected,
  DirectTalk,
  HangupFlash,
};

LedMode ledMode = LedMode::BootRainbow;
int8_t activeClient = -1;
int8_t pendingClient = -1;
bool callRinging = false;
bool callAnswered = false;
bool directTalkActive = false;
bool hardwareReady = false;
bool cameraReady = false;
bool mdnsStarted = false;
bool touchWasHigh = false;
bool touchStableState = false;
uint32_t touchChangedAt = 0;
uint32_t lastWifiRetryAt = 0;
uint32_t lastMdnsAttemptAt = 0;
uint32_t pendingAuthStartedAt = 0;
uint32_t lastLedAnimationAt = 0;
uint32_t nextStandbySparkAt = 0;
uint32_t standbySparkOffAt = 0;
bool standbySparkActive = false;
uint8_t spinnerPosition = 0;
uint8_t hangupFlashStep = 0;
uint32_t receivedPcmFramesThisSecond = 0;
uint32_t rejectedPcmFramesThisSecond = 0;
uint16_t receivedPcmPeakThisSecond = 0;
uint32_t lastPcmDiagnosticAt = 0;

void showRainbow() {
  for (uint8_t i = 0; i < kLedRingCount; ++i) {
    const uint16_t hue =
        static_cast<uint16_t>((static_cast<uint32_t>(i) * 65535) /
                              kLedRingCount);
    ledRing.setPixelColor(i, ledRing.gamma32(ledRing.ColorHSV(hue)));
  }
  ledRing.show();
}

void setLedMode(LedMode mode) {
  ledMode = mode;
  lastLedAnimationAt = millis();
  spinnerPosition = 0;
  hangupFlashStep = 0;
  standbySparkActive = false;

  switch (ledMode) {
    case LedMode::BootRainbow:
      showRainbow();
      break;
    case LedMode::StandbySparkles:
      ledRing.clear();
      ledRing.show();
      nextStandbySparkAt =
          millis() + random(kStandbySparkMinIntervalMs,
                            kStandbySparkMaxIntervalMs + 1);
      break;
    case LedMode::Ringing:
      ledRing.clear();
      ledRing.show();
      break;
    case LedMode::Connected:
      ledRing.fill(ledRing.Color(0, 255, 0));
      ledRing.show();
      break;
    case LedMode::DirectTalk:
      ledRing.fill(ledRing.Color(0, 180, 255));
      ledRing.show();
      break;
    case LedMode::HangupFlash:
      ledRing.clear();
      ledRing.show();
      break;
  }
}

void updateLedAnimation() {
  const uint32_t now = millis();

  if (ledMode == LedMode::StandbySparkles) {
    if (standbySparkActive) {
      if (static_cast<int32_t>(now - standbySparkOffAt) >= 0) {
        ledRing.clear();
        ledRing.show();
        standbySparkActive = false;
        nextStandbySparkAt =
            now + random(kStandbySparkMinIntervalMs,
                         kStandbySparkMaxIntervalMs + 1);
      }
    } else if (static_cast<int32_t>(now - nextStandbySparkAt) >= 0) {
      const uint8_t pixel = random(kLedRingCount);
      const uint16_t hue = random(65536);
      ledRing.clear();
      ledRing.setPixelColor(
          pixel, ledRing.ColorHSV(hue, 255, kStandbySparkValue));
      ledRing.show();
      standbySparkActive = true;
      standbySparkOffAt = now + kStandbySparkDurationMs;
    }
    return;
  }

  if (now - lastLedAnimationAt < kLedAnimationIntervalMs) {
    return;
  }
  lastLedAnimationAt = now;

  if (ledMode == LedMode::Ringing) {
    ledRing.clear();
    for (uint8_t tail = 0; tail < 4; ++tail) {
      const uint8_t pixel =
          (spinnerPosition + kLedRingCount - tail) % kLedRingCount;
      const uint8_t blue = 255 - tail * 64;
      ledRing.setPixelColor(pixel, ledRing.Color(0, 0, blue));
    }
    ledRing.show();
    spinnerPosition = (spinnerPosition + 1) % kLedRingCount;
  } else if (ledMode == LedMode::HangupFlash) {
    if (hangupFlashStep >= 6) {
      setLedMode(LedMode::StandbySparkles);
      return;
    }
    if ((hangupFlashStep % 2) == 0) {
      ledRing.fill(ledRing.Color(255, 255, 255));
    } else {
      ledRing.clear();
    }
    ledRing.show();
    ++hangupFlashStep;
    if (hangupFlashStep >= 6) {
      setLedMode(LedMode::StandbySparkles);
    }
  }
}

bool initializeCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = kCameraY2Pin;
  config.pin_d1 = kCameraY3Pin;
  config.pin_d2 = kCameraY4Pin;
  config.pin_d3 = kCameraY5Pin;
  config.pin_d4 = kCameraY6Pin;
  config.pin_d5 = kCameraY7Pin;
  config.pin_d6 = kCameraY8Pin;
  config.pin_d7 = kCameraY9Pin;
  config.pin_xclk = kCameraXclkPin;
  config.pin_pclk = kCameraPclkPin;
  config.pin_vsync = kCameraVsyncPin;
  config.pin_href = kCameraHrefPin;
  config.pin_sccb_sda = kCameraSccbSdaPin;
  config.pin_sccb_scl = kCameraSccbSclPin;
  config.pin_pwdn = -1;
  config.pin_reset = -1;
  config.xclk_freq_hz = 14000000;
  config.pixel_format = PIXFORMAT_RGB565;
  config.frame_size = psramFound() ? FRAMESIZE_QVGA : FRAMESIZE_QQVGA;
  config.jpeg_quality = 12;
  config.fb_count = psramFound() ? 2 : 1;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;

  const esp_err_t result = esp_camera_init(&config);
  if (result != ESP_OK) {
    Serial.printf("Camera initialization failed: 0x%x\n", result);
    return false;
  }

  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor != nullptr) {
    sensor->set_vflip(sensor, 1);
    Serial.printf("Camera ready: PID 0x%04x, %s\n", sensor->id.PID,
                  psramFound() ? "QVGA with PSRAM" : "QQVGA without PSRAM");
  } else {
    Serial.println("Camera initialized; sensor information unavailable");
  }
  return true;
}

bool writeAll(WiFiClient &client, const uint8_t *data, size_t length) {
  size_t offset = 0;
  while (offset < length && client.connected()) {
    const size_t chunk = std::min(length - offset, static_cast<size_t>(1024));
    const size_t written = client.write(data + offset, chunk);
    if (written == 0) {
      return false;
    }
    offset += written;
  }
  return offset == length;
}

void handleCameraClient(WiFiClient &client) {
  client.setTimeout(2000);
  String request = client.readStringUntil('\n');
  request.trim();

  const String expectedPath =
      String("GET /stream?token=") + DOORBELL_ACCESS_TOKEN;
  const bool authorized =
      std::strlen(DOORBELL_ACCESS_TOKEN) >= 16 &&
      (request == expectedPath + " HTTP/1.1" ||
       request == expectedPath + " HTTP/1.0");

  while (client.connected()) {
    String header = client.readStringUntil('\n');
    if (header.length() == 0 || header == "\r") {
      break;
    }
  }

  if (!authorized) {
    client.print("HTTP/1.1 401 Unauthorized\r\n"
                 "Content-Length: 0\r\n"
                 "Connection: close\r\n\r\n");
    return;
  }
  if (!cameraReady) {
    client.print("HTTP/1.1 503 Service Unavailable\r\n"
                 "Content-Length: 0\r\n"
                 "Connection: close\r\n\r\n");
    return;
  }

  client.print("HTTP/1.1 200 OK\r\n"
               "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
               "Cache-Control: no-store\r\n"
               "Connection: close\r\n\r\n");

  while (client.connected()) {
    camera_fb_t *frame = esp_camera_fb_get();
    if (frame == nullptr) {
      Serial.println("Camera frame capture failed");
      vTaskDelay(pdMS_TO_TICKS(250));
      continue;
    }

    uint8_t *jpeg = nullptr;
    size_t jpegLength = 0;
    const bool encoded = frame2jpg(frame, 40, &jpeg, &jpegLength);
    esp_camera_fb_return(frame);
    if (!encoded || jpeg == nullptr || jpegLength == 0) {
      Serial.println("Camera JPEG encoding failed");
      free(jpeg);
      vTaskDelay(pdMS_TO_TICKS(250));
      continue;
    }

    client.printf("--frame\r\n"
                  "Content-Type: image/jpeg\r\n"
                  "Content-Length: %u\r\n\r\n",
                  static_cast<unsigned>(jpegLength));
    const bool sent = writeAll(client, jpeg, jpegLength);
    free(jpeg);
    if (!sent || !client.print("\r\n")) {
      break;
    }
    vTaskDelay(pdMS_TO_TICKS(250));
  }
}

void cameraServerTask(void *) {
  cameraServer.begin();
  cameraServer.setNoDelay(true);
  Serial.printf("Camera stream server listening on port %u\n", kCameraPort);

  for (;;) {
    WiFiClient client = cameraServer.available();
    if (client) {
      handleCameraClient(client);
      client.stop();
    } else {
      vTaskDelay(pdMS_TO_TICKS(50));
    }
  }
}

void runDacDiagnosticTone() {
#if DOORBELL_DAC_TEST_TONE
  Serial.println("DAC test: playing a quiet 440 Hz tone for 2 seconds");
  int32_t micSamples[kSamplesPerFrame];
  int32_t dacSamples[kSamplesPerFrame];
  uint32_t phase = 0;
  constexpr uint64_t phaseIncrement =
      (static_cast<uint64_t>(kDacDiagnosticToneHz) << 32) / kSampleRate;
  constexpr float kPhaseToRadians =
      6.28318530718f / 4294967296.0f;
  const uint32_t stopAt = millis() + kDacDiagnosticToneDurationMs;

  while (static_cast<int32_t>(stopAt - millis()) > 0) {
    size_t bytesRead = 0;
    const esp_err_t readResult =
        i2s_read(kI2sPort, micSamples, sizeof(micSamples), &bytesRead,
                 pdMS_TO_TICKS(40));
    if (readResult != ESP_OK) {
      Serial.printf("DAC test I2S read failed: %d\n", readResult);
      break;
    }

    for (size_t i = 0; i < kSamplesPerFrame; ++i) {
      const float radians = static_cast<float>(phase) * kPhaseToRadians;
      const int16_t sample = static_cast<int16_t>(
          std::sin(radians) * kDacDiagnosticToneAmplitude);
      dacSamples[i] = static_cast<int32_t>(sample) * kSpeakerGainQ16;
      phase += static_cast<uint32_t>(phaseIncrement);
    }

    size_t bytesWritten = 0;
    const esp_err_t writeResult =
        i2s_write(kI2sPort, dacSamples, sizeof(dacSamples), &bytesWritten,
                  pdMS_TO_TICKS(40));
    if (writeResult != ESP_OK || bytesWritten != sizeof(dacSamples)) {
      Serial.printf("DAC test I2S write failed: %d, wrote %u bytes\n",
                    writeResult, static_cast<unsigned>(bytesWritten));
      break;
    }
  }

  i2s_zero_dma_buffer(kI2sPort);
  Serial.println("DAC test complete");
#endif
}

void sendStatus(const char *status) {
  if (activeClient >= 0) {
    webSocket.sendTXT(static_cast<uint8_t>(activeClient), status);
  }
}

void endCall(const char *status = "CALL_END") {
  const bool hadCall = callRinging || callAnswered || directTalkActive;
  callRinging = false;
  callAnswered = false;
  directTalkActive = false;
  if (playbackQueue != nullptr) {
    xQueueReset(playbackQueue);
  }
  if (hadCall) {
    setLedMode(LedMode::HangupFlash);
  }
  sendStatus(status);
}

void handleWebSocketEvent(uint8_t client, WStype_t type, uint8_t *payload,
                          size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      if (activeClient >= 0 || pendingClient >= 0) {
        webSocket.disconnect(client);
        return;
      }
      pendingClient = static_cast<int8_t>(client);
      pendingAuthStartedAt = millis();
      webSocket.sendTXT(client, "AUTH_REQUIRED");
      break;

    case WStype_DISCONNECTED:
      if (pendingClient == static_cast<int8_t>(client)) {
        pendingClient = -1;
      }
      if (activeClient == static_cast<int8_t>(client)) {
        activeClient = -1;
        endCall();
      }
      break;

    case WStype_TEXT:
      if (pendingClient == static_cast<int8_t>(client)) {
        constexpr size_t kAuthPrefixLength = 5;
        const size_t tokenLength = std::strlen(DOORBELL_ACCESS_TOKEN);
        if (length == kAuthPrefixLength + tokenLength &&
            std::memcmp(payload, "AUTH ", kAuthPrefixLength) == 0 &&
            std::memcmp(payload + kAuthPrefixLength, DOORBELL_ACCESS_TOKEN,
                        tokenLength) == 0 &&
            tokenLength >= 16) {
          pendingClient = -1;
          activeClient = static_cast<int8_t>(client);
          webSocket.sendTXT(client, "READY");
          if (callRinging) {
            webSocket.sendTXT(client, "RING");
          }
        } else {
          pendingClient = -1;
          webSocket.disconnect(client);
        }
        return;
      }

      if (activeClient != static_cast<int8_t>(client)) {
        return;
      }
      if (length == 6 && std::memcmp(payload, "ANSWER", 6) == 0 &&
          callRinging) {
        callRinging = false;
        callAnswered = true;
        xQueueReset(playbackQueue);
        setLedMode(LedMode::Connected);
        sendStatus("ANSWERED");
      } else if (length == 6 && std::memcmp(payload, "HANGUP", 6) == 0) {
        Serial.printf("HANGUP received (ring=%d, call=%d, talk=%d)\n",
                      callRinging, callAnswered, directTalkActive);
        endCall();
      } else if (length == 10 &&
                 std::memcmp(payload, "TALK_START", 10) == 0) {
        if (callRinging || callAnswered) {
          Serial.println("Direct talk rejected: doorbell is busy");
          sendStatus("BUSY");
        } else {
          directTalkActive = true;
          xQueueReset(playbackQueue);
          setLedMode(LedMode::DirectTalk);
          Serial.println("Direct talk started");
          sendStatus("TALK_STARTED");
        }
      } else if (length == 9 &&
                 std::memcmp(payload, "TALK_STOP", 9) == 0) {
        if (directTalkActive) {
          Serial.println("Direct talk stopped");
          endCall("TALK_STOPPED");
        } else {
          sendStatus("TALK_STOPPED");
        }
      }
      break;

    case WStype_BIN:
      if ((callAnswered || directTalkActive) &&
          activeClient == static_cast<int8_t>(client)) {
        if (length != kPcmFrameBytes) {
          ++rejectedPcmFramesThisSecond;
          break;
        }
        AudioFrame frame;
        std::memcpy(frame.samples, payload, kPcmFrameBytes);
        ++receivedPcmFramesThisSecond;
        for (size_t i = 0; i < kSamplesPerFrame; ++i) {
          const int32_t sample = frame.samples[i];
          const uint16_t magnitude =
              static_cast<uint16_t>(sample < 0 ? -sample : sample);
          receivedPcmPeakThisSecond =
              std::max(receivedPcmPeakThisSecond, magnitude);
        }
        if (xQueueSend(playbackQueue, &frame, 0) != pdTRUE) {
          AudioFrame staleFrame;
          xQueueReceive(playbackQueue, &staleFrame, 0);
          xQueueSend(playbackQueue, &frame, 0);
        }
      }
      break;

    default:
      break;
  }
}

bool initializeI2s() {
  i2s_config_t config = {};
  config.mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_TX |
                                        I2S_MODE_RX);
  config.sample_rate = kSampleRate;
  config.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  config.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  config.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  config.dma_buf_count = 8;
  config.dma_buf_len = kSamplesPerFrame;
  config.use_apll = false;
  config.tx_desc_auto_clear = true;
  config.fixed_mclk = 0;

  i2s_pin_config_t pins = {};
  pins.bck_io_num = kI2sBitClockPin;
  pins.ws_io_num = kI2sWordSelectPin;
  pins.data_out_num = kDacDataPin;
  pins.data_in_num = kMicDataPin;
  pins.mck_io_num = I2S_PIN_NO_CHANGE;

  if (i2s_driver_install(kI2sPort, &config, 0, nullptr) != ESP_OK) {
    Serial.println("I2S driver installation failed");
    return false;
  }
  if (i2s_set_pin(kI2sPort, &pins) != ESP_OK) {
    Serial.println("I2S pin configuration failed");
    i2s_driver_uninstall(kI2sPort);
    return false;
  }
  i2s_zero_dma_buffer(kI2sPort);
  return true;
}

void startCall() {
  if (callRinging || callAnswered || directTalkActive) {
    endCall();
    return;
  }
  callRinging = true;
  setLedMode(LedMode::Ringing);
  if (activeClient >= 0) {
    sendStatus("RING");
    Serial.println("Incoming call sent to the connected client");
  } else {
    Serial.println("Touch detected; no companion client is connected");
  }
}

void checkTouch() {
  const bool reading = digitalRead(kTouchPin) == HIGH;
  const uint32_t now = millis();

  if (reading != touchWasHigh) {
    touchWasHigh = reading;
    touchChangedAt = now;
  }

  if (reading != touchStableState &&
      now - touchChangedAt >= kTouchDebounceMs) {
    touchStableState = reading;
    if (touchStableState) {
      startCall();
    }
  }
}

void serviceAudio() {
  int32_t micSamples[kSamplesPerFrame] = {};
  size_t bytesRead = 0;
  const esp_err_t readResult =
      i2s_read(kI2sPort, micSamples, sizeof(micSamples), &bytesRead,
               pdMS_TO_TICKS(25));

  if (readResult != ESP_OK) {
    Serial.printf("I2S read failed: %d\n", readResult);
    return;
  }

  if (bytesRead < sizeof(micSamples)) {
    std::memset(reinterpret_cast<uint8_t *>(micSamples) + bytesRead, 0,
                sizeof(micSamples) - bytesRead);
  }

  int16_t micFrame[kSamplesPerFrame];
  int32_t dacSamples[kSamplesPerFrame];
  for (size_t i = 0; i < kSamplesPerFrame; ++i) {
    micFrame[i] = static_cast<int16_t>(micSamples[i] >> 16);
  }

  AudioFrame playbackFrame = {};
  if (callAnswered || directTalkActive) {
    xQueueReceive(playbackQueue, &playbackFrame, 0);
  }
  for (size_t i = 0; i < kSamplesPerFrame; ++i) {
    dacSamples[i] =
        static_cast<int32_t>(playbackFrame.samples[i]) * kSpeakerGainQ16;
  }

  size_t bytesWritten = 0;
  const esp_err_t writeResult =
      i2s_write(kI2sPort, dacSamples, sizeof(dacSamples), &bytesWritten,
                pdMS_TO_TICKS(25));
  if (writeResult != ESP_OK || bytesWritten != sizeof(dacSamples)) {
    Serial.printf("I2S write incomplete: error=%d, wrote=%u of %u bytes\n",
                  writeResult, static_cast<unsigned>(bytesWritten),
                  static_cast<unsigned>(sizeof(dacSamples)));
    return;
  }

  if ((callAnswered || directTalkActive) && activeClient >= 0) {
    webSocket.broadcastBIN(reinterpret_cast<uint8_t *>(micFrame),
                           sizeof(micFrame));
  }
}

void connectWifi() {
  if (std::strlen(DOORBELL_WIFI_SSID) == 0) {
    Serial.println("Wi-Fi is not configured; add include/secrets.h and reboot");
    return;
  }
  if (std::strlen(DOORBELL_ACCESS_TOKEN) < 16) {
    Serial.println("Configure a 16-character access token in include/secrets.h");
    return;
  }

  WiFi.mode(WIFI_STA);
  WiFi.setHostname("goouuu-doorbell");
  WiFi.setAutoReconnect(true);
  WiFi.begin(DOORBELL_WIFI_SSID, DOORBELL_WIFI_PASSWORD);
  Serial.printf("Connecting to Wi-Fi %s", DOORBELL_WIFI_SSID);

  const uint32_t startedAt = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - startedAt < 20000) {
    delay(250);
    Serial.print('.');
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Wi-Fi connection failed; checking for reconnection");
    return;
  }

  Serial.printf("Doorbell IP: %s\n", WiFi.localIP().toString().c_str());
  lastMdnsAttemptAt = millis();
  if (MDNS.begin("goouuu-doorbell")) {
    mdnsStarted = true;
    MDNS.addService("doorbell", "tcp", 81);
    Serial.println("Companion address: goouuu-doorbell.local");
  } else {
    Serial.println("mDNS startup failed; use the IP address printed above");
  }
}

void maintainWifi() {
  if (std::strlen(DOORBELL_WIFI_SSID) == 0 ||
      std::strlen(DOORBELL_ACCESS_TOKEN) < 16) {
    return;
  }

  if (WiFi.status() != WL_CONNECTED) {
    if (millis() - lastWifiRetryAt >= 5000) {
      lastWifiRetryAt = millis();
      WiFi.reconnect();
    }
    return;
  }

  if (!mdnsStarted && millis() - lastMdnsAttemptAt >= 5000) {
    lastMdnsAttemptAt = millis();
    if (MDNS.begin("goouuu-doorbell")) {
      mdnsStarted = true;
      MDNS.addService("doorbell", "tcp", 81);
      Serial.println("Companion address: goouuu-doorbell.local");
    }
  }
}

}  // namespace

void setup() {
  Serial.begin(115200);
  ledRing.begin();
  ledRing.setBrightness(kLedBrightness);
  setLedMode(LedMode::BootRainbow);
  pinMode(kTouchPin, INPUT);

  playbackQueue = xQueueCreate(4, sizeof(AudioFrame));
  if (playbackQueue == nullptr) {
    Serial.println("Could not allocate audio queue");
    return;
  }

  if (!initializeI2s()) {
    return;
  }

  runDacDiagnosticTone();
  cameraReady = initializeCamera();
  hardwareReady = true;
  connectWifi();
  webSocket.begin();
  webSocket.onEvent(handleWebSocketEvent);
  Serial.println("Doorbell WebSocket server listening on port 81");

  if (xTaskCreatePinnedToCore(cameraServerTask, "camera-stream", 8192, nullptr,
                              1, nullptr, 0) != pdPASS) {
    Serial.println("Could not start camera stream server task");
  }
  setLedMode(LedMode::StandbySparkles);
}

void loop() {
  if (!hardwareReady) {
    delay(1000);
    return;
  }

  maintainWifi();
  webSocket.loop();
  if ((callAnswered || directTalkActive) &&
      millis() - lastPcmDiagnosticAt >= 1000) {
    Serial.printf("PC audio RX: %lu frames/s, peak=%u, rejected=%lu\n",
                  static_cast<unsigned long>(receivedPcmFramesThisSecond),
                  receivedPcmPeakThisSecond,
                  static_cast<unsigned long>(rejectedPcmFramesThisSecond));
    receivedPcmFramesThisSecond = 0;
    rejectedPcmFramesThisSecond = 0;
    receivedPcmPeakThisSecond = 0;
    lastPcmDiagnosticAt = millis();
  }
  if (pendingClient >= 0 && millis() - pendingAuthStartedAt >= 10000) {
    const uint8_t unauthenticatedClient =
        static_cast<uint8_t>(pendingClient);
    pendingClient = -1;
    webSocket.disconnect(unauthenticatedClient);
  }
  checkTouch();
  updateLedAnimation();
  serviceAudio();
}
