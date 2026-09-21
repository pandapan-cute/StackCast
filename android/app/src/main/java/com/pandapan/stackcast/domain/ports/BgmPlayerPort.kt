package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.PcmAudioData

/**
 * BGMの内部再生およびループ制御を担うポート。
 *
 * ドメイン層・アプリケーション層はこのインターフェースに依存し、
 * 具体的な音源の読み込みやバッファリング、再生実装はインフラ層へ委譲する。
 */
interface BgmPlayerPort {

    /** BGMの再生を開始する。 */
    fun play()

    /** BGMの再生を停止し、先頭位置へ巻き戻す。 */
    fun stop()

    /** BGMの再生を一時停止する。 */
    fun pause()

    /** BGMが現在再生中かどうか。 */
    val isPlaying: Boolean

    /** BGMの音量倍率 (0.0〜1.0)。 */
    var volume: Float

    /** BGMをループ再生するかどうか。 */
    var isLooping: Boolean

    /**
     * BGM音源全体のPCMデータを取得する。
     *
     * @return ロード済みの [PcmAudioData]、未ロードの場合は null。
     */
    fun getPcmData(): PcmAudioData?

    /**
     * 指定されたサンプル数のBGM PCMサンプルを取得し、再生位置を進める。
     *
     * ループ再生が有効な場合は終端到達時に自動的に先頭から巡回する。
     * 停止中の場合は無音 (0) の配列を返す。
     *
     * @param sampleCount 要求するサンプル数 (モノラル基準)。
     * @param targetSampleRateHz 要求する出力サンプリングレート (Hz)。
     * @return 16-bit PCM サンプル配列。
     */
    fun readNextSamples(sampleCount: Int, targetSampleRateHz: Int): ShortArray

    /** リソースを解放する。 */
    fun release()
}
