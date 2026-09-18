package com.pandapan.stackcast.infrastructure.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.TtsPort
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * `android.speech.tts.TextToSpeech` を用いた [TtsPort] の実機実装。
 *
 * [synthesizeToPcm] は `TextToSpeech#synthesizeToFile` で音声を一旦WAVファイルへ書き出し、
 * [WavPcmReader] で16-bit PCMへ変換したうえで一時ファイルを削除する。
 * これにより、実際にスピーカーへ音を出さずに [com.pandapan.stackcast.domain.services.LipSyncCalculator]
 * によるリップシンク解析に必要なPCMデータのみを取得できる。
 *
 * @property context TextToSpeech エンジンの初期化に使用するアプリケーションコンテキスト。
 * @property locale 音声合成に使用する言語ロケール。
 */
class AndroidTtsEngine(
    private val context: Context,
    private val locale: Locale = Locale.JAPANESE
) : TtsPort {

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var isInitialized = false

    override fun isReady(): Boolean = isInitialized

    override suspend fun synthesizeToPcm(text: String): Result<PcmAudioData> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val engine = ensureEngineInitialized()
                    ?: throw IllegalStateException("TextToSpeechエンジンの初期化に失敗しました。")

                val outputFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, context.cacheDir)
                try {
                    synthesizeToFileAndAwait(engine, text, outputFile)
                    WavPcmReader.read(outputFile)
                } finally {
                    outputFile.delete()
                }
            }
        }
    }

    override suspend fun speak(text: String): Result<Unit> {
        return withContext(Dispatchers.Main) {
            runCatching {
                val engine = ensureEngineInitialized()
                    ?: throw IllegalStateException("TextToSpeechエンジンの初期化に失敗しました。")
                speakAndAwaitCompletion(engine, text)
            }
        }
    }

    /**
     * ネイティブリソースを解放する。`StreamingForegroundService` 終了時など、
     * このエンジンが不要になったタイミングで必ず呼び出すこと。
     */
    fun close() {
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }

    /**
     * TextToSpeech エンジンが未初期化であれば初期化を行い、初期化済みのインスタンスを返す。
     * 初期化に失敗した場合は null を返す。
     */
    private suspend fun ensureEngineInitialized(): TextToSpeech? {
        textToSpeech?.let { if (isInitialized) return it }

        return suspendCancellableCoroutine { continuation ->
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine.language = locale
                    isInitialized = true
                    continuation.resume(engine)
                } else {
                    continuation.resume(null)
                }
            }
            textToSpeech = engine
        }
    }

    /**
     * 指定テキストをWAVファイルへ合成し、書き込み完了 ([UtteranceProgressListener.onDone]) まで待機する。
     */
    private suspend fun synthesizeToFileAndAwait(engine: TextToSpeech, text: String, outputFile: File) {
        val utteranceId = UUID.randomUUID().toString()

        suspendCancellableCoroutine<Unit> { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("音声合成 (ファイル出力) に失敗しました。"))
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val result = engine.synthesizeToFile(text, params, outputFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                continuation.resumeWithException(IOException("synthesizeToFileの呼び出しに失敗しました。"))
            }
        }
    }

    /** 指定テキストをスピーカーへ再生し、再生完了 ([UtteranceProgressListener.onDone]) まで待機する。 */
    private suspend fun speakAndAwaitCompletion(engine: TextToSpeech, text: String) {
        val utteranceId = UUID.randomUUID().toString()

        suspendCancellableCoroutine<Unit> { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("音声再生に失敗しました。"))
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                continuation.resumeWithException(IOException("speakの呼び出しに失敗しました。"))
            }
        }
    }

    private companion object {
        const val TEMP_FILE_PREFIX = "stackcast_tts_"
        const val TEMP_FILE_SUFFIX = ".wav"
    }
}
