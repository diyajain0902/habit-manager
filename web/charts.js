'use strict';

/*
 * Highlights tab: every graph is drawn by hand as SVG (no chart library).
 * Uses the helpers defined in app.js (h, s, tooltip, colorOf, date helpers...),
 * which are available by the time anything here is called.
 */

function renderHighlights() {
  const list = activeHabits();
  if (!list.length) return [h('h2', { class: 'section-title', text: 'Highlights' }), emptyState()];

  const habit = list.find(x => x.name === ui.highlightHabit) || list[0];
  ui.highlightHabit = habit.name;
  const today = todayDate();
  const legendItems = [['Done', colorOf(habit).deep]];
  if (habit.target > 1) legendItems.push(['Part done', colorOf(habit).soft]);
  legendItems.push(['Missed', colorOf(habit).tint], ['Rest day / not tracked', 'var(--card)']);

  return [
    h('h2', { class: 'section-title', text: 'Highlights ✨' }),
    overviewFacts(today),
    h('div', { class: 'grid grid-2', style: 'margin-top:16px' },
      chartCard('Streaks', 'Current streak vs. personal best', streakChart(),
        legend([['Thick bar = current streak', 'var(--muted)'], ['Thin bar = best ever', 'var(--line)']])),
      chartCard('Completion', 'Share of expected check-ins done since each habit started', completionDonuts())),
    h('div', { style: 'margin-top:16px' },
      chartCard('Daily check-ins', 'Habits completed each day over the last 30 days', trendChart(today))),
    h('div', { style: 'margin-top:16px' },
      chartCard('Badges 🏅', 'Earned for streaks (your best ever counts) and for total check-ins. The faded one is next.', badgeGrid())),
    h('h2', { class: 'section-title' }, 'Habit deep dive ', h('span', { class: 'hint', text: '· pick a habit' })),
    habitPills(list, habit.name, name => { ui.highlightHabit = name; render(); }),
    h('div', { class: 'grid grid-2' },
      chartCard('Weekday rhythm', 'How often it gets done on each scheduled day of the week', weekdayChart(habit), weekdayFacts(habit)),
      chartCard('Activity', 'Recent weeks, one square per day', heatmap(habit, today), legend(legendItems))),
  ];
}

function chartCard(title, caption, chart, footer) {
  return h('section', { class: 'card chart-card' },
    h('h3', { text: title }),
    h('p', { class: 'caption', text: caption }),
    chart,
    footer || null);
}

function legend(items) {
  return h('div', { class: 'legend' },
    items.map(([label, color]) => h('span', {},
      h('i', { style: `background:${color};border:1px solid ${LINE_COLOR}` }), label)));
}

/** Earned badges as medals, plus each habit's next badge faded with a progress bar. */
function badgeGrid() {
  const badges = [...ui.data.totalBadges, ...activeHabits().flatMap(habit => habit.badges)];
  return h('div', { class: 'badge-grid' }, badges.map(badge => {
    const locked = !badge.earned;
    const percent = Math.round(Math.min(1, badge.progress / badge.goal) * 100);
    return h('div', {
      class: `badge${locked ? ' locked' : ''}`, style: badgeAccent(badge),
      title: locked ? `${badge.progress} of ${badge.goal} so far` : 'Earned!',
    },
    h('div', { class: 'medal', 'aria-hidden': 'true' }, badgeIcon(badge)),
    h('div', { class: 'badge-label', text: badge.label }),
    h('div', { class: 'badge-habit', text: badge.habit ? displayName(findHabit(badge.habit) || { name: badge.habit }) : 'All habits' }),
    locked
      ? h('div', { class: 'badge-progress', role: 'img', 'aria-label': `${badge.progress} of ${badge.goal}` },
        h('span', { style: `width:${percent}%` }))
      : null);
  }));
}

/** Habits that should have been done on the date (weekly habits never block a "perfect day"). */
function requiredOn(list, key, date) {
  return list.filter(habit => habit.createdDate <= key
    && habit.schedule.type !== 'WEEKLY' && scheduledOn(habit, date));
}

