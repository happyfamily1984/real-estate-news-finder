import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseLhList, parseApplyhome, parseApplyhomeDetail, parseLhDetail, parseShList, parseGhList, isRecruitNotice, eok } from '../src/apply.js';
import { sourceById } from '../src/sources.js';

const now = Date.parse('2026-11-16T03:00:00Z');

test('LH 청약플러스: 유형·지역·마감일, 마감 지난 공고 제외, 상세 주소', () => {
  const html = `<table><tbody>
   <tr><td>19</td><td class="mVw cate col1">분양주택</td><td class="mVw bbs_tit"><a href="javascript:" data-id1="0000061211" data-id2="02" data-id3="05" data-id4="05" class="wrtancInfoBtn"><span>의정부우정 A-1블록 공공분양주택 잔여세대 추가입주자모집공고 <em class="day">1일전</em></span></a></td><td class="mVw cate col2">경기도</td><td class="mVw"></td><td>2026.11.13</td><td>2026.12.10</td><td class="mVw stt noti">공고중</td><td>6,639</td></tr>
   <tr><td>18</td><td class="mVw cate col1">분양주택</td><td class="mVw bbs_tit"><a href="javascript:" data-id1="0000061000" data-id2="02" data-id3="05" data-id4="05" class="wrtancInfoBtn"><span>지난 공고</span></a></td><td class="mVw cate col2">강원특별자치도</td><td class="mVw"></td><td>2026.10.01</td><td>2026.11.10</td><td class="mVw stt">접수마감</td><td>10</td></tr>
  </tbody></table>`;
  const posts = parseLhList(html, sourceById['lh-sale'], now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].title, '[분양주택] 의정부우정 A-1블록 공공분양주택 잔여세대 추가입주자모집공고 (경기도, ~12/10 마감)');
  assert.equal(posts[0].url, 'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?mi=1027&panId=0000061211&ccrCnntSysDsCd=02&uppAisTpCd=05&aisTpCd=05');
  assert.equal(posts[0].summary, '분양주택 · 경기도 · 게시 2026.11.13 · 마감 2026.12.10 · 공고중');
  assert.equal(new Date(posts[0].publishedAt).toISOString(), '2026-11-13T03:00:00.000Z');
  // 임대 공고 소스는 임대 유형만
  assert.equal(parseLhList(html, sourceById.lh, now).length, 0);
});

test('청약홈 분양정보: 단지명·지역·청약기간, 접수 끝난 공고 제외', () => {
  const html = `<table><tbody>
   <tr data-pbno="2026000566" data-hmno="2026000566" data-honm="호반써밋 초량"><td>부산</td><td>민영</td><td>분양주택</td><td class="txt_l"><a href="#b"><b>호반써밋 초량</b></a></td><td class="word_cut">(주)호반건설</td><td><a href="tel:1670-3235">☎ 1670-3235</a></td><td>2026-11-13</td><td>2026-11-23 ~ 2026-11-25</td><td>2026-12-01</td><td><button>신청현황</button></td><td><button>경쟁률</button></td></tr>
   <tr data-pbno="2026000500" data-hmno="2026000500" data-honm="끝난 단지"><td>서울</td><td>민영</td><td>분양주택</td><td class="txt_l"><b>끝난 단지</b></td><td>(주)어디건설</td><td></td><td>2026-10-20</td><td>2026-11-02 ~ 2026-11-04</td><td>2026-11-11</td><td></td><td></td></tr>
  </tbody></table>`;
  const posts = parseApplyhome(html, sourceById.applyhome, now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].title, '[청약] 호반써밋 초량 (부산·민영) 11/23~11/25 접수');
  assert.equal(posts[0].url, 'https://www.applyhome.co.kr/ai/aia/selectAPTLttotPblancDetail.do?houseManageNo=2026000566&pblancNo=2026000566');
  assert.equal(posts[0].summary, '민영 분양주택 · 모집공고 2026-11-13 · 청약 2026-11-23 ~ 2026-11-25 · 당첨자 발표 2026-12-01 · (주)호반건설');
});

