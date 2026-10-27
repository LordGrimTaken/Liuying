// Fixture: a well-behaved plugin.
async function getStreams(tmdbId, mediaType, season, episode) {
  const res = await fetch('https://api.fixture.dev/streams?id=' + encodeURIComponent(tmdbId) + '&type=' + mediaType + (season ? '&s=' + season + '&e=' + episode : ''));
  const data = await res.json();
  return data.items.map(function (i) { return { name: 'Fixture ' + i.q, title: i.title + ' [' + tmdbId + ']', url: i.url, quality: i.q, headers: i.headers }; });
}
module.exports = { getStreams: getStreams };
