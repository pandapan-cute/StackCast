package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.ports.TtsPort
import com.pandapan.stackcast.domain.services.LipSyncCalculator

/**
 * テキストから音声合成〜リップシンク解析までを一連の処理として実行するユースケース。
 *
 * [TtsPort] でテキストをPCM音声データへ合成し、[LipSyncCalculator] でそのPCMデータから
 * 口パク・サーボ角の時系列 ([LipSyncMotion] のリスト) を算出する。
 * 実際の音声再生やｽﾀｯｸﾁｬﾝへのサーボ角送信は、本ユースケースの責務外とし呼び出し元に委ねる。
 *
 * @property ttsPort テキストをPCM音声データへ合成するポート (Android実機実装 / モック実装のいずれでも可)。
 * @property lipSyncCalculator PCM音声データから口パクタイムラインを算出するドメインサービス。
 */
class SynthesizeVoiceAndLipSyncUseCase(
    private val ttsPort: TtsPort,
    private val lipSyncCalculator: LipSyncCalculator = LipSyncCalculator()
) {

    /**
     * テキストを音声合成し、口パク・サーボ角の時系列を算出する。
     *
     * @param text 音声合成対象のテキスト。
     * @return 成功時は [LipSyncMotion] の時系列リストを含む [Result.success]、
     *   音声合成に失敗した場合は [Result.failure] を返す。
     */
    suspend operator fun invoke(text: String): Result<List<LipSyncMotion>> {
        return ttsPort.synthesizeToPcm(text).map { pcm -> lipSyncCalculator.calculate(pcm) }
    }
}
