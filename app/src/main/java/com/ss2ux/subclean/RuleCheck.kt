package com.ss2ux.subclean

import java.text.Normalizer

/** 점검 결과의 심각도 */
enum class Severity(val label: String) {
    ERROR("오류"),   // 규칙이 아예 동작하지 않거나 깨짐
    WARN("경고"),    // 거의 확실히 의도와 다르게 동작함
    INFO("참고")     // 의도한 것일 수도 있음 (연쇄 적용, 우선순위 등)
}

/** 규칙 충돌 점검에서 찾은 문제 한 건 */
data class Finding(
    val severity: Severity,
    val kind: String,              // 문제 종류 코드 (같은 문제를 합칠 때 쓴다)
    val ruleIds: List<Long>,       // 관련된 규칙 id (눌러서 고칠 수 있게)
    val words: List<String>,       // 관련된 제외 단어
    val title: String,             // 한 줄 요약
    val detail: String,            // 쉬운 설명 + 고치는 방법
    val speeches: Set<Speech> = emptySet()   // 어느 말투를 골랐을 때 생기는지
) {
    val key: String get() = kind + "|" + ruleIds.sorted().joinToString(",") + "|" + words.sorted().joinToString(",")
}

/**
 * 규칙 충돌 점검 (UI 와 무관한 순수 로직).
 *
 * 실제 엔진(Cleaner)에 규칙의 찾을 말·바꿀 말을 넣어 보는 "시험 실행"과, 규칙 모양만 보는 정적 검사를 섞어서 쓴다.
 * 말투(공통/높임1/높임2/반말)는 같이 적용되는 묶음이 다르므로 말투별로 따로 검사해서 합친다.
 *
 * 찾는 문제:
 *  - 정규식 문법/바꿀 말 $ 표기 오류, 빈 글자에도 맞는 정규식
 *  - 바꾼 결과에 찾을 말이 다시 들어 있어 계속 불어나는 규칙 (안녕 -> 안녕하세요 -> 안녕하세요하세요 ...)
 *  - 서로 되돌리며 끝나지 않는 규칙들
 *  - 같은 찾을 말에 바꿀 말이 다른 규칙 (아래 규칙만 적용됨)
 *  - 제외 단어가 규칙의 찾을 말을 가려서 규칙이 안 먹는 경우
 *  - (참고) 긴 말 규칙이 짧은 말 규칙을 덮는 경우, 한 말에 여러 규칙이 연쇄로 걸리는 경우
 */
object RuleCheck {

    private const val MAX_INFO = 40

    /**
     * @param focusRule 이 규칙과 관련된 문제만 찾는다 (규칙을 저장하기 전 빠른 점검용). null 이면 전부.
     * @param includeInfo false 면 '참고' 단계 검사는 건너뛴다 (더 빠름)
     */
    fun check(
        rules: List<Rule>,
        opt: CleanOptions,
        focusRule: Long? = null,
        includeInfo: Boolean = true
    ): List<Finding> {
        val modes: List<Speech> = if (focusRule == null) Speech.values().toList() else {
            val r = rules.firstOrNull { it.id == focusRule }
            when {
                r == null -> emptyList()
                r.speech == Speech.ALL -> Speech.values().toList()
                else -> listOf(r.speech)
            }
        }
        val merged = LinkedHashMap<String, Finding>()
        for (mode in modes) {
            val scope = rules.filter {
                it.enabled && it.find.isNotEmpty() && (it.speech == Speech.ALL || it.speech == mode)
            }
            for (f in checkScope(scope, opt, mode, focusRule, includeInfo)) {
                val old = merged[f.key]
                merged[f.key] = if (old == null) f.copy(speeches = setOf(mode)) else old.copy(speeches = old.speeches + mode)
            }
        }
        val out = merged.values.sortedWith(compareBy({ it.severity.ordinal }, { it.kind }))
        // 참고 단계는 너무 많으면 화면이 길어지므로 줄인다
        val infos = out.filter { it.severity == Severity.INFO }
        return if (infos.size <= MAX_INFO) out else out.filter { it.severity != Severity.INFO } + infos.take(MAX_INFO)
    }

