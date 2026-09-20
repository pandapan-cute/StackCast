# StackCast ｽﾀｯｸﾁｬﾝ BLE 受信ファームウェア (M5Stack CoreS3)

## 概要

`StackCast` Android アプリから Bluetooth Low Energy (BLE) 経由で送信されるリップシンク開度および首振りサーボ角度パケットを受信し、M5Stack CoreS3 上の `M5Avatar`（口パク・表情）とパン/チルトサーボをリアルタイム連動させるファームウェアです。

---

## 🛠️ ハードウェア仕様・ピン配置

- **ターゲットボード**: M5Stack CoreS3 (ESP32-S3)
- **アドバタイズ名**: `StackChan-CoreS3`
- **サーボピン設定 (デフォルト)**:
  - 水平 (Yaw): `GPIO 1` (Port.A SDA)
  - 垂直 (Pitch): `GPIO 2` (Port.A SCL)
  ※ スタックちゃんベース基板の配線に応じて `src/main.cpp` 内の `SERVO_PIN_YAW`, `SERVO_PIN_PITCH` を変更できます。

---

## 📡 BLE 通信仕様

- **サービス UUID**: `7A0247E7-8E88-409B-A959-ABAA88B3E3B0`
- **RX キャラクタリスティック (書き込み: Android -> ｽﾀｯｸﾁｬﾝ)**: `7A0247E8-8E88-409B-A959-ABAA88B3E3B0` (Write / Write Without Response)
- **TX キャラクタリスティック (通知: ｽﾀｯｸﾁｬﾝ -> Android)**: `7A0247E9-8E88-409B-A959-ABAA88B3E3B0` (Notify)

### パケットフォーマット

すべてのパケットは先頭 1 バイトのヘッダ `0xAA` (RX) から始まります。

#### 1. リップシンク & サーボ角度パケット (Type: `0x01`, 計 7 bytes)

| オフセット | 型 | 説明 |
| --- | --- | --- |
| 0 | uint8 | ヘッダ (`0xAA`) |
| 1 | uint8 | タイプ (`0x01`) |
| 2 | uint8 | 口の開度 `lipOpening` (0 ~ 100) |
| 3-4 | int16 (LE) | 水平角度 `yawAngle` (0 ~ 180度, 90が中央) |
| 5-6 | int16 (LE) | 垂直角度 `pitchAngle` (45 ~ 135度, 90が中央) |

#### 2. 表情指定パケット (Type: `0x02`, 計 3 bytes)

- `[0xAA] [0x02] [expression: 0:Neutral, 1:Happy, 2:Angry, 3:Sad, 4:Doubt, 5:Sleep]`

#### 3. サーボトルク制御パケット (Type: `0x03`, 計 3 bytes)

- `[0xAA] [0x03] [enable: 0=脱力(トルクオフ), 1=トルクオン]`

#### 4. ハートビート Ping/Pong (Type: `0x10`, 計 4 bytes)

- 送信: `[0xAA] [0x10] [sequence: uint16]`
- 返信: `[0x55] [0x10] [sequence: uint16]` (TX Notify)

---

## 🚀 ビルド & 書き込み手順

M5Stack CoreS3 を USB ケーブルで PC に接続した状態で、以下のコマンドを実行します。

PlatformIO Core CLIを使用してビルドおよび書き込みを行います。

### 1. ビルドの検証

```bash
pio run -d firmware
```

### 2. 実機への書き込み

```bash
pio run -d firmware -t upload
```

### 3. シリアルモニターで動作確認

```bash
pio device monitor -b 115200
```

起動時に `BLE Advertising started: StackChan-CoreS3` と出力され、画面にスタックちゃんの顔（アバター）が表示されれば準備完了です！
スマホの `nRF Connect` でスキャンすると `StackChan-CoreS3` が見つかるようになります。
