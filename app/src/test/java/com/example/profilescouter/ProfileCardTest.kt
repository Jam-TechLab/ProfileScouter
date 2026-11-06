/*
 * PCから受け取ったプロフィールと未登録の結果が、適切なカード表示内容になるか確認する単体テスト。
 */
package com.example.profilescouter

import org.junit.Assert.*
import org.junit.Test

class ProfileCardTest {
    @Test fun serverProfileBecomesTheCorrectCardContent() {
        val result = FaceRecognitionResult(true, 0.82, PersonProfile("user-1", "山田", "軽音", "ギター担当"))
        val card = profileCardContent(3, result, RecognitionMode.LIVE)
        assertEquals("山田", card.name)
        assertEquals("軽音", card.affiliation)
        assertEquals("ギター担当", card.bio)
        assertTrue(card.badge.contains("MATCHED"))
    }

    @Test fun unmatchedAndMockProfilesAreClearlyDistinguished() {
        assertEquals("未登録の人物", profileCardContent(1,
            FaceRecognitionResult(false, 0.1, null), RecognitionMode.LIVE).name)
        val demo = FaceRecognitionResult(true, 0.95, PersonProfile("demo", "仮の人", "", ""), mock = true)
        assertTrue(profileCardContent(1, demo, RecognitionMode.MOCK).badge.contains("DEMO"))
    }
}
