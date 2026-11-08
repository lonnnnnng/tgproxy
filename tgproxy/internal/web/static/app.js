const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || response.statusText || '请求失败');
  return data;
}

let codeSent = false;
let awaitingPassword = false;
let refreshing = false;
let channels = [];
let latestStatus = null;
let toastTimer = 0;

function showMessage(id, message, state = 'info') {
  const element = $(id);
  element.textContent = message;
  element.dataset.state = state;
  element.hidden = !message;
}

function showToast(message, state = 'success') {
  const toast = $('toast');
  toast.textContent = message;
  toast.dataset.state = state;
  toast.hidden = !message;
  window.clearTimeout(toastTimer);
  if (message) toastTimer = window.setTimeout(() => { toast.hidden = true; }, 5000);
}

function applyTheme(theme, persist = true) {
  const selected = theme === 'dark' ? 'dark' : 'light';
  document.documentElement.dataset.theme = selected;
  const nextTheme = selected === 'dark' ? '浅色' : '深色';
  $('theme-toggle').setAttribute('aria-label', `切换为${nextTheme}主题`);
  $('theme-toggle').title = `切换为${nextTheme}主题`;
  if (persist) localStorage.setItem('tgproxy-theme', selected);
}

function setStage(id, state, status, detail) {
  const stage = $(`${id}-stage`);
  stage.dataset.state = state;
  $(`${id}-stage-status`).textContent = status;
  $(`${id}-stage-detail`).textContent = detail;
}

function renderStages(status) {
  const progress = status.scan_progress || {};
  const progressRows = channels.map((channel) => progress[channel.id]).filter(Boolean);
  const hasScan = progressRows.length > 0;
  const failedScans = progressRows.filter((item) => item.status === 'failed').length;
  const completedScans = progressRows.filter((item) => item.status === 'completed').length;

  if (!status.authorized) {
    setStage('telegram', 'waiting', '等待登录', '登录 Telegram 后可扫描频道');
  } else if (status.scanning) {
    setStage('telegram', 'active', '扫描中', '按频道顺序读取视频历史');
  } else if (failedScans > 0) {
    setStage('telegram', 'error', '有扫描失败', `${failedScans} 个频道需要检查日志`);
  } else if (hasScan && completedScans === progressRows.length) {
    setStage('telegram', 'done', '扫描完成', `${completedScans} 个频道已处理`);
  } else {
    setStage('telegram', 'waiting', '待扫描', '扫描会更新频道视频索引');
  }

  if (status.rebuilding) {
    setStage('tree', 'active', '生成中', '正在写入 Jellyfin 媒体入口');
  } else if (status.needs_rebuild) {
    setStage('tree', 'waiting', '待重建', '索引变化后需要生成媒体树');
  } else {
    setStage('tree', 'done', '已生成', status.strm_files_known ? `${status.strm_files} 个媒体入口` : '媒体树已存在');
  }

  if (!status.jellyfin_configured) {
    setStage('jellyfin', 'waiting', '未配置通知', '媒体树生成后需手动刷新');
  } else if (status.jellyfin_state === 'notifying') {
    setStage('jellyfin', 'active', '提交中', '正在请求 Jellyfin 刷新媒体库');
  } else if (status.needs_rebuild || status.scanning || status.rebuilding) {
    setStage('jellyfin', 'waiting', '等待媒体树', '媒体树更新后才会提交刷新');
  } else if (status.jellyfin_state === 'notified') {
    setStage('jellyfin', 'done', '已通知', 'Jellyfin 已收到刷新请求');
  } else if (status.jellyfin_state === 'failed') {
    setStage('jellyfin', 'error', '通知失败', '检查 Jellyfin 地址和 API 密钥');
  } else {
    setStage('jellyfin', 'waiting', '尚未执行', '生成媒体树后会提交刷新');
  }
}

