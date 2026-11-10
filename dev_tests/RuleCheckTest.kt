// 규칙 충돌 점검 단독 테스트 (안드로이드 없이 kotlinc 만으로 실행)
// 컴파일: kotlinc ../app/src/main/java/com/ss2ux/subclean/{Cleaner,Josa,Speech,RuleCheck}.kt RuleCheckTest.kt -include-runtime -d t.jar
// 실행:  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar t.jar ../app/src/main/res/raw/speech_pack.tsv
import com.ss2ux.subclean.*
import java.io.File

fun unesc(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) { '\\' -> append('\\'); 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> { append(c); append(s[i + 1]) } }
            i += 2
        } else { append(c); i++ }
    }
}

fun loadTsv(text: String, firstId: Long): List<Rule> {
    val out = ArrayList<Rule>(); var id = firstId
    for (line in text.removePrefix("\uFEFF").split('\n')) {
        val l = line.trimEnd('\r')
        if (l.isBlank() || l.startsWith("#")) continue
        val cols = l.split('\t'); val find = unesc(cols[0]); if (find.isEmpty()) continue
        val repl = if (cols.size > 1) unesc(cols[1]) else ""
        val flags = if (cols.size > 2) cols[2].trim().uppercase() else ""
        val sp = when { "H1" in flags -> Speech.HONORIFIC1; "H2" in flags -> Speech.HONORIFIC2; 'H' in flags -> Speech.HONORIFIC2; 'C' in flags -> Speech.CASUAL; else -> Speech.ALL }
        out.add(Rule(id++, find, repl, 'R' in flags, '-' !in flags, sp))
    }
    return out
}

var fails = 0
fun expect(name: String, cond: Boolean) { if (!cond) { fails++; println("FAIL: $name") } else println("ok:   $name") }
fun kinds(f: List<Finding>) = f.map { it.kind }
fun has(f: List<Finding>, kind: String, vararg ids: Long) = f.any { it.kind == kind && it.ruleIds.containsAll(ids.toList()) }

