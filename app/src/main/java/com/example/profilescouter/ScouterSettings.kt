/*
 * 顔の検出枠などの表示設定と、接続・認識・登録画面への入口、詳細な動作確認をまとめる画面。
 */
package com.example.profilescouter

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.profilescouter.ui.designsystem.*

@Composable
internal fun ScouterSettings(showBoxes: Boolean, detectionCount: Int, status: String,
    analysisEnabled: Boolean, onAnalysisChanged: (Boolean) -> Unit,
    onBoxesChanged: (Boolean) -> Unit, onConnection: () -> Unit, onRecognition: () -> Unit, onProfile: () -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit) {
    var diagnostics by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    ScouterSheet("設定", "見る・つながる・自分の情報を整える", onDismiss) {
        ScouterSection("接続とプロフィール") {
            ScouterAction("PCに接続", "認識に使うPCのアドレスを設定", onConnection)
            HorizontalDivider()
            ScouterAction("認識モード", "PCで認識する・デモで試す", onRecognition)
            HorizontalDivider()
            ScouterAction("プロフィール登録", "3方向の顔写真とプロフィールをPCへ登録", onProfile)
        }
        ScouterSection("表示") {
            ScouterSwitch("顔の枠を表示", "現在 ${detectionCount}人を検出", showBoxes, onBoxesChanged)
        }
        TextButton(onClick = { diagnostics = !diagnostics }) {
            Text(if (diagnostics) "詳細設定を閉じる" else "詳細設定・動作確認")
        }
        if (diagnostics) {
            ScouterSection("動作確認") {
                ScouterSwitch("顔の解析", "一時停止して映像を確認できます", analysisEnabled, onAnalysisChanged)
            }
            ScouterNotice(status)
            TextButton(onClick = { confirmReset = true }) { Text("表示位置と認識結果をリセット") }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("表示をリセットしますか？") },
        text = { Text("現在の顔の追跡と認識結果を消し、検出し直します。保存したプロフィールは残ります。") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("リセット") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("キャンセル") } })
}
