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
        val systemPrompt = persona.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
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
            appendLine(systemPrompt)
            appendLine()
            appendLine(SECTION_OUTPUT_LANGUAGE)
            appendLine(OUTPUT_LANGUAGE_RULE_TEXT)
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
            appendLine(SECTION_RESPONSE_RULE)
            appendLine(RESPONSE_RULE_TEXT)
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
        val trimmedResponse = normalizeResponseText(responseText)

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

    /**
     * LLM 応答の連続反復を簡易的に除去する。
     *
     * 同じ語句・同じ数字・同じ短い断片が区切り記号や空白を挟んで連続した場合は、
     * 先頭の1回だけを残して以降を落とす。生成崩壊時の「2022, 2022, 2022」や
     * 「私、私、私」のような反復を抑えるための後処理である。
     *
     * @param responseText LLM が生成した応答テキスト。
     * @return 連続反復を簡易除去した応答テキスト。
     */
    private fun normalizeResponseText(responseText: String): String {
        val trimmed = responseText.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        return collapseConsecutiveDuplicateTokens(trimmed)
    }

    /**
     * 区切り記号や空白を挟んだ連続重複トークンを1つにまとめる。
     *
     * @param text 正規化対象の応答テキスト。
     * @return 連続する重複トークンを間引いた文字列。
     */
    private fun collapseConsecutiveDuplicateTokens(text: String): String {
        val matches = RESPONSE_TOKEN_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) {
            return text
        }

        val builder = StringBuilder()
        var cursor = 0
        var lastEmittedToken: String? = null

        for (match in matches) {
            val token = match.value
            val separator = text.substring(cursor, match.range.first)
            val isDuplicate = token == lastEmittedToken && separator.all { it.isWhitespace() || it in SEPARATOR_CHARACTERS }

            if (isDuplicate) {
                cursor = match.range.last + 1
                continue
            }

            if (separator.any { !it.isWhitespace() }) {
                builder.append(separator)
            }
            builder.append(token)
            lastEmittedToken = token
            cursor = match.range.last + 1
        }

        builder.append(text.substring(cursor))
        return builder.toString().trim()
    }

    private companion object {
        const val NONE_LABEL = "なし"
        const val NOT_SPECIFIED_LABEL = "指定なし"
        const val NO_HISTORY_LABEL = "(会話履歴なし)"

        const val SECTION_SYSTEM_PROMPT = "【システムプロンプト】"
        const val SECTION_CHARACTER_SETTING = "【キャラクター設定】"
        const val SECTION_ENDING_RULE = "【語尾ルール】"
        const val SECTION_OUTPUT_LANGUAGE = "【出力言語】"
        const val SECTION_NG_WORDS = "【禁止ワード】"
        const val SECTION_HISTORY = "【直前の会話履歴】"
        const val SECTION_LATEST_COMMENT = "【最新コメント】"
        const val SECTION_RESPONSE_RULE = "【応答ルール】"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are StackCast's live-stream assistant. Reply in natural, idiomatic Japanese. Keep answers short, warm, and easy to speak aloud. Avoid repetition, filler words, and unnatural sentence breaks."
        const val OUTPUT_LANGUAGE_RULE_TEXT =
            "Final answers must be written in natural Japanese. Think in English if needed, but do not output English unless the viewer explicitly asks for it."
        const val RESPONSE_RULE_TEXT = "同じ語句、同じ数字、同じ文の連続反復は避け、短く自然に返答してください。"
        const val INSTRUCTION_FOOTER =
            "上記のキャラクター設定・語尾ルール・禁止ワードを厳守し、最新コメントに返答してください。"

        /** 語尾ルール適用時に一旦取り除き、後で付け直す句読点の一覧。 */
        val TRAILING_PUNCTUATIONS = listOf("。", "！", "？", "!", "?", ".", "、")

        /** 応答の反復除去でトークン扱いする文字の範囲。 */
        val RESPONSE_TOKEN_PATTERN = Regex("[0-9A-Za-z一-龯ぁ-ゔァ-ヴー々〆〤]+")

        /** 連続重複判定時に区切り記号として許容する文字。 */
        val SEPARATOR_CHARACTERS = setOf('、', '。', '，', '．', ',', '.', '！', '？', '!', '?', ' ', '\t', '\n', '\r', '・', '：', ':', '；', ';', '／', '/', '「', '」', '『', '』', '（', '）', '(', ')', '[', ']', '【', '】')
    }
}
