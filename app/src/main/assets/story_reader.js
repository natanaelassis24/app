(function () {
  "use strict";

  var KEY = "__folioStory";
  if (window[KEY]) return JSON.stringify(window[KEY].next());

  var WINDOW_SIZE = 16;
  var RESUME_KEY_LENGTH = 720;
  var MAX_ROOTS_PER_TIER = 8;
  var MAX_ELEMENTS_PER_ROOT = 400;
  var MAX_STORED_BLOCKS = 192;
  var MAX_BLOCK_CHARACTERS = 2200;
  var state = {
    dirty: true,
    sequence: 0,
    items: [],
    nodes: new Map(),
    delivered: new Set()
  };

  var ignoredTags = {
    NAV: true, HEADER: true, FOOTER: true, ASIDE: true, FORM: true,
    BUTTON: true, INPUT: true, SELECT: true, TEXTAREA: true, SCRIPT: true,
    STYLE: true, NOSCRIPT: true, SVG: true, CANVAS: true, VIDEO: true,
    AUDIO: true, DIALOG: true
  };
  var unwanted = /\b(?:ad|ads|advert|advertisement|adsbygoogle|banner|cookie|consent|nav|menu|toolbar|footer|header|sidebar|comment|coment[a\u00e1]rio|reply|related|recommend|newsletter|subscribe|social|share|breadcrumb|pagination|search-results|feed|disqus|review|author|byline|toc|table-of-contents|chapter-nav|next|previous|login|signup|vote|reaction|tag|metadata|caption|widget|modal|popup)\b/i;
  var boilerplate = /^(?:publicidade|an[u\u00fa]ncio|advertisement|sponsored|compartilhe|share|coment[a\u00e1]rios?|comments?|responder|reply|assine|subscribe|leia tamb[e\u00e9]m|read more|pr[o\u00f3]ximo|anterior|next|previous)\b/i;
  var semanticSelector = "p, blockquote, [role='paragraph']";
  var rootTiers = [
    "[itemprop='articleBody'], .chapter-content, .story-content, .novel-content, .reading-content, .article-content, .article-body, .entry-content, .post-content, .content-body, .text-body, #chapter-content, #story-content, #article-content, [id*='chapter'], [class*='chapter'], [id*='story'], [class*='story'], [id*='novel'], [class*='novel'], [id*='reading'], [class*='reading']",
    "article, [role='article']",
    "main, [role='main'], #content, .content"
  ];

  function clean(value) {
    return String(value || "").replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
  }

  function signature(element) {
    if (!element) return "";
    var values = [
      element.id || "",
      typeof element.className === "string" ? element.className : "",
      element.getAttribute("role") || "",
      element.getAttribute("itemprop") || "",
      element.getAttribute("aria-label") || "",
      element.getAttribute("data-testid") || "",
      element.getAttribute("data-test") || "",
      element.getAttribute("data-component") || "",
      element.getAttribute("data-section") || "",
      element.getAttribute("data-type") || "",
      element.getAttribute("data-name") || ""
    ];
    return values.join(" ");
  }

  function visible(element) {
    if (!element || element.nodeType !== 1) return false;
    var style = window.getComputedStyle(element);
    return style.display !== "none" && style.visibility !== "hidden" &&
      style.visibility !== "collapse" && style.opacity !== "0" &&
      element.getClientRects().length > 0;
  }

  function excluded(element) {
    for (var current = element; current && current !== document.documentElement;
         current = current.parentElement) {
      if (ignoredTags[current.tagName]) return true;
      if (current.getAttribute("aria-hidden") === "true") return true;
      if (current !== document.body && unwanted.test(signature(current))) return true;
    }
    return false;
  }

  function textOf(element) {
    return clean(element.innerText || element.textContent);
  }

  function linkTextLength(element) {
    var total = 0;
    var links = element.querySelectorAll("a");
    for (var i = 0; i < links.length; i++) total += textOf(links[i]).length;
    return total;
  }

  function blockInfo(element) {
    if (!visible(element) || excluded(element)) return null;
    var text = textOf(element);
    if (text.length < 8 || boilerplate.test(text)) return null;
    var linkLength = linkTextLength(element);
    if (linkLength > Math.max(24, text.length * 0.28)) return null;
    return { element: element, text: text, linkLength: linkLength };
  }

  function semanticBlocks(root) {
    var elements = [];
    if (root.matches && root.matches(semanticSelector)) elements.push(root);
    var found = root.querySelectorAll(semanticSelector);
    for (var i = 0; i < found.length && elements.length < MAX_ELEMENTS_PER_ROOT; i++) {
      elements.push(found[i]);
    }

    var rough = [];
    var seen = new Set();
    for (var j = 0; j < elements.length; j++) {
      if (seen.has(elements[j])) continue;
      seen.add(elements[j]);
      var info = blockInfo(elements[j]);
      if (info) rough.push(info);
    }

    var result = [];
    for (var k = 0; k < rough.length; k++) {
      var current = rough[k];
      var previous = k > 0 ? rough[k - 1] : null;
      var next = k + 1 < rough.length ? rough[k + 1] : null;
      if (current.text.length >= 32 ||
          (previous && previous.text.length >= 32) ||
          (next && next.text.length >= 32)) {
        result.push(current);
      }
    }
    return result;
  }

  function leafBlocks(root) {
    var result = [];
    var candidates = root.querySelectorAll("div, section");
    for (var i = 0; i < candidates.length && i < MAX_ELEMENTS_PER_ROOT; i++) {
      var element = candidates[i];
      if (element.querySelector(semanticSelector)) continue;

      var hasTextualChild = false;
      for (var child = element.firstElementChild; child; child = child.nextElementSibling) {
        if ((child.tagName === "DIV" || child.tagName === "SECTION") &&
            visible(child) && textOf(child).length >= 8) {
          hasTextualChild = true;
          break;
        }
      }
      if (hasTextualChild) continue;
      var info = blockInfo(element);
      if (info && info.text.length >= 32) result.push(info);
    }
    return result;
  }

  function blocksFor(root) {
    var blocks = semanticBlocks(root).concat(leafBlocks(root));
    blocks.sort(function (left, right) {
      if (left.element === right.element) return 0;
      return left.element.compareDocumentPosition(right.element) & Node.DOCUMENT_POSITION_FOLLOWING
        ? -1 : 1;
    });

    var result = [];
    var seenElements = new Set();
    for (var i = 0; i < blocks.length; i++) {
      if (seenElements.has(blocks[i].element)) continue;
      seenElements.add(blocks[i].element);
      result.push(blocks[i]);
    }
    return result;
  }

  function score(root, tier) {
    if (!root || excluded(root)) return null;
    if (root.tagName !== "ARTICLE" && root !== document.body &&
        root.querySelectorAll("article").length > 1) return null;
    var blocks = blocksFor(root);
    var characters = 0;
    var links = 0;
    for (var i = 0; i < blocks.length; i++) {
      characters += blocks[i].text.length;
      links += blocks[i].linkLength;
    }
    if (!blocks.length || characters < 32) return null;
    var value = characters + blocks.length * 90 - Math.min(1200, links * 2);
    if (root.tagName === "ARTICLE" || root.getAttribute("itemprop") === "articleBody") {
      value += 900;
    }
    if (tier === 0) value += 500;
    if (root === document.body) value -= 2000;
    return { root: root, blocks: blocks, value: value };
  }

  function rootsFor(selector) {
    var roots = [];
    var seen = new Set();
    var found = document.querySelectorAll(selector);
    for (var i = 0; i < found.length && roots.length < MAX_ROOTS_PER_TIER; i++) {
      if (seen.has(found[i]) || excluded(found[i])) continue;
      seen.add(found[i]);
      roots.push(found[i]);
    }
    return roots;
  }

  function bestFor(roots, tier) {
    var best = null;
    for (var i = 0; i < roots.length; i++) {
      var candidate = score(roots[i], tier);
      if (candidate && (!best || candidate.value > best.value)) best = candidate;
    }
    return best;
  }

  function bestRoot() {
    for (var tier = 0; tier < rootTiers.length; tier++) {
      var candidate = bestFor(rootsFor(rootTiers[tier]), tier);
      if (candidate) return candidate;
    }
    return bestFor([document.body], 3);
  }

  function makeId(element) {
    var id = element.getAttribute("data-folio-story-id");
    if (!id) {
      id = "folio-story-" + (++state.sequence);
      element.setAttribute("data-folio-story-id", id);
    }
    return id;
  }

  function scan() {
    var best = bestRoot();
    state.items = [];
    state.nodes.clear();
    if (best) {
      var occurrences = new Map();
      for (var i = 0; i < best.blocks.length && state.items.length < MAX_STORED_BLOCKS; i++) {
        var block = best.blocks[i];
        var id = makeId(block.element);
        var text = block.text.length > MAX_BLOCK_CHARACTERS
          ? block.text.substring(0, MAX_BLOCK_CHARACTERS).trim() : block.text;
        var blockKey = resumeKey(text);
        var occurrence = occurrences.has(blockKey) ? occurrences.get(blockKey) : 0;
        occurrences.set(blockKey, occurrence + 1);
        state.nodes.set(id, block.element);
        state.items.push({ id: id, text: text, occurrence: occurrence });
      }
    }
    state.dirty = false;
  }

  function get(force) {
    if (force || state.dirty) scan();
    return state.items.map(function (item) {
      return { id: item.id, text: item.text, occurrence: item.occurrence };
    });
  }

  function takeNext(items) {
    var result = [];
    for (var i = 0; i < items.length && result.length < WINDOW_SIZE; i++) {
      if (state.delivered.has(items[i].id)) continue;
      state.delivered.add(items[i].id);
      result.push(items[i]);
    }
    return result;
  }

  function next() {
    return takeNext(get(true));
  }

  function resumeKey(text) {
    var normalized = clean(text).toLowerCase();
    var prefix = normalized.length > RESUME_KEY_LENGTH
      ? normalized.substring(0, RESUME_KEY_LENGTH) : normalized;
    return normalized.length + ":" + prefix;
  }

  function keyPrefix(key) {
    var separator = String(key || "").indexOf(":");
    return separator < 0 ? String(key || "") : String(key).substring(separator + 1);
  }

  function resume(anchor, occurrence, scrollY) {
    state.delivered.clear();
    var items = get(true);
    var target = String(anchor || "");
    var targetPrefix = keyPrefix(target);
    var wantedOccurrence = Math.max(0, Number(occurrence) || 0);
    var match = -1;
    var matches = 0;

    for (var i = 0; i < items.length; i++) {
      if (resumeKey(items[i].text) !== target) continue;
      if (matches++ === wantedOccurrence) {
        match = i;
        break;
      }
    }
    if (match < 0 && targetPrefix) {
      matches = 0;
      for (var j = 0; j < items.length; j++) {
        var candidatePrefix = keyPrefix(resumeKey(items[j].text));
        if (candidatePrefix.indexOf(targetPrefix) !== 0 &&
            targetPrefix.indexOf(candidatePrefix) !== 0) continue;
        if (matches++ === wantedOccurrence) {
          match = j;
          break;
        }
      }
    }
    if (match >= 0) {
      for (var previous = 0; previous < match; previous++) {
        state.delivered.add(items[previous].id);
      }
    }
    if (Number(scrollY) > 0) {
      try {
        window.scrollTo({ top: Number(scrollY), behavior: "auto" });
      } catch (error) {
        window.scrollTo(0, Number(scrollY));
      }
    }
    return takeNext(items);
  }

  function installStyle() {
    if (document.getElementById("folio-story-highlight-style")) return;
    var style = document.createElement("style");
    style.id = "folio-story-highlight-style";
    style.textContent = ".folio-story-highlight{" +
      "background:rgba(128,128,128,.24)!important;" +
      "outline:2px solid rgba(255,255,255,.92)!important;" +
      "box-shadow:0 0 0 1px rgba(0,0,0,.88)!important;" +
      "outline-offset:4px!important;border-radius:5px!important;" +
      "transition:background .2s ease,outline-color .2s ease,box-shadow .2s ease!important}";
    (document.head || document.documentElement).appendChild(style);
  }

  function focus(id) {
    get(false);
    var element = state.nodes.get(id);
    if (!element || !document.documentElement.contains(element)) {
      scan();
      element = state.nodes.get(id);
    }
    if (!element) return false;

    installStyle();
    var active = document.querySelectorAll(".folio-story-highlight");
    for (var i = 0; i < active.length; i++) active[i].classList.remove("folio-story-highlight");
    element.classList.add("folio-story-highlight");
    try {
      element.scrollIntoView({ behavior: "smooth", block: "center", inline: "nearest" });
    } catch (error) {
      element.scrollIntoView(true);
    }
    return true;
  }

  function clear() {
    var active = document.querySelectorAll(".folio-story-highlight");
    for (var i = 0; i < active.length; i++) active[i].classList.remove("folio-story-highlight");
    var style = document.getElementById("folio-story-highlight-style");
    if (style && style.parentNode) style.parentNode.removeChild(style);
    state.items = [];
    state.nodes.clear();
    state.delivered.clear();
    state.dirty = true;
    if (observer) observer.disconnect();
    delete window[KEY];
  }

  function scrollForMore() {
    var amount = Math.max(480, window.innerHeight * 1.45);
    try {
      window.scrollBy({ top: amount, behavior: "smooth" });
    } catch (error) {
      window.scrollBy(0, amount);
    }
  }

  window[KEY] = {
    get: get,
    next: next,
    refresh: next,
    resume: resume,
    focus: focus,
    clear: clear,
    scrollForMore: scrollForMore
  };

  var observer = new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var target = records[i].target;
      var parent = target.nodeType === 1 ? target : target.parentElement;
      if (!parent || parent.id !== "folio-story-highlight-style") {
        state.dirty = true;
        return;
      }
    }
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  return JSON.stringify(next());
})();
