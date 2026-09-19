package com.pandapan.stackcast

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import com.pandapan.stackcast.databinding.ActivityMainBinding

/**
 * アプリ唯一の Activity。
 *
 * StackCast は Single Activity + Fragment Navigation 構成を採用しており、
 * 画面遷移はすべて [NavHostFragment] が保持する nav_graph.xml によって管理される。
 * 24時間連続稼働を前提としたライブ配信アプリのため、画面が自動的にスリープ状態へ
 * 遷移しないよう [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] を設定する。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 配信中に画面が自動消灯・スリープしないようにするためのフラグ。
        // StreamingForegroundService 側のWakeLockと合わせて24時間稼働を担保する。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
