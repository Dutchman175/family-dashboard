/* Bedtime countdown. No network. Edit the times here.
   Weekend nights are Friday and Saturday (no school the next day).
   Sunday through Thursday nights use the school-night time.
   Hours are 0-23 in America/Chicago. */
var BEDTIME_CONFIG = {
  zone: "America/Chicago",
  countdownMinutes: 360,
  soonMinutes: 15,
  holdMinutes: 60,
  kids: [
    { name: "Jack", school: [20, 0], weekend: [21, 0] },
    { name: "Girls", school: [19, 0], weekend: [20, 0] }
  ]
};

function bedtimeWeekday(year, month, day) {
  return new Date(Date.UTC(year, month - 1, day)).getUTCDay();
}

function bedtimeIsWeekendNight(year, month, day) {
  var day = bedtimeWeekday(year, month, day);
  return day === 5 || day === 6;
}

function bedtimeAddDays(year, month, day, count) {
  var utc = new Date(Date.UTC(year, month - 1, day + count));
  return {
    y: utc.getUTCFullYear(),
    m: utc.getUTCMonth() + 1,
    d: utc.getUTCDate()
  };
}

function bedtimeOffsetMinutes(zone, utcMillis) {
  var fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
  var parts = fmt.formatToParts(new Date(utcMillis));
  var map = {};
  var i;
  for (i = 0; i < parts.length; i++) map[parts[i].type] = parts[i].value;
  var hour = parseInt(map.hour, 10) % 24;
  var asUtc = Date.UTC(+map.year, +map.month - 1, +map.day, hour, +map.minute, +map.second);
  return Math.round((asUtc - utcMillis) / 60000);
}

function bedtimeZoneParts(zone, now) {
  var fmt = new Intl.DateTimeFormat("en-US", {
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
  var parts = fmt.formatToParts(now);
  var map = {};
  var i;
  for (i = 0; i < parts.length; i++) map[parts[i].type] = parts[i].value;
  return {
    y: parseInt(map.year, 10),
    m: parseInt(map.month, 10),
    d: parseInt(map.day, 10),
    hour: parseInt(map.hour, 10) % 24,
    minute: parseInt(map.minute, 10)
  };
}

function bedtimeInstant(zone, year, month, day, hour, minute) {
  var guess = Date.UTC(year, month - 1, day, hour, minute, 0);
  var offset = bedtimeOffsetMinutes(zone, guess);
  var instant = guess - offset * 60000;
  var offset2 = bedtimeOffsetMinutes(zone, instant);
  if (offset2 !== offset) instant = guess - offset2 * 60000;
  return instant;
}

function bedtimeClock(hour, minute) {
  var h = hour % 12;
  if (h === 0) h = 12;
  var mm = minute < 10 ? "0" + minute : String(minute);
  return h + ":" + mm + " " + (hour < 12 ? "AM" : "PM");
}

function bedtimePad(n) {
  return n < 10 ? "0" + n : String(n);
}

function bedtimeCountdown(totalSeconds) {
  var secs = totalSeconds;
  if (secs < 1) secs = 1;
  var h = Math.floor(secs / 3600);
  var m = Math.floor((secs % 3600) / 60);
  var s = secs % 60;
  return h + ":" + bedtimePad(m) + ":" + bedtimePad(s);
}

function bedtimeRows(now, config) {
  var cfg = config || BEDTIME_CONFIG;
  var zone = cfg.zone || "America/Chicago";
  var parts = bedtimeZoneParts(zone, now);
  var hold = (cfg.holdMinutes || 60) * 60000;
  var soon = cfg.soonMinutes || 15;
  var windowMin = cfg.countdownMinutes || 180;
  var kids = cfg.kids || [];
  var out = [];
  var i;
  for (i = 0; i < kids.length; i++) {
    var kid = kids[i];
    var todaySpec = bedtimeIsWeekendNight(parts.y, parts.m, parts.d) ? kid.weekend : kid.school;
    var todayAt = bedtimeInstant(zone, parts.y, parts.m, parts.d, todaySpec[0], todaySpec[1]);
    var spec = todaySpec;
    var at = todayAt;
    if (now.getTime() >= todayAt + hold) {
      var next = bedtimeAddDays(parts.y, parts.m, parts.d, 1);
      spec = bedtimeIsWeekendNight(next.y, next.m, next.d) ? kid.weekend : kid.school;
      at = bedtimeInstant(zone, next.y, next.m, next.d, spec[0], spec[1]);
    }
    var clock = bedtimeClock(spec[0], spec[1]);
    var remain = at - now.getTime();
    var phase = "later";
    var line = "Bedtime " + clock;
    var time = "";
    if (remain <= 0) {
      phase = "now";
      line = "Bedtime! \uD83C\uDF19";
      time = "";
    } else {
      var secs = Math.ceil(remain / 1000);
      if (secs <= soon * 60) phase = "soon";
      else if (secs <= windowMin * 60) phase = "count";
      if (phase === "later") {
        line = "Bedtime " + clock;
        time = "";
      } else {
        line = bedtimeCountdown(secs);
        time = clock;
      }
    }
    out.push({ name: kid.name, phase: phase, line: line, time: time });
  }
  return out;
}
