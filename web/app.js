'use strict';

/*
 * Habit Manager dashboard. Plain JavaScript, no libraries.
 * The Java server computes every number (streaks, %, weekday rates); this file only
 * draws them. User text is always inserted with textContent / text nodes, never as HTML.
 * The Highlights graphs live in charts.js.
 */

// ------------------------------------------------------------------ constants & state

const PALETTE = [
  { soft: '#cdd4f8', deep: '#7c88e2', tint: '#eef0fd' }, // periwinkle
  { soft: '#dccff8', deep: '#9f82e3', tint: '#f4f0fd' }, // lavender
  { soft: '#c6e3f8', deep: '#57a5db', tint: '#edf6fd' }, // sky
  { soft: '#ecd3f5', deep: '#be7fd6', tint: '#faf1fc' }, // lilac
  { soft: '#c6eaf0', deep: '#4aafc2', tint: '#ecf8fa' }, // aqua
  { soft: '#c2d3f6', deep: '#5a84d3', tint: '#edf2fd' }, // cornflower
];
const PALETTE_NAMES = ['Periwinkle', 'Lavender', 'Sky', 'Lilac', 'Aqua', 'Cornflower'];
const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const WEEKDAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DAY_CODES = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
const EMOJI_CHOICES = ['💧', '🏃', '📚', '🧘', '💪', '🥗', '😴', '🧠', '✍️', '🎵', '🧹', '💊', '🚶', '🦷', '☀️', '🫧'];
const LINE_COLOR = '#e7e9f6';
const SVG_NS = 'http://www.w3.org/2000/svg';
const NOTICE_POLL_MS = 20000;

const ui = {
  tab: 'today',
  data: null,
  settings: null,
  calendarHabit: null,
  calendarMonth: null,
  calendarDay: null,
  highlightHabit: null,
  archivedOpen: false,
  dragName: null,
  focusHandle: null,
  lastNoticeId: null,
};

// ------------------------------------------------------------------ DOM helpers

function append(node, children) {
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/** Creates an HTML element. props: class, text, on<event>, or any attribute. */
function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'value') node.value = value;
    else if (key === 'checked') node.checked = Boolean(value);
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

/** "07:30" -> "7:30 AM" */
function formatTime(hhmm) {
  const [hours, minutes] = hhmm.split(':').map(Number);
  return new Date(2000, 0, 1, hours, minutes).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
}

// ------------------------------------------------------------------ data helpers

const habits = () => (ui.data ? ui.data.habits : []);
const activeHabits = () => habits().filter(habit => !habit.archived);
const archivedHabits = () => habits().filter(habit => habit.archived);
const todayDate = () => parseISO(ui.data.today);
const findHabit = name => habits().find(habit => habit.name === name);

/** The habit's chosen colour, or one picked from its position in the list. */
function colorOf(habit) {
  const index = habit.color !== null && habit.color !== undefined ? habit.color : habits().indexOf(habit);
  return PALETTE[((index % PALETTE.length) + PALETTE.length) % PALETTE.length];
}

function accentStyle(color) {
  return `--accent-soft:${color.soft};--accent-deep:${color.deep};--accent-tint:${color.tint}`;
}

function truncate(text, max) {
  return text.length > max ? text.slice(0, max - 1) + '…' : text;
}

const displayName = habit => (habit.emoji ? `${habit.emoji} ${habit.name}` : habit.name);
const scheduledOn = (habit, date) => habit.schedule.days.includes(DAY_CODES[weekdayIndex(date)]);
const countOn = (habit, key) => (habit.checkInMap.get(key) || { count: 0 }).count;
const noteOn = (habit, key) => (habit.checkInMap.get(key) || { note: null }).note;

/** "1 day", "4 days", "2 weeks" */
function unitWord(habit, n) {
  return `${n} ${habit.streakUnit}${n === 1 ? '' : 's'}`;
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
  return body;
}

/** Stores a full state response, adding quick lookups to each habit. */
function setState(body) {
  for (const habit of body.habits) {
    habit.done = new Set(habit.completedDates);
    habit.checkInMap = new Map(habit.checkIns.map(c => [c.date, c]));
  }
  ui.data = body;
}

/** POST a change, re-render, and show a toast. */
async function act(path, params, successMessage) {
  try {
    setState(await request('POST', path, params));
    render();
    if (successMessage) toast(successMessage);
  } catch (e) {
    toast(e.message, true);
  }
}

