// 정책브리핑(korea.kr) 목록 페이지 수집.
// korea.kr는 2026-07-01부터 RSS 제공을 종료해서(/rss/*.xml → 404) 목록 HTML을 읽는다.
// 보도자료 목록에는 모든 부처 글이 섞여 있으므로 부동산 관련 제목만 남기고, 부처 이름을 매체명(outlet)으로 쓴다.
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';
import { isRelevant } from './topics.js';

const DAY = 24 * 3600_000;
const KST = 9 * 3600_000;

/** "2026.11.15" → 그날 낮 12시(KST). 오늘 날짜면 null (처음 수집한 시각을 대신 씀) */
function dateOf(text, now) {
  const m = text.match(/(20\d{2})[.\/-]\s?(\d{1,2})[.\/-]\s?(\d{1,2})/);
  if (!m) return undefined;
  const noon = Date.UTC(+m[1], +m[2] - 1, +m[3], 12) - KST;
  const today = Math.floor((now + KST) / DAY);
  return Math.floor((noon + KST) / DAY) >= today ? null : noon;
}

const MAX_AGE_MS = 96 * 3600_000; // 보도자료는 주말에 거의 없어서 4일치까지

/** sample: 넘기면 거르기 전 제목을 담아줌 (구조 확인용 로그) */
export function parseKoreaList(html, source, now = Date.now(), sample = null) {
  const $ = cheerio.load(html);
  const out = [];
  const seen = new Set();
  $('a[href*="View.do"][href*="newsId="]').each((_, el) => {
    const a = $(el);
    const id = (a.attr('href').match(/newsId=(\d+)/) || [])[1];
    if (!id || seen.has(id)) return;
    const box = a.closest('li').length ? a.closest('li') : a;
    const clean = (t) => t.replace(/\s+/g, ' ').trim();
    const title = clean(a.find('strong').first().text() || box.find('strong, .tit, .title').first().text() || a.attr('title') || a.text());
    if (title.length < 6) return;
    seen.add(id);
    if (sample) sample.push(title);
    if (source.filter && !isRelevant(title)) return;

    const sourceText = clean(box.find('.source, .info, .date').first().text() || box.text());
    const publishedAt = dateOf(sourceText, now);
    if (publishedAt && now - publishedAt > MAX_AGE_MS) return;
    // 출처 칸: <span class="source"><span>2026.11.15</span><span>국토교통부</span></span>
    const dept = clean(box.find('.source span').last().text());
    const lead = clean(box.find('.lead, .txt').first().text());
    const path = new URL(a.attr('href'), source.url).pathname;
    const url = `https://www.korea.kr${path}?newsId=${id}`;
    const post = { id: `${source.id}:${id}`, source: source.id, title, url, views: null, comments: null, likes: null, publishedAt: publishedAt ?? null };
    if (dept && !/\d{4}/.test(dept) && dept.length <= 20) post.outlet = dept;
    if (lead && lead !== title) post.summary = lead.slice(0, 200);
    out.push(post);
  });
  return out;
}

export async function fetchKorea(source) {
  const all = [];
  const seen = new Set();
  let total = 0;
  const sample = [];
  for (let page = 1; page <= (source.pages ?? 1); page++) {
    const html = await fetchHtml(`${source.url}?pageIndex=${page}`);
    const links = (html.match(/View\.do\?newsId=/g) || []).length;
    total += links;
    if (links === 0) {
      // 구조가 바뀌었을 때 원인을 찾을 수 있게 로그에 단서를 남김
      console.log(`[korea] ${source.id} p${page}: 글 링크 없음. 길이 ${html.length}, newsId ${(html.match(/newsId/g) || []).length}회, 앞부분: ${html.slice(0, 300).replace(/\s+/g, ' ')}`);
      break;
    }
    for (const p of parseKoreaList(html, source, Date.now(), sample)) if (!seen.has(p.id)) { seen.add(p.id); all.push(p); }
  }
  console.log(`[korea] ${source.id}: 글 링크 ${total}개, 제목 ${sample.length}개 중 부동산 관련 ${all.length}개. 예: ${sample.slice(0, 4).join(' / ')}`);
  if (total === 0) throw new Error('목록에서 글을 찾지 못했습니다 (사이트 구조 변경 가능성)');
  return all;
}
