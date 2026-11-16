// 진단: 청약홈·LH 공고 상세 페이지에 어떤 항목이 있는지 확인. Actions > crawl > Run workflow 에서 diag를 켜면 실행됨
import fs from 'node:fs';
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';
import { fetchApplyhome, fetchLh } from './apply.js';
import { sourceById } from './sources.js';

async function show(name, url) {
  console.log(`\n### ${name}\n${url}`);
  try {
    const html = await fetchHtml(url, { cookies: true, timeoutMs: 25000 });
    const $ = cheerio.load(html);
    $('script,style,noscript,header,footer,nav').remove();
    console.log('len', html.length, '| tables', $('table').length, '| captions:', $('caption').map((_, e) => $(e).text().replace(/\s+/g, ' ').trim().slice(0, 40)).get().join(' / ').slice(0, 600));
    // 표마다: 머리글 + 첫 두 줄
    $('table').slice(0, 14).each((i, t) => {
      const rows = $(t).find('tr').slice(0, 5).map((_, tr) => $(tr).find('th,td').map((__, c) => $(c).text().replace(/\s+/g, ' ').trim().slice(0, 30)).get().join(' | ')).get();
      console.log(`[표${i}] ${$(t).attr('class') || ''} :: ${rows.join(' // ').slice(0, 520)}`);
    });
    const text = $('body').text().replace(/\s+/g, ' ');
    const i = Math.max(0, text.search(/공급위치|공고일|공급규모|접수기간/));
    console.log('본문:', text.slice(i, i + 1500));
  } catch (e) { console.log('ERROR', e.message, e.cause?.code || ''); }
}

const a = (await fetchApplyhome(sourceById.applyhome).catch(() => []))[0];
const r = (await fetchApplyhome(sourceById['applyhome-remain']).catch(() => []))[0];
const l = (await fetchLh(sourceById['lh-sale']).catch(() => []))[0];
const m = (await fetchLh(sourceById.lh).catch(() => []))[0];
if (a) await show('청약홈 APT 상세', a.url);
if (r) await show('청약홈 무순위 상세', r.url);
if (l) await show('LH 분양 상세', l.url);
if (m) await show('LH 임대 상세', m.url);
