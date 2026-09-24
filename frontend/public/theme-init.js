(function () {
  var theme = 'light';
  try {
    var stored = localStorage.getItem('pelada.theme');
    if (stored === 'dark' || stored === 'light') theme = stored;
    else if (window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches) theme = 'dark';
  } catch (_) {
    // Private browsing can disable storage; use the default appearance.
  }
  document.documentElement.dataset.theme = theme;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'dark' ? '#0b1510' : '#10241a');
})();