async function refresh() {
  try {
    setState(await request('GET', '/api/state'));
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
 * A small dialog. Pass inputValue for a single text field (multiline for a textarea), or
 * body for custom content. onConfirm(value) may throw: the message is shown inside the
 * dialog and it stays open so the user can fix their input. extraActions are shown on
 * the left, e.g. Archive / Delete.
 */
function openModal({ title, message, body, inputValue, multiline, placeholder, confirmLabel, danger, onConfirm, extraActions = [] }) {
  const backdrop = document.getElementById('modal');
  const close = () => {
    backdrop.hidden = true;
    backdrop.replaceChildren();
    document.removeEventListener('keydown', onKey);
  };
  const onKey = event => { if (event.key === 'Escape') close(); };

  let input = null;
  if (inputValue !== undefined) {
    input = multiline
      ? h('textarea', { rows: '4', placeholder, 'aria-label': title, maxlength: '280' })
      : h('input', { type: 'text', placeholder, 'aria-label': title, maxlength: '60' });
    input.value = inputValue;
  }
  const errorText = h('div', { class: 'error-text', role: 'alert' });

  const confirm = async () => {
    try {
      await onConfirm(input ? input.value : undefined);
      close();
    } catch (e) {
      errorText.textContent = e.message;
    }
  };
  if (input && !multiline) input.addEventListener('keydown', e => { if (e.key === 'Enter') confirm(); });

  const dialog = h('div', { class: `card modal${body ? ' wide' : ''}`, role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('h2', { text: title }),
    message ? h('p', { text: message }) : null,
    body || null,
    input,
    errorText,
    h('div', { class: 'modal-actions' },
      h('div', { class: 'left-actions' }, extraActions.map(action =>
        h('button', { class: action.danger ? 'btn danger small' : 'btn secondary small', type: 'button', text: action.label,
          onclick: () => action.onClick(close) }))),
      h('button', { class: 'btn secondary', type: 'button', text: 'Cancel', onclick: close }),
      h('button', { class: danger ? 'btn danger' : 'btn', type: 'button', text: confirmLabel, onclick: confirm })));

  backdrop.replaceChildren(dialog);
  backdrop.onclick = event => { if (event.target === backdrop) close(); };
  backdrop.hidden = false;
  document.addEventListener('keydown', onKey);
  const focusTarget = input || dialog.querySelector('input, button.btn:not(.secondary)');
  focusTarget.focus();
  if (input && !multiline) input.select();
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
  const views = { today: renderToday, calendar: renderCalendar, highlights: renderHighlights, settings: renderSettings };
  root.replaceChildren(...views[ui.tab]().filter(Boolean));

  if (ui.focusHandle) {
    const handle = [...document.querySelectorAll('.drag-handle')].find(el => el.dataset.name === ui.focusHandle);
    if (handle) handle.focus();
    ui.focusHandle = null;
  }
}

function emptyState() {
  return h('div', { class: 'card empty' },
    h('img', { class: 'big', src: 'bubble.svg', alt: '' }),
    h('h2', { text: 'No habits yet' }),
    h('p', { text: 'Add your first habit above, or load some demo data to look around.' }),
    h('button', { class: 'btn', type: 'button', text: 'Load demo data', onclick: confirmDemo }));
}

// ------------------------------------------------------------------ Today tab

function renderToday() {
  const list = activeHabits();
  return [
    summaryCards(),
    addHabitForm(),
    h('h2', { class: 'section-title' }, 'Your habits ',
      h('span', { class: 'hint', text: '· tap the circle to check in, drag ⠿ to reorder' })),
    list.length ? h('div', { class: 'habit-list' }, list.map(habitCard)) : emptyState(),
    archivedSection(),
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
  if (total === 0 && activeHabits().length) mood = 'Nothing due today. Enjoy the rest 💤';
  else if (total > 0 && doneToday === total) mood = 'All done, you are glowing ✨';
  else if (doneToday > 0) mood = 'Keep going, you have got this 💪';

  const best = activeHabits().reduce((top, habit) => (!top || habit.currentStreak > top.currentStreak ? habit : top), null);
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
        h('div', { class: 'stat-icon', style: 'background:var(--lilac)', 'aria-hidden': 'true' }, '🔥'),
        h('div', {},
          h('div', { class: 'label', text: 'Hottest streak' }),
          h('div', { class: 'value', text: best ? unitWord(best, best.currentStreak) : '–' }),
          h('div', { class: 'sub', text: best && best.currentStreak > 0 ? best.name : 'Check in to start one' }))),
      h('div', { class: 'card stat-card' },
        h('div', { class: 'stat-icon', style: `background:${attention.length ? 'var(--lilac)' : 'var(--aqua)'}`, 'aria-hidden': 'true' },
          attention.length ? '🌧️' : '🌈'),
        h('div', {},
          h('div', { class: 'label', text: 'Needs some love' }),
          attention.length
            ? h('div', { class: 'chip-list' }, attention.map(name => h('span', { class: 'chip', text: name })))
            : h('div', { class: 'sub', text: 'Nothing has been missed lately' })))));
}

function progressRing(done, total) {
  const r = 22;
  const circumference = 2 * Math.PI * r;
  const fraction = total ? done / total : 0;
  return s('svg', { width: 56, height: 56, viewBox: '0 0 56 56', role: 'img', 'aria-label': `${done} of ${total} done today` },
    s('circle', { cx: 28, cy: 28, r, fill: 'none', stroke: 'var(--periwinkle)', 'stroke-width': 7, opacity: 0.45 }),
    fraction > 0 ? s('circle', {
      cx: 28, cy: 28, r, fill: 'none', stroke: 'var(--periwinkle-deep)', 'stroke-width': 7, 'stroke-linecap': 'round',
      'stroke-dasharray': `${circumference * fraction} ${circumference}`, transform: 'rotate(-90 28 28)',
    }) : null,
    s('text', { x: 28, y: 29, 'text-anchor': 'middle', 'dominant-baseline': 'middle', 'font-size': 13, 'font-weight': 800, fill: 'var(--ink)' },
      total ? `${Math.round(fraction * 100)}%` : '–'));
}

function addHabitForm() {
  const input = h('input', { type: 'text', placeholder: 'Add a new habit, e.g. "Stretch for 10 min"', 'aria-label': 'New habit name', maxlength: '60' });
  const submit = event => {
    event.preventDefault();
    openHabitDialog(null, input.value.trim());
  };
  return h('form', { class: 'add-form', onsubmit: submit },
    input,
    h('button', { class: 'btn', type: 'submit', text: '+ Add habit' }));
}

function habitCard(habit) {
  const color = colorOf(habit);
  const resting = !habit.dueToday && !habit.doneToday;
  let card;

  const handle = h('span', {
    class: 'drag-handle', role: 'button', tabindex: '0', draggable: 'true', 'data-name': habit.name,
    title: 'Drag to reorder (or use the arrow keys)', 'aria-label': `Reorder "${habit.name}". Use the up and down arrow keys.`,
    ondragstart: event => {
      ui.dragName = habit.name;
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', habit.name);
      card.classList.add('dragging');
    },
    ondragend: () => {
      ui.dragName = null;
      document.querySelectorAll('.drop-target, .dragging').forEach(el => el.classList.remove('drop-target', 'dragging'));
    },
    onkeydown: event => {
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
        event.preventDefault();
        moveBy(habit, event.key === 'ArrowUp' ? -1 : 1);
      }
    },
  }, '⠿');

  card = h('article', {
    class: `card habit${habit.doneToday ? ' done' : ''}${resting ? ' resting' : ''}`,
    style: accentStyle(color),
    ondragover: event => {
      if (ui.dragName && ui.dragName !== habit.name) {
        event.preventDefault();
        card.classList.add('drop-target');
      }
    },
    ondragleave: () => card.classList.remove('drop-target'),
    ondrop: event => {
      event.preventDefault();
      const name = ui.dragName;
      ui.dragName = null;
      if (name && name !== habit.name) moveTo(name, habit);
    },
  },
  handle,
  checkControl(habit),
  h('div', { class: 'habit-info' },
    h('div', { class: 'habit-name' },
      habit.emoji ? h('span', { class: 'habit-emoji', 'aria-hidden': 'true', text: habit.emoji }) : null,
      habit.name),
    habitMeta(habit, resting)),
  weekDots(habit),
  h('div', { class: 'icon-buttons' },
    h('button', { class: 'icon-button', type: 'button', title: 'Note for today', 'aria-label': `Note for "${habit.name}" today`,
      onclick: () => editNote(habit, ui.data.today) }, '📝'),
    h('button', { class: 'icon-button', type: 'button', title: 'Edit', 'aria-label': `Edit "${habit.name}"`,
      onclick: () => openHabitDialog(habit) }, '✏️')));
  return card;
}

