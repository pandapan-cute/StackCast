/**
 * @file main.cpp
 * @brief StackCast ｽﾀｯｸﾁｬﾝ BLE 受信ファームウェア (M5Stack CoreS3)
 * 
 * Android アプリ (StackCast) から BLE 経由で送信された LipSyncMotion パケットを受信し、
 * M5Avatar による表情・口パク描画およびサーボモーターのパン/チルト駆動を行います。
 */

#include <M5Unified.h>
#include <Avatar.h>
#include <NimBLEDevice.h>
#include <ESP32Servo.h>
#include "ble_protocol.h"

using namespace m5avatar;

// ============================================================================
// サーボピン設定 (CoreS3 スタックちゃん基板に合わせて変更可能)
// ============================================================================
// ※ 一般的な Port.A (G1/G2) をデフォルト設定としています
#define SERVO_PIN_YAW   1  // 水平首振り (Yaw)
#define SERVO_PIN_PITCH 2  // 垂直首振り (Pitch)

// ============================================================================
// グローバル変数
// ============================================================================
Avatar avatar;
Servo servoYaw;
Servo servoPitch;

NimBLEServer* pServer = nullptr;
NimBLECharacteristic* pTxCharacteristic = nullptr;

bool isConnected = false;
bool servoAttached = false;

// 目標サーボ角度
int currentYaw = 90;
int currentPitch = 90;

// ============================================================================
// サーボ制御関数
// ============================================================================
void initServos() {
    // ESP32Servo 初期設定 (50Hz, パルス幅 500us〜2500us)
    servoYaw.setPeriodHertz(50);
    servoPitch.setPeriodHertz(50);
    servoYaw.attach(SERVO_PIN_YAW, 500, 2500);
    servoPitch.attach(SERVO_PIN_PITCH, 500, 2500);

    servoYaw.write(90);
    servoPitch.write(90);
    servoAttached = true;
    M5_LOGI("Servos attached: Yaw=%d, Pitch=%d", SERVO_PIN_YAW, SERVO_PIN_PITCH);
}

void setServoTorque(bool enable) {
    if (enable && !servoAttached) {
        servoYaw.attach(SERVO_PIN_YAW, 500, 2500);
        servoPitch.attach(SERVO_PIN_PITCH, 500, 2500);
        servoAttached = true;
        M5_LOGI("Servo torque ON");
    } else if (!enable && servoAttached) {
        servoYaw.detach();
        servoPitch.detach();
        servoAttached = false;
        M5_LOGI("Servo torque OFF (detached)");
    }
}

void updateServoAngles(int yaw, int pitch) {
    if (!servoAttached) return;

    // 安全のため角度を 0〜180 にクランプ
    yaw = constrain(yaw, 0, 180);
    pitch = constrain(pitch, 45, 135); // 垂直は可動域を狭めに制限

    currentYaw = yaw;
    currentPitch = pitch;

    servoYaw.write(currentYaw);
    servoPitch.write(currentPitch);
}

// ============================================================================
// 表情マッピング
// ============================================================================
void applyExpression(uint8_t expr) {
    switch (expr) {
        case EXPR_HAPPY:
            avatar.setExpression(Expression::Happy);
            break;
        case EXPR_ANGRY:
            avatar.setExpression(Expression::Angry);
            break;
        case EXPR_SAD:
            avatar.setExpression(Expression::Sad);
            break;
        case EXPR_DOUBT:
            avatar.setExpression(Expression::Doubt);
            break;
        case EXPR_SLEEP:
            avatar.setExpression(Expression::Sleepy);
            break;
        case EXPR_NEUTRAL:
        default:
            avatar.setExpression(Expression::Neutral);
            break;
    }
}

// ============================================================================
// BLE コールバック
// ============================================================================
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        isConnected = true;
        M5_LOGI("BLE Client connected: %s", NimBLEAddress(desc->peer_ota_addr).toString().c_str());
        avatar.setExpression(Expression::Happy);
        delay(500);
        avatar.setExpression(Expression::Neutral);
    }

    void onDisconnect(NimBLEServer* pServer) override {
        isConnected = false;
        M5_LOGI("BLE Client disconnected. Restarting advertising...");
        avatar.setExpression(Expression::Sad);
        avatar.setMouthOpenRatio(0.0f);

        // 切断時は安全のためサーボを正面に戻す
        updateServoAngles(90, 90);

        // アドバタイズ再開
        NimBLEDevice::startAdvertising();
    }
};

class RxCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic) override {
        std::string rxValue = pCharacteristic->getValue();
        if (rxValue.length() == 0) return;

        const uint8_t* data = (const uint8_t*)rxValue.data();
        size_t len = rxValue.length();

        M5_LOGI("RX packet received: len=%u, bytes=%02X %02X %02X %02X %02X %02X %02X %02X",
            (unsigned)len,
            len > 0 ? data[0] : 0,
            len > 1 ? data[1] : 0,
            len > 2 ? data[2] : 0,
            len > 3 ? data[3] : 0,
            len > 4 ? data[4] : 0,
            len > 5 ? data[5] : 0,
            len > 6 ? data[6] : 0,
            len > 7 ? data[7] : 0);

