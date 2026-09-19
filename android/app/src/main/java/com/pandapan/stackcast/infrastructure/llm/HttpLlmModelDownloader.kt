package com.pandapan.stackcast.infrastructure.llm

import com.pandapan.stackcast.domain.model.LlmModelConfig
import com.pandapan.stackcast.domain.model.ModelDownloadState
import com.pandapan.stackcast.domain.ports.ModelDownloaderPort
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * `java.net.HttpURLConnection` を用いて HTTP(S) 経由でLLMモデルファイルをダウンロードする
 * [ModelDownloaderPort] の実装。
 *
 * HuggingFace 等、アクセストークンが必要な配布元にも対応できるよう、
 * [LlmModelConfig.huggingFaceAccessToken] が空でない場合は `Authorization: Bearer <token>`
 * ヘッダを付与してリクエストする。
 *
 * ダウンロード中の一時ファイルへ書き込み、完了後にアトミックに目的のファイル名へリネームすることで、
 * 途中失敗時に不完全なモデルファイルが読み込まれてしまうことを防ぐ。
 */
class HttpLlmModelDownloader : ModelDownloaderPort {

    override fun download(config: LlmModelConfig, destinationPath: String): Flow<ModelDownloadState> {
        return flow {
            emit(ModelDownloadState.InProgress(downloadedBytes = 0L, totalBytes = -1L))

            if (config.modelUrl.isBlank()) {
                emit(ModelDownloadState.Failed(MESSAGE_URL_BLANK))
                return@flow
            }

            val destinationFile = File(destinationPath)
            val tempFile = File(destinationFile.parentFile, "${destinationFile.name}$TEMP_FILE_SUFFIX")

            var connection: HttpURLConnection? = null
            try {
                destinationFile.parentFile?.mkdirs()

                connection = (URL(config.modelUrl).openConnection() as HttpURLConnection).apply {
                    if (config.huggingFaceAccessToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${config.huggingFaceAccessToken}")
                    }
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    emit(ModelDownloadState.Failed("$MESSAGE_HTTP_ERROR_PREFIX$responseCode"))
                    return@flow
                }

                val totalBytes = connection.contentLengthLong

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE_BYTES)
                        var downloadedBytes = 0L
                        var readBytes = input.read(buffer)
                        while (readBytes >= 0) {
                            output.write(buffer, 0, readBytes)
                            downloadedBytes += readBytes
                            emit(ModelDownloadState.InProgress(downloadedBytes, totalBytes))
                            readBytes = input.read(buffer)
                        }
                    }
                }

                // ダウンロード完了後にのみ本来のファイル名へリネームし、不完全なファイルの混入を防ぐ。
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                if (!tempFile.renameTo(destinationFile)) {
                    throw IOException("一時ファイルのリネームに失敗しました: $tempFile -> $destinationFile")
                }

                emit(ModelDownloadState.Completed(destinationFile.absolutePath))
            } catch (e: IOException) {
                tempFile.delete()
                emit(ModelDownloadState.Failed(e.message ?: MESSAGE_UNKNOWN_ERROR))
            } finally {
                connection?.disconnect()
            }
        }.flowOn(Dispatchers.IO)
    }

    private companion object {
        const val BUFFER_SIZE_BYTES = 8 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val TEMP_FILE_SUFFIX = ".download"

        const val MESSAGE_URL_BLANK = "モデルダウンロードURLが設定されていません。"
        const val MESSAGE_HTTP_ERROR_PREFIX = "ダウンロードに失敗しました (HTTPステータス: "
        const val MESSAGE_UNKNOWN_ERROR = "モデルファイルのダウンロード中に不明なエラーが発生しました。"
    }
}
