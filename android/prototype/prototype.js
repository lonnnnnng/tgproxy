'use strict';

const icons = {
  logo:'<path d="M5 4h10a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H5z"/><path d="m10 8 6 4-6 4z" fill="currentColor" stroke="none"/>',
  library:'<rect x="3" y="6" width="18" height="15" rx="3"/><path d="M7 3h10m-7 8 5 3-5 3z"/>',
  file:'<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 14h8m-8 3h5"/>',
  channels:'<rect x="7" y="7" width="14" height="14" rx="3"/><path d="M4 16H3V5a2 2 0 0 1 2-2h11v1m-5 8h6m-6 4h4"/>',
  user:'<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  play:'<path d="m8 5 11 7-11 7z"/>',
  pause:'<path d="M8 5v14M16 5v14" stroke-width="4"/>',
  search:'<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.8 4.8"/>',
  filter:'<path d="M4 7h16M7 12h10m-7 5h4"/>',
  sort:'<path d="M4 5h12M4 10h9M4 15h6m8-5v11m-3-3 3 3 3-3"/>',
  grid:'<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  list:'<path d="M10 5h11M10 12h11M10 19h11"/><rect x="3" y="3" width="3" height="4" rx=".5"/><rect x="3" y="10" width="3" height="4" rx=".5"/><rect x="3" y="17" width="3" height="4" rx=".5"/>',
  back:'<path d="m11 5-7 7 7 7M4 12h16"/>',
  close:'<path d="m6 6 12 12M6 18 18 6"/>',
  'chevron-down':'<path d="m6 9 6 6 6-6"/>',
  'chevron-right':'<path d="m9 5 7 7-7 7"/>',
  plus:'<path d="M12 4v16M4 12h16"/>',
  check:'<path d="m5 12 4 4L20 5"/>',
  more:'<circle cx="5" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="19" cy="12" r="1" fill="currentColor"/>',
  refresh:'<path d="M20 8a8 8 0 0 0-13-3L3 9m0-6v6h6m-5 7a8 8 0 0 0 13 3l4-4m0 6v-6h-6"/>',
  sun:'<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  moon:'<path d="M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11z"/>',
  rotate:'<rect x="6" y="5" width="12" height="15" rx="2" transform="rotate(-25 12 12)"/><path d="M17 2h5v5M2 17v5h5"/>',
  fullscreen:'<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/>',
  lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
  unlock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 7.5-2M12 15v2"/>',
  ratio:'<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 11V9h3m7 4v2h-3"/>',
  rewind:'<path d="M4 8a8 8 0 1 1-1 7M4 3v5h5"/>',
  forward:'<path d="M20 8a8 8 0 1 0 1 7m-1-12v5h-5"/>',
  speed:'<circle cx="12" cy="12" r="9"/><path d="m12 7 4 5h-4v5"/>',
  info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.2"/>',
  shield:'<path d="m12 3 8 3v6c0 5-8 9-8 9S4 17 4 12V6z"/><path d="m8 12 3 3 5-6"/>',
  key:'<circle cx="8" cy="9" r="5"/><path d="m12 13 8 8m-4-4 3-3m-7-1 3-3"/>',
  database:'<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  volume:'<path d="M3 9h4l5-4v14l-5-4H3zM16 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/>',
  hand:'<path d="M8 13V6a2 2 0 0 1 4 0v6-3a2 2 0 0 1 4 0v3-1a2 2 0 0 1 4 0v5c0 4-3 6-6 6-2 0-4-1-5-3l-5-6a2 2 0 0 1 3-2l1 2"/>',
  cursor:'<path d="m5 3 14 10-7 1-3 7z"/>',
  external:'<path d="M13 3h8v8m0-8L10 14M8 4H4v16h16v-4"/>',
  wifi:'<path d="M2 8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0m-11 4a5 5 0 0 1 8 0m-4 4h.1" stroke-width="2.4"/>',
  signal:'<path d="M4 20v-4m5 4v-8m5 8V8m5 12V4" stroke-width="3"/>',
  battery:'<rect x="2" y="6" width="18" height="12" rx="2"/><path d="M22 10v4"/><rect x="5" y="9" width="12" height="6" rx=".5" fill="currentColor" stroke="none"/>',
  copy:'<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 4V3H3v13h1"/>',
  film:'<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18m10-18v18M3 8h4m-4 8h4m10-8h4m-4 8h4"/>',
  subtitles:'<rect x="2" y="5" width="20" height="14" rx="3"/><path d="M10 10H6v4h4m8-4h-4v4h4"/>'
};
const $=s=>document.querySelector(s);
const $$=s=>[...document.querySelectorAll(s)];
const svg=name=>`<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name]||icons.film}</svg>`;
const escapeHTML=value=>String(value).replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const time=value=>`${Math.floor(value/60).toString().padStart(2,'0')}:${Math.floor(value%60).toString().padStart(2,'0')}`;
function renderIcons(root=document){root.querySelectorAll('[data-icon]').forEach(node=>{node.innerHTML=svg(node.dataset.icon);});}