    /**
     * 제외 단어를 새로 넣기 전에: 이 단어들이 규칙을 가리는지만 본다 (가벼운 정적 검사).
     * 말투와 상관없이 켜진 규칙 전부를 대상으로 한다.
     */
    fun checkProtectWords(rules: List<Rule>, opt: CleanOptions, newWords: List<String>): List<Finding> {
        val norm = normalizer(opt)
        val words = newWords.map(norm).filter { it.isNotEmpty() }.distinct()
        if (words.isEmpty()) return emptyList()
        val scope = rules.filter { it.enabled && it.find.isNotEmpty() }
        val map = LinkedHashMap<String, Finding>()
        for (f in protectFindings(scope, words, norm)) {
            val old = map[f.key]
            map[f.key] = if (old == null) f else old.copy(speeches = old.speeches + f.speeches)
        }
        return map.values.sortedBy { it.severity.ordinal }
    }

    // ------------------------------------------------------------------

    private fun normalizer(opt: CleanOptions): (String) -> String =
        { s -> if (opt.normalize) Normalizer.normalize(s, Normalizer.Form.NFC) else s }

    private fun short(s: String, max: Int = 28): String =
        if (s.length <= max) s else s.substring(0, max) + "…"

    private fun label(r: Rule): String =
        "‘${short(r.find)}’ → ‘${if (r.replace.isEmpty()) "(삭제)" else short(r.replace)}’" +
            (if (r.regex) " (정규식)" else "")

    /** 괄호·앵커만 있고 나머지는 평범한 글자인 정규식이면, 그 글자를 시험 입력으로 쓴다 */
    private fun literalProbe(src: String): String? {
        var s = src.removePrefix("^").removeSuffix("$")
        s = s.replace("(?:", "").replace("(", "").replace(")", "")
        val meta = ".*+?[]{}|\\^$"
        if (s.isEmpty() || s.any { it in meta }) return null
        return s
    }

    /** 바꿀 말의 $ 표기가 이 정규식에서 쓸 수 없는 것이면 이유를 돌려준다 (Cleaner 가 실제로 적용될 때 내는 오류를 미리 찾는 것) */
    private fun badGroupRef(re: Regex, source: String, repl: String): String? {
        val groups = re.toPattern().matcher("").groupCount()
        var i = 0
        while (i < repl.length) {
            val c = repl[i]
            if (c == '\\') {
                if (i + 1 >= repl.length) return "바꿀 말이 \\ 로 끝나요"
                i += 2
                continue
            }
            if (c == '$') {
                if (i + 1 >= repl.length) return "바꿀 말이 \$ 로 끝나요"
                val d = repl[i + 1]
                if (d.isDigit()) {
                    if (d - '0' > groups) return "\$${d} 는 없는 묶음이에요 (찾을 말의 괄호 묶음은 ${groups}개)"
                    i += 2
                    continue
                }
                if (d == '{') {
                    val end = repl.indexOf('}', i + 2)
                    if (end < 0) return "\${ 가 닫히지 않았어요"
                    val name = repl.substring(i + 2, end)
                    if (!source.contains("(?<$name>")) return "이름이 ${name} 인 묶음이 없어요"
                    i = end + 1
                    continue
                }
                return "\$ 뒤에는 숫자나 {이름} 이 와야 해요"
            }
            i++
        }
        return null
    }

