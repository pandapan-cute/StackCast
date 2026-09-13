package com.pandapan.stackcast.domain.model

/**
 * AIキャラクターの人格・応答スタイルを定義する値オブジェクト。
 *
 * LLM への入力プロンプト構築 (`PersonaPromptBuilder`) の材料となる、
 * システムプロンプト・キャラクター設定・語尾ルール・NGワードを保持する。
 * ドメイン層に属するため Android SDK には一切依存しない。
 *
 * @property systemPrompt LLMに与える基本的なシステムプロンプト。
 * @property characterSetting キャラクターの性格・口調・背景設定。
 * @property endingRule 語尾など話し方のルール (例: 「〜ですぱん」)。
 * @property ngWords 応答に含めてはいけない禁止ワードの一覧。
 */
data class PersonaPrompt(
    val systemPrompt: String = "",
    val characterSetting: String = "",
    val endingRule: String = "",
    val ngWords: List<String> = emptyList()
)
