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
const html = (mode, chapters) => `<!doctype html><html class="hayai-${mode}" data-document-id="1" data-writing-direction="horizontal"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}</style></head><body><main id="hayai-reader">${chapters}</main><script>window.locations=[];window.HayaiReader={onReady(){},onChapterProgress(id,progress){locations.push({id,progress})},onPageLocation(id,progress,number,count){locations.push({id,progress,number,count})}};</script><script>${script}</script></body></html>`;

(async () => {
    const browser = await chromium.launch({ headless: true });
    try {
        const page = await browser.newPage({ viewport: { width: 412, height: 800 } });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.setContent(html('continuous', chapter(2, 45) + chapter(3, 2)));
        await page.evaluate(() => hayaiReader.focusBlock('2', 60));
        await page.waitForTimeout(200);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'A short next chapter must not steal the active chapter');
        const before = await page.evaluate(() => ({ y: scrollY, top: document.querySelector('[data-chapter-id="2"]').getBoundingClientRect().top }));
        await page.evaluate(content => hayaiReader.upsertBlock('1', content, 'before', false), chapter(1, 8).replace(/^<article[^>]*>|<\/article>$/g, ''));
        await page.waitForTimeout(200);
        const insertedTop = await page.evaluate(() => document.querySelector('[data-chapter-id="2"]').getBoundingClientRect().top);
        assert.ok(Math.abs(insertedTop - before.top) <= 1, 'Prepending preserves the visible chapter position');
        await page.evaluate(() => hayaiReader.retainBlocks(['2', '3']));
        await page.waitForTimeout(200);
        assert.ok(Math.abs(await page.evaluate(() => scrollY) - before.y) <= 1, 'Removing the preceding chapter preserves position');
        const noScroll = await page.evaluate(() => { const y = scrollY; hayaiReader.focusBlock('3', null); return scrollY === y; });
        assert.ok(noScroll, 'Focus without a position must not scroll');
        await page.evaluate(() => { hayaiReader.focusBlock('3', 0); });
        await page.waitForTimeout(200);
        await page.evaluate(() => scrollBy(0, -150));
        await page.waitForTimeout(200);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'Scrolling backwards enters the previous chapter');
        await page.evaluate(() => hayaiReader.focusBlock('2', 45));
        await page.waitForTimeout(200);
        const anchor = await page.evaluate(() => { const p = [...document.querySelectorAll('[data-chapter-id="2"] p')].find(p => p.getBoundingClientRect().bottom > 0); return {text:p.textContent,top:p.getBoundingClientRect().top}; });
        await page.evaluate(() => { const p = document.querySelector('[data-chapter-id="2"] p'); p.style.height = `${p.offsetHeight + 400}px`; });
        await page.waitForTimeout(200);
        const afterGrowth = await page.evaluate(text => [...document.querySelectorAll('p')].find(p => p.textContent === text).getBoundingClientRect().top, anchor.text);
        assert.ok(Math.abs(afterGrowth - anchor.top) <= 1, 'Layout growth above the viewport preserves the text anchor');
        await page.setContent(html('paged', chapter(1, 30) + chapter(2, 12)));
        await page.evaluate(() => hayaiReader.focusBlock('1', 0));
        await page.waitForTimeout(200);
        assert.ok(await page.evaluate(() => document.scrollingElement.scrollHeight <= innerHeight + 1), 'Paged text stays within the viewport height');
        await page.evaluate(() => hayaiReader.step(1));
        await page.waitForTimeout(700);
        assert.ok(Math.abs(await page.evaluate(() => scrollX) - 412) <= 1, 'Next moves by one full page');
        await page.evaluate(() => scrollTo(412 * 2.4, 0));
        await page.waitForTimeout(700);
        assert.ok(Math.abs(await page.evaluate(() => scrollX) - 824) <= 1, 'Swipe stopping between columns snaps to a page');
        await page.evaluate(() => hayaiReader.focusBlock('1', 100));
        await page.waitForTimeout(250);
        const lastCurrentPage = await page.evaluate(() => scrollX);
        await page.evaluate(() => scrollBy(innerWidth * 0.8, 0));
        await page.waitForTimeout(700);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'A swipe beyond the last page enters the next chapter');
        assert.ok(Math.abs(await page.evaluate(() => scrollX) - lastCurrentPage - 412) <= 1, 'The chapter boundary is one page wide');
        await page.evaluate(() => scrollBy(-innerWidth * 0.8, 0));
        await page.waitForTimeout(700);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '1', 'A swipe before the first page enters the previous chapter');
        await page.evaluate(() => hayaiReader.focusBlock('2', 0));
        await page.waitForTimeout(250);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '2', 'Paged chapter seeking activates the requested chapter');
        const selected = await page.evaluate(() => { const text = document.querySelector('[data-chapter-id="2"] p').firstChild; const range = document.createRange(); range.setStart(text,0);range.setEnd(text,12);getSelection().addRange(range); return hayaiReader.takeSelectionAnchor()?.selectedText; });
        assert.equal(selected, 'Chapter 2, p', 'Selection exposes the exact selected text');
        await page.evaluate(() => { getSelection().removeAllRanges(); hayaiReader.step(-1); });
        await page.waitForTimeout(700);
        assert.equal(await page.evaluate(() => locations.at(-1).id), '1', 'Paging backwards enters the preceding chapter');
        await page.setContent(html('paged', chapter(1, 1)));
        await page.evaluate(() => hayaiReader.focusBlock('1', 0));
        await page.waitForTimeout(250);
        assert.ok(await page.evaluate(() => document.scrollingElement.scrollWidth <= innerWidth + 1), 'A short chapter has no artificial blank page');
        assert.deepEqual(errors, [], 'Production bridge must not throw');
        console.log('Novel web reader browser regressions passed (containment, bidirectional navigation, prepend/remove, focus, relayout, pagination, snap, selection).');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
