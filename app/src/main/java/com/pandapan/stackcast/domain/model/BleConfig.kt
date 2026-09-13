package com.pandapan.stackcast.domain.model

/**
 * ｽﾀｯｸﾁｬﾝ (M5Stack) との BLE 接続設定を表す値オブジェクト。
 *
 * 実際の BLE スキャン・ペアリング処理は Phase 8 で実装するが、
 * 接続対象デバイスの識別情報と自動再接続方針は Phase 2 の時点で
 * 設定画面から入力・永続化できるようにしておく。
 * ドメイン層に属するため Android SDK (BluetoothGatt 等) には依存しない。
 *
 * @property deviceAddress 接続対象ｽﾀｯｸﾁｬﾝの BLE MAC アドレス。
 * @property deviceName 接続対象ｽﾀｯｸﾁｬﾝのデバイス名（表示用）。
 * @property autoReconnect 通信切断時に自動再接続を試みるかどうか。
 */
data class BleConfig(
    val deviceAddress: String = "",
    val deviceName: String = "",
    val autoReconnect: Boolean = true
)
