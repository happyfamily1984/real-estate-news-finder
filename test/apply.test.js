import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseLhList, parseApplyhome } from '../src/apply.js';
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
