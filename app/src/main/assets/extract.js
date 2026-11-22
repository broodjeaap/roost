// The only code that knows Reddit's markup (new Reddit's mobile site, served from sh.reddit.com).
// Defines window.roost (idempotent) for Kotlin; evaluateJavascript JSON-encodes return values.
//   roost.extract()          -> page as a plain object
//   roost.threadReady()      -> true once a thread's comment tree has rendered
//   roost.scrollToEnd()      -> scrolls the feed so Reddit's own infinite scroll loads more posts
//   roost.loadMore(id)       -> expands "N more replies" under comment `id`, or more top-level
//                               comments for id 'top'; false if there is nothing to expand
//   roost.moreLoaded(id)     -> true once that expansion has been replaced by comments
//   roost.toggleNav()        -> opens/closes the nav drawer, which loads the subscription list
//   roost.startSearch(q)     -> asks Reddit's search typeahead for subreddits matching q
//   roost.searchResult(q)    -> null while pending, then [{name, detail}] or {error}
window.roost = (function () {
  var TOP = 'top';

  function text(el) {
    return el ? el.textContent.trim() : null;
  }

  function num(value) {
    var n = parseInt(value, 10);
    return isNaN(n) ? null : n;
  }

  function absolute(href) {
    return href ? new URL(href, location.href).href : null;
  }

  function html(id) {
    var node = document.getElementById(id);
    return node ? node.innerHTML.trim() : null;
  }

  function subscriptions() {
    var nav = document.querySelector('left-nav-communities-controller[initialstatejson]');
    if (!nav) return null;
    return JSON.parse(nav.getAttribute('initialstatejson')).map(function (s) {
      return s.prefixedName.replace(/^r\//, '');
    });
  }

  function media(post) {
    var player = post.querySelector('shreddit-player, shreddit-player-2');
    if (player && player.getAttribute('src')) {
      var src = absolute(player.getAttribute('src'));
      var poster = post.querySelector('img[slot=poster]');
      var size = videoSize(player);
      var gif = player.hasAttribute('gif') || post.getAttribute('post-type') === 'gif';
      return {
        type: 'video',
        hlsUrl: src.indexOf('.m3u8') !== -1 ? src : null,
        mp4Url: src.indexOf('.m3u8') === -1 ? src : null,
        poster: player.getAttribute('poster') || (poster && poster.getAttribute('src')) || null,
        width: size && size.width,
        height: size && size.height,
        gif: gif
      };
    }
    var pages = post.querySelectorAll('gallery-carousel li[slot^=page-]');
    if (pages.length) {
      return {
        type: 'gallery',
        images: Array.prototype.map.call(pages, function (page) {
          var img = page.querySelector('img');
          var full = page.querySelector('img[src*="i.redd.it"]');
          return {
            url: absolute(img.getAttribute('src') || img.getAttribute('data-lazy-src') || (full && full.getAttribute('src'))),
            width: num(img.getAttribute('width')),
            height: num(img.getAttribute('height'))
          };
        })
      };
    }
    var image = post.querySelector('img#post-image, img[data-post-media-primary]');
    if (image) {
      return {
        type: 'image',
        url: absolute(image.getAttribute('src')),
        width: num(image.getAttribute('width')),
        height: num(image.getAttribute('height'))
      };
    }
    return null;
  }

  // The largest packaged MP4 rendition carries the video's real dimensions.
  function videoSize(player) {
    try {
      var packaged = JSON.parse(player.getAttribute('packaged-media-json') || 'null');
      var renditions = packaged && packaged.playbackMp4s && packaged.playbackMp4s.permutations;
      if (!renditions || !renditions.length) return null;
      return renditions[renditions.length - 1].source.dimensions;
    } catch (e) {
      return null;
    }
  }

  function post(p) {
    var type = p.getAttribute('post-type');
    var thumb = p.querySelector('[slot=thumbnail] img');
    return {
      id: p.id,
      title: p.getAttribute('post-title'),
      author: p.getAttribute('author') || '[deleted]',
      subreddit: p.getAttribute('subreddit-name'),
      score: num(p.getAttribute('score')),
      commentCount: num(p.getAttribute('comment-count')) || 0,
      permalink: p.getAttribute('permalink'),
      url: absolute(p.getAttribute('content-href')),
      domain: p.getAttribute('domain'),
      created: Date.parse(p.getAttribute('created-timestamp')) || null,
      nsfw: p.hasAttribute('nsfw'),
      spoiler: p.hasAttribute('spoiler'),
      isSelf: (p.getAttribute('domain') || '').indexOf('self.') === 0,
      flair: text(p.querySelector('shreddit-post-flair')) || null,
      thumbnail: thumb ? absolute(thumb.getAttribute('src')) : null,
      bodyHtml: html(p.id + '-post-rtjson-content'),
      media: type === 'text' ? null : media(p)
    };
  }

  // "N more replies" loaders sit inside the comment whose replies they extend.
  function owner(el) {
    var comment = el.closest('shreddit-comment');
    return comment ? comment.getAttribute('thingid') : null;
  }

  function comments(postAuthor) {
    var byId = {};
    var roots = [];
    Array.prototype.forEach.call(document.querySelectorAll('shreddit-comment'), function (c) {
      var id = c.getAttribute('thingid');
      var parent = c.parentElement && c.parentElement.closest('shreddit-comment');
      var node = {
        kind: 'comment',
        id: id,
        author: c.getAttribute('author') || '[deleted]',
        score: num(c.getAttribute('score')),
        created: Date.parse(c.getAttribute('created')) || null,
        permalink: c.getAttribute('permalink'),
        bodyHtml: html(id + '-comment-rtjson-content') || '',
        submitter: !!postAuthor && c.getAttribute('author') === postAuthor,
        moderator: c.hasAttribute('is-mod-distinguished'),
        collapsed: c.hasAttribute('collapsed'),
        children: []
      };
      byId[id] = node;
      var parentNode = parent && byId[parent.getAttribute('thingid')];
      (parentNode ? parentNode.children : roots).push(node);
    });
    var expandable = {};
    Array.prototype.forEach.call(document.querySelectorAll('faceplate-partial[slot=children]'), function (partial) {
      var id = owner(partial);
      if (!id || !byId[id] || expandable[id]) return;
      expandable[id] = true;
      byId[id].children.push({ kind: 'more', id: id, count: num((/(\d+)/.exec(text(partial)) || [])[1]) });
    });
    // Without an inline loader, deep threads only offer a link to their own page.
    Array.prototype.forEach.call(document.querySelectorAll('a[slot=more-comments-permalink]'), function (link) {
      var id = owner(link);
      if (!id || !byId[id] || expandable[id]) return;
      byId[id].children.push({ kind: 'continue', url: absolute(link.getAttribute('href')) });
    });
    if (topLevelLoader()) roots.push({ kind: 'more', id: TOP, count: null });
    return roots;
  }

  function topLevelLoader() {
    return document.querySelector('#top-level-more-comments-partial, faceplate-partial.top-level');
  }

  function repliesLoader(id) {
    var partials = document.querySelectorAll('faceplate-partial[slot=children]');
    for (var i = 0; i < partials.length; i++) {
      if (owner(partials[i]) === id) return partials[i];
    }
    return null;
  }

  // Reddit's block and rate-limit pages are not app pages; recognise them by their wording.
  function blockReason() {
    var page = (document.title + ' ' + (document.body ? document.body.innerText.slice(0, 2000) : '')).toLowerCase();
    var markers = ['blocked by network security', 'whoa there, pardner', 'too many requests', 'prove you\'re human', 'captcha'];
    for (var i = 0; i < markers.length; i++) {
      if (page.indexOf(markers[i]) !== -1) return markers[i];
    }
    return null;
  }

  function extract() {
    try {
      var app = document.querySelector('shreddit-app');
      var result = {
        ok: true,
        url: location.href,
        title: document.title,
        route: app ? app.getAttribute('routename') : null,
        loggedIn: !!app && app.hasAttribute('user-logged-in'),
        subscriptions: subscriptions()
      };
      var tree = document.querySelector('shreddit-comment-tree');
      var posts = document.querySelectorAll('shreddit-post');
      var reason = posts.length ? null : blockReason();
      if (tree && posts.length) {
        var main = post(posts[0]);
        result.page = 'thread';
        result.post = main;
        result.comments = comments(main.author);
      } else if (reason) {
        result.page = 'blocked';
        result.blockReason = reason;
      } else if (app) {
        result.page = 'listing';
        result.posts = Array.prototype.map.call(posts, post);
      } else {
        result.page = 'unknown';
      }
      return result;
    } catch (e) {
      return { ok: false, url: location.href, error: String((e && e.stack) || e) };
    }
  }

  function threadReady() {
    var tree = document.querySelector('shreddit-comment-tree');
    if (!tree) return false;
    return tree.getAttribute('total-comments') === '0' || !!document.querySelector('shreddit-comment');
  }

  function scrollToEnd() {
    window.scrollTo(0, document.documentElement.scrollHeight);
    return true;
  }

  function loadMore(id) {
    var loader = id === TOP ? topLevelLoader() : repliesLoader(id);
    if (!loader) return false;
    if (id === TOP) {
      // Lazy loader: fires when scrolled into view, as when reading to the end of the comments.
      loader.setAttribute('data-roost-more', id);
      loader.scrollIntoView({ block: 'center' });
      return true;
    }
    var button = loader.querySelector('button');
    if (!button) return false;
    loader.setAttribute('data-roost-more', id);
    button.click();
    return true;
  }

  function moreLoaded(id) {
    return !document.querySelector('[data-roost-more="' + id + '"]');
  }

  function toggleNav() {
    var button = document.getElementById('navbar-menu-button');
    if (!button) return false;
    button.click();
    return true;
  }

  // window.roost is redefined on every call, so in-flight searches live on window itself.
  function startSearch(query) {
    var store = window.__roostSearches || (window.__roostSearches = {});
    var session = window.__roostSearchSession || (window.__roostSearchSession = crypto.randomUUID());
    delete store[query];
    // The same request Reddit's own search box makes while typing.
    fetch('/svc/shreddit/search-typeahead?query=' + encodeURIComponent(query) + '&cId=' + session +
      '&recentSearchesCount=5&mode=search', { credentials: 'include' })
      .then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.text();
      })
      .then(function (html) { store[query] = communities(html); })
      .catch(function (e) { store[query] = { error: String(e) }; });
    return true;
  }

  // Typeahead entries are links like <a href="/r/Kotlin/">r/Kotlin 10K weekly visitors</a>.
  function communities(html) {
    var doc = new DOMParser().parseFromString(html, 'text/html');
    var seen = {};
    var out = [];
    Array.prototype.forEach.call(doc.querySelectorAll('a[href^="/r/"]'), function (link) {
      var name = link.getAttribute('href').split('/')[2];
      if (!name || seen[name.toLowerCase()]) return;
      seen[name.toLowerCase()] = true;
      var detail = text(link).replace(/\s+/g, ' ').replace(new RegExp('^r/' + name, 'i'), '').trim();
      out.push({ name: name, detail: detail || null });
    });
    return out;
  }

  function searchResult(query) {
    var store = window.__roostSearches;
    return store && Object.prototype.hasOwnProperty.call(store, query) ? store[query] : null;
  }

  return {
    extract: extract,
    threadReady: threadReady,
    scrollToEnd: scrollToEnd,
    loadMore: loadMore,
    moreLoaded: moreLoaded,
    toggleNav: toggleNav,
    startSearch: startSearch,
    searchResult: searchResult
  };
})();
