package com.realestatenewsfinder.app

/**
 * 제목으로 주제 태그·관련성·스레드 추천 점수를 매김.
 * 클라우드 수집기(src/topics.js)와 같은 규칙. 휴대폰에서 직접 받아오는 글(뽐뿌 부동산포럼)에 사용.
 */
object Topics {
    private val TOPICS = listOf(
        "정책" to Regex("대책|규제|국토부|국토교통부|정부|법안|개정|시행령|토지거래허가|토허|공급\\s?확대|그린벨트|신도시|공시가|입법|국회|금융위|발표"),
        "청약·분양" to Regex("청약|분양|당첨|모델하우스|견본주택|입주자\\s?모집|특별공급|특공|줍줍|무순위|사전청약|로또\\s?아파트"),
        "시세" to Regex("집값|아파트값|매매가|전셋값|실거래|신고가|최고가|상승|하락|급등|급락|시세|호가|거래량|반등|보합|억\\s?(?:올|뛰|떨|내)"),
        "금리·대출" to Regex("금리|대출|주담대|DSR|LTV|DTI|한은|한국은행|기준금리|스트레스|가계부채|은행|보금자리|디딤돌|특례"),
        "재건축" to Regex("재건축|재개발|정비사업|리모델링|조합|시공사|수주|용적률|안전진단|1기\\s?신도시|선도지구|모아타운"),
        "전월세" to Regex("전세|월세|임대|전세사기|보증금|임차|세입자|집주인|갱신|깡통|역전세|보증보험"),
        "지원·혜택" to Regex("청년|신혼|신생아|출산|지원금|월세\\s?지원|바우처|혜택|특례|우대|감면|환급|생애\\s?최초|무주택"),
        "세금" to Regex("세금|양도세|양도소득세|취득세|종부세|종합부동산세|보유세|재산세|증여|상속|세제|공제|비과세"),
    )
    private val RELEVANT = Regex("부동산|아파트|주택|집값|전세|월세|청약|분양|재건축|재개발|주담대|주택담보|임대|오피스텔|빌라|다세대|입주|매매|공시가|양도세|취득세|종부세|신도시|GTX|토지거래|토허|건설사|미분양|LH|택지|정비사업|전셋값|갭투자|DSR|가계대출|디딤돌|보금자리")
    private val INTEREST = listOf(
        Regex("언제부터|부터\\s?시행|시행|내달|다음\\s?달|내년|이달|일정|마감|접수|신청|모집|공고|D-\\d") to 0.3,
        Regex("대상|자격|조건|요건|기준|무주택|청년|신혼|신생아|생애\\s?최초|1주택|다주택|실수요") to 0.25,
        Regex("얼마|한도|최대|최저|부담|이자|월\\s?\\d|분양가|보증금|세금|환급|감면|지원금|줄어|늘어|오른다|내린다|인상|인하") to 0.3,
        Regex("달라지|바뀌|바뀐|변경|개편|완화|강화|총정리|정리|Q&A|체크|주의|유의|피해|사기|방법|꿀팁|알아야|확인") to 0.3,
        Regex("청약|분양|대출|주담대|DSR|전세|월세|집값|아파트값|금리|양도세|취득세|종부세|재건축") to 0.2,
    )
    private val FIGURE = Regex("\\d+(?:\\.\\d+)?\\s?(?:억|만\\s?원|만원|천만|조|%|％|%p|배|가구|세대|명|대\\s?1|평|㎡|개월|년|월|일)")
    private val DULL = Regex("실적|영업이익|수주|MOU|협약|개최|참석|방문|수상|출시|인사|부고|기념|캠페인|봉사|주가|특징주|선정|성과발표|최우수|표창|간담회|설명회|박람회|업무협약")

    val names: List<String> = TOPICS.map { it.first }

    fun tagsOf(title: String): List<String> = TOPICS.filter { it.second.containsMatchIn(title) }.map { it.first }

    fun isRelevant(title: String) = RELEVANT.containsMatchIn(title)

    /** 0~100. 독자가 궁금해할 내용(대상·시행일·금액)과 구체적인 숫자가 있을수록 높음 */
    fun threadPick(title: String): Int {
        var w = INTEREST.filter { it.first.containsMatchIn(title) }.sumOf { it.second }
        w += minOf(FIGURE.findAll(title).count(), 2) * 0.15
        if (DULL.containsMatchIn(title)) w -= 0.4
        return Math.round(w.coerceIn(0.0, 1.0) * 100).toInt()
    }
}