    private fun checkScope(
        scope: List<Rule>,
        opt: CleanOptions,
        mode: Speech,
        focus: Long?,
        includeInfo: Boolean
    ): List<Finding> {
        val norm = normalizer(opt)
        val out = ArrayList<Finding>()
        val plain = scope.filter { !it.regex }
        val regexes = scope.filter { it.regex }

        // ---- 1) 정규식 자체의 문제 ----
        for (r in regexes) {
            val src = norm(r.find)
            val repl = norm(r.replace)
            val re = try {
                Regex(HangulClass.expand(src))
            } catch (e: Exception) {
                val why = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                out.add(Finding(
                    Severity.ERROR, "REGEX_SYNTAX", listOf(r.id), emptyList(),
                    "정규식 문법이 잘못됐어요: ${label(r)}",
                    "이 규칙은 정규식이 잘못돼서 아예 적용되지 않아요." + (if (why.isNotEmpty()) " (원인: $why)" else "") +
                        " 규칙을 눌러 찾을 말을 고치거나, 일반 규칙이면 ‘정규식으로 처리’를 꺼 주세요."
                ))
                continue
            }
            badGroupRef(re, src, repl)?.let { why ->
                out.add(Finding(
                    Severity.ERROR, "REGEX_REPLACE", listOf(r.id), emptyList(),
                    "바꿀 말의 \$ 표기가 잘못됐어요: ${label(r)}",
                    "$why. 이대로면 규칙이 처음 걸리는 순간 오류가 나서 그 규칙은 꺼진 것처럼 동작해요. " +
                        "글자 \$ 를 그대로 쓰려면 \\\$ 라고 써 주세요."
                ))
            }
            val hasAnchorOrLook = Regex("""\(\?<?[=!]|\$|\^|\\b|\\B""").containsMatchIn(src)
            if (repl.isNotEmpty() && !hasAnchorOrLook && re.find("") != null) {
                out.add(Finding(
                    Severity.WARN, "REGEX_EMPTY", listOf(r.id), emptyList(),
                    "빈 곳에도 걸리는 정규식: ${label(r)}",
                    "이 정규식은 글자가 하나도 없는 곳에도 맞아서, 바꿀 말이 모든 글자 사이에 끼어들 수 있어요. " +
                        "*, ? 로만 이루어진 부분이 없는지 확인해 주세요."
                ))
            }
        }

        // ---- 2) 바꾼 결과에 찾을 말이 다시 들어 있는 일반 규칙 (자기 증식) ----
        val selfGrow = HashSet<Long>()
        for (r in plain) {
            val f = norm(r.find)
            val t = norm(r.replace)
            if (f == t || t.isEmpty() || !t.contains(f)) continue
            selfGrow.add(r.id)
            val hint = when {
                t.startsWith(f) -> "예: 찾을 말을 정규식 ${f}(?!${t.removePrefix(f)}) 로 바꾸면 이미 바뀐 곳은 건너뛰어요."
                t.endsWith(f) -> "예: 찾을 말을 정규식 (?<!${t.removeSuffix(f)})${f} 로 바꾸면 이미 바뀐 곳은 건너뛰어요."
                else -> "이미 바뀐 곳은 건너뛰도록 정규식(?<!…) (?!…) 조건을 붙여 주세요."
            }
            out.add(Finding(
                Severity.WARN, "SELF_GROW", listOf(r.id), emptyList(),
                "계속 불어나는 규칙: ${label(r)}",
                "바꾼 결과 안에 찾을 말 ‘${short(r.find)}’ 이 또 들어 있어서, 규칙이 결과가 안 바뀔 때까지(최대 ${Cleaner.MAX_PASSES}번) " +
                    "되풀이돼요. 예를 들어 ‘${short(t, 12)}’ 뒤에 같은 말이 여러 번 붙을 수 있어요. $hint"
            ))
        }
        // 정규식 중 글자 그대로 쓴 것도 같은 검사
        val probeOrigin = LinkedHashMap<String, MutableList<Long>>()
        val findProbes = HashSet<String>()   // 일반 규칙의 '찾을 말'에서 나온 시험 입력 (연쇄 안내는 이것만)
        fun addProbe(p: String, id: Long) {
            if (p.isNotEmpty()) probeOrigin.getOrPut(p) { ArrayList() }.add(id)
        }
        for (r in plain) {
            addProbe(norm(r.find), r.id)
            findProbes.add(norm(r.find))
            addProbe(norm(r.replace), r.id)
        }
        for (r in regexes) {
            val lit = literalProbe(norm(r.find)) ?: continue
            addProbe(lit, r.id)
            val t = norm(r.replace)
            if (t.isNotEmpty() && !t.contains('$') && t.contains(lit) && t != lit) selfGrow.add(r.id)
        }

        // ---- 3) 시험 실행: 끝나지 않는 규칙 / 연쇄 ----
        val tracer = Cleaner(scope, opt.copy(protect = emptyList(), speech = mode))
        val loopSeen = HashSet<String>()
        var infoCount = 0
        val focusRuleObj = if (focus == null) null else scope.firstOrNull { it.id == focus }
        val focusFind = focusRuleObj?.takeIf { !it.regex }?.let { norm(it.find) }
        val focusRepl = focusRuleObj?.takeIf { !it.regex }?.let { norm(it.replace) }
        for ((probe, origins) in probeOrigin) {
            // 저장 전 빠른 점검: 이 규칙(일반 규칙)과 관련 없는 시험 입력은 돌리지 않는다. 정규식은 어디에 걸릴지 몰라 전부 돌린다
            if (focusFind != null && focus !in origins &&
                !probe.contains(focusFind) && !(focusRepl!!.isNotEmpty() && probe.contains(focusRepl))) continue
            val t = tracer.trace(probe)
            if (t.passes.isEmpty()) continue
            if (t.hitLimit) {
                val involved = t.passes.takeLast(2).flatten().distinct()
                if (involved.isEmpty() || selfGrow.containsAll(involved)) continue
                val key = involved.sorted().joinToString(",")
                if (!loopSeen.add(key)) continue
                val names = involved.mapNotNull { id -> scope.firstOrNull { it.id == id } }.joinToString("\n") { "· " + label(it) }
                val single = involved.size == 1
                out.add(Finding(
                    Severity.WARN, "LOOP", involved, emptyList(),
                    if (single) "스스로 계속 반복되는 규칙" else "서로 계속 바꾸는 규칙",
                    "‘${short(probe, 20)}’ 을 다듬으면 규칙이 ${Cleaner.MAX_PASSES}번 돌아도 끝나지 않고 결과가 계속 달라져요. " +
                        (if (single) "바꾼 결과에 이 규칙이 또 걸리고 있어요. 찾을 말에 이미 바뀐 곳은 건너뛰는 조건(?!…) (?<!…)을 붙여 주세요."
                        else "아래 규칙들이 서로 되돌리고 있는지 확인해 주세요.") + "\n$names"
                ))
            } else if (includeInfo && infoCount < MAX_INFO) {
                // 한 말에 규칙이 이어서 걸린 경우(연쇄)만 알려 준다. 일반 규칙에서 나온 시험 입력일 때만 (노이즈 줄이기)
                val firstPass = t.passes[0]
                val ids = (t.passes.flatten()).distinct()
                val chained = t.passes.size >= 2 || firstPass.distinct().size >= 2
                if (chained && probe in findProbes && ids.size >= 2) {
                    val names = ids.take(5).mapNotNull { id -> scope.firstOrNull { it.id == id } }
                        .joinToString("\n") { "· " + label(it) }
                    out.add(Finding(
                        Severity.INFO, "CHAIN", ids.take(5), emptyList(),
                        "한 말에 규칙이 이어서 걸려요: ‘${short(probe, 16)}’ → ‘${short(t.result, 16)}’",
                        "‘${short(probe, 20)}’ 에 아래 규칙들이 차례로 적용돼서 최종 ‘${short(t.result, 20)}’ 이 돼요. " +
                            "의도한 연쇄라면 괜찮아요.\n$names"
                    ))
                    infoCount++
                }
            }
        }

        // ---- 4) 같은 찾을 말, 다른 바꿀 말 ----
        for ((find, group) in plain.groupBy { norm(it.find) }) {
            if (group.size < 2) continue
            val repls = group.map { norm(it.replace) }.distinct()
            if (repls.size < 2) continue
            val winner = group.last()
            out.add(Finding(
                Severity.WARN, "DUP_FIND", group.map { it.id }, emptyList(),
                "같은 찾을 말에 바꿀 말이 달라요: ‘${short(find)}’",
                "목록에서 가장 아래에 있는 ${label(winner)} 규칙만 적용되고 나머지는 무시돼요(말투가 달라도 같은 말투를 고르면 겹쳐요). " +
                    "필요 없는 규칙은 지우거나 꺼 주세요.\n" + group.joinToString("\n") { "· " + label(it) + (if (it.speech != Speech.ALL) " [${it.speech.ruleLabel}]" else "") }
            ))
        }
        for ((find, group) in regexes.groupBy { norm(it.find) }) {
            if (group.size < 2) continue
            val repls = group.map { norm(it.replace) }.distinct()
            if (repls.size < 2) continue
            out.add(Finding(
                Severity.WARN, "DUP_REGEX", group.map { it.id }, emptyList(),
                "같은 정규식에 바꿀 말이 달라요: ‘${short(find)}’",
                "정규식은 목록 순서대로 적용돼서, 앞 규칙이 먼저 바꾸면 뒤 규칙은 대개 걸리지 않아요. 하나만 남겨 주세요.\n" +
                    group.joinToString("\n") { "· " + label(it) }
            ))
        }

        // ---- 5) 제외 단어가 규칙을 가리는 경우 ----
        val words = opt.protect.map(norm).filter { it.isNotEmpty() }.distinct()
        if (words.isNotEmpty()) out.addAll(protectFindings(scope, words, norm))

        // ---- 6) 긴 말 규칙이 짧은 말 규칙을 덮는 경우 (참고) ----
        if (includeInfo) {
            var shadow = 0
            val plainN = plain.map { Triple(it, norm(it.find), norm(it.replace)) }
            loop@ for ((b, bf, br) in plainN) {
                for ((a, af, ar) in plainN) {
                    if (a.id == b.id || af == bf || af.isEmpty() || !bf.contains(af)) continue
                    val viaA = bf.replace(af, ar)
                    if (viaA == br) continue
                    out.add(Finding(
                        Severity.INFO, "SHADOW", listOf(a.id, b.id), emptyList(),
                        "긴 말 규칙이 우선: ‘${short(bf)}’ 안에서는 ‘${short(af)}’ 규칙이 안 쓰여요",
                        "일반 규칙은 긴 말이 먼저 적용돼요. ‘${short(bf)}’ 는 ${label(b)} 규칙 때문에 ‘${short(br)}’ 이 되고, " +
                            "짧은 규칙 ${label(a)} 였다면 ‘${short(viaA)}’ 이 됐을 거예요. 의도한 것이면 괜찮아요."
                    ))
                    if (++shadow >= MAX_INFO) break@loop
                }
            }
        }

        return if (focus == null) out else out.filter { focus in it.ruleIds }
    }