function renderProgress(status) {
  const scanProgress = status.scan_progress || {};
  const activeId = String(status.active_channel_id || '');
  const active = channels.find((channel) => String(channel.id) === activeId);
  const finished = channels.filter((channel) => ['completed', 'failed'].includes(scanProgress[channel.id]?.status)).length;
  const failures = channels.filter((channel) => scanProgress[channel.id]?.status === 'failed').length;
  const currentProgress = active ? scanProgress[active.id] : null;

  $('scan-focus-title').textContent = status.scanning
    ? (active?.title || active?.ref || '正在扫描频道')
    : status.rebuilding ? '正在生成媒体树'
      : failures ? `${failures} 个频道扫描失败`
        : channels.length ? '等待下一次扫描' : '尚未添加频道';
  $('scan-focus-count').textContent = status.scanning && currentProgress
    ? String(currentProgress.videos_found || 0)
    : String(status.videos || 0);
  $('scan-focus-caption').textContent = status.scanning && currentProgress ? '本频道已发现' : '已索引视频';

  let summary = channels.length ? `${finished} / ${channels.length} 个频道已处理` : '添加频道后可开始扫描';
  if (status.scanning && active) summary += ` · 正在扫描 ${active.title || active.ref}`;
  else if (status.rebuilding) summary += ' · 正在生成媒体树';
  else if (failures) summary += ` · ${failures} 个频道保留原有索引`;
  else if (finished === channels.length && channels.length > 0) summary += ' · 最近一次扫描已完成';
  $('scan-summary').textContent = summary;

  const indicator = $('sync-indicator');
  const state = status.scanning ? 'active' : status.rebuilding ? 'active' : failures ? 'error' : finished ? 'done' : 'idle';
  indicator.dataset.state = state;
  indicator.textContent = status.scanning ? '扫描进行中' : status.rebuilding ? '生成媒体树' : failures ? '需要检查' : finished ? '扫描完成' : '等待扫描';

  const segments = $('progress-segments');
  segments.replaceChildren();
  channels.forEach((channel) => {
    const segment = document.createElement('span');
    const channelState = scanProgress[channel.id]?.status || 'idle';
    segment.className = 'progress-segment';
    segment.dataset.state = channelState;
    segment.title = `${channel.title || channel.ref}：${channelStateLabel(channelState)}`;
    segments.append(segment);
  });
  segments.setAttribute('aria-valuemax', String(channels.length));
  segments.setAttribute('aria-valuenow', String(finished));
  $('progress-count').textContent = `${finished} / ${channels.length}`;
  $('progress-label').textContent = status.scanning && active ? `正在处理：${active.title || active.ref}` : '频道处理进度';

  $('indexed-videos').textContent = String(status.videos || 0);
  $('media-entries').textContent = status.needs_rebuild ? '待生成' : status.strm_files_known ? String(status.strm_files) : '已生成';
  $('jellyfin-updated').textContent = formatTimestamp(status.jellyfin_updated_at);
}

function channelStateLabel(state) {
  return ({ queued: '等待扫描', scanning: '扫描中', completed: '已完成', failed: '失败', idle: '尚未扫描' })[state] || '尚未扫描';
}

function renderSchedule(status) {
  const rows = $('channel-schedule');
  const scanProgress = status.scan_progress || {};
  const videoCounts = status.channel_video_counts || {};
  rows.replaceChildren();
  $('schedule-empty').hidden = channels.length > 0;
  channels.forEach((channel, index) => {
    const progress = scanProgress[channel.id] || {};
    const row = document.createElement('li');
    row.className = 'schedule-row';
    row.dataset.state = progress.status || 'idle';

    const number = document.createElement('span');
    number.className = 'schedule-order';
    number.textContent = String(index + 1).padStart(2, '0');

    const identity = document.createElement('div');
    identity.className = 'schedule-identity';
    const title = document.createElement('strong');
    title.textContent = channel.title || channel.ref || '未命名频道';
    const ref = document.createElement('span');
    ref.textContent = channel.ref || String(channel.id);
    identity.append(title, ref);

    const state = document.createElement('span');
    state.className = 'schedule-state';
    state.textContent = channelStateLabel(progress.status || 'idle');

    const videoCount = document.createElement('span');
    videoCount.className = 'schedule-count';
    if (progress.status === 'scanning') videoCount.textContent = `已发现 ${progress.videos_found || 0} 个`;
    else if (progress.status === 'failed') videoCount.textContent = `保留索引 ${videoCounts[channel.id] || 0} 个`;
    else videoCount.textContent = `已索引 ${videoCounts[channel.id] || 0} 个`;

    row.append(number, identity, state, videoCount);
    if (progress.error) {
      const error = document.createElement('span');
      error.className = 'schedule-error';
      error.textContent = progress.error;
      row.append(error);
    }
    rows.append(row);
  });
}

