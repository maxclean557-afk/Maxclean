// Maxclean: every page sees the time zone UTC, as in Tor Browser, instead of the phone's own.
// The time zone narrows down where you are, even through Tor. Dates, Intl and the Date
// constructor all agree on UTC, so the spoof can't be spotted by comparing them.
function () {
  var UTC = 'UTC', D = Date.prototype;
  var withUtc = function (o) {
    o = Object.assign({}, o);
    if (!o.timeZone) o.timeZone = UTC;
    return o;
  };

  D.getTimezoneOffset = function () { return 0; };
  ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (n) {
    D['get' + n] = D['getUTC' + n];
    if (D['setUTC' + n]) D['set' + n] = D['setUTC' + n];
  });

  // "Tue Nov 11 2026 18:22:05 GMT+0000 (Coordinated Universal Time)", built from the UTC form.
  var utcString = D.toUTCString;
  var parts = function (d) { return utcString.call(d).replace(',', '').split(' '); };
  var ZONE = 'GMT+0000 (Coordinated Universal Time)';
  D.toString = function () {
    if (isNaN(this)) return 'Invalid Date';
    var p = parts(this);
    return p[0] + ' ' + p[2] + ' ' + p[1] + ' ' + p[3] + ' ' + p[4] + ' ' + ZONE;
  };
  D.toDateString = function () {
    if (isNaN(this)) return 'Invalid Date';
    var p = parts(this);
    return p[0] + ' ' + p[2] + ' ' + p[1] + ' ' + p[3];
  };
  D.toTimeString = function () {
    if (isNaN(this)) return 'Invalid Date';
    return parts(this)[4] + ' ' + ZONE;
  };
  ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString'].forEach(function (n) {
    var orig = D[n];
    D[n] = function (locales, options) { return orig.call(this, locales, withUtc(options)); };
  });

  // Intl reports UTC and formats in it.
  var DTF = Intl.DateTimeFormat;
  var Wrapped = function (locales, options) { return new DTF(locales, withUtc(options)); };
  Wrapped.prototype = DTF.prototype;
  Wrapped.supportedLocalesOf = DTF.supportedLocalesOf;
  Intl.DateTimeFormat = Wrapped;

  // new Date(2026, 10, 11, 9, 30) means 9:30 in UTC now, like every other date here.
  var OrigDate = Date;
  var NewDate = function () {
    var a = Array.prototype.slice.call(arguments);
    if (!(this instanceof NewDate)) return new OrigDate().toString();
    if (a.length > 1) return new OrigDate(OrigDate.UTC.apply(null, a));
    return a.length ? new OrigDate(a[0]) : new OrigDate();
  };
  NewDate.prototype = D;
  NewDate.now = OrigDate.now;
  NewDate.parse = OrigDate.parse;
  NewDate.UTC = OrigDate.UTC;
  try { Object.defineProperty(D, 'constructor', { value: NewDate, configurable: true, writable: true }); } catch (e) {}
  window.Date = NewDate;
}
