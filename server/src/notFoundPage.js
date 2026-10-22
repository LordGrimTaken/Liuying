// Branded 404 page for browsers (plain text for everything else). Static markup, same assets as the site pages,
// so it works under the strict CSP (no inline script or style).
export const NOT_FOUND_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Page not found | Liuying</title>
<meta name="robots" content="noindex">
<meta name="theme-color" content="#14110f">
<link rel="icon" href="/watch/favicon.svg" type="image/svg+xml">
<link rel="stylesheet" href="/style.css"><link rel="stylesheet" href="/auth.css"></head>
<body class="auth-page" data-page="notfound">
<a class="skip" href="#main">Skip to content</a>
<header class="top"><div class="nav"><a class="brand" href="/"><span class="seal" aria-hidden="true">流<br>影</span><span>Liuying</span></a>
<nav class="links always" aria-label="Primary"><a class="link" href="/">Home</a><a class="link" href="/watch/">Web player</a></nav></div></header>
<main id="main" class="auth"><div class="mist" aria-hidden="true"></div>
<div class="auth-wrap"><section class="scroll" aria-labelledby="h"><div class="scroll-body">
<p class="eyebrow">Error 404</p><h1 id="h">Page not found</h1>
<p class="lede">The page you asked for is not here. It may have moved, or the link may be mistyped.</p>
<p><a class="btn pri" href="/">Go to the home page</a> <a class="btn sec" href="/watch/">Open the web player</a></p>
</div></section></div></main>
<footer class="slim"><div class="wrap"><small>Liuying does not host, provide or endorse any content. Sources are added by the user.</small></div></footer>
</body></html>
`;
