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

Chosen: a hidden Android WebView loads real old.reddit.com pages, injected JavaScript extracts posts and comments from the DOM, and a native Compose UI renders them. To Reddit this looks like someone browsing old.reddit in Chrome on their phone, because the WebView is Chromium.

Why old.reddit rather than new Reddit: in the WebView, new Reddit serves its mobile site, and that site ignores front-page sorts. `reddit.com/new/`, `/top/?t=week` and `/rising/` all redirect to `reddit.com/?screen_view_count=…` (Best only), and multireddits (`r/a+b/top`) redirect to the front page too. Sorted subreddits worked, but a sorted front page was the main reason for building this. old.reddit supports every sort on the front page, subreddits and multireddits for the logged-in session. It renders on the server, its markup has been frozen for years, it returns full nested comment trees, and it paginates with a plain "next" link.

| Option | How it works | Why not / why |
| --- | --- | --- |
| Hidden WebView + DOM extraction of old.reddit (chosen) | Real page loads; JS pulls data out | Real TLS fingerprint, headers, JS, cookies and telemetry; slower and breaks on markup changes; desktop site on a phone |
| Hidden WebView + DOM extraction of new Reddit | Same, reading `<shreddit-post>` attributes | Mobile site has no sorted front page and no multireddits; comments arrive shallow |
| `.json` URLs with session cookie | HTTP client fetches `reddit.com/…/top.json?t=week` | Cleanest data, but browsers never request these URLs, so a session doing only this is easy to flag |
| Raw HTML fetch, spoofed user agent | OkHttp fetches pages, parses HTML | User agent is a weak signal; TLS/HTTP2 fingerprint, header order, missing client hints and no subresource loads still give it away |
| Patched Boost / public API | Existing app with own API key | Being shut down; already returning 403 |

## Architecture

&#91;embedded content: client architecture · 6 components\]

Every request to Reddit goes through one shared WebView. It stays attached underneath the Compose UI at full size, because Reddit's pages misbehave in a detached or `wrap_content` WebView: viewport units resolve to 0 and bottom sheets collapse. Login, block pages and debugging work by revealing that same WebView instead of using a second one. Requests are serialized, so only one page loads at a time.

## Login and session

Log in once in the visible WebView at `www.reddit.com/login/`; the `.reddit.com` session cookies then also work on old.reddit.com, and the front page is simply `old.reddit.com/` with my subscriptions.

- **Shared cookies**: Android's `CookieManager` is app-wide. Call `CookieManager.getInstance().flush()` after login so the session survives the app being killed.
- **Token refresh**: Reddit's short-lived token is refreshed by the page's own JS, so loading real pages keeps it fresh with no extra code.
- **Logout detection**: the extractor reports old.reddit's `body.loggedin` class. If it is missing, the login overlay opens; it closes itself once Reddit navigates away from the login pages.
- **Login method**: username and password, or Reddit's email code. Not "Continue with Google": Google blocks sign-in inside embedded WebViews (`disallowed_useragent`). 2FA works.
- **User agent**: keep the WebView's default, and never change it between login and browsing. Media requests (Coil, ExoPlayer) send the same user agent.

## Extraction layer

One small injected JS file turns the loaded page into JSON and hands it to Kotlin; it is the only part that knows Reddit's markup.

- **Injection**: after page load, `evaluateJavascript` runs `extract.js`, which defines `window.roost` with `extract()`, `loadMore(id)` and `moreLoaded(id)`. `extract()` returns a listing, a comment thread or a block-page marker; `evaluateJavascript` JSON-encodes the result.
- **Source of data**: listings are `#siteTable > .thing.link` with `data-*` attributes (fullname, author, subreddit, score, comments count, url, permalink, timestamp, nsfw, spoiler). Comments are `.thing.comment` nested through `.child > .sitetable`, with the score in `.score.unvoted[title]` and the body in `.usertext-body .md`. Subscriptions come from the "my subreddits" dropdown on every page.
- **Pagination**: listings follow the page's "next" link (`?count=25&after=…`), which loads only when the end of the list scrolls into view. Comment "load more" clicks old.reddit's own link in the hidden page, which fetches inline, then re-extracts the thread. "Continue this thread" opens as a new thread.
- **Media**: the expando HTML (`data-cachedhtml` on listings, rendered inline on threads) holds image previews with dimensions, gallery media IDs (full images at `i.redd.it/<id>.<ext>`) and `data-hls-url` for v.redd.it. Images load with Coil and video plays in ExoPlayer over HLS. Self-post text is not in listing HTML (old.reddit fetches it on expand), so feed cards show the title only and the thread shows the text.
- **Fragility**: the Kotlin `RedditSource` interface returns typed models (`Post`, `Media`, `CommentNode`, `Listing`, `CommentThread`). The UI depends only on that, so a markup change means patching `extract.js`, nothing else. Extraction failures are logged with the URL under the `Roost` tag, and debug builds allow `chrome://inspect`.

## Detection and account risk

A read-only client at human pace inside a real WebView is about the lowest-risk way to do this; the usual result of detection is a 403 or captcha, not a ban. The risk is not zero, and this does break Reddit's terms of service.

Rules the client follows:

- Load only what I scroll to: one page at a time, no prefetching of other subreddits or threads
- No direct HTTP requests to Reddit pages outside the WebView, so every page request carries a real browser fingerprint. Media from Reddit's CDNs (`i.redd.it`, `preview.redd.it`, `v.redd.it`) is the exception: Coil and ExoPlayer fetch it directly with the WebView's user agent
- Default WebView user agent, unchanged for the whole session
- No voting, commenting or other writes (the strictest detection is on vote manipulation)
- On a 403/429, captcha or "blocked by network security" page: stop, show it to me ("Show page" reveals the WebView), and back off. Nothing loads for 1 minute, doubling to at most 30 minutes, rather than retrying in a loop

Fallback if old.reddit is retired or locked down: the `RedditSource` interface allows switching back to new Reddit's mobile markup (`<shreddit-post>` attributes; front page Best only, sorted subreddits work).

## Open questions

- [x] Does new Reddit's mobile markup still expose post data as `<shreddit-post>` attributes, and how are comments structured? Yes for posts. Comment bodies sit under `<details>` in `#<thingid>-comment-rtjson-content`, nested by DOM, and only the first levels load. Moot now that old.reddit is used.
- [x] Does the hidden WebView load the mobile or desktop layout, and which is easier to extract? new Reddit serves mobile. old.reddit always serves desktop and is easier to extract.
- [ ] Main account or a secondary account for the first few weeks?
- [ ] Is battery use from a hidden WebView acceptable in daily use?
- [x] Open a tapped link in an external browser, or in a visible in-app WebView? External browser; Reddit thread and subreddit links open in the app.
