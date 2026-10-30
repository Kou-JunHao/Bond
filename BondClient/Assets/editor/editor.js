/* Bond document editor host page. Runs fully offline. */
(function () {
  'use strict';

  var bridge = window.chrome && window.chrome.webview ? window.chrome.webview : null;
  var pendingMarkdownRequest = null;

  function post(msg) {
    try {
      if (bridge) {
        bridge.postMessage(msg);
      }
    } catch (e) {
      /* host gone */
    }
  }

  function fail(message) {
    post({ type: 'error', message: String(message || 'unknown error') });
    var box = document.getElementById('error');
    var text = document.getElementById('error-text');
    if (box && text) {
      text.textContent = String(message || '');
      box.hidden = false;
      var ed = document.getElementById('editor');
      if (ed) {
        ed.style.display = 'none';
      }
    }
  }

  if (typeof window.Quill !== 'function') {
    fail('quill.js not loaded');
    return;
  }
  if (typeof window.marked === 'undefined') {
    fail('marked.min.js not loaded');
    return;
  }
  if (typeof window.TurndownService !== 'function') {
    fail('turndown.js not loaded');
    return;
  }

  var turndown = new window.TurndownService({
    headingStyle: 'atx',
    hr: '---',
    bulletListMarker: '-',
    codeBlockStyle: 'fenced',
    emDelimiter: '*',
    strongDelimiter: '**'
  });
  if (window.turndownPluginGfm && window.turndownPluginGfm.gfm) {
    turndown.use(window.turndownPluginGfm.gfm);
  }
  turndown.keep(['table', 'thead', 'tbody', 'tr', 'th', 'td']);

  var suppress = false;
  var ready = false;
  var lastMarkdown = '';
  var dirtyTimer = 0;

  // task-169：笔记图片只支持「公网 http(s) 外链」和「本地内嵌 data:image/…;base64」两类来源。
  // /api/files/… 鉴权地址、相对路径、本地文件路径一律不解析、不改写、不保存任何 token，
  // 统一显示「图片地址不受支持」占位图并提示用户改用公网链接（安全口径：避免短期凭证长期化）。
  var unsupportedImageCount = 0;
  var unsupportedImageOriginals = [];
  var maxInsertedDataImageChars = 2800000; // ≈2MB 二进制图片的 base64 字符数上限（仅限制新插入）
  var unsupportedPlaceholder = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="520" height="112">'
    + '<rect width="100%" height="100%" rx="8" fill="#f3efe8" stroke="#e2d9cc"/>'
    + '<text x="22" y="48" font-family="Segoe UI, Microsoft YaHei, sans-serif" font-size="17" fill="#8a7f72">图片地址不受支持</text>'
    + '<text x="22" y="78" font-family="Segoe UI, Microsoft YaHei, sans-serif" font-size="13" fill="#a89a8a">仅支持公网 https 图片链接或本地内嵌图片</text>'
    + '</svg>');

  var quill = new window.Quill('#editor', {
    theme: 'snow',
    placeholder: '开始写文档…',
    modules: {
      toolbar: [
        [{ header: [1, 2, 3, false] }],
        ['bold', 'italic', 'underline', 'strike'],
        [{ list: 'ordered' }, { list: 'bullet' }],
        [{ indent: '-1' }, { indent: '+1' }],
        [{ align: [] }],
        ['blockquote', 'code-block'],
        ['link', 'image', 'table'],
        ['clean']
      ]
    }
  });

  // 保存时把「图片不支持」占位图还原成文档里原本写的地址：只还原原文，不做任何解析/转换，
  // 因此 token URL、相对路径等不支持地址不会被客户端改名、也不会被替换成内嵌图片。
  function restoreUnsupportedImages(md) {
    if (!md || unsupportedImageOriginals.length === 0) {
      return md;
    }
    var index = 0;
    return md.replace(/(data:image\/svg\+xml;charset=utf-8,[^)\s]+)/g, function (match) {
      if (index < unsupportedImageOriginals.length) {
        return unsupportedImageOriginals[index++];
      }
      return match;
    });
  }

  function toMarkdown(html) {
    try {
      var source = html && html.length ? html : quill.root.innerHTML;
      var md = turndown.turndown(source || '');
      md = restoreUnsupportedImages(md);
      return md.replace(/\u00a0/g, ' ').replace(/\n{3,}/g, '\n\n').trim() + (md.trim().length ? '\n' : '');
    } catch (e) {
      return lastMarkdown;
    }
  }

  function snapshot() {
    var html = '';
    try {
      html = quill.getSemanticHTML();
    } catch (e) {
      html = quill.root.innerHTML;
    }
    var md = toMarkdown(html);
    lastMarkdown = md;
    return { html: html, markdown: md, text: quill.getText(), words: quill.getLength() };
  }

  function scheduleDirty() {
    if (dirtyTimer) {
      return;
    }
    dirtyTimer = window.setTimeout(function () {
      dirtyTimer = 0;
      if (!ready || suppress) {
        return;
      }
      var snap = snapshot();
      post({ type: 'dirty', markdown: snap.markdown, html: snap.html, text: snap.text, words: snap.words });
    }, 250);
  }

  quill.on('text-change', function (delta, oldDelta, source) {
    if (source === 'api' && suppress) {
      return;
    }
    scheduleDirty();
  });

  function looksLikeImageUrl(text) {
    var value = String(text || '').trim();
    if (!value || /\s/.test(value)) {
      return false;
    }
    if (/^data:image\//i.test(value)) {
      return true;
    }
    if (/^\/api\/(files|chat)\//i.test(value) || /^api\/(files|chat)\//i.test(value)) {
      return true;
    }
    return /^https?:\/\//i.test(value) && /\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?$/i.test(value);
  }

  quill.root.addEventListener('paste', function (e) {
    var data = e.clipboardData;
    if (!data) {
      return;
    }
    var hasFiles = data.files && data.files.length > 0;
    var html = data.getData ? data.getData('text/html') : '';
    var text = data.getData ? data.getData('text/plain') : '';
    if (hasFiles || (html && html.length > 0)) {
      return;
    }
    if (!looksLikeImageUrl(text)) {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    insertImageUrl(text, '');
  }, true);

  function fromMarkdown(markdown) {
    var html = '';
    try {
      html = window.marked.parse(String(markdown || ''), { gfm: true, breaks: false, silent: true });
    } catch (e) {
      html = '<p>' + String(markdown || '').replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</p>';
    }
    return html;
  }

  function notifyUnsupportedImage(src) {
    if (unsupportedImageCount >= 5) {
      return;
    }
    unsupportedImageCount++;
    post({ type: 'imageUnsupported', src: String(src || '') });
  }

  // 安全白名单（task-169）：只接受
  //   ① 公网 http(s) 外链；
  //   ② 本地内嵌 data:image/(png|jpeg|jpg|gif|webp|bmp);base64,…（新插入限制 2MB）。
  // 其他来源（/api/files/… 鉴权地址、/api/chat/files/…、相对路径、file:、本地盘符）一律
  // 不解析、不改写、不追加 token，直接显示「图片地址不受支持」占位图并提示宿主。
  function isAllowedDataImage(value) {
    return /^data:image\/(png|jpeg|jpg|gif|webp|bmp);base64,/i.test(value);
  }

  function isUnsupportedPlaceholder(src) {
    return src === unsupportedPlaceholder;
  }

  // 明确拒绝「后端鉴权地址」：即使用户把它写成绝对 https（含 /api/** 或 ?token=），
  // 也不在客户端渲染，避免把受保护资源当图床/把短期凭证长期留在文档里。
  function isDisallowedRemoteImage(value) {
    try {
      var u = new URL(value, window.location.href);
      var path = (u.pathname || '').toLowerCase();
      if (path === '/api' || path.indexOf('/api/') === 0) {
        return true;
      }
      return (u.search || '').toLowerCase().indexOf('token=') >= 0;
    } catch (e) {
      return true;
    }
  }

  function normalizeImageSrc(src, enforceInsertSize) {
    var value = String(src || '').trim();
    if (!value) {
      return value;
    }
    if (isAllowedDataImage(value)) {
      if (enforceInsertSize && value.length > maxInsertedDataImageChars) {
        notifyUnsupportedImage('data-image-too-large');
        return unsupportedPlaceholder;
      }
      return value;
    }
    if (/^https?:\/\//i.test(value)) {
      if (isDisallowedRemoteImage(value)) {
        notifyUnsupportedImage(value);
        return unsupportedPlaceholder;
      }
      return value;
    }
    notifyUnsupportedImage(value);
    return unsupportedPlaceholder;
  }

  function normalizeHtmlImages(html) {
    try {
      var holder = document.createElement('div');
      holder.innerHTML = html;
      var images = holder.querySelectorAll('img');
      var changed = false;
      for (var i = 0; i < images.length; i++) {
        var src = images[i].getAttribute('src');
        var fixed = normalizeImageSrc(src, false);
        if (fixed === src) {
          continue;
        }

        images[i].setAttribute('src', fixed);
        changed = true;
        if (fixed === unsupportedPlaceholder && !isUnsupportedPlaceholder(src)) {
          unsupportedImageOriginals.push(String(src || ''));
        }
      }
      return changed ? holder.innerHTML : html;
    } catch (e) {
      return html;
    }
  }

  function insertImageUrl(url, alt) {
    var fixed = normalizeImageSrc(url, true);
    if (!fixed) {
      return false;
    }
    if (fixed === unsupportedPlaceholder) {
      unsupportedImageOriginals.push(String(url || ''));
    }
    var range = quill.getSelection(true) || { index: quill.getLength() };
    var index = range.index;
    quill.insertEmbed(index, 'image', fixed, 'user');
    quill.insertText(index + 1, '\n', 'user');
    quill.setSelection(index + 2, 0, 'silent');
    return true;
  }

  function setMarkdown(markdown) {
    suppress = true;
    try {
      if (!markdown) {
        quill.setContents([]);
      } else {
        quill.clipboard.dangerouslyPasteHTML(normalizeHtmlImages(fromMarkdown(markdown)));
      }
      lastMarkdown = toMarkdown(quill.getSemanticHTML());
    } catch (e) {
      fail('load markdown failed: ' + (e && e.message ? e.message : e));
    } finally {
      suppress = false;
    }
    post({ type: 'loaded' });
  }

  function applyTheme(dark) {
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  }

  function handleHostMessage(event) {
    var msg = event.data;
    if (!msg || !msg.type) {
      return;
    }
    switch (msg.type) {
      case 'load':
        setMarkdown(msg.markdown);
        break;
      case 'theme':
        applyTheme(!!msg.dark);
        break;
      case 'focus':
        try {
          quill.focus();
          var len = quill.getLength();
          quill.setSelection(Math.max(0, len - 1), 0, 'silent');
        } catch (e) {
          /* ignore */
        }
        break;
      case 'readonly':
        try {
          quill.enable(!msg.on);
        } catch (e) {
          /* ignore */
        }
        break;
      case 'requestMarkdown':
        pendingMarkdownRequest = msg.id || '';
        snapshot();
        post({ type: 'markdown', id: pendingMarkdownRequest, markdown: lastMarkdown });
        break;
      case 'insertImage':
        try {
          if (!insertImageUrl(msg.url, msg.alt)) {
            post({ type: 'error', message: 'insert image failed: unsupported url ' + String(msg.url || '') });
          }
        } catch (e) {
          post({ type: 'error', message: 'insert image failed: ' + (e && e.message ? e.message : e) });
        }
        break;
      case 'insertLink':
        try {
          var sel = quill.getSelection(true);
          var at = sel ? sel.index : quill.getLength();
          var text = msg.text || msg.url;
          quill.insertText(at, text, 'link', msg.url, 'user');
        } catch (e) {
          /* ignore */
        }
        break;
      default:
        break;
    }
  }

  if (bridge) {
    bridge.addEventListener('message', handleHostMessage);
  }

  document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();
      post({ type: 'save' });
    }
  });

  window.addEventListener('error', function (e) {
    if (e && e.message) {
      post({ type: 'error', message: e.message });
    }
  });

  window.__bond = {
    setMarkdown: setMarkdown,
    getMarkdown: function () {
      return toMarkdown(quill.getSemanticHTML());
    },
    getHtml: function () {
      return quill.getSemanticHTML();
    },
    setTheme: applyTheme,
    focus: function () {
      quill.focus();
    },
    setReadOnly: function (on) {
      quill.enable(!on);
    },
    insertImage: function (url, alt) {
      insertImageUrl(url, alt);
    },
    getText: function () {
      return quill.getText();
    },
    version: (window.Quill && window.Quill.version) || ''
  };

  try {
    applyTheme(true === false);
  } catch (e) {
    /* ignore */
  }
  ready = true;
  post({ type: 'ready', quillVersion: (window.Quill && window.Quill.version) || '' });
})();