fun main(args: Array<String>) {
    val opt = CleanOptions()
    fun R(id: Long, f: String, r: String, rx: Boolean = false, sp: Speech = Speech.ALL, on: Boolean = true) = Rule(id, f, r, rx, on, sp)

    // 1) 자기 증식
    var f = RuleCheck.check(listOf(R(1, "안녕", "안녕하세요")), opt)
    expect("자기증식(일반)", has(f, "SELF_GROW", 1))
    expect("자기증식 안내에 해결 정규식 포함", f.first { it.kind == "SELF_GROW" }.detail.contains("안녕(?!하세요)"))
    f = RuleCheck.check(listOf(R(1, "(안녕)", "\$1!", true)), opt)
    expect("자기증식(정규식 \$1! 로 계속 불어남)", has(f, "LOOP", 1) && f.first { it.kind == "LOOP" }.title.contains("스스로"))
    f = RuleCheck.check(listOf(R(1, "(?<![가-힣])안녕(?=[.!?]|\$)", "안녕하세요", true)), opt)
    expect("뒤를 확인하는 정규식(팩 방식)은 경고 없음", f.isEmpty())

    // 2) 왕복/끝나지 않음
    f = RuleCheck.check(listOf(R(1, "가", "나"), R(2, "나", "가")), opt)
    expect("왕복 규칙(LOOP)", has(f, "LOOP", 1, 2))

    // 3) 같은 찾을 말
    f = RuleCheck.check(listOf(R(1, "나", "저"), R(2, "나", "제")), opt)
    expect("같은 찾을 말 다른 바꿀 말", has(f, "DUP_FIND", 1, 2))
    f = RuleCheck.check(listOf(R(1, "나", "저"), R(2, "나", "저", sp = Speech.HONORIFIC1)), opt)
    expect("같은 내용이면 경고 없음", f.none { it.kind == "DUP_FIND" })
    f = RuleCheck.check(listOf(R(1, "나", "저", sp = Speech.HONORIFIC1), R(2, "나", "제", sp = Speech.CASUAL)), opt)
    expect("서로 같이 안 쓰이는 말투끼리는 경고 없음", f.none { it.kind == "DUP_FIND" })
    f = RuleCheck.check(listOf(R(1, "나", "저"), R(2, "나", "제", sp = Speech.HONORIFIC2)), opt)
    expect("공통 + 높임2 충돌 (높임2에서만)", has(f, "DUP_FIND", 1, 2) && f.first { it.kind == "DUP_FIND" }.speeches == setOf(Speech.HONORIFIC2))

    // 4) 제외 단어
    f = RuleCheck.check(listOf(R(1, "나는", "저는")), opt.copy(protect = listOf("나")))
    expect("제외 단어가 찾을 말에 들어 있음", has(f, "PROTECT_BLOCKS", 1))
    f = RuleCheck.check(listOf(R(1, "나라", "국가")), opt.copy(protect = listOf("나라")))
    expect("제외 단어 == 찾을 말", has(f, "PROTECT_BLOCKS", 1))
    f = RuleCheck.check(listOf(R(1, "나", "저")), opt.copy(protect = listOf("우리나라")))
    expect("제외 단어 안에서만 막힘(참고)", has(f, "PROTECT_PARTIAL", 1) && f.none { it.severity == Severity.WARN })
    f = RuleCheck.check(listOf(R(1, "(철수)", "민철", true)), opt.copy(protect = listOf("철수")))
    expect("글자 그대로인 정규식도 제외 단어 검사", has(f, "PROTECT_BLOCKS", 1))
    f = RuleCheck.checkProtectWords(listOf(R(1, "나는", "저는"), R(2, "하늘", "땅")), opt, listOf("나"))
    expect("제외 단어 추가 전 점검", has(f, "PROTECT_BLOCKS", 1) && f.none { 2L in it.ruleIds })

    // 5) 정규식 오류
    f = RuleCheck.check(listOf(R(1, "(가", "나", true)), opt)
    expect("정규식 문법 오류", has(f, "REGEX_SYNTAX", 1))
    f = RuleCheck.check(listOf(R(1, "(가)", "\$3", true)), opt)
    expect("없는 묶음 \$3", has(f, "REGEX_REPLACE", 1))
    f = RuleCheck.check(listOf(R(1, "가격", "\$", true)), opt)
    expect("단독 \$", has(f, "REGEX_REPLACE", 1))
    f = RuleCheck.check(listOf(R(1, "(가)(나)", "\$2\$1", true), R(2, "(?<x>가)", "\${x}!", true)), opt)
    expect("올바른 \$1 \${이름} 은 경고 없음", f.none { it.kind == "REGEX_REPLACE" })
    f = RuleCheck.check(listOf(R(1, "a*", "-", true)), opt)
    expect("빈 곳에도 맞는 정규식", has(f, "REGEX_EMPTY", 1))
    f = RuleCheck.check(listOf(R(1, "(?<=아)(?=\$)", "요", true), R(2, "^", "- ", true)), opt)
    expect("앵커/룩어라운드 정규식은 빈 곳 경고 없음", f.none { it.kind == "REGEX_EMPTY" })

    // 6) 연쇄 / 덮어쓰기 (참고)
    f = RuleCheck.check(listOf(R(1, "A", "B"), R(2, "B", "C")), opt)
    expect("연쇄(참고)", has(f, "CHAIN", 1, 2) && f.none { it.severity != Severity.INFO })
    f = RuleCheck.check(listOf(R(1, "나", "저"), R(2, "나는", "저는요")), opt)
    expect("긴 말이 짧은 말을 덮음(참고)", has(f, "SHADOW", 1, 2))
    f = RuleCheck.check(listOf(R(1, "나", "저"), R(2, "나는", "저는")), opt)
    expect("일관된 긴 말 규칙은 경고 없음", f.none { it.kind == "SHADOW" })

    // 7) 꺼진 규칙은 안 본다 / 말투 / focus
    f = RuleCheck.check(listOf(R(1, "안녕", "안녕하세요", on = false)), opt)
    expect("꺼진 규칙은 점검 안 함", f.isEmpty())
    val many = listOf(R(1, "안녕", "안녕하세요"), R(2, "나", "저"), R(3, "나", "제"))
    f = RuleCheck.check(many, opt, focusRule = 2)
    expect("focus: 해당 규칙 관련 문제만", f.all { 2L in it.ruleIds } && has(f, "DUP_FIND", 2, 3) && f.none { it.kind == "SELF_GROW" })

    // 8) 실제 규칙 팩: 거짓 경고가 없어야 한다
    if (args.isNotEmpty()) {
        val pack = loadTsv(File(args[0]).readText(), 1000)
        val t0 = System.nanoTime()
        val pf = RuleCheck.check(pack, opt)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("--- 규칙 팩 ${pack.size}개 점검: ${ms}ms, 결과 ${pf.size}건 " + pf.groupBy { it.severity }.mapValues { it.value.size })
        for (x in pf.filter { it.severity != Severity.INFO }) println("  [${x.severity.label}] ${x.title}\n      ${x.detail.replace("\n", " / ")}")
        expect("규칙 팩에 오류/경고 없음", pf.none { it.severity != Severity.INFO })

        // 팩 + 사용자 규칙 100개 + 일부 문제 규칙 : 속도
        val user = (1..150).map { R(5000L + it, "단어$it", "바뀐말$it") } +
            listOf(R(9001, "안녕", "안녕하세요"), R(9002, "하나", "둘"), R(9003, "둘", "하나"))
        val t1 = System.nanoTime()
        val all = RuleCheck.check(pack + user, opt.copy(protect = listOf("우리나라", "단어7")))
        val ms2 = (System.nanoTime() - t1) / 1_000_000
        println("--- 팩+사용자 규칙 ${pack.size + user.size}개 전체 점검: ${ms2}ms, " + all.groupBy { it.severity }.mapValues { it.value.size })
        expect("전체 점검에서 안녕 자기증식 발견", has(all, "SELF_GROW", 9001))
        expect("전체 점검에서 하나<->둘 왕복 발견", has(all, "LOOP", 9002, 9003))
        expect("전체 점검에서 제외단어(단어7) 영향 발견", all.any { it.kind.startsWith("PROTECT") && "단어7" in it.words })
        val t2 = System.nanoTime()
        val q = RuleCheck.check(pack + user, opt, focusRule = 9001, includeInfo = false)
        println("--- 저장 전 빠른 점검(focus 1개, 참고 제외): ${(System.nanoTime() - t2) / 1_000_000}ms, ${q.size}건")
        expect("빠른 점검에서도 자기증식 발견", has(q, "SELF_GROW", 9001))
    }
    println(if (fails == 0) "ALL OK" else "$fails 건 실패")
}
