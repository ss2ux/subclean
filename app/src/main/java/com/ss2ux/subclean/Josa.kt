package com.ss2ux.subclean

/**
 * 한국어 조사 보정.
 * 치환으로 낱말이 바뀌면 받침이 달라져서 뒤에 붙은 조사가 어색해진다 (철수가 -> 민철가).
 * 바뀐 낱말 바로 뒤에 조사가 붙어 있으면 새 낱말의 받침에 맞는 형태로 고친다 (민철이).
 *
 * 조사처럼 보여도 다른 낱말의 일부인 경우(철수가방, 철수이야기)는 건드리지 않도록,
 * 조사 바로 뒤가 한글이 아니거나 아래에 적은 흔한 꼬리말일 때만 조사로 본다.
 */
object Josa {
    /** withB: 받침 있을 때, noB: 받침 없을 때. rieulAsNo: ㄹ 받침을 받침 없음으로 취급 (으로/로) */
    private class Particle(
        val withB: String,
        val noB: String,
        val rieulAsNo: Boolean = false,
        val tails: List<String> = emptyList()
    )

    private val pairs = listOf(
        Particle("이에요", "예요"),
        Particle("이랑", "랑"),
        Particle("이나", "나"),
        Particle("이란", "란"),
        Particle("이라", "라", tails = listOf("고", "면", "는", "서")),
        Particle("으로", "로", rieulAsNo = true,
            tails = listOf("부터", "서", "써", "는", "도", "만", "까지")),
        Particle("이", "가"),
        Particle("은", "는", tails = listOf("요")),
        Particle("을", "를", tails = listOf("요")),
        Particle("과", "와"),
        Particle("아", "야")
    )

    /** (형태, 그 형태를 쓴 쪽 Pair) 를 긴 것부터 */
    private val forms: List<kotlin.Pair<String, Particle>> =
        pairs.flatMap { listOf(it.withB to it, it.noB to it) }
            .sortedByDescending { it.first.length }

    private fun isSyllable(c: Char) = c in '\uAC00'..'\uD7A3'

    /** 0 = 받침 없음, 1 = ㄹ 받침, 2 = 그 밖의 받침, -1 = 한글 아님 */
    private fun batchim(c: Char): Int {
        if (!isSyllable(c)) return -1
        val j = (c - '\uAC00') % 28
        return when (j) {
            0 -> 0
            8 -> 1
            else -> 2
        }
    }

    /**
     * s 의 from 위치에 조사가 있으면, newWord 의 받침에 맞게 고친 조사와 조사가 끝나는 위치를 돌려준다.
     * 조사가 아니거나 newWord 가 한글로 끝나지 않으면 null.
     */
    fun fix(s: String, from: Int, newWord: String): kotlin.Pair<String, Int>? {
        if (newWord.isEmpty() || from >= s.length) return null
        val cls = batchim(newWord.last())
        if (cls < 0) return null
        for ((form, p) in forms) {
            if (!s.startsWith(form, from)) continue
            val end = from + form.length
            val ok = end >= s.length || !isSyllable(s[end]) || p.tails.any { s.startsWith(it, end) }
            if (!ok) continue
            val useB = if (p.rieulAsNo) cls == 2 else cls != 0
            return (if (useB) p.withB else p.noB) to end
        }
        return null
    }
}
