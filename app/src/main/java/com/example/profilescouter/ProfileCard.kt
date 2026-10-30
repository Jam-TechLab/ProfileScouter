package com.example.profilescouter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache

internal data class ProfileCardContent(val badge: String, val name: String, val affiliation: String, val bio: String)

internal fun profileCardContent(number: Int, result: FaceRecognitionResult?, mode: RecognitionMode): ProfileCardContent = when {
    result?.matched == true -> ProfileCardContent(when {
        result.mock -> "DEMO · 仮データ"
        result.simpleProfile -> "PROFILE · 受信済み"
        else -> "MATCHED · 照合一致"
    },
        result.profile!!.displayName, result.profile.affiliation, result.profile.bio)
    result != null -> ProfileCardContent("UNKNOWN", "未登録の人物", "一致する登録情報がありません", "")
    else -> ProfileCardContent("FACE $number", "人物を検出", if (mode == RecognitionMode.OFF) "認識モードを選択してください" else "プロフィールを確認中…", "")
}

/** Camera-anchored card uses the same dark/mint visual language as the Material settings. */
internal class ProfileCardRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cache = LruCache<ProfileCardContent, List<StaticLayout>>(32)
    private fun layout(text: String, size: Float, bold: Boolean = false, lines: Int = 1,
        color: Int = Color.WHITE): StaticLayout {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, 288)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
    }

    fun draw(canvas: Canvas, content: ProfileCardContent, debug: String?) {
        val layouts = cache[content] ?: listOf(
            layout(content.badge, 11f, color = Color.rgb(150, 239, 208)),
            layout(content.name, 25f, bold = true),
            layout(content.affiliation, 15f, color = Color.rgb(225, 238, 232)),
            layout(content.bio, 15f, lines = 2, color = Color.rgb(242, 246, 243))
        ).also { cache.put(content, it) }
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(85, 8, 22, 17)
        canvas.drawRoundRect(0f, 0f, 320f, 180f, 16f, 16f, paint)
        val positions = floatArrayOf(12f, 34f, 70f, 96f)
        layouts.forEachIndexed { index, text ->
            val saved = canvas.save()
            canvas.translate(16f, positions[index])
            text.draw(canvas)
            canvas.restoreToCount(saved)
        }
        if (debug != null) {
            paint.color = Color.rgb(213, 231, 221)
            paint.textSize = 10f
            paint.setShadowLayer(2f, 0f, 1f, Color.BLACK)
            canvas.drawText(debug, 16f, 165f, paint)
            paint.clearShadowLayer()
        }
    }
}
