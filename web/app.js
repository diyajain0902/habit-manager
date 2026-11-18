'use strict';

/*
 * Habit Manager dashboard. Plain JavaScript, no libraries.
 * The Java server computes every number (streaks, %, weekday rates); this file only
 * draws them. User text is always inserted with textContent / text nodes, never as HTML.
 */

// ------------------------------------------------------------------ constants & state

const PALETTE = [
  { soft: '#f7c5d5', deep: '#e48aaa', tint: '#fdeef3' }, // pink
  { soft: '#d9cdf7', deep: '#a58ce6', tint: '#f3effd' }, // lavender
  { soft: '#c3ead8', deep: '#63bf98', tint: '#ecf8f2' }, // mint
  { soft: '#ffdac4', deep: '#f2a273', tint: '#fff1e8' }, // peach
  { soft: '#c8e1f6', deep: '#78ade0', tint: '#edf5fc' }, // sky
  { soft: '#fbe8aa', deep: '#dbb53e', tint: '#fef8e3' }, // butter
];
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const WEEKDAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const LINE_COLOR = '#f1e9ef';
const SVG_NS = 'http://www.w3.org/2000/svg';

const ui = {
  tab: 'today',
  data: null,
  calendarHabit: null,
  calendarMonth: null,
  highlightHabit: null,
};

// ------------------------------------------------------------------ DOM helpers

function append(node, children) {
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/** Creates an HTML element. props: class, text, style, on<event>, or any attribute. */
function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  append(node, children);
  return node;
}

/** Creates an SVG element. */
function s(tag, attrs = {}, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value !== null && value !== undefined) node.setAttribute(key, value);
  }
  append(node, children);
  return node;
}

const tooltip = text => s('title', {}, text);

// ------------------------------------------------------------------ date helpers (local time, no UTC shifts)

const pad = n => String(n).padStart(2, '0');
const toISO = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const formatDate = (d, options) => d.toLocaleDateString('en-US', options);
const weekdayIndex = d => (d.getDay() + 6) % 7; // Monday = 0

function parseISO(text) {
  const [y, m, d] = text.split('-').map(Number);
  return new Date(y, m - 1, d);
}

// ------------------------------------------------------------------ data helpers

const habits = () => ui.data ? ui.data.habits : [];
const todayDate = () => parseISO(ui.data.today);
const findHabit = name => habits().find(habit => habit.name === name);

function colorOf(habit) {
  return PALETTE[habits().indexOf(habit) % PALETTE.length];
}

function accentStyle(color) {
  return `--accent-soft:${color.soft};--accent-deep:${color.deep};--accent-tint:${color.tint}`;
}

function truncate(text, max) {
  return text.length > max ? text.slice(0, max - 1) + '…' : text;
}

// ------------------------------------------------------------------ server calls

async function request(method, path, params) {
  const options = { method, headers: { 'X-Habit-Client': '1' } };
  if (params) {
    options.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    options.body = new URLSearchParams(params);
  }
  let response;
  try {
    response = await fetch(path, options);
  } catch (e) {
    throw new Error('Cannot reach the Habit Manager server. Is it still running?');
  }
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || 'Something went wrong.');
  for (const habit of body.habits) habit.done = new Set(habit.completedDates);
  ui.data = body;
  return body;
}

/** POST a change, re-render, and show a toast. */
async function act(path, params, successMessage) {
  try {
    await request('POST', path, params);
    render();
    if (successMessage) toast(successMessage);
  } catch (e) {
    toast(e.message, true);
  }
}

async function refresh() {
  try {
    await request('GET', '/api/state');
    render();
  } catch (e) {
    toast(e.message, true);
  }
}

// ------------------------------------------------------------------ toast & modal

let toastTimer;
function toast(message, isError = false) {
  const node = document.getElementById('toast');
  node.textContent = message;
  node.classList.toggle('error', isError);
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, isError ? 4500 : 2600);
}

/**
 * Small promise-free modal. onConfirm(value) may throw: the message is shown inside
 * the modal and it stays open so the user can fix their input.
 */
