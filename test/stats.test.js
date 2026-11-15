import { test } from 'node:test';
import assert from 'node:assert/strict';
import { kbSeries, kbPir, roneSeries } from '../src/stats.js';

test('KB 증감률: 날짜 순서대로 값, 지역 이름 정리, 필요 없는 지역 제외', () => {
  const data = { 날짜리스트: ['20261102', '20261109'], 데이터리스트: [
    { 지역명: '전국', dataList: [0.0883309, 0.0707641] },
    { 지역명: '강북14개구', dataList: [0.19278, 0.19157] },
    { 지역명: '(구)전라남도', dataList: [-0.0123, -0.031] },
    { 지역명: '(구)광주광역시', dataList: [0.1, 0.2] },
    { 지역명: '6개광역시', dataList: [0.1, 0.2] },
  ] };
  const s = kbSeries({ id: 'x', digits: 2 }, data);
  assert.equal(s.date, '2026-11-09');
  assert.deepEqual(s.dates, ['2026-11-02', '2026-11-09']);
  assert.deepEqual(s.regions, [
    { name: '전국', values: [0.09, 0.07] },
    { name: '서울 강북', values: [0.19, 0.19] },
    { name: '전남', values: [-0.01, -0.03] },
  ]);
});

test('KB 시장동향: 기준날짜로 값을 찾음', () => {
  const data = { 날짜리스트: ['20261102', '20261109'], 데이터리스트: [
    { 지역명: '서울', dataList: [{ 기준날짜: '20261109', 매수우위지수: 54.88228516 }, { 기준날짜: '20261102', 매수우위지수: 60.22433856 }] },
  ] };
  assert.deepEqual(kbSeries({ id: 'b', digits: 1 }, data, '매수우위지수').regions, [{ name: '서울', values: [60.2, 54.9] }]);
});

test('KB PIR: 3분위 주택 · 3분위 소득', () => {
  const data = { 날짜리스트: ['202605', '202606'], 데이터리스트: [
    { 지역명: '서울', 주택분위구분: [{ 주택분위: 5, 소득분위구분: [{ 소득분위: 3, dataList: [39.1, 39.3] }] }, { 주택분위: 3, 소득분위구분: [{ 소득분위: 5, dataList: [5, 5] }, { 소득분위: 3, dataList: [10.44, 10.51] }] }] },
  ] };
  const s = kbPir({ id: 'p', digits: 1 }, data);
  assert.equal(s.date, '2026-06');
  assert.deepEqual(s.regions, [{ name: '서울', values: [10.4, 10.5] }]);
});

test('R-ONE: 지수에서 전주 대비 변동률 계산, 맨 위 단계 지역만', () => {
  const row = (t, d, nm, full, v) => ({ WRTTIME_IDTFR_ID: t, WRTTIME_DESC: d, CLS_NM: nm, CLS_FULLNM: full, ITM_NM: '지수', DTA_VAL: v });
  const rows = [
    row('202645', '2026-11-02', '전국', '전국', 100), row('202646', '2026-11-09', '전국', '전국', 100.07),
    row('202645', '2026-11-02', '서울', '서울', 104), row('202646', '2026-11-09', '서울', '서울', 104.104),
    row('202645', '2026-11-02', '강북지역', '서울>강북지역', 103), row('202646', '2026-11-09', '강북지역', '서울>강북지역', 103.206),
    row('202645', '2026-11-02', '지방권', '지방권', 99), row('202646', '2026-11-09', '지방권', '지방권', 98.98),
    row('202646', '2026-11-09', '은평구', '서울>강북지역>서북권>은평구', 103.8),
    row('202646', '2026-11-09', '광주', '경기>동부1권>광주', 101),
    row('202645', '2026-11-02', '전남', '광주전남>전남', 100), row('202646', '2026-11-09', '전남', '광주전남>전남', 99.95),
  ];
  const s = roneSeries({ id: 'r', digits: 2 }, rows, { change: true });
  assert.equal(s.date, '2026-11-09');
  assert.deepEqual(s.dates, ['2026-11-09']);
  assert.deepEqual(s.regions, [
    { name: '전국', values: [0.07] }, { name: '서울', values: [0.1] }, { name: '서울 강북', values: [0.2] }, { name: '지방', values: [-0.02] }, { name: '전남', values: [-0.05] },
  ]);
  const lv = roneSeries({ id: 'l', digits: 1 }, rows.map((r) => ({ ...r, ITM_NM: '매매수급지수' })));
  assert.deepEqual(lv.regions[0], { name: '전국', values: [100, 100.1] });
});