test('청약홈 상세: 위치·규모·분양가·일정·입주예정', () => {
  const html = `<body>
   <table><caption>입주자모집공고 주요정보</caption><tr><th colspan="2">호반써밋 초량</th></tr><tr><td>공급위치</td><td>부산광역시 동구 초량3동 659번지 일원</td></tr><tr><th>공급규모</th><td>236세대</td></tr></table>
   <table><caption>입주자모집공고 청약일정</caption><tr><th>모집공고일</th><td>2026-11-13 (서울신문 )</td></tr>
     <tr><th>특별공급</th><td>2026-11-23</td><td>2026-11-23</td><td>인터넷</td></tr><tr><th>1순위</th><td>2026-11-24</td><td>2026-11-24</td><td>인터넷</td></tr><tr><th>2순위</th><td>2026-11-25</td><td>2026-11-25</td><td>인터넷</td></tr>
     <tr><th>당첨자 발표일</th><td>2026-12-01 ( https://x.co.kr/ )</td></tr><tr><th>계약일</th><td>2026-12-14 ~ 2026-12-16</td></tr></table><p>* 특이사항 : 정비사업 * 특별공급 종류에 따라</p>
   <table><caption>입주자모집공고 공급대상</caption><tr><td>민영</td><td>059.9445A</td><td>82.4368</td><td>24</td><td>35</td><td>59</td></tr><tr><td>계</td><td>99</td><td>137</td><td>236</td></tr></table>
   <h5>특별공급 공급대상</h5>
   <table><caption>공급금액, 2순위 청약금</caption><tr><th>주택형</th><th>공급금액(최고가 기준)</th><th>2순위 청약금</th></tr>
     <tr><td>059.9445A</td><td>56,320</td><td>청약통장으로 청약(청약금 없음)</td></tr><tr><td>059.9942B</td><td>54,820</td></tr><tr><td>084.9763A</td><td>74,680</td></tr></table><p>* 입주예정월 : 2029.08 * 층별</p>
   <table><caption>입주자모집공고 기타사항</caption><tr><th>시행사</th><th>시공사</th><th>사업주체 전화번호</th></tr><tr><td>초량3구역주택재개발정비사업조합</td><td>(주)호반건설</td><td>1670-3235</td></tr></table></body>`;
  assert.deepEqual(parseApplyhomeDetail(html).details, [
    '공급위치: 부산광역시 동구 초량3동 659번지 일원',
    '공급규모: 236세대 (일반 99 · 특별 137)',
    '59㎡ 분양가: 5억4,820만~5억6,320만원',
    '84㎡ 분양가: 7억4,680만원',
    '청약일정: 특별공급 11/23 · 1순위 11/24 · 2순위 11/25',
    '당첨자 발표: 12/1 · 계약 12/14~12/16',
    '입주예정: 2029년 8월',
    '시행·시공: 초량3구역주택재개발정비사업조합 / (주)호반건설',
    '특이사항: 정비사업',
  ]);
  assert.equal(eok(38678), '3억8,678만원');
});

test('LH 상세: 소재지·공급 세대·분양가·접수 일정', () => {
  const html = `<body><div>공고일2026.11.13 마감일2026.12.10 문의처1600-1004</div><h3>공고내용</h3><p>■ 금회 공급하는 주택의 입주자모집공고일은 2026.11.13.(금)이며 잔여세대의 순번추첨 동호지정을 위한 공고입니다. ■ 금회 추가모집은 거주 지역, 소득 및 자산, 청약통장 가입여부를 불문합니다. ■ 공급일정 - 신청접수 : 2026.12.07(월) 10:00 ~ 2026.12.10(목) 17:00 - 동호지정 순번발표 : 2026.12.11(금)(16:00) - 동호지정 및 계약체결 : 2026.12.14(월)~12.18(금) ■ 문의전화 : 031-528-2220</p>
   <h3>공급정보</h3><p>의정부우정 A1 소재지 : 경기도 의정부시 녹양동 1 전용면적(㎡) : 59 총 세대수 : 538</p>
   <table><caption>주택형 안내 : 주택형, 전용면적(㎡), 세대수</caption><thead><tr><th>주택형</th><th>전용면적(㎡)</th><th>세대수</th><th>금회공급 세대수</th><th>평균분양가격(원)</th><th>인터넷청약</th><th>주택형안내</th></tr></thead><tbody><tr><th>59.0000O</th><td>59.34</td><td>538</td><td>433</td><td>386,784,000</td><td>청약신청전</td><td>상세보기</td></tr></tbody></table>
   <table><caption>공급일정 : 구분, 신청일시, 신청방법</caption><thead><tr><th>구분</th><th>신청일시</th><th>신청방법</th></tr></thead><tbody><tr><td>순번추첨 동호지정</td><td>2026.12.07 10:00 ~ 2026.12.10 </td><td>인터넷접수</td></tr></tbody></table></body>`;
  const r = parseLhDetail(html);
  assert.deepEqual(r.details, [
    '소재지: 경기도 의정부시 녹양동 1',
    '금회 공급: 433세대',
    '전용면적: 59.34㎡',
    '59㎡ 평균 분양가: 3억8,678만원',
    '순번추첨 동호지정 접수: 2026.12.07 ~ 2026.12.10 (인터넷접수)',
    '발표: 12/11',
    '계약: 12/14~12/18',
    '문의: 031-528-2220',
  ]);
  assert.match(r.note, /^금회 공급하는 주택의 입주자모집공고일은/);
});