function openModal({ title, message, inputValue, placeholder, confirmLabel, danger, onConfirm }) {
  const backdrop = document.getElementById('modal');
  const close = () => {
    backdrop.hidden = true;
    backdrop.replaceChildren();
    document.removeEventListener('keydown', onKey);
  };
  const onKey = event => { if (event.key === 'Escape') close(); };

  const input = inputValue !== undefined
    ? h('input', { type: 'text', value: inputValue, placeholder, 'aria-label': title, maxlength: '60' })
    : null;
  const errorText = h('div', { class: 'error-text', role: 'alert' });

  const confirm = async () => {
    try {
      await onConfirm(input ? input.value : undefined);
      close();
    } catch (e) {
      errorText.textContent = e.message;
    }
  };
  if (input) input.addEventListener('keydown', e => { if (e.key === 'Enter') confirm(); });

  const dialog = h('div', { class: 'card modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('h2', { text: title }),
    message ? h('p', { text: message }) : null,
    input,
    errorText,
    h('div', { class: 'modal-actions' },
      h('button', { class: 'btn secondary', type: 'button', text: 'Cancel', onclick: close }),
      h('button', { class: danger ? 'btn danger' : 'btn', type: 'button', text: confirmLabel, onclick: confirm })));

  backdrop.replaceChildren(dialog);
  backdrop.onclick = event => { if (event.target === backdrop) close(); };
  backdrop.hidden = false;
  document.addEventListener('keydown', onKey);
  (input || dialog.querySelector('.btn:not(.secondary)')).focus();
  if (input) input.select();
}

// ------------------------------------------------------------------ rendering entry point

function render() {
  if (!ui.data) return;
  document.getElementById('today-label').textContent =
    formatDate(todayDate(), { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
  for (const tab of document.querySelectorAll('.tab')) {
    tab.setAttribute('aria-selected', String(tab.dataset.tab === ui.tab));
  }
  const root = document.getElementById('app');
  const views = { today: renderToday, calendar: renderCalendar, highlights: renderHighlights };
  root.replaceChildren(...views[ui.tab]());
}

function emptyState() {
  return h('div', { class: 'card empty' },
    h('div', { class: 'big', 'aria-hidden': 'true' }, '🌱'),
    h('h2', { text: 'No habits yet' }),
    h('p', { text: 'Plant your first habit above, or load some demo data to look around.' }),
    h('button', { class: 'btn', type: 'button', text: 'Load demo data', onclick: confirmDemo }));
}

// ------------------------------------------------------------------ Today tab

function renderToday() {
  return [
    summaryCards(),
    addHabitForm(),
    h('h2', { class: 'section-title' }, 'Your habits ',
      h('span', { class: 'hint', text: '· tap the circle to check in' })),
    habits().length ? h('div', { class: 'habit-list' }, habits().map(habitCard)) : emptyState(),
  ];
}

function greeting() {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good morning ☀️';
  if (hour < 18) return 'Good afternoon 🌤️';
  return 'Good evening 🌙';
}

function summaryCards() {
  const summary = ui.data.summary;
  const { doneToday, total } = summary;
  let mood = 'A fresh start awaits';
  if (total > 0 && doneToday === total) mood = 'All done, you are glowing ✨';
  else if (doneToday > 0) mood = 'Keep going, you have got this 💪';

  const best = habits().reduce((top, habit) => (!top || habit.currentStreak > top.currentStreak ? habit : top), null);
  const attention = summary.needingAttention;

  return h('div', {},
    h('h2', { class: 'section-title', text: greeting() }),
    h('div', { class: 'grid grid-3' },
      h('div', { class: 'card stat-card' },
        progressRing(doneToday, total),
        h('div', {},
          h('div', { class: 'label', text: 'Today' }),
          h('div', { class: 'value', text: `${doneToday} of ${total} done` }),
          h('div', { class: 'sub', text: mood }))),
      h('div', { class: 'card stat-card' },
        h('div', { class: 'stat-icon', style: 'background:var(--peach)', 'aria-hidden': 'true' }, '🔥'),
        h('div', {},
          h('div', { class: 'label', text: 'Hottest streak' }),
          h('div', { class: 'value', text: best ? `${best.currentStreak} day${best.currentStreak === 1 ? '' : 's'}` : '–' }),
          h('div', { class: 'sub', text: best && best.currentStreak > 0 ? best.name : 'Check in to start one' }))),
      h('div', { class: 'card stat-card' },
        h('div', { class: 'stat-icon', style: `background:${attention.length ? 'var(--pink)' : 'var(--mint)'}`, 'aria-hidden': 'true' },
          attention.length ? '🌧️' : '🌈'),
        h('div', {},
          h('div', { class: 'label', text: 'Needs some love' }),
          attention.length
            ? h('div', { class: 'chip-list' }, attention.map(name => h('span', { class: 'chip', text: name })))
            : h('div', { class: 'sub', text: 'Everything was done in the last 3 days' })))));
}

function progressRing(done, total) {
  const r = 22;
  const circumference = 2 * Math.PI * r;
  const fraction = total ? done / total : 0;
  return s('svg', { width: 56, height: 56, viewBox: '0 0 56 56', role: 'img', 'aria-label': `${done} of ${total} done today` },
    s('circle', { cx: 28, cy: 28, r, fill: 'none', stroke: 'var(--mint)', 'stroke-width': 7, opacity: 0.45 }),
    fraction > 0 ? s('circle', {
      cx: 28, cy: 28, r, fill: 'none', stroke: 'var(--mint-deep)', 'stroke-width': 7, 'stroke-linecap': 'round',
      'stroke-dasharray': `${circumference * fraction} ${circumference}`, transform: 'rotate(-90 28 28)',
    }) : null,
    s('text', { x: 28, y: 29, 'text-anchor': 'middle', 'dominant-baseline': 'middle', 'font-size': 13, 'font-weight': 800, fill: 'var(--ink)' },
      total ? `${Math.round(fraction * 100)}%` : '–'));
}

function addHabitForm() {
  const input = h('input', { type: 'text', placeholder: 'Add a new habit, e.g. "Stretch for 10 min"', 'aria-label': 'New habit name', maxlength: '60' });
  const submit = async event => {
    event.preventDefault();
    const name = input.value.trim();
    if (!name) { input.focus(); return; }
    try {
      await request('POST', '/api/habits/add', { name });
      render();
      toast(`"${name}" planted 🌱`);
    } catch (e) {
      toast(e.message, true);
    }
  };
  return h('form', { class: 'add-form', onsubmit: submit },
    input,
    h('button', { class: 'btn', type: 'submit', text: '+ Add habit' }));
}

function habitCard(habit) {
  const color = colorOf(habit);
  const toggleToday = () => habit.doneToday
    ? act('/api/undo', { name: habit.name }, `Undid today for "${habit.name}"`)
    : act('/api/checkin', { name: habit.name }, celebrate(habit));

  return h('article', { class: `card habit${habit.doneToday ? ' done' : ''}`, style: accentStyle(color) },
    h('button', {
      class: 'check', type: 'button', text: '✓',
      'aria-pressed': String(habit.doneToday),
      'aria-label': `${habit.doneToday ? 'Undo' : 'Mark'} "${habit.name}" ${habit.doneToday ? 'for today' : 'done today'}`,
      onclick: toggleToday,
    }),
    h('div', {},
      h('div', { class: 'habit-name', text: habit.name }),
      h('div', { class: 'habit-meta' },
        h('span', { text: `🔥 ${habit.currentStreak} day streak` }),
        h('span', { text: `🏆 best ${habit.longestStreak}` }),
        h('span', { text: `📈 ${habit.completion}%` }),
        habit.needsAttention ? h('span', { class: 'attention', text: 'missed 3 days' }) : null)),
    weekDots(habit),
    h('div', { class: 'icon-buttons' },
      h('button', { class: 'icon-button', type: 'button', title: 'Rename', 'aria-label': `Rename "${habit.name}"`, onclick: () => renameHabit(habit) }, '✏️'),
      h('button', { class: 'icon-button', type: 'button', title: 'Delete', 'aria-label': `Delete "${habit.name}"`, onclick: () => deleteHabit(habit) }, '🗑️')));
}

/** Picks a cheerful message for a check in, celebrating milestones and finishing the day. */
function celebrate(habit) {
  const next = habit.currentStreak + 1;
  const { doneToday, total } = ui.data.summary;
  if (doneToday + 1 === total && total > 1) return 'Every habit done today! 🎉';
  if ([7, 14, 21, 30, 50, 100].includes(next)) return `${next} day streak! You are on fire 🔥`;
  return `Nice! "${habit.name}" done 🌟`;
}

function weekDots(habit) {
  return h('div', { class: 'week', 'aria-label': 'Last 7 days' },
    habit.lastSevenDays.map(day => {
      const date = parseISO(day.date);
      const status = day.beforeCreation ? 'not tracked yet' : (day.done ? 'done' : 'missed');
      return h('div', { class: 'week-day', title: `${formatDate(date, { weekday: 'long', month: 'short', day: 'numeric' })}: ${status}` },
        h('div', { class: `dot${day.done ? ' done' : ''}${day.beforeCreation ? ' untracked' : ''}` }),
        WEEKDAY_SHORT[weekdayIndex(date)].charAt(0));
    }));
}

function renameHabit(habit) {
  openModal({
    title: 'Rename habit',
    inputValue: habit.name,
    confirmLabel: 'Save',
    onConfirm: async newName => {
      await request('POST', '/api/habits/rename', { name: habit.name, newName });
      render();
      toast('Renamed ✏️');
    },
  });
}

function deleteHabit(habit) {
  openModal({
    title: `Delete "${habit.name}"?`,
    message: 'This removes the habit and all of its history. It cannot be undone.',
    confirmLabel: 'Delete',
    danger: true,
    onConfirm: async () => {
      await request('POST', '/api/habits/delete', { name: habit.name });
      render();
      toast('Habit deleted');
    },
  });
}

function confirmDemo() {
  openModal({
    title: 'Load demo data?',
    message: 'This replaces ALL your current habits with 3 sample habits that have 30 days of history.',
    confirmLabel: 'Replace with demo data',
    danger: true,
    onConfirm: async () => {
      await request('POST', '/api/demo', {});
      ui.calendarHabit = ui.highlightHabit = null;
      render();
      toast('Demo data loaded ✨');
    },
  });
}

// ------------------------------------------------------------------ Calendar tab

function habitPills(selectedName, onSelect) {
  return h('div', { class: 'pills', role: 'group', 'aria-label': 'Choose a habit' },
    habits().map(habit => h('button', {
      class: 'pill', type: 'button', style: accentStyle(colorOf(habit)),
      'aria-pressed': String(habit.name === selectedName),
      onclick: () => onSelect(habit.name),
    }, h('span', { class: 'swatch', 'aria-hidden': 'true' }), habit.name)));
}

function renderCalendar() {
  if (!habits().length) return [h('h2', { class: 'section-title', text: 'Calendar' }), emptyState()];

  const habit = findHabit(ui.calendarHabit) || habits()[0];
  ui.calendarHabit = habit.name;
  const today = todayDate();
  if (!ui.calendarMonth) ui.calendarMonth = new Date(today.getFullYear(), today.getMonth(), 1);
  const month = ui.calendarMonth;
  const color = colorOf(habit);
  const created = parseISO(habit.createdDate);

  const isCurrentMonth = month.getFullYear() === today.getFullYear() && month.getMonth() === today.getMonth();
  const isCreationMonth = month.getFullYear() === created.getFullYear() && month.getMonth() === created.getMonth();
  const changeMonth = delta => {
    ui.calendarMonth = new Date(month.getFullYear(), month.getMonth() + delta, 1);
    render();
  };

  const cells = WEEKDAY_SHORT.map(day => h('div', { class: 'calendar-weekday', text: day }));
  for (let i = 0; i < weekdayIndex(month); i++) cells.push(h('div', { class: 'day blank', 'aria-hidden': 'true' }));

  let checkIns = 0;
  for (let d = new Date(month); d.getMonth() === month.getMonth(); d = addDays(d, 1)) {
    const key = toISO(d);
    const done = habit.done.has(key);
    if (done) checkIns++;
    const trackable = d <= today && d >= created;
    cells.push(h('button', {
      class: `day${done ? ' done' : ''}${key === ui.data.today ? ' today' : ''}`,
      type: 'button',
      disabled: !trackable,
      'aria-pressed': String(done),
      'aria-label': `${formatDate(d, { weekday: 'long', month: 'long', day: 'numeric' })}${done ? ', done' : ''}`,
      onclick: () => act('/api/toggle', { name: habit.name, date: key }, done ? `Unmarked ${formatDate(d, { month: 'short', day: 'numeric' })}` : `Marked ${formatDate(d, { month: 'short', day: 'numeric' })} done ✓`),
    }, String(d.getDate())));
  }

  return [
    h('h2', { class: 'section-title' }, 'Calendar ', h('span', { class: 'hint', text: '· tap a day to mark it done or undo it' })),
    habitPills(habit.name, name => { ui.calendarHabit = name; render(); }),
    h('div', { class: 'card', style: accentStyle(color) },
      h('div', { class: 'calendar-head' },
        h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Previous month', disabled: isCreationMonth, onclick: () => changeMonth(-1) }, '‹'),
        h('h3', { text: formatDate(month, { month: 'long', year: 'numeric' }) }),
        h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Next month', disabled: isCurrentMonth, onclick: () => changeMonth(1) }, '›')),
      h('div', { class: 'calendar-grid' }, cells),
      h('div', { class: 'calendar-foot',
        text: `${checkIns} check-in${checkIns === 1 ? '' : 's'} this month · tracking since ${formatDate(created, { month: 'long', day: 'numeric', year: 'numeric' })}` })),
  ];
}

// ------------------------------------------------------------------ Highlights tab (graphs)

function renderHighlights() {
  if (!habits().length) return [h('h2', { class: 'section-title', text: 'Highlights' }), emptyState()];

  const habit = findHabit(ui.highlightHabit) || habits()[0];
  ui.highlightHabit = habit.name;
  const today = todayDate();

  return [
    h('h2', { class: 'section-title', text: 'Highlights ✨' }),
    overviewFacts(today),
    h('div', { class: 'grid grid-2', style: 'margin-top:16px' },
      chartCard('Streaks', 'Current streak vs. personal best', streakChart(),
        legend([['Thick bar = current streak', '#b3a9bf'], ['Thin bar = best ever', '#e6e0ea']])),
      chartCard('Completion', 'Share of days done since each habit started', completionDonuts())),
    h('div', { style: 'margin-top:16px' },
      chartCard('Daily check-ins', 'Habits completed each day over the last 30 days', trendChart(today))),
    h('h2', { class: 'section-title' }, 'Habit deep dive ', h('span', { class: 'hint', text: '· pick a habit' })),
    habitPills(habit.name, name => { ui.highlightHabit = name; render(); }),
    h('div', { class: 'grid grid-2' },
      chartCard('Weekday rhythm', 'How often it gets done on each day of the week', weekdayChart(habit), weekdayFacts(habit)),
      chartCard('Activity', 'Recent weeks, one square per day', heatmap(habit, today),
        legend([['Done', colorOf(habit).deep], ['Missed', colorOf(habit).tint], ['Not tracked', '#ffffff']]))),
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

function overviewFacts(today) {
  const all = habits();
  const totalCheckIns = all.reduce((sum, habit) => sum + habit.completedDates.length, 0);
  const best = all.reduce((top, habit) => (habit.longestStreak > top.longestStreak ? habit : top), all[0]);

  // A "perfect day" is one where every habit that existed that day was done.
  let perfectDays = 0;
  for (let i = 0; i < 30; i++) {
    const key = toISO(addDays(today, -i));
    const active = all.filter(habit => habit.createdDate <= key);
    if (active.length && active.every(habit => habit.done.has(key))) perfectDays++;
  }

  const strongest = findHabit(ui.data.summary.strongest);
  const facts = [
    ['🌸', 'Total check-ins', String(totalCheckIns), 'across all habits'],
    ['🏆', 'Best streak ever', `${best.longestStreak} days`, best.name],
    ['💎', 'Most consistent', strongest ? `${strongest.completion}%` : '–', strongest ? strongest.name : ''],
    ['🌟', 'Perfect days', String(perfectDays), 'every habit done, last 30 days'],
  ];
  const tints = ['var(--pink)', 'var(--butter)', 'var(--sky)', 'var(--mint)'];
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
  const list = habits();
  const width = 400, labelWidth = 104, valueRoom = 52, rowHeight = 50, top = 4;
  const height = top + list.length * rowHeight;
  const max = Math.max(1, ...list.map(habit => habit.longestStreak));
  const barLength = value => Math.max(value > 0 ? 8 : 0, (width - labelWidth - valueRoom) * value / max);

  const rows = list.map((habit, i) => {
    const y = top + i * rowHeight;
    const color = colorOf(habit);
    const trackWidth = width - labelWidth - valueRoom;
    return s('g', {},
      tooltip(`${habit.name}: current ${habit.currentStreak} days, best ${habit.longestStreak} days`),
      s('text', { x: 0, y: y + 22, 'font-weight': 700 }, truncate(habit.name, 13)),
      s('rect', { x: labelWidth, y: y + 6, width: trackWidth, height: 16, rx: 8, fill: color.tint }),
      s('rect', { x: labelWidth, y: y + 6, width: barLength(habit.currentStreak), height: 16, rx: 8, fill: color.deep }),
      s('text', { x: labelWidth + trackWidth + 8, y: y + 19, class: 'value-label' }, `${habit.currentStreak}d`),
      s('rect', { x: labelWidth, y: y + 26, width: barLength(habit.longestStreak), height: 10, rx: 5, fill: color.soft }),
      s('text', { x: labelWidth + trackWidth + 8, y: y + 36, 'font-size': 11 }, `best ${habit.longestStreak}`));
  });
  return s('svg', { class: 'chart', viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': 'Streaks per habit' }, rows);
}

/** One donut per habit showing its completion percentage. */
function completionDonuts() {
  const r = 40, size = 104, circumference = 2 * Math.PI * r;
  return h('div', { class: 'donuts' }, habits().map(habit => {
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
    return h('div', { class: 'donut' }, ring, h('div', { class: 'name', text: habit.name }));
  }));
}

/** Area chart: number of habits done on each of the last 30 days. */
function trendChart(today) {
  const days = 30, width = 760, height = 220, left = 30, right = 14, top = 14, bottom = 30;
  const points = [];
  for (let i = days - 1; i >= 0; i--) {
    const date = addDays(today, -i);
    const key = toISO(date);
    const active = habits().filter(habit => habit.createdDate <= key);
    points.push({ date, done: active.filter(habit => habit.done.has(key)).length, active: active.length });
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
    cx: x(i), cy: y(p.done), r: p.active && p.done === p.active ? 5 : 3.5,
    fill: p.active && p.done === p.active ? '#e48aaa' : '#ffffff', stroke: '#a58ce6', 'stroke-width': 2,
  }, tooltip(`${formatDate(p.date, { weekday: 'short', month: 'short', day: 'numeric' })}: ${p.done} of ${p.active} done`)));

  return h('div', {},
    s('svg', { class: 'chart', viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': 'Daily check-ins over the last 30 days' },
      s('defs', {}, s('linearGradient', { id: 'trend-fill', x1: 0, y1: 0, x2: 0, y2: 1 },
        s('stop', { offset: '0%', 'stop-color': '#d9cdf7', 'stop-opacity': 0.9 }),
        s('stop', { offset: '100%', 'stop-color': '#f7c5d5', 'stop-opacity': 0.15 }))),
      grid,
      s('path', { d: area, fill: 'url(#trend-fill)' }),
      s('path', { d: line, fill: 'none', stroke: '#a58ce6', 'stroke-width': 2.5, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }),
      dots,
      xLabels),
    legend([['Habits done', '#a58ce6'], ['Perfect day (pink dot)', '#e48aaa']]));
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
    const x = side + i * slot + (slot - barWidth) / 2;
    const isBest = day === habit.bestWeekday;
    const parts = [s('rect', { x, y: top, width: barWidth, height: plotHeight, rx: 10, fill: color.tint })];
    if (rate === undefined) {
      parts.push(s('text', { x: x + barWidth / 2, y: top + plotHeight / 2, 'text-anchor': 'middle' }, '–'));
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
    const label = rate === undefined ? 'has not come round yet' : `${Math.round(rate * 100)}%`;
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
    h('div', { class: 'fact' }, '🔥 Streak: ', h('b', { text: `${habit.currentStreak} now · ${habit.longestStreak} best` })));
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
      const fill = done ? color.deep : (tracked ? color.tint : '#ffffff');
      const status = done ? 'done' : (tracked ? 'missed' : 'not tracked');
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

// ------------------------------------------------------------------ wiring

for (const tab of document.querySelectorAll('.tab')) {
  tab.addEventListener('click', () => { ui.tab = tab.dataset.tab; render(); });
}
document.getElementById('demo-button').addEventListener('click', confirmDemo);

// Re-fetch when the tab regains focus, so "today" rolls over after midnight.
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });

(async () => {
  await refresh();
  if (ui.data && ui.data.warnings.length) {
    toast(`Skipped ${ui.data.warnings.length} broken line(s) in the habits file`, true);
  }
})();
