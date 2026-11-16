// 진단: SH(서울주택도시공사)·GH(경기주택도시공사) 공고 목록 후보 주소 접속과 목록 구조
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';

const urls = [
  'https://www.i-sh.co.kr/main/lay2/program/S1T294C297/www/brd/m_247/list.do?multi_itm_seq=2',
  'https://www.i-sh.co.kr/main/lay2/program/S1T294C296/www/brd/m_244/list.do?multi_itm_seq=1',
  'https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/list.do',
  'https://www.i-sh.co.kr/app/lay2/program/S48T1581C563/www/brd/m_247/list.do?multi_itm_seq=2',
  'https://www.i-sh.co.kr/main/index.do',
  'https://www.gh.or.kr/gh/announcement-of-salerental001.do',
  'https://www.gh.or.kr/gh/announcement-of-salerental001.do?mode=list&articleLimit=20',
  'https://apply.gh.or.kr/',
  'https://www.gh.or.kr/gh/index.do',
];
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();

for (const url of urls) {
  console.log('\n########', url);
  try {
    const html = await fetchHtml(url, { cookies: true, timeoutMs: 20000 });
    const $ = cheerio.load(html);
    console.log('길이', html.length, '| 제목:', clean($('title').text()), '| 표 줄 수', $('tbody tr').length);
    $('tbody tr').slice(0, 6).each((_, el) => {
      const tr = $(el);
      console.log(' 줄:', tr.find('td').map((__, td) => `[${$(td).attr('class') ?? ''}] ${clean($(td).text()).slice(0, 70)}`).get().join(' / '));
      tr.find('a').slice(0, 2).each((__, a) => console.log('   a:', JSON.stringify({ href: $(a).attr('href'), onclick: $(a).attr('onclick'), cls: $(a).attr('class'), data: a.attribs && Object.fromEntries(Object.entries(a.attribs).filter(([k]) => k.startsWith('data-'))) })));
    });
    if ($('tbody tr').length === 0) {
      // 표가 없으면 목록 모양(li)과 공고 관련 링크를 찾아봄
      const links = $('a').map((_, a) => ({ t: clean($(a).text()).slice(0, 40), h: $(a).attr('href') ?? '', o: ($(a).attr('onclick') ?? '').slice(0, 80) })).get()
        .filter((l) => /공고|분양|임대|청약|모집/.test(l.t));
      console.log(' 관련 링크', links.length, JSON.stringify(links.slice(0, 40)));
      console.log(' 본문 앞:', clean($('body').text()).slice(0, 600));
    }
    const forms = $('form').map((_, f) => `${$(f).attr('id') ?? ''}→${$(f).attr('action') ?? ''}`).get();
    console.log(' 폼:', forms.slice(0, 6).join(' , '));
    const fn = html.match(/function\s+(?:fn\w*[Vv]iew|getDetailView|goView|view)\w*\s*\([^)]*\)\s*\{[\s\S]{0,500}/);
    if (fn) console.log(' 상세 함수:', clean(fn[0]).slice(0, 500));
  } catch (e) {
    console.log('실패:', e.message, e.cause?.code ?? '');
  }
}