test('SH 공고 게시판: 모집 공고만, 발표·안내 글 제외, 상세 주소·조회수', () => {
  const html = `<table><tbody>
   <tr><td>제목 내용 담당부서 전체</td></tr>
   <tr><td>1735</td><td class="txtL"><a href="#" onclick="javascript:getDetailView('313316');return false;" class="ellipsis ">제47차 장기전세주택(2025.04.30.) 예비 6차 입주대상자 발표</a></td><td>미리내집공급부</td><td class="num">2026-11-13</td><td class="num">4609</td></tr>
   <tr><td>1732</td><td class="txtL"><a href="#" onclick="javascript:getDetailView('313180');return false;" class="ellipsis ">홈페이지 등 공사 시스템 일시 중단 안내</a></td><td>시스템운영부</td><td class="num">2026-11-11</td><td class="num">2719</td></tr>
   <tr><td>1730</td><td class="txtL"><a href="#" onclick="javascript:getDetailView('312903');return false;" class="ellipsis icon">NEW [청년형] 특화형 매입임대주택 입주자 모집 공고(운영기관 : 한지붕 협동조합)</a></td><td>매입주택공급부</td><td class="num">2026-11-11</td><td class="num">14,139</td></tr>
  </tbody></table>`;
  assert.equal(isRecruitNotice('뉴홈 전용 디딤돌대출(토지임대부 분양주택) 출시 안내'), false);
  assert.equal(isRecruitNotice('고덕강일 3단지 토지임대부 분양주택 평균 건물분양가격 공개'), false);
  assert.equal(isRecruitNotice('[정정] 고덕강일지구 3단지 토지임대부 분양주택 입주자모집공고'), true);
  const posts = parseShList(html, sourceById.sh, now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].id, 'sh:312903');
  assert.equal(posts[0].title, '[SH 임대] [청년형] 특화형 매입임대주택 입주자 모집 공고(운영기관 : 한지붕 협동조합)');
  assert.equal(posts[0].url, 'https://www.i-sh.co.kr/main/lay2/program/S1T294C297/www/brd/m_247/view.do?seq=312903&multi_itm_seq=2');
  assert.equal(posts[0].views, 14139);
  assert.equal(posts[0].summary, '주택임대 공고 · 매입주택공급부 · 게시 2026-11-11');
  assert.equal(new Date(posts[0].publishedAt).toISOString(), '2026-11-11T03:00:00.000Z');
});

test('GH 공고 게시판: 주택 분류의 모집 공고만, 두 자리 연도 날짜', () => {
  const html = `<table><tbody>
   <tr><td class="number">공지</td><td class="category">택지</td><td class="title"><a href="?mode=view&amp;articleNo=65324&amp;article.offset=0&amp;articleLimit=40">GH 공급용지 알선 장려금 시행 안내</a></td><td class="department">판매총괄부</td><td class="date">26.11.06</td><td class="hit ">11995</td><td class="attach"></td></tr>
   <tr><td class="number">2304</td><td class="category">주택</td><td class="title"><a href="?mode=view&amp;articleNo=65345&amp;article.offset=0&amp;articleLimit=40">다산 메트로3단지 국민임대주택 예비입주자 모집공고</a></td><td class="department">건설임대공급2부</td><td class="date">26.11.13</td><td class="hit ">1396</td><td class="attach"><a href="?mode=download&amp;articleNo=65345&amp;attachNo=83667">첨부</a></td></tr>
   <tr><td class="number">2302</td><td class="category">상가</td><td class="title"><a href="?mode=view&amp;articleNo=65341&amp;article.offset=0&amp;articleLimit=40">다산진건 B5 분양주택 분양상가 모집공고</a></td><td class="department">주택분양부</td><td class="date">26.11.13</td><td class="hit ">616</td><td class="attach"></td></tr>
  </tbody></table>`;
  const posts = parseGhList(html, sourceById.gh, now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].title, '[GH] 다산 메트로3단지 국민임대주택 예비입주자 모집공고');
  assert.equal(posts[0].url, 'https://www.gh.or.kr/gh/announcement-of-salerental001.do?mode=view&articleNo=65345');
  assert.equal(posts[0].views, 1396);
  assert.equal(posts[0].summary, '분양·임대 공고 · 건설임대공급2부 · 게시 2026-11-13');
});
