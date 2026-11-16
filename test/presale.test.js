import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parsePresaleList, parsePresaleDetail, lawdCodes, shortSido, collectPresale } from '../src/presale.js';

const listHtml = `<table><tbody>
 <tr data-pbno="2021001007" data-hmno="2021001007" data-honm="래미안 포레스티지"><td>부산</td><td>민영</td><td>분양주택</td><td class="txt_l"><b>래미안 포레스티지</b></td><td>삼성물산</td><td></td><td>2021-12-31</td><td>2022-01-10 ~ 2022-01-12</td><td>2022-01-19</td></tr>
 <tr data-pbno="2021001006" data-honm="다른 단지"><td>경기</td><td>민영</td><td>분양주택</td><td class="txt_l"><b>다른 단지</b></td><td></td><td></td><td>2021-12-30</td><td></td><td></td></tr>
</tbody></table>`;
const detailHtml = `<body>
 <table><caption>입주자모집공고 주요정보</caption><tr><td>공급위치</td><td>경기도 성남시 분당구 정자동 1번지</td></tr><tr><th>공급규모</th><td>1,236세대</td></tr></table>
 <table><caption>공급금액, 2순위 청약금 및 입주예정월</caption><tr><th>주택형</th><th>공급금액(최고가 기준)</th></tr>
  <tr><td>059.9800A</td><td>61,200</td><td rowspan="3">청약통장</td></tr><tr><td>084.9500A</td><td>84,300</td></tr><tr><td>084.9900B</td><td>86,000</td></tr></table>
 <p>* 입주예정월 : 2024.9</p></body>`;

test('분양 공고 목록과 상세: 주택형별 분양가', () => {
  const rows = parsePresaleList(listHtml);
  assert.deepEqual(rows[0], { id: '2021001007', hm: '2021001007', name: '래미안 포레스티지', sido: '부산', date: '2021-12-31' });
  assert.equal(rows[1].hm, '2021001006');
  assert.deepEqual(parsePresaleDetail(detailHtml), { addr: '경기도 성남시 분당구 정자동 1번지', total: 1236, movein: '2024-09', prices: { 59: [61200, 61200], 84: [84300, 86000] } });
});

test('공급위치 → 시군구 코드', () => {
  assert.equal(shortSido('전북특별자치도'), '전북');
  assert.equal(shortSido('충청남도'), '충남');
  assert.deepEqual(lawdCodes('경기도 성남시 분당구 정자동 1번지'), ['41135']);
  assert.deepEqual(lawdCodes('서울특별시 강남구 개포동 12'), ['11680']);
  assert.deepEqual(lawdCodes('경기도 과천시 주암동 일원'), ['41290']);
  assert.equal(lawdCodes('경기도 부천시 괴안동 2').length, 3); // 구가 주소에 없으면 그 시의 구 전부
  assert.equal(lawdCodes('세종특별자치시 산울동').length, 1);
  assert.deepEqual(lawdCodes('어디인지 모름'), []);
  assert.deepEqual(lawdCodes('창원시 마산회원구 합성2동 262-1번지 일원', '경남'), lawdCodes('경상남도 창원시 마산회원구 합성2동'));
  assert.equal(lawdCodes('창원시 마산회원구 합성2동', '경남').length, 1);
});

test('조금씩 쌓기: 한 번에 정한 건수만, 다음 실행은 이어서', async () => {
  const now = Date.UTC(2020, 2, 15); // 2020년 3월
  const months = { 202001: [{ id: 'a', hm: 'a', name: 'A', sido: '서울', date: '2020-01-05' }, { id: 'b', hm: 'b', name: 'B', sido: '서울', date: '2020-01-20' }], 202002: [], 202003: [{ id: 'c', hm: 'c', name: 'C', sido: '경기', date: '2020-03-02' }] };
  const opts = { now, maxDetails: 1, fetchList: async (ym) => months[ym] ?? [], fetchDetail: async (r) => ({ addr: '서울특별시 강남구 개포동', prices: r.id === 'b' ? {} : { 84: [100000, 110000] } }) };
  const s1 = await collectPresale(null, opts);
  assert.deepEqual(s1.items.map((i) => i.id), ['a']);
  assert.equal(s1.cursor, '202001'); // 1월을 다 못 봤으니 다음에도 1월부터
  assert.deepEqual(s1.items[0].lawd, ['11680']);
  const s2 = await collectPresale(s1, { ...opts, maxDetails: 5 });
  assert.deepEqual(s2.items.map((i) => i.id), ['c', 'a']); // 최근 공고가 앞
  assert.deepEqual(s2.empty, ['b']); // 분양가 없는 공고는 빼 두고 다시 받지 않음
  assert.equal(s2.cursor, '202003');
  assert.equal(await collectPresale(s2, opts), s2); // 따라잡은 뒤 6시간 안에는 다시 받지 않음
});
