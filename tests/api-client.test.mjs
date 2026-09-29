import assert from "node:assert/strict";
import test from "node:test";

class MemoryStorage {
  #values = new Map();
  getItem(key) { return this.#values.get(key) ?? null; }
  setItem(key, value) { this.#values.set(key, String(value)); }
  removeItem(key) { this.#values.delete(key); }
  clear() { this.#values.clear(); }
}

globalThis.window = { sessionStorage: new MemoryStorage() };
process.env.NEXT_PUBLIC_API_URL = "http://api.test";
const api = await import(`../app/lib/api.ts?api-test=${Date.now()}`);

const session = {
  token: "signed-jwt",
  userId: "04bf12c5-9e80-48bc-9ff2-d91e6d241317",
  username: "meter-user",
  email: "meter@example.com",
  role: "USER",
};

function jsonResponse(body, status = 200) {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: body == null ? {} : { "content-type": "application/json" },
  });
}

test("защищённый запрос автоматически передаёт сохранённый JWT", async () => {
  window.sessionStorage.clear();
  api.saveSession(session);
  let captured;
  globalThis.fetch = async (url, options) => {
    captured = { url, options };
    return jsonResponse([]);
  };
  assert.deepEqual(await api.meterApi.list(), []);
  assert.equal(captured.url, "http://api.test/api/meters");
  assert.equal(captured.options.headers.get("Authorization"), "Bearer signed-jwt");
});

test("multipart-загрузка не задаёт Content-Type вручную и возвращает requestId", async () => {
  api.saveSession(session);
  let captured;
  globalThis.fetch = async (url, options) => {
    captured = { url, options };
    return jsonResponse({ requestId: "ocr-request", status: "QUEUED" }, 202);
  };
  const result = await api.readingApi.upload("meter-id", new File(["png"], "meter.png", { type: "image/png" }));
  assert.equal(result.requestId, "ocr-request");
  assert.equal(captured.options.headers.has("Content-Type"), false);
  assert.ok(captured.options.body instanceof FormData);
  assert.equal(captured.options.body.get("image").name, "meter.png");
});

test("401 очищает сессию и вызывает единый обработчик", async () => {
  api.saveSession(session);
  let unauthorized = false;
  api.onUnauthorized(() => { unauthorized = true; });
  globalThis.fetch = async () => jsonResponse({ error: "Требуется авторизация", details: [] }, 401);
  await assert.rejects(() => api.meterApi.list(), (error) => {
    assert.equal(error.status, 401);
    assert.match(error.message, /Требуется авторизация/);
    return true;
  });
  assert.equal(api.getSession(), null);
  assert.equal(unauthorized, true);
  api.onUnauthorized(null);
});

test("ошибка API включает русские сообщения из details", async () => {
  globalThis.fetch = async () => jsonResponse({ error: "Ошибка проверки данных", details: [{ field: "value", message: "Показание не может быть меньше предыдущего" }] }, 400);
  await assert.rejects(() => api.authApi.login({ login: "u", password: "bad" }), /Показание не может быть меньше предыдущего/);
});
