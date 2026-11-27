/*
 * 登録写真の方向とプロフィール入力の共通ルールを定義する。
 */
package com.example.profilescouter.model

internal enum class FaceDirection(val field: String, val label: String, val guide: String) {
    FRONT("photo_front", "正面", "顔を正面に向けて撮影してください"),
    LEFT("photo_left", "左側面", "本人の左側面が見えるように顔を向けてください"),
    RIGHT("photo_right", "右側面", "本人の右側面が見えるように顔を向けてください")
}

internal fun validateRegistration(name: String, department: String, description: String) {
    require(name.trim().isNotEmpty()) { "氏名を入力してください" }
    require(name.length <= 100 && department.length <= 100 && description.length <= 1000) {
        "氏名・所属は100文字、その他の属性は1000文字以内で入力してください"
    }
}
