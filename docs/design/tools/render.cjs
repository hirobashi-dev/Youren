/* 设计稿渲染与检查。运行依赖只用于导出，离线图册本身无依赖。 */
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require(process.env.DESIGN_PLAYWRIGHT_PATH || 'playwright');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'exports');
const previewOnly = process.argv.includes('--preview');
function assert(ok, message) { if (!ok) throw new Error(message); }
(async () => {
  fs.mkdirSync(out, {recursive:true});
  const browser = await chromium.launch({headless:true, executablePath:process.env.DESIGN_BROWSER_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1080},deviceScaleFactor:1});
    const errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    page.on('requestfailed',request=>errors.push(request.url()+': '+request.failure().errorText));
    await page.goto(pathToFileURL(path.join(root,'index.html')).href);
    const count=await page.locator('.screen-card').count();
    const expected=['B01','B02','B03','B04','B05','B06','A01','A02','D01','D02','G01','G02','G03','L01','L02','L03','L04','L05','E01','E02','E03','E04','E05','N01','P01','S01','R01'];
    const keys=await page.evaluate(()=>DESIGN_SCREENS.map(s=>s.key));
    assert(new Set(keys).size===keys.length,'Duplicate screen ID');
    assert(expected.every(id=>keys.includes(id)),'Missing required screen');
    await page.screenshot({path:path.join(out,'overview.png')});
    await page.getByRole('button',{name:'线框图',exact:true}).click();
    assert(await page.locator('body').evaluate(el=>el.classList.contains('wireframe')),'Wire toggle failed');
    await page.screenshot({path:path.join(out,'overview-wireframe.png')});
    await page.getByRole('button',{name:'视觉稿',exact:true}).click();
    await page.locator('#filters [data-module="恋爱"]').click();
    assert(await page.locator('.screen-card').count()===6,'Module filter failed');
    await page.locator('#filters [data-module="全部"]').click();
    await page.locator('#search').fill('B04');
    assert(await page.locator('.screen-card').count()===3,'Search filter failed');
    await page.locator('#search').fill('不存在的页面XYZ');
    assert(await page.locator('#empty').isVisible(),'Empty search state failed');
    await page.locator('#search').fill('');
    await page.locator('.screen-label [data-open]').first().click();
    assert(await page.locator('#viewer').isVisible(),'Viewer failed');
    assert(await page.locator('#viewer-title').innerText()==='留言板首页','Wrong viewer screen');
    await page.keyboard.press('ArrowRight');
    assert(await page.locator('#viewer-id').innerText()==='B02 / 留言板','Keyboard navigation failed');
    await page.keyboard.press('Escape');
    assert(!(await page.locator('#viewer').isVisible()),'Escape did not close');
    await page.locator('[data-flow="event"]').click();
    assert((await page.locator('#viewer-id').innerText()).startsWith('E01'),'Flow failed');
    await page.locator('#next').click();
    assert((await page.locator('#viewer-id').innerText()).startsWith('E02'),'Flow step failed');
    await page.locator('#close').click();
    await page.getByRole('button',{name:'Android',exact:true}).click();
    assert(await page.locator('body').evaluate(el=>el.classList.contains('android')),'Platform toggle failed');
    await page.getByRole('button',{name:'iOS',exact:true}).click();
    await page.setViewportSize({width:375,height:900});
    const width=await page.evaluate(()=>({scroll:document.documentElement.scrollWidth,viewport:innerWidth}));
    assert(width.scroll<=width.viewport,'Mobile page overflow');
    await page.screenshot({path:path.join(out,'gallery-mobile.png')});
    await page.locator('.screen-label [data-open]').first().click();
    const dialogWidth=await page.locator('#viewer').evaluate(el=>({scroll:el.scrollWidth,width:el.clientWidth}));
    assert(dialogWidth.scroll<=dialogWidth.width,'Mobile dialog overflow');
    await page.locator('#close').click();
    assert(errors.length===0,'Browser errors: '+errors.join('; '));
    console.log(`Verified ${count} screens: 27 required IDs, filters, search, wire/visual, viewer keyboard, flow navigation, iOS/Android, mobile width, no browser errors.`);
    await page.setViewportSize({width:1440,height:1080});
    await page.addStyleTag({content:'.export-page{margin:0;padding:0;background:white}.export-page .device{height:auto;min-height:844px;border-radius:0;width:390px}.export-page .screen-body{overflow:visible;flex:1 0 auto;min-height:620px}.export-page .art.portrait{height:260px}.export-page .fab{bottom:100px}'});
    for(const viewMode of ['visual','wireframe']) {
      fs.mkdirSync(path.join(out,viewMode),{recursive:true});
      const ids=previewOnly?['B01','L03','E02','B04','E05']:keys;
      for(const id of ids) {
        await page.evaluate(({id,viewMode})=>{const s=DESIGN_SCREENS.find(s=>s.key===id);document.body.className=`export-page ${viewMode==='wireframe'?'wireframe':''}`;document.body.innerHTML=DESIGN_RENDER(s);},{id,viewMode});
        const overflow=await page.locator('.device').evaluate(el=>el.scrollWidth>el.clientWidth);
        assert(!overflow,'Horizontal screen overflow: '+id);
        await page.locator('.device').screenshot({path:path.join(out,viewMode,id+'.png')});
      }
      console.log(`Exported ${ids.length} ${viewMode} PNGs.`);
    }
    if(!previewOnly) {
      const mobileKeys=keys.filter(id=>!id.startsWith('M'));
      for(const viewMode of ['visual','wireframe']) {
        for(let start=0;start<mobileKeys.length;start+=12) {
          const batch=mobileKeys.slice(start,start+12);
          await page.setViewportSize({width:1320,height:1000});
          const cells=batch.map(id=>`<article><h2>${id}</h2><img src="${pathToFileURL(path.join(out,viewMode,id+'.png')).href}" alt="${id}"></article>`).join('');
          await page.setContent(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>*{box-sizing:border-box}body{margin:0;padding:28px;background:#eff5f7;font-family:Microsoft YaHei,sans-serif;color:#163e43}h1{font-size:22px;font-weight:500;margin:0 0 8px}p{font-size:12px;margin:0 0 22px;color:#597078}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:22px}article{background:white;padding:10px;border:1px solid #dce7ea;border-radius:12px}h2{font-size:12px;margin:0 0 10px}img{display:block;width:100%;height:620px;object-fit:contain;object-position:top;background:white}</style><h1>在日·同好 / ${viewMode==='visual'?'视觉稿':'线框图'} / ${start+1}–${start+batch.length}</h1><p>全部图片为示意，完整单页 PNG 另附；长页面在此缩小展示。</p><div class="grid">${cells}</div></html>`,{waitUntil:'load'});
          await page.screenshot({path:path.join(out,`${viewMode}-sheet-${Math.floor(start/12)+1}.png`),fullPage:true});
        }
      }
      console.log('Exported visual and wireframe contact sheets.');
    }
    fs.writeFileSync(path.join(out,'verification.json'),JSON.stringify({screenCount:count,requiredIDs:expected,missingIDs:expected.filter(id=>!keys.includes(id)),browserErrors:errors,previewOnly,checks:['module-filter','search','empty-state','wire-visual','viewer','keyboard','flow','platform','mobile-width','dialog-width','export-width']},null,2)+'\n');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