    /** 제외 단어 ↔ 규칙 찾을 말 (일반 규칙과, 글자 그대로 쓴 정규식) */
    private fun protectFindings(scope: List<Rule>, words: List<String>, norm: (String) -> String): List<Finding> {
        val out = ArrayList<Finding>()
        for (r in scope) {
            val target = if (r.regex) literalProbe(norm(r.find)) else norm(r.find)
            if (target.isNullOrEmpty()) continue
            for (w in words) {
                if (target.contains(w)) {
                    val same = target == w
                    out.add(Finding(
                        Severity.WARN, "PROTECT_BLOCKS", listOf(r.id), listOf(w),
                        "제외 단어 때문에 규칙이 안 먹어요: ‘${short(w)}’ ↔ ${label(r)}",
                        (if (same) "이 규칙의 찾을 말이 제외 단어 ‘${short(w)}’ 와 똑같아요."
                        else "이 규칙의 찾을 말 안에 제외 단어 ‘${short(w)}’ 이 들어 있어요.") +
                            " 제외 단어는 규칙보다 먼저 지켜지므로 이 규칙은 거의 적용되지 않아요. " +
                            "바꾸고 싶다면 설정에서 제외 단어를 빼고, 지키고 싶다면 이 규칙을 지워 주세요.",
                        setOf(r.speech)
                    ))
                } else if (w != target && w.contains(target)) {
                    out.add(Finding(
                        Severity.INFO, "PROTECT_PARTIAL", listOf(r.id), listOf(w),
                        "제외 단어 안에서는 규칙이 안 쓰여요: ‘${short(w)}’ ↔ ${label(r)}",
                        "‘${short(w)}’ 안에 들어 있는 ‘${short(target)}’ 은 제외 단어라서 ${label(r)} 규칙이 적용되지 않아요. " +
                            "그 단어만 지키려는 거라면 의도한 대로예요.",
                        setOf(r.speech)
                    ))
                }
            }
        }
        return out
    }
}