/** A tick button for simple habits; a progress ring with + / − for habits with a target. */
function checkControl(habit) {
  if (habit.target <= 1) {
    const toggleToday = () => (habit.doneToday
      ? act('/api/undo', { name: habit.name }, `Undid today for "${habit.name}"`)
      : act('/api/checkin', { name: habit.name }, celebrate(habit)));
    return h('button', {
      class: 'check', type: 'button', text: '✓',
      'aria-pressed': String(habit.doneToday),
      'aria-label': `${habit.doneToday ? 'Undo' : 'Mark'} "${habit.name}" ${habit.doneToday ? 'for today' : 'done today'}`,
      onclick: toggleToday,
    });
  }

  const count = habit.todayCount;
  const r = 20, circumference = 2 * Math.PI * r;
  const ring = s('svg', { width: 48, height: 48, viewBox: '0 0 48 48', 'aria-hidden': 'true' },
    s('circle', { cx: 24, cy: 24, r, fill: 'none', stroke: 'var(--accent-tint)', 'stroke-width': 5 }),
    count > 0 ? s('circle', {
      cx: 24, cy: 24, r, fill: 'none', stroke: 'var(--accent-deep)', 'stroke-width': 5, 'stroke-linecap': 'round',
      'stroke-dasharray': `${circumference * Math.min(1, count / habit.target)} ${circumference}`, transform: 'rotate(-90 24 24)',
    }) : null);
  const plusOne = () => {
    if (habit.doneToday) { toast(`"${habit.name}" target already reached today ✨`); return; }
    const next = count + 1;
    act('/api/progress', { name: habit.name, delta: 1 },
      next >= habit.target ? celebrate(habit) : `${habit.emoji || '✓'} ${next} of ${habit.target}`);
  };
  return h('div', { class: 'counter' },
    h('button', {
      class: `check ring${habit.doneToday ? ' full' : ''}`, type: 'button',
      'aria-label': `Add one to "${habit.name}": ${count} of ${habit.target} done today`, onclick: plusOne,
    }, ring, h('span', { class: 'ring-count', text: habit.doneToday ? '✓' : `${count}/${habit.target}` })),
    count > 0 ? h('button', {
      class: 'minus', type: 'button', text: '−', title: 'Remove one',
      'aria-label': `Remove one from "${habit.name}"`,
      onclick: () => act('/api/progress', { name: habit.name, delta: -1 }),
    }) : null);
}

