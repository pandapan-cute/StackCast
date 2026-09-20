package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.AvatarExpression
import com.pandapan.stackcast.domain.ports.StackChanPort

/**
 * ｽﾀｯｸﾁｬﾝに表情変更コマンドを送信するユースケース。
 *
 * @property stackChanPort ｽﾀｯｸﾁｬﾝ通信ポート。
 */
class SendExpressionUseCase(
    private val stackChanPort: StackChanPort
) {
    /**
     * 指定された表情をｽﾀｯｸﾁｬﾝへ送信する。
     *
     * @param expression 変更先の表情。
     * @return 送信成否のResult。
     */
    suspend operator fun invoke(expression: AvatarExpression): Result<Unit> {
        return stackChanPort.sendExpression(expression)
    }
}
