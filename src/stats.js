// 통계 수집: 한국부동산원 R-ONE(REB_API_KEY 필요) + KB부동산 데이터허브 주택가격동향(키 없이 열리는 공개 조회 API).
// 결과는 out/stats.json → 앱의 '통계' 탭. 통계는 주 1회·월 1회만 바뀌므로 3시간에 한 번만 받는다.
import fs from 'node:fs';
import path from 'node:path';
import { fetchJson } from './fetcher.js';

const KB = 'https://data-api.kbland.kr/bfmstat/weekMnthlyHuseTrnd';
const REFRESH_MS = 3 * 3600_000;
const HISTORY = 12;

// KB 지역명 → 앱에 보여줄 이름. 목록에 없는 지역(6개광역시, (구)광주광역시)은 뺌
const REGION = {
  전국: '전국', 서울: '서울', 수도권: '수도권', 강북14개구: '서울 강북', 강남11개구: '서울 강남', '5개광역시': '5개 광역시', 기타지방: '기타 지방',
  부산: '부산', 대구: '대구', 인천: '인천', 광주: '광주', 대전: '대전', 울산: '울산', 세종: '세종', 경기: '경기', 강원: '강원',
  충북: '충북', 충남: '충남', 전북: '전북', '(구)전라남도': '전남', 경북: '경북', 경남: '경남', 제주: '제주',
};
/** 지역별 순위에 쓰는 시도 17곳 */
export const SIDO = ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주'];

const q = (o) => Object.entries(o).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
const round = (v, d) => (typeof v === 'number' && Number.isFinite(v) ? Math.round(v * 10 ** d) / 10 ** d : null);
const fmtDate = (s) => (s.length === 8 ? `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6)}` : `${s.slice(0, 4)}-${s.slice(4, 6)}`);

/**
 * KB 응답 → 시리즈. field가 있으면 dataList가 {기준날짜, [field]: 값} 객체 배열(시장동향), 없으면 숫자 배열(증감률)
 */
export function kbSeries(meta, data, field) {
  const dates = data.날짜리스트 ?? [];
  const n = Math.min(HISTORY, dates.length);
  const regions = [];
  for (const r of data.데이터리스트 ?? []) {
    const name = REGION[r.지역명];
    if (!name) continue;
    let values;
    if (field) {
      const byDate = new Map((r.dataList ?? []).map((d) => [d.기준날짜, d[field]]));
      values = dates.slice(-n).map((d) => round(byDate.get(d), meta.digits));
    } else {
      // 숫자 배열은 날짜리스트와 같은 순서 (길이가 다르면 앞쪽 날짜 기준으로 맞춤)
      values = dates.map((_, i) => round(r.dataList?.[i], meta.digits)).slice(-n);
    }
    if (values.at(-1) == null) continue;
    regions.push({ name, values });
  }
  return { ...meta, source: 'KB부동산', note: 'KB부동산 주택가격동향', date: fmtDate(dates.at(-1) ?? ''), dates: dates.slice(-n).map(fmtDate), regions };
}

/** PIR: 중간 가격(3분위) 주택 ÷ 중간 소득(3분위) 가구의 연소득 */
export function kbPir(meta, data) {
  const dates = data.날짜리스트 ?? [];
  const n = Math.min(HISTORY, dates.length);
  const regions = [];
  for (const r of data.데이터리스트 ?? []) {
    const name = REGION[r.지역명];
    const mid = r.주택분위구분?.find((h) => h.주택분위 === 3)?.소득분위구분?.find((s) => s.소득분위 === 3)?.dataList;
    if (!name || !mid) continue;
    regions.push({ name, values: dates.map((_, i) => round(mid[i], meta.digits)).slice(-n) });
  }
  return { ...meta, source: 'KB부동산', note: 'KB부동산 주택가격동향', date: fmtDate(dates.at(-1) ?? ''), dates: dates.slice(-n).map(fmtDate), regions };
}