function habitMeta(habit, resting) {
  const chips = [
    h('span', { text: `🔥 ${habit.currentStreak}-${habit.streakUnit} streak` }),
    h('span', { text: `🏆 best ${habit.longestStreak}` }),
    h('span', { text: `📈 ${habit.completion}%` }),
  ];
  if (habit.schedule.type === 'WEEKLY' && habit.weekProgress) {
    chips.push(h('span', { text: `🗓️ ${habit.weekProgress.done}/${habit.weekProgress.goal} this week` }));
  } else if (habit.schedule.type === 'WEEKDAYS') {
    chips.push(h('span', { text: `📅 ${habit.schedule.label}` }));
  }
  if (habit.reminder) chips.push(h('span', { text: `⏰ ${formatTime(habit.reminder)}` }));
  if (resting) {
    chips.push(h('span', { class: 'rest-chip', text: habit.schedule.type === 'WEEKLY' ? '✨ weekly goal met' : '💤 rest day' }));
  }
  if (habit.needsAttention) chips.push(h('span', { class: 'attention', text: 'needs some love' }));
  if (habit.todayNote) chips.push(h('span', { class: 'note-chip', title: habit.todayNote, text: `📝 ${truncate(habit.todayNote, 36)}` }));
  return h('div', { class: 'habit-meta' }, chips);
}

/** Picks a cheerful message for a check in, celebrating milestones and finishing the day. */
function celebrate(habit) {
  const next = habit.currentStreak + 1;
  const { doneToday, total } = ui.data.summary;
  if (doneToday + 1 === total && total > 1) return 'Every habit done today! 🎉';
  if (habit.streakUnit === 'day' && [7, 14, 21, 30, 50, 100].includes(next)) return `${next} day streak! You are on fire 🔥`;
  return `Nice! "${habit.name}" done 🌟`;
}

function weekDots(habit) {
  return h('div', { class: 'week', 'aria-label': 'Last 7 days' },
    habit.lastSevenDays.map(day => {
      const date = parseISO(day.date);
      let status = day.done ? 'done' : 'missed';
      if (day.beforeCreation) status = 'not tracked yet';
      else if (!day.done && !day.scheduled) status = 'rest day';
      const classes = ['dot'];
      if (day.done) classes.push('done');
      if (day.beforeCreation) classes.push('untracked');
      else if (!day.done && !day.scheduled) classes.push('rest');
      return h('div', { class: 'week-day', title: `${formatDate(date, { weekday: 'long', month: 'short', day: 'numeric' })}: ${status}` },
        h('div', { class: classes.join(' ') }),
        WEEKDAY_SHORT[weekdayIndex(date)].charAt(0));
    }));
}

// ------------------------------------------------------------------ reordering

function moveTo(name, target) {
  act('/api/habits/move', { name, index: habits().indexOf(target) });
}

function moveBy(habit, delta) {
  const list = activeHabits();
  const target = list[list.indexOf(habit) + delta];
  if (!target) return;
  ui.focusHandle = habit.name;
  moveTo(habit.name, target);
}

// ------------------------------------------------------------------ archived habits

function archivedSection() {
  const list = archivedHabits();
  if (!list.length) return null;
  const details = h('details', { class: 'card archived', open: ui.archivedOpen },
    h('summary', { text: `Archived habits (${list.length})` }),
    h('p', { class: 'hint', text: 'Archived habits keep their history but stay out of your day, stats and reminders.' }),
    h('div', { class: 'archived-list' }, list.map(habit => h('div', { class: 'archived-row', style: accentStyle(colorOf(habit)) },
      h('span', { class: 'swatch', 'aria-hidden': 'true' }),
      h('span', { class: 'archived-name', text: displayName(habit) }),
      h('span', { class: 'hint', text: `best streak ${unitWord(habit, habit.longestStreak)} · ${habit.completedDates.length} check-ins` }),
      h('div', { class: 'row-actions' },
        h('button', { class: 'btn secondary small', type: 'button', text: 'Restore',
          onclick: () => act('/api/habits/archive', { name: habit.name, archived: 'false' }, `"${habit.name}" is back 🫧`) }),
        h('button', { class: 'btn danger small', type: 'button', text: 'Delete', onclick: () => deleteHabit(habit) }))))));
  details.addEventListener('toggle', () => { ui.archivedOpen = details.open; });
  return details;
}

// ------------------------------------------------------------------ dialogs

