package com.pandapan.stackcast.presentation.streaming

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.R
import com.pandapan.stackcast.databinding.FragmentStreamingBinding

/**
 * 配信中画面。
 *
 * カメラプレビューと配信状態・音声・リップシンク・BLE通信・エラー通知のオーバーレイを表示する。
 * Phase 1 時点ではダミーUIのみを提供し、実際の RTMP / BLE / AI 連携は Phase 2 以降で実装する。
 */
class StreamingFragment : Fragment() {

    private var _binding: FragmentStreamingBinding? = null

    /** onCreateView 〜 onDestroyView の間のみ有効な ViewBinding 参照。 */
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStreamingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStopStream.setOnClickListener {
            // 配信終了ボタン: 配信結果サマリー画面へ遷移する。
            // Phase 2 以降で配信終了確認ダイアログ (EndConfirm) を挟む予定。
            findNavController().navigate(R.id.action_streaming_to_summary)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }
}
