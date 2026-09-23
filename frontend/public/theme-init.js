(function () {
  var theme = 'light';
  var sidebar = 'expanded';
  try {
    if (localStorage.getItem('pelada.theme') === 'dark') theme = 'dark';
    if (localStorage.getItem('pelada.sidebar') === 'collapsed') sidebar = 'collapsed';
  } catch (_) {
    // Private browsing can disable storage; use the default appearance.
  }
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.sidebar = sidebar;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme === 'dark' ? '#14171a' : '#fbfcf8');
})();