/** Add (habit = null) or edit a habit: name, emoji, colour, schedule, daily target, reminder. */
function openHabitDialog(habit, presetName = '') {
  const isNew = !habit;
  const form = {
    emoji: habit ? habit.emoji : null,
    color: habit ? habit.color : null,
    schedule: habit ? habit.schedule.type : 'DAILY',
    days: new Set(habit && habit.schedule.type === 'WEEKDAYS' ? habit.schedule.days : ['MON', 'WED', 'FRI']),
    times: habit && habit.schedule.type === 'WEEKLY' ? habit.schedule.timesPerWeek : 3,
  };

  const nameInput = h('input', { type: 'text', maxlength: '60', placeholder: 'e.g. Drink water', 'aria-label': 'Habit name' });
  nameInput.value = habit ? habit.name : presetName;

  // Emoji: a pick-one grid, plus "none". A custom emoji from before is kept as an option.
  const emojiChoices = form.emoji && !EMOJI_CHOICES.includes(form.emoji) ? [form.emoji, ...EMOJI_CHOICES] : EMOJI_CHOICES;
  const emojiButtons = [null, ...emojiChoices].map(emoji => h('button', {
    type: 'button', class: 'emoji-choice', 'aria-pressed': String(form.emoji === emoji),
    'aria-label': emoji ? `Emoji ${emoji}` : 'No emoji',
    onclick: event => {
      form.emoji = emoji;
      emojiButtons.forEach(b => b.setAttribute('aria-pressed', String(b === event.currentTarget)));
    },
  }, emoji || '∅'));

  const colorButtons = [null, 0, 1, 2, 3, 4, 5].map(index => h('button', {
    type: 'button', class: `swatch-choice${index === null ? ' auto' : ''}`,
    style: index === null ? null : `background:${PALETTE[index].deep}`,
    'aria-pressed': String(form.color === index), 'aria-label': index === null ? 'Automatic colour' : PALETTE_NAMES[index],
    title: index === null ? 'Automatic' : PALETTE_NAMES[index],
    onclick: event => {
      form.color = index;
      colorButtons.forEach(b => b.setAttribute('aria-pressed', String(b === event.currentTarget)));
    },
  }, index === null ? 'auto' : ''));

  const dayButtons = DAY_CODES.map((code, i) => h('button', {
    type: 'button', class: 'day-choice', 'aria-pressed': String(form.days.has(code)), text: WEEKDAY_SHORT[i],
    onclick: event => {
      if (form.days.has(code)) form.days.delete(code); else form.days.add(code);
      event.currentTarget.setAttribute('aria-pressed', String(form.days.has(code)));
    },
  }));
  const timesInput = h('input', { type: 'number', min: '1', max: '6', 'aria-label': 'Times per week', class: 'small-number' });
  timesInput.value = String(form.times);
  const daysPanel = h('div', { class: 'day-choices' }, dayButtons);
  const timesPanel = h('div', { class: 'inline-field' }, timesInput, h('span', { text: 'times a week, on any days' }));

  const scheduleOptions = [['DAILY', 'Every day'], ['WEEKDAYS', 'Some days'], ['WEEKLY', 'X times a week']];
  const scheduleButtons = scheduleOptions.map(([value, label]) => h('button', {
    type: 'button', class: 'seg', 'aria-pressed': String(form.schedule === value), text: label,
    onclick: () => { form.schedule = value; syncSchedule(); },
  }));
  const syncSchedule = () => {
    scheduleButtons.forEach((b, i) => b.setAttribute('aria-pressed', String(scheduleOptions[i][0] === form.schedule)));
    daysPanel.hidden = form.schedule !== 'WEEKDAYS';
    timesPanel.hidden = form.schedule !== 'WEEKLY';
  };
  syncSchedule();

  const targetInput = h('input', { type: 'number', min: '1', max: '99', 'aria-label': 'Daily target', class: 'small-number' });
  targetInput.value = String(habit ? habit.target : 1);

  const remindToggle = h('input', { type: 'checkbox', class: 'switch', 'aria-label': 'Remind me', checked: Boolean(habit && habit.reminder) });
  const timeInput = h('input', { type: 'time', 'aria-label': 'Reminder time' });
  timeInput.value = habit && habit.reminder ? habit.reminder : '08:00';
  timeInput.disabled = !remindToggle.checked;
  remindToggle.addEventListener('change', () => { timeInput.disabled = !remindToggle.checked; });

  const body = h('div', { class: 'habit-form' },
    field('Name', nameInput),
    field('Emoji', h('div', { class: 'emoji-grid' }, emojiButtons)),
    field('Colour', h('div', { class: 'swatches' }, colorButtons)),
    field('How often', h('div', {}, h('div', { class: 'segmented' }, scheduleButtons), daysPanel, timesPanel)),
    field('Daily target', h('div', { class: 'inline-field' }, targetInput,
      h('span', { class: 'hint', text: 'times a day, e.g. 8 glasses. 1 = a simple check-in.' }))),
    field('Reminder', h('label', { class: 'inline-field' }, remindToggle, h('span', { text: 'Remind me at' }), timeInput)));

  const extraActions = isNew ? [] : [
    { label: 'Archive', onClick: close => { close(); act('/api/habits/archive', { name: habit.name, archived: 'true' }, `"${habit.name}" archived`); } },
    { label: 'Delete', danger: true, onClick: close => { close(); deleteHabit(habit); } },
  ];

  openModal({
    title: isNew ? 'New habit' : 'Edit habit',
    body,
    confirmLabel: isNew ? 'Add habit' : 'Save',
    extraActions,
    onConfirm: async () => {
      const params = {
        emoji: form.emoji || '',
        color: form.color === null || form.color === undefined ? '' : String(form.color),
        schedule: form.schedule,
        days: [...form.days].join(','),
        timesPerWeek: timesInput.value,
        target: targetInput.value,
        reminder: remindToggle.checked ? timeInput.value : '',
      };
      if (isNew) {
        setState(await request('POST', '/api/habits/add', { ...params, name: nameInput.value }));
        render();
        toast(`"${nameInput.value.trim()}" added 🫧`);
      } else {
        setState(await request('POST', '/api/habits/update', { ...params, name: habit.name, newName: nameInput.value }));
        render();
        toast('Saved ✓');
      }
    },
  });
  nameInput.focus();
}

function field(label, control) {
  return h('div', { class: 'field' }, h('div', { class: 'field-label', text: label }), control);
}

