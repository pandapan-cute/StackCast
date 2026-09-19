package com.pandapan.stackcast.domain.model

/**
 * 16-bit PCM (符号付きリニアPCM、モノラル) の音声データを表す値オブジェクト。
 *
 * [com.pandapan.stackcast.domain.ports.TtsPort.synthesizeToPcm] の戻り値、および
 * [com.pandapan.stackcast.domain.services.LipSyncCalculator] の入力として用いる。
 * ドメイン層に属するため、Android SDK の音声APIには一切依存しない。
 *
 * @property samples 16-bit PCM サンプル列 (モノラル、時系列順)。
 * @property sampleRateHz サンプリングレート (Hz)。16kHz または 48kHz を想定するが、任意の値を許容する。
 */
data class PcmAudioData(
    val samples: ShortArray,
    val sampleRateHz: Int
) {
    /**
     * データクラスの自動生成 equals は配列を参照比較してしまうため、
     * サンプル列の内容比較 ([ShortArray.contentEquals]) を用いた実装で上書きする。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmAudioData) return false
        return sampleRateHz == other.sampleRateHz && samples.contentEquals(other.samples)
    }

    /** [equals] を配列の内容比較で上書きしたため、[hashCode] も内容ベースで算出する。 */
    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRateHz
        return result
    }
}
