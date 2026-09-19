package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.PcmAudioData

/**
 * テキスト音声合成 (TTS) を担うポート (インターフェース)。
 *
 * ドメイン層・アプリケーション層はこのポートにのみ依存し、
 * `android.speech.tts.TextToSpeech` 等の具体的な合成エンジンの実装詳細は
 * インフラ層のアダプタークラスに委ねる (DIP)。
 */
interface TtsPort {

    /**
     * テキストを音声合成し、リップシンク解析に用いる 16-bit PCM データとして取得する。
     *
     * 実際にスピーカーから音を再生するのではなく、解析用のPCMデータを取得するための
     * メソッドである点に注意。実際の再生と口パク駆動を同期させたい場合は、
     * このメソッドで得たPCMデータを元に [com.pandapan.stackcast.domain.services.LipSyncCalculator]
     * でタイムラインを算出したうえで、別途再生処理と同期させる。
     *
     * @param text 音声合成対象のテキスト。
     * @return 成功時は [PcmAudioData] を含む [Result.success]、
     *   合成エンジン未初期化や合成失敗時は [Result.failure] を返す。
     */
    suspend fun synthesizeToPcm(text: String): Result<PcmAudioData>

    /**
     * テキストを音声合成し、即座にスピーカーから再生する。
     *
     * @param text 音声合成対象のテキスト。
     * @return 再生完了まで待機し、成功時は [Result.success]、
     *   合成エンジン未初期化や再生失敗時は [Result.failure] を返す。
     */
    suspend fun speak(text: String): Result<Unit>

    /**
     * TTSエンジンが初期化済みで、即座に合成・再生可能な状態かどうかを返す。
     *
     * @return エンジンが初期化済みであれば true。
     */
    fun isReady(): Boolean
}
