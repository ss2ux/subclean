package com.ss2ux.subclean

import java.text.Normalizer

/** 치환 규칙 한 줄. 찾을 말이 정규식이면 regex = true */
data class Rule(
    val id: Long,
    val find: String,
    val replace: String,
    val regex: Boolean = false,
    val enabled: Boolean = true,
    val speech: Speech = Speech.ALL,  // 공통 / 높임1 / 높임2 / 반말. 공통이 아니면 그 말투를 고를 때만 적용
    val polish: Boolean = false       // 다듬기 전용: 1차 한글 복구 예시 추천에만 사용
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
    val polishExclude: List<String> = emptyList(), // 다듬기 제외: 1차 한글 복구(줄 변경) 검색에서 빼는 단어
    val speech: Speech = Speech.ALL    // 지금 고른 말투 (다듬기 탭 위쪽 칩). 이 말투와 공통 규칙만 적용한다
)

/** 규칙 점검(RuleCheck)이 엔진을 시험 실행할 때 남기는 기록. 실제 다듬기에는 쓰지 않는다 */
class CleanTrace {
    /** 글자가 실제로 바뀐 반복(pass)마다, 그때 바꾼 규칙 id 들 (순서대로) */
    val passes = ArrayList<List<Long>>()
    /** 최대 반복 횟수까지 돌았는데도 결과가 계속 달라졌으면 true (끝나지 않는 규칙) */
    var hitLimit = false
    /** 반복 압축 전의 결과 */
    var result = ""
}

/**
 * 오프라인 텍스트 다듬기 엔진. 순서:
 *  0) (옵션) NFC 정규화
 *  1) 일반 치환 + 정규식 치환을 결과가 안 바뀔 때까지 반복 (A→B→C 연쇄, 최대 MAX_PASSES)
 *     일반 규칙은 긴 말 우선. (옵션) 바뀐 말 뒤 조사 보정은 일반 규칙만
 *  2) 반복 압축 (한 글자 -> 단어 -> 여러 글자 덩어리) — 마지막에 한 번
 *
 * 숫자는 압축하지 않는다 (100000원 이 1000원 이 되면 안 되므로).
 */
class Cleaner(rules: List<Rule>, private val opt: CleanOptions) {

    /** 잘못된 정규식 규칙 등 사용자가 고쳐야 할 문제 */
    val problems = ArrayList<String>()

    private val plainMap = HashMap<String, String>()
    private val plainIds = HashMap<String, Long>()   // 일반 규칙 찾을 말 -> 규칙 id (점검 기록용)
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
                plainIds[find] = r.id
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
    private fun replacePlain(s: String, re: Regex, fired: MutableList<Long>? = null): String {
        val sb = StringBuilder(s.length + 16)
        var pos = 0
        while (true) {
            val m = re.find(s, pos) ?: break
            sb.append(s, pos, m.range.first)
            val repl = plainMap[m.value] ?: m.value
            if (fired != null) plainIds[m.value]?.let { fired.add(it) }
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

    /**
     * 치환을 결과가 더 이상 바뀌지 않을 때까지 반복한다 (A→B→C 연쇄).
     * 서로 바꾸는 규칙이 있으면 무한 루프를 막기 위해 최대 [MAX_PASSES] 번만 돈다.
     * 반복 압축은 마지막에 한 번만 적용한다.
     */
    fun clean(text: String): String = execute(text, null)

    /** 점검용: clean 과 똑같이 돌리되, 어떤 규칙이 몇 번째 반복에서 글자를 바꿨는지 기록해서 돌려준다 */
    fun trace(text: String): CleanTrace {
        val t = CleanTrace()
        execute(text, t)
        return t
    }

    private fun execute(text: String, trace: CleanTrace?): String {
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

        var passes = 0
        var changedLast = false
        do {
            val prev = s
            val fired = if (trace != null) ArrayList<Long>() else null
            plainRegex?.let { re -> s = replacePlain(s, re, fired) }
            for ((rule, re, repl) in regexRules) {
                if (rule.id in brokenRules) continue
                try {
                    val before = s
                    s = re.replace(s, repl)
                    if (fired != null && s != before) fired.add(rule.id)
                } catch (e: Exception) {
                    // 바꿀 말의 $1 같은 그룹 참조가 잘못된 경우
                    brokenRules.add(rule.id)
                    problems.add("바꿀 말 오류: ${rule.find} -> ${rule.replace}")
                }
            }
            passes++
            changedLast = s != prev
            if (fired != null && changedLast) trace!!.passes.add(fired)
            if (!changedLast) break
        } while (passes < MAX_PASSES)
        if (trace != null) {
            trace.hitLimit = changedLast && passes >= MAX_PASSES
            trace.result = s
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

    companion object {
        /** A→B→C 연쇄용. 10이면 일반적인 말투 변환에 충분하고, 왕복 규칙 무한 루프도 막는다 */
        const val MAX_PASSES = 10
    }
}
