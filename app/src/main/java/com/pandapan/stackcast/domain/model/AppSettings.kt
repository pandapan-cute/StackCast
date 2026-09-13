package com.pandapan.stackcast.domain.model

/**
 * 設定画面で管理する全設定をまとめた集約ルート的な値オブジェクト。
 *
 * 配信設定・AI(ペルソナ)設定・BLE設定の3つの値オブジェクトを束ね、
 * `SettingsRepositoryPort` の入出力単位として扱う。
 *
 * @property rtmpConfig 配信 (RTMP) 設定。
 * @property personaPrompt AIキャラクターのペルソナ設定。
 * @property bleConfig ｽﾀｯｸﾁｬﾝ BLE 接続設定。
 */
data class AppSettings(
    val rtmpConfig: RtmpConfig = RtmpConfig(),
    val personaPrompt: PersonaPrompt = PersonaPrompt(),
    val bleConfig: BleConfig = BleConfig()
)
