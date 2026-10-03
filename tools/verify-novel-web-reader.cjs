const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/java/dev/ahmedmohamed/hayai/novel/reader/NovelHtmlDocumentBuilder.kt'), 'utf8');
const script = source.match(/private const val BRIDGE_SCRIPT = """([\s\S]*?)"""/)[1];
const cssSource = source.slice(source.indexOf('private fun buildReaderCss')).match(/return """([\s\S]*?)"""/)[1];
const values = {
    'if (isDark(style.backgroundColor)) "dark" else "light"': 'light',
    'style.marginTop.coerceIn(0, 200)': '16',
    'style.marginRight.coerceIn(0, 200)': '16',
    'style.marginBottom.coerceIn(0, 200)': '16',
    'style.marginLeft.coerceIn(0, 200)': '16',
    'style.marginTop.coerceIn(0, 200) + style.marginBottom.coerceIn(0, 200)': '32',
    'style.marginLeft.coerceIn(0, 200) + style.marginRight.coerceIn(0, 200)': '32',
    'style.fontSize.coerceIn(8, 72)': '18',
    'style.lineHeight.coerceIn(0.8f, 3f)': '1.5',
    'style.paragraphSpacing.coerceIn(0f, 5f)': '0.5',
    'style.paragraphIndent.coerceIn(0f, 10f)': '0',
    'color(style.linkColor)': '#0000ff',
    'ttsHighlightCss(style.ttsHighlightStyle, highlightColor, highlightTextColor)': '',
    importedFontCss: '', backgroundColor: '#ffffff', textColor: '#111111', resolvedFontFamily: 'sans-serif', align: 'left', selection: 'text',
};
const css = cssSource.replace(/\$\{([^}]+)\}|\$(\w+)/g, (_, expression, name) => {
    assert.ok(Object.hasOwn(values, expression || name), `Fixture value missing for ${expression || name}`);
    return values[expression || name];
});
const chapter = (id, count) => `<article class="hayai-chapter-block" data-chapter-id="${id}" data-state="ready">${Array.from({ length: count }, (_, index) => `<p>Chapter ${id}, paragraph ${index}. ${'A readable sentence with selectable text. '.repeat(12)}</p>`).join('')}</article>`;
const html = (mode, chapters, extraCss = '') => `<!doctype html><html class="hayai-${mode}" data-document-id="1" data-writing-direction="horizontal"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}${extraCss}</style></head><body><main id="hayai-reader">${chapters}</main><script>window.locations=[];window.HayaiReader={onReady(){},onChapterProgress(id,progress){locations.push({id,progress})},onPageLocation(id,progress,number,count){locations.push({id,progress,number,count})}};</script><script>${script}</script></body></html>`;

