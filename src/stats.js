// 통계 수집: KB부동산 데이터허브 주택가격동향 (키 없이 열리는 공개 조회 API).
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
  return { ...meta, source: 'KB부동산', date: fmtDate(dates.at(-1) ?? ''), dates: dates.slice(-n).map(fmtDate), regions };
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
  return { ...meta, source: 'KB부동산', date: fmtDate(dates.at(-1) ?? ''), dates: dates.slice(-n).map(fmtDate), regions };
}

const WEEK = { 월간주간구분코드: '02' };
const SERIES = [
  { meta: { id: 'kb-sale-wk', title: '아파트 매매가격 주간 변동률', short: '매매가', unit: '%', digits: 2, cycle: '주간', kind: 'change', desc: '지난주 대비 아파트 매매가격지수 변동률' }, path: 'prcIndxInxrdcRt', params: { ...WEEK, 매물종별구분: '01', 매매전세코드: '01' } },
  { meta: { id: 'kb-jeonse-wk', title: '아파트 전세가격 주간 변동률', short: '전세가', unit: '%', digits: 2, cycle: '주간', kind: 'change', desc: '지난주 대비 아파트 전세가격지수 변동률' }, path: 'prcIndxInxrdcRt', params: { ...WEEK, 매물종별구분: '01', 매매전세코드: '02' } },
  { meta: { id: 'kb-buyer-wk', title: '매수우위지수', short: '매수우위', unit: '', digits: 1, cycle: '주간', kind: 'level', base: 100, desc: '100보다 크면 사려는 사람이, 작으면 팔려는 사람이 많음 (0~200)' }, path: 'maktTrnd', params: { ...WEEK, 메뉴코드: '01' }, field: '매수우위지수' },
  { meta: { id: 'kb-jeonse-supply-wk', title: '전세수급지수', short: '전세수급', unit: '', digits: 1, cycle: '주간', kind: 'level', base: 100, desc: '100보다 클수록 전세 물량이 부족함 (0~200)' }, path: 'maktTrnd', params: { ...WEEK, 메뉴코드: '03' }, field: '전세수급지수' },
  { meta: { id: 'kb-outlook-mm', title: '매매가격 전망지수', short: '매매 전망', unit: '', digits: 1, cycle: '월간', kind: 'level', base: 100, desc: '중개업소 설문. 100보다 크면 상승 전망이 우세 (0~200)' }, path: 'maktTrnd', params: { 월간주간구분코드: '01', 메뉴코드: '05' }, field: '매매상승하락전망지수' },
  { meta: { id: 'kb-pir', title: 'PIR (소득 대비 집값)', short: 'PIR', unit: '배', digits: 1, cycle: '분기', kind: 'level', desc: '중간 가격 주택을 사려면 중간 소득 가구가 연소득을 몇 년 모아야 하는지' }, path: 'pir', params: { 메뉴코드: '01' }, pir: true },
];

/** 이전 결과(prev)가 3시간 안쪽이면 그대로 둠. 일부 지표만 실패하면 그 지표는 이전 값을 유지 */
export async function collectStats(prev, now = Date.now()) {
  if (prev?.updatedAt && now - prev.updatedAt < REFRESH_MS && prev.series?.length === SERIES.length) return prev;
  const old = new Map((prev?.series ?? []).map((s) => [s.id, s]));
  const series = [];
  const errors = {};
  for (const s of SERIES) {
    try {
      const json = await fetchJson(`${KB}/${s.path}?${q(s.params)}`);
      const data = json?.dataBody?.data;
      if (!data?.날짜리스트?.length) throw new Error(json?.dataHeader?.message || '자료 없음');
      const out = s.pir ? kbPir(s.meta, data) : kbSeries(s.meta, data, s.field);
      if (out.regions.length === 0) throw new Error('지역 자료 없음');
      series.push(out);
      console.log(`[stats] ${s.meta.title}: ${out.date}, 지역 ${out.regions.length}곳, 전국 ${out.regions.find((r) => r.name === '전국')?.values.at(-1)}`);
    } catch (e) {
      errors[s.meta.id] = e.message;
      console.log(`[stats] ${s.meta.title}: 실패 (${e.message})`);
      if (old.has(s.meta.id)) series.push(old.get(s.meta.id));
    }
  }
  if (series.length === 0) return prev ?? null;
  // 하나라도 실패했으면 다음 실행 때 다시 시도하도록 시각을 갱신하지 않음
  return { version: 1, updatedAt: Object.keys(errors).length ? (prev?.updatedAt ?? 0) : now, checkedAt: now, sido: SIDO, series, errors };
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
