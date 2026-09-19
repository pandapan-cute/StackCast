package com.pandapan.stackcast.presentation.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.R
import com.pandapan.stackcast.databinding.FragmentDashboardBinding

/**
 * ダッシュボード画面 (アプリの拠点)。
 *
 * 配信開始ボタンから配信中画面へ、設定ボタンから設定画面へ遷移する導線を提供する。
 * `nav_graph.xml` の startDestination であり、アプリ起動後は直接この画面が表示される。
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null

    /** onCreateView 〜 onDestroyView の間のみ有効な ViewBinding 参照。 */
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStartStream.setOnClickListener {
            // 配信開始ボタン: 配信中画面へ遷移する。
            // Phase 2 以降で設定・権限チェック (Preflight) を挟む予定。
            findNavController().navigate(R.id.action_dashboard_to_streaming)
        }

        binding.btnSettings.setOnClickListener {
            // 設定ボタン: 設定画面へ遷移する。
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }
}
