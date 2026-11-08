package com.ss2ux.subclean

/**
 * 말투 모드.
 * - ALL: 공통 규칙만
 * - HONORIFIC1: 화자 연상 · 청자 연하 (존대 표현을 쓰는 상황)
 * - HONORIFIC2: 화자 연하 · 청자 연상 (전형적인 존댓말/해요체)
 * - CASUAL: 반말(해체)로 통일
 *
 * 이전 버전 JSON의 "HONORIFIC" 은 HONORIFIC2 로 읽는다.
 */
enum class Speech(val chipLabel: String, val ruleLabel: String) {
    ALL("공통만", "공통"),
    HONORIFIC1("높임1", "높임1"),
    HONORIFIC2("높임2", "높임2"),
    CASUAL("반말", "반말");

    companion object {
        fun fromName(s: String?): Speech = when (s) {
            "HONORIFIC" -> HONORIFIC2   // 이전 버전 호환
            else -> values().firstOrNull { it.name == s } ?: ALL
        }
    }
}

/**
 * 정규식 규칙의 찾을 말 안에서 쓸 수 있는 "한글 받침 묶음" 약속어.
 * 받침 때문에 말이 달라지는 어미(먹어/좋아, 학생이야/친구야 등)를 정규식 하나로 가르기 위한 것이다.
 *   {받침}      받침이 있는 글자 (먹, 좋, 학)
 *   {무받침}    받침이 없는 글자 (가, 구, 어)
 *   {ㄹ받침}    ㄹ 받침 글자 (갈, 할, 을)
 *   {ㄴ받침}    ㄴ 받침 글자
 *   {ㅆ받침}    ㅆ 받침 글자 (했, 갔, 있, 겠)
 *   {양성받침}  ㅏ/ㅗ 모음 + 받침 글자 (좋, 많, 알, 받). 뒤에 어 대신 아가 붙는 쪽
 * 사용 예: (?<={받침})이야  = 받침 있는 글자 바로 뒤의 "이야"
 */
object HangulClass {
    private fun build(pred: (Int, Int) -> Boolean): String {
        val sb = StringBuilder("[")
        var start = -1
        var prev = -1
        fun flush() {
            if (start < 0) return
            sb.append(start.toChar())
            if (prev - start >= 2) sb.append('-')
            if (prev > start) sb.append(prev.toChar())
            start = -1
        }
        for (code in 0xAC00..0xD7A3) {
            val idx = code - 0xAC00
            val jung = (idx / 28) % 21   // 0 = ㅏ, 8 = ㅗ
            val jong = idx % 28          // 0 = 받침 없음, 4 = ㄴ, 8 = ㄹ, 20 = ㅆ
            if (pred(jung, jong)) {
                if (start < 0) start = code
                prev = code
            } else {
                flush()
            }
        }
        flush()
        sb.append(']')
        return sb.toString()
    }

    private val tokens: List<Pair<String, String>> by lazy {
        listOf(
            "{받침}" to build { _, jong -> jong != 0 },
            "{무받침}" to build { _, jong -> jong == 0 },
            "{ㄹ받침}" to build { _, jong -> jong == 8 },
            "{ㄴ받침}" to build { _, jong -> jong == 4 },
            "{ㅆ받침}" to build { _, jong -> jong == 20 },
            "{양성받침}" to build { jung, jong -> jong != 0 && (jung == 0 || jung == 8) }
        )
    }

    /** 약속어가 없으면 그대로 돌려준다 (일반적인 {2,3} 같은 반복 표기는 건드리지 않는다) */
    fun expand(s: String): String {
        if (s.indexOf('{') < 0) return s
        var r = s
        for ((k, v) in tokens) {
            if (r.contains(k)) r = r.replace(k, v)
        }
        return r
    }
}
