const $ = (id) => document.getElementById(id);

async function api(path, opts) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

let codeSent = false;

async function refreshStatus() {
  try {
    const st = await api('/api/status');
    $('creds-card').hidden = st.has_credentials;
    const dot = st.authorized ? 'dot-ok' : 'dot-bad';
    const auth = st.authorized ? 'Telegram 已连接' : 'Telegram 未登录';
    const scan = st.scanning ? ' · 扫描中…' : '';
    $('status').innerHTML =
      `<span class="status-dot ${dot}"></span>${auth}${scan} · ` +
      `频道 ${st.channels} 个 · 媒体 ${st.items} 条`;
    $('login-card').hidden = st.authorized;
    $('channels-card').hidden = !st.authorized;
    if (st.authorized) refreshChannels();
    if (st.scan_log && st.scan_log.length) {
      $('scan-log').hidden = false;
      $('scan-log').textContent = st.scan_log.join('\n');
    }
    if (st.last_error) {
      $('scan-log').hidden = false;
      $('scan-log').textContent += '\n[最后错误] ' + st.last_error;
    }
  } catch (e) {
    $('status').textContent = '状态获取失败: ' + e.message;
  }
}

async function refreshChannels() {
  try {
    const list = await api('/api/channels');
    const tbody = $('channel-rows');
    if (!list.length) {
      tbody.innerHTML = '<tr><td colspan="4">（无）</td></tr>';
      return;
    }
    tbody.innerHTML = list.map((c) =>
      `<tr><td>${esc(c.title || '(未解析)')}</td><td>${esc(c.ref)}</td><td>${c.id}</td>` +
      `<td><button class="del" data-ref="${esc(c.ref)}">删除</button></td></tr>`
    ).join('');
    tbody.querySelectorAll('.del').forEach((btn) =>
      btn.addEventListener('click', async () => {
        await api('/api/channels/' + encodeURIComponent(btn.dataset.ref), { method: 'DELETE' });
        refreshChannels(); refreshStatus();
      })
    );
  } catch (e) { console.error(e); }
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function setMsg(ok, text) {
  const el = $('login-msg');
  el.className = 'msg ' + (ok ? 'ok' : 'err');
  el.textContent = text;
}

$('creds-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const msg = $('creds-msg');
  const id = parseInt($('app-id').value.trim(), 10);
  const hash = $('app-hash').value.trim();
  msg.className = 'msg';
  if (!id || !hash) { msg.textContent = '请填写 api_id 和 api_hash'; return; }
  try {
    await api('/api/credentials', { method: 'POST', body: JSON.stringify({ app_id: id, app_hash: hash }) });
    msg.className = 'msg ok';
    msg.textContent = '凭证已保存，请继续登录 Telegram';
    refreshStatus();
  } catch (e) {
    msg.className = 'msg err';
    msg.textContent = '保存失败: ' + e.message;
  }
});

let awaitingPassword = false;

$('send-code').addEventListener('click', async () => {
  const phone = $('phone').value.trim();
  if (!phone) { setMsg(false, '请先填手机号'); return; }
  $('send-code').disabled = true;
  try {
    await api('/api/send-code', { method: 'POST', body: JSON.stringify({ phone }) });
    codeSent = true;
    awaitingPassword = false;
    setMsg(true, '验证码已发送，请查收后点「登录」');
  } catch (e) {
    setMsg(false, '发送失败: ' + e.message);
  } finally { $('send-code').disabled = false; }
});

$('login-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const code = $('code').value.trim();
  const password = $('password').value;
  $('do-login').disabled = true;
  try {
    if (awaitingPassword) {
      // complete 2FA
      await api('/api/password', { method: 'POST', body: JSON.stringify({ password }) });
      setMsg(true, '登录成功！');
      refreshStatus();
    } else {
      if (!codeSent) { setMsg(false, '请先点「发送验证码」'); return; }
      if (!code) { setMsg(false, '请填验证码'); return; }
      try {
        await api('/api/sign-in', { method: 'POST', body: JSON.stringify({ code }) });
        setMsg(true, '登录成功！');
        refreshStatus();
      } catch (e) {
        if (/password required/i.test(e.message)) {
          awaitingPassword = true;
          setMsg(true, '需要两步验证：请填写密码后再点「登录」');
        } else {
          setMsg(false, '登录失败: ' + e.message);
        }
      }
    }
  } catch (e) {
    setMsg(false, '登录失败: ' + e.message);
  } finally { $('do-login').disabled = false; }
});

$('add-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const ref = $('channel-ref').value.trim();
  if (!ref) return;
  try {
    await api('/api/channels', { method: 'POST', body: JSON.stringify({ ref }) });
    $('channel-ref').value = '';
    refreshChannels(); refreshStatus();
  } catch (e) { alert('添加失败: ' + e.message); }
});

$('btn-scan').addEventListener('click', async () => {
  try {
    await api('/api/scan', { method: 'POST', body: '{}' });
    $('scan-log').hidden = false;
    $('scan-log').textContent = '扫描已启动…';
    poll = setInterval(refreshStatus, 3000);
  } catch (e) { alert(e.message); }
});

$('btn-rebuild').addEventListener('click', async () => {
  try {
    const r = await api('/api/rebuild', { method: 'POST', body: '{}' });
    alert(`已重建：${r.groups} 个剧集组，${r.strm_files} 个 strm 文件\n目录: ${r.media_dir}`);
  } catch (e) { alert(e.message); }
});

let poll = setInterval(refreshStatus, 5000);
refreshStatus();
