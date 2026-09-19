package com.pandapan.stackcast.presentation.summary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.R
import com.pandapan.stackcast.databinding.FragmentSummaryBinding

/**
 * 配信結果サマリー画面。
 *
 * 配信終了時の配信時間・処理コメント数などの実績を表示する。
 * ダッシュボードへ戻る際は、バックスタックに配信中画面が残らないよう
 * nav_graph.xml 側で `popUpTo` + `popUpToInclusive` を設定している。
 */
class SummaryFragment : Fragment() {

    private var _binding: FragmentSummaryBinding? = null

    /** onCreateView 〜 onDestroyView の間のみ有効な ViewBinding 参照。 */
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBackToDashboard.setOnClickListener {
            // ダッシュボードへ戻るボタン: ダッシュボード画面へ遷移し、バックスタックをクリアする。
            findNavController().navigate(R.id.action_summary_to_dashboard)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }
}
