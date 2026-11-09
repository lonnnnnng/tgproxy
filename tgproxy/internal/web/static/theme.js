// long: 在样式加载前恢复主题，避免深色用户刷新时闪过浅色背景；存储受限不影响页面运行。
try {
  document.documentElement.dataset.theme = localStorage.getItem('tgproxy-theme') === 'dark' ? 'dark' : 'light';
} catch { document.documentElement.dataset.theme = 'light'; }
