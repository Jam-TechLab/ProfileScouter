/*
 * 認識結果からプロフィールの表示内容を作り、人物の横に表示するARカードを描画する。
 */
package com.example.profilescouter.ui.scouter

import com.example.profilescouter.model.FaceRecognitionResult

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

internal fun profileCardContent(number: Int, result: FaceRecognitionResult?, enabled: Boolean): ProfileCardContent = when {
    result?.matched == true -> ProfileCardContent(when {
        result.simpleProfile -> "PROFILE · 受信済み"
        else -> "MATCHED · 照合一致"
    },
        result.profile!!.displayName, result.profile.affiliation, result.profile.bio)
    result != null -> ProfileCardContent("UNKNOWN", "未登録の人物", "一致する登録情報がありません", "")
    else -> ProfileCardContent("FACE $number", "人物を検出", if (enabled) "プロフィールを確認中…" else "PC認識は停止中", "")
}

/** Camera-anchored card receives its semantic colors from the Material theme. */
internal data class ProfileCardStyle(val surface: Int, val onSurface: Int, val secondaryText: Int,
    val accent: Int, val nameSize: Float, val bodySize: Float, val labelSize: Float)

internal class ProfileCardRenderer(private val style: ProfileCardStyle) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cache = LruCache<ProfileCardContent, List<StaticLayout>>(32)
    private fun layout(text: String, size: Float, bold: Boolean = false, lines: Int = 1,
        color: Int = Color.WHITE): StaticLayout {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, 288)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
    }

    fun draw(canvas: Canvas, content: ProfileCardContent, debug: String?) {
        val layouts = cache[content] ?: listOf(
            layout(content.badge, style.labelSize, color = style.accent),
            layout(content.name, style.nameSize, bold = true, color = style.onSurface),
            layout(content.affiliation, style.bodySize, color = style.secondaryText),
            layout(content.bio, style.bodySize, lines = 3, color = style.onSurface)
        ).also { cache.put(content, it) }
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = style.surface
        canvas.drawRoundRect(0f, 0f, 320f, 180f, 16f, 16f, paint)
        val positions = floatArrayOf(12f, 34f, 70f, 96f)
        layouts.forEachIndexed { index, text ->
            val saved = canvas.save()
            canvas.translate(16f, positions[index])
            text.draw(canvas)
            canvas.restoreToCount(saved)
        }
        if (debug != null || content.bio.isNotBlank()) {
            paint.color = style.secondaryText
            paint.textSize = 10f
            canvas.drawText(listOfNotNull(debug, if (content.bio.isNotBlank()) "タップして全文" else null).joinToString(" · "), 16f, 165f, paint)
            paint.clearShadowLayer()
        }
    }
}
