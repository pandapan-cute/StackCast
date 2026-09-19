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
    fun `プロンプトに最新コメントの投稿者と本文が含まれる`() {
        val prompt = builder.buildPrompt(persona, comment)

        assertTrue(prompt.contains(comment.author))
        assertTrue(prompt.contains(comment.text))
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
}