function renderJellyfin(status) {
  const label = $('jellyfin-status');
  if (!status.jellyfin_configured) {
    label.dataset.state = 'warning';
    label.textContent = '未配置自动通知';
    $('jellyfin-message').textContent = status.jellyfin_message || '媒体树生成后，请在 Jellyfin 中手动刷新媒体库。';
    return;
  }
  if (status.scanning) {
    label.dataset.state = 'idle';
    label.textContent = '等待扫描完成';
    $('jellyfin-message').textContent = '本次扫描完成并生成媒体树后，会向 Jellyfin 提交新的刷新请求。';
    return;
  }
  if (status.rebuilding || status.needs_rebuild) {
    label.dataset.state = 'idle';
    label.textContent = status.rebuilding ? '等待媒体树生成' : '媒体树待更新';
  } else if (status.jellyfin_state === 'notifying') {
    label.dataset.state = 'active';
    label.textContent = '提交刷新请求';
  } else if (status.jellyfin_state === 'notified') {
    label.dataset.state = 'done';
    label.textContent = '已通知 Jellyfin';
  } else if (status.jellyfin_state === 'failed') {
    label.dataset.state = 'error';
    label.textContent = '通知失败';
  } else {
    label.dataset.state = 'idle';
    label.textContent = '尚未通知';
  }
  $('jellyfin-message').textContent = status.jellyfin_message || '媒体树生成后会向 Jellyfin 提交刷新请求。';
}

function formatTimestamp(value) {
  if (!value) return '尚无记录';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function renderChannels(status) {
  const body = $('channel-rows');
  const query = $('channel-search').value.trim().toLocaleLowerCase();
  const visible = channels.filter((channel) => `${channel.title || ''} ${channel.ref || ''} ${channel.id}`.toLocaleLowerCase().includes(query));
  body.replaceChildren();
  $('channel-count').textContent = String(channels.length);

  if (visible.length === 0) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.className = 'table-empty';
    cell.colSpan = 4;
    cell.textContent = channels.length ? '没有匹配的频道' : '尚未添加频道';
    row.append(cell);
    body.append(row);
    return;
  }

  visible.forEach((channel) => {
    const row = document.createElement('tr');
    const title = document.createElement('td');
    title.dataset.label = '频道';
    title.className = 'channel-title';
    title.textContent = channel.title || '未命名频道';
    const ref = document.createElement('td');
    ref.dataset.label = '引用';
    ref.textContent = channel.ref || '—';
    const id = document.createElement('td');
    id.dataset.label = 'Telegram ID';
    id.className = 'numeric';
    id.textContent = String(channel.id);
    const actions = document.createElement('td');
    actions.dataset.label = '操作';
    actions.className = 'row-actions';
    const remove = document.createElement('button');
    remove.className = 'delete-button';
    remove.type = 'button';
    remove.title = `移除 ${channel.title || channel.ref}`;
    remove.setAttribute('aria-label', remove.title);
    remove.disabled = status.scanning || status.rebuilding || !status.authorized;
    remove.innerHTML = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 6h12m-10 0 .7 10h6.6l.7-10M8 6V4h4v2m-3 3v4m2-4v4"></path></svg>';
    remove.addEventListener('click', async () => {
      if (!window.confirm(`移除频道“${channel.title || channel.ref}”？现有扫描内容仍保留在媒体索引中。`)) return;
      remove.disabled = true;
      try {
        await api(`/api/channels/${encodeURIComponent(channel.ref)}`, { method: 'DELETE' });
        showToast('频道已移除');
        await refreshStatus();
      } catch (error) {
        showToast(`移除失败：${error.message}`, 'error');
        remove.disabled = false;
      }
    });
    actions.append(remove);
    row.append(title, ref, id, actions);
    body.append(row);
  });
}

