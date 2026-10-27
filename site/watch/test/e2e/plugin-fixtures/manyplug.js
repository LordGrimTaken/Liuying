// Fixture: asks for 40 pages; the 31st request must be refused (limit 30 per run).
module.exports.getStreams = async function () {
  let ok = 0, refused = '';
  for (let i = 0; i < 40; i++) {
    try { await fetch('https://api.fixture.dev/page?i=' + i); ok++; } catch (e) { refused = refused || String(e.message); }
  }
  return [{ name: 'Fixture Many', title: 'ok=' + ok + ' refused=' + refused, url: 'https://cdn.fixture.dev/many.mp4' }];
};