const WEEK = { 월간주간구분코드: '02' };
const SERIES = [
  { meta: { id: 'kb-sale-wk', title: '아파트 매매가격 주간 변동률', short: '매매가', unit: '%', digits: 2, cycle: '주간', kind: 'change', desc: '지난주 대비 아파트 매매가격지수 변동률' }, path: 'prcIndxInxrdcRt', params: { ...WEEK, 매물종별구분: '01', 매매전세코드: '01' } },
  { meta: { id: 'kb-jeonse-wk', title: '아파트 전세가격 주간 변동률', short: '전세가', unit: '%', digits: 2, cycle: '주간', kind: 'change', desc: '지난주 대비 아파트 전세가격지수 변동률' }, path: 'prcIndxInxrdcRt', params: { ...WEEK, 매물종별구분: '01', 매매전세코드: '02' } },
  { meta: { id: 'kb-buyer-wk', title: '매수우위지수', short: '매수우위', unit: '', digits: 1, cycle: '주간', kind: 'level', base: 100, desc: '100보다 크면 사려는 사람이, 작으면 팔려는 사람이 많음 (0~200)' }, path: 'maktTrnd', params: { ...WEEK, 메뉴코드: '01' }, field: '매수우위지수' },
  { meta: { id: 'kb-jeonse-supply-wk', title: '전세수급지수', short: '전세수급', unit: '', digits: 1, cycle: '주간', kind: 'level', base: 100, desc: '100보다 클수록 전세 물량이 부족함 (0~200)' }, path: 'maktTrnd', params: { ...WEEK, 메뉴코드: '03' }, field: '전세수급지수' },
  { meta: { id: 'kb-outlook-mm', title: '매매가격 전망지수', short: '매매 전망', unit: '', digits: 1, cycle: '월간', kind: 'level', base: 100, desc: '중개업소 설문. 100보다 크면 상승 전망이 우세 (0~200)' }, path: 'maktTrnd', params: { 월간주간구분코드: '01', 메뉴코드: '05' }, field: '매매상승하락전망지수' },
  { meta: { id: 'kb-pir', title: 'PIR (소득 대비 집값)', short: 'PIR', unit: '배', digits: 1, cycle: '분기', kind: 'level', desc: '중간 소득 가구가 연소득을 한 푼도 안 쓰고 모아야 중간 가격 집을 살 수 있는 햇수' }, path: 'pir', params: { 메뉴코드: '01' }, pir: true },
];

// ---------- 한국부동산원 R-ONE (주간 아파트가격 동향) ----------
const RONE = 'https://www.reb.or.kr/r-one/openapi';
// R-ONE 지역명 → 앱 이름. 맨 위 단계 지역과 서울 강북·강남만 씀
const RONE_REGION = { 전국: '전국', 수도권: '수도권', 지방권: '지방', '5대광역시': '5개 광역시', 강북지역: '서울 강북', 강남지역: '서울 강남' };
for (const n of SIDO) RONE_REGION[n] = n;

