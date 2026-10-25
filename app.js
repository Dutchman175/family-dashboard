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

  function renderCalendar(snapshot) {
    if (!calendarEl || !calendarListEl || !calendarNoteEl) return;
    if (!snapshot || !snapshot.configured) {
      calendarEl.className = "panel panel-calendar";
      clearNode(calendarListEl);
      calendarNoteEl.className = "calendar-note";
      calendarNoteEl.textContent = "";
      return;
    }
    calendarEl.className = "panel panel-calendar is-on";
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
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
