"use strict";

const $ = (id) => document.getElementById(id);
const state = { links: [], page: 0, size: 20, total: 0, search: "", tag: "", editing: null, deleting: null, loading: 0, busy: new Set() };
let toastTimer;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers },
  });
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.detail || "Не удалось выполнить запрос. Попробуйте ещё раз.");
  return data;
}

function dateLabel(value) {
  return value ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "short", timeStyle: "short" }).format(new Date(value)) : "Ещё не проверялась";
}

function toast(message) {
  clearTimeout(toastTimer);
  $("toast").textContent = message;
  $("toast").hidden = false;
  toastTimer = setTimeout(() => { $("toast").hidden = true; }, 4500);
}

function actionButton(symbol, label, action, link) {
  const button = element("button", `icon-button ${action}`, symbol);
  button.type = "button";
  button.title = label;
  button.setAttribute("aria-label", `${label}: ${link.title}`);
  button.dataset.action = action;
  button.dataset.id = link.id;
  button.disabled = state.busy.has(link.id);
  return button;
}

function renderLinks() {
  $("links-list").replaceChildren();
  $("link-count").textContent = String(state.total);
  $("empty-state").hidden = state.links.length > 0;
  const filtered = Boolean(state.search || state.tag);
  $("empty-title").textContent = filtered ? "Ничего не нашлось" : "Здесь начнётся ваша коллекция";
  $("empty-description").textContent = filtered ? "Попробуйте изменить запрос или сбросить фильтры." : "Добавьте репозиторий GitHub или вопрос Stack Overflow. Когда что-то изменится, обновление появится в ленте.";
  $("empty-add").hidden = filtered;
  $("reset-filters").hidden = !filtered;

  for (const link of state.links) {
    const row = element("article", "link-row");
    const identity = element("div", "link-identity");
    const isGithub = new URL(link.url).hostname === "github.com";
    identity.append(element("span", `provider-icon ${isGithub ? "" : "so"}`, isGithub ? "GH" : "SO"));
    const info = element("div", "link-info");
    const anchor = element("a", "link-name", link.title);
    anchor.href = link.url;
    anchor.target = "_blank";
    anchor.rel = "noopener noreferrer";
    info.append(anchor, element("span", "link-url", link.url.replace("https://", "")));
    const tags = element("div", "tags");
    for (const tag of link.tags) {
      const button = element("button", "tag", tag);
      button.type = "button";
      button.title = `Показать ссылки с тегом «${tag}»`;
      button.addEventListener("click", () => { $("tag-filter").value = tag; applyFilters(); });
      tags.append(button);
    }
    info.append(tags);
    identity.append(info);
    const status = element("div", "link-state");
    const busy = state.busy.has(link.id);
    const statusClass = !link.enabled ? "paused" : link.lastError ? "failed" : "";
    const statusText = busy ? "Проверяем…" : !link.enabled ? "На паузе" : link.lastError ? "Нужна проверка" : "Отслеживается";
    status.append(element("span", `status ${statusClass}`, statusText), element("span", "checked-at", dateLabel(link.lastCheckTime)));
    if (link.lastError) status.append(element("p", "row-error", link.lastError));
    const actions = element("div", "actions");
    actions.append(actionButton("↻", "Проверить сейчас", "check", link), actionButton("✎", "Редактировать", "edit", link), actionButton("×", "Удалить", "delete", link));
    row.append(identity, status, actions);
    $("links-list").append(row);
  }
  const start = state.total === 0 ? 0 : state.page * state.size + 1;
  $("page-label").textContent = `${start}–${Math.min((state.page + 1) * state.size, state.total)} из ${state.total}`;
  $("previous-page").disabled = state.page === 0;
  $("next-page").disabled = (state.page + 1) * state.size >= state.total;
}

function renderUpdates(updates) {
  const list = $("updates-list");
  list.replaceChildren();
  if (!updates.length) {
    const empty = element("div", "empty-state links-panel");
    empty.append(element("div", "empty-icon", "◷"), element("h3", "", "Пока всё спокойно"), element("p", "", "Здесь появятся изменения, найденные после первой успешной проверки ваших ссылок."));
    list.append(empty);
    return;
  }
  for (const update of updates) {
    const card = element("article", "update-card");
    const anchor = element("a", "", update.title);
    anchor.href = update.url;
    anchor.target = "_blank";
    anchor.rel = "noopener noreferrer";
    const time = element("time", "", `Обнаружено ${dateLabel(update.detectedAt)}`);
    time.dateTime = update.detectedAt;
    card.append(anchor, element("p", "", update.description), time);
    list.append(card);
  }
}

async function load() {
  const generation = ++state.loading;
  $("links-panel").setAttribute("aria-busy", "true");
  const query = new URLSearchParams({ search: state.search, tag: state.tag, page: state.page, size: state.size });
  try {
    const [page, updates] = await Promise.all([api(`/api/links?${query}`), api("/api/updates")]);
    if (generation !== state.loading) return;
    if (!page.links.length && page.total > 0 && state.page > 0) {
      state.page = Math.max(0, Math.ceil(page.total / state.size) - 1);
      return load();
    }
    state.links = page.links;
    state.total = page.total;
    renderLinks();
    renderUpdates(updates);
    $("page-error").hidden = true;
  } catch (error) {
    if (generation !== state.loading) return;
    $("page-error-text").textContent = error.message === "Failed to fetch" ? "Нет соединения с сервером. Проверьте, запущено ли приложение." : error.message;
    $("page-error").hidden = false;
    if (!state.links.length) $("links-list").replaceChildren(element("p", "loading", "Не удалось загрузить коллекцию."));
  } finally {
    if (generation === state.loading) $("links-panel").setAttribute("aria-busy", "false");
  }
}

