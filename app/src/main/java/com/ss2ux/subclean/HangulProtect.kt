package com.ss2ux.subclean

/**
 * 1차 다듬기에서 생긴 한글을 줄 단위로 기억하고,
 * DeepL 반영 후 해당 줄에 그 단어가 남아 있는지 검사한다.
 *
 * 순서 매칭을 쓰지 않는다. 같은 줄 안에서 "1차 때 있던 단어가 지금 있는지"만 본다.
 */

/** 한글(완성형 + 자모) 연속 구간 */
private val HANGUL_RUN = Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]+")

fun extractHangulRuns(text: String): List<String> =
    HANGUL_RUN.findAll(text).map { it.value }.toList()

/**
 * 1차 다듬기 때 한 줄에 들어 있던 한글 단어 기록.
 * lineIndex 는 Doc.lines 기준 인덱스.
 */
data class FirstPassLineKorean(
    val lineIndex: Int,
    val words: List<String>   // 그 줄에 있던 한글 구간들 (등장 순서)
)

/**
 * 1차 때 있던 단어가 현재 줄에서 사라진 경우.
 * 사용자가 체크하면 [word] 를 그 줄에 반영(또는 직접 수정한 값으로 반영)한다.
 */
data class MissingKorean(
    val id: Int,              // UI 선택용 고유 id
    val lineIndex: Int,
    val word: String,         // 1차 때 있던 단어 (기본 복원값)
    val currentLine: String   // 지금 그 줄의 본문 (참고용)
)

/**
 * 1차 기록과 현재 편집 가능 줄들을 비교해서,
 * 1차 때 있던 단어가 해당 줄에 더 이상 포함되지 않는 것만 모은다.
 *
 * - 줄 번호가 그대로라고 가정 (SRT 본문만 바뀌는 일반적인 경우)
 * - 단어가 현재 줄 문자열에 substring 으로 들어 있으면 "남아 있음"으로 본다
 */
fun findMissingKoreans(
    firstPass: List<FirstPassLineKorean>,
    currentBodiesByLine: Map<Int, String>
): List<MissingKorean> {
    val out = ArrayList<MissingKorean>()
    var id = 0
    for (fp in firstPass) {
        val current = currentBodiesByLine[fp.lineIndex] ?: continue
        for (w in fp.words) {
            if (w.isBlank()) continue
            if (!current.contains(w)) {
                out.add(MissingKorean(id++, fp.lineIndex, w, current))
            }
        }
    }
    return out
}

/**
 * 선택한 항목들을 해당 줄에 반영한다.
 * 기본 전략: 줄 끝(또는 줄이 비어 있으면 전체)에 단어를 붙이지 않고,
 * 사용자가 지정한 교체 문자열로 줄 전체를 바꿀 수 있게 한다.
 *
 * 여기서는 "이 줄의 본문을 replacement 로 교체" 방식을 쓴다.
 * UI에서 기본값을 현재 줄 + 필요 시 1차 단어로 편집할 수 있게 한다.
 */
fun applyLineReplacements(
    bodies: List<String>,
    editableFlags: List<Boolean>,
    replacements: Map<Int, String>   // lineIndex → 새 본문
): List<String> {
    return bodies.mapIndexed { i, body ->
        if (editableFlags.getOrElse(i) { false } && i in replacements) {
            replacements[i]!!
        } else {
            body
        }
    }
}