async function roneCall(api, params, key) {
  const qs = Object.entries({ KEY: key, Type: 'json', ...params }).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  const res = await fetch(`${RONE}/${api}.do?${qs}`, { signal: AbortSignal.timeout(40000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const body = (await res.json())[api];
  const result = body?.[0]?.head?.[1]?.RESULT;
  if (!body) throw new Error('응답 형식이 다름');
  if (result && result.CODE !== 'INFO-000') throw new Error(result.MESSAGE || result.CODE);
  return { total: body[0]?.head?.[0]?.list_total_count ?? 0, rows: body[1]?.row ?? [] };
}

/** 통계표의 최근 weeks주치 행 (자료는 오래된 순이라 마지막 쪽부터 거꾸로 읽음) */
async function roneRecent(statId, weeks, key) {
  const size = 1000;
  const head = await roneCall('SttsApiTblData', { STATBL_ID: statId, DTACYCLE_CD: 'WK', pIndex: 1, pSize: 1 }, key);
  let page = Math.max(1, Math.ceil(head.total / size));
  const rows = [];
  const times = new Set();
  for (let i = 0; i < 12 && page >= 1 && times.size <= weeks + 1; i++, page--) {
    const r = await roneCall('SttsApiTblData', { STATBL_ID: statId, DTACYCLE_CD: 'WK', pIndex: page, pSize: size }, key);
    for (const x of r.rows) { rows.push(x); times.add(x.WRTTIME_IDTFR_ID); }
  }
  return rows;
}

/**
 * R-ONE 지역 경로 → 앱 이름과 상위 지역들.
 *  "서울>강남지역>동남권>강남구" → { name: '서울 강남구', parents: ['서울 동남권', '서울 강남', '서울'] }
 *  "경기>경부1권>성남시>분당구" → { name: '경기 성남시 분당구', parents: ['경기 성남시', '경기 경부1권', '경기'] }
 * 맨 위 단계(전국·수도권·시도 등)는 예전 이름 그대로. 안 쓰는 묶음(4대광역시·8개도 등)은 null
 */
export function roneRegion(full) {
  const parts = String(full ?? '').split('>').map((x) => x.trim()).filter(Boolean).filter((x) => x !== '전남광주' && x !== '광주전남');
  if (parts.length === 0) return null;
  if (parts.length === 1) return RONE_REGION[parts[0]] ? { name: RONE_REGION[parts[0]], parents: [] } : null;
  const sido = parts[0];
  if (!SIDO.includes(sido)) return null;
  const isZone = (x) => /권$|지역$/.test(x);
  // 경로의 각 단계 이름: 권역은 "시도 권역", 시군구는 권역을 뺀 "시도 시 구"
  const nameAt = (k) => {
    const last = parts[k];
    if (last === '강북지역' || last === '강남지역') return RONE_REGION[last];
    if (isZone(last)) return `${sido} ${last}`;
    return [sido, ...parts.slice(1, k + 1).filter((x) => !isZone(x))].join(' ');
  };
  const chain = [];
  for (let k = parts.length - 1; k >= 1; k--) chain.push(nameAt(k));
  chain.push(sido);
  return { name: chain[0], parents: chain.slice(1) };
}

/**
 * R-ONE 행 → 시리즈. change=true면 지수에서 전주 대비 변동률(%)을 계산.
 * itemRe: 여러 항목이 있는 표에서 쓸 항목 이름. 시군구·권역까지 모두 담고, parents에 상위 지역을 적음
 */
export function roneSeries(meta, rows, { change = false, itemRe = /지수|동향/ } = {}) {
  const item = [...new Set(rows.map((r) => r.ITM_NM))].find((n) => itemRe.test(n ?? ''));
  const byTime = new Map(); // 시점 → (지역 → 값)
  const dateOf = new Map();
  const parents = {};
  for (const r of rows) {
    if (item && r.ITM_NM !== item) continue;
    const reg = roneRegion(r.CLS_FULLNM || r.CLS_NM || '');
    if (!reg) continue;
    if (typeof r.DTA_VAL !== 'number') continue;
    if (reg.parents.length) parents[reg.name] = reg.parents;
    if (!byTime.has(r.WRTTIME_IDTFR_ID)) byTime.set(r.WRTTIME_IDTFR_ID, new Map());
    byTime.get(r.WRTTIME_IDTFR_ID).set(reg.name, r.DTA_VAL);
    if (r.WRTTIME_DESC) dateOf.set(r.WRTTIME_IDTFR_ID, r.WRTTIME_DESC);
  }
  const times = [...byTime.keys()].sort();
  const use = times.slice(change ? -(HISTORY + 1) : -HISTORY);
  const outTimes = change ? use.slice(1) : use;
  const names = [...byTime.get(times.at(-1) ?? '')?.keys() ?? []];
  // 맨 위 단계 지역을 앞에, 그 뒤에 하위 지역 (예전 앱은 앞쪽 이름만 씀)
  names.sort((a, b) => (parents[a] ? 1 : 0) - (parents[b] ? 1 : 0));
  const regions = names.map((name) => ({
    name,
    values: outTimes.map((t) => {
      const v = byTime.get(t)?.get(name);
      if (!change) return round(v, meta.digits);
      const before = byTime.get(use[use.indexOf(t) - 1])?.get(name);
      return typeof v === 'number' && typeof before === 'number' && before !== 0 ? round((v / before - 1) * 100, meta.digits) : null;
    }),
  })).filter((r) => r.values.at(-1) != null);
  const kept = new Set(regions.map((r) => r.name));
  const d = (t) => dateOf.get(t) || t;
  return {
    ...meta, source: '한국부동산원', note: '한국부동산원 R-ONE 주간아파트가격동향', date: d(outTimes.at(-1) ?? ''), dates: outTimes.map(d), regions,
    parents: Object.fromEntries(Object.entries(parents).filter(([n]) => kept.has(n))),
  };
}

const RONE_SERIES = [
  { meta: { id: 'reb-sale-wk', title: '아파트 매매가격 주간 변동률', short: '매매가', unit: '%', digits: 2, cycle: '주간', kind: 'change', desc: '지난주 대비 아파트 매매가격지수 변동률' }, stat: 'T244183132827305', change: true },
  { meta: { id: 'reb-jeonse-wk', title: '아파트 전세가격 주간 변동률', short: '전세가', unit: '%', digits: 2, cycle: '주간', kind: 'change', desc: '지난주 대비 아파트 전세가격지수 변동률' }, stat: 'T247713133046872', change: true },
  { meta: { id: 'reb-buy-supply-wk', title: '매매수급지수', short: '매매수급', unit: '', digits: 1, cycle: '주간', kind: 'level', base: 100, desc: '100보다 크면 사려는 사람이, 작으면 팔려는 사람이 많음 (0~200)' }, stat: 'T248163133074619' },
  { meta: { id: 'reb-jeonse-supply-wk', title: '전세수급지수', short: '전세수급', unit: '', digits: 1, cycle: '주간', kind: 'level', base: 100, desc: '100보다 클수록 전세를 구하는 사람이 많음 (0~200)' }, stat: 'T245423133086632' },
];

/** 이전 결과(prev)가 3시간 안쪽이면 그대로 둠. 일부 지표만 실패하면 그 지표는 이전 값을 유지 */
export async function collectStats(prev, now = Date.now(), key = process.env.REB_API_KEY || '') {
  const jobs = [
    ...(key ? RONE_SERIES.map((s) => ({ meta: s.meta, run: async () => roneSeries(s.meta, await roneRecent(s.stat, HISTORY + 1, key), { change: !!s.change }) })) : []),
    ...SERIES.map((s) => ({ meta: s.meta, run: async () => {
      const json = await fetchJson(`${KB}/${s.path}?${q(s.params)}`);
      const data = json?.dataBody?.data;
      if (!data?.날짜리스트?.length) throw new Error(json?.dataHeader?.message || '자료 없음');
      return s.pir ? kbPir(s.meta, data) : kbSeries(s.meta, data, s.field);
    } })),
  ];
  if (prev?.version === 2 && prev?.updatedAt && now - prev.updatedAt < REFRESH_MS && prev.series?.length === jobs.length
    && jobs.every((j) => prev.series.find((x) => x.id === j.meta.id)?.desc === j.meta.desc)) return prev; // 설명 문구가 바뀌면 바로 다시 받음
  const old = new Map((prev?.series ?? []).map((s) => [s.id, s]));
  const series = [];
  const errors = {};
  for (const j of jobs) {
    try {
      const out = await j.run();
      if (out.regions.length < 2) throw new Error('지역 자료 없음');
      series.push(out);
      console.log(`[stats] ${out.source} ${j.meta.title}: ${out.date}, 지역 ${out.regions.length}곳(${out.regions.map((r) => r.name).join(',')}), 전국 ${out.regions.find((r) => r.name === '전국')?.values.slice(-3)}, 서울 ${out.regions.find((r) => r.name === '서울')?.values.slice(-2)}`);
    } catch (e) {
      errors[j.meta.id] = e.message;
      console.log(`[stats] ${j.meta.title}(${j.meta.id}): 실패 (${e.message})`);
      if (old.has(j.meta.id)) series.push(old.get(j.meta.id));
    }
  }
  if (series.length === 0) return prev ?? null;
  // 하나라도 실패했으면 다음 실행 때 다시 시도하도록 시각을 갱신하지 않음
  // 지역 선택 화면용: 하위 지역 → 상위 지역들 (지표마다 따로 온 것을 하나로)
  const parents = {};
  for (const s of series) { Object.assign(parents, s.parents ?? {}); }
  return { version: 2, updatedAt: Object.keys(errors).length ? (prev?.updatedAt ?? 0) : now, checkedAt: now, sido: SIDO, parents, series: series.map(({ parents: _p, ...rest }) => rest), errors };
}

export async function runStats(outDir) {
  const file = path.join(outDir, 'stats.json');
  let prev = null;
  try { prev = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { /* 처음 */ }
  try {
    const next = await collectStats(prev);
    if (next) fs.writeFileSync(file, JSON.stringify(next));
  } catch (e) {
    console.log('[stats] 수집 실패:', e.message);
  }
}
