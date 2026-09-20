package com.pandapan.stackcast.domain.services

import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.model.PersonaPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonaPromptBuilder] の単体テスト。
 *
 * プロンプト文字列の組み立て内容と、応答テキストへの語尾ルール適用処理が
 * 期待どおりに行われることを検証する。
 */
class PersonaPromptBuilderTest {

    private val builder = PersonaPromptBuilder()

    private val persona = PersonaPrompt(
        systemPrompt = "あなたは視聴者に優しく寄り添うAIキャラクターです。",
        characterSetting = "明るく元気な性格で、絵文字は使わない。",
        endingRule = "のだ",
        ngWords = listOf("暴力", "差別")
    )

    private val comment = LiveComment(
        id = "comment-1",
        author = "視聴者A",
        text = "こんにちは！今日の配信も楽しみにしています。",
        timestamp = 1_700_000_000_000L
    )

    @Test
    fun `プロンプトにシステムプロンプト・キャラクター設定・語尾ルール・NGワードが含まれる`() {
        val prompt = builder.buildPrompt(persona, comment)

        assertTrue(prompt.contains(persona.systemPrompt))
        assertTrue(prompt.contains(persona.characterSetting))
        assertTrue(prompt.contains(persona.endingRule))
        assertTrue(prompt.contains("暴力"))
        assertTrue(prompt.contains("差別"))
    }

    @Test
    fun `システムプロンプトが空欄でも英語の既定指示が使われる`() {
        val blankPersona = persona.copy(systemPrompt = "")

        val prompt = builder.buildPrompt(blankPersona, comment)

        assertTrue(prompt.contains("You are StackCast's live-stream assistant."))
        assertTrue(prompt.contains("Reply in natural, idiomatic Japanese."))
    }

    @Test
    fun `出力言語は日本語に固定される`() {
        val prompt = builder.buildPrompt(persona, comment)

        assertTrue(prompt.contains("Final answers must be written in natural Japanese."))
        assertTrue(prompt.contains("Think in English if needed"))
    }

    @Test
    fun `プロンプトに最新コメントの投稿者と本文が含まれる`() {
        val prompt = builder.buildPrompt(persona, comment)

        assertTrue(prompt.contains(comment.author))
        assertTrue(prompt.contains(comment.text))
    }

    @Test
    fun `プロンプトに連続反復を避ける応答ルールが含まれる`() {
        val prompt = builder.buildPrompt(persona, comment)

        assertTrue(prompt.contains("同じ語句、同じ数字、同じ文の連続反復は避け"))
    }

    @Test
    fun `会話履歴が空の場合は履歴なしラベルが挿入される`() {
        val prompt = builder.buildPrompt(persona, comment, conversationHistory = emptyList())

        assertTrue(prompt.contains("会話履歴なし"))
    }

    @Test
    fun `会話履歴が渡された場合は全ての履歴行がプロンプトに含まれる`() {
        val history = listOf("視聴者B: 前回の配信面白かったです", "AI: ありがとうございます！")

        val prompt = builder.buildPrompt(persona, comment, conversationHistory = history)

        history.forEach { line ->
            assertTrue(prompt.contains(line))
        }
    }

    @Test
    fun `語尾ルールが未適用の応答には句読点の前に語尾が挿入される`() {
        val result = builder.applyToneEnding("今日は良い天気です。", persona)

        assertEquals("今日は良い天気ですのだ。", result)
    }

    @Test
    fun `語尾ルールがすでに適用済みの応答はそのまま返される`() {
        val alreadyApplied = "今日は良い天気なのだ。"

        val result = builder.applyToneEnding(alreadyApplied, persona)

        assertEquals(alreadyApplied, result)
    }

    @Test
    fun `語尾ルールが空の場合は応答テキストをそのまま返す`() {
        val noEndingPersona = persona.copy(endingRule = "")

        val result = builder.applyToneEnding("今日は良い天気です。", noEndingPersona)

        assertEquals("今日は良い天気です。", result)
    }

    @Test
    fun `末尾に句読点がない応答にも語尾ルールが正しく挿入される`() {
        val result = builder.applyToneEnding("今日は良い天気です", persona)

        assertEquals("今日は良い天気ですのだ", result)
        assertFalse(result.endsWith("。"))
    }

    @Test
    fun `連続する同一語句は1回にまとめられる`() {
        val result = builder.applyToneEnding("2022、2022、2022 について話す。", persona)

        assertEquals("2022について話すのだ。", result)
    }

    @Test
    fun `同じ語句の連続反復が空白や読点を挟んでも抑制される`() {
        val result = builder.applyToneEnding("私、私、私、今日の話をする。", persona)

        assertEquals("私、今日の話をするのだ。", result)
    }
}
