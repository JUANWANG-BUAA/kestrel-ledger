(function () {
  var root = document.documentElement;
  var toggle = document.querySelector('[data-theme-toggle]');
  var label = document.querySelector('[data-theme-label]');
  var icon = document.querySelector('[data-theme-icon]');
  var media = window.matchMedia('(prefers-color-scheme: dark)');

  function current() {
    return root.dataset.theme === 'dark' ? 'dark' : 'light';
  }

  function apply(theme, persist) {
    var dark = theme === 'dark';
    root.dataset.theme = dark ? 'dark' : 'light';
    root.style.colorScheme = dark ? 'dark' : 'light';
    if (label) label.textContent = dark ? '浅色' : '深色';
    if (icon) icon.textContent = dark ? '☀' : '☾';
    if (toggle) {
      toggle.setAttribute('aria-pressed', String(dark));
      toggle.setAttribute('aria-label', dark ? '切换到浅色外观' : '切换到深色外观');
    }
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#11110f' : '#f3efe6');
    if (persist) {
      try { localStorage.setItem('kestrel-theme', dark ? 'dark' : 'light'); } catch (e) {}
    }
  }

  var forced = null;
  try { forced = new URLSearchParams(window.location.search).get('theme'); } catch (e) {}
  if (forced === 'dark' || forced === 'light') apply(forced, false);
  else apply(current(), false);

  if (toggle) {
    toggle.addEventListener('click', function () {
      apply(current() === 'dark' ? 'light' : 'dark', true);
    });
  }

  // If the visitor never made an explicit choice, follow a live OS theme change.
  var onMediaChange = function (event) {
    var saved = null;
    try { saved = localStorage.getItem('kestrel-theme'); } catch (e) {}
    if (!saved) apply(event.matches ? 'dark' : 'light', false);
  };
  if (media.addEventListener) media.addEventListener('change', onMediaChange);

  var downloadStatus = document.querySelector('[data-copy-status]');
  var copyButton = document.querySelector('[data-copy-download]');
  var primaryDownload = document.querySelector('[data-download]');

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (resolve, reject) {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try {
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        ok ? resolve() : reject(new Error('copy blocked'));
      } catch (error) {
        document.body.removeChild(area);
        reject(error);
      }
    });
  }

  if (copyButton && primaryDownload) {
    copyButton.addEventListener('click', function () {
      copyText(primaryDownload.href).then(function () {
        if (downloadStatus) downloadStatus.textContent = '链接已复制';
      }).catch(function () {
        if (downloadStatus) downloadStatus.textContent = '复制失败，请用浏览器打开';
      });
    });
  }

  document.querySelectorAll('[data-download]').forEach(function (link) {
    link.addEventListener('click', function () {
      if (downloadStatus) downloadStatus.textContent = '已发起下载；没有反应就复制链接';
    });
  });

  if (/MicroMessenger/i.test(navigator.userAgent)) {
    document.querySelectorAll('[data-wechat-tip]').forEach(function (tip) { tip.hidden = false; });
  }

  var year = document.querySelector('[data-year]');
  if (year) year.textContent = String(new Date().getFullYear());

  document.querySelectorAll('.mobile-menu a').forEach(function (link) {
    link.addEventListener('click', function () {
      var menu = link.closest('details');
      if (menu) menu.open = false;
    });
  });

  var revealItems = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          observer.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });
    revealItems.forEach(function (item) { observer.observe(item); });
  } else {
    revealItems.forEach(function (item) { item.classList.add('is-visible'); });
  }
}());