function openEditor(link = null) {
  state.editing = link?.id ?? null;
  $("link-form").reset();
  $("link-title").value = link?.title || "";
  $("link-url").value = link?.url || "";
  $("link-tags").value = link?.tags.join(", ") || "";
  $("link-enabled").checked = link?.enabled ?? true;
  $("dialog-title").textContent = link ? "Редактировать ссылку" : "Добавить ссылку";
  $("save-link").textContent = link ? "Сохранить изменения" : "Добавить ссылку";
  $("edit-hint").hidden = !link;
  $("form-error").hidden = true;
  $("link-dialog").showModal();
  $("link-title").focus();
}

$("link-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const tags = [...new Set($("link-tags").value.split(",").map((tag) => tag.trim()).filter(Boolean))];
  if (tags.length > 10 || tags.some((tag) => tag.length > 32)) {
    $("form-error").textContent = "Добавьте не более 10 тегов, длина каждого — до 32 символов.";
    $("form-error").hidden = false;
    return;
  }
  const payload = { title: $("link-title").value.trim(), url: $("link-url").value.trim(), tags, enabled: $("link-enabled").checked };
  if (!payload.title) { $("link-title").focus(); return; }
  const id = state.editing;
  $("save-link").disabled = true;
  $("form-error").hidden = true;
  try {
    await api(id === null ? "/api/links" : `/api/links/${id}`, { method: id === null ? "POST" : "PUT", body: JSON.stringify(payload) });
    $("link-dialog").close();
    if (id === null) { state.page = 0; state.search = ""; state.tag = ""; $("search-form").reset(); }
    toast(id === null ? "Ссылка добавлена в коллекцию" : "Изменения сохранены");
    await load();
  } catch (error) {
    $("form-error").textContent = error.message;
    $("form-error").hidden = false;
  } finally { $("save-link").disabled = false; }
});

$("links-list").addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const id = Number(button.dataset.id);
  const link = state.links.find((candidate) => candidate.id === id);
  if (!link) return;
  if (button.dataset.action === "edit") { openEditor(link); return; }
  if (button.dataset.action === "delete") {
    state.deleting = id;
    $("delete-link-title").textContent = link.title;
    $("delete-error").hidden = true;
    $("delete-dialog").showModal();
    $("cancel-delete").focus();
    return;
  }
  state.busy.add(id);
  renderLinks();
  try {
    const checked = await api(`/api/links/${id}/check`, { method: "POST" });
    toast(checked.lastError || "Проверка завершена");
  } catch (error) { toast(error.message); }
  finally { state.busy.delete(id); await load(); }
});

$("confirm-delete").addEventListener("click", async () => {
  $("confirm-delete").disabled = true;
  try {
    await api(`/api/links/${state.deleting}`, { method: "DELETE" });
    $("delete-dialog").close();
    toast("Ссылка удалена");
    if (state.links.length === 1 && state.page > 0) state.page--;
    await load();
  } catch (error) {
    $("delete-error").textContent = error.message;
    $("delete-error").hidden = false;
  } finally { $("confirm-delete").disabled = false; }
});

function applyFilters() {
  state.page = 0;
  state.search = $("search").value.trim();
  state.tag = $("tag-filter").value.trim();
  load();
}

$("search-form").addEventListener("submit", (event) => { event.preventDefault(); applyFilters(); });
$("reset-filters").addEventListener("click", () => { $("search-form").reset(); applyFilters(); });
$("previous-page").addEventListener("click", () => { state.page--; load(); });
$("next-page").addEventListener("click", () => { state.page++; load(); });
for (const id of ["refresh", "refresh-updates", "retry-load"]) $(id).addEventListener("click", load);
for (const id of ["add-link", "empty-add"]) $(id).addEventListener("click", () => openEditor());
for (const id of ["close-dialog", "cancel-dialog"]) $(id).addEventListener("click", () => $("link-dialog").close());
$("cancel-delete").addEventListener("click", () => $("delete-dialog").close());

document.querySelectorAll("button[data-view]").forEach((button) => {
  button.addEventListener("click", () => {
    const updates = button.dataset.view === "updates";
    document.querySelectorAll("button[data-view]").forEach((item) => {
      item.classList.toggle("selected", item === button);
      if (item === button) item.setAttribute("aria-current", "page"); else item.removeAttribute("aria-current");
    });
    $("links-view").hidden = updates;
    $("updates-view").hidden = !updates;
    $("breadcrumb").textContent = updates ? "Обновления" : "Мои ссылки";
    $("page-title").replaceChildren(document.createTextNode(updates ? "Изменения," : "Ваши ссылки,"), document.createElement("br"), element("span", "", updates ? "которые важны." : "под наблюдением."));
    $("page-description").textContent = updates ? "Всё новое в вашей коллекции — без лишних вкладок." : "Репозитории и вопросы, к которым хочется вернуться.";
    load();
  });
});

load();
setInterval(() => { if (!document.hidden && !$("link-dialog").open && !$("delete-dialog").open) load(); }, 30000);
