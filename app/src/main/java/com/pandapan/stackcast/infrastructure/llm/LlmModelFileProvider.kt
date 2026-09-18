package com.pandapan.stackcast.infrastructure.llm

import android.content.Context
import java.io.File

/**
 * ダウンロード済みLLMモデルファイルの標準的な保存先パスを解決するための拡張関数。
 *
 * アプリ内部ストレージ (`filesDir/models/`) 配下に固定のファイル名で保存することで、
 * ダウンロード処理 ([HttpLlmModelDownloader]) と推論エンジン ([MediaPipeLlmInferenceEngine])
 * の双方が同じパス規約を参照できるようにする。
 *
 * @receiver モデルファイルパスの解決に使用するアプリケーションコンテキスト。
 * @return モデルファイルの [File] (まだ存在するとは限らない)。
 */
fun Context.llmModelFile(): File {
    val modelsDir = File(filesDir, "models")
    return File(modelsDir, "llm_model.task")
}
