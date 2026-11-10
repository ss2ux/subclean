package com.ss2ux.subclean

/**
 * 1차 다듬기에서 생긴 한글을 DeepL이 바꾼 뒤에도 지킬 수 있게 하는 도우미.
 *
 * - 1차 다듬기 후 원본 덮어쓰기 때: 한글 연속 구간을 순서대로 기억
 * - DeepL 결과를 반영한 뒤 파일을 다시 불러오면: 1차 한글과 비교해서
 *   달라진 부분만 찾아, 1차 한글을 쓸지 현재(DeepL) 한글을 쓸지 체크박스로 고르게 한다
 */

/** 한 개의 한글 구간이 바뀐 정보 */
data class KoreanDiff(
    val runIndex: Int,      // 전체 텍스트에서 몇 번째 한글 구간인지 (0부터)
    val original: String,   // 1차 다듬기 때 기억한 한글
    val current: String,    // 지금 파일에 있는 값 (DeepL 반영 후)
    val lineHint: Int? = null  // 대략적인 줄 번호 (가능하면)
)

/** 한글(완성형 + 자모) 연속 구간을 찾는 정규식 */
private val HANGUL_RUN = Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]+")

/** 텍스트에서 한글 연속 구간을 등장 순서대로 추출 */
fun extractHangulRuns(text: String): List<String> =
    HANGUL_RUN.findAll(text).map { it.value }.toList()

/**
 * 기억해 둔 원본 목록과 현재 목록을 순서 기준으로 비교해서
 * 내용이 다른 것만 KoreanDiff 리스트로 반환한다.
 * 개수가 다르면 짧은 쪽까지만 비교하고, 남는 쪽은 별도로 표시하지 않는다
 * (DeepL이 한글을 아예 지운 경우는 복원이 애매해서 제외).
 */
fun findKoreanDiffs(originals: List<String>, currents: List<String>): List<KoreanDiff> {
    val n = minOf(originals.size, currents.size)
    val diffs = ArrayList<KoreanDiff>(n)
    for (i in 0 until n) {
        if (originals[i] != currents[i]) {
            diffs.add(KoreanDiff(i, originals[i], currents[i]))
        }
    }
    return diffs
}

/**
 * 텍스트 안의 한글 구간을 순서대로 돌면서,
 * selectedRunIndexes 에 들어 있는 구간의 내용을 원본으로 바꾼다.
 * 나머지 구간은 그대로 둔다.
 */
fun restoreHangulRuns(
    text: String,
    originals: List<String>,
    selectedRunIndexes: Set<Int>
): String {
    if (selectedRunIndexes.isEmpty() || originals.isEmpty()) return text
    val sb = StringBuilder()
    var last = 0
    var runIdx = 0
    for (m in HANGUL_RUN.findAll(text)) {
        sb.append(text, last, m.range.first)
        val replacement = if (runIdx in selectedRunIndexes && runIdx < originals.size) {
            originals[runIdx]
        } else {
            m.value
        }
        sb.append(replacement)
        last = m.range.last + 1
        runIdx++
    }
    sb.append(text, last, text.length)
    return sb.toString()
}
