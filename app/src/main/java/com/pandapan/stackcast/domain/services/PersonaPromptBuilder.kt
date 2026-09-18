package com.pandapan.stackcast.domain.services

import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.model.PersonaPrompt

/**
 * [PersonaPrompt] と [LiveComment]、直前の会話履歴を組み合わせて
 * LLM へ投入する単一テキストプロンプトを構築するドメインサービス。
 *
 * また、LLMが生成した応答テキストに対してキャラクターの語尾ルールを機械的に適用する
 * 後処理も担う。ドメイン層に属するため Android SDK には一切依存しない純粋 Kotlin で実装する。
 */
class PersonaPromptBuilder {

    /**
     * LLMへ入力する単一テキストプロンプトを構築する。
     *
     * システムプロンプト・キャラクター設定・語尾ルール・禁止ワード・直前の会話履歴・最新コメントを
     * 1つのテキストにまとめ、LLMがそのままプロンプトとして解釈できる形式で返す。
     *
     * @param persona 適用するキャラクターのペルソナ設定。
     * @param comment 応答対象となる最新のライブコメント。
     * @param conversationHistory 直前までの会話履歴 (発話テキストの時系列リスト、古い順)。
     * @return LLMに渡す単一テキストプロンプト。
     */
    fun buildPrompt(
        persona: PersonaPrompt,
        comment: LiveComment,
        conversationHistory: List<String> = emptyList()
    ): String {
        val ngWordsSection = if (persona.ngWords.isEmpty()) {
            NONE_LABEL
        } else {
            persona.ngWords.joinToString(separator = "、")
        }

        val historySection = if (conversationHistory.isEmpty()) {
            NO_HISTORY_LABEL
        } else {
            conversationHistory.joinToString(separator = "\n") { "- $it" }
        }

        return buildString {
            appendLine(SECTION_SYSTEM_PROMPT)
            appendLine(persona.systemPrompt.ifBlank { NONE_LABEL })
            appendLine()
            appendLine(SECTION_CHARACTER_SETTING)
            appendLine(persona.characterSetting.ifBlank { NONE_LABEL })
            appendLine()
            appendLine(SECTION_ENDING_RULE)
            appendLine(persona.endingRule.ifBlank { NOT_SPECIFIED_LABEL })
            appendLine()
            appendLine(SECTION_NG_WORDS)
            appendLine(ngWordsSection)
            appendLine()
            appendLine(SECTION_HISTORY)
            appendLine(historySection)
            appendLine()
            appendLine(SECTION_LATEST_COMMENT)
            appendLine("${comment.author}: ${comment.text}")
            appendLine()
            append(INSTRUCTION_FOOTER)
        }
    }

    /**
     * LLMが生成した応答テキストに対して、ペルソナの語尾ルールを機械的に適用する。
     *
     * 応答テキストの末尾がすでに指定の語尾で終わっている場合は変更しない。
     * それ以外の場合は、末尾の句読点 (「。」「！」「？」等) を一旦取り除いたうえで
     * 語尾ルールの文字列を挿入し、句読点を再度末尾へ付与する。
     *
     * @param responseText LLMが生成した応答テキスト (加工前)。
     * @param persona 適用するキャラクターのペルソナ設定。
     * @return 語尾ルールが適用された応答テキスト。
     */
    fun applyToneEnding(responseText: String, persona: PersonaPrompt): String {
        val ending = persona.endingRule.trim()
        val trimmedResponse = responseText.trim()

        if (ending.isBlank() || trimmedResponse.isBlank()) {
            return trimmedResponse
        }

        val trailingPunctuation = TRAILING_PUNCTUATIONS.firstOrNull { trimmedResponse.endsWith(it) }
        val body = if (trailingPunctuation != null) {
            trimmedResponse.removeSuffix(trailingPunctuation)
        } else {
            trimmedResponse
        }

        // すでに指定の語尾で終わっている場合は二重付与を避ける。
        if (body.endsWith(ending)) {
            return trimmedResponse
        }

        return body + ending + (trailingPunctuation ?: "")
    }

    private companion object {
        const val NONE_LABEL = "なし"
        const val NOT_SPECIFIED_LABEL = "指定なし"
        const val NO_HISTORY_LABEL = "(会話履歴なし)"

        const val SECTION_SYSTEM_PROMPT = "【システムプロンプト】"
        const val SECTION_CHARACTER_SETTING = "【キャラクター設定】"
        const val SECTION_ENDING_RULE = "【語尾ルール】"
        const val SECTION_NG_WORDS = "【禁止ワード】"
        const val SECTION_HISTORY = "【直前の会話履歴】"
        const val SECTION_LATEST_COMMENT = "【最新コメント】"
        const val INSTRUCTION_FOOTER =
            "上記のキャラクター設定・語尾ルール・禁止ワードを厳守し、最新コメントに一言で返答してください。"

        /** 語尾ルール適用時に一旦取り除き、後で付け直す句読点の一覧。 */
        val TRAILING_PUNCTUATIONS = listOf("。", "！", "？", "!", "?", ".", "、")
    }
}
