/*
 * OpenBrowser Mobile —— 环境指纹注入脚本
 *
 * 该脚本作为 document-start 脚本注入到每个页面（含 iframe），
 * 所有改动都尽量贴近真实浏览器行为，并且同一环境的输出保持稳定（可复现）。
 *
 * 注入前会用 Profile.fingerprintJson() 替换 __OB_CONFIG__ 占位符。
 */
(function () {
  'use strict';

  var C = __OB_CONFIG__;
  if (!C) return;

  var NativeDate = Date;
  var nativeDateToString = NativeDate.prototype.toString;
  var nativeDateToTimeString = NativeDate.prototype.toTimeString;
  var nativeDateToDateString = NativeDate.prototype.toDateString;
  var nativeDateToLocaleString = NativeDate.prototype.toLocaleString;
  var nativeDateToLocaleDateString = NativeDate.prototype.toLocaleDateString;
  var nativeDateToLocaleTimeString = NativeDate.prototype.toLocaleTimeString;
  var nativeGetTimezoneOffset = NativeDate.prototype.getTimezoneOffset;

  function safe(fn) {
    try {
      fn();
    } catch (e) {
      /* 忽略单个模块失败，避免影响整页 */
    }
  }

  function defineGetter(obj, prop, getter) {
    try {
      Object.defineProperty(obj, prop, {
        get: getter,
        configurable: true,
        enumerable: true
      });
    } catch (e) {}
  }

  function defineValue(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, {
        value: value,
        configurable: true,
        writable: true
      });
    } catch (e) {}
  }

  var seed = (C.seed | 0) || 1;

  /* ------------------------------------------------------------------ *
   * 1. navigator
   * ------------------------------------------------------------------ */
  safe(function () {
    var ua = C.userAgent || navigator.userAgent;
    defineGetter(navigator, 'userAgent', function () {
      return ua;
    });
    defineGetter(navigator, 'appVersion', function () {
      return ua.replace(/^Mozilla\//, '');
    });
    defineGetter(navigator, 'platform', function () {
      return C.platform || navigator.platform;
    });
    defineGetter(navigator, 'vendor', function () {
      if (/iPhone|iPad|iPod/i.test(ua)) return 'Apple Computer, Inc.';
      return 'Google Inc.';
    });
    defineGetter(navigator, 'appName', function () {
      return 'Netscape';
    });
    defineGetter(navigator, 'appCodeName', function () {
      return 'Mozilla';
    });
    defineGetter(navigator, 'product', function () {
      return 'Gecko';
    });
    defineGetter(navigator, 'webdriver', function () {
      return false;
    });
    defineGetter(navigator, 'pdfViewerEnabled', function () {
      return true;
    });

    var langs = (C.languages && C.languages.length ? C.languages : [C.locale || 'zh-CN']).slice();
    defineGetter(navigator, 'language', function () {
      return langs[0];
    });
    defineGetter(navigator, 'languages', function () {
      return Object.freeze(langs.slice());
    });

    if (C.hardwareConcurrency) {
      defineGetter(navigator, 'hardwareConcurrency', function () {
        return C.hardwareConcurrency;
      });
    }
    if (C.deviceMemory) {
      defineGetter(navigator, 'deviceMemory', function () {
        return C.deviceMemory;
      });
    }
    if (typeof C.touchPoints === 'number') {
      defineGetter(navigator, 'maxTouchPoints', function () {
        return C.touchPoints;
      });
    }
  });

  /* ------------------------------------------------------------------ *
   * 2. 屏幕参数
   * ------------------------------------------------------------------ */
  safe(function () {
    var w = C.screenWidth | 0;
    var h = C.screenHeight | 0;
    if (w > 0 && h > 0) {
      defineGetter(screen, 'width', function () {
        return w;
      });
      defineGetter(screen, 'height', function () {
        return h;
      });
      defineGetter(screen, 'availWidth', function () {
        return w;
      });
      defineGetter(screen, 'availHeight', function () {
        return h;
      });
    }
    defineGetter(screen, 'colorDepth', function () {
      return 24;
    });
    defineGetter(screen, 'pixelDepth', function () {
      return 24;
    });
    if (C.devicePixelRatio) {
      defineGetter(window, 'devicePixelRatio', function () {
        return C.devicePixelRatio;
      });
    }
    defineGetter(window, 'outerWidth', function () {
      return window.innerWidth;
    });
    defineGetter(window, 'outerHeight', function () {
      return window.innerHeight;
    });
    defineGetter(window, 'screenX', function () {
      return 0;
    });
    defineGetter(window, 'screenY', function () {
      return 0;
    });
  });

  /* ------------------------------------------------------------------ *
   * 3. 时区
   * ------------------------------------------------------------------ */
  safe(function () {
    var tz = C.timezone;
    if (!tz) return;
    try {
      new Intl.DateTimeFormat('en-US', { timeZone: tz });
    } catch (e) {
      return;
    }

    var dtf = null;
    var longName = null;

    function zoneFormatter() {
      if (!dtf) {
        dtf = new Intl.DateTimeFormat('en-US', {
          timeZone: tz,
          hour12: false,
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit'
        });
      }
      return dtf;
    }

    function offsetMinutes(ts) {
      var parts = zoneFormatter().formatToParts(new NativeDate(ts));
      var map = {};
      for (var i = 0; i < parts.length; i++) map[parts[i].type] = parts[i].value;
      var hour = parseInt(map.hour, 10) % 24;
      var asUtc = NativeDate.UTC(
        parseInt(map.year, 10),
        parseInt(map.month, 10) - 1,
        parseInt(map.day, 10),
        hour,
        parseInt(map.minute, 10),
        parseInt(map.second, 10)
      );
      return Math.round((asUtc - ts) / 60000);
    }

    function zoneLongName() {
      if (longName) return longName;
      try {
        var parts = new Intl.DateTimeFormat('en-US', {
          timeZone: tz,
          timeZoneName: 'long'
        }).formatToParts(new NativeDate());
        for (var i = 0; i < parts.length; i++) {
          if (parts[i].type === 'timeZoneName') {
            longName = parts[i].value;
            break;
          }
        }
      } catch (e) {}
      if (!longName) longName = tz;
      return longName;
    }

    function offsetText(minutes) {
      var sign = minutes >= 0 ? '+' : '-';
      var abs = Math.abs(minutes);
      var hh = String(Math.floor(abs / 60)).padStart(2, '0');
      var mm = String(abs % 60).padStart(2, '0');
      return 'GMT' + sign + hh + mm;
    }

    // 把真实时间戳平移成“目标时区的本地时间”，再用原生 getter 取值。
    function shifted(date) {
      var ts = date.getTime();
      var target = offsetMinutes(ts);
      var system = -nativeGetTimezoneOffset.call(date);
      return new NativeDate(ts + (target - system) * 60000);
    }

    var nativeLocalGetters = {};
    [
      'getFullYear',
      'getMonth',
      'getDate',
      'getDay',
      'getHours',
      'getMinutes',
      'getSeconds',
      'getMilliseconds'
    ].forEach(function (name) {
      nativeLocalGetters[name] = NativeDate.prototype[name];
    });

    defineValue(NativeDate.prototype, 'getTimezoneOffset', function () {
      return -offsetMinutes(this.getTime());
    });

    Object.keys(nativeLocalGetters).forEach(function (name) {
      var nativeGetter = nativeLocalGetters[name];
      if (typeof nativeGetter !== 'function') return;
      defineValue(NativeDate.prototype, name, function () {
        return nativeGetter.call(shifted(this));
      });
    });

    var dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    var monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    function pad2(n) {
      return (n < 10 ? '0' : '') + n;
    }

    function localParts(date) {
      // 这里必须走原生 getter，否则会被上面打的补丁二次平移。
      var d = shifted(date);
      return {
        year: nativeLocalGetters.getFullYear.call(d),
        month: nativeLocalGetters.getMonth.call(d),
        day: nativeLocalGetters.getDate.call(d),
        weekday: nativeLocalGetters.getDay.call(d),
        hours: nativeLocalGetters.getHours.call(d),
        minutes: nativeLocalGetters.getMinutes.call(d),
        seconds: nativeLocalGetters.getSeconds.call(d),
        offset: offsetMinutes(date.getTime())
      };
    }

    defineValue(NativeDate.prototype, 'toString', function () {
      var p = localParts(this);
      return (
        dayNames[p.weekday] + ' ' + monthNames[p.month] + ' ' + pad2(p.day) + ' ' + p.year + ' ' +
        pad2(p.hours) + ':' + pad2(p.minutes) + ':' + pad2(p.seconds) + ' ' +
        offsetText(p.offset) + ' (' + zoneLongName() + ')'
      );
    });

    defineValue(NativeDate.prototype, 'toTimeString', function () {
      var p = localParts(this);
      return (
        pad2(p.hours) + ':' + pad2(p.minutes) + ':' + pad2(p.seconds) + ' ' +
        offsetText(p.offset) + ' (' + zoneLongName() + ')'
      );
    });

    defineValue(NativeDate.prototype, 'toDateString', function () {
      var p = localParts(this);
      return dayNames[p.weekday] + ' ' + monthNames[p.month] + ' ' + pad2(p.day) + ' ' + p.year;
    });

    function withZone(args) {
      var options = args.length > 1 && args[1] ? args[1] : {};
      if (!options.timeZone) {
        options = Object.assign({}, options, { timeZone: tz });
      }
      return [args.length > 0 ? args[0] : undefined, options];
    }

    defineValue(NativeDate.prototype, 'toLocaleString', function () {
      var a = withZone(arguments);
      return nativeDateToLocaleString.call(this, a[0], a[1]);
    });
    defineValue(NativeDate.prototype, 'toLocaleDateString', function () {
      var a = withZone(arguments);
      return nativeDateToLocaleDateString.call(this, a[0], a[1]);
    });
    defineValue(NativeDate.prototype, 'toLocaleTimeString', function () {
      var a = withZone(arguments);
      return nativeDateToLocaleTimeString.call(this, a[0], a[1]);
    });

    // Intl.DateTimeFormat 默认带上目标时区
    if (window.Intl && Intl.DateTimeFormat) {
      var NativeDTF = Intl.DateTimeFormat;

      function PatchedDateTimeFormat(locales, options) {
        var opts = options ? Object.assign({}, options) : {};
        if (!opts.timeZone) opts.timeZone = tz;
        return new NativeDTF(locales, opts);
      }
      PatchedDateTimeFormat.prototype = NativeDTF.prototype;
      PatchedDateTimeFormat.supportedLocalesOf = NativeDTF.supportedLocalesOf.bind(NativeDTF);
      defineValue(Intl, 'DateTimeFormat', PatchedDateTimeFormat);
    }
  });

  /* ------------------------------------------------------------------ *
   * 4. WebGL 厂商 / 渲染器
   * ------------------------------------------------------------------ */
  safe(function () {
    var vendor = C.webglVendor;
    var renderer = C.webglRenderer;
    if (!vendor && !renderer) return;

    ['WebGLRenderingContext', 'WebGL2RenderingContext'].forEach(function (name) {
      var Ctx = window[name];
      if (!Ctx || !Ctx.prototype || !Ctx.prototype.getParameter) return;
      var nativeGetParameter = Ctx.prototype.getParameter;
      defineValue(Ctx.prototype, 'getParameter', function (pname) {
        if (pname === 37445 || pname === 7936) return vendor || undefined;
        if (pname === 37446 || pname === 7937) return renderer || undefined;
        return nativeGetParameter.apply(this, arguments);
      });
    });
  });

  /* ------------------------------------------------------------------ *
   * 5. Canvas 噪声（同一环境结果稳定）
   * ------------------------------------------------------------------ */
  safe(function () {
    if (!C.canvasNoise) return;

    function noiseAt(i) {
      var x = (i * 2654435761 + seed * 40503) >>> 0;
      x ^= x >>> 15;
      x = Math.imul(x, 2246822519) >>> 0;
      x ^= x >>> 13;
      return (x % 3) - 1;
    }

    function clamp(v) {
      return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    function applyNoise(data) {
      for (var i = 0; i < data.length; i += 4) {
        data[i] = clamp(data[i] + noiseAt(i));
        data[i + 1] = clamp(data[i + 1] + noiseAt(i + 1));
        data[i + 2] = clamp(data[i + 2] + noiseAt(i + 2));
      }
      return data;
    }

    var C2D = window.CanvasRenderingContext2D;
    var nativeGetImageData = C2D && C2D.prototype.getImageData;
    if (nativeGetImageData) {
      defineValue(C2D.prototype, 'getImageData', function () {
        var image = nativeGetImageData.apply(this, arguments);
        try {
          applyNoise(image.data);
        } catch (e) {}
        return image;
      });
    }

    var O2D = window.OffscreenCanvasRenderingContext2D;
    var nativeOffscreenGet = O2D && O2D.prototype.getImageData;
    if (nativeOffscreenGet) {
      defineValue(O2D.prototype, 'getImageData', function () {
        var image = nativeOffscreenGet.apply(this, arguments);
        try {
          applyNoise(image.data);
        } catch (e) {}
        return image;
      });
    }

    var canvasProto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
    if (canvasProto && nativeGetImageData) {
      var nativeToDataURL = canvasProto.toDataURL;
      var nativeToBlob = canvasProto.toBlob;

      function readNoisy(canvas) {
        var ctx = canvas.getContext('2d');
        if (!ctx) return null;
        var image = nativeGetImageData.call(ctx, 0, 0, canvas.width, canvas.height);
        applyNoise(image.data);
        return image;
      }

      defineValue(canvasProto, 'toDataURL', function () {
        try {
          if (this.width > 0 && this.height > 0) {
            var image = readNoisy(this);
            if (image) {
              var tmp = document.createElement('canvas');
              tmp.width = this.width;
              tmp.height = this.height;
              tmp.getContext('2d').putImageData(image, 0, 0);
              return nativeToDataURL.apply(tmp, arguments);
            }
          }
        } catch (e) {}
        return nativeToDataURL.apply(this, arguments);
      });

      defineValue(canvasProto, 'toBlob', function () {
        var args = arguments;
        try {
          if (this.width > 0 && this.height > 0) {
            var image = readNoisy(this);
            if (image) {
              var tmp = document.createElement('canvas');
              tmp.width = this.width;
              tmp.height = this.height;
              tmp.getContext('2d').putImageData(image, 0, 0);
              return nativeToBlob.apply(tmp, args);
            }
          }
        } catch (e) {}
        return nativeToBlob.apply(this, args);
      });
    }
  });

  /* ------------------------------------------------------------------ *
   * 6. Audio 噪声
   * ------------------------------------------------------------------ */
  safe(function () {
    if (!C.audioNoise) return;

    function audioNoise(i) {
      var x = (i * 1597334677 + seed) >>> 0;
      x ^= x >>> 16;
      return ((x % 2000) / 1000 - 1) * 1e-7;
    }

    var AudioBufferCtor = window.AudioBuffer;
    if (AudioBufferCtor && AudioBufferCtor.prototype.getChannelData) {
      var nativeChannelData = AudioBufferCtor.prototype.getChannelData;
      defineValue(AudioBufferCtor.prototype, 'getChannelData', function () {
        var data = nativeChannelData.apply(this, arguments);
        try {
          if (!data.__obNoised) {
            for (var i = 0; i < data.length; i++) data[i] = data[i] + audioNoise(i);
            data.__obNoised = true;
          }
        } catch (e) {}
        return data;
      });
    }

    var analyserProto = window.AnalyserNode && window.AnalyserNode.prototype;
    if (analyserProto) {
      ['getFloatFrequencyData', 'getByteFrequencyData', 'getFloatTimeDomainData'].forEach(function (name) {
        var nativeMethod = analyserProto[name];
        if (typeof nativeMethod !== 'function') return;
        defineValue(analyserProto, name, function (array) {
          nativeMethod.apply(this, arguments);
          try {
            if (array && name === 'getFloatFrequencyData') {
              for (var i = 0; i < array.length; i += 16) array[i] = array[i] + audioNoise(i) * 10;
            }
          } catch (e) {}
        });
      });
    }
  });

  /* ------------------------------------------------------------------ *
   * 7. WebRTC 泄漏控制
   * ------------------------------------------------------------------ */
  safe(function () {
    var mode = C.webRtcMode || 'blocked';
    if (mode === 'real') return;

    var spoofIp = C.webRtcIp || '';
    var peer = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (!peer) return;

    function candidateText(candidate) {
      return candidate && candidate.candidate ? String(candidate.candidate) : '';
    }

    function ipType(text) {
      if (/\btyp\s+relay\b/.test(text)) return 'relay';
      if (/\btyp\s+host\b/.test(text)) return 'host';
      if (/\btyp\s+srflx\b/.test(text)) return 'srflx';
      if (/\btyp\s+prflx\b/.test(text)) return 'prflx';
      return 'other';
    }

    function rewrite(text) {
      var type = ipType(text);
      if (mode === 'blocked') {
        return type === 'relay' ? text : null;
      }
      if (mode === 'spoof' && spoofIp) {
        // 只替换 host / srflx 候选里的 IP 字段
        var replaced = text.replace(/(\bcandidate:\S+\s+\d+\s+\w+\s+\d+\s+)(\S+)(\s+\d+\s+typ\s+)/, function (m, p1, p2, p3) {
          return p1 + spoofIp + p3;
        });
        return replaced;
      }
      return text;
    }

    function filterEvent(event, pc) {
      if (!event || !event.candidate) return event;
      var text = candidateText(event.candidate);
      if (!text) return event;
      var next = rewrite(text);
      if (next === null) return null;
      if (next === text) return event;
      try {
        return {
          type: 'icecandidate',
          target: pc,
          currentTarget: pc,
          candidate: {
            candidate: next,
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
            usernameFragment: event.candidate.usernameFragment,
            address: spoofIp || undefined,
            protocol: event.candidate.protocol,
            port: event.candidate.port,
            type: event.candidate.type,
            foundation: event.candidate.foundation,
            priority: event.candidate.priority,
            relatedAddress: null,
            relatedPort: null,
            tcpType: event.candidate.tcpType
          }
        };
      } catch (e) {
        return event;
      }
    }

    var proto = peer.prototype;
    var descriptor = Object.getOwnPropertyDescriptor(proto, 'onicecandidate');
    if (descriptor && descriptor.set) {
      try {
        Object.defineProperty(proto, 'onicecandidate', {
          configurable: true,
          get: descriptor.get,
          set: function (handler) {
            if (typeof handler !== 'function') return descriptor.set.call(this, handler);
            var pc = this;
            return descriptor.set.call(this, function (event) {
              var filtered = filterEvent(event, pc);
              if (filtered) handler.call(pc, filtered);
            });
          }
        });
      } catch (e) {}
    }

    var nativeAddEventListener = proto.addEventListener;
    if (typeof nativeAddEventListener === 'function') {
      defineValue(proto, 'addEventListener', function (type, listener, options) {
        if (type === 'icecandidate' && typeof listener === 'function') {
          var pc = this;
          return nativeAddEventListener.call(this, type, function (event) {
            var filtered = filterEvent(event, pc);
            if (filtered) listener.call(pc, filtered);
          }, options);
        }
        return nativeAddEventListener.apply(this, arguments);
      });
    }
  });

  /* ------------------------------------------------------------------ *
   * 8. Client Hints / window.chrome
   * ------------------------------------------------------------------ */
  safe(function () {
    var ua = C.userAgent || navigator.userAgent;
    var chromeMatch = /Chrome\/(\d+)/.exec(ua);
    var fullVersion = chromeMatch ? chromeMatch[1] + '.0.0.0' : '';
    var major = chromeMatch ? chromeMatch[1] : '';

    var platformName = 'Windows';
    if (/Android/i.test(ua)) platformName = 'Android';
    else if (/iPhone|iPad|iPod/i.test(ua)) platformName = 'iOS';
    else if (/Macintosh|Mac OS X/i.test(ua)) platformName = 'macOS';
    else if (/Linux/i.test(ua)) platformName = 'Linux';

    if (chromeMatch) {
      var brands = [
        { brand: 'Google Chrome', version: major },
        { brand: 'Chromium', version: major },
        { brand: 'Not-A.Brand', version: '99' }
      ];
      var uaData = {
        brands: brands,
        mobile: !!C.mobile,
        platform: platformName,
        toJSON: function () {
          return { brands: brands, mobile: !!C.mobile, platform: platformName };
        },
        getHighEntropyValues: function () {
          return Promise.resolve({
            architecture: /arm|aarch/i.test(C.platform || '') ? 'arm' : 'x86',
            bitness: '64',
            brands: brands,
            fullVersionList: brands.map(function (b) {
              return { brand: b.brand, version: fullVersion };
            }),
            mobile: !!C.mobile,
            model: (function () {
              var m = /Android [\d.]+;\s*([^)]+?)\)/.exec(ua);
              return m && !/^K$|^wv$/i.test(m[1]) ? m[1] : '';
            })(),
            platform: platformName,
            platformVersion: (function () {
              var m = /Android ([\d.]+)/.exec(ua);
              if (m) return m[1] + '.0';
              m = /Windows NT ([\d.]+)/.exec(ua);
              if (m) return m[1] === '10.0' ? '15.0.0' : m[1];
              m = /Mac OS X ([\d_]+)/.exec(ua);
              if (m) return m[1].replace(/_/g, '.');
              m = /CPU (?:iPhone )?OS ([\d_]+)/.exec(ua);
              if (m) return m[1].replace(/_/g, '.');
              return '';
            })(),
            uaFullVersion: fullVersion,
            wow64: false
          });
        }
      };
      defineValue(navigator, 'userAgentData', uaData);
    }

    if (!('chrome' in window)) {
      defineValue(window, 'chrome', {
        app: { isInstalled: false },
        runtime: {},
        loadTimes: function () {
          return {};
        },
        csi: function () {
          return {};
        }
      });
    }
  });
})();
