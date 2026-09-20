# StackCast

`StackCast` は、Android アプリ本体と M5Stack CoreS3 向け BLE 受信ファームウェアをまとめたモノレポです。Android 側では RTMP 配信、オンデバイス LLM のモデル管理、音声合成、BLE 送信を担当し、`firmware/` では ｽﾀｯｸﾁｬﾝ 側のモーション受信を担当します。

## リポジトリ構成

- `android/` : Android アプリ本体。
- `firmware/` : M5Stack CoreS3 向け BLE 受信ファームウェア。
- `docs/` : 実装手順やバックアップ手順などの補助資料。

## まず読む資料

- [android/README.md](android/README.md)
- [firmware/README.md](firmware/README.md)
- [android/docs/04_実装/実装フェーズ.md](android/docs/04_実装/実装フェーズ.md)

## 現在の実装の要点

- Android 側は Classic View + Fragment + ViewBinding 構成。
- 設定は DataStore で保持し、RTMP 設定・ペルソナ・BLE 設定・LLM モデル設定をまとめて扱う。
- LLM は MediaPipe GenAI ベースで、モデルダウンロード機能を備える。
- TTS は PCM 化してリップシンク計算に流し、BLE で ｽﾀｯｸﾁｬﾝ に送信する。
