// Maxclean: "Фоновая активность вкладок". The page always sees its tab as visible and focused, so
// players and web apps don't stop when the browser is minimised or an overlay takes the focus.
// The replaced getters and hasFocus look like the built-ins they replace: same descriptors, names,
// lengths, no prototype, the same "Illegal invocation" errors and a native-looking toString.
// Every patch is guarded on its own: if one can't be applied, the others still are, and the page's
// own scripts are never affected (this runs as a separate script and touches no DOM nodes).
function () {
  var D = Document.prototype, F = Function.prototype;
  var gpd = Object.getOwnPropertyDescriptor, dp = Object.defineProperty;
  var fakes = new WeakMap();
  var looksNative = function (fn, name) { fakes.set(fn, 'function ' + name + '() { [native code] }'); };

  // Function.prototype.toString answers for our functions as it does for built-ins, itself included.
  try {
    var nativeToString = F.toString;
    var toString = new Proxy(nativeToString, {
      apply: function (target, self, args) {
        return fakes.has(self) ? fakes.get(self) : Reflect.apply(target, self, args);
      }
    });
    looksNative(toString, 'toString');
    var ts = gpd(F, 'toString');
    if (ts && ts.configurable) { ts.value = toString; dp(F, 'toString', ts); }
  } catch (e) {}

  // Getters. Calling the real getter first keeps its checks (same error on a wrong receiver).
  var fixed = { hidden: false, webkitHidden: false, visibilityState: 'visible', webkitVisibilityState: 'visible' };
  Object.keys(fixed).forEach(function (k) {
    try {
      var real = gpd(D, k);
      if (!real || !real.get || !real.configurable) return; // never add or force a property
      var realGet = real.get, value = fixed[k];
      var get = gpd({ get [k]() { realGet.call(this); return value; } }, k).get;
      looksNative(get, 'get ' + k);
      real.get = get;
      dp(D, k, real);
    } catch (e) {}
  });

  // hasFocus.
  var realHasFocus = D.hasFocus;
  try {
    var hf = gpd(D, 'hasFocus');
    if (hf && hf.configurable && typeof realHasFocus === 'function') {
      var hasFocus = { hasFocus() { realHasFocus.call(this); return true; } }.hasFocus;
      looksNative(hasFocus, 'hasFocus');
      hf.value = hasFocus;
      dp(D, 'hasFocus', hf);
    }
  } catch (e) {}

  // Events, stopped before any listener of the page. blur and focusout only when the focus leaves
  // the whole window; moving between fields of a form works as usual.
  try {
    var stop = function (e) { e.stopImmediatePropagation(); };
    var stopIfLeaving = function (e) {
      var leaving = true;
      try { leaving = e.target === window || e.target === document || !realHasFocus.call(document); } catch (x) {}
      if (leaving) e.stopImmediatePropagation();
    };
    window.addEventListener('visibilitychange', stop, true);
    window.addEventListener('webkitvisibilitychange', stop, true);
    window.addEventListener('blur', stopIfLeaving, true);
    window.addEventListener('focusout', stopIfLeaving, true);
  } catch (e) {}
}
