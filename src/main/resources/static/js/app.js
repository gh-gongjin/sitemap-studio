window.SitemapUI = (function () {
  var toastEl = null;
  var toastTimer = null;
  var modal = null;
  var modalKeyHandler = null;
  var modalOutsideHandler = null;
  var ICON_ALERT = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16.5v.01"/></svg>';

  function toast(message) {
    if (!toastEl) {
      toastEl = document.getElementById("toast");
    }
    if (!toastEl) return;
    toastEl.textContent = message;
    toastEl.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove("on"); }, 1800);
  }

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function highlight(xml) {
    return esc(xml)
      .replace(/(&gt;)([^&<]*\S[^&<]*)(&lt;)/g, "$1<span class=\"v\">$2</span>$3")
      .replace(/(&lt;\/?\??[^&]*?\/?\??&gt;)/g, "<span class=\"t\">$1</span>");
  }

  function highlightInto(codeEl) {
    if (!codeEl) return;
    codeEl.innerHTML = highlight(codeEl.textContent);
  }

  function copyText(text, okMessage, failMessage) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () { toast(okMessage); },
        function () { toast(failMessage); }
      );
    } else {
      toast(failMessage);
    }
  }

  function closeModal(result) {
    if (!modal) return;
    var m = modal;
    modal = null;
    document.removeEventListener("keydown", modalKeyHandler, true);
    document.removeEventListener("mousedown", modalOutsideHandler, true);
    document.body.classList.remove("modal-open");
    m.overlay.classList.remove("on");
    window.setTimeout(function () {
      if (m.overlay.parentNode) m.overlay.parentNode.removeChild(m.overlay);
    }, 180);
    if (m.prevFocus && m.prevFocus.focus) m.prevFocus.focus();
    m.resolve(result);
  }

  function confirmDialog(opts) {
    opts = opts || {};
    if (modal) closeModal(false);
    return new Promise(function (resolve) {
      var overlay = document.createElement("div");
      overlay.className = "modal";

      var card = document.createElement("div");
      card.className = "modal-card" + (opts.danger ? " danger" : "");
      card.setAttribute("role", "alertdialog");
      card.setAttribute("aria-modal", "true");

      var head = document.createElement("div");
      head.className = "modal-head";

      var ico = document.createElement("div");
      ico.className = "modal-ico";
      ico.innerHTML = ICON_ALERT;
      head.appendChild(ico);

      if (opts.title) {
        var title = document.createElement("b");
        title.className = "modal-title";
        title.textContent = opts.title;
        card.setAttribute("aria-label", opts.title);
        head.appendChild(title);
      }
      card.appendChild(head);

      var msg = document.createElement("p");
      msg.className = "modal-msg";
      msg.textContent = opts.message || "";
      card.appendChild(msg);

      var acts = document.createElement("div");
      acts.className = "modal-acts";

      var cancel = document.createElement("button");
      cancel.type = "button";
      cancel.className = "btn";
      cancel.textContent = opts.cancelText || "取消";

      var ok = document.createElement("button");
      ok.type = "button";
      ok.className = "btn " + (opts.danger ? "danger" : "primary");
      ok.textContent = opts.confirmText || "确定";

      acts.appendChild(cancel);
      acts.appendChild(ok);
      card.appendChild(acts);
      overlay.appendChild(card);

      modal = { overlay: overlay, ok: ok, cancel: cancel, resolve: resolve, prevFocus: document.activeElement };

      modalKeyHandler = function (e) {
        if (!modal) return;
        if (e.key === "Escape") {
          e.preventDefault();
          closeModal(false);
        } else if (e.key === "Tab") {
          e.preventDefault();
          if (e.shiftKey) {
            (document.activeElement === cancel ? ok : cancel).focus();
          } else {
            (document.activeElement === ok ? cancel : ok).focus();
          }
        }
      };
      modalOutsideHandler = function (e) {
        if (modal && e.target === modal.overlay) closeModal(false);
      };

      cancel.addEventListener("click", function () { closeModal(false); });
      ok.addEventListener("click", function () { closeModal(true); });

      document.body.appendChild(overlay);
      document.body.classList.add("modal-open");
      document.addEventListener("keydown", modalKeyHandler, true);
      document.addEventListener("mousedown", modalOutsideHandler, true);
      window.requestAnimationFrame(function () { overlay.classList.add("on"); });
      ok.focus();
    });
  }

  function urlGuard(opts) {
    var form = opts.form;
    var input = opts.input;
    var box = opts.error;
    var msgs = opts.messages || {};

    function bar() {
      return input.closest ? input.closest(".urlbar") : null;
    }

    function clear() {
      if (box) box.hidden = true;
      var b = bar();
      if (b) b.classList.remove("bad");
    }

    function show(text) {
      if (box) {
        var span = box.querySelector("span");
        if (span) span.textContent = text; else box.textContent = text;
        box.hidden = false;
      }
      var b = bar();
      if (b) b.classList.add("bad");
      input.focus();
    }

    input.addEventListener("input", clear);
    form.addEventListener("submit", function (e) {
      var raw = input.value.trim();
      if (!raw) {
        e.preventDefault();
        show(msgs.required || "Required");
        return;
      }
      var url = /^https?:\/\//i.test(raw) ? raw : "https://" + raw;
      if (!/^https?:\/\/[^\s\/?#]+(:\d{1,5})?([\/?#]|$)/i.test(url)) {
        e.preventDefault();
        show(msgs.invalid || "Invalid URL");
        return;
      }
      input.value = url;
      clear();
    });
  }

  function selectMenu(opts) {
    var root = opts.root;
    var btn = opts.button;
    var menu = opts.menu;
    var input = opts.input;
    var label = opts.label;
    var options = Array.prototype.slice.call(menu.querySelectorAll("[role=option]"));

    function onDocDown(e) {
      if (!root.contains(e.target)) close(false);
    }

    function close(focusBtn) {
      if (menu.hidden) return;
      menu.hidden = true;
      btn.setAttribute("aria-expanded", "false");
      root.classList.remove("open");
      document.removeEventListener("mousedown", onDocDown, true);
      if (focusBtn) btn.focus();
    }

    function open(focusOption) {
      if (!menu.hidden) return;
      menu.hidden = false;
      btn.setAttribute("aria-expanded", "true");
      root.classList.add("open");
      document.addEventListener("mousedown", onDocDown, true);
      if (focusOption) {
        var current = currentOption();
        if (current) current.focus();
      }
    }

    function currentOption() {
      return options.filter(function (o) { return o.getAttribute("aria-selected") === "true"; })[0] || options[0];
    }

    function choose(opt) {
      input.value = opt.dataset.value;
      label.textContent = opt.textContent;
      options.forEach(function (o) { o.setAttribute("aria-selected", o === opt ? "true" : "false"); });
      close(true);
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }

    btn.addEventListener("click", function () {
      if (menu.hidden) open(false); else close(true);
    });
    btn.addEventListener("keydown", function (e) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        if (menu.hidden) {
          open(true);
        } else {
          var current = currentOption();
          if (current) current.focus();
        }
      } else if (e.key === "Escape") {
        close(true);
      }
    });

    options.forEach(function (opt) {
      opt.tabIndex = -1;
      opt.addEventListener("click", function (e) {
        e.preventDefault();
        choose(opt);
      });
      opt.addEventListener("keydown", function (e) {
        var i = options.indexOf(opt);
        if (e.key === "ArrowDown") {
          e.preventDefault();
          (options[i + 1] || options[0]).focus();
        } else if (e.key === "ArrowUp") {
          e.preventDefault();
          (options[i - 1] || options[options.length - 1]).focus();
        } else if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          choose(opt);
        } else if (e.key === "Escape") {
          e.preventDefault();
          close(true);
        } else if (e.key === "Tab") {
          close(false);
        }
      });
    });
  }

  function formGuard(opts) {
    var form = opts.form;
    var rules = opts.rules || [];

    function clear(rule) {
      if (rule.error) rule.error.hidden = true;
      rule.input.classList.remove("bad");
    }

    function mark(rule) {
      if (rule.error) {
        var span = rule.error.querySelector("span");
        if (span) span.textContent = rule.message; else rule.error.textContent = rule.message;
        rule.error.hidden = false;
      }
      rule.input.classList.add("bad");
    }

    rules.forEach(function (rule) {
      rule.input.addEventListener("input", function () { clear(rule); });
    });

    form.addEventListener("submit", function (e) {
      var first = null;
      rules.forEach(function (rule) {
        if (!rule.input || (rule.when && !rule.when())) {
          if (rule.input) clear(rule);
          return;
        }
        var value = (rule.input.value || "").trim();
        var ok = rule.test ? rule.test(value) : value.length > 0;
        if (ok) {
          clear(rule);
        } else {
          mark(rule);
          if (!first) first = rule;
        }
      });
      if (first) {
        e.preventDefault();
        first.input.focus();
      }
    });
  }

  return {
    toast: toast,
    esc: esc,
    highlight: highlight,
    highlightInto: highlightInto,
    copyText: copyText,
    confirm: confirmDialog,
    urlGuard: urlGuard,
    selectMenu: selectMenu,
    formGuard: formGuard
  };
})();
