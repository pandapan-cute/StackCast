package com.pandapan.stackcast.infrastructure.tts

import com.pandapan.stackcast.domain.model.PcmAudioData
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `TextToSpeech#synthesizeToFile` が出力する WAV (RIFF/PCM) ファイルを読み込み、
 * [PcmAudioData] (16-bit PCM モノラル) へ変換するユーティリティ。
 *
 * WAVファイルのチャンク構造 (`fmt ` / `data`) を解析し、ステレオ音声の場合は
 * 全チャンネルの平均を取ってモノラルへダウンミックスする。
 */
object WavPcmReader {

    /**
     * WAVファイルを読み込み、[PcmAudioData] へ変換する。
     *
     * @param wavFile 読み込み対象のWAVファイル。
     * @return 変換後の16-bit PCM (モノラル) データ。
     * @throws IOException WAVファイルとして不正な形式であった場合、
     *   または16-bit以外のビット深度であった場合。
     */
    fun read(wavFile: File): PcmAudioData {
        val bytes = wavFile.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val riffId = buffer.readAsciiString(FOUR_CC_LENGTH)
        if (riffId != RIFF_CHUNK_ID) {
            throw IOException("不正なWAVファイルです (RIFFヘッダが見つかりません): $wavFile")
        }
        buffer.int // ファイル全体サイズ (未使用)
        val waveId = buffer.readAsciiString(FOUR_CC_LENGTH)
        if (waveId != WAVE_FORMAT_ID) {
            throw IOException("不正なWAVファイルです (WAVEフォーマットではありません): $wavFile")
        }

        var channelCount = 1
        var sampleRateHz = DEFAULT_SAMPLE_RATE_HZ
        var bitsPerSample = BITS_PER_SAMPLE_16
        var pcmSamples: ShortArray? = null

        while (buffer.remaining() >= MIN_CHUNK_HEADER_SIZE) {
            val chunkId = buffer.readAsciiString(FOUR_CC_LENGTH)
            val chunkSize = buffer.int

            when (chunkId) {
                FMT_CHUNK_ID -> {
                    val chunkStart = buffer.position()
                    buffer.short // オーディオフォーマット (1=PCM、未使用)
                    channelCount = buffer.short.toInt()
                    sampleRateHz = buffer.int
                    buffer.int // バイトレート (未使用)
                    buffer.short // ブロックアライン (未使用)
                    bitsPerSample = buffer.short.toInt()
                    // fmt チャンクに拡張フィールドが含まれる場合に備え、チャンク終端まで読み飛ばす。
                    buffer.position(chunkStart + chunkSize)
                }

                DATA_CHUNK_ID -> {
                    if (bitsPerSample != BITS_PER_SAMPLE_16) {
                        throw IOException("16-bit以外のPCM ($bitsPerSample bit) には対応していません: $wavFile")
                    }
                    pcmSamples = readPcmSamples(buffer, chunkSize, channelCount)
                }

                else -> {
                    // 未知のチャンクは読み飛ばす。
                    buffer.position(buffer.position() + chunkSize)
                }
            }

            // チャンクサイズが奇数の場合、1バイトのパディングが付与される仕様に対応する。
            if (chunkSize % 2 != 0 && buffer.remaining() > 0) {
                buffer.get()
            }
        }

        val samples = pcmSamples
            ?: throw IOException("dataチャンクが見つかりませんでした: $wavFile")

        return PcmAudioData(samples, sampleRateHz)
    }

    /** data チャンクの生データを読み取り、多チャンネルの場合はモノラルへダウンミックスする。 */
    private fun readPcmSamples(buffer: ByteBuffer, chunkSize: Int, channelCount: Int): ShortArray {
        val totalSampleCount = chunkSize / BYTES_PER_SAMPLE_16BIT
        val frameCount = totalSampleCount / channelCount

        val monoSamples = ShortArray(frameCount)
        for (frameIndex in 0 until frameCount) {
            var sum = 0
            for (channelIndex in 0 until channelCount) {
                sum += buffer.short
            }
            monoSamples[frameIndex] = (sum / channelCount).toShort()
        }
        return monoSamples
    }

    /** 現在位置から [length] バイトをASCII文字列として読み取る。 */
    private fun ByteBuffer.readAsciiString(length: Int): String {
        val chars = ByteArray(length)
        get(chars)
        return String(chars, Charsets.US_ASCII)
    }

    private const val FOUR_CC_LENGTH = 4
    private const val MIN_CHUNK_HEADER_SIZE = 8
    private const val RIFF_CHUNK_ID = "RIFF"
    private const val WAVE_FORMAT_ID = "WAVE"
    private const val FMT_CHUNK_ID = "fmt "
    private const val DATA_CHUNK_ID = "data"
    private const val BITS_PER_SAMPLE_16 = 16
    private const val BYTES_PER_SAMPLE_16BIT = 2
    private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
}