async function refreshStatus() {
  if (refreshing) return;
  refreshing = true;
  try {
    const [status, channelList] = await Promise.all([api('/api/status'), api('/api/channels')]);
    latestStatus = status;
    channels = channelList || [];

    const connection = $('telegram-status');
    connection.dataset.state = status.authorized ? 'connected' : 'disconnected';
    connection.textContent = status.authorized ? 'Telegram 已连接' : 'Telegram 未登录';
    $('login-card').hidden = status.authorized;
    const credentialsCard = $('credentials-card');
    const credentialsWereVisible = !credentialsCard.hidden;
    credentialsCard.hidden = status.has_credentials;
    if (!status.has_credentials && !credentialsWereVisible) credentialsCard.open = true;
    if (status.has_credentials) credentialsCard.open = false;

    renderStages(status);
    renderProgress(status);
    renderSchedule(status);
    renderJellyfin(status);
    renderChannels(status);

    const taskBusy = status.scanning || status.rebuilding;
    $('btn-scan').disabled = !status.authorized || taskBusy || channels.length === 0;
    $('btn-rebuild').disabled = !status.authorized || taskBusy;
    $('add-form').querySelector('button[type="submit"]').disabled = !status.authorized || taskBusy;
    $('channel-ref').disabled = !status.authorized || taskBusy;
    $('phone').disabled = taskBusy;
    $('code').disabled = taskBusy;
    $('password').disabled = taskBusy;
    $('send-code').disabled = taskBusy || !status.has_credentials;
    $('do-login').disabled = taskBusy || !status.has_credentials;

    const lines = [...(status.scan_log || [])];
    if (status.last_error) lines.push(`最后一次错误：${status.last_error}`);
    $('scan-log').textContent = lines.length ? lines.join('\n') : '暂无扫描日志';
    $('log-count').textContent = lines.length ? `（${lines.length} 条）` : '';
    $('diagnostics').hidden = lines.length === 0;
    $('diagnostics').dataset.state = status.last_error ? 'error' : 'normal';
  } catch (error) {
    $('telegram-status').dataset.state = 'error';
    $('telegram-status').textContent = `服务状态读取失败：${error.message}`;
  } finally {
    refreshing = false;
  }
}

const savedTheme = localStorage.getItem('tgproxy-theme');
applyTheme(savedTheme || 'light', false);
$('theme-toggle').addEventListener('click', () => {
  applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
});

$('creds-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const appId = Number.parseInt($('app-id').value.trim(), 10);
  const appHash = $('app-hash').value.trim();
  if (!appId || !appHash) {
    showMessage('creds-msg', '请填写 api_id 和 api_hash。', 'error');
    return;
  }
  const button = $('creds-form').querySelector('button[type="submit"]');
  button.disabled = true;
  showMessage('creds-msg', '正在保存凭证…', 'pending');
  try {
    await api('/api/credentials', { method: 'POST', body: JSON.stringify({ app_id: appId, app_hash: appHash }) });
    showMessage('creds-msg', '凭证已保存，请继续登录 Telegram。', 'success');
    await refreshStatus();
  } catch (error) {
    showMessage('creds-msg', `保存失败：${error.message}`, 'error');
  } finally {
    button.disabled = false;
  }
});

