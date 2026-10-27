module.exports.getStreams = async function () { const r = await fetch('https://down.fixture.dev/api'); return r.json(); };
