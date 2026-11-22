// The only code that knows old.reddit's markup. Defines window.roost (idempotent) for Kotlin:
//   roost.extract()        -> page as a plain object (evaluateJavascript JSON-encodes it)
//   roost.loadMore(id)     -> clicks a "load more comments" link; false if it isn't on the page
//   roost.moreLoaded(id)   -> true once that link has been replaced by the fetched comments
window.roost = (function () {
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

  // Listings stash the expanded media in data-cachedhtml; thread pages render it inline.
  function expando(thing) {
    var ex = thing.querySelector('.expando');
    if (!ex) return null;
    if (!ex.dataset.cachedhtml) return ex;
    var template = document.createElement('template');
    template.innerHTML = ex.dataset.cachedhtml;
    return template.content;
  }

  function media(thing, ex) {
    if (!ex) return null;
    var video = ex.querySelector('[data-hls-url]');
    if (video) {
      return {
        type: 'video',
        hlsUrl: video.dataset.hlsUrl,
        width: parseFloat(video.dataset.videoWidth) || null,
        height: parseFloat(video.dataset.videoHeight) || null,
        gif: video.dataset.isGif === 'true'
      };
    }
    var tiles = ex.querySelectorAll('.gallery-tile[data-media-id]');
    if (tiles.length) {
      return {
        type: 'gallery',
        images: Array.prototype.map.call(tiles, function (tile) {
          var img = tile.querySelector('img');
          var src = img ? img.getAttribute('src') : '';
          var ext = (/\.(\w+)\?/.exec(src) || [])[1] || 'jpg';
          return {
            url: 'https://i.redd.it/' + tile.dataset.mediaId + '.' + ext,
            width: num(img && img.getAttribute('width')),
            height: num(img && img.getAttribute('height'))
          };
        })
      };
    }
    var img = ex.querySelector('img.preview');
    if (img) {
      return {
        type: 'image',
        url: absolute(img.getAttribute('src')),
        width: num(img.getAttribute('width')),
        height: num(img.getAttribute('height'))
      };
    }
    var source = ex.querySelector('video source[src], video[src]');
    if (source) {
      return { type: 'video', mp4Url: absolute(source.getAttribute('src')), width: null, height: null, gif: true };
    }
    return null;
  }

  function post(thing) {
    var ex = expando(thing);
    var body = ex && ex.querySelector('.usertext-body .md');
    var thumb = thing.querySelector(':scope > a.thumbnail img');
    var data = thing.dataset;
    return {
      id: data.fullname,
      title: text(thing.querySelector('a.title')),
      author: data.author || '[deleted]',
      subreddit: data.subreddit,
      score: num(data.score),
      commentCount: num(data.commentsCount) || 0,
      permalink: data.permalink,
      url: absolute(data.url),
      domain: data.domain,
      created: num(data.timestamp),
      nsfw: data.nsfw === 'true',
      spoiler: data.spoiler === 'true',
      stickied: thing.classList.contains('stickied'),
      isSelf: thing.classList.contains('self'),
      flair: text(thing.querySelector('.linkflairlabel')),
      thumbnail: thumb ? absolute(thumb.getAttribute('src')) : null,
      bodyHtml: body ? body.innerHTML : null,
      media: media(thing, ex)
    };
  }

  function comments(sitetable) {
    var out = [];
    Array.prototype.forEach.call(sitetable.children, function (el) {
      if (el.classList.contains('morechildren')) {
        var link = el.querySelector('.morecomments a');
        out.push({ kind: 'more', id: el.dataset.fullname, count: num((/\((\d+)/.exec(text(link)) || [])[1]) });
      } else if (el.classList.contains('comment')) {
        out.push(comment(el));
      } else if (el.classList.contains('deepthread')) {
        var deep = el.querySelector('a');
        if (deep) out.push({ kind: 'continue', url: absolute(deep.getAttribute('href')) });
      }
    });
    return out;
  }

  function comment(thing) {
    var entry = thing.querySelector(':scope > .entry');
    var score = entry.querySelector('.tagline .score.unvoted');
    var time = entry.querySelector('.tagline time');
    var body = entry.querySelector('.usertext-body .md');
    var author = entry.querySelector('.tagline .author');
    var child = thing.querySelector(':scope > .child');
    var replies = child && child.querySelector(':scope > .sitetable');
    var deep = child && child.querySelector(':scope > .deepthread a');
    var children = replies ? comments(replies) : [];
    if (deep) children.push({ kind: 'continue', url: absolute(deep.getAttribute('href')) });
    return {
      kind: 'comment',
      id: thing.dataset.fullname,
      author: thing.dataset.author || '[deleted]',
      score: score ? num(score.getAttribute('title')) : null,
      created: time ? Date.parse(time.getAttribute('datetime')) : null,
      permalink: thing.dataset.permalink,
      bodyHtml: body ? body.innerHTML : '',
      submitter: !!(author && author.classList.contains('submitter')),
      moderator: !!(author && author.classList.contains('moderator')),
      flair: text(entry.querySelector('.tagline .flair')),
      collapsed: thing.classList.contains('collapsed'),
      children: children
    };
  }

  // Reddit's block/rate-limit pages are not old.reddit pages; recognise them by their wording.
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
      var result = {
        ok: true,
        url: location.href,
        title: document.title,
        loggedIn: !!document.body && document.body.classList.contains('loggedin'),
        subscriptions: Array.prototype.map.call(
          document.querySelectorAll('#sr-header-area .drop-choices a.choice:not(.bottom-option)'), text)
      };
      var commentArea = document.querySelector('.commentarea > .sitetable');
      var siteTable = document.getElementById('siteTable');
      if (commentArea && siteTable) {
        var thing = siteTable.querySelector('.thing.link');
        result.page = 'thread';
        result.post = thing ? post(thing) : null;
        result.comments = comments(commentArea);
      } else if (siteTable) {
        var next = document.querySelector('.nav-buttons .next-button a');
        result.page = 'listing';
        result.posts = Array.prototype.map.call(
          document.querySelectorAll('#siteTable > .thing.link:not(.promoted)'), post);
        result.nextUrl = next ? absolute(next.getAttribute('href')) : null;
      } else {
        var reason = blockReason();
        result.page = reason ? 'blocked' : 'unknown';
        result.blockReason = reason;
      }
      return result;
    } catch (e) {
      return { ok: false, url: location.href, error: String((e && e.stack) || e) };
    }
  }

  function loadMore(id) {
    var link = document.getElementById('more_' + id);
    if (!link) return false;
    link.click();
    return true;
  }

  function moreLoaded(id) {
    return !document.querySelector('#thing_' + id + '.morechildren');
  }

  return { extract: extract, loadMore: loadMore, moreLoaded: moreLoaded };
})();