        // 1. Androidアプリ (LipSyncMotionPacketCodec) からの 'SC' リップシンクパケット判定
        if (len >= 6 && data[0] == 'S' && data[1] == 'C') {
            uint8_t count = data[5];
            M5_LOGI("SC lip-sync packet received: len=%u, count=%u", (unsigned)len, count);
            for (uint8_t i = 0; i < count; i++) {
                size_t offset = 6 + i * 7;
                if (offset + 7 > len) break;

                // 口の開度 (0 ~ 255 -> 0.0 ~ 1.0)
                uint8_t lip = data[offset + 4];
                float ratio = (float)lip / 255.0f;
                avatar.setMouthOpenRatio(ratio);

                // サーボ角度 (int16_t リトルエンディアン)
                int16_t angle = (int16_t)(data[offset + 5] | (data[offset + 6] << 8));
                updateServoAngles(angle, currentPitch);
                M5_LOGI("SC motion[%u]: lip=%u ratio=%.3f yaw=%d pitch=%d", i, lip, ratio, angle, currentPitch);
            }
            return;
        }

        if (len < 6 && data[0] == 'S') {
            M5_LOGW("Partial SC packet fragment received: len=%u", (unsigned)len);
            return;
        }

        // 2. コマンドパケット (0xAA ヘッダ) のチェック
        if (data[0] != PACKET_HEADER_RX) {
            M5_LOGW("Invalid packet header: 0x%02X", data[0]);
            return;
        }

        uint8_t type = data[1];

        switch (type) {
            case PACKET_TYPE_LIP_SYNC_SERVO: {
                if (len < sizeof(LipSyncServoPacket)) return;
                const auto* packet = (const LipSyncServoPacket*)data;

                // 1. 口パク開度の適用 (0 ~ 100 -> 0.0 ~ 1.0)
                float ratio = (float)packet->lipOpening / 100.0f;
                ratio = constrain(ratio, 0.0f, 1.0f);
                avatar.setMouthOpenRatio(ratio);

                // 2. サーボ角度の適用
                updateServoAngles(packet->yawAngle, packet->pitchAngle);
                M5_LOGI("PACKET_TYPE_LIP_SYNC_SERVO: lipOpening=%u ratio=%.3f yaw=%d pitch=%d", packet->lipOpening, ratio, packet->yawAngle, packet->pitchAngle);
                break;
            }

            case PACKET_TYPE_EXPRESSION: {
                if (len < sizeof(ExpressionPacket)) return;
                const auto* packet = (const ExpressionPacket*)data;
                applyExpression(packet->expression);
                M5_LOGI("PACKET_TYPE_EXPRESSION: expression=%u", packet->expression);
                break;
            }

            case PACKET_TYPE_SERVO_TORQUE: {
                if (len < sizeof(ServoTorquePacket)) return;
                const auto* packet = (const ServoTorquePacket*)data;
                setServoTorque(packet->enable != 0);
                M5_LOGI("PACKET_TYPE_SERVO_TORQUE: enable=%u", packet->enable);
                break;
            }

            case PACKET_TYPE_HEARTBEAT: {
                if (len < sizeof(HeartbeatPacket)) return;
                const auto* rxPacket = (const HeartbeatPacket*)data;

                // Pong レスポンスを送信
                if (pTxCharacteristic != nullptr) {
                    HeartbeatPacket txPacket;
                    txPacket.header = PACKET_HEADER_TX;
                    txPacket.type = PACKET_TYPE_HEARTBEAT;
                    txPacket.sequence = rxPacket->sequence;

                    pTxCharacteristic->setValue((uint8_t*)&txPacket, sizeof(HeartbeatPacket));
                    pTxCharacteristic->notify();
                }
                break;
            }

            default:
                M5_LOGW("Unknown packet type: 0x%02X", type);
                break;
        }
    }
};

// ============================================================================
// BLE 初期化
// ============================================================================
void initBLE() {
    NimBLEDevice::init(STACKCHAN_BLE_DEVICE_NAME);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9); // 最大送信電力

    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    NimBLEService* pService = pServer->createService(STACKCHAN_SERVICE_UUID);

    // RX キャラクタリスティック (書き込み受付)
    NimBLECharacteristic* pRxCharacteristic = pService->createCharacteristic(
        STACKCHAN_RX_CHARACTERISTIC_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
    );
    pRxCharacteristic->setCallbacks(new RxCallbacks());

    // TX キャラクタリスティック (通知送信)
    pTxCharacteristic = pService->createCharacteristic(
        STACKCHAN_TX_CHARACTERISTIC_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    pService->start();

    // アドバタイズ設定
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(STACKCHAN_SERVICE_UUID);
    pAdvertising->setName(STACKCHAN_BLE_DEVICE_NAME);
    pAdvertising->setScanResponse(true);
    pAdvertising->start();

    M5_LOGI("BLE Advertising started: %s", STACKCHAN_BLE_DEVICE_NAME);
}

// ============================================================================
// セットアップ & ループ
// ============================================================================
void setup() {
    auto cfg = M5.config();
    M5.begin(cfg);

    M5_LOGI("=== StackCast ｽﾀｯｸﾁｬﾝ BLE Firmware Initializing ===");

    // 1. サーボの初期化
    initServos();

    // 2. M5Avatar (アバター画面) の初期化
    avatar.init();
    avatar.setExpression(Expression::Neutral);

    // 3. BLE サーバーの初期化
    initBLE();

    M5_LOGI("=== StackCast ｽﾀｯｸﾁｬﾝ BLE Firmware Ready ===");
}

void loop() {
    M5.update();

    // 本体のタッチ操作などでテスト動作
    if (M5.Touch.getCount() > 0) {
        auto touch = M5.Touch.getDetail();
        if (touch.wasPressed()) {
            M5_LOGI("Screen touched! Cycling expression...");
            static int exprIdx = 0;
            exprIdx = (exprIdx + 1) % 6;
            applyExpression(exprIdx);
        }
    }

    delay(33); // 約30FPSで更新
}