function overviewFacts(today) {
  const all = activeHabits();
  const totalCheckIns = habits().reduce((sum, habit) => sum + habit.completedDates.length, 0);
  const best = all.reduce((top, habit) => (habit.longestStreak > top.longestStreak ? habit : top), all[0]);

  // A "perfect day" is one where every habit scheduled that day was done.
  let perfectDays = 0;
  for (let i = 0; i < 30; i++) {
    const date = addDays(today, -i);
    const key = toISO(date);
    const required = requiredOn(all, key, date);
    if (required.length && required.every(habit => habit.done.has(key))) perfectDays++;
  }

  const strongest = findHabit(ui.data.summary.strongest);
  const facts = [
    ['💙', 'Total check-ins', String(totalCheckIns), 'across all habits'],
    ['🏆', 'Best streak ever', unitWord(best, best.longestStreak), best.name],
    ['💎', 'Most consistent', strongest ? `${strongest.completion}%` : '–', strongest ? strongest.name : ''],
    ['🌟', 'Perfect days', String(perfectDays), 'every scheduled habit done, last 30 days'],
  ];
  const tints = ['var(--lavender)', 'var(--periwinkle)', 'var(--sky)', 'var(--aqua)'];
  return h('div', { class: 'grid grid-4' }, facts.map(([icon, label, value, sub], i) =>
    h('div', { class: 'card stat-card' },
      h('div', { class: 'stat-icon', style: `background:${tints[i]}`, 'aria-hidden': 'true' }, icon),
      h('div', { style: 'min-width:0' },
        h('div', { class: 'label', text: label }),
        h('div', { class: 'value', text: value }),
        h('div', { class: 'sub ellipsis', text: sub, title: sub })))));
}

