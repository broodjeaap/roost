# Boost-style Reddit client — design doc

Nov 22, 2026 · @David

## Background

Patched Boost for Reddit now returns 403 on the front page and on sorted views like top of the week; plain subreddit pages still load. Patched Boost depends on Reddit's public Data API, and Reddit is closing that API in stages, so these failures will spread rather than get fixed.

| Date | Change |
| --- | --- |
| March 2027 | All public API access ends |
| January 12, 2027 | Access starts closing for unregistered apps |
| October 31, 2026 | New public API requests stop |
| Late May 2026 | Logged-out `.json` URLs return 403 |
| November 2025 | Self-service API keys closed |
| June 2023 | Paid API tiers; most third-party apps shut down |

The website itself keeps working for logged-in users, which is what this client builds on. Sources: [bigideasdb: Reddit API shutdown timeline](https://bigideasdb.com/reddit-api-shutdown-2027), [dev.to: Reddit blocks unauthenticated JSON](https://dev.to/tonywangca/why-reddit-blocked-unauthenticated-json-in-2026-and-how-to-still-get-reddit-data-58b9).

## Goals and non-goals

The goal is a personal, read-only Android client that looks and feels like Boost, logged in to my own account.

Goals:

- Front page with my subscriptions, subreddits, and every sort and time filter (hot, new, top top hour/day/week/etc)
- Comment threads with collapsing, Boost-style
- Images, galleries and v.redd.it video inline
- Traffic that looks like an ordinary mobile browser user

Non-goals:

- Voting, commenting, posting or messaging: I'll use a browser for those
- Background prefetching or offline sync
- Other users or a Play Store release

## Approach

Chosen: hidden Android WebViews load real pages of new Reddit's mobile site from `sh.reddit.com`, injected JavaScript extracts posts and comments from the DOM, and a native Compose UI renders them. To Reddit this looks like someone using Chrome on their phone, because the WebView is Chromium.

Why `sh.reddit.com`: it is new Reddit's own host and always serves the new site. `www.reddit.com` serves the old layout to any session carrying the `redesign_optout` cookie, which visiting old.reddit sets.

Limits of the mobile site, accepted: the front page is Best only (`/new/`, `/top/?t=week` and `/rising/` redirect to `/?screen_view_count=…`), and `r/all` and multireddits (`r/a+b`) land on the front page or return nothing. Sorts and time filters work for subreddits and r/popular.

old.reddit was tried and dropped. It had every sort, frozen markup, full comment trees and link-based pagination. But since 2026 it requires a login, and Reddit is narrowing it to accounts that used it in the last six months ([Ars Technica](https://arstechnica.com/gadgets/2026/09/reddit-will-block-old-reddit-com-from-people-who-havent-used-it-in-6-months/)). In practice a freshly logged-in phone session was refused (`302` to `old.reddit.com/login/?reason=lor2`) while an older emulator session on the same account, Wi-Fi and IP was let in. A new install can't count on access.

| Option | How it works | Why not / why |
| --- | --- | --- |
| Hidden WebView + DOM extraction of new Reddit mobile (chosen) | Real page loads; JS pulls data out | Real TLS fingerprint, headers, JS, cookies and telemetry; slower and breaks on markup changes; front page Best only |
| Hidden WebView + DOM extraction of old.reddit | Same, reading old.reddit's server-rendered markup | Every sort and full comment trees, but Reddit gates it to some sessions and is shrinking it |
| `.json` URLs with session cookie | HTTP client fetches `reddit.com/…/top.json?t=week` | Cleanest data, but browsers never request these URLs, so a session doing only this is easy to flag |
| Raw HTML fetch, spoofed user agent | OkHttp fetches pages, parses HTML | User agent is a weak signal; TLS/HTTP2 fingerprint, header order, missing client hints and no subresource loads still give it away |
| Patched Boost / public API | Existing app with own API key | Being shut down; already returning 403 |

## Architecture

&#91;embedded content: client architecture · 6 components\]

Every request to Reddit goes through two hidden WebViews, like two browser tabs: the feed tab keeps the current listing loaded, because more posts come from scrolling that page; the thread tab opens threads. Both stay attached underneath the Compose UI at full size, because Reddit's pages misbehave in a detached or `wrap_content` WebView: viewport units resolve to 0 and bottom sheets collapse. Login, block pages and debugging reveal one of them (login and the feed use the feed tab, an open thread its own tab). Requests are serialized, so only one page loads at a time.

## Login and session

Log in once in the visible WebView at `www.reddit.com/login/`; the `.reddit.com` session cookies then also work on `sh.reddit.com`, and the front page is simply `sh.reddit.com/` with my subscriptions.

- **Shared cookies**: Android's `CookieManager` is app-wide. Call `CookieManager.getInstance().flush()` after login so the session survives the app being killed.
- **Token refresh**: Reddit's short-lived token is refreshed by the page's own JS, so loading real pages keeps it fresh with no extra code.
- **Logout detection**: the extractor reports whether `<shreddit-app>` has the `user-logged-in` attribute. If not, the login overlay opens; it closes itself once Reddit navigates away from the login pages. If the session still isn't accepted straight after a login, the app shows an error instead of reopening login, so it can't loop.
- **Login method**: username and password, or Reddit's email code. Not "Continue with Google": Google blocks sign-in inside embedded WebViews (`disallowed_useragent`). 2FA works.
- **User agent**: keep the WebView's default, and never change it between login and browsing. Media requests (Coil, ExoPlayer) send the same user agent.

## Extraction layer

One small injected JS file turns the loaded page into JSON and hands it to Kotlin; it is the only part that knows Reddit's markup.

- **Injection**: `evaluateJavascript` runs `extract.js`, which defines `window.roost`: `extract()` (listing, thread or block page), `threadReady()`, `scrollToEnd()`, `loadMore(id)`, `moreLoaded(id)` and `toggleNav()`. `evaluateJavascript` JSON-encodes the results.
- **Posts**: `<shreddit-post>` attributes give id, title, author, subreddit, score, comment count, permalink, `content-href`, domain, created timestamp, post type and `spoiler`; flair is `shreddit-post-flair`; self text (a preview on listings, complete on threads) is `#<id>-post-rtjson-content`.
- **Comments**: `<shreddit-comment>` attributes give thingid, author, score, created, permalink, `parentid`, `collapsed` and `is-mod-distinguished`, and replies are nested inside their parent in the DOM. The body is `#<thingid>-comment-rtjson-content`, and OP is "author equals post author". Threads render their comments shortly after the page loads, so the source waits for `threadReady()`.
- **Rich text**: post and comment bodies are split into blocks in reading order, HTML text between embedded media, because the text renderer can't show images. Comment images and GIFs (and inline images in posts) are `<figure class="rte-media">`. An image links to a larger preview than it displays. A GIF is a `<shreddit-player gif>` playing MP4, with the GIF itself linked as a still poster. Sizes come only from a CSS `aspect-ratio`. The app shows them at most 320 dp wide; images tap to open full size, GIFs tap to play.
- **Pagination**: the feed pages through Reddit's own infinite scroll, only when the end of the app's list comes into view. The page recycles its DOM (old posts are removed as new ones load), so new posts are recognised by id and the app keeps the accumulated list. Thread "N more replies" is a `faceplate-partial[slot=children]` inside the parent comment; the source clicks its button and re-extracts. More top-level comments come from a lazy `#top-level-more-comments-partial` that loads when scrolled into view. Deep chains with only an `a[slot=more-comments-permalink]` open as their own thread.
- **Subscriptions**: `left-nav-communities-controller[initialstatejson]` only appears once the nav drawer opens, so the source opens and closes it once per session.
- **Subreddit search**: typing in the drawer filters subscriptions, then asks Reddit's search typeahead for others. The feed tab's page runs `fetch('/svc/shreddit/search-typeahead?query=…')`, the same request Reddit's own search box makes while typing, so it carries the page's cookies and fingerprint. It fires from two characters and after a 400 ms pause (Reddit's box fires on every keystroke). The HTML response lists communities as `a[href^="/r/"]` with a one-line blurb. It returns about seven matches per query; there is no full directory.
- **Media**: images come from `img#post-image` / `img[data-post-media-primary]` with dimensions; galleries from `gallery-carousel li[slot^=page-]` previews; video from `shreddit-player[src]` (HLS for v.redd.it, MP4 for GIFs), with dimensions from `packaged-media-json` and a poster; link thumbnails from `[slot=thumbnail] img`. Images load with Coil and video plays in ExoPlayer.
- **Fragility**: the Kotlin `RedditSource` interface returns typed models (`Post`, `Media`, `CommentNode`, `Listing`, `CommentThread`). The UI depends only on that, so a markup change means patching `extract.js`, nothing else. Extraction failures, unexpected pages and "not logged in" results are logged with the URL under the `Roost` tag, and debug builds allow `chrome://inspect`.

## Detection and account risk

A read-only client at human pace inside a real WebView is about the lowest-risk way to do this; the usual result of detection is a 403 or captcha, not a ban. The risk is not zero, and this does break Reddit's terms of service.

Rules the client follows:

- Load only what I scroll to: one page at a time, no prefetching of other subreddits or threads
- No direct HTTP requests to Reddit pages outside the WebView, so every page request carries a real browser fingerprint. Media from Reddit's CDNs (`i.redd.it`, `preview.redd.it`, `v.redd.it`) is the exception: Coil and ExoPlayer fetch it directly with the WebView's user agent
- Default WebView user agent, unchanged for the whole session
- No voting, commenting or other writes (the strictest detection is on vote manipulation)
- On a 403/429, captcha or "blocked by network security" page: stop, show it to me ("Show page" reveals the WebView), and back off. Nothing loads for 1 minute, doubling to at most 30 minutes, rather than retrying in a loop

Fallback if the mobile site gets locked down further: the `RedditSource` interface allows another source, e.g. old.reddit parsing for sessions it still serves.

## Open questions

- [x] Does new Reddit's mobile markup still expose post data as `<shreddit-post>` attributes, and how are comments structured? Yes; see the extraction layer.
- [x] Does the hidden WebView load the mobile or desktop layout, and which is easier to extract? Mobile on `sh.reddit.com`. old.reddit (desktop) was easier but is gated.
- [ ] Main account or a secondary account for the first few weeks?
- [ ] Is battery use from a hidden WebView acceptable in daily use?
- [x] Open a tapped link in an external browser, or in a visible in-app WebView? External browser; Reddit thread and subreddit links open in the app.
