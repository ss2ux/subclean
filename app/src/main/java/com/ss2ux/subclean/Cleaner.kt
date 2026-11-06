package com.ss2ux.subclean

import java.text.Normalizer

/** 치환 규칙 한 줄. 찾을 말이 정규식이면 regex = true */
data class Rule(
    val id: Long,
    val find: String,
    val replace: String,
    val regex: Boolean = false,
    val enabled: Boolean = true,
    val speech: Speech = Speech.ALL   // 공통 / 높임 / 반말. 공통이 아니면 그 말투를 고를 때만 적용
)

/** 반복 압축 등 다듬기 옵션 (설정 화면에서 바꾼다) */
data class CleanOptions(
    val threshold: Int = 5,            // 같은 글자/단어가 이 횟수 이상 이어지면 압축
    val keep: Int = 2,                 // 압축하고 남길 개수
    val normalize: Boolean = true,     // 유니코드 NFC 정규화 (맥에서 만든 한글 자막의 분리된 자모 등을 합침)
    val compressChars: Boolean = true, // ㅋㅋㅋㅋㅋ -> ㅋㅋ
    val compressWords: Boolean = true, // 응 응 응 응 응 -> 응 응
    val compressUnits: Boolean = false, // 하하하하 하하... 같은 2~4글자 덩어리 반복 (띄어쓰기 없는 중/일어용, 실험적)
    val fixJosa: Boolean = true,       // 치환 뒤 조사 보정: 철수가 -> (영수로 바꾸면) 영수가, (민철로 바꾸면) 민철이
    val protect: List<String> = emptyList(), // 제외 단어: 이 말이 들어 있는 부분은 어떤 규칙·압축도 건드리지 않는다
    val speech: Speech = Speech.ALL    // 지금 고른 말투 (다듬기 탭 위쪽 칩). 이 말투와 공통 규칙만 적용한다
)

/**
 * 오프라인 텍스트 다듬기 엔진. 순서:
 *  0) (옵션) NFC 정규화
 *  1) 일반 치환 규칙 전부를 한 번에: 긴 말부터 우선, 치환 결과가 다시 치환되는 연쇄는 없음
 *     (옵션) 바뀐 말 바로 뒤의 조사를 새 말의 받침에 맞게 보정 (일반 규칙만; 정규식 규칙은 대상 아님)
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

    private val fixJosa = opt.fixJosa
    private val n = opt.threshold.coerceIn(3, 50)
    private val keep = opt.keep.coerceIn(1, n - 1)
    private val charRe = Regex("([^\\p{Nd}\\s])\\1{${n - 1},}")
    private val wordRe = Regex("(?<!\\S)(\\S+)(?:[ \\t\\u3000]+\\1){${n - 1},}(?!\\S)")
    private val unitRe = Regex("([^\\p{Nd}\\s]{2,4}?)\\1{${n - 1},}")

    // 제외 단어: 치환 전에 칸막이 글자(사용자 지정 영역 문자)로 바꿔 두었다가 마지막에 되돌린다
    private val protectRe: Regex? = opt.protect.map { norm(it) }.filter { it.isNotEmpty() }.distinct()
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.let { w -> Regex(w.joinToString("|") { esc(it) }) }
    private val PUA = 0xE000
    private val PUA_MAX = 6000

    init {
        for (r in rules) {
            if (!r.enabled || r.find.isEmpty()) continue
            val find = norm(r.find)
            val repl = norm(r.replace)
            if (r.regex) {
                try {
                    regexRules.add(Triple(r, Regex(HangulClass.expand(find)), repl))
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

    /** 일반 규칙 치환. 바뀐 말 뒤에 조사가 붙어 있으면 받침에 맞게 같이 고친다 */
    private fun replacePlain(s: String, re: Regex): String {
        val sb = StringBuilder(s.length + 16)
        var pos = 0
        while (true) {
            val m = re.find(s, pos) ?: break
            sb.append(s, pos, m.range.first)
            val repl = plainMap[m.value] ?: m.value
            sb.append(repl)
            var next = m.range.last + 1
            if (fixJosa && repl != m.value) {
                val j = Josa.fix(s, next, repl)
                if (j != null) {
                    sb.append(j.first)
                    next = j.second
                }
            }
            pos = next
        }
        if (pos < s.length) sb.append(s, pos, s.length)
        return sb.toString()
    }

    fun clean(text: String): String {
        var s = norm(text)

        // 제외 단어 보호 (줄에 원래 칸막이 글자가 있으면 보호하지 않는다)
        var saved: ArrayList<String>? = null
        if (protectRe != null && s.none { it.code in PUA..(PUA + PUA_MAX) }) {
            val list = ArrayList<String>()
            s = protectRe.replace(s) { m ->
                if (list.size >= PUA_MAX) m.value
                else { list.add(m.value); (PUA + list.size - 1).toChar().toString() }
            }
            if (list.isNotEmpty()) saved = list
        }

        plainRegex?.let { re -> s = replacePlain(s, re) }

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

        saved?.let { list ->
            val sb = StringBuilder(s.length + 16)
            for (ch in s) {
                val k = ch.code - PUA
                if (k in list.indices) sb.append(list[k]) else sb.append(ch)
            }
            s = sb.toString()
        }
        return s
    }
}
