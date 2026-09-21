package com.pandapan.stackcast.infrastructure.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pandapan.stackcast.domain.model.PcmAudioData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit PCM データを端末の物理スピーカーから外部再生するユーティリティクラス。
 *
 * 主に設定画面での「外部スピーカーで確認」機能において、内部結合された
 * (TTS + BGM) PCM 音声を開発者・利用者が耳で確認するために使用する。
 */
class SpeakerAudioPlayer {

    private var activeTrack: AudioTrack? = null

    /**
     * 指定された [PcmAudioData] を外部スピーカーへ再生し、再生完了まで待機する。
     *
     * @param audio 再生対象のモノラル 16-bit PCM データ。
     * @param volume 再生音量倍率 (0.0〜1.0)。
     * @return 正常に再生完了した場合は [Result.success]、エラー時は [Result.failure]。
     */
    suspend fun play(audio: PcmAudioData, volume: Float = 1.0f): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            stop()
            if (audio.samples.isEmpty()) return@runCatching

            val sampleRate = audio.sampleRateHz
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

            val byteBuffer = ByteBuffer.allocate(audio.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audio.samples) {
                byteBuffer.putShort(sample)
            }
            val pcmBytes = byteBuffer.array()

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferSize, pcmBytes.size))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            activeTrack = track
            track.setVolume(volume.coerceIn(0.0f, 1.0f))
            track.play()

            // チャンク単位で書き込み
            var offset = 0
            val chunkSize = 4096
            while (offset < pcmBytes.size && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val bytesToWrite = minOf(chunkSize, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, bytesToWrite)
                if (written < 0) break
                offset += written
            }

            // 再生完了まで待機 (再生位置が全サンプルに達するまで)
            val durationMs = (audio.samples.size * 1000L) / sampleRate
            delay(durationMs + 100L)

            stop()
        }
    }

    /** 再生を直ちに停止し、リソースを解放する。 */
    fun stop() {
        activeTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        }
        activeTrack = null
    }
}
