#pragma once

#include <stdint.h>

/**
 * @file ble_protocol.h
 * @brief StackCast BLE通信プロトコル定義
 * 
 * Androidアプリ（StackCast）とｽﾀｯｸﾁｬﾝ（M5Stack CoreS3）間で送受信する
 * BLE サービスUUID、キャラクタリスティックUUID、およびバイナリパケット定義。
 */

// サービスUUID
#define STACKCHAN_SERVICE_UUID           "7A0247E7-8E88-409B-A959-ABAA88B3E3B0"
// RX キャラクタリスティック (Android -> ｽﾀｯｸﾁｬﾝ 書き込み用)
#define STACKCHAN_RX_CHARACTERISTIC_UUID "7A0247E8-8E88-409B-A959-ABAA88B3E3B0"
// TX キャラクタリスティック (ｽﾀｯｸﾁｬﾝ -> Android 通知用)
#define STACKCHAN_TX_CHARACTERISTIC_UUID "7A0247E9-8E88-409B-A959-ABAA88B3E3B0"

// デバイス名
#define STACKCHAN_BLE_DEVICE_NAME        "StackChan-CoreS3"

// パケットヘッダ定数
#define PACKET_HEADER_RX 0xAA
#define PACKET_HEADER_TX 0x55

// パケットコマンドタイプ
enum PacketType : uint8_t {
    PACKET_TYPE_LIP_SYNC_SERVO = 0x01,  // 口パク開度 & サーボ角度
    PACKET_TYPE_EXPRESSION     = 0x02,  // 表情切り替え
    PACKET_TYPE_SERVO_TORQUE   = 0x03,  // サーボトルク制御（ON/OFF）
    PACKET_TYPE_HEARTBEAT      = 0x10,  // ハートビート Ping/Pong
};

// 表情タイプ
enum AvatarExpression : uint8_t {
    EXPR_NEUTRAL = 0,
    EXPR_HAPPY   = 1,
    EXPR_ANGRY   = 2,
    EXPR_SAD     = 3,
    EXPR_DOUBT   = 4,
    EXPR_SLEEP   = 5
};

#pragma pack(push, 1)

/**
 * @brief リップシンク & サーボ角度パケット (Type: 0x01)
 * サイズ: 7 bytes
 */
struct LipSyncServoPacket {
    uint8_t header;       // 0xAA
    uint8_t type;         // 0x01
    uint8_t lipOpening;   // 口の開度 (0 ~ 100)
    int16_t yawAngle;     // 水平サーボ角度 (0 ~ 180度, 90が正面)
    int16_t pitchAngle;   // 垂直サーボ角度 (0 ~ 180度, 90が正面)
};

/**
 * @brief 表情指定パケット (Type: 0x02)
 * サイズ: 3 bytes
 */
struct ExpressionPacket {
    uint8_t header;       // 0xAA
    uint8_t type;         // 0x02
    uint8_t expression;   // AvatarExpression
};

/**
 * @brief サーボトルク制御パケット (Type: 0x03)
 * サイズ: 3 bytes
 */
struct ServoTorquePacket {
    uint8_t header;       // 0xAA
    uint8_t type;         // 0x03
    uint8_t enable;       // 0: 脱力(トルクオフ), 1: トルクオン
};

/**
 * @brief ハートビートパケット (Type: 0x10)
 * サイズ: 4 bytes
 */
struct HeartbeatPacket {
    uint8_t header;       // 0xAA (RX) または 0x55 (TX)
    uint8_t type;         // 0x10
    uint16_t sequence;    // シーケンス番号
};

#pragma pack(pop)
