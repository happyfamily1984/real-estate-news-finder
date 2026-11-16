import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseDineOut, collectDineOut } from '../src/prices.js';

test('참가격 외식비 표에서 서울 품목별 값', () => {
  const html = `<table><tr><th>일자</th><td>2009 2010</td></tr></table>
   <table><thead><tr><th>지역</th><th>냉면</th><th>비빔밥</th><th>김치찌개백반</th><th>삼겹살</th><th>자장면</th><th>삼계탕</th><th>칼국수</th><th>김밥</th></tr></thead>
   <tbody><tr><td>부산광역시</td><td>11,000</td><td>9,000</td><td>8,000</td><td>16,000</td><td>6,500</td><td>16,000</td><td>8,000</td><td>3,000</td></tr>
   <tr><td>서울특별시</td><td>12,423</td><td>11,577</td><td>8,577</td><td>20,639</td><td>7,654</td><td>18,000</td><td>9,846</td><td>3,646</td></tr></tbody></table>`;
  assert.deepEqual(parseDineOut(html), { 냉면: 12423, 비빔밥: 11577, 김치찌개백반: 8577, 삼겹살: 20639, 자장면: 7654, 삼계탕: 18000, 칼국수: 9846, 김밥: 3646 });
  assert.equal(parseDineOut(html, '부산').자장면, 6500);
  assert.equal(parseDineOut('<table><tr><td>없음</td></tr></table>'), null);
});

test('해마다 같은 달 값 모으기, 발표 안 된 달은 앞 달로', async () => {
  const now = Date.UTC(2011, 10, 16); // 2011년 11월
  const data = { '2011-10': { 자장면: 4200, 김밥: 2800 }, '2010-10': { 자장면: 4000, 김밥: 2500 }, '2009-12': { 자장면: 3800 } };
  const fetchOne = async (y, m) => data[`${y}-${m}`] ?? null;
  const r = await collectDineOut(null, { now, fetchOne });
  assert.equal(r.latest, '2011-10'); // 11월은 아직 없어서 10월
  assert.deepEqual(r.items, { 자장면: { 2011: 4200, 2010: 4000, 2009: 3800 }, 김밥: { 2011: 2800, 2010: 2500 } }); // 2009년 10월이 없으면 그 해 12월
  assert.equal(await collectDineOut(r, { now: now + 86400_000, fetchOne }), r); // 7일 안에는 다시 받지 않음
});
