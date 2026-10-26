(function () {
  var TIME_ZONE = "America/Chicago";
  var DESIGN_WIDTH = 1920;
  var DESIGN_HEIGHT = 1080;
  var REFRESH_MS = 15 * 60 * 1000;
  var RETRY_MS = 2 * 60 * 1000;
  var WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=41.2914&longitude=-87.4206&current=temperature_2m,weather_code,wind_speed_10m,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=America%2FChicago&forecast_days=5";

  var SUN = "#f2c14d";
  var CLOUD = "#f6f3ec";
  var RAIN = "#9ec4ef";
  var SNOW = "#d5e4f7";
  var MOON = "#f6f3ec";
  var SVG_NS = "http://www.w3.org/2000/svg";

  var clockEl = document.getElementById("clock");
  var ampmEl = document.getElementById("ampm");
  var dateEl = document.getElementById("date");
  var dashEl = document.getElementById("dashboard");
  var statusEl = document.getElementById("weather-status");
  var statusTitleEl = document.getElementById("weather-status-title");
  var statusBodyEl = document.getElementById("weather-status-body");
  var bodyEl = document.getElementById("weather-body");
  var currentEl = document.getElementById("weather-current");
  var forecastEl = document.getElementById("weather-forecast");

  var hasWeather = false;
  var requestInFlight = false;
  var weatherTimer = null;

  function fitStage() {
    if (!dashEl) return;
    var stageW = window.innerWidth || document.documentElement.clientWidth || DESIGN_WIDTH;
    var stageH = window.innerHeight || document.documentElement.clientHeight || DESIGN_HEIGHT;
    var scale = Math.min(stageW / DESIGN_WIDTH, stageH / DESIGN_HEIGHT);
    if (!(scale > 0)) scale = 1;
    var x = Math.round((stageW - DESIGN_WIDTH * scale) / 2);
    var y = Math.round((stageH - DESIGN_HEIGHT * scale) / 2);
    var transform = "scale(" + scale + ")";
    dashEl.style.left = x + "px";
    dashEl.style.top = y + "px";
    dashEl.style.webkitTransform = transform;
    dashEl.style.transform = transform;
    fitCalendar();
  }

  function renderZonedClock(now) {
    if (!window.Intl || !Intl.DateTimeFormat) return false;
    var timeFmt = new Intl.DateTimeFormat("en-US", {
      timeZone: TIME_ZONE,
      hour: "numeric",
      minute: "2-digit",
      hour12: true
    });
    var hour = "";
    var minute = "";
    var dayPeriod = "";
    if (typeof timeFmt.formatToParts === "function") {
      var parts = timeFmt.formatToParts(now);
      var i;
      for (i = 0; i < parts.length; i++) {
        if (parts[i].type === "hour") hour = parts[i].value;
        if (parts[i].type === "minute") minute = parts[i].value;
        if (parts[i].type === "dayPeriod") dayPeriod = parts[i].value;
      }
    } else {
      var formatted = timeFmt.format(now).replace("\u202f", " ").replace("\u00a0", " ");
      var bits = formatted.split(" ");
      hour = bits[0] || "";
      dayPeriod = bits.length > 1 ? bits[bits.length - 1] : "";
      var colon = hour.indexOf(":");
      if (colon !== -1) {
        minute = hour.substring(colon + 1);
        hour = hour.substring(0, colon);
      }
    }
    if (!hour || !minute) return false;
    clockEl.textContent = hour + ":" + minute;
    ampmEl.textContent = String(dayPeriod).toUpperCase();
    dateEl.textContent = new Intl.DateTimeFormat("en-US", {
      timeZone: TIME_ZONE,
      weekday: "long",
      month: "long",
      day: "numeric"
    }).format(now);
    return true;
  }

  function renderLocalClock(now) {
    var hours = now.getHours();
    var minutes = now.getMinutes();
    var ampm = hours >= 12 ? "PM" : "AM";
    hours = hours % 12;
    if (hours === 0) hours = 12;
    var minuteText = minutes < 10 ? "0" + minutes : String(minutes);
    var days = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
    var months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
    clockEl.textContent = hours + ":" + minuteText;
    ampmEl.textContent = ampm;
    dateEl.textContent = days[now.getDay()] + ", " + months[now.getMonth()] + " " + now.getDate();
  }

  function updateClock() {
    if (!clockEl || !ampmEl || !dateEl) return;
    var now = new Date();
    var shown = false;
    try {
      shown = renderZonedClock(now);
    } catch (e) {
      shown = false;
    }
    if (!shown) renderLocalClock(now);
  }

  function chicagoDateISO(now) {
    try {
      if (!window.Intl || !Intl.DateTimeFormat) return "";
      var fmt = new Intl.DateTimeFormat("en-US", {
        timeZone: TIME_ZONE,
        year: "numeric",
        month: "2-digit",
        day: "2-digit"
      });
      if (typeof fmt.formatToParts === "function") {
        var parts = fmt.formatToParts(now);
        var y = "";
        var m = "";
        var d = "";
        var i;
        for (i = 0; i < parts.length; i++) {
          if (parts[i].type === "year") y = parts[i].value;
          if (parts[i].type === "month") m = parts[i].value;
          if (parts[i].type === "day") d = parts[i].value;
        }
        if (y && m && d) return y + "-" + m + "-" + d;
      }
      var bits = fmt.format(now).split("/");
      if (bits.length === 3) {
        var mm = bits[0].length === 1 ? "0" + bits[0] : bits[0];
        var dd = bits[1].length === 1 ? "0" + bits[1] : bits[1];
        return bits[2] + "-" + mm + "-" + dd;
      }
    } catch (e2) {}
    return "";
  }

  function weekdayShort(iso) {
    var bits = String(iso || "").split("-");
    if (bits.length !== 3) return "";
    var y = parseInt(bits[0], 10);
    var m = parseInt(bits[1], 10);
    var d = parseInt(bits[2], 10);
    if (!y || !m || !d) return "";
    var names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    return names[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  }

  function asNum(value) {
    var n = parseFloat(value);
    if (isNaN(n) || !isFinite(n)) return null;
    return n;
  }

  function roundAway(n) {
    if (n < 0) return -Math.round(-n);
    return Math.round(n);
  }

  function formatTemp(n) {
    if (n === null) return "--";
    return roundAway(n) + "\u00B0";
  }

  function formatApiTime(iso) {
    if (!iso || String(iso).length < 16) return "";
    var text = String(iso);
    var hh = parseInt(text.substring(11, 13), 10);
    var mm = text.substring(14, 16);
    if (isNaN(hh)) return "";
    var suffix = hh >= 12 ? "PM" : "AM";
    var h12 = hh % 12;
    if (h12 === 0) h12 = 12;
    return h12 + ":" + mm + " " + suffix;
  }

  function describeCode(code) {
    if (code === 0) return "Clear";
    if (code === 1) return "Mostly clear";
    if (code === 2) return "Partly cloudy";
    if (code === 3) return "Cloudy";
    if (code === 45 || code === 48) return "Fog";
    if (code === 51 || code === 53 || code === 55) return "Drizzle";
    if (code === 56 || code === 57) return "Freezing drizzle";
    if (code === 61) return "Light rain";
    if (code === 63) return "Rain";
    if (code === 65) return "Heavy rain";
    if (code === 66 || code === 67) return "Freezing rain";
    if (code === 71) return "Light snow";
    if (code === 73) return "Snow";
    if (code === 75) return "Heavy snow";
    if (code === 77) return "Snow grains";
    if (code === 80) return "Light showers";
    if (code === 81) return "Showers";
    if (code === 82) return "Heavy showers";
    if (code === 85) return "Snow showers";
    if (code === 86) return "Heavy snow showers";
    if (code === 95 || code === 96 || code === 99) return "Thunderstorm";
    return "Weather";
  }

  function iconKind(code, daytime) {
    if (code === 0 || code === 1) return daytime ? "clear" : "moon";
    if (code === 2) return daytime ? "partly" : "partly-night";
    if (code === 3) return "cloudy";
    if (code === 45 || code === 48) return "fog";
    if (code >= 51 && code <= 57) return "drizzle";
    if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "rain";
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) return "snow";
    if (code >= 95 && code <= 99) return "thunder";
    return "cloudy";
  }

  function isDaytime(current) {
    if (!current) return true;
    if (current.is_day === 0 || current.is_day === "0") return false;
    if (current.is_day === 1 || current.is_day === "1") return true;
    if (current.time && String(current.time).length >= 13) {
      var hh = parseInt(String(current.time).substring(11, 13), 10);
      if (!isNaN(hh)) return hh >= 6 && hh < 20;
    }
    return true;
  }

  function svgEl(name, attrs) {
    var node = document.createElementNS(SVG_NS, name);
    var key;
    for (key in attrs) {
      if (Object.prototype.hasOwnProperty.call(attrs, key)) {
        node.setAttribute(key, attrs[key]);
      }
    }
    return node;
  }

  function addSun(parent, cx, cy, r) {
    parent.appendChild(svgEl("circle", {
      cx: String(cx),
      cy: String(cy),
      r: String(r),
      fill: SUN
    }));
    var i;
    for (i = 0; i < 8; i++) {
      var rad = i * 45 * Math.PI / 180;
      var x1 = cx + Math.cos(rad) * (r + 4);
      var y1 = cy + Math.sin(rad) * (r + 4);
      var x2 = cx + Math.cos(rad) * (r + 12);
      var y2 = cy + Math.sin(rad) * (r + 12);
      parent.appendChild(svgEl("line", {
        x1: x1.toFixed(1),
        y1: y1.toFixed(1),
        x2: x2.toFixed(1),
        y2: y2.toFixed(1),
        stroke: SUN,
        "stroke-width": "4",
        "stroke-linecap": "round"
      }));
    }
  }

  function addCloud(parent) {
    parent.appendChild(svgEl("circle", { cx: "27", cy: "40", r: "11", fill: CLOUD }));
    parent.appendChild(svgEl("circle", { cx: "40", cy: "33", r: "15", fill: CLOUD }));
    parent.appendChild(svgEl("circle", { cx: "53", cy: "40", r: "10", fill: CLOUD }));
    parent.appendChild(svgEl("rect", { x: "18", y: "38", width: "42", height: "14", rx: "7", fill: CLOUD }));
  }

  function addDrops(parent, light) {
    var drops = light
      ? [[24, 52, 24, 58], [34, 54, 34, 60], [46, 52, 46, 58]]
      : [[24, 50, 24, 62], [35, 52, 35, 64], [47, 50, 47, 62]];
    var i;
    for (i = 0; i < drops.length; i++) {
      parent.appendChild(svgEl("line", {
        x1: String(drops[i][0]),
        y1: String(drops[i][1]),
        x2: String(drops[i][2]),
        y2: String(drops[i][3]),
        stroke: RAIN,
        "stroke-width": light ? "3" : "4",
        "stroke-linecap": "round"
      }));
    }
  }

  function addSnow(parent) {
    var flakes = [[24, 56], [36, 60], [48, 55]];
    var i;
    var j;
    for (i = 0; i < flakes.length; i++) {
      var cx = flakes[i][0];
      var cy = flakes[i][1];
      var arms = [[0, -5, 0, 5], [-5, 0, 5, 0], [-3.5, -3.5, 3.5, 3.5], [-3.5, 3.5, 3.5, -3.5]];
      for (j = 0; j < arms.length; j++) {
        parent.appendChild(svgEl("line", {
          x1: String(cx + arms[j][0]),
          y1: String(cy + arms[j][1]),
          x2: String(cx + arms[j][2]),
          y2: String(cy + arms[j][3]),
          stroke: SNOW,
          "stroke-width": "2",
          "stroke-linecap": "round"
        }));
      }
    }
  }

  function weatherIcon(kind) {
    var svg = svgEl("svg", {
      viewBox: "0 0 64 64",
      class: "wx-icon",
      "aria-hidden": "true",
      focusable: "false"
    });
    if (kind === "clear") {
      addSun(svg, 32, 32, 11);
    } else if (kind === "moon") {
      svg.appendChild(svgEl("path", {
        d: "M44 14 A 18 18 0 1 0 44 50",
        fill: "none",
        stroke: MOON,
        "stroke-width": "7",
        "stroke-linecap": "round"
      }));
    } else if (kind === "partly" || kind === "partly-night") {
      if (kind === "partly") addSun(svg, 18, 20, 8);
      else {
        svg.appendChild(svgEl("path", {
          d: "M28 12 A 11 11 0 1 0 28 36",
          fill: "none",
          stroke: MOON,
          "stroke-width": "5",
          "stroke-linecap": "round"
        }));
      }
      var g = svgEl("g", { transform: "translate(16 18) scale(0.74)" });
      addCloud(g);
      svg.appendChild(g);
    } else if (kind === "fog") {
      var bars = [[12, 18, 40], [18, 30, 34], [14, 42, 38], [20, 54, 28]];
      var b;
      for (b = 0; b < bars.length; b++) {
        svg.appendChild(svgEl("rect", {
          x: String(bars[b][0]),
          y: String(bars[b][1]),
          width: String(bars[b][2]),
          height: "5",
          rx: "2.5",
          fill: CLOUD
        }));
      }
    } else if (kind === "drizzle" || kind === "rain" || kind === "snow" || kind === "thunder") {
      var cg = svgEl("g", { transform: "translate(0 -8) scale(0.92)" });
      addCloud(cg);
      svg.appendChild(cg);
      if (kind === "drizzle") addDrops(svg, true);
      else if (kind === "rain") addDrops(svg, false);
      else if (kind === "snow") addSnow(svg);
      else {
        svg.appendChild(svgEl("path", {
          d: "M36 44 L29 54 H35 L31 66 L46 52 H39 L43 44 Z",
          fill: SUN
        }));
      }
    } else {
      addCloud(svg);
    }
    return svg;
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (typeof text === "string") node.textContent = text;
    return node;
  }

  function clearNode(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function showStatus(title, body) {
    if (statusTitleEl) statusTitleEl.textContent = title;
    if (statusBodyEl) statusBodyEl.textContent = body;
    if (statusEl) statusEl.className = "weather-status";
    if (bodyEl) bodyEl.className = "weather-body";
  }

  function showWeather() {
    if (statusEl) statusEl.className = "weather-status is-hidden";
    if (bodyEl) bodyEl.className = "weather-body is-on";
  }

  function renderWeatherError() {
    if (hasWeather) {
      var note = document.getElementById("weather-note");
      if (!note && currentEl) {
        note = el("p", "weather-note", "Couldn't refresh the forecast. Showing the last update.");
        note.id = "weather-note";
        currentEl.appendChild(note);
      }
      return;
    }
    showStatus(
      "Weather unavailable",
      "The forecast didn't load. The clock is still running, and weather will try again shortly."
    );
  }

  function renderWeather(data) {
    var current = data.current;
    var daily = data.daily;
    var temp = asNum(current.temperature_2m);
    var code = parseInt(current.weather_code, 10);
    if (isNaN(code)) code = -1;
    var wind = asNum(current.wind_speed_10m);
    var highs = daily.temperature_2m_max;
    var lows = daily.temperature_2m_min;
    var codes = daily.weather_code || [];
    var days = daily.time;
    if (temp === null || !days || !highs || !lows) {
      throw new Error("incomplete weather");
    }
    var count = days.length;
    if (highs.length < count) count = highs.length;
    if (lows.length < count) count = lows.length;
    if (count > 5) count = 5;
    if (count < 1) throw new Error("no days");

    var today = "";
    if (current.time && String(current.time).length >= 10) today = String(current.time).substring(0, 10);
    if (!today) today = chicagoDateISO(new Date());

    clearNode(currentEl);
    clearNode(forecastEl);

    currentEl.appendChild(el("p", "kicker", "Lowell, Indiana"));
    var main = el("div", "now-main");
    main.appendChild(weatherIcon(iconKind(code, isDaytime(current))));
    main.appendChild(el("p", "temp", formatTemp(temp)));
    currentEl.appendChild(main);
    currentEl.appendChild(el("p", "condition", describeCode(code)));

    var high0 = asNum(highs[0]);
    var low0 = asNum(lows[0]);
    currentEl.appendChild(el("p", "detail detail-first", "High " + formatTemp(high0)));
    currentEl.appendChild(el("p", "detail", "Low " + formatTemp(low0)));
    if (wind !== null) {
      currentEl.appendChild(el("p", "detail", "Wind " + roundAway(wind) + " mph"));
    }
    var updated = formatApiTime(current.time);
    var source = updated ? "Updated " + updated + " \u00B7 open-meteo.com" : "Weather from open-meteo.com";
    currentEl.appendChild(el("p", "updated", source));

    forecastEl.appendChild(el("p", "kicker", "Next days"));
    var table = el("div", "forecast-table");
    var labels = el("div", "forecast-labels");
    labels.appendChild(el("div", "slot-name", "Day"));
    labels.appendChild(el("div", "slot-icon"));
    labels.appendChild(el("div", "slot-high", "High"));
    labels.appendChild(el("div", "slot-low", "Low"));
    table.appendChild(labels);

    var i;
    for (i = 0; i < count; i++) {
      var dayHigh = asNum(highs[i]);
      var dayLow = asNum(lows[i]);
      var dayCode = parseInt(codes[i], 10);
      if (isNaN(dayCode)) dayCode = 3;
      var name = days[i] === today ? "Today" : weekdayShort(days[i]);
      var col = el("div", "forecast-day");
      col.setAttribute("aria-label", name + ", high " + formatTemp(dayHigh) + ", low " + formatTemp(dayLow) + ", " + describeCode(dayCode));
      col.appendChild(el("div", "slot-name", name));
      var iconSlot = el("div", "slot-icon");
      iconSlot.appendChild(weatherIcon(iconKind(dayCode, true)));
      col.appendChild(iconSlot);
      col.appendChild(el("div", "slot-high", formatTemp(dayHigh)));
      col.appendChild(el("div", "slot-low", formatTemp(dayLow)));
      table.appendChild(col);
    }
    forecastEl.appendChild(table);
    showWeather();
    hasWeather = true;
  }

  function scheduleWeather(delay) {
    if (weatherTimer) window.clearTimeout(weatherTimer);
    weatherTimer = window.setTimeout(fetchWeather, delay);
  }

  function fetchWeather() {
    if (requestInFlight) return;
    if (weatherTimer) {
      window.clearTimeout(weatherTimer);
      weatherTimer = null;
    }
    requestInFlight = true;
    var settled = false;
    function finish(ok) {
      if (settled) return;
      settled = true;
      requestInFlight = false;
      if (!ok) {
        try {
          renderWeatherError();
        } catch (e2) {}
      }
      scheduleWeather(ok ? REFRESH_MS : RETRY_MS);
    }
    var xhr = new XMLHttpRequest();
    xhr.open("GET", WEATHER_URL + "&_=" + new Date().getTime(), true);
    xhr.timeout = 20000;
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      var ok = false;
      try {
        if (xhr.status >= 200 && xhr.status < 300) {
          renderWeather(JSON.parse(xhr.responseText));
          ok = true;
        }
      } catch (e) {
        ok = false;
      }
      finish(ok);
    };
    xhr.onerror = function () { finish(false); };
    xhr.ontimeout = function () { finish(false); };
    try {
      xhr.send();
    } catch (e3) {
      finish(false);
    }
  }

  var calendarEl = document.getElementById("calendar");
  var calendarListEl = document.getElementById("calendar-list");
  var calendarNoteEl = document.getElementById("calendar-note");
  var lastCalendarSnapshot = null;

  function renderCalendar(snapshot) {
    lastCalendarSnapshot = snapshot;
    if (!calendarEl || !calendarListEl || !calendarNoteEl) return;
    if (!snapshot || !snapshot.configured) {
      calendarEl.className = "panel panel-calendar";
      clearNode(calendarListEl);
      calendarNoteEl.className = "calendar-note";
      calendarNoteEl.textContent = "";
      syncCalendarTarget();
      if (monthOpen) closeMonthView();
      return;
    }
    var focused = calendarEl.className.indexOf("is-focused") >= 0;
    calendarEl.className = focused ? "panel panel-calendar is-on is-focused" : "panel panel-calendar is-on";
    clearNode(calendarListEl);
    var events = snapshot.events || [];
    if (!events.length) {
      var empty = snapshot.loading ? "Loading calendar" : (snapshot.stale ? "" : "Nothing coming up");
      if (empty) calendarListEl.appendChild(el("p", "calendar-empty", empty));
    } else {
      var lastHeading = "";
      var i;
      for (i = 0; i < events.length; i++) {
        var ev = events[i];
        if (!ev) continue;
        var heading = ev.heading || "";
        if (heading !== lastHeading) {
          calendarListEl.appendChild(el("p", "calendar-day", heading));
          lastHeading = heading;
        }
        var row = el("div", "calendar-row");
        row.appendChild(el("p", "calendar-time", ev.time || (ev.allDay ? "All day" : "")));
        row.appendChild(el("p", "calendar-title", ev.title || "Busy"));
        calendarListEl.appendChild(row);
      }
    }
    if (snapshot.stale) {
      calendarNoteEl.textContent = "Calendar not updated";
      calendarNoteEl.className = "calendar-note is-on";
    } else {
      calendarNoteEl.textContent = "";
      calendarNoteEl.className = "calendar-note";
    }
    fitCalendar();
    syncCalendarTarget();
    if (monthOpen) {
      if (!snapshot.configured) closeMonthView();
      else renderMonth();
    }
  }

  function fitCalendar() {
    if (!calendarEl || !calendarListEl || calendarEl.className.indexOf("is-on") < 0) return;
    if (calendarListEl.clientHeight < 40) return;
    var guard = 0;
    while (guard < 40 && calendarListEl.lastChild && calendarListEl.scrollHeight > calendarListEl.clientHeight + 1) {
      calendarListEl.removeChild(calendarListEl.lastChild);
      guard++;
    }
    var tail = calendarListEl.lastChild;
    while (tail && tail.className === "calendar-day") {
      calendarListEl.removeChild(tail);
      tail = calendarListEl.lastChild;
    }
  }

  function pullCalendar() {
    if (!window.FamilyCalendar || typeof window.FamilyCalendar.getSnapshot !== "function") {
      return;
    }
    try {
      renderCalendar(JSON.parse(window.FamilyCalendar.getSnapshot()));
    } catch (e) {}
  }

  window.onCalendarSnapshot = function (snapshot) {
    renderCalendar(snapshot);
  };

  if (typeof window.__tvKeys === "undefined") window.__tvKeys = false;
  var monthEl = document.getElementById("month");
  var monthTitleEl = document.getElementById("month-title");
  var monthGridEl = document.getElementById("month-grid");
  var monthOpen = false;
  var monthCursor = null;
  var MONTH_NAMES = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  ];

  function syncCalendarTarget() {
    if (!calendarEl) return;
    var on = !!(window.__tvKeys && lastCalendarSnapshot && lastCalendarSnapshot.configured);
    if (on) {
      calendarEl.setAttribute("tabindex", "0");
    } else {
      calendarEl.removeAttribute("tabindex");
      if (document.activeElement === calendarEl) calendarEl.blur();
    }
  }

  function monthFromToday(today) {
    var y = 0;
    var m = -1;
    if (typeof today === "string" && today.length >= 7) {
      y = parseInt(today.slice(0, 4), 10);
      m = parseInt(today.slice(5, 7), 10) - 1;
    }
    if (!y || m < 0 || m > 11) {
      var fallback = chicagoTodayParts();
      y = fallback.y;
      m = fallback.m;
    }
    return { y: y, m: m };
  }

  function chicagoTodayParts() {
    try {
      var fmt = new Intl.DateTimeFormat("en-US", {
        timeZone: "America/Chicago",
        year: "numeric",
        month: "2-digit"
      });
      var parts = fmt.formatToParts(new Date());
      var y = 0;
      var m = 0;
      var i;
      for (i = 0; i < parts.length; i++) {
        if (parts[i].type === "year") y = parseInt(parts[i].value, 10);
        if (parts[i].type === "month") m = parseInt(parts[i].value, 10) - 1;
      }
      if (y && m >= 0 && m <= 11) return { y: y, m: m };
    } catch (e) {}
    var now = new Date();
    return { y: now.getFullYear(), m: now.getMonth() };
  }

  function daysInMonth(y, m) {
    return new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  }

  function monthDayKey(y, m, d) {
    var mm = m + 1;
    return y + "-" + (mm < 10 ? "0" : "") + mm + "-" + (d < 10 ? "0" : "") + d;
  }

  function openMonthView() {
    if (!monthEl || !lastCalendarSnapshot || !lastCalendarSnapshot.configured) return;
    if (!monthOpen || !monthCursor) {
      monthCursor = monthFromToday(lastCalendarSnapshot.today);
    }
    monthOpen = true;
    monthEl.className = "month is-on";
    monthEl.setAttribute("aria-hidden", "false");
    renderMonth();
  }

  function closeMonthView() {
    monthOpen = false;
    if (!monthEl) return;
    monthEl.className = "month";
    monthEl.setAttribute("aria-hidden", "true");
  }

  function shiftMonth(delta) {
    if (!monthOpen || !monthCursor) return;
    var m = monthCursor.m + delta;
    var y = monthCursor.y;
    while (m < 0) {
      m += 12;
      y -= 1;
    }
    while (m > 11) {
      m -= 12;
      y += 1;
    }
    monthCursor = { y: y, m: m };
    renderMonth();
  }

  function renderMonth() {
    if (!monthOpen || !monthCursor || !monthGridEl || !monthTitleEl) return;
    var y = monthCursor.y;
    var m = monthCursor.m;
    monthTitleEl.textContent = MONTH_NAMES[m] + " " + y;
    clearNode(monthGridEl);
    var byDay = {};
    var events = (lastCalendarSnapshot && lastCalendarSnapshot.month) || [];
    var i;
    for (i = 0; i < events.length; i++) {
      var ev = events[i];
      if (!ev || !ev.day) continue;
      if (!byDay[ev.day]) byDay[ev.day] = [];
      byDay[ev.day].push(ev);
    }
    var firstDow = new Date(Date.UTC(y, m, 1)).getUTCDay();
    var dim = daysInMonth(y, m);
    var weeks = Math.ceil((firstDow + dim) / 7);
    if (weeks < 4) weeks = 4;
    var todayKey = (lastCalendarSnapshot && lastCalendarSnapshot.today) || "";
    var prevDim = daysInMonth(y, m - 1);
    var week;
    for (week = 0; week < weeks; week++) {
      var row = el("div", "month-week");
      var col;
      for (col = 0; col < 7; col++) {
        var index = week * 7 + col;
        var dayNum;
        var cellY = y;
        var cellM = m;
        var out = false;
        if (index < firstDow) {
          out = true;
          dayNum = prevDim - firstDow + index + 1;
          cellM = m - 1;
          if (cellM < 0) {
            cellM = 11;
            cellY = y - 1;
          }
        } else if (index >= firstDow + dim) {
          out = true;
          dayNum = index - (firstDow + dim) + 1;
          cellM = m + 1;
          if (cellM > 11) {
            cellM = 0;
            cellY = y + 1;
          }
        } else {
          dayNum = index - firstDow + 1;
        }
        var key = monthDayKey(cellY, cellM, dayNum);
        var cls = "month-cell";
        if (out) cls += " is-out";
        if (key === todayKey) cls += " is-today";
        var cell = el("div", cls);
        cell.appendChild(el("p", "month-num", String(dayNum)));
        var list = byDay[key] || [];
        var shown = list.length > 3 ? 3 : list.length;
        var n;
        for (n = 0; n < shown; n++) {
          var item = list[n];
          cell.appendChild(el(
            "p",
            item && item.allDay ? "month-event is-all-day" : "month-event",
            (item && item.title) || "Busy"
          ));
        }
        if (list.length > 3) {
          cell.appendChild(el("p", "month-more", "+" + (list.length - 3) + " more"));
        }
        row.appendChild(cell);
      }
      monthGridEl.appendChild(row);
    }
  }

  window.onDashKey = function (name) {
    if (!window.__tvKeys) return;
    if (name === "focus") {
      syncCalendarTarget();
      setPhotoFocused(false);
      if (calendarEl && calendarEl.getAttribute("tabindex") === "0") {
        calendarEl.className = "panel panel-calendar is-on is-focused";
        calendarEl.focus();
      }
      return;
    }
    if (name === "focus-photo") {
      setCalendarFocused(false);
      setPhotoFocused(true);
      return;
    }
    if (name === "open-settings") {
      openPhotoSettings();
      return;
    }
    if (name === "close-settings") {
      closePhotoSettings();
      return;
    }
    if (name === "seconds-prev") {
      stepPhotoSeconds(-1);
      return;
    }
    if (name === "seconds-next") {
      stepPhotoSeconds(1);
      return;
    }
    if (name === "open") {
      openMonthView();
      return;
    }
    if (name === "close") {
      closeMonthView();
      return;
    }
    if (name === "prev") {
      shiftMonth(-1);
      return;
    }
    if (name === "next") {
      shiftMonth(1);
    }
  };

  var PHOTO_STEPS = [5, 10, 15, 20, 30, 45, 60, 90, 120, 300, 600];
  var PHOTO_MS = 18000;
  var photoSeconds = 18;
  var photoPanel = document.getElementById("photo");
  var photoFrame = document.getElementById("photo-frame");
  var photoA = document.getElementById("photo-a");
  var photoB = document.getElementById("photo-b");
  var photoBgA = document.getElementById("photo-bg-a");
  var photoBgB = document.getElementById("photo-bg-b");
  var photoSettings = document.getElementById("photo-settings");
  var photoSecondsEl = document.getElementById("photo-seconds");
  var photoUrls = [];
  var photoIndex = 0;
  var photoTimer = null;
  var photoKey = "";
  var photoShowingA = true;
  var photoSettingsOpen = false;

  function blurFor(img) {
    if (img === photoA) return photoBgA;
    if (img === photoB) return photoBgB;
    return null;
  }

  function setCalendarFocused(on) {
    if (!calendarEl) return;
    if (!on) {
      calendarEl.className = calendarEl.className.replace(" is-focused", "");
      return;
    }
    if (calendarEl.className.indexOf("is-on") < 0) return;
    if (calendarEl.className.indexOf("is-focused") < 0) {
      calendarEl.className = calendarEl.className + " is-focused";
    }
    calendarEl.focus();
  }

  function setPhotoFocused(on) {
    if (!photoPanel || photoPanel.className.indexOf("is-on") < 0) {
      if (photoPanel && !on) photoPanel.className = photoPanel.className.replace(" is-focused", "");
      return;
    }
    var cls = "panel panel-photo is-on";
    if (on) cls += " is-focused";
    photoPanel.className = cls;
    if (on) {
      photoPanel.setAttribute("tabindex", "0");
      photoPanel.focus();
    }
  }

  function showPhotoSeconds() {
    if (photoSecondsEl) photoSecondsEl.textContent = String(photoSeconds);
  }

  function applyPhotoInterval(seconds) {
    var n = parseInt(seconds, 10);
    if (isNaN(n) || n < 5 || n > 600) n = 18;
    var changed = n !== photoSeconds;
    photoSeconds = n;
    PHOTO_MS = n * 1000;
    showPhotoSeconds();
    if (!changed) return;
    if (photoTimer) window.clearInterval(photoTimer);
    photoTimer = null;
    if (photoUrls.length > 1) {
      photoTimer = window.setInterval(advancePhoto, PHOTO_MS);
    }
  }

  function stepPhotoSeconds(delta) {
    var next = photoSeconds;
    var idx = PHOTO_STEPS.indexOf(photoSeconds);
    var i;
    if (idx < 0) {
      if (delta > 0) {
        next = PHOTO_STEPS[PHOTO_STEPS.length - 1];
        for (i = 0; i < PHOTO_STEPS.length; i++) {
          if (PHOTO_STEPS[i] > photoSeconds) {
            next = PHOTO_STEPS[i];
            break;
          }
        }
      } else {
        next = PHOTO_STEPS[0];
        for (i = PHOTO_STEPS.length - 1; i >= 0; i--) {
          if (PHOTO_STEPS[i] < photoSeconds) {
            next = PHOTO_STEPS[i];
            break;
          }
        }
      }
    } else {
      var at = idx + delta;
      if (at < 0) at = 0;
      if (at >= PHOTO_STEPS.length) at = PHOTO_STEPS.length - 1;
      next = PHOTO_STEPS[at];
    }
    if (next === photoSeconds) return;
    applyPhotoInterval(next);
    try {
      if (window.FamilyPhotos && typeof window.FamilyPhotos.setSeconds === "function") {
        window.FamilyPhotos.setSeconds(next);
      }
    } catch (e) {}
  }

  function openPhotoSettings() {
    if (!photoSettings || !photoPanel || photoPanel.className.indexOf("is-on") < 0) return;
    photoSettingsOpen = true;
    photoSettings.className = "photo-settings is-on";
    photoSettings.setAttribute("aria-hidden", "false");
    showPhotoSeconds();
  }

  function closePhotoSettings() {
    photoSettingsOpen = false;
    if (!photoSettings) return;
    photoSettings.className = "photo-settings";
    photoSettings.setAttribute("aria-hidden", "true");
  }

  function allowedPhotoUrl(url) {
    if (typeof url !== "string") return false;
    if (/^https:\/\/appassets\.androidplatform\.net\/photos\/[a-f0-9]{32}\.jpg$/.test(url)) return true;
    var host = window.location.hostname;
    if (host === "127.0.0.1" || host === "localhost") {
      return /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\/sample-photo\.(png|jpe?g)$/.test(url);
    }
    return false;
  }

  function shufflePhotos(list) {
    var copy = list.slice();
    var i;
    for (i = copy.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var tmp = copy[i];
      copy[i] = copy[j];
      copy[j] = tmp;
    }
    return copy;
  }

  function setPhotoPanel(on) {
    if (!photoPanel) return;
    var messagesOn = dashEl && dashEl.className.indexOf("has-messages") >= 0;
    var wantDash = on ? "dashboard has-photo" : "dashboard";
    if (messagesOn) wantDash += " has-messages";
    var layoutChanged = !dashEl || dashEl.className !== wantDash;
    var focused = photoPanel.className.indexOf("is-focused") >= 0;
    var cls = on ? "panel panel-photo is-on" : "panel panel-photo";
    if (on && focused) cls += " is-focused";
    photoPanel.className = cls;
    if (on && (focused || window.__tvKeys)) photoPanel.setAttribute("tabindex", "0");
    else photoPanel.removeAttribute("tabindex");
    photoPanel.setAttribute("aria-hidden", on ? "false" : "true");
    if (!on) closePhotoSettings();
    if (dashEl) dashEl.className = wantDash;
    if (layoutChanged) {
      if (lastCalendarSnapshot) renderCalendar(lastCalendarSnapshot);
      else fitCalendar();
    }
  }

  function stopPhotos() {
    if (photoTimer) {
      window.clearInterval(photoTimer);
      photoTimer = null;
    }
    photoUrls = [];
    photoKey = "";
    if (photoA) {
      photoA.setAttribute("data-want", "");
      photoA.className = "photo-img";
      photoA.removeAttribute("src");
    }
    if (photoB) {
      photoB.setAttribute("data-want", "");
      photoB.className = "photo-img";
      photoB.removeAttribute("src");
    }
    if (photoBgA) {
      photoBgA.className = "photo-blur";
      photoBgA.removeAttribute("src");
    }
    if (photoBgB) {
      photoBgB.className = "photo-blur";
      photoBgB.removeAttribute("src");
    }
    setPhotoPanel(false);
  }

  function showPhoto(img, url, attempts) {
    var bg = blurFor(img);
    img.onload = function () {
      if (img.getAttribute("data-want") !== url) return;
      var other = img === photoA ? photoB : photoA;
      var otherBg = blurFor(other);
      var cross = other && other.getAttribute("src") && other.className.indexOf("is-on") >= 0;
      if (cross) {
        img.className = "photo-img is-fade";
        if (bg) bg.className = "photo-blur is-fade";
        other.className = "photo-img is-fade is-on";
        if (otherBg) otherBg.className = "photo-blur is-fade is-on";
        var ignored = img.offsetWidth;
        img.className = "photo-img is-fade is-on";
        if (bg) bg.className = "photo-blur is-fade is-on";
        other.className = "photo-img is-fade";
        if (otherBg) otherBg.className = "photo-blur is-fade";
      } else {
        img.className = "photo-img is-on";
        if (bg) bg.className = "photo-blur is-on";
        if (other) other.className = "photo-img";
        if (otherBg) otherBg.className = "photo-blur";
      }
      setPhotoPanel(true);
      var at = photoUrls.indexOf(url);
      photoIndex = at >= 0 ? at : 0;
      photoShowingA = img === photoA;
    };
    img.onerror = function () {
      if (img.getAttribute("data-want") !== url) return;
      if (!photoUrls.length || attempts + 1 >= photoUrls.length) {
        if (!photoPanel || photoPanel.className.indexOf("is-on") < 0) stopPhotos();
        return;
      }
      var at = photoUrls.indexOf(url);
      var nextUrl = photoUrls[(at + 1) % photoUrls.length];
      showPhoto(img, nextUrl, attempts + 1);
    };
    img.setAttribute("data-want", url);
    if (bg) bg.src = url;
    img.src = url;
  }

  function advancePhoto() {
    if (photoUrls.length < 2 || !photoA || !photoB) return;
    var next = (photoIndex + 1) % photoUrls.length;
    var img = photoShowingA ? photoB : photoA;
    showPhoto(img, photoUrls[next], 0);
  }

  function applyPhotoPlaylist(payload) {
    if (!photoA || !photoB || !photoPanel) return;
    var list = [];
    var raw = payload && payload.photos;
    if (payload && payload.enabled && raw && raw.length) {
      var i;
      for (i = 0; i < raw.length; i++) {
        if (allowedPhotoUrl(raw[i])) list.push(raw[i]);
      }
    }
    if (payload && typeof payload.seconds === "number") applyPhotoInterval(payload.seconds);
    var key = list.slice().sort().join("\n");
    if (!list.length) {
      stopPhotos();
      return;
    }
    if (key === photoKey) return;
    var current = photoUrls.length ? photoUrls[photoIndex] : "";
    var keepCurrent = current && list.indexOf(current) >= 0 && photoPanel.className.indexOf("is-on") >= 0;
    photoKey = key;
    photoUrls = shufflePhotos(list);
    if (keepCurrent) {
      photoIndex = photoUrls.indexOf(current);
      if (photoIndex < 0) photoIndex = 0;
    } else {
      photoIndex = 0;
      photoShowingA = true;
      setPhotoPanel(true);
      showPhoto(photoA, photoUrls[0], 0);
    }
    if (photoTimer) window.clearInterval(photoTimer);
    photoTimer = null;
    if (photoUrls.length > 1) {
      photoTimer = window.setInterval(advancePhoto, PHOTO_MS);
    }
  }

  function pullPhotos() {
    if (!window.FamilyPhotos || typeof window.FamilyPhotos.getPlaylist !== "function") return;
    try {
      applyPhotoPlaylist(JSON.parse(window.FamilyPhotos.getPlaylist()));
    } catch (e) {}
  }

  window.onPhotoPlaylist = function (playlist) {
    applyPhotoPlaylist(playlist);
  };

  var messagesEl = document.getElementById("messages");
  var messageListEl = document.getElementById("message-list");
  var lastMessages = null;
  var MESSAGE_TTL = 24 * 60 * 60 * 1000;

  function asPlain(value, max) {
    var s = String(value == null ? "" : value);
    s = s.replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, " ").replace(/\s+/g, " ").trim();
    if (s.length > max) s = s.substring(0, max);
    return s;
  }

  function agoLabel(at) {
    var delta = Date.now() - at;
    if (!(delta >= 0)) return "just now";
    var min = Math.floor(delta / 60000);
    if (min < 1) return "just now";
    if (min < 60) return min === 1 ? "1 min ago" : (min + " min ago");
    var hr = Math.floor(min / 60);
    if (hr < 24) return hr === 1 ? "1 hour ago" : (hr + " hours ago");
    return "1 day ago";
  }

  function renderMessages(payload) {
    if (!messagesEl || !messageListEl) return;
    var pulse = !!(payload && payload.pulse);
    var raw = payload && payload.messages;
    lastMessages = {
      configured: !!(payload && payload.configured),
      pulse: false,
      messages: raw && raw.length ? raw : []
    };
    var visible = [];
    var i;
    for (i = 0; i < lastMessages.messages.length && visible.length < 3; i++) {
      var item = lastMessages.messages[i];
      if (!item) continue;
      var text = asPlain(item.text, 200);
      var sender = asPlain(item.sender, 40);
      var at = Number(item.at);
      if (!text || !isFinite(at)) continue;
      if (Date.now() - at >= MESSAGE_TTL) continue;
      visible.push({ sender: sender || "Family", text: text, at: at });
    }
    if (dashEl) {
      var photoOn = dashEl.className.indexOf("has-photo") >= 0;
      dashEl.className = photoOn ? "dashboard has-photo" : "dashboard";
      if (visible.length) dashEl.className += " has-messages";
    }
    if (!visible.length) {
      messagesEl.className = "panel panel-messages";
      messagesEl.setAttribute("aria-hidden", "true");
      clearNode(messageListEl);
      fitCalendar();
      return;
    }
    messagesEl.className = "panel panel-messages is-on";
    if (pulse) {
      void messagesEl.offsetWidth;
      messagesEl.className = "panel panel-messages is-on is-pulse";
    }
    messagesEl.setAttribute("aria-hidden", "false");
    clearNode(messageListEl);
    for (i = 0; i < visible.length; i++) {
      var note = visible[i];
      var block = el("p", "message message-line");
      block.appendChild(el("span", "message-who", note.sender + ", " + agoLabel(note.at) + ":"));
      block.appendChild(document.createTextNode(" " + note.text));
      messageListEl.appendChild(block);
    }
    fitCalendar();
  }

  function refreshMessages() {
    if (!lastMessages) return;
    renderMessages(lastMessages);
  }

  function pullMessages() {
    if (!window.FamilyMessages || typeof window.FamilyMessages.getMessages !== "function") return;
    try {
      renderMessages(JSON.parse(window.FamilyMessages.getMessages()));
    } catch (e) {}
  }

  window.onMessages = function (payload) {
    renderMessages(payload);
  };

  function start() {
    fitStage();
    updateClock();
    window.setInterval(updateClock, 1000);
    window.setTimeout(fitStage, 400);
    window.setTimeout(fitStage, 1500);
    window.onresize = fitStage;
    window.addEventListener("orientationchange", fitStage);
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) fetchWeather();
    });
    window.addEventListener("online", fetchWeather);
    fetchWeather();
    pullCalendar();
    pullPhotos();
    pullMessages();
    window.setInterval(refreshMessages, 30000);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