function editNote(habit, dateKey) {
  openModal({
    title: `Note · ${formatDate(parseISO(dateKey), { weekday: 'short', month: 'short', day: 'numeric' })}`,
    message: displayName(habit),
    inputValue: noteOn(habit, dateKey) || '',
    multiline: true,
    placeholder: 'How did it go? e.g. "5 km, felt great"',
    confirmLabel: 'Save note',
    onConfirm: async value => {
      setState(await request('POST', '/api/note', { name: habit.name, date: dateKey, note: value }));
      render();
      toast(value.trim() ? 'Note saved 📝' : 'Note removed');
    },
  });
}

function deleteHabit(habit) {
  openModal({
    title: `Delete "${habit.name}"?`,
    message: 'This removes the habit and all of its history. It cannot be undone. (Archive keeps the history instead.)',
    confirmLabel: 'Delete',
    danger: true,
    onConfirm: async () => {
      setState(await request('POST', '/api/habits/delete', { name: habit.name }));
      render();
      toast('Habit deleted');
    },
  });
}

function confirmDemo() {
  openModal({
    title: 'Load demo data?',
    message: 'This replaces ALL your current habits with 4 sample habits that have 30 days of history.',
    confirmLabel: 'Replace with demo data',
    danger: true,
    onConfirm: async () => {
      setState(await request('POST', '/api/demo', {}));
      ui.calendarHabit = ui.highlightHabit = ui.calendarDay = null;
      render();
      toast('Demo data loaded ✨');
    },
  });
}

// ------------------------------------------------------------------ Calendar tab

function habitPills(list, selectedName, onSelect) {
  return h('div', { class: 'pills', role: 'group', 'aria-label': 'Choose a habit' },
    list.map(habit => h('button', {
      class: 'pill', type: 'button', style: accentStyle(colorOf(habit)),
      'aria-pressed': String(habit.name === selectedName),
      onclick: () => onSelect(habit.name),
    }, h('span', { class: 'swatch', 'aria-hidden': 'true' }), displayName(habit))));
}

