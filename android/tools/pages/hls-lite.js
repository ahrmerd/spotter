/* Tiny HLS loader for the M3 sniffer test. Fetches the master playlist (so
 * shouldInterceptRequest sees it) and assigns a variant to the video element.
 * Not the full hls.js engine. */
(function (root) {
  function Hls() { this._listeners = {}; }
  Hls.isSupported = function () { return true; };
  Hls.prototype.on = function (ev, fn) { this._listeners[ev] = fn; };
  Hls.prototype.loadSource = function (url) { this._url = url; };
  Hls.prototype.attachMedia = function (video) {
    var url = this._url;
    fetch(url).then(function (r) { return r.text(); }).then(function (text) {
      var next = url;
      var lines = text.split(/\r?\n/);
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (line && line[0] !== "#") {
          next = line.indexOf("http") === 0 ? line : url.replace(/[^/]+$/, line);
          break;
        }
      }
      video.src = next;
    }).catch(function () { video.src = url; });
  };
  Hls.prototype.destroy = function () {};
  root.Hls = Hls;
})(window);