const channels=[
 {id:'wild',name:'山野放映室',mark:'山',color:'#558075',background:'#e5eee9'},
 {id:'doc',name:'纪录片收藏',mark:'纪',color:'#867252',background:'#f1ebe0'},
 {id:'film',name:'光影档案',mark:'光',color:'#6776a2',background:'#e9ecf5'},
 {id:'music',name:'现场与声音',mark:'声',color:'#9a6b84',background:'#f3e8ef'}
];
const videos=[
 {id:1,title:'沿着山脊，等一场日出',file:'Along.The.Ridge.2025.2160p.WEB-DL.mp4',channel:'wild',image:'mountains',duration:1448,size:'1.82 GB',quality:'4K',position:756,date:'今天 09:12'},
 {id:2,title:'森林深处，万物生长',file:'森林深处.E02.1080P.mp4',channel:'doc',image:'forest',duration:2583,size:'2.14 GB',quality:'1080P',position:925,date:'今天 08:46'},
 {id:3,title:'在湖岸，慢慢走',file:'在湖岸慢慢走.2025.4K.mp4',channel:'wild',image:'coast',duration:1976,size:'2.36 GB',quality:'4K',position:0,date:'今天 08:21'},
 {id:4,title:'日落以后，城市醒来',file:'City.After.Dark.2025.1080p.mkv',channel:'film',image:'city',duration:1161,size:'986 MB',quality:'1080P',position:0,date:'昨天 22:30'},
 {id:5,title:'现场记录：把夜晚交给音乐',file:'Live.Session.2025.1080p.AAC.mp4',channel:'music',image:'stage',duration:3488,size:'3.10 GB',quality:'1080P',position:0,date:'昨天 20:18'},
 {id:6,title:'当银幕亮起',file:'当银幕亮起.电影幕后访谈.mp4',channel:'film',image:'cinema',duration:2145,size:'1.56 GB',quality:'1080P',position:0,date:'昨天 18:32'},
 {id:7,title:'沙漠里的时间',file:'Desert.Time.2025.2160p.mp4',channel:'doc',image:'desert',duration:2851,size:'3.27 GB',quality:'4K',position:0,date:'昨天 17:10'},
 {id:8,title:'向海而行',file:'向海而行.第一集.1080P.mp4',channel:'wild',image:'ocean',duration:1638,size:'1.24 GB',quality:'1080P',position:0,date:'昨天 14:08'},
 {id:9,title:'山林之间：一次没有目的地的旅行',file:'[4K] 山林之间_一次没有目的地的旅行_完整版_2025.mp4',channel:'wild',image:'forest',duration:3218,size:'4.02 GB',quality:'4K',position:0,date:'11 月 8 日'},
 {id:10,title:'即兴演奏，雨夜的最后一首',file:'Improvisation.Last.Song.Live.1080p.mp4',channel:'music',image:'stage',duration:1342,size:'912 MB',quality:'1080P',position:0,date:'11 月 8 日'},
 {id:11,title:'影像笔记：光线与构图',file:'影像笔记_光线与构图_第03期.mp4',channel:'film',image:null,duration:1875,size:'763 MB',quality:'720P',position:0,date:'11 月 7 日'},
 {id:12,title:'群山以北，风的方向',file:'群山以北.2025.1080p.mp4',channel:'doc',image:'mountains',duration:2729,size:'2.28 GB',quality:'1080P',position:0,date:'11 月 7 日'}
];
// long: 原型只使用内存中的示例资源，不读取真实账号，不把视觉演示伪装成已完成的索引或下载。
const state={route:'library',scope:'all',query:'',channel:'all',sort:'newest',theme:'light',selected:videos[0],player:null,playing:false,speed:1,ratio:'适应',locked:false,controlVisible:true,volume:55,brightness:75,backgroundPlaying:false};
let sheetOrigin=null,toastTimer,feedbackTimer,hideTimer,boostTimer,press=null,lastTap=0,lastTapX=0,boosted=false,lastTick=performance.now();
const getChannel=id=>channels.find(channel=>channel.id===id)||channels[0];
function channelAvatar(channel){return `<span class="channel-avatar" style="color:${channel.color};background:${channel.background}">${channel.mark}</span>`;}
function filteredVideos(){return videos.filter(v=>(state.channel==='all'||v.channel===state.channel)&&(state.scope==='all'||v.position>0)&&(!state.query||`${v.title} ${v.file}`.toLowerCase().includes(state.query.toLowerCase()))).sort((a,b)=>state.sort==='title'?a.title.localeCompare(b.title,'zh-CN'):state.sort==='oldest'?b.id-a.id:a.id-b.id);}
function renderLibrary(){
 $('#clear-search').hidden=!state.query;
 $('#filter-label').textContent=state.channel==='all'?'所有频道':getChannel(state.channel).name;
 $('#filter-button').classList.toggle('filtered',state.channel!=='all');
 const showContinue=state.scope==='all'&&!state.query&&state.channel==='all';
 $('#continue-section').hidden=!showContinue;
 $('#continue-track').classList.add('continue-list');
 // long: 已确认只采用文字列表；不创建封面节点，不保留展示模式偏好。
 $('#continue-track').innerHTML=videos.filter(v=>v.position>0).slice(0,2).map(v=>`<button class="continue-row" data-play="${v.id}" aria-label="继续观看 ${v.title}"><span class="continue-row-copy"><strong>${v.title}</strong><small>${getChannel(v.channel).name}<span>看到 ${time(v.position)}</span></small></span><span class="continue-row-play">${svg('play')}</span></button>`).join('');
 const list=filteredVideos();
 $('#results-heading').innerHTML=`${state.query?'搜索结果':state.scope==='recent'?'最近播放':'视频列表'} <span id="results-count">${list.length} 部</span>`;
 $('#video-grid').classList.add('list-view');
 $('#video-grid').innerHTML=list.map(v=>`<article class="video-item"><button class="video-open" data-video="${v.id}" aria-label="查看 ${v.title}"><h4>${v.title}</h4><div class="video-meta"><span class="source">${getChannel(v.channel).name}</span><span class="list-duration" aria-label="发布时间">${v.date}</span><span class="file-size">${v.size}</span></div></button></article>`).join('');
 $('#empty-state').hidden=list.length!==0;
 $('#list-end').hidden=list.length===0;
 $$('[data-scope]').forEach(button=>{const selected=button.dataset.scope===state.scope;button.classList.toggle('selected',selected);button.setAttribute('aria-selected',selected);});
}
function navigate(route,replace=false){
 if(state.player&&!$('#player-screen').hidden)exitPlayer();
 closeSheet();
 state.route=route;
 $$('.screen').forEach(screen=>screen.classList.toggle('active',screen.id===`${route}-screen`));
 $$('[data-route]').forEach(button=>{const selected=button.dataset.route===route;button.classList.toggle('selected',selected);selected?button.setAttribute('aria-current','page'):button.removeAttribute('aria-current');});
 $$('[data-preview]').forEach(button=>button.classList.toggle('active',button.dataset.preview===route));
 $('#preview-label').textContent=({library:'视频资源',detail:'资源详情',channels:'频道管理',account:'账号与偏好'})[route]||'视频资源';
 $('#bottom-nav').hidden=route==='detail';
 if(route==='library')renderLibrary();
 if(route==='channels')renderChannels();
 updateMini();
 if(location.hash!==`#${route}`)history[replace?'replaceState':'pushState']({route},'',`#${route}`);
}
function showDetail(id){
 const v=videos.find(v=>v.id===Number(id));if(!v)return;
 state.selected=v;
 const ch=getChannel(v.channel);
 const related=videos.filter(item=>item.channel===v.channel&&item.id!==v.id).slice(0,3);
 $('#detail-scroll').innerHTML=`<div class="detail-list-top"><button class="icon-button" data-action="library" aria-label="返回视频库">${svg('back')}</button></div><div class="detail-content"><h2>${v.title}</h2><p class="detail-file">${v.file}</p><div class="media-facts"><span>${svg('file')}${v.size}</span><span>发布于 ${v.date}</span></div><div class="detail-source">${channelAvatar(ch)}<div><p>${ch.name}</p><small>Telegram 频道</small></div><button data-channel-filter="${ch.id}">查看频道</button></div><button class="primary-button detail-play" data-play="${v.id}">${svg('play')}${v.position>0?'继续播放':'播放视频'}</button>${v.position>0?`<span class="detail-resume">上次看到 ${time(v.position)}</span>`:''}<h3 class="related-heading">来自同一频道</h3>${related.map(r=>`<button class="related-item" data-video="${r.id}"><span><strong>${r.title}</strong><small>${r.date} · ${r.size}</small></span></button>`).join('')}</div>`;
 navigate('detail');$('#detail-scroll').scrollTop=0;
}
function renderChannels(){
 $('#channel-count').textContent=channels.length;
 $('#channel-total').textContent=`${videos.length} 个视频`;
 $('#channel-list').innerHTML=channels.map(ch=>`<article class="channel-item">${channelAvatar(ch)}<button class="channel-content" data-channel-filter="${ch.id}"><strong>${ch.name}</strong><p><span>${videos.filter(v=>v.channel===ch.id).length} 个视频</span><small>${ch.added?'等待首次扫描':'已同步'}</small></p></button><button class="icon-button" data-channel-more="${ch.id}" aria-label="管理 ${ch.name}">${svg('more')}</button></article>`).join('');
}
function showToast(message){clearTimeout(toastTimer);$('#snackbar').textContent=message;$('#snackbar').hidden=false;toastTimer=setTimeout(()=>$('#snackbar').hidden=true,2800);}
function showSheet(title,content){
 endBoost();clearTimeout(hideTimer);
 sheetOrigin=document.activeElement;
 $('#sheet-title').textContent=title;$('#sheet-body').innerHTML=content;
 $('#sheet-backdrop').hidden=false;$('#bottom-sheet').hidden=false;
 renderIcons($('#bottom-sheet'));$('#close-sheet').focus({preventScroll:true});
 $('#bottom-sheet').addEventListener('keydown',trapFocus);
 syncInert();
}
// long: 弹层和播放器打开时隔离底层焦点，键盘与读屏不能操作被遮住的视频卡片。
function syncInert(){const modal=!$('#bottom-sheet').hidden,playing=!$('#player-screen').hidden;$$('.screen').forEach(screen=>screen.inert=modal||playing);$('#bottom-nav').inert=modal||playing;$('#mini-player').inert=modal||playing;$('#player-screen').inert=modal;}
function trapFocus(event){if(event.key!=='Tab')return;const focusable=[...$('#bottom-sheet').querySelectorAll('button:not(:disabled),input,a')];const first=focusable[0],last=focusable.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}}
function closeSheet(){if($('#bottom-sheet').hidden)return;$('#bottom-sheet').hidden=true;$('#sheet-backdrop').hidden=true;$('#bottom-sheet').removeEventListener('keydown',trapFocus);syncInert();if(sheetOrigin?.isConnected)sheetOrigin.focus({preventScroll:true});scheduleHide();}
const option=(value,label,selected,action,description='')=>`<button class="option${selected?' selected':''}" data-${action}="${value}" aria-pressed="${selected}"><span>${label}${description?`<small>${description}</small>`:''}</span><span class="option-check">${selected?svg('check'):''}</span></button>`;
function showFilter(){showSheet('选择频道',`<p class="sheet-intro">只看你想看的内容。</p><div class="option-list">${option('all','所有频道',state.channel==='all','filter')}${channels.map(ch=>option(ch.id,ch.name,state.channel===ch.id,'filter',`${videos.filter(v=>v.channel===ch.id).length} 个视频`)).join('')}</div>`);}
function showSort(){showSheet('视频排序',`<div class="option-list">${option('newest','发布时间：从新到旧',state.sort==='newest','sort')}${option('title','名称 A—Z',state.sort==='title','sort')}${option('oldest','发布时间：从旧到新',state.sort==='oldest','sort')}</div>`);}
function showSpeed(){showSheet('播放速度',`<p class="sheet-intro">下次播放沿用你的选择。</p><div class="speed-grid">${[.5,.75,1,1.25,1.5,1.75,2,3].map(speed=>`<button class="speed-choice${state.speed===speed?' selected':''}" data-speed="${speed}" aria-pressed="${state.speed===speed}">${speed.toFixed(speed%1===0?1:2).replace(/0$/,'').replace(/\.$/,'.0')}×</button>`).join('')}</div><p class="sheet-hint">长按视频画面可临时 2× 播放，松手恢复。</p>`);}
function showRatio(){showSheet('画面比例',`<div class="option-list">${['适应','填满裁切','拉伸铺满','16:9','4:3'].map((ratio,index)=>option(index,ratio,state.ratio===ratio,'ratio',['保留原始比例，完整显示画面','铺满屏幕，边缘可能被裁切','拉伸画面到屏幕大小','',''][index])).join('')}</div>`);}
function showAppearance(){showSheet('外观',`<div class="option-list">${option('light','浅色',state.theme==='light','theme')}${option('dark','深色',state.theme==='dark','theme')}</div><p class="sheet-hint" style="margin-top:18px">播放器始终使用深色背景，让注意力留在画面里。</p>`);}
function applyTheme(theme){state.theme=theme;$('#device').classList.toggle('dark',theme==='dark');$('#preview-theme').innerHTML=svg(theme==='dark'?'sun':'moon');$('#appearance-value').textContent=theme==='dark'?'深色':'浅色';try{localStorage.setItem('tgproxy-prototype-theme',theme);}catch{}}
function showHelp(){showSheet('播放手势',`<div class="option-list"><div class="option">${svg('hand')}<span>单击画面<small>显示或隐藏控制栏</small></span></div><div class="option">${svg('forward')}<span>双击左侧 / 右侧<small>后退 / 前进 10 秒</small></span></div><div class="option">${svg('speed')}<span>长按画面<small>临时 2×，松手恢复</small></span></div><div class="option">${svg('sun')}<span>左侧上下滑动<small>调节当前画面亮度</small></span></div><div class="option">${svg('volume')}<span>右侧上下滑动<small>调节媒体音量</small></span></div></div>`);}
function showAdd(){showSheet('添加频道',`<p class="sheet-intro">从 Telegram 已加入的频道中选择。</p><div class="option-list">${[{id:'travel',name:'旅途片刻',detail:'旅行短片与城市记录'},{id:'life',name:'日常观察',detail:'生活片段与记录'}].filter(ch=>!channels.some(c=>c.id===ch.id)).map(ch=>`<button class="option" data-add-channel="${ch.id}"><span>${ch.name}<small>${ch.detail}</small></span><span class="option-check">${svg('plus')}</span></button>`).join('')}</div><hr class="sheet-divider"><label class="sheet-label" for="channel-link">也可以通过链接添加</label><input class="sheet-input" id="channel-link" placeholder="https://t.me/频道名" autocomplete="off"><p class="form-error" id="channel-error"></p><button class="primary-button" id="add-by-link" style="width:100%">添加频道</button>`);}
function addChannel(id,name){if(channels.some(ch=>ch.id===id)){showToast('这个频道已经添加');return;}channels.push({id,name,mark:name[0],color:'#617a92',background:'#e7edf2',added:true});closeSheet();renderChannels();showToast('已添加示例频道，可在频道菜单开始扫描');}
function syncChannel(id='all'){
 closeSheet();
 const button=$('#sync-all');
 if(button.disabled){showToast('正在同步，请稍候');return;}
 const total=id==='all'?channels.length:1;
 if(!total){showToast('请先添加频道');return;}
 let count=0;
 // long: 同步状态只在操作期间反馈，常驻页面留给频道列表，不再保留占空间的就绪卡片。
 button.classList.add('spinning');button.disabled=true;button.setAttribute('aria-label','正在同步频道');
 showToast('正在检查新视频');
 const timer=setInterval(()=>{
  count++;button.title=`已完成 ${count} / ${total} 个频道`;
  if(count>=total){
   clearInterval(timer);channels.forEach(ch=>{if(id==='all'||ch.id===id)ch.added=false;});
   button.classList.remove('spinning');button.disabled=false;button.title='同步全部频道';button.setAttribute('aria-label','同步全部频道');
   renderChannels();showToast('示例同步完成，暂无新视频');
  }
 },600);
}
function showChannelMenu(id){const ch=getChannel(id);showSheet(ch.name,`<div class="option-list"><button class="option" data-channel-filter="${id}">${svg('library')}<span>查看频道视频</span></button><button class="option" data-sync="${id}">${svg('refresh')}<span>${ch.added?'开始扫描':'检查新视频'}</span></button><button class="option danger" data-remove-channel="${id}">${svg('close')}<span>移除频道</span></button></div>`);}
function showVideoMenu(){const v=state.selected;showSheet('资源信息',`<div class="info-pair"><span>视频名称</span><strong>${v.title}</strong></div><div class="info-pair"><span>来源频道</span><strong>${getChannel(v.channel).name}</strong></div><div class="info-pair"><span>文件大小</span><strong>${v.size}</strong></div><div class="sheet-footer"><button class="secondary-button" id="copy-info" style="width:100%">复制资源信息</button></div>`);}
function startPlayer(id){
 const v=videos.find(v=>v.id===Number(id));if(!v)return;
 closeSheet();state.selected=v;state.player=v;state.playing=true;state.backgroundPlaying=false;state.locked=false;
 $('#player-image').src=`assets/${v.image||'mountains'}.jpg`;
 $('#player-title').textContent=v.title;$('#player-source').textContent=getChannel(v.channel).name;
 $('#timeline').max=v.duration;$('#timeline').value=v.position;
 $('#time-duration').textContent=time(v.duration);
 $('#player-screen').hidden=false;$('#device').classList.add('playing');$('#unlock-button').hidden=true;$('#mini-player').hidden=true;
 $('#preview-label').textContent='播放体验';$$('[data-preview]').forEach(button=>button.classList.toggle('active',button.dataset.preview==='player'));
 setControls(true);renderPlayback();scheduleHide();syncInert();
}
function exitPlayer(){endBoost();clearTimeout(hideTimer);state.backgroundPlaying=state.playing;$('#player-screen').hidden=true;$('#device').classList.remove('playing','landscape');$('#brightness-mask').style.opacity=0;state.brightness=75;state.locked=false;$('#unlock-button').hidden=true;syncInert();updateMini();$('#preview-label').textContent=({library:'视频资源',detail:'资源详情',channels:'频道管理',account:'账号与偏好'})[state.route];$$('[data-preview]').forEach(button=>button.classList.toggle('active',button.dataset.preview===state.route));}
function updateMini(){const show=state.player&&$('#player-screen').hidden;$('#mini-player').hidden=!show;if(!show)return;$('#mini-title').textContent=state.player.title;$('#mini-subtitle').textContent=state.playing?'正在后台播放':`已暂停 · ${time(state.player.position)}`;$('#mini-toggle').innerHTML=svg(state.playing?'pause':'play');$('#mini-toggle').setAttribute('aria-label',state.playing?'暂停播放':'继续播放');}
function renderPlayback(){if(!state.player)return;$('#time-current').textContent=time(state.player.position);$('#timeline').value=state.player.position;$('#timeline').style.setProperty('--progress',`${state.player.position/state.player.duration*100}%`);$('#play-toggle').innerHTML=svg(state.playing?'pause':'play');$('#play-toggle').setAttribute('aria-label',state.playing?'暂停':'播放');$('#speed-button').textContent=state.speed.toFixed(state.speed%1?2:1).replace(/50$/,'5').replace(/00$/,'0')+'×';$('#default-speed-value').textContent=$('#speed-button').textContent;$('#ratio-button').innerHTML=`${svg('ratio')}<span>${state.ratio==='填满裁切'?'裁切':state.ratio==='拉伸铺满'?'拉伸':state.ratio}</span>`;updateMini();}
function togglePlayback(){if(!state.player)return;endBoost();state.playing=!state.playing;if(state.player.position>=state.player.duration)state.player.position=0;renderPlayback();setControls(true);scheduleHide();}
function seek(delta){if(!state.player||state.locked)return;state.player.position=Math.max(0,Math.min(state.player.duration,state.player.position+delta));renderPlayback();showFeedback(delta>0?'前进 10 秒':'后退 10 秒');scheduleHide();}
function showFeedback(message){clearTimeout(feedbackTimer);$('#gesture-feedback').textContent=message;$('#gesture-feedback').hidden=false;feedbackTimer=setTimeout(()=>$('#gesture-feedback').hidden=true,950);}
function setControls(visible){state.controlVisible=visible;$('#player-controls').classList.toggle('controls-hidden',!visible||state.locked);}
function scheduleHide(){clearTimeout(hideTimer);if(state.playing&&!state.locked&&$('#bottom-sheet').hidden)hideTimer=setTimeout(()=>{if(!press)setControls(false);},3500);}
function endBoost(){clearTimeout(boostTimer);boosted=false;$('#boost-feedback').hidden=true;}
function lockPlayer(){endBoost();state.locked=true;setControls(false);$('#unlock-button').hidden=false;showFeedback('已锁定，点按解锁按钮恢复操作');}
function rotatePlayer(){endBoost();$('#device').classList.toggle('landscape');setControls(true);scheduleHide();}
function applyRatio(){const image=$('#player-image');image.style.width='100%';image.style.height='100%';image.style.position='static';image.style.inset='auto';image.style.margin='0';image.style.objectFit=state.ratio==='填满裁切'?'cover':state.ratio==='拉伸铺满'?'fill':'contain';if(['16:9','4:3'].includes(state.ratio)){image.style.position='absolute';image.style.inset='0';image.style.margin='auto';image.style.aspectRatio=state.ratio.replace(':','/');image.style.height='auto';image.style.maxHeight='100%';image.style.objectFit='fill';}renderPlayback();}

