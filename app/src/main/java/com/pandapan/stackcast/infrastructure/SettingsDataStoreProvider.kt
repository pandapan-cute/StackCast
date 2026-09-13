package com.pandapan.stackcast.infrastructure

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** DataStore (Preferences) のファイル名。アプリ設定専用のストアとして分離する。 */
private const val SETTINGS_DATASTORE_NAME = "stackcast_settings"

/**
 * アプリ設定用の [DataStore] を提供する [Context] の拡張プロパティ。
 *
 * `preferencesDataStore` デリゲートによりプロセス内でシングルトンとして生成されるため、
 * 複数箇所から参照しても同一のファイルを安全に共有できる。
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME
)