/** Horizontal bars: current streak (solid) above best-ever streak (soft) for each habit. */
function streakChart() {
  const list = activeHabits();
  const width = 400, labelWidth = 104, valueRoom = 52, rowHeight = 50, top = 4;
  const height = top + list.length * rowHeight;
  const max = Math.max(1, ...list.map(habit => habit.longestStreak));
  const barLength = value => Math.max(value > 0 ? 8 : 0, (width - labelWidth - valueRoom) * value / max);

  const rows = list.map((habit, i) => {
    const y = top + i * rowHeight;
    const color = colorOf(habit);
    const trackWidth = width - labelWidth - valueRoom;
    const letter = habit.streakUnit === 'week' ? 'w' : 'd';
    return s('g', {},
      tooltip(`${habit.name}: current ${unitWord(habit, habit.currentStreak)}, best ${unitWord(habit, habit.longestStreak)}`),
      s('text', { x: 0, y: y + 22, 'font-weight': 700 }, truncate(habit.name, 13)),
      s('rect', { x: labelWidth, y: y + 6, width: trackWidth, height: 16, rx: 8, fill: color.tint }),
      s('rect', { x: labelWidth, y: y + 6, width: barLength(habit.currentStreak), height: 16, rx: 8, fill: color.deep }),
      s('text', { x: labelWidth + trackWidth + 8, y: y + 19, class: 'value-label' }, `${habit.currentStreak}${letter}`),
      s('rect', { x: labelWidth, y: y + 26, width: barLength(habit.longestStreak), height: 10, rx: 5, fill: color.soft }),
      s('text', { x: labelWidth + trackWidth + 8, y: y + 36, 'font-size': 11 }, `best ${habit.longestStreak}`));
  });
  return s('svg', { class: 'chart', viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': 'Streaks per habit' }, rows);
}

/** One donut per habit showing its completion percentage. */
function completionDonuts() {
  const r = 40, size = 104, circumference = 2 * Math.PI * r;
  return h('div', { class: 'donuts' }, activeHabits().map(habit => {
    const color = colorOf(habit);
    const ring = s('svg', { width: size, height: size, viewBox: `0 0 ${size} ${size}`, role: 'img', 'aria-label': `${habit.name}: ${habit.completion}%` },
      s('circle', { cx: size / 2, cy: size / 2, r, fill: 'none', stroke: color.tint, 'stroke-width': 12 }),
      habit.completion > 0 ? s('circle', {
        cx: size / 2, cy: size / 2, r, fill: 'none', stroke: color.deep, 'stroke-width': 12, 'stroke-linecap': 'round',
        'stroke-dasharray': `${circumference * habit.completion / 100} ${circumference}`,
        transform: `rotate(-90 ${size / 2} ${size / 2})`,
      }) : null,
      s('text', { x: size / 2, y: size / 2 + 1, 'text-anchor': 'middle', 'dominant-baseline': 'middle', 'font-size': 17, 'font-weight': 800, fill: 'var(--ink)' },
        `${Math.round(habit.completion)}%`));
    return h('div', { class: 'donut' }, ring, h('div', { class: 'name', text: displayName(habit) }));
  }));
}

/** Area chart: number of habits done on each of the last 30 days. */
function trendChart(today) {
  const days = 30, width = 760, height = 220, left = 30, right = 14, top = 14, bottom = 30;
  const list = activeHabits();
  const points = [];
  for (let i = days - 1; i >= 0; i--) {
    const date = addDays(today, -i);
    const key = toISO(date);
    const existing = list.filter(habit => habit.createdDate <= key);
    const required = requiredOn(list, key, date);
    points.push({
      date,
      done: existing.filter(habit => habit.done.has(key)).length,
      active: existing.length,
      perfect: required.length > 0 && required.every(habit => habit.done.has(key)),
    });
  }

  const maxY = Math.max(1, ...points.map(p => p.active));
  const step = maxY <= 6 ? 1 : Math.ceil(maxY / 5);
  const x = i => left + i * (width - left - right) / (days - 1);
  const y = v => top + (height - top - bottom) * (1 - v / maxY);

  const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.done).toFixed(1)}`).join(' ');
  const area = `${line} L${x(days - 1)},${y(0)} L${x(0)},${y(0)} Z`;

  const grid = [];
  for (let v = 0; v <= maxY; v += step) {
    grid.push(s('line', { x1: left, x2: width - right, y1: y(v), y2: y(v), class: 'gridline' }));
    grid.push(s('text', { x: left - 10, y: y(v) + 4, 'text-anchor': 'end' }, String(v)));
  }
  const xLabels = points.map((p, i) => ((days - 1 - i) % 7 === 0
    ? s('text', { x: x(i), y: height - 8, 'text-anchor': 'middle' }, i === days - 1 ? 'Today' : formatDate(p.date, { month: 'short', day: 'numeric' }))
    : null));
  const dots = points.map((p, i) => s('circle', {
    cx: x(i), cy: y(p.done), r: p.perfect ? 5 : 3.5,
    fill: p.perfect ? '#57a5db' : 'var(--card)', stroke: '#9f82e3', 'stroke-width': 2,
  }, tooltip(`${formatDate(p.date, { weekday: 'short', month: 'short', day: 'numeric' })}: ${p.done} of ${p.active} done${p.perfect ? ' (perfect day)' : ''}`)));

  return h('div', {},
    s('svg', { class: 'chart', viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': 'Daily check-ins over the last 30 days' },
      s('defs', {}, s('linearGradient', { id: 'trend-fill', x1: 0, y1: 0, x2: 0, y2: 1 },
        s('stop', { offset: '0%', 'stop-color': '#9f82e3', 'stop-opacity': 0.45 }),
        s('stop', { offset: '100%', 'stop-color': '#57a5db', 'stop-opacity': 0.04 }))),
      grid,
      s('path', { d: area, fill: 'url(#trend-fill)' }),
      s('path', { d: line, fill: 'none', stroke: '#9f82e3', 'stroke-width': 2.5, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }),
      dots,
      xLabels),
    legend([['Habits done', '#9f82e3'], ['Perfect day (blue dot)', '#57a5db']]));
}

/** Vertical bars Mon–Sun with the weekday completion rate. Best day is highlighted. */
function weekdayChart(habit) {
  const color = colorOf(habit);
  const width = 420, height = 210, top = 24, bottom = 26, side = 6;
  const slot = (width - side * 2) / 7;
  const barWidth = slot * 0.58;
  const plotHeight = height - top - bottom;

  const bars = WEEKDAYS.map((day, i) => {
    const rate = habit.weekdayRates[day];
    const isRestDay = !habit.schedule.days.includes(DAY_CODES[i]);
    const x = side + i * slot + (slot - barWidth) / 2;
    const isBest = day === habit.bestWeekday;
    const parts = [s('rect', { x, y: top, width: barWidth, height: plotHeight, rx: 10, fill: isRestDay ? 'var(--card)' : color.tint, stroke: isRestDay ? LINE_COLOR : 'none' })];
    if (rate === undefined) {
      parts.push(s('text', { x: x + barWidth / 2, y: top + plotHeight / 2, 'text-anchor': 'middle', 'font-size': isRestDay ? 10 : 12 },
        isRestDay ? 'rest' : '–'));
    } else {
      const barHeight = plotHeight * rate;
      if (barHeight > 0) {
        parts.push(s('rect', {
          x, y: top + plotHeight - barHeight, width: barWidth, height: barHeight,
          rx: Math.min(10, barHeight / 2), fill: isBest ? color.deep : color.soft,
        }));
      }
      parts.push(s('text', { x: x + barWidth / 2, y: top + plotHeight - barHeight - 6, 'text-anchor': 'middle', class: 'value-label', 'font-size': 11 },
        `${Math.round(rate * 100)}%`));
    }
    parts.push(s('text', { x: x + barWidth / 2, y: height - 6, 'text-anchor': 'middle', 'font-weight': isBest ? 800 : 400 },
      WEEKDAY_SHORT[i]));
    let label = rate === undefined ? 'has not come round yet' : `${Math.round(rate * 100)}%`;
    if (isRestDay) label = 'rest day';
    return s('g', {}, tooltip(`${WEEKDAY_SHORT[i]}: ${label}`), parts);
  });
  return s('svg', { class: 'chart', viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': `Weekday completion rates for ${habit.name}` }, bars);
}

function weekdayFacts(habit) {
  const name = day => (day ? day.charAt(0) + day.slice(1).toLowerCase() : '–');
  const rate = day => (day ? ` (${Math.round(habit.weekdayRates[day] * 100)}%)` : '');
  return h('div', { class: 'highlight-facts' },
    h('div', { class: 'fact' }, '✨ Best day: ', h('b', { text: name(habit.bestWeekday) + rate(habit.bestWeekday) })),
    h('div', { class: 'fact' }, '🌧️ Toughest day: ', h('b', { text: name(habit.worstWeekday) + rate(habit.worstWeekday) })),
    h('div', { class: 'fact' }, '🔥 Streak: ', h('b', { text: `${unitWord(habit, habit.currentStreak)} now · ${habit.longestStreak} best` })),
    h('div', { class: 'fact' }, '📅 ', h('b', { text: habit.schedule.label })));
}

/** GitHub-style grid: columns are weeks (Mon at the top), one square per day. */
function heatmap(habit, today) {
  // Show the habit's whole life, but at least 10 and at most 18 weeks.
  const weeksAlive = Math.ceil((today - parseISO(habit.createdDate)) / (7 * 86400000)) + 1;
  const weeks = Math.min(18, Math.max(10, weeksAlive));
  const cell = 15, gap = 4, left = 32, top = 20;
  const color = colorOf(habit);
  const start = addDays(today, -weekdayIndex(today) - (weeks - 1) * 7);
  const width = left + weeks * (cell + gap);
  const height = top + 7 * (cell + gap);
  const parts = [];

  ['Mon', 'Wed', 'Fri'].forEach((label, i) =>
    parts.push(s('text', { x: 0, y: top + i * 2 * (cell + gap) + cell - 3, 'font-size': 10 }, label)));

  let previousMonth = -1;
  for (let w = 0; w < weeks; w++) {
    const columnStart = addDays(start, w * 7);
    if (columnStart.getMonth() !== previousMonth) {
      parts.push(s('text', { x: left + w * (cell + gap), y: 12, 'font-size': 10 }, formatDate(columnStart, { month: 'short' })));
      previousMonth = columnStart.getMonth();
    }
    for (let r = 0; r < 7; r++) {
      const date = addDays(columnStart, r);
      if (date > today) continue;
      const key = toISO(date);
      const tracked = key >= habit.createdDate;
      const done = habit.done.has(key);
      const count = countOn(habit, key);
      const rest = !scheduledOn(habit, date);
      let fill = 'var(--card)';
      let status = 'not tracked';
      if (done) { fill = color.deep; status = 'done'; }
      else if (tracked && count > 0) { fill = color.soft; status = `${count} of ${habit.target}`; }
      else if (tracked && rest) { status = 'rest day'; }
      else if (tracked) { fill = color.tint; status = 'missed'; }
      parts.push(s('rect', {
        x: left + w * (cell + gap), y: top + r * (cell + gap), width: cell, height: cell, rx: 4,
        fill, stroke: done ? 'none' : LINE_COLOR,
      }, tooltip(`${formatDate(date, { weekday: 'short', month: 'short', day: 'numeric' })}: ${status}`)));
    }
  }
  // Cap the size so squares and labels stay small on wide cards.
  return s('svg', {
    class: 'chart', viewBox: `0 0 ${width} ${height}`, style: `max-width:${Math.round(width * 1.3)}px`,
    role: 'img', 'aria-label': `Activity heatmap for ${habit.name}`,
  }, parts);
}
