const gallery=document.getElementById('gallery');
const viewer=document.getElementById('viewer');
let activeModule='全部',mode='visual',platform='ios',visible=[],selected=0,activeFlow=null;
const flows={guest:['B01','B04','B04-UPLOAD','B04-REVIEW','B06','B03','B05'],group:['D01','D02','G01','A01','A01-CODE','A02','G01-PENDING','G02','G03'],dating:['L01','L02','L03','L04','L05','L05-CHAT'],event:['E01','E02','A01','A01-CODE','E03','E03-SUCCESS','E04']};
const flowNames={guest:'游客图文发帖',group:'注册与加入群',dating:'恋爱开通与匹配',event:'活动报名'};
function updateMode(){document.body.classList.toggle('wireframe',mode==='wire');document.body.classList.toggle('android',platform==='android');['visual','wire'].forEach(x=>document.getElementById(x).setAttribute('aria-pressed',String(mode===x)));['ios','android'].forEach(x=>document.getElementById(x).setAttribute('aria-pressed',String(platform===x)));}
function fit(){document.querySelectorAll('.preview').forEach(el=>{const scale=el.clientWidth/390;el.style.height=`${844*scale}px`;el.querySelector('.device').style.transform=`scale(${scale})`;});}
function render(){const query=document.getElementById('search').value.toLowerCase().trim();visible=DESIGN_SCREENS.filter(s=>(activeModule==='全部'||s.module===activeModule)&&(!query||`${s.key} ${s.module} ${s.title}`.toLowerCase().includes(query)));gallery.innerHTML=visible.map((s,i)=>`<article class="screen-card" data-key="${s.key}"><div class="screen-label"><button data-open="${i}">${s.title}<small>${s.module}${s.admin?' · 窄幅后台布局':' · 手机页面'}</small></button><span class="page-id">${s.key}</span></div><div class="preview">${DESIGN_RENDER(s)}<button class="preview-overlay" data-open="${i}" aria-label="放大查看 ${s.key} ${s.title}"><span>放大查看 ↗</span></button></div><p class="screen-caption">${s.note}</p></article>`).join('');document.getElementById('result-count').textContent=`显示 ${visible.length} / ${DESIGN_SCREENS.length} 张设计稿`;document.getElementById('empty').hidden=visible.length>0;document.querySelectorAll('#filters button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.module===activeModule)));fit();}
function show(index){selected=index;const s=activeFlow?DESIGN_SCREENS.find(x=>x.key===flows[activeFlow][selected]):visible[selected];if(!s)return;document.getElementById('viewer-id').textContent=`${s.key} / ${s.module}`;document.getElementById('viewer-title').textContent=s.title;document.getElementById('viewer-screen').innerHTML=DESIGN_RENDER(s);document.getElementById('viewer-note').textContent=s.note;document.getElementById('viewer-context').textContent=s.context||`${s.module}模块 → ${s.title}`;document.getElementById('viewer-flow').textContent=activeFlow?`${flowNames[activeFlow]} · ${selected+1} / ${flows[activeFlow].length}`:`${mode==='wire'?'线框图':'视觉稿'} · ${selected+1} / ${visible.length}`;document.getElementById('previous').disabled=selected===0;document.getElementById('next').disabled=selected===(activeFlow?flows[activeFlow].length:visible.length)-1;if(!viewer.open)viewer.showModal();}
const modules=['全部',...new Set(DESIGN_SCREENS.map(s=>s.module))];
document.getElementById('filters').innerHTML=modules.map(m=>`<button data-module="${m}" aria-pressed="${m==='全部'}">${m}</button>`).join('');
document.getElementById('screen-count').textContent=DESIGN_SCREENS.length;
document.getElementById('filters').addEventListener('click',e=>{const b=e.target.closest('[data-module]');if(b){activeModule=b.dataset.module;render();}});
gallery.addEventListener('click',e=>{const b=e.target.closest('[data-open]');if(b){activeFlow=null;show(Number(b.dataset.open));}});
document.getElementById('search').addEventListener('input',render);
['visual','wire'].forEach(x=>document.getElementById(x).addEventListener('click',()=>{mode=x;updateMode();}));
['ios','android'].forEach(x=>document.getElementById(x).addEventListener('click',()=>{platform=x;updateMode();}));
document.querySelector('.flows').addEventListener('click',e=>{const b=e.target.closest('[data-flow]');if(b){activeFlow=b.dataset.flow;show(0);}});
document.getElementById('close').addEventListener('click',()=>viewer.close());
document.getElementById('previous').addEventListener('click',()=>{if(selected>0)show(selected-1);});
document.getElementById('next').addEventListener('click',()=>{const total=activeFlow?flows[activeFlow].length:visible.length;if(selected<total-1)show(selected+1);});
document.getElementById('print').addEventListener('click',()=>window.print());
document.addEventListener('keydown',e=>{if(!viewer.open)return;if(e.key==='ArrowLeft'&&selected>0){e.preventDefault();show(selected-1);}if(e.key==='ArrowRight'){const total=activeFlow?flows[activeFlow].length:visible.length;if(selected<total-1){e.preventDefault();show(selected+1);}}});
window.addEventListener('resize',fit);
updateMode();render();
