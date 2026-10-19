// Maxclean fingerprint protection. Runs before any page script, in every frame.
// It makes this phone look like many others (fixed hardware values, no battery or network
// details, generic graphics chip) and adds tiny random noise to canvas reads, so sites cannot
// recognise the same device across visits. It lowers fingerprinting; it cannot remove it.
// `seed` is new every time Maxclean starts.
function (seed) {
  var def = function (obj, key, value) {
    try {
      Object.defineProperty(obj, key, { get: function () { return value; }, configurable: true });
    } catch (e) {}
  };

  // Hardware: the same answer on every phone.
  def(Navigator.prototype, 'hardwareConcurrency', 4);
  def(Navigator.prototype, 'deviceMemory', 4);
  def(Navigator.prototype, 'maxTouchPoints', 5);
  def(Screen.prototype, 'colorDepth', 24);
  def(Screen.prototype, 'pixelDepth', 24);

  // APIs that tell sites about the battery and the network.
  def(Navigator.prototype, 'getBattery', undefined);
  def(Navigator.prototype, 'connection', undefined);

  // Graphics chip: report a generic one instead of the real model.
  var glPatch = function (proto) {
    if (!proto) return;
    var orig = proto.getParameter;
    proto.getParameter = function (p) {
      if (p === 37445) return 'WebKit';        // UNMASKED_VENDOR_WEBGL
      if (p === 37446) return 'WebKit WebGL';  // UNMASKED_RENDERER_WEBGL
      return orig.apply(this, arguments);
    };
  };
  glPatch(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
  glPatch(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);

  // Canvas: flip the lowest bit of a few pixels, differently in every session.
  var state = seed >>> 0;
  var next = function () {
    state = (state + 0x6D2B79F5) >>> 0;
    var t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  var noise = function (data) {
    var n = Math.min(10, data.length >> 2);
    for (var i = 0; i < n; i++) {
      var at = (Math.floor(next() * (data.length >> 2)) << 2) + Math.floor(next() * 3);
      data[at] = data[at] ^ 1;
    }
  };
  var C2D = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
  if (C2D) {
    var getImageData = C2D.getImageData;
    C2D.getImageData = function () {
      var img = getImageData.apply(this, arguments);
      noise(img.data);
      return img;
    };
  }
  var noisyCopy = function (canvas) {
    try {
      if (!canvas.width || !canvas.height) return canvas;
      var copy = document.createElement('canvas');
      copy.width = canvas.width;
      copy.height = canvas.height;
      var ctx = copy.getContext('2d');
      ctx.drawImage(canvas, 0, 0);
      var img = getImageData.call(ctx, 0, 0, copy.width, copy.height);
      noise(img.data);
      ctx.putImageData(img, 0, 0);
      return copy;
    } catch (e) {
      return canvas;
    }
  };
  var CE = window.HTMLCanvasElement && HTMLCanvasElement.prototype;
  if (CE && C2D) {
    var toDataURL = CE.toDataURL, toBlob = CE.toBlob;
    CE.toDataURL = function () { return toDataURL.apply(noisyCopy(this), arguments); };
    CE.toBlob = function () { return toBlob.apply(noisyCopy(this), arguments); };
  }
}
