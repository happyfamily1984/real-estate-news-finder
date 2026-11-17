// 진단: 국토부·정책브리핑 보도자료 페이지의 첨부파일 링크와 파일 형식
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36';
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();

async function probe(url, referer) {
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: referer }, signal: AbortSignal.timeout(30000), redirect: 'follow' });
    const buf = Buffer.from(await res.arrayBuffer());
    console.log(`    받기 ${res.status} ${res.headers.get('content-type')} | ${res.headers.get('content-disposition')} | ${buf.length}바이트 | 앞 8바이트 ${buf.subarray(0, 8).toString('hex')} ${JSON.stringify(buf.subarray(0, 8).toString('latin1'))}`);
  } catch (e) { console.log('    받기 실패', e.message); }
}

async function page(label, url) {
  console.log(`\n######## ${label} ${url}`);
  try {
    const html = await fetchHtml(url, { cookies: true, timeoutMs: 25000 });
    const $ = cheerio.load(html);
    console.log('길이', html.length, '| 제목', clean($('title').text()).slice(0, 80), '| 본문 글자 수(스크립트 제외)', clean($('body').clone().find('script,style').remove().end().text()).length);
    const links = $('a').map((_, a) => ({ t: clean($(a).text()).slice(0, 70), h: $(a).attr('href') ?? '', o: ($(a).attr('onclick') ?? '').slice(0, 160) })).get()
      .filter((l) => /\.(hwpx?|pdf|docx?|xlsx?|zip)\b/i.test(l.t + l.h + l.o) || /down|file|attach|첨부/i.test(l.h + l.o));
    console.log('첨부로 보이는 링크', links.length);
    links.slice(0, 12).forEach((l) => console.log('  ', JSON.stringify(l)));
    const fn = html.match(/function\s+\w*(?:[Dd]own|[Ff]ile)\w*\s*\([^)]*\)\s*\{[\s\S]{0,400}/g) ?? [];
    fn.slice(0, 3).forEach((f) => console.log('  함수:', clean(f).slice(0, 400)));
    const seen = new Set();
    for (const l of links.filter((x) => x.h && !x.h.startsWith('javascript') && !x.h.startsWith('#')).slice(0, 4)) {
      const abs = new URL(l.h.replace(/&amp;/g, '&'), url).toString();
      if (seen.has(abs)) continue;
      seen.add(abs);
      console.log('  →', l.t, abs);
      await probe(abs, url);
    }
    return html;
  } catch (e) { console.log('실패', e.message); return ''; }
}

// 국토부: RSS에서 최근 글 2개
try {
  const rss = await fetchHtml('https://www.molit.go.kr/dev/board/board_rss.jsp?rss_id=NEWS', { cookies: true, timeoutMs: 25000 });
  const $ = cheerio.load(rss, { xmlMode: true });
  const items = $('item').map((_, el) => ({ title: clean($(el).children('title').text()), link: clean($(el).children('link').text()) })).get().slice(0, 2);
  for (const it of items) await page('국토부 | ' + it.title.slice(0, 40), it.link.replace(/&amp;/g, '&'));
} catch (e) { console.log('국토부 RSS 실패', e.message); }

// 정책브리핑 보도자료: 목록에서 최근 글 2개
try {
  const list = await fetchHtml('https://www.korea.kr/briefing/pressReleaseList.do', { cookies: true, timeoutMs: 25000 });
  const ids = [...new Set([...list.matchAll(/pressReleaseView\.do\?newsId=(\d+)/g)].map((m) => m[1]))].slice(0, 2);
  for (const id of ids) await page('정책브리핑', `https://www.korea.kr/briefing/pressReleaseView.do?newsId=${id}`);
} catch (e) { console.log('정책브리핑 실패', e.message); }