(async () => {
    const browser = await chromium.launch({ headless: true });
    try {
        const page = await browser.newPage({ viewport: { width: 412, height: 800 } });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        const waitForState = async (description, predicate, argument) => {
            try {
                await page.waitForFunction(predicate, argument, { polling: 'raf', timeout: 10000 });
            } catch (cause) {
                const state = await page.evaluate(() => ({
                    ready: document.readyState,
                    fonts: document.fonts?.status,
                    x: scrollX,
                    y: scrollY,
                    locations: window.locations?.slice(-5),
                    blocks: [...document.querySelectorAll('.hayai-chapter-block')].map(block => ({ id: block.dataset.chapterId, top: block.getBoundingClientRect().top, left: block.getBoundingClientRect().left })),
                }));
                throw new Error(`Timed out waiting for ${description}: ${JSON.stringify({ ...state, errors })}`, { cause });
            }
        };
        const loadFixture = async (mode, chapters, extraCss = '') => {
            await page.setContent(html(mode, chapters, extraCss));
            await waitForState(`${mode} fixture layout and first bridge location`, () => window.hayaiReader && document.readyState === 'complete' && document.fonts.status === 'loaded' && window.locations.length > 0);
        };
        await loadFixture('continuous', chapter(2, 45) + chapter(3, 2));
        await page.evaluate(() => hayaiReader.focusBlock('2', 60));
        await waitForState('continuous chapter 2 at 60 percent', () => locations.at(-1)?.id === '2' && Math.abs(locations.at(-1).progress - 60) <= 1);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'A short next chapter must not steal the active chapter');
        const before = await page.evaluate(() => ({ y: scrollY, top: document.querySelector('[data-chapter-id="2"]').getBoundingClientRect().top }));
        const beforePrepend = await page.evaluate(() => locations.length);
        await page.evaluate(content => hayaiReader.upsertBlock('1', content, 'before', false), chapter(1, 8).replace(/^<article[^>]*>|<\/article>$/g, ''));
        await waitForState('prepend layout preserving the visible chapter', ({ after, top }) => locations.length > after && document.querySelector('[data-chapter-id="1"]') && Math.abs(document.querySelector('[data-chapter-id="2"]').getBoundingClientRect().top - top) <= 1, { after: beforePrepend, top: before.top });
        const insertedTop = await page.evaluate(() => document.querySelector('[data-chapter-id="2"]').getBoundingClientRect().top);
        assert.ok(Math.abs(insertedTop - before.top) <= 1, 'Prepending preserves the visible chapter position');
        const beforeRetain = await page.evaluate(() => locations.length);
        await page.evaluate(() => hayaiReader.retainBlocks(['2', '3']));
        await waitForState('removed predecessor layout preserving scroll position', ({ after, y }) => locations.length > after && !document.querySelector('[data-chapter-id="1"]') && Math.abs(scrollY - y) <= 1, { after: beforeRetain, y: before.y });
        assert.ok(Math.abs(await page.evaluate(() => scrollY) - before.y) <= 1, 'Removing the preceding chapter preserves position');
        const noScroll = await page.evaluate(() => { const y = scrollY; hayaiReader.focusBlock('3', null); return scrollY === y; });
        assert.ok(noScroll, 'Focus without a position must not scroll');
        await page.evaluate(() => { hayaiReader.focusBlock('3', 0); });
        await waitForState('continuous seek to chapter 3', () => locations.at(-1)?.id === '3');
        assert.equal(await page.evaluate(() => locations.at(-1).id), '3', 'Continuous chapter seeking activates the requested short final chapter');
        await page.evaluate(() => scrollBy(0, -150));
        await waitForState('backward scroll reporting chapter 2', () => locations.at(-1)?.id === '2');
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'Scrolling backwards enters the previous chapter');
        await page.evaluate(() => hayaiReader.focusBlock('2', 45));
        await waitForState('continuous chapter 2 at 45 percent', () => locations.at(-1)?.id === '2' && Math.abs(locations.at(-1).progress - 45) <= 1);
        const anchor = await page.evaluate(() => { const p = [...document.querySelectorAll('[data-chapter-id="2"] p')].find(p => p.getBoundingClientRect().bottom > 0); return {text:p.textContent,top:p.getBoundingClientRect().top}; });
        const beforeGrowth = await page.evaluate(() => locations.length);
        await page.evaluate(() => { const p = document.querySelector('[data-chapter-id="2"] p'); p.style.height = `${p.offsetHeight + 400}px`; });
        await waitForState('layout growth restoring the text anchor', ({ after, text, top }) => locations.length > after && Math.abs([...document.querySelectorAll('[data-chapter-id="2"] p')].find(p => p.textContent === text).getBoundingClientRect().top - top) <= 1, { after: beforeGrowth, ...anchor });
        const afterGrowth = await page.evaluate(text => [...document.querySelectorAll('p')].find(p => p.textContent === text).getBoundingClientRect().top, anchor.text);
        assert.ok(Math.abs(afterGrowth - anchor.top) <= 1, 'Layout growth above the viewport preserves the text anchor');
        for (const [top, bottom] of [[0, 0], [64, 24], [24, 64]]) {
            await loadFixture('continuous', chapter(1, 12) + chapter(2, 1), `body{padding-top:${top}px;padding-bottom:${bottom}px}.hayai-chapter-block{min-height:calc(100vh - ${top + bottom}px)}`);
            await page.evaluate(() => hayaiReader.focusBlock('2', 0));
            await waitForState(`short final chapter seek with ${top}/${bottom}px margins`, () => locations.at(-1)?.id === '2');
            assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'Continuous seeking honors the requested chapter with customized margins');
            assert.ok(await page.evaluate(inset => Math.abs(document.querySelector('[data-chapter-id="2"]').getBoundingClientRect().top - inset) <= 1, top), 'A short final chapter starts at the configured reading inset');
            await page.evaluate(() => scrollBy(0, -150));
            await waitForState(`previous chapter scroll with ${top}/${bottom}px margins`, () => locations.at(-1)?.id === '1');
            assert.equal(await page.evaluate(() => locations.at(-1).id), '1', 'Customized margins preserve backward chapter detection');
        }
        await loadFixture('paged', chapter(1, 30) + chapter(2, 12));
        await page.evaluate(() => hayaiReader.focusBlock('1', 0));
        await waitForState('first paged chapter at its first column', () => locations.at(-1)?.id === '1' && locations.at(-1).number === 1 && Math.abs(scrollX) <= 1);
        assert.ok(await page.evaluate(() => document.scrollingElement.scrollHeight <= innerHeight + 1), 'Paged text stays within the viewport height');
        await page.evaluate(() => hayaiReader.step(1));
        await waitForState('next-page movement completing at one page', () => Math.abs(scrollX - 412) <= 1 && locations.at(-1)?.id === '1' && locations.at(-1).number === 2);
        assert.ok(Math.abs(await page.evaluate(() => scrollX) - 412) <= 1, 'Next moves by one full page');
        await page.evaluate(() => scrollTo(412 * 2.4, 0));
        await waitForState('partial-column swipe snapping to the third page', () => Math.abs(scrollX - 824) <= 1 && locations.at(-1)?.id === '1' && locations.at(-1).number === 3);
        assert.ok(Math.abs(await page.evaluate(() => scrollX) - 824) <= 1, 'Swipe stopping between columns snaps to a page');
        await page.evaluate(() => hayaiReader.focusBlock('1', 100));
        await waitForState('seek to the final page of chapter 1', () => locations.at(-1)?.id === '1' && locations.at(-1).number === locations.at(-1).count);
        const lastCurrentPage = await page.evaluate(() => scrollX);
        await page.evaluate(() => scrollBy(innerWidth * 0.8, 0));
        await waitForState('forward swipe entering chapter 2 one page away', x => locations.at(-1)?.id === '2' && Math.abs(scrollX - x - 412) <= 1, lastCurrentPage);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'A swipe beyond the last page enters the next chapter');
        assert.ok(Math.abs(await page.evaluate(() => scrollX) - lastCurrentPage - 412) <= 1, 'The chapter boundary is one page wide');
        await page.evaluate(() => scrollBy(-innerWidth * 0.8, 0));
        await waitForState('backward swipe returning to chapter 1', x => locations.at(-1)?.id === '1' && Math.abs(scrollX - x) <= 1, lastCurrentPage);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '1', 'A swipe before the first page enters the previous chapter');
        await page.evaluate(() => hayaiReader.focusBlock('2', 0));
        await waitForState('paged seek reporting the first page of chapter 2', () => locations.at(-1)?.id === '2' && locations.at(-1).number === 1);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'Paged chapter seeking activates the requested chapter');
        const selected = await page.evaluate(() => { const text = document.querySelector('[data-chapter-id="2"] p').firstChild; const range = document.createRange(); range.setStart(text,0);range.setEnd(text,12);getSelection().addRange(range); return hayaiReader.takeSelectionAnchor()?.selectedText; });
        assert.equal(selected, 'Chapter 2, p', 'Selection exposes the exact selected text');
        await page.evaluate(() => { getSelection().removeAllRanges(); hayaiReader.step(-1); });
        await waitForState('previous-page movement returning to chapter 1', x => locations.at(-1)?.id === '1' && Math.abs(scrollX - x) <= 1, lastCurrentPage);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '1', 'Paging backwards enters the preceding chapter');
        await loadFixture('paged', chapter(1, 1));
        await page.evaluate(() => hayaiReader.focusBlock('1', 0));
        await waitForState('single-page chapter location', () => locations.at(-1)?.id === '1' && locations.at(-1).count === 1 && Math.abs(scrollX) <= 1);
        assert.ok(await page.evaluate(() => document.scrollingElement.scrollWidth <= innerWidth + 1), 'A short chapter has no artificial blank page');
        assert.deepEqual(errors, [], 'Production bridge must not throw');
        console.log('Novel web reader browser regressions passed (containment, bidirectional navigation, prepend/remove, focus, relayout, pagination, snap, selection).');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