document.addEventListener('click',event=>{
 const button=event.target.closest('button');if(!button)return;
 if(button.dataset.preview){const route=button.dataset.preview;if(route==='player')startPlayer(state.selected.id);else if(route==='detail')showDetail(state.selected.id);else navigate(route);return;}
 if(button.dataset.route){navigate(button.dataset.route);return;}
 if(button.dataset.video){showDetail(button.dataset.video);return;}
 if(button.dataset.play){startPlayer(button.dataset.play);return;}
 if(button.dataset.scope){state.scope=button.dataset.scope;renderLibrary();$('#library-scroll').scrollTop=0;return;}
 if(button.dataset.filter){state.channel=button.dataset.filter;closeSheet();renderLibrary();$('#library-scroll').scrollTop=0;return;}
 if(button.dataset.channelFilter){state.channel=button.dataset.channelFilter;state.scope='all';state.query='';$('#search').value='';navigate('library');$('#library-scroll').scrollTop=0;return;}
 if(button.dataset.sort){state.sort=button.dataset.sort;closeSheet();renderLibrary();$('#library-scroll').scrollTop=0;return;}
 if(button.dataset.speed){state.speed=Number(button.dataset.speed);try{localStorage.setItem('tgproxy-prototype-speed',state.speed);}catch{}renderPlayback();$('#default-speed-value').textContent=state.speed+'×';closeSheet();showToast(`播放速度设为 ${state.speed}×`);return;}
 if(button.dataset.ratio){state.ratio=['适应','填满裁切','拉伸铺满','16:9','4:3'][Number(button.dataset.ratio)];applyRatio();closeSheet();return;}
 if(button.dataset.theme){applyTheme(button.dataset.theme);closeSheet();return;}
 if(button.dataset.addChannel){addChannel(button.dataset.addChannel,button.dataset.addChannel==='travel'?'旅途片刻':'日常观察');return;}
 if(button.dataset.channelMore){showChannelMenu(button.dataset.channelMore);return;}
 if(button.dataset.sync){syncChannel(button.dataset.sync);return;}
 if(button.dataset.removeChannel){const ch=getChannel(button.dataset.removeChannel);showSheet('移除这个频道？',`<p class="sheet-intro">“${escapeHTML(ch.name)}”的本机索引和播放记录将被移除。你仍然保留 Telegram 频道成员身份。</p><button class="option danger" data-confirm-remove="${ch.id}">移除频道</button><button class="secondary-button" id="cancel-remove" style="width:100%;margin-top:12px">保留频道</button>`);return;}
 if(button.dataset.confirmRemove){const id=button.dataset.confirmRemove;channels.splice(channels.findIndex(ch=>ch.id===id),1);for(let i=videos.length-1;i>=0;i--)if(videos[i].channel===id)videos.splice(i,1);if(state.channel===id)state.channel='all';if(state.player?.channel===id){state.player=null;state.playing=false;updateMini();}closeSheet();renderChannels();showToast('示例频道已移除，刷新页面可恢复');return;}
 if(button.dataset.action==='video-more'){showVideoMenu();return;}
 if(button.dataset.action){navigate(button.dataset.action);return;}
 const actions={
  'filter-button':showFilter,'sort-button':showSort,
  'clear-search':()=>{state.query='';$('#search').value='';renderLibrary();$('#search').focus();},
  'reset-search':()=>{state.query='';state.channel='all';state.scope='all';$('#search').value='';renderLibrary();},
  'preview-theme':()=>applyTheme(state.theme==='light'?'dark':'light'),'preview-landscape':()=>{if($('#player-screen').hidden)startPlayer(state.selected.id);rotatePlayer();},
  'close-sheet':closeSheet,'cancel-remove':closeSheet,'add-channel':showAdd,'sync-all':()=>syncChannel(),
  'appearance-setting':showAppearance,'default-speed-setting':showSpeed,'gesture-help':showHelp,
  'account-info':()=>showSheet('Telegram 授权',`<p class="sheet-intro">这是界面演示中的已授权状态，不连接真实 Telegram 账号。</p><div class="info-pair"><span>账号</span><strong>Long（示例）</strong></div><div class="info-pair"><span>连接状态</span><strong>已连接</strong></div>`),
  'storage-info':()=>showSheet('本机数据',`<div class="info-pair"><span>频道索引</span><strong>${channels.length} 个频道</strong></div><div class="info-pair"><span>视频资源</span><strong>${videos.length} 个视频</strong></div><p class="sheet-hint" style="margin-top:20px">此原型仅展示示例数据，不会读取或清理手机文件。</p>`),
  'add-by-link':()=>{const value=$('#channel-link').value.trim();const match=value.match(/^(?:https?:\/\/)?t\.me\/([a-zA-Z0-9_]{5,})\/?$/);if(!match){$('#channel-error').textContent='请输入有效的 Telegram 公开频道链接';$('#channel-link').focus();return;}addChannel('link-'+match[1],match[1]);},
  'player-back':exitPlayer,'play-toggle':togglePlayback,'seek-back':()=>seek(-10),'seek-forward':()=>seek(10),
  'speed-button':showSpeed,'ratio-button':showRatio,'player-lock':lockPlayer,'player-rotate':rotatePlayer,
  'unlock-button':()=>{state.locked=false;$('#unlock-button').hidden=true;setControls(true);scheduleHide();},
  'player-more':()=>showSheet('播放设置',`<div class="option-list"><button class="option" id="sheet-speed">${svg('speed')}<span>播放速度</span><span class="option-count">${state.speed}×</span></button><button class="option" id="sheet-ratio">${svg('ratio')}<span>画面比例</span><span class="option-count">${state.ratio}</span></button><button class="option" id="sheet-audio">${svg('volume')}<span>音轨</span><span class="option-count">默认音轨</span></button><button class="option" id="sheet-subtitles">${svg('subtitles')}<span>字幕</span><span class="option-count">关闭</span></button><button class="option" id="sheet-help">${svg('hand')}<span>手势说明</span></button></div>`),
  'sheet-speed':showSpeed,'sheet-ratio':showRatio,'sheet-help':showHelp,
  'sheet-audio':()=>showSheet('音轨',`<div class="option selected">${svg('check')}<span>默认音轨<small>AAC · 双声道（示例）</small></span></div>`),
  'sheet-subtitles':()=>showSheet('字幕',`<div class="option selected">${svg('check')}<span>关闭字幕</span></div><p class="sheet-hint" style="margin:12px">这条示例资源没有内嵌字幕。</p>`),
  'mini-resume':()=>startPlayer(state.player.id),'mini-toggle':()=>{togglePlayback();updateMini();},'mini-close':()=>{state.playing=false;state.player=null;updateMini();},
  'copy-info':async()=>{try{await navigator.clipboard.writeText(`${state.selected.title}\n${state.selected.file}\n${state.selected.size}`);closeSheet();showToast('资源信息已复制');}catch{showToast('浏览器未允许复制，可在详情页查看资源信息');}}
 };
 actions[button.id]?.();
});
$('#sheet-backdrop').addEventListener('click',closeSheet);
$('#search').addEventListener('input',event=>{state.query=event.target.value.trim();renderLibrary();$('#library-scroll').scrollTop=0;});
$('#timeline').addEventListener('input',event=>{state.player.position=Number(event.target.value);renderPlayback();clearTimeout(hideTimer);});
$('#timeline').addEventListener('change',scheduleHide);
// long: 手势在画面层独立识别，操作栏与时间轴不参与，避免拖动进度被识别为亮度或倍速。
$('#gesture-area').addEventListener('pointerdown',event=>{
 if(event.button!==0)return;
 if(press){endBoost();press=null;return;}
 const bounds=event.currentTarget.getBoundingClientRect();
 press={x:event.clientX,y:event.clientY,at:performance.now(),moved:false,left:event.clientX<bounds.left+bounds.width/2,brightness:state.brightness,volume:state.volume,height:bounds.height,pointer:event.pointerId};
 event.currentTarget.setPointerCapture(event.pointerId);clearTimeout(hideTimer);
 boostTimer=setTimeout(()=>{if(press&&!press.moved&&!state.locked&&state.playing){boosted=true;$('#boost-feedback').hidden=false;setControls(false);}},500);
});
$('#gesture-area').addEventListener('pointermove',event=>{
 if(!press||state.locked||boosted)return;
 const dy=press.y-event.clientY,dx=event.clientX-press.x;
 if(Math.max(Math.abs(dx),Math.abs(dy))>10){press.moved=true;clearTimeout(boostTimer);}
 if(press.moved&&Math.abs(dy)>Math.abs(dx)){
  setControls(false);const amount=dy/press.height*140;
  if(press.left){state.brightness=Math.max(10,Math.min(100,Math.round(press.brightness+amount)));$('#brightness-mask').style.opacity=(100-state.brightness)/150;showFeedback(`亮度 ${state.brightness}%`);}
  else{state.volume=Math.max(0,Math.min(100,Math.round(press.volume+amount)));showFeedback(`音量 ${state.volume}%`);}
 }
});
$('#gesture-area').addEventListener('pointerup',event=>{
 if(!press)return;const held=boosted,was=press;endBoost();press=null;
 if(state.locked){$('#unlock-button').hidden=false;return;}
 if(!held&&!was.moved&&performance.now()-was.at<500){
  if(performance.now()-lastTap<280&&Math.abs(lastTapX-event.clientX)<55){seek(was.left?-10:10);lastTap=0;}
  else{lastTap=performance.now();lastTapX=event.clientX;setTimeout(()=>{if(lastTap&&performance.now()-lastTap>=270){setControls(!state.controlVisible);lastTap=0;scheduleHide();}},285);}
 }
 scheduleHide();
});
function cancelGesture(){endBoost();press=null;}
$('#gesture-area').addEventListener('pointercancel',cancelGesture);
window.addEventListener('blur',cancelGesture);
document.addEventListener('visibilitychange',()=>{if(document.hidden)cancelGesture();});
document.addEventListener('keydown',event=>{
 if(event.key==='Escape'){if(!$('#bottom-sheet').hidden)closeSheet();else if(!$('#player-screen').hidden){if(state.locked){state.locked=false;$('#unlock-button').hidden=true;setControls(true);}else exitPlayer();}else if(state.route==='detail')navigate('library');return;}
 if(['INPUT','TEXTAREA'].includes(event.target.tagName)||$('#player-screen').hidden||!$('#bottom-sheet').hidden)return;
 if(event.code==='Space'){event.preventDefault();if(!state.locked)togglePlayback();}
 if(event.key==='ArrowRight'){event.preventDefault();seek(10);}if(event.key==='ArrowLeft'){event.preventDefault();seek(-10);}
 if(event.key==='Enter'&&event.target.id==='gesture-area'){event.preventDefault();setControls(!state.controlVisible);scheduleHide();}
});
window.addEventListener('popstate',()=>{const route=location.hash.slice(1);navigate(['library','channels','account','detail'].includes(route)?route:'library',true);});
setInterval(()=>{const now=performance.now(),elapsed=Math.min(2,(now-lastTick)/1000);lastTick=now;if(state.player&&state.playing&&!document.hidden){state.player.position=Math.min(state.player.duration,state.player.position+elapsed*(boosted?2:state.speed));if(state.player.position>=state.player.duration){state.playing=false;setControls(true);}renderPlayback();}},250);
renderIcons();
try{const theme=localStorage.getItem('tgproxy-prototype-theme');if(['light','dark'].includes(theme))applyTheme(theme);const speed=Number(localStorage.getItem('tgproxy-prototype-speed'));if([.5,.75,1,1.25,1.5,1.75,2,3].includes(speed))state.speed=speed;}catch{}
$('#default-speed-value').textContent=state.speed+'×';
navigate(['library','channels','account'].includes(location.hash.slice(1))?location.hash.slice(1):'library',true);
