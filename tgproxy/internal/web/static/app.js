'use strict';
const $ = (id) => document.getElementById(id);
const icons = {
  layers: '<path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5"/>',
  overview: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  channels: '<rect x="3" y="4" width="18" height="15" rx="3"/><path d="M8 22h8M8 9h8M8 13h5"/>',
  logs: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z"/><path d="M14 3v6h6M8 13h8M8 17h5"/>',
  settings: '<path d="M4 7h9m4 0h3M4 17h3m4 0h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
  server: '<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  refresh: '<path d="M20 7a9 9 0 0 0-15-1L3 9m0-5v5h5M4 17a9 9 0 0 0 15 1l2-3m0 5v-5h-5"/>',
  rebuild: '<path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5"/>',
  play: '<path d="m9 5 11 7-11 7V5Z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  video: '<rect x="3" y="5" width="14" height="14" rx="3"/><path d="m17 10 4-3v10l-4-3"/>',
  folder: '<path d="M3 7V5a2 2 0 0 1 2-2h5l3 4h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z"/>',
  telegram: '<path d="m3 10 18-7-5 18-5-7-8-4Z"/><path d="m11 14 5-6"/>',
  send: '<path d="m22 2-7 20-4-9-9-4 20-7Zm0 0L11 13"/>',
  jellyfin: '<path d="M10.3 4.4a2 2 0 0 1 3.4 0l7 12.2a2 2 0 0 1-1.7 3H5a2 2 0 0 1-1.7-3L10.3 4.4Z"/><path d="m12 10 3.5 6h-7l3.5-6Z"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
  alert: '<path d="m10.3 4.4-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.7-2.6l-8-14a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4m0 4h.01"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',
  moon: '<path d="M20.5 14A9 9 0 0 1 10 3.5 9 9 0 1 0 20.5 14Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1 1m12 12 1 1M5 19l1-1M18 6l1-1"/>'
};
function icon(name) { return `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.info}</svg>`; }
document.querySelectorAll('[data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon); });
const views = {
  overview: ['同步概览', '从频道到媒体库，每一步都清晰可见。'],
  channels: ['频道管理', '整理你的内容来源，让每一个频道各就其位。'],
  logs: ['运行日志', '查看扫描记录，快速定位需要处理的问题。'],
  connections: ['连接设置', '管理 Telegram 授权和 Jellyfin 刷新通知。']
};
const state = { status: null, channels: [], online: false, pending: '', refresh: null, view: 'overview', codeSent: false, passwordNeeded: false, loginExpanded: false, deleting: null, signatures: {}, toastTimer: null };
const number = value => new Intl.NumberFormat('zh-CN').format(Number(value) || 0);
const text = (id, value) => { $(id).textContent = value; };
const taskBusy = () => !!(state.status?.scanning || state.status?.rebuilding || state.status?.jellyfin_state === 'notifying');
const locked = () => !state.online || !!state.pending || taskBusy();
function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content !== undefined) node.textContent = content;
  return node;
}
function badge(label, tone) { const node = element('span', 'badge', label); node.dataset.state = tone; return node; }
function setBadge(id, label, tone = 'neutral') { text(id, label); $(id).dataset.state = tone; }
function message(id, content, tone = 'active') { text(id, content); $(id).dataset.state = tone; $(id).hidden = !content; }
function notify(content, tone = 'success') {
  clearTimeout(state.toastTimer);
  text('toast-message', content); $('toast').dataset.state = tone; $('toast').hidden = false;
  $('toast').querySelector('[data-icon]').innerHTML = icon(tone === 'error' ? 'alert' : 'check');
  if (tone !== 'error') state.toastTimer = setTimeout(() => { $('toast').hidden = true; }, 6000);
}
async function api(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), options.method ? 90000 : 12000);
  try {
    const response = await fetch(path, { ...options, signal: controller.signal, headers: { 'Content-Type': 'application/json' }, cache: 'no-store' });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || `请求失败（HTTP ${response.status}）`);
    return body;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('请求超时，请刷新状态确认操作结果。');
    throw error;
  } finally { clearTimeout(timer); }
}
function route(focus = false) {
  const wanted = location.hash.slice(1) || 'overview';
  // long: 路由只接受显式页面名，排除 constructor 等对象继承属性，避免异常链接造成空白页。
  state.view = Object.hasOwn(views, wanted) ? wanted : 'overview';
  Object.keys(views).forEach(view => { $(`view-${view}`).hidden = view !== state.view; });
  document.querySelectorAll('[data-view]').forEach(link => {
    if (link.dataset.view === state.view) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  });
  const [title, description] = views[state.view];
  text('page-title', title); text('breadcrumb-current', title); text('page-description', description);
  document.title = `${title} · tgproxy`;
  $('sync-actions').hidden = state.view !== 'overview'; $('show-add').hidden = state.view !== 'channels';
  if (focus) { $('page-title').focus({ preventScroll: true }); window.scrollTo(0, 0); }
  if (state.status) render();
}
function applyTheme(theme) {
  const dark = theme === 'dark'; document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  try { localStorage.setItem('tgproxy-theme', dark ? 'dark' : 'light'); } catch { /* long: 存储不可用时仍允许本页切换主题。 */ }
  text('theme-label', dark ? '浅色模式' : '深色模式');
  $('theme-toggle').setAttribute('aria-label', dark ? '切换为浅色主题' : '切换为深色主题');
  $('theme-toggle').querySelector('[data-icon]').innerHTML = icon(dark ? 'sun' : 'moon');
  document.querySelectorAll('[data-theme-choice]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.themeChoice === (dark ? 'dark' : 'light'))));
}
function stamp(value) {
  if (!value) return '本次运行暂无记录';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间未知' : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date);
}
function channelStatus(channel) {
  const progress = state.status?.scan_progress?.[channel.id];
  if (!progress) return ['暂无扫描记录', 'neutral'];
  return ({ completed: ['已完成', 'success'], failed: ['扫描失败', 'error'], scanning: ['扫描中', 'active'], queued: ['等待扫描', 'neutral'] })[progress.status] || ['暂无扫描记录', 'neutral'];
}
function scanStats() {
  const progress = state.status.scan_progress || {};
  const rows = state.channels.map(c => progress[c.id]).filter(Boolean);
  return { rows, done: rows.filter(p => ['completed', 'failed'].includes(p.status)).length, failed: rows.filter(p => p.status === 'failed').length, active: state.channels.find(c => String(c.id) === String(state.status.active_channel_id)) };
}
function jellyfinStatus() {
  const s = state.status;
  if (!s.jellyfin_configured) return ['未配置', 'neutral', '尚未开启自动通知。生成媒体树后，请在 Jellyfin 中手动刷新媒体库。'];
  if (s.jellyfin_state === 'notifying') return ['提交中', 'active', '媒体树已生成，正在向 Jellyfin 提交刷新请求。'];
  if (s.rebuilding) return ['等待生成', 'active', '媒体树生成后，会向 Jellyfin 提交新的刷新请求。'];
  if (s.scanning) return ['等待扫描', 'neutral', '本次扫描结束后会自动生成媒体树，并通知 Jellyfin。'];
  if (s.needs_rebuild) return ['等待更新', 'warning', '索引已发生变化，请重建媒体树后提交刷新请求。'];
  if (s.jellyfin_state === 'failed') return ['通知失败', 'error', s.jellyfin_message || '刷新请求失败，请检查 Jellyfin 服务与配置。'];
  if (s.jellyfin_state === 'notified') return ['已通知', 'success', '刷新请求已提交。媒体库入库进度请在 Jellyfin 中查看。'];
  return ['已配置', 'neutral', '自动通知已配置，本次服务运行尚无请求记录。'];
}
function stage(id, label, tone) { text(`stage-${id}-label`, label); $(`stage-${id}`).dataset.state = tone; }
function renderOverview() {
  const s = state.status, stats = scanStats();
  text('total-channels', number(state.channels.length)); text('total-videos', number(s.videos ?? s.items));
  text('total-entries', s.strm_files_known ? number(s.strm_files) : s.needs_rebuild ? '待生成' : '已生成');
  $('setup-banner').hidden = s.authorized;
  const scanDone = stats.rows.length === state.channels.length && stats.done === state.channels.length && state.channels.length > 0;
  let title = '准备好下一次同步', detail = '扫描全部频道，自动更新媒体入口并通知 Jellyfin。', label = '就绪', tone = 'neutral';
  // long: scanning 覆盖整个流水线，先判断重建和通知，避免后半程仍显示“频道扫描中”。
  if (s.jellyfin_state === 'notifying') { title = '正在通知 Jellyfin'; detail = '媒体树已生成，等待 Jellyfin 确认刷新请求。'; label = '通知中'; tone = 'active'; }
  else if (s.rebuilding) { title = '正在生成媒体树'; detail = '根据最新索引同步媒体入口，完成后自动提交刷新。'; label = '生成中'; tone = 'active'; }
  else if (s.scanning) { title = stats.active ? `正在扫描 ${stats.active.title || stats.active.ref}` : '正在扫描频道'; detail = stats.active ? `当前频道已发现 ${number(s.scan_progress?.[stats.active.id]?.videos_found)} 个视频，完整数量以扫描完成后为准。` : '任务已启动，正在读取频道视频历史。'; label = '扫描中'; tone = 'active'; }
  else if (!s.authorized) { title = '等待 Telegram 连接'; detail = '请前往连接设置登录，或等待已有会话恢复连接。'; label = '待连接'; tone = 'warning'; }
  else if (!state.channels.length) { title = '从第一个频道开始'; detail = '添加你的 Telegram 视频频道，即可建立媒体索引。'; label = '待添加'; }
  else if (s.last_error || stats.failed) { title = '有一项结果需要确认'; detail = stats.failed ? `${stats.failed} 个频道扫描失败，原索引已保留。请查看日志后重试。` : '最近一次操作报告了错误，请查看日志确认具体原因。'; label = '需关注'; tone = 'error'; }
  else if (s.needs_rebuild) { title = '媒体树等待更新'; detail = '频道或视频索引已变更，重建媒体树即可更新媒体入口。'; label = '待重建'; tone = 'warning'; }
  else if (scanDone) { title = '本次扫描已完成'; detail = `${number(stats.done)} 个频道已处理，媒体树已生成。`; label = '已完成'; tone = 'success'; }
  else { detail = '媒体树与当前索引一致。本次服务运行还没有频道扫描记录。'; }
  text('task-title', title); text('task-description', detail); setBadge('task-badge', label, tone);
  if (stats.failed && !s.scanning) stage('scan', `${stats.failed} 个失败`, 'error');
  else if (scanDone) stage('scan', `${stats.done} 个已处理`, 'success');
  else if (s.scanning && !s.rebuilding && s.jellyfin_state !== 'notifying') stage('scan', `${stats.done} / ${state.channels.length} 个已处理`, 'active');
  else stage('scan', s.authorized ? '暂无记录' : '等待连接', 'neutral');
  stage('tree', s.rebuilding ? '生成中' : s.needs_rebuild ? '待生成' : '已生成', s.rebuilding ? 'active' : s.needs_rebuild ? 'neutral' : 'success');
  const [jfLabel, jfTone, jfDescription] = jellyfinStatus();
  stage('notify', jfLabel, jfTone); setBadge('jellyfin-badge', jfLabel, jfTone);
  text('jellyfin-description', jfDescription); text('notify-configured', s.jellyfin_configured ? '已启用' : '未配置');
  text('notify-time', stamp(s.jellyfin_updated_at)); text('tree-state', s.rebuilding ? '生成中' : s.needs_rebuild ? '等待更新' : '与索引一致');
  $('task-progress').hidden = !s.scanning || s.rebuilding || s.jellyfin_state === 'notifying';
  text('progress-description', '已处理频道'); text('progress-fraction', `${stats.done} / ${state.channels.length}`);
  $('progress').max = Math.max(1, state.channels.length); $('progress').value = stats.done;
  text('task-footnote', taskBusy() ? '任务在服务端执行，关闭页面不会中断。' : '扫描后自动生成媒体树并通知 Jellyfin。');
  text('preview-count', number(state.channels.length)); text('preview-shown', state.channels.length > 5 ? `显示前 5 个，共 ${number(state.channels.length)} 个` : `共 ${number(state.channels.length)} 个频道`);
  renderTable('channel-preview', state.channels.slice(0, 5), false);
  $('overview-error').hidden = !s.last_error; text('overview-error-text', s.last_error?.split('\n')[0] || '');
}
function empty(target, title, description, action) {
  const node = element('div', 'empty'); const symbol = element('span'); symbol.innerHTML = icon('channels');
  node.append(symbol, element('strong', '', title), element('p', '', description));
  if (action) { const link = element('a', 'text-link', action.label); link.href = action.href; node.append(link); }
  target.replaceChildren(node);
}
function renderTable(id, channels, full) {
  const key = JSON.stringify([channels, state.status.channel_video_counts, state.status.scan_progress, full ? locked() : false]);
  if (state.signatures[id] === key) return;
  state.signatures[id] = key;
  const container = $(id);
  if (!channels.length) {
    empty(container, state.channels.length ? '没有匹配的频道' : '还没有内容来源', state.channels.length ? '试试其他关键词，或重置筛选条件。' : '添加有访问权限的 Telegram 频道，开始建立你的媒体库。', !full ? { label: '前往添加频道', href: '#channels' } : null);
    return;
  }
  const wrapper = element('div', 'table-scroll'); const table = element('table', full ? 'full-table' : 'preview-table');
  const head = element('thead'); const heading = element('tr');
  ['频道', '视频索引', '扫描状态', full ? '操作' : ''].forEach(title => { const th = element('th', '', title); th.scope = 'col'; heading.append(th); });
  head.append(heading); const body = element('tbody');
  channels.forEach(channel => {
    const row = element('tr'); const identityCell = element('td'); const identity = element('div', 'channel-identity');
    const title = channel.title || channel.ref || String(channel.id);
    const avatar = element('span', 'avatar', Array.from(title.replace(/[^\p{L}\p{N}]/gu, ''))[0] || '频');
    avatar.dataset.color = String(Math.abs(Number(channel.id)) % 4); avatar.setAttribute('aria-hidden', 'true');
    const copy = element('div', 'channel-copy'); const name = element('strong', '', title); name.title = title;
    const ref = element('small', '', channel.ref || String(channel.id)); ref.title = `${channel.ref} · ID ${channel.id}`;
    copy.append(name, ref); identity.append(avatar, copy); identityCell.append(identity);
    const count = element('td', 'video-count', number(state.status.channel_video_counts?.[channel.id]));
    const statusCell = element('td'); const [label, tone] = channelStatus(channel); const chip = badge(label, tone);
    if (state.status.scan_progress?.[channel.id]?.error) chip.title = state.status.scan_progress[channel.id].error;
    statusCell.append(chip); const actionCell = element('td', 'row-action');
    if (full) {
      const button = element('button', 'icon-button'); button.type = 'button'; button.innerHTML = icon('trash'); button.setAttribute('aria-label', `删除频道 ${title}`); button.title = `删除 ${title}`; button.disabled = locked();
      button.addEventListener('click', () => openDelete(channel)); actionCell.append(button);
    }
    row.append(identityCell, count, statusCell, actionCell); body.append(row);
  });
  table.append(head, body); wrapper.append(table); container.replaceChildren(wrapper);
}
function renderChannels() {
  const query = $('channel-search').value.trim().toLocaleLowerCase(); const filter = $('channel-filter').value;
  let channels = state.channels.filter(c => `${c.title || ''} ${c.ref} ${c.id}`.toLocaleLowerCase().includes(query));
  if (filter === 'empty') channels = channels.filter(c => !state.status.channel_video_counts?.[c.id]);
  else if (filter !== 'all') channels = channels.filter(c => state.status.scan_progress?.[c.id]?.status === filter);
  if ($('channel-sort').value === 'videos') channels.sort((a, b) => (state.status.channel_video_counts?.[b.id] || 0) - (state.status.channel_video_counts?.[a.id] || 0));
  if ($('channel-sort').value === 'name') channels.sort((a, b) => (a.title || a.ref).localeCompare(b.title || b.ref, 'zh-CN'));
  renderTable('channel-list', channels, true); text('filter-result', `显示 ${number(channels.length)} / ${number(state.channels.length)} 个频道`);
  $('channel-lock').hidden = !taskBusy();
}
function logLevel(line) { return /^ERROR\b|失败|错误/.test(line) ? 'error' : /^OK\b/.test(line) ? 'success' : 'neutral'; }
function exportText() { return [...(state.status?.scan_log || []), ...(state.status?.last_error ? ['\n最近一次错误：', state.status.last_error] : [])].join('\n'); }
function renderLogs() {
  const lines = state.status.scan_log || []; const query = $('log-search').value.toLocaleLowerCase(); const filter = $('log-filter').value;
  const visible = lines.map((line, i) => ({ line, i, level: logLevel(line) })).filter(item => item.line.toLocaleLowerCase().includes(query) && (filter === 'all' || item.level === filter));
  text('log-count', number(lines.length)); text('log-result', `显示 ${visible.length} / ${lines.length} 条记录`);
  const key = JSON.stringify(visible);
  if (state.signatures.logs !== key) {
    state.signatures.logs = key;
    if (!visible.length) empty($('log-list'), lines.length ? '没有匹配的记录' : '暂时没有扫描记录', lines.length ? '尝试修改关键词或日志级别。' : '扫描开始后，这里会显示频道进度与处理结果。');
    else {
      const fragment = document.createDocumentFragment();
      visible.forEach(({ line, i, level }) => { const row = element('div', 'log-row'); row.append(element('span', 'log-index', String(i + 1).padStart(2, '0')), badge(({error:'错误', success:'完成', neutral:'信息'})[level], level), element('p', '', line)); fragment.append(row); });
      $('log-list').replaceChildren(fragment);
    }
  }
  $('last-error-panel').hidden = !state.status.last_error; text('last-error', state.status.last_error || '');
  $('copy-logs').disabled = !exportText(); $('download-logs').disabled = !exportText();
}
function renderConnections() {
  const s = state.status;
  setBadge('telegram-badge', s.authorized ? '已连接' : '未连接', s.authorized ? 'success' : 'warning');
  $('account-connected').hidden = !s.authorized || state.loginExpanded;
  $('login-form').hidden = s.authorized && !state.loginExpanded;
  $('cancel-login').hidden = !s.authorized;
  const [label, tone, description] = jellyfinStatus();
  setBadge('settings-jellyfin-badge', s.jellyfin_configured ? '已配置' : '未配置', s.jellyfin_configured ? 'success' : 'neutral');
  text('settings-jellyfin-copy', description); text('settings-notify-state', label); $('settings-notify-state').dataset.state = tone;
  text('settings-notify-time', stamp(s.jellyfin_updated_at));
}
function controls() {
  const busy = locked(), s = state.status;
  $('scan').disabled = busy || !s?.authorized || !state.channels.length;
  $('rebuild').disabled = busy;
  text('scan-label', taskBusy() ? '同步进行中' : state.pending === 'scan' ? '正在启动…' : '扫描并更新');
  ['add-submit', 'show-add'].forEach(id => { $(id).disabled = busy || !s?.authorized; });
  $('channel-ref').disabled = busy || !s?.authorized;
  $('send-code').disabled = busy || !s?.has_credentials;
  $('login-submit').disabled = busy || !state.codeSent;
  ['save-credentials', 'relogin', 'confirm-delete'].forEach(id => { $(id).disabled = busy; });
  ['phone', 'code', 'password', 'app-id', 'app-hash'].forEach(id => { $(id).disabled = busy; });
  ['cancel-delete', 'cancel-delete-icon'].forEach(id => { $(id).disabled = state.pending === 'delete'; });
  $('refresh').disabled = !!state.refresh;
}
function render() {
  const s = state.status;
  if (!s) { controls(); return; }
  text('connection', !state.online ? '服务连接中断' : s.authorized ? 'Telegram 已连接' : 'Telegram 未连接');
  $('connection').dataset.state = !state.online ? 'error' : s.authorized ? 'success' : 'neutral';
  text('nav-count', number(state.channels.length)); $('nav-error').hidden = !s.last_error;
  if (state.view === 'overview') renderOverview();
  if (state.view === 'channels') renderChannels();
  if (state.view === 'logs') renderLogs();
  if (state.view === 'connections') renderConnections();
  controls();
}
function refresh() {
  if (state.refresh) return state.refresh;
  state.refresh = (async () => {
    try {
      const [status, channels] = await Promise.all([api('/api/status'), api('/api/channels')]);
      state.status = status; state.channels = Array.isArray(channels) ? channels : []; state.online = true;
      $('network-banner').hidden = true;
      text('last-read', `更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date())}`);
    } catch (error) {
      state.online = false; $('network-banner').hidden = false;
      text('connection', '服务连接中断'); $('connection').dataset.state = 'error';
      text('network-message', `正在自动重连，操作暂时停用。${state.status ? '当前显示上次读取的数据。' : ''}`);
    } finally { state.refresh = null; render(); }
  })();
  controls();
  return state.refresh;
}
async function mutate(name, path, data, method = 'POST') {
  if (locked()) throw new Error('服务暂不可用或另一个任务正在执行，请稍后重试。');
  state.pending = name; render();
  try { return await api(path, { method, ...(data !== undefined ? { body: JSON.stringify(data) } : {}) }); }
  finally {
    // long: 写操作完成后等待旧轮询退出，再获取新快照，避免旧状态重新启用按钮或复活已删除行。
    if (state.refresh) await state.refresh;
    await refresh(); state.pending = ''; render();
  }
}
function openDelete(channel) {
  if (locked()) return;
  state.deleting = channel; text('delete-name', channel.title || channel.ref); message('delete-message', '');
  $('delete-dialog').showModal();
}
function closeDelete() { if (state.pending !== 'delete') { $('delete-dialog').close(); state.deleting = null; } }
$('delete-dialog').addEventListener('cancel', event => { if (state.pending === 'delete') event.preventDefault(); });
$('cancel-delete').addEventListener('click', closeDelete); $('cancel-delete-icon').addEventListener('click', closeDelete);
$('confirm-delete').addEventListener('click', async () => {
  if (!state.deleting) return;
  try {
    await mutate('delete', `/api/channels/${encodeURIComponent(state.deleting.ref)}`, undefined, 'DELETE');
    closeDelete(); notify('频道与索引已移除，请重建媒体树。'); $('show-add').focus();
  } catch (error) { message('delete-message', error.message, 'error'); }
});
$('show-add').addEventListener('click', () => { $('add-panel').hidden = false; $('channel-ref').focus(); });
$('close-add').addEventListener('click', () => { $('add-panel').hidden = true; $('show-add').focus(); });
$('add-form').addEventListener('submit', async event => {
  event.preventDefault(); const ref = $('channel-ref').value.trim(); if (!ref) return;
  message('add-message', '正在解析频道…');
  try { const result = await mutate('add', '/api/channels', { ref }); $('channel-ref').value = ''; message('add-message', `已添加 ${result.title || ref}，回到同步概览即可扫描。`, 'success'); }
  catch (error) { message('add-message', error.message, 'error'); }
});
$('scan').addEventListener('click', async () => {
  try { await mutate('scan', '/api/scan', {}); notify('扫描已启动，进度会自动更新。'); }
  catch (error) { notify(error.message, 'error'); }
});
$('rebuild').addEventListener('click', async () => {
  try { const result = await mutate('rebuild', '/api/rebuild', {}); notify(result.warning || `已生成 ${number(result.strm_files)} 个媒体入口。${result.jellyfin_message || ''}`, result.warning ? 'error' : 'success'); }
  catch (error) { notify(error.message, 'error'); }
});
['channel-search', 'channel-filter', 'channel-sort'].forEach(id => $(id).addEventListener(id === 'channel-search' ? 'input' : 'change', () => { if (state.status) renderChannels(); }));
$('clear-filters').addEventListener('click', () => { $('channel-search').value = ''; $('channel-filter').value = 'all'; $('channel-sort').value = 'default'; if (state.status) renderChannels(); });
['log-search', 'log-filter'].forEach(id => $(id).addEventListener(id === 'log-search' ? 'input' : 'change', () => { if (state.status) renderLogs(); }));
$('copy-logs').addEventListener('click', async () => {
  try {
    if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(exportText());
    else {
      // long: 局域网 HTTP 不提供 Clipboard API，通过临时文本框保留复制能力，失败时引导下载。
      const area = element('textarea'); area.value = exportText(); area.style.position = 'fixed'; area.style.left = '-9999px'; document.body.append(area); area.select();
      let copied; try { copied = document.execCommand('copy'); } finally { area.remove(); $('copy-logs').focus(); }
      if (!copied) throw new Error('浏览器限制了复制，请使用“下载”保存日志。');
    }
    notify('日志已复制。');
  } catch (error) { notify(error.message || '复制失败，请下载日志。', 'error'); }
});
$('download-logs').addEventListener('click', () => {
  const url = URL.createObjectURL(new Blob([exportText()], { type: 'text/plain;charset=utf-8' }));
  const a = element('a'); a.href = url; a.download = `tgproxy-scan-${new Date().toISOString().slice(0, 10)}.log`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$('relogin').addEventListener('click', () => { state.loginExpanded = true; renderConnections(); $('phone').focus(); });
$('cancel-login').addEventListener('click', () => { state.loginExpanded = false; renderConnections(); });
$('phone').addEventListener('input', () => { state.codeSent = false; state.passwordNeeded = false; $('code-field').hidden = true; $('password-field').hidden = true; controls(); });
$('send-code').addEventListener('click', async () => {
  const phone = $('phone').value.replace(/\s/g, '');
  if (!/^\+\d{6,15}$/.test(phone)) { message('login-message', '请使用国际格式手机号，例如 +8613800138000。', 'error'); $('phone').focus(); return; }
  message('login-message', '正在请求验证码…');
  try {
    await mutate('sendCode', '/api/send-code', { phone });
    state.codeSent = true; state.passwordNeeded = false; $('code-field').hidden = false; $('password-field').hidden = true; text('send-code', '重新发送'); controls();
    message('login-message', '验证码已发送，请在 Telegram 客户端查收。', 'success'); $('code').focus();
  } catch (error) { message('login-message', error.message, 'error'); }
});
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!state.codeSent) return;
  const value = state.passwordNeeded ? $('password').value : $('code').value.trim();
  if (!value) { message('login-message', state.passwordNeeded ? '请输入两步验证密码。' : '请输入验证码。', 'error'); return; }
  message('login-message', '正在验证…');
  try {
    await mutate('login', state.passwordNeeded ? '/api/password' : '/api/sign-in', state.passwordNeeded ? { password: value } : { code: value });
    state.loginExpanded = false; state.codeSent = false; state.passwordNeeded = false;
    $('code').value = ''; $('password').value = ''; $('password-field').hidden = true; $('code-field').hidden = true;
    message('login-message', '授权已完成，正在建立服务连接。', 'success'); render();
  } catch (error) {
    if (/password required/i.test(error.message)) { state.passwordNeeded = true; $('password-field').hidden = false; message('login-message', '账号已启用两步验证，请继续输入密码。'); $('password').focus(); }
    else message('login-message', error.message, 'error');
  }
});
$('credentials-form').addEventListener('submit', async event => {
  event.preventDefault(); const id = $('app-id').value.trim(), hash = $('app-hash').value.trim();
  if (!/^\d+$/.test(id) || Number(id) <= 0 || !Number.isSafeInteger(Number(id)) || !hash) { message('credentials-message', '请填写有效的 API ID 和 API Hash。', 'error'); return; }
  try { await mutate('credentials', '/api/credentials', { app_id: Number(id), app_hash: hash }); $('app-hash').value = ''; state.codeSent = false; state.passwordNeeded = false; $('code-field').hidden = true; $('password-field').hidden = true; controls(); message('credentials-message', '凭证已保存，连接正在恢复。', 'success'); }
  catch (error) { message('credentials-message', error.message, 'error'); }
});
$('theme-toggle').addEventListener('click', () => applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'));
document.querySelectorAll('[data-theme-choice]').forEach(button => button.addEventListener('click', () => applyTheme(button.dataset.themeChoice)));
$('refresh').addEventListener('click', refresh); $('retry').addEventListener('click', refresh);
$('close-toast').addEventListener('click', () => { $('toast').hidden = true; });
window.addEventListener('hashchange', () => route(true));
document.querySelector('.skip-link').addEventListener('click', event => {
  event.preventDefault();
  $('page-title').focus();
});
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
applyTheme(document.documentElement.dataset.theme); route();
// long: 轮询完成后再安排下一次，隐藏标签页减频；任何页面共用一个请求，不因切换导航累积定时器。
async function poll() { await refresh(); setTimeout(poll, document.hidden ? 15000 : 3000); }
poll();
