package com.ss2ux.subclean

import java.text.Normalizer

/** 치환 규칙 한 줄. 찾을 말이 정규식이면 regex = true */
data class Rule(
    val id: Long,
    val find: String,
    val replace: String,
    val regex: Boolean = false,
    val enabled: Boolean = true
)

/** 반복 압축 등 다듬기 옵션 (설정 화면에서 바꾼다) */
data class CleanOptions(
    val threshold: Int = 5,            // 같은 글자/단어가 이 횟수 이상 이어지면 압축
    val keep: Int = 2,                 // 압축하고 남길 개수
    val normalize: Boolean = true,     // 유니코드 NFC 정규화 (맥에서 만든 한글 자막의 분리된 자모 등을 합침)
    val compressChars: Boolean = true, // ㅋㅋㅋㅋㅋ -> ㅋㅋ
    val compressWords: Boolean = true, // 응 응 응 응 응 -> 응 응
    val compressUnits: Boolean = false // 하하하하 하하... 같은 2~4글자 덩어리 반복 (띄어쓰기 없는 중/일어용, 실험적)
)

/**
 * 오프라인 텍스트 다듬기 엔진. 순서:
 *  0) (옵션) NFC 정규화
 *  1) 일반 치환 규칙 전부를 한 번에: 긴 말부터 우선, 치환 결과가 다시 치환되는 연쇄는 없음
 *  2) 정규식 규칙을 목록 순서대로
 *  3) 반복 압축 (한 글자 -> 단어 -> 여러 글자 덩어리)
 *
 * 숫자는 압축하지 않는다 (100000원 이 1000원 이 되면 안 되므로).
 */
class Cleaner(rules: List<Rule>, private val opt: CleanOptions) {

    /** 잘못된 정규식 규칙 등 사용자가 고쳐야 할 문제 */
    val problems = ArrayList<String>()

    private val plainMap = HashMap<String, String>()
    private var plainRegex: Regex? = null
    private val regexRules = ArrayList<Triple<Rule, Regex, String>>()
    private val brokenRules = HashSet<Long>()

    private val n = opt.threshold.coerceIn(3, 50)
    private val keep = opt.keep.coerceIn(1, n - 1)
    private val charRe = Regex("([^\\p{Nd}\\s])\\1{${n - 1},}")
    private val wordRe = Regex("(?<!\\S)(\\S+)(?:[ \\t\\u3000]+\\1){${n - 1},}(?!\\S)")
    private val unitRe = Regex("([^\\p{Nd}\\s]{2,4}?)\\1{${n - 1},}")

    init {
        for (r in rules) {
            if (!r.enabled || r.find.isEmpty()) continue
            val find = norm(r.find)
            val repl = norm(r.replace)
            if (r.regex) {
                try {
                    regexRules.add(Triple(r, Regex(find), repl))
                } catch (e: Exception) {
                    problems.add("정규식 오류: ${r.find}")
                }
            } else if (find != repl) {
                plainMap[find] = repl      // 같은 찾을 말이 여러 번 있으면 아래 줄이 이긴다
            }
        }
        if (plainMap.isNotEmpty()) {
            val alt = plainMap.keys.sortedByDescending { it.length }.joinToString("|") { esc(it) }
            plainRegex = Regex(alt)
        }
    }

    private fun norm(s: String): String =
        if (opt.normalize) Normalizer.normalize(s, Normalizer.Form.NFC) else s

    private fun esc(s: String): String {
        val meta = "\\^\$.|?*+()[]{}"
        val sb = StringBuilder()
        for (ch in s) {
            if (ch in meta) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    fun clean(text: String): String {
        var s = norm(text)

        plainRegex?.let { re -> s = re.replace(s) { m -> plainMap[m.value] ?: m.value } }

        for ((rule, re, repl) in regexRules) {
            if (rule.id in brokenRules) continue
            try {
                s = re.replace(s, repl)
            } catch (e: Exception) {
                // 바꿀 말의 $1 같은 그룹 참조가 잘못된 경우
                brokenRules.add(rule.id)
                problems.add("바꿀 말 오류: ${rule.find} -> ${rule.replace}")
            }
        }

        if (opt.compressChars) s = charRe.replace(s) { m -> m.groupValues[1].repeat(keep) }
        if (opt.compressWords) s = wordRe.replace(s) { m -> List(keep) { m.groupValues[1] }.joinToString(" ") }
        if (opt.compressUnits) s = unitRe.replace(s) { m -> m.groupValues[1].repeat(keep) }
        return s
    }
}