$('send-code').addEventListener('click', async () => {
  const phone = $('phone').value.trim();
  if (!phone) {
    showMessage('login-msg', '请先填写带国家区号的手机号。', 'error');
    $('phone').focus();
    return;
  }
  $('send-code').disabled = true;
  showMessage('login-msg', '正在向 Telegram 请求验证码…', 'pending');
  try {
    await api('/api/send-code', { method: 'POST', body: JSON.stringify({ phone }) });
    codeSent = true;
    awaitingPassword = false;
    $('password-field').hidden = true;
    $('code').focus();
    showMessage('login-msg', '验证码已发送，请填写后登录。', 'success');
  } catch (error) {
    showMessage('login-msg', `发送失败：${error.message}`, 'error');
  } finally {
    $('send-code').disabled = false;
  }
});

$('login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = $('do-login');
  button.disabled = true;
  try {
    if (awaitingPassword) {
      const password = $('password').value;
      if (!password) {
        showMessage('login-msg', '请填写 Telegram 两步验证密码。', 'error');
        $('password').focus();
        return;
      }
      await api('/api/password', { method: 'POST', body: JSON.stringify({ password }) });
      showMessage('login-msg', 'Telegram 登录成功。', 'success');
    } else {
      const code = $('code').value.trim();
      if (!codeSent) {
        showMessage('login-msg', '请先发送验证码。', 'error');
        return;
      }
      if (!code) {
        showMessage('login-msg', '请填写 Telegram 验证码。', 'error');
        $('code').focus();
        return;
      }
      await api('/api/sign-in', { method: 'POST', body: JSON.stringify({ code }) });
      showMessage('login-msg', 'Telegram 登录成功。', 'success');
    }
    await refreshStatus();
  } catch (error) {
    if (/password required/i.test(error.message)) {
      awaitingPassword = true;
      $('password-field').hidden = false;
      $('password').focus();
      showMessage('login-msg', '此账号已启用两步验证，请输入密码。', 'pending');
    } else {
      showMessage('login-msg', `登录失败：${error.message}`, 'error');
    }
  } finally {
    button.disabled = false;
  }
});

$('add-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = $('channel-ref');
  const button = $('add-form').querySelector('button[type="submit"]');
  const ref = input.value.trim();
  if (!ref) {
    showMessage('channel-message', '请填写 Telegram 频道引用。', 'error');
    input.focus();
    return;
  }
  button.disabled = true;
  showMessage('channel-message', '正在解析频道…', 'pending');
  try {
    const result = await api('/api/channels', { method: 'POST', body: JSON.stringify({ ref }) });
    input.value = '';
    showMessage('channel-message', `已添加 ${result.title || ref}。`, 'success');
    await refreshStatus();
  } catch (error) {
    showMessage('channel-message', `添加失败：${error.message}`, 'error');
  } finally {
    button.disabled = latestStatus?.scanning || latestStatus?.rebuilding || !latestStatus?.authorized;
  }
});

$('channel-search').addEventListener('input', () => {
  if (latestStatus) renderChannels(latestStatus);
});

$('btn-scan').addEventListener('click', async () => {
  $('btn-scan').disabled = true;
  try {
    await api('/api/scan', { method: 'POST', body: '{}' });
    showToast('扫描已开始，频道进度会自动更新。');
    await refreshStatus();
  } catch (error) {
    showToast(`无法开始扫描：${error.message}`, 'error');
    await refreshStatus();
  }
});

$('btn-rebuild').addEventListener('click', async () => {
  $('btn-rebuild').disabled = true;
  try {
    const result = await api('/api/rebuild', { method: 'POST', body: JSON.stringify({}) });
    showToast(result.warning || result.jellyfin_message || `媒体树已生成，共 ${result.strm_files} 个入口。`, result.warning ? 'warning' : 'success');
    await refreshStatus();
  } catch (error) {
    showToast(`重建失败：${error.message}`, 'error');
    await refreshStatus();
  }
});

// long：轮询串行执行，避免慢响应与下一轮交错覆盖正在显示的频道进度。
async function pollStatus() {
  await refreshStatus();
  window.setTimeout(pollStatus, 3000);
}

pollStatus();
