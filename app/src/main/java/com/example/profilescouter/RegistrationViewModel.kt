/*
 * プロフィール登録の送信中・成功・失敗の状態を保持し、画面回転による重複送信を防ぐ。
 */
package com.example.profilescouter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface RegistrationState {
    data object Ready : RegistrationState
    data object Sending : RegistrationState
    data class Done(val personId: Long) : RegistrationState
    data class Failed(val message: String, val uncertain: Boolean) : RegistrationState
}

/** Activity-scoped: a configuration change must not start a second registration. */
internal class RegistrationViewModel : ViewModel() {
    var state: RegistrationState by mutableStateOf(RegistrationState.Ready)
        private set

    fun reset() { if (state != RegistrationState.Sending) state = RegistrationState.Ready }

    fun markInterrupted() {
        if (state == RegistrationState.Ready)
            state = RegistrationState.Failed("前回の登録結果を復元できませんでした", uncertain = true)
    }

    fun submit(base: String, name: String, department: String, description: String, paths: List<String>) {
        if (state == RegistrationState.Sending || state is RegistrationState.Done) return
        state = RegistrationState.Sending
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val photos = FaceDirection.entries.associateWith { File(paths[it.ordinal]).readBytes() }
                    RegistrationClient(base).register(name, department, description, photos)
                }
                state = RegistrationState.Done(id)
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                val message = when (exception) {
                    is java.net.ConnectException -> "PCへ接続できません。アドレス・ポート・Wi-FiとPC側の起動設定を確認してください"
                    is java.net.SocketTimeoutException -> "応答がタイムアウトしました"
                    else -> exception.message ?: "登録結果を確認できませんでした"
                }
                state = RegistrationState.Failed(message, exception !is RegistrationRejected)
            }
        }
    }
}