function renderCalendar() {
  const list = activeHabits();
  if (!list.length) return [h('h2', { class: 'section-title', text: 'Calendar' }), emptyState()];

  const habit = list.find(x => x.name === ui.calendarHabit) || list[0];
  ui.calendarHabit = habit.name;
  const today = todayDate();
  if (!ui.calendarMonth) ui.calendarMonth = new Date(today.getFullYear(), today.getMonth(), 1);
  const month = ui.calendarMonth;
  const color = colorOf(habit);
  const created = parseISO(habit.createdDate);
  const trackable = d => d <= today && d >= created;

  if (ui.calendarDay) {
    const selected = parseISO(ui.calendarDay);
    if (selected.getMonth() !== month.getMonth() || selected.getFullYear() !== month.getFullYear() || !trackable(selected)) {
      ui.calendarDay = null;
    }
  }
  if (!ui.calendarDay && month.getMonth() === today.getMonth() && month.getFullYear() === today.getFullYear()) {
    ui.calendarDay = ui.data.today;
  }

  const isCurrentMonth = month.getFullYear() === today.getFullYear() && month.getMonth() === today.getMonth();
  const isCreationMonth = month.getFullYear() === created.getFullYear() && month.getMonth() === created.getMonth();
  const changeMonth = delta => {
    ui.calendarMonth = new Date(month.getFullYear(), month.getMonth() + delta, 1);
    ui.calendarDay = null;
    render();
  };

  const cells = WEEKDAY_SHORT.map(day => h('div', { class: 'calendar-weekday', text: day }));
  for (let i = 0; i < weekdayIndex(month); i++) cells.push(h('div', { class: 'day blank', 'aria-hidden': 'true' }));

  let checkIns = 0;
  for (let d = new Date(month); d.getMonth() === month.getMonth(); d = addDays(d, 1)) {
    const key = toISO(d);
    const done = habit.done.has(key);
    const count = countOn(habit, key);
    const note = noteOn(habit, key);
    if (done) checkIns++;
    const classes = ['day'];
    if (done) classes.push('done');
    else if (count > 0) classes.push('partial');
    if (!scheduledOn(habit, d)) classes.push('rest');
    if (note) classes.push('has-note');
    if (key === ui.data.today) classes.push('today');
    if (key === ui.calendarDay) classes.push('selected');
    let label = formatDate(d, { weekday: 'long', month: 'long', day: 'numeric' });
    if (done) label += ', done';
    else if (count > 0) label += `, ${count} of ${habit.target}`;
    if (note) label += ', has a note';
    cells.push(h('button', {
      class: classes.join(' '), type: 'button', disabled: !trackable(d),
      'aria-pressed': String(key === ui.calendarDay), 'aria-label': label,
      onclick: () => { ui.calendarDay = key; render(); },
    }, String(d.getDate())));
  }

  return [
    h('h2', { class: 'section-title' }, 'Calendar ', h('span', { class: 'hint', text: '· tap a day to change it or add a note' })),
    habitPills(list, habit.name, name => { ui.calendarHabit = name; ui.calendarDay = null; render(); }),
    h('div', { class: 'calendar-layout', style: accentStyle(color) },
      h('div', { class: 'card' },
        h('div', { class: 'calendar-head' },
          h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Previous month', disabled: isCreationMonth, onclick: () => changeMonth(-1) }, '‹'),
          h('h3', { text: formatDate(month, { month: 'long', year: 'numeric' }) }),
          h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Next month', disabled: isCurrentMonth, onclick: () => changeMonth(1) }, '›')),
        h('div', { class: 'calendar-grid' }, cells),
        h('div', { class: 'calendar-foot',
          text: `${checkIns} check-in${checkIns === 1 ? '' : 's'} this month · ${habit.schedule.label} · tracking since ${formatDate(created, { month: 'long', day: 'numeric', year: 'numeric' })}` })),
      dayPanel(habit)),
  ];
}

/** Details for the selected calendar day: mark done / adjust the count, and its note. */
function dayPanel(habit) {
  if (!ui.calendarDay) {
    return h('div', { class: 'card day-panel empty-panel' }, h('p', { class: 'hint', text: 'Tap a day to see or change it.' }));
  }
  const key = ui.calendarDay;
  const date = parseISO(key);
  const done = habit.done.has(key);
  const count = countOn(habit, key);
  const rest = !scheduledOn(habit, date);
  let status = done ? 'Done ✓' : (count > 0 ? `${count} of ${habit.target} so far` : 'Not done');
  if (!done && count === 0 && rest) status = 'Rest day 💤';
  const niceDate = formatDate(date, { month: 'short', day: 'numeric' });

  const controls = habit.target > 1
    ? h('div', { class: 'stepper' },
      h('button', { class: 'btn secondary', type: 'button', text: '−', 'aria-label': 'Remove one', disabled: count === 0,
        onclick: () => act('/api/progress', { name: habit.name, date: key, delta: -1 }) }),
      h('span', { class: 'stepper-value', text: `${count} / ${habit.target}` }),
      h('button', { class: 'btn', type: 'button', text: '+', 'aria-label': 'Add one', disabled: done,
        onclick: () => act('/api/progress', { name: habit.name, date: key, delta: 1 }) }))
    : h('button', { class: done ? 'btn secondary' : 'btn', type: 'button', text: done ? 'Mark not done' : 'Mark done',
      onclick: () => act('/api/toggle', { name: habit.name, date: key }, done ? `Unmarked ${niceDate}` : `Marked ${niceDate} done ✓`) });

  const noteBox = h('textarea', { rows: '4', maxlength: '280', placeholder: 'Add a note for this day…', 'aria-label': 'Note' });
  noteBox.value = noteOn(habit, key) || '';
  const saveNote = () => act('/api/note', { name: habit.name, date: key, note: noteBox.value },
    noteBox.value.trim() ? 'Note saved 📝' : 'Note removed');

  return h('div', { class: 'card day-panel' },
    h('h3', { text: formatDate(date, { weekday: 'long', month: 'long', day: 'numeric' }) }),
    h('p', { class: 'day-status', text: status }),
    controls,
    h('div', { class: 'field-label', style: 'margin-top:18px', text: 'Note' }),
    noteBox,
    h('button', { class: 'btn secondary small', type: 'button', text: 'Save note', onclick: saveNote }));
}

// ------------------------------------------------------------------ Settings tab

async function loadSettings() {
  ui.settings = await request('GET', '/api/settings');
}

function renderSettings() {
  if (!ui.settings) {
    loadSettings().then(render).catch(e => toast(e.message, true));
    return [h('p', { class: 'loading', text: 'Loading settings…' })];
  }
  const st = ui.settings;
  const save = async (params, message = 'Saved ✓') => {
    try {
      ui.settings = await request('POST', '/api/settings', params);
      toast(message);
    } catch (e) {
      toast(e.message, true);
    }
    render();
  };
  const off = !st.remindersEnabled;

  const dayEndOptions = ['00:00', '01:00', '02:00', '03:00', '04:00', '05:00'];
  const dayEndSelect = h('select', { 'aria-label': 'Day ends at', onchange: e => save({ dayEndsAt: e.target.value }) },
    dayEndOptions.map(t => h('option', { value: t, text: t === '00:00' ? 'Midnight (default)' : formatTime(t) })));
  dayEndSelect.value = st.dayEndsAt;

  return [
    h('h2', { class: 'section-title', text: 'Settings ⚙️' }),
    h('div', { class: 'grid grid-2 settings' },
      h('section', { class: 'card' },
        h('h3', { text: '⏰ Reminders' }),
        switchRow('All reminders', 'Turn every notification on or off.', st.remindersEnabled,
          v => save({ remindersEnabled: v }, v ? 'Reminders on ⏰' : 'Reminders paused')),
        h('p', { class: 'hint', text: 'Each habit can have its own reminder time: tap ✏️ on a habit to set it.' }),
        switchRow('Evening nudge', 'If anything due today is still not done.', st.nudgeEnabled,
          v => save({ nudgeEnabled: v }), timeField(st.nudgeTime, v => save({ nudgeTime: v }), off || !st.nudgeEnabled), off),
        switchRow('Streak alert', 'When a streak would break tonight.', st.streakAlertEnabled,
          v => save({ streakAlertEnabled: v }), timeField(st.streakAlertTime, v => save({ streakAlertTime: v }), off || !st.streakAlertEnabled), off),
        h('button', { class: 'btn secondary small', type: 'button', text: 'Send a test notification', onclick: sendTestNotice })),
      h('section', { class: 'card' },
        h('h3', { text: '🔔 Where reminders appear' }),
        h('p', { text: st.trayAvailable
          ? 'Windows notifications come from the bubble icon in your system tray, even when this page is closed. Keep the app running (it can stay in the tray).'
          : 'No system tray here, so reminders only appear while this page is open.' }),
        browserNotificationRow()),
      h('section', { class: 'card' },
        h('h3', { text: '🌙 Your day' }),
        h('div', { class: 'setting-row' },
          h('div', {},
            h('div', { class: 'setting-title', text: 'Day ends at' }),
            h('div', { class: 'hint', text: 'For night owls: with 3:00 AM, a check-in at 1 AM still counts for yesterday.' })),
          dayEndSelect)),
      h('section', { class: 'card' },
        h('h3', { text: '🚀 Startup' }),
        st.startAtLoginSupported
          ? switchRow('Start with Windows', 'Opens quietly in the tray when you log in, so reminders just work.', st.startAtLogin,
            v => save({ startAtLogin: v }, v ? 'Will start with Windows 🚀' : 'Will not start with Windows'))
          : h('p', { class: 'hint', text: 'Starting automatically is only available on Windows.' }))),
  ];
}

function switchRow(title, hint, checked, onChange, extra = null, disabled = false) {
  return h('div', { class: `setting-row${disabled ? ' disabled' : ''}` },
    h('div', {},
      h('div', { class: 'setting-title', text: title }),
      h('div', { class: 'hint', text: hint })),
    h('div', { class: 'setting-controls' },
      extra,
      h('input', { type: 'checkbox', class: 'switch', role: 'switch', checked, disabled, 'aria-label': title,
        onchange: e => onChange(e.target.checked) })));
}

function timeField(value, onChange, disabled) {
  const input = h('input', { type: 'time', 'aria-label': 'Time', disabled, onchange: e => { if (e.target.value) onChange(e.target.value); } });
  input.value = value;
  return input;
}

function browserNotificationRow() {
  if (!('Notification' in window)) {
    return h('p', { class: 'hint', text: 'This browser does not support notifications.' });
  }
  const permission = Notification.permission;
  const labels = {
    granted: 'Browser notifications are allowed for this page.',
    denied: 'Browser notifications are blocked. You can allow them in your browser\'s site settings.',
    default: 'Allow browser notifications as a backup while this page is open in the background.',
  };
  return h('div', { class: 'setting-row' },
    h('div', { class: 'hint', text: labels[permission] }),
    permission === 'default'
      ? h('button', { class: 'btn small', type: 'button', text: 'Allow', onclick: async () => { await Notification.requestPermission(); render(); } })
      : null);
}

async function sendTestNotice() {
  try {
    setState(await request('POST', '/api/notifications/test', {}));
    await pollNotices();
  } catch (e) {
    toast(e.message, true);
  }
}

// ------------------------------------------------------------------ reminders in the page

async function pollNotices() {
  try {
    const body = await request('GET', `/api/notifications?after=${ui.lastNoticeId || 0}`);
    const firstPoll = ui.lastNoticeId === null;
    ui.lastNoticeId = body.latestId;
    if (firstPoll || !body.notices.length) return; // don't replay reminders sent before the page opened
    body.notices.forEach(showNotice);
    refresh();
  } catch (e) {
    // The server may be restarting; the next poll will try again.
  }
}

/** A banner with quick actions, plus a browser notification when the tab is hidden and there is no tray. */
function showNotice(notice) {
  const container = document.getElementById('notices');
  const habit = notice.habit ? findHabit(notice.habit) : null;
  let banner;
  const dismiss = () => banner.remove();
  const actions = [];
  if (habit && notice.kind === 'HABIT') {
    actions.push(h('button', { class: 'btn small', type: 'button', text: 'Mark done',
      onclick: () => { dismiss(); act('/api/checkin', { name: habit.name }, celebrate(habit)); } }));
    actions.push(h('button', { class: 'btn secondary small', type: 'button', text: 'Snooze 10 min',
      onclick: async () => {
        dismiss();
        try {
          setState(await request('POST', '/api/notifications/snooze', { name: habit.name, minutes: '10' }));
          toast('Snoozed for 10 minutes 😴');
        } catch (e) {
          toast(e.message, true);
        }
      } }));
  }
  banner = h('div', { class: 'card notice', role: 'alert' },
    h('img', { src: 'bubble.svg', alt: '', class: 'notice-icon' }),
    h('div', { class: 'notice-body' },
      h('div', { class: 'notice-title', text: notice.title }),
      h('div', { class: 'notice-message', text: notice.message }),
      actions.length ? h('div', { class: 'notice-actions' }, actions) : null),
    h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Dismiss', onclick: dismiss }, '✕'));
  container.prepend(banner);
  while (container.children.length > 4) container.lastElementChild.remove();

  const trayAvailable = ui.settings && ui.settings.trayAvailable;
  if ('Notification' in window && Notification.permission === 'granted' && document.hidden && !trayAvailable) {
    const browserNotice = new Notification(notice.title, { body: notice.message, icon: 'bubble.svg', tag: `habit-${notice.id}` });
    browserNotice.onclick = () => window.focus();
  }
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
  try { await loadSettings(); } catch (e) { /* the Settings tab will retry */ }
  await pollNotices();
  setInterval(pollNotices, NOTICE_POLL_MS);
})();
