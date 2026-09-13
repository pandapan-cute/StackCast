package com.pandapan.stackcast.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.databinding.FragmentSettingsBinding

/**
 * 設定画面。
 *
 * 配信 (RTMP / 動画ID)、AI (LLM / ペルソナ)、BLE (ｽﾀｯｸﾁｬﾝ接続) の設定をまとめて管理する。
 * Phase 1 時点ではダミーUIのみを提供し、戻るボタンおよびシステムバックでダッシュボードへ戻る。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null

    /** onCreateView 〜 onDestroyView の間のみ有効な ViewBinding 参照。 */
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            // 戻るボタン: ダッシュボード画面へ遷移する。
            findNavController().navigateUp()
        }

        // システムバック操作でもダッシュボードへ戻れるようにコールバックを登録する。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().navigateUp()
                }
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }
}
