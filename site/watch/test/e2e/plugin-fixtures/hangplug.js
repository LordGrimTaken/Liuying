// Fixture: a tight synchronous loop. Must be stopped at 20 s without freezing the page.
module.exports.getStreams = function () { while (true) { /* spin */ } };
