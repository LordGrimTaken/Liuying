// Fixture: uses both bundled modules.
const CryptoJS = require('crypto-js');
const cheerio = require('cheerio-without-node-native');
module.exports.getStreams = async function (tmdbId) {
  const html = await (await fetch('https://api.fixture.dev/page')).text();
  const $ = cheerio.load(html);
  const href = $('a.dl').attr('href');
  return [{ name: 'Fixture Crypto', title: 'md5 ' + CryptoJS.MD5(tmdbId).toString().slice(0, 8), url: href, quality: '720p' }];
};
