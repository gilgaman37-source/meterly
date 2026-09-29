"use client";

import { CSSProperties, FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AnalyticsBundle,
  AuthResponse,
  AdminUser,
  MeterResponse,
  MeterType,
  OcrStatusResponse,
  PaymentPeriod,
  ProcessingStatus,
  ReadingResponse,
  Role,
  TariffBillingMethod,
  TariffRequest,
  TariffResponse,
  adminApi,
  analyticsApi,
  authApi,
  checkApiHealth,
  clearSession,
  getSession,
  meterApi,
  ocrApi,
  onUnauthorized,
  paymentApi,
  readingApi,
  saveSession,
} from "./lib/api";

type View = "overview" | "meters" | "readings" | "ocr" | "analytics" | "admin";
type PaymentWithMeter = PaymentPeriod & { meterId: string };
type ApiState = "checking" | "ready" | "offline";

const typeLabels: Record<MeterType, string> = {
  COLD_WATER: "Холодная вода",
  HOT_WATER: "Горячая вода",
  GAS: "Газ",
};
const typeShort: Record<MeterType, string> = { COLD_WATER: "ХВ", HOT_WATER: "ГВ", GAS: "Г" };
const typeClass: Record<MeterType, string> = { COLD_WATER: "cold", HOT_WATER: "hot", GAS: "gas" };
const typeColor: Record<MeterType, string> = { COLD_WATER: "var(--cold)", HOT_WATER: "var(--hot)", GAS: "var(--gas)" };
const statusLabels: Record<ProcessingStatus, string> = {
  QUEUED: "В очереди",
  PROCESSING: "Обработка",
  COMPLETED: "Завершено",
  FAILED: "Ошибка",
  REQUIRES_CONFIRMATION: "Нужно подтверждение",
};
const paymentLabels: Record<PaymentPeriod["status"], string> = {
  MISSING_PREVIOUS: "Нет предыдущего показания",
  RESET_BASELINE: "Новая точка отсчёта",
  MISSING_TARIFF: "Тариф не настроен",
  INVALID_INCREASE: "Некорректное уменьшение",
  CALCULATED: "Рассчитано",
};
const billingLabels: Record<TariffBillingMethod, string> = {
  SINGLE_RATE: "Одиночная ставка",
  TWO_COMPONENT_HOT_WATER: "ГВС: вода + тепло",
};

function formatNumber(value: number | null | undefined, maximumFractionDigits = 3) {
  return value == null ? "—" : new Intl.NumberFormat("ru-RU", { maximumFractionDigits }).format(value);
}

function formatMoney(value: number | null | undefined, currency = "RUB") {
  return value == null ? "—" : new Intl.NumberFormat("ru-RU", { style: "currency", currency, maximumFractionDigits: 2 }).format(value);
}

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—";
}

function monthLabel(value: string) {
  const [year, month] = value.split("-").map(Number);
  return new Intl.DateTimeFormat("ru-RU", { month: "short" }).format(new Date(year, month - 1, 1)).replace(".", "");
}

function roleLabel(role: Role) {
  return role === "ADMIN" ? "Администратор" : role === "ANALYST" ? "Аналитик" : "Домашний аккаунт";
}

function initials(name: string) {
  return name.split(/[\s_-]+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join("") || "M";
}

function EmptyState({ children }: { children: React.ReactNode }) {
  return <div className="empty-state" role="status">{children}</div>;
}

function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  return <div className="error-state" role="alert"><span>{message}</span>{retry && <button className="secondary" onClick={retry}>Повторить</button>}</div>;
}

function LoadingState({ label = "Загрузка данных…" }: { label?: string }) {
  return <div className="loading-state" role="status"><span className="spinner" />{label}</div>;
}

function compactAxis(value: number) {
  const abs = Math.abs(value);
  if (abs >= 1_000_000) return `${formatNumber(value / 1_000_000, 1)}M`;
  if (abs >= 1000) return `${formatNumber(value / 1000, 1)}k`;
  return formatNumber(value, abs < 10 ? 1 : 0);
}

function axisBounds(values: number[]) {
  const minValue = Math.min(...values, 0);
  const maxValue = Math.max(...values, 1);
  const spread = Math.max(maxValue - minValue, Math.abs(maxValue) * 0.1, 1);
  return { min: Math.max(0, minValue - spread * 0.08), max: maxValue + spread * 0.12 };
}

function LineChart({ data, labels, color = "#0c735f", second, secondColor = "#dc614f" }: { data: number[]; labels: string[]; color?: string; second?: number[]; secondColor?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || data.length === 0) return;
    const draw = () => {
      const rect = canvas.getBoundingClientRect();
      const ratio = window.devicePixelRatio || 1;
      canvas.width = rect.width * ratio;
      canvas.height = rect.height * ratio;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.scale(ratio, ratio);
      ctx.clearRect(0, 0, rect.width, rect.height);
      const pad = { left: 46, right: 18, y: 18, bottom: 25 };
      const width = rect.width - pad.left - pad.right;
      const height = rect.height - pad.y - pad.bottom;
      const values = second ? [...data, ...second] : data;
      const { min, max } = axisBounds(values);
      ctx.fillStyle = "#7a8481"; ctx.font = "9px system-ui"; ctx.textAlign = "right"; ctx.textBaseline = "middle";
      ctx.strokeStyle = "#e5ebe7";
      ctx.lineWidth = 1;
      for (let i = 0; i < 4; i += 1) {
        const y = pad.y + (height / 3) * i;
        const tick = max - ((max - min) / 3) * i;
        ctx.fillText(compactAxis(tick), pad.left - 8, y);
        ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(rect.width - pad.right, y); ctx.stroke();
      }
      const plot = (items: number[], stroke: string, dashed = false) => {
        ctx.strokeStyle = stroke; ctx.lineWidth = 2.2; ctx.setLineDash(dashed ? [4, 4] : []); ctx.lineJoin = "round"; ctx.lineCap = "round"; ctx.beginPath();
        items.forEach((value, index) => {
          const x = pad.left + (items.length === 1 ? width / 2 : (width / (items.length - 1)) * index);
          const y = pad.y + height - ((value - min) / (max - min)) * height;
          if (index) ctx.lineTo(x, y); else ctx.moveTo(x, y);
        });
        ctx.stroke(); ctx.setLineDash([]);
      };
      plot(data, color);
      if (second?.length) plot(second, secondColor, true);
      ctx.fillStyle = "#7a8481"; ctx.font = "9px system-ui"; ctx.textAlign = "center"; ctx.textBaseline = "alphabetic";
      labels.forEach((label, index) => {
        const x = pad.left + (labels.length === 1 ? width / 2 : (width / (labels.length - 1)) * index);
        ctx.fillText(label, x, rect.height - 6);
      });
    };
    draw();
    const observer = new ResizeObserver(draw); observer.observe(canvas);
    return () => observer.disconnect();
  }, [data, labels, color, second, secondColor]);
  return <canvas className="chart-canvas" ref={ref} aria-label="Линейный график по месяцам" role="img" />;
}

function BarChart({ labels, series }: { labels: string[]; series: { name: string; color: string; values: number[] }[] }) {
  const max = Math.max(1, ...series.flatMap((item) => item.values));
  const ticks = [max, max * 2 / 3, max / 3, 0];
  return <div className="bar-chart-wrap" role="img" aria-label={series.map((item) => item.name).join(", ")}><div className="bar-axis">{ticks.map((tick) => <span key={tick}>{compactAxis(tick)}</span>)}</div><div className="bars">
      {labels.map((label, index) => <div className="bar-col" key={`${label}-${index}`}>
        {series.map((item) => <i key={item.name} className="bar" title={`${item.name}: ${formatNumber(item.values[index] ?? 0, 2)}`} style={{ height: `${Math.max(2, ((item.values[index] ?? 0) / max) * 100)}%`, background: item.color }} />)}
        <label>{label}</label>
      </div>)}
    </div></div>;
}

function PointChart({ data, labels, color = "#0c735f", second, secondColor = "#dc614f" }: { data: number[]; labels: string[]; color?: string; second?: number[]; secondColor?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || data.length === 0) return;
    const draw = () => {
      const rect = canvas.getBoundingClientRect();
      const ratio = window.devicePixelRatio || 1;
      canvas.width = rect.width * ratio;
      canvas.height = rect.height * ratio;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.scale(ratio, ratio);
      ctx.clearRect(0, 0, rect.width, rect.height);
      const pad = { left: 46, right: 18, y: 18, bottom: 25 };
      const width = rect.width - pad.left - pad.right;
      const height = rect.height - pad.y - pad.bottom;
      const values = second ? [...data, ...second] : data;
      const { min, max } = axisBounds(values);
      ctx.fillStyle = "#7a8481"; ctx.font = "9px system-ui"; ctx.textAlign = "right"; ctx.textBaseline = "middle";
      ctx.strokeStyle = "#e5ebe7";
      ctx.lineWidth = 1;
      for (let i = 0; i < 4; i += 1) {
        const y = pad.y + (height / 3) * i;
        const tick = max - ((max - min) / 3) * i;
        ctx.fillText(compactAxis(tick), pad.left - 8, y);
        ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(rect.width - pad.right, y); ctx.stroke();
      }
      const plot = (items: number[], fill: string) => {
        ctx.fillStyle = fill; ctx.strokeStyle = "#fff"; ctx.lineWidth = 1.5;
        items.forEach((value, index) => {
          const x = pad.left + (items.length === 1 ? width / 2 : (width / (items.length - 1)) * index);
          const y = pad.y + height - ((value - min) / (max - min)) * height;
          ctx.beginPath(); ctx.arc(x, y, 4.5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
        });
      };
      plot(data, color);
      if (second?.length) plot(second, secondColor);
      ctx.fillStyle = "#7a8481"; ctx.font = "9px system-ui"; ctx.textAlign = "center"; ctx.textBaseline = "alphabetic";
      labels.forEach((label, index) => {
        const x = pad.left + (labels.length === 1 ? width / 2 : (width / (labels.length - 1)) * index);
        ctx.fillText(label, x, rect.height - 6);
      });
    };
    draw();
    const observer = new ResizeObserver(draw); observer.observe(canvas);
    return () => observer.disconnect();
  }, [data, labels, color, second, secondColor]);
  return <canvas className="chart-canvas" ref={ref} aria-label="Точечный график по месяцам" role="img" />;
}

function PaymentShareChart({ items }: { items: { type: MeterType; total: number }[] }) {
  const total = items.reduce((sum, item) => sum + item.total, 0);
  if (total <= 0) return <EmptyState>Нет рассчитанных платежей для круговой диаграммы</EmptyState>;
  const segments = items.map((item, index) => {
    const start = items.slice(0, index).reduce((sum, previous) => sum + previous.total, 0) / total * 100;
    const end = start + item.total / total * 100;
    return `${typeColor[item.type]} ${start.toFixed(2)}% ${end.toFixed(2)}%`;
  });
  return <div className="donut-wrap" role="img" aria-label="Соотношение платежей по типам счётчиков">
    <div className="donut" style={{ "--donut-background": `conic-gradient(${segments.join(",")})` } as CSSProperties}><span>{formatMoney(total)}</span></div>
    <div className="donut-legend">{items.map((item) => <div key={item.type}><span><i style={{ background: typeColor[item.type] }} />{typeLabels[item.type]}</span><b>{formatNumber(item.total / total * 100, 1)}%</b></div>)}</div>
  </div>;
}

function Stat({ label, value, note, symbol, tint = "#e8f4f0" }: { label: string; value: string; note?: string; symbol: string; tint?: string }) {
  return <div className="stat-card"><div className="stat-top"><span>{label}</span><span className="stat-icon" style={{ background: tint }}>{symbol}</span></div><div className="stat-value">{value}{note && <span className="trend">{note}</span>}</div></div>;
}

function MeterRows({ meters, readings }: { meters: MeterResponse[]; readings: ReadingResponse[] }) {
  const latest = useMemo(() => {
    const values = new Map<string, ReadingResponse>();
    readings.forEach((reading) => {
      if (!values.has(reading.meterId)) values.set(reading.meterId, reading);
    });
    return values;
  }, [readings]);
  if (!meters.length) return <EmptyState>Счётчиков пока нет. Добавьте первый счётчик, чтобы передавать показания.</EmptyState>;
  return <div className="meter-list">{meters.map((meter) => {
    const reading = latest.get(meter.id);
    return <div className="meter-row" key={meter.id}>
      <div className={`meter-type ${typeClass[meter.meterType]}`}>{typeShort[meter.meterType]}</div>
      <div><div className="meter-name">{meter.description || typeLabels[meter.meterType]}</div><div className="meter-meta">{meter.serialNumber} · Последнее показание: {reading ? formatDate(reading.submittedAt) : "нет"}</div></div>
      <div className="reading-value">{reading ? formatNumber(reading.value) : "—"} <span>м³</span><small>Текущее показание</small></div>
      <span className={`status ${meter.active ? "good" : "warning"}`}>{meter.active ? "Активен" : "Отключён"}</span>
    </div>;
  })}</div>;
}

function PaymentsTable({ meters, payments }: { meters: MeterResponse[]; payments: PaymentWithMeter[] }) {
  const names = new Map(meters.map((meter) => [meter.id, meter.description || typeLabels[meter.meterType]]));
  if (!payments.length) return <EmptyState>Для расчёта нужен хотя бы один период между двумя показаниями.</EmptyState>;
  return <div className="table-wrap"><table><thead><tr><th>Период завершён</th><th>Счётчик</th><th>Расход</th><th>Платёж</th><th>Состояние</th></tr></thead><tbody>
    {payments.slice(0, 12).map((period) => <tr key={period.readingId}><td>{formatDate(period.submittedAt)}</td><td>{names.get(period.meterId) || "Счётчик"}</td><td>{formatNumber(period.consumption)} м³</td><td>{period.status === "CALCULATED" ? formatMoney(period.payment, period.currency) : "—"}</td><td><span className={`status ${period.status === "CALCULATED" ? "good" : period.status === "INVALID_INCREASE" ? "warning" : "info"}`}>{paymentLabels[period.status]}</span></td></tr>)}
  </tbody></table></div>;
}

function Overview({ session, meters, readings, payments, loading, error, go, retry, hasOcr }: { session: AuthResponse; meters: MeterResponse[]; readings: ReadingResponse[]; payments: PaymentWithMeter[]; loading: boolean; error: string; go: (view: View) => void; retry: () => void; hasOcr: boolean }) {
  const activeMeters = meters.filter((meter) => meter.active);
  const calculated = payments.filter((period) => period.status === "CALCULATED" && period.payment != null);
  const totalPayment = calculated.reduce((sum, period) => sum + (period.payment ?? 0), 0);
  const ocrReadings = readings.filter((reading) => reading.source === "IMAGE_OCR");
  const averageConfidence = ocrReadings.length ? ocrReadings.reduce((sum, reading) => sum + (reading.ocrConfidence ?? 0), 0) / ocrReadings.length : null;
  const today = new Intl.DateTimeFormat("ru-RU", { weekday: "long", day: "numeric", month: "long", year: "numeric" }).format(new Date());
  return <>
    <div className="hero-row"><div><div className="eyebrow">{today}</div><h1>Добрый день, {session.username}.</h1><p className="subtitle">Здесь отображаются только данные вашего аккаунта, сохранённые в Meterly.</p></div><div className="button-row"><button className="secondary" onClick={() => go("meters")}>Добавить счётчик</button><button className="primary" onClick={() => go("readings")} disabled={!activeMeters.length}>Передать показание</button></div></div>
    {error && <ErrorState message={error} retry={retry} />}
    <div className="stats"><Stat label="Активные счётчики" value={String(activeMeters.length)} note={meters.length ? `${meters.length} всего` : "Пока нет"} symbol="С" /><Stat label="Сохранённые показания" value={String(readings.length)} note="Из PostgreSQL" symbol="П" tint="#eaf2f8" /><Stat label="Рассчитанные платежи" value={calculated.length ? formatMoney(totalPayment) : "—"} note={calculated.length ? `${calculated.length} периодов` : "Нет расчётов"} symbol="₽" tint="#fff3df" /><Stat label="Средняя уверенность OCR" value={averageConfidence == null ? "—" : `${formatNumber(averageConfidence * 100, 1)}%`} note={ocrReadings.length ? `${ocrReadings.length} показаний` : "Нет данных"} symbol="O" tint="#f0ebf8" /></div>
    {loading ? <LoadingState /> : <div className="grid-main"><section className="panel"><div className="panel-head"><div><h2>Ваши счётчики</h2><div className="panel-note">Последние сохранённые показания</div></div><button className="text-link" onClick={() => go("meters")}>Управление</button></div><MeterRows meters={meters} readings={readings} /></section>
      <aside className="panel"><div className="panel-head"><div><h2>Быстрые действия</h2><div className="panel-note">Доступные операции</div></div></div><div className="quick-actions">
        <button className="quick-action" onClick={() => go("readings")} disabled={!activeMeters.length}><span className="quick-symbol">#</span><span><strong>Ввести показание</strong><small>Передать значение вручную</small></span><span>›</span></button>
        <button className="quick-action" onClick={() => go("readings")} disabled={!activeMeters.length}><span className="quick-symbol">▣</span><span><strong>Сканировать счётчик</strong><small>Загрузить реальную фотографию</small></span><span>›</span></button>
        {hasOcr && <button className="quick-action" onClick={() => go("ocr")}><span className="quick-symbol">!</span><span><strong>Проверить результат OCR</strong><small>Открыть последний запрос</small></span><span>›</span></button>}
        {(session.role === "ANALYST" || session.role === "ADMIN") && <button className="quick-action" onClick={() => go("analytics")}><span className="quick-symbol">↗</span><span><strong>Открыть аналитику</strong><small>Обезличенные серверные сводки</small></span><span>›</span></button>}
      </div></aside></div>}
    <section className="panel" style={{ marginTop: 16 }}><div className="panel-head"><div><h2>Платежи по показаниям</h2><div className="panel-note">Суммы и состояния рассчитаны backend с точностью BigDecimal</div></div></div>{loading ? <LoadingState /> : <PaymentsTable meters={meters} payments={payments} />}</section>
  </>;
}

function MeterForm({ meter, busy, onCancel, onSaved, notify }: { meter?: MeterResponse; busy: boolean; onCancel: () => void; onSaved: () => Promise<void>; notify: (message: string) => void }) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSubmitting(true); setError("");
    const form = new FormData(event.currentTarget);
    const body = { serialNumber: String(form.get("serialNumber") || ""), meterType: form.get("meterType") as MeterType, description: String(form.get("description") || "") || null, region: String(form.get("region") || "") || null };
    try {
      if (meter) await meterApi.update(meter.id, { ...body, active: meter.active });
      else await meterApi.create(body);
      await onSaved(); notify(meter ? "Счётчик обновлён" : "Счётчик добавлен в ваш аккаунт"); onCancel();
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось сохранить счётчик"); }
    finally { setSubmitting(false); }
  };
  return <form className="form-grid" onSubmit={submit}>{error && <div className="field full"><ErrorState message={error} /></div>}
    <div className="field full"><label htmlFor="serialNumber">Серийный номер</label><input id="serialNumber" name="serialNumber" required maxLength={100} defaultValue={meter?.serialNumber} placeholder="Например, CW-1234-5678" /></div>
    <div className="field"><label htmlFor="meterType">Тип счётчика</label><select id="meterType" name="meterType" defaultValue={meter?.meterType || "COLD_WATER"}><option value="COLD_WATER">Холодная вода</option><option value="HOT_WATER">Горячая вода</option><option value="GAS">Газ</option></select></div>
    <div className="field"><label htmlFor="region">Регион</label><input id="region" name="region" maxLength={120} defaultValue={meter?.region || "Московская область"} required /></div>
    <div className="field full"><label htmlFor="description">Описание</label><input id="description" name="description" maxLength={300} defaultValue={meter?.description || ""} placeholder="Где установлен этот счётчик?" /></div>
    <div className="form-actions"><button className="primary" disabled={submitting || busy}>{submitting ? "Сохранение…" : "Сохранить счётчик"}</button><button type="button" className="secondary" onClick={onCancel} disabled={submitting}>Отмена</button></div>
  </form>;
}

function Meters({ meters, readings, loading, error, refresh, notify }: { meters: MeterResponse[]; readings: ReadingResponse[]; loading: boolean; error: string; refresh: () => Promise<void>; notify: (message: string) => void }) {
  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<MeterResponse | null>(null);
  const [disabling, setDisabling] = useState<string | null>(null);
  const [actionError, setActionError] = useState("");
  const disable = async (meter: MeterResponse) => {
    if (!window.confirm(`Отключить счётчик «${meter.description || meter.serialNumber}»? История сохранится.`)) return;
    setDisabling(meter.id); setActionError("");
    try { await meterApi.disable(meter.id); await refresh(); notify("Счётчик отключён, история сохранена"); }
    catch (cause) { setActionError(cause instanceof Error ? cause.message : "Не удалось отключить счётчик"); }
    finally { setDisabling(null); }
  };
  return <><div className="hero-row"><div><div className="eyebrow">Личный учёт</div><h1>Счётчики</h1><p className="subtitle">Каждый физический счётчик учитывается отдельно по типу и серийному номеру.</p></div><button className="primary" onClick={() => { setShowAdd((value) => !value); setEditing(null); }}>{showAdd ? "Закрыть форму" : "Добавить счётчик"}</button></div>
    {(error || actionError) && <ErrorState message={actionError || error} retry={error ? refresh : undefined} />}
    <div className="section-grid"><section className="panel"><div className="panel-head"><div><h2>Ваши счётчики</h2><div className="panel-note">Зарегистрировано устройств: {meters.length}</div></div></div>
      {loading ? <LoadingState /> : meters.length ? <div className="meter-list">{meters.map((meter) => <div className="meter-row meter-manage" key={meter.id}><div className={`meter-type ${typeClass[meter.meterType]}`}>{typeShort[meter.meterType]}</div><div><div className="meter-name">{meter.description || typeLabels[meter.meterType]}</div><div className="meter-meta">{meter.serialNumber} · {meter.region || "Регион не указан"}</div></div><span className={`status ${meter.active ? "good" : "warning"}`}>{meter.active ? "Активен" : "Отключён"}</span><div className="row-actions"><button className="secondary compact" onClick={() => { setEditing(meter); setShowAdd(false); }}>Изменить</button>{meter.active && <button className="danger-button compact" onClick={() => disable(meter)} disabled={disabling === meter.id}>{disabling === meter.id ? "Отключение…" : "Отключить"}</button>}</div></div>)}</div> : <EmptyState>У вас ещё нет счётчиков. Добавьте первый через форму справа.</EmptyState>}
    </section><section className="panel"><div className="panel-head"><div><h2>{editing ? "Редактирование счётчика" : showAdd ? "Регистрация счётчика" : "Управление счётчиками"}</h2><div className="panel-note">Изменения сохраняются в PostgreSQL</div></div></div>
      {(showAdd || editing) ? <MeterForm key={editing?.id || "new"} meter={editing || undefined} busy={Boolean(disabling)} onCancel={() => { setShowAdd(false); setEditing(null); }} onSaved={refresh} notify={notify} /> : <><div className="quick-actions"><div className="quick-action static"><span className="quick-symbol">✓</span><span><strong>Данные защищены</strong><small>Backend проверяет владельца каждой записи</small></span><span /></div></div><div className="callout">Укажите сброс или замену, если новое показание меньше предыдущего. Причина будет сохранена в истории.</div></>}
    </section></div>
    {!loading && meters.length > 0 && <section className="panel" style={{ marginTop: 16 }}><div className="panel-head"><div><h2>Последние показания</h2><div className="panel-note">Реальные значения активных и отключённых счётчиков</div></div></div><MeterRows meters={meters} readings={readings} /></section>}
  </>;
}

function Readings({ meters, readings, payments, loading, error, refresh, go, notify, setOcrRequestId }: { meters: MeterResponse[]; readings: ReadingResponse[]; payments: PaymentWithMeter[]; loading: boolean; error: string; refresh: () => Promise<void>; go: (view: View) => void; notify: (message: string) => void; setOcrRequestId: (id: string) => void }) {
  const activeMeters = meters.filter((meter) => meter.active);
  const [meterReset, setMeterReset] = useState(false);
  const [manualBusy, setManualBusy] = useState(false);
  const [uploadBusy, setUploadBusy] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [formError, setFormError] = useState("");
  const [sourceFilter, setSourceFilter] = useState<"ALL" | ReadingResponse["source"]>("ALL");
  const visibleReadings = sourceFilter === "ALL" ? readings : readings.filter((reading) => reading.source === sourceFilter);
  const meterNames = new Map(meters.map((meter) => [meter.id, meter.description || typeLabels[meter.meterType]]));
  const manual = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setManualBusy(true); setFormError("");
    const form = new FormData(event.currentTarget);
    const submittedAt = String(form.get("submittedAt") || "");
    try {
      await readingApi.submit(String(form.get("meterId")), { value: Number(form.get("value")), submittedAt: submittedAt ? new Date(submittedAt).toISOString() : null, meterReset, resetReason: meterReset ? String(form.get("resetReason") || "") : null });
      event.currentTarget.reset(); setMeterReset(false); await refresh(); notify("Показание сохранено и добавлено в историю");
    } catch (cause) { setFormError(cause instanceof Error ? cause.message : "Не удалось сохранить показание"); }
    finally { setManualBusy(false); }
  };
  const upload = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setFormError("");
    if (!file) { setFormError("Сначала выберите фотографию счётчика"); return; }
    setUploadBusy(true);
    const form = new FormData(event.currentTarget);
    try {
      const queued = await readingApi.upload(String(form.get("ocrMeterId")), file);
      setOcrRequestId(queued.requestId); notify("Изображение загружено — запрос OCR добавлен в очередь"); go("ocr");
    } catch (cause) { setFormError(cause instanceof Error ? cause.message : "Не удалось загрузить изображение"); }
    finally { setUploadBusy(false); }
  };
  return <><div className="hero-row"><div><div className="eyebrow">Проверенные данные</div><h1>Показания</h1><p className="subtitle">Введите значение вручную или отправьте реальную фотографию дисплея в OCR.</p></div></div>
    {(error || formError) && <ErrorState message={formError || error} retry={error ? refresh : undefined} />}
    {!loading && !activeMeters.length ? <section className="panel"><EmptyState>Сначала добавьте активный счётчик в разделе «Счётчики».</EmptyState></section> : <div className="section-grid"><section className="panel"><div className="panel-head"><div><h2>Ручной ввод</h2><div className="panel-note">Значение не может уменьшиться без отметки о сбросе</div></div></div><form className="form-grid" onSubmit={manual}>
      <div className="field full"><label htmlFor="manual-meter">Счётчик</label><select id="manual-meter" name="meterId" required>{activeMeters.map((meter) => <option value={meter.id} key={meter.id}>{meter.description || typeLabels[meter.meterType]} · {meter.serialNumber}</option>)}</select></div>
      <div className="field"><label htmlFor="value">Показание</label><input id="value" name="value" required type="number" min="0" step="0.000001" placeholder="0,000" /></div>
      <div className="field"><label htmlFor="submittedAt">Дата и время</label><input id="submittedAt" name="submittedAt" type="datetime-local" /></div>
      <div className="field full"><label className="check-row"><input type="checkbox" checked={meterReset} onChange={(event) => setMeterReset(event.target.checked)} /> Перед этим счётчик был сброшен или заменён</label></div>
      {meterReset && <div className="field full"><label htmlFor="resetReason">Причина сброса или замены</label><input id="resetReason" name="resetReason" required maxLength={300} placeholder="Например, замена прибора" /></div>}
      <div className="form-actions"><button className="primary" disabled={manualBusy}>{manualBusy ? "Сохранение…" : "Проверить и сохранить"}</button></div>
    </form></section><section className="panel"><div className="panel-head"><div><h2>Распознать по фото</h2><div className="panel-note">JPEG, PNG, WebP или TIFF · до 10 МБ</div></div><span className="status info">OCR на backend</span></div><form className="dropzone" onSubmit={upload}><div>
      <div className="field full"><label htmlFor="ocr-meter">Счётчик</label><select id="ocr-meter" name="ocrMeterId" required>{activeMeters.map((meter) => <option value={meter.id} key={meter.id}>{meter.description || typeLabels[meter.meterType]} · {meter.serialNumber}</option>)}</select></div>
      <div className="drop-icon">▣</div><strong>{file?.name || "Выберите фото счётчика"}</strong><small>{file ? `${formatNumber(file.size / 1024 / 1024, 2)} МБ · готово к загрузке` : "Файл будет отправлен в поле image как multipart/form-data"}</small><label className="secondary file-label">Выбрать изображение<input hidden type="file" accept="image/png,image/jpeg,image/webp,image/tiff" onChange={(event) => setFile(event.target.files?.[0] || null)} /></label>{file && <button className="primary" disabled={uploadBusy}>{uploadBusy ? "Загрузка…" : "Запустить OCR"}</button>}
    </div></form></section></div>}
    <section className="panel" style={{ marginTop: 16 }}><div className="panel-head"><div><h2>История показаний</h2><div className="panel-note">Отдельно загружена для каждого вашего счётчика</div></div><div className="field compact-filter"><label htmlFor="source-filter">Источник</label><select id="source-filter" value={sourceFilter} onChange={(event) => setSourceFilter(event.target.value as typeof sourceFilter)}><option value="ALL">Все</option><option value="MANUAL">Вручную</option><option value="IMAGE_OCR">Фото + OCR</option></select></div></div>
      {loading ? <LoadingState /> : visibleReadings.length ? <div className="table-wrap"><table><thead><tr><th>Передано</th><th>Счётчик</th><th>Тип</th><th>Значение</th><th>Источник</th><th>Статус</th><th>OCR</th></tr></thead><tbody>{visibleReadings.map((reading) => <tr key={reading.id}><td>{formatDate(reading.submittedAt)}</td><td>{meterNames.get(reading.meterId) || "Счётчик"}</td><td><span className="type-label" style={{ "--type-color": typeColor[reading.meterType] } as CSSProperties}>{typeLabels[reading.meterType]}</span></td><td>{formatNumber(reading.value)} м³</td><td>{reading.source === "MANUAL" ? "Вручную" : "Фото + OCR"}</td><td><span className={`status ${reading.processingStatus === "COMPLETED" ? "good" : "warning"}`}>{statusLabels[reading.processingStatus]}</span></td><td>{reading.ocrConfidence == null ? "—" : `${formatNumber(reading.ocrConfidence * 100, 1)}%`}</td></tr>)}</tbody></table></div> : <EmptyState>Показаний по выбранному фильтру пока нет.</EmptyState>}
    </section>
    <section className="panel" style={{ marginTop: 16 }}><div className="panel-head"><div><h2>Расчёт платежей</h2><div className="panel-note">Только серверные результаты; при нулевом тарифе сумма не выдумывается</div></div></div>{loading ? <LoadingState /> : <PaymentsTable meters={meters} payments={payments} />}</section>
  </>;
}

function OcrQueue({ requestId, setRequestId, refresh, notify }: { requestId: string; setRequestId: (id: string) => void; refresh: () => Promise<void>; notify: (message: string) => void }) {
  const [status, setStatus] = useState<OcrStatusResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState("");
  const terminal = status && ["COMPLETED", "FAILED", "REQUIRES_CONFIRMATION"].includes(status.status);
  const load = useCallback(async () => {
    if (!requestId) return null;
    try { const result = await ocrApi.status(requestId); setStatus(result); setError(""); return result; }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось получить статус OCR"); return null; }
  }, [requestId]);
  useEffect(() => {
    if (!requestId) return;
    let cancelled = false; let timer: number | undefined;
    const poll = async () => {
      setLoading(true);
      const result = await load();
      if (!cancelled && result && !["COMPLETED", "FAILED", "REQUIRES_CONFIRMATION"].includes(result.status)) timer = window.setTimeout(poll, 1500);
      setLoading(false);
    };
    poll();
    return () => { cancelled = true; if (timer) window.clearTimeout(timer); };
  }, [requestId, load]);
  useEffect(() => { if (status?.status === "COMPLETED") refresh(); }, [status?.status, refresh]);
  const confirm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setConfirming(true); setError("");
    const form = new FormData(event.currentTarget);
    try { const result = await ocrApi.confirm(requestId, Number(form.get("value"))); setStatus(result); await refresh(); notify("Показание подтверждено и добавлено в историю"); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось подтвердить показание"); }
    finally { setConfirming(false); }
  };
  if (!requestId) return <><div className="hero-row"><div><div className="eyebrow">Фоновая обработка</div><h1>Обработка OCR</h1><p className="subtitle">Здесь появится реальный статус после загрузки изображения.</p></div></div><section className="panel"><EmptyState>Активного OCR-запроса нет. Загрузите фотографию в разделе «Показания».</EmptyState></section></>;
  const stage = status?.status === "QUEUED" ? 1 : status?.status === "PROCESSING" ? 2 : status ? 3 : 0;
  return <><div className="hero-row"><div><div className="eyebrow">Запрос {requestId.slice(0, 8)}…</div><h1>Обработка OCR</h1><p className="subtitle">Статус запрашивается с backend каждые 1,5 секунды до конечного результата.</p></div>{status && <span className={`status ${status.status === "COMPLETED" ? "good" : status.status === "FAILED" || status.status === "REQUIRES_CONFIRMATION" ? "warning" : "info"}`}>{statusLabels[status.status]}</span>}</div>
    {error && <ErrorState message={error} retry={load} />}{loading && !status ? <LoadingState label="Получение статуса OCR…" /> : <div className="section-grid"><section className="panel"><div className="panel-head"><div><h2>Ход обработки</h2><div className="panel-note">{status ? `${typeLabels[status.meterType]} · ${status.serialNumber}` : "Ожидание ответа API"}</div></div></div><div className="timeline">
      {["Изображение принято backend", "Запрос добавлен в очередь FIFO", "OpenCV и Tesseract обрабатывают файл", "Значение проверено сервером"].map((label, index) => <div className={`timeline-item ${index === stage ? "current" : ""}`} key={label}><div className="timeline-rail"><span className="timeline-dot" /></div><div className="timeline-content"><strong>{label}</strong><p>{index < stage ? "Завершено" : index === stage && !terminal ? "В работе" : index === stage ? statusLabels[status!.status] : "Ожидает"}</p></div></div>)}
    </div></section><section className="panel"><div className="panel-head"><div><h2>Результат распознавания</h2><div className="panel-note">Значение и уверенность получены от Tesseract</div></div></div>
      {!status ? <LoadingState /> : <><div className="stat-card" style={{ boxShadow: "none", marginBottom: 13 }}><div className="stat-top"><span>Распознанное значение</span><span>{typeLabels[status.meterType]} · м³</span></div><div className="stat-value">{formatNumber(status.recognizedValue)}</div><div className="confidence"><span style={{ width: `${Math.max(0, Math.min(100, (status.ocrConfidence ?? 0) * 100))}%` }} /></div><div className="panel-note">Уверенность OCR: {status.ocrConfidence == null ? "нет данных" : `${formatNumber(status.ocrConfidence * 100, 1)}%`}</div></div>
        {status.validationErrors.length > 0 && <div className="callout" role="alert">{status.validationErrors.join(" · ")}</div>}
        {(status.status === "REQUIRES_CONFIRMATION" || status.status === "FAILED") && <form className="form-grid" style={{ marginTop: 14 }} onSubmit={confirm}><div className="field full"><label htmlFor="corrected">Подтверждённое показание</label><input id="corrected" name="value" type="number" min="0" step="0.000001" defaultValue={status.recognizedValue ?? ""} required /></div><div className="form-actions"><button className="primary" disabled={confirming}>{confirming ? "Сохранение…" : "Подтвердить показание"}</button></div></form>}
        {status.status === "COMPLETED" && <div className="success-state" role="status">Готово. Показание сохранено в истории и участвует в расчётах.</div>}
        {(status.status === "QUEUED" || status.status === "PROCESSING") && <LoadingState label={statusLabels[status.status]} />}
      </>}
    </section></div>}
    <div className="button-row" style={{ marginTop: 16 }}><button className="secondary" onClick={() => { setRequestId(""); setStatus(null); }}>Закрыть запрос</button></div>
  </>;
}

function seriesForMonths<T>(rows: T[], month: (row: T) => string, value: (row: T) => number) {
  const labels = [...new Set(rows.map(month))].sort();
  return { months: labels, labels: labels.map(monthLabel), values: labels.map((item) => rows.filter((row) => month(row) === item).reduce((sum, row) => sum + Number(value(row) || 0), 0)) };
}

function Analytics({ role }: { role: Role }) {
  const today = new Date();
  const yearAgo = new Date(today); yearAgo.setFullYear(today.getFullYear() - 1);
  const isoDate = (value: Date) => value.toISOString().slice(0, 10);
  const [from, setFrom] = useState(isoDate(yearAgo));
  const [to, setTo] = useState(isoDate(today));
  const [meterType, setMeterType] = useState<"" | MeterType>("");
  const [data, setData] = useState<AnalyticsBundle | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    if (role === "USER") return;
    setLoading(true); setError("");
    try { setData(await analyticsApi.load({ from, to, meterType: meterType || undefined })); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось загрузить аналитику"); }
    finally { setLoading(false); }
  }, [from, to, meterType, role]);
  useEffect(() => { load(); }, [load]);
  if (role === "USER") return <><div className="hero-row"><div><div className="eyebrow">Ограниченный раздел</div><h1>Панель аналитика</h1></div></div><section className="panel"><ErrorState message="Для просмотра обезличенной аналитики требуется роль ANALYST или ADMIN." /></section></>;
  const monthlyPayments = data ? seriesForMonths(data.monthlyPayments, (row) => row.month, (row) => row.total_payment) : { months: [], labels: [], values: [] };
  const averagePayments = data ? seriesForMonths(data.monthlyPayments, (row) => row.month, (row) => row.average_payment) : { months: [], labels: [], values: [] };
  const readingCounts = data ? seriesForMonths(data.readings, (row) => row.month, (row) => row.reading_count) : { months: [], labels: [], values: [] };
  const typeMonths = data ? [...new Set(data.paymentsByType.map((row) => row.month))].sort() : [];
  const paymentSeries = (["COLD_WATER", "HOT_WATER", "GAS"] as MeterType[]).map((type) => ({ name: typeLabels[type], color: typeColor[type], values: typeMonths.map((month) => data?.paymentsByType.filter((row) => row.month === month && row.meter_type === type).reduce((sum, row) => sum + Number(row.total_payment), 0) || 0) }));
  const paymentShares = (["COLD_WATER", "HOT_WATER", "GAS"] as MeterType[]).map((type) => ({ type, total: data?.paymentsByType.filter((row) => row.meter_type === type).reduce((sum, row) => sum + Number(row.total_payment), 0) || 0 })).filter((item) => item.total > 0);
  const consumptionMonths = data ? [...new Set(data.consumption.map((row) => row.month))].sort() : [];
  const consumption = (type: MeterType) => consumptionMonths.map((month) => data?.consumption.find((row) => row.month === month && row.meter_type === type)?.average_consumption ?? 0);
  const ocrTotals = data ? (["QUEUED", "PROCESSING", "COMPLETED", "REQUIRES_CONFIRMATION", "FAILED"] as ProcessingStatus[]).map((status) => ({ status, count: data.ocr.filter((row) => row.processing_status === status).reduce((sum, row) => sum + Number(row.request_count), 0) })) : [];
  const ocrConfidence = data ? seriesForMonths(data.ocr.filter((row) => row.average_confidence != null), (row) => row.month, (row) => Number(row.average_confidence) * 100) : { months: [], labels: [], values: [] };
  const noData = data && data.summary.submitted_readings === 0 && data.ocr.length === 0;
  return <><div className="hero-row"><div><div className="eyebrow">Обезличенные сводные данные</div><h1>Панель аналитика</h1><p className="subtitle">Расход, платежи и качество OCR агрегируются только на backend.</p></div></div>
    <form className="filters" onSubmit={(event) => { event.preventDefault(); load(); }}><div className="field"><label htmlFor="analytics-from">С</label><input id="analytics-from" type="date" value={from} onChange={(event) => setFrom(event.target.value)} required /></div><div className="field"><label htmlFor="analytics-to">По</label><input id="analytics-to" type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} required /></div><div className="field"><label htmlFor="analytics-type">Тип счётчика</label><select id="analytics-type" value={meterType} onChange={(event) => setMeterType(event.target.value as "" | MeterType)}><option value="">Все типы</option><option value="COLD_WATER">Холодная вода</option><option value="HOT_WATER">Горячая вода</option><option value="GAS">Газ</option></select></div><div className="form-actions analytics-submit"><button className="secondary" disabled={loading}>{loading ? "Обновление…" : "Применить"}</button></div></form>
    {error && <ErrorState message={error} retry={load} />}{loading && !data ? <LoadingState label="Загрузка аналитики…" /> : noData ? <section className="panel"><EmptyState>За выбранный период сервер не вернул аналитических данных.</EmptyState></section> : data && <>
      <div className="summary-five"><Stat label="Общий расход" value={`${formatNumber(data.summary.total_consumption)} м³`} symbol="Р" /><Stat label="Всего платежей" value={formatMoney(data.summary.total_payments)} symbol="₽" tint="#fff3df" /><Stat label="Передано показаний" value={formatNumber(data.summary.submitted_readings, 0)} symbol="П" tint="#f0ebf8" /><Stat label="Активные пользователи" value={formatNumber(data.summary.active_users, 0)} symbol="Л" tint="#eaf2f8" /><Stat label="Активные счётчики" value={formatNumber(data.summary.active_meters, 0)} symbol="С" /></div>
      <div className="charts">
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Платежи по месяцам</h2><div className="panel-note">Рубли · серверный расчёт</div></div></div>{monthlyPayments.values.length ? <BarChart labels={monthlyPayments.labels} series={[{ name: "Платежи", color: "var(--brand)", values: monthlyPayments.values }]} /> : <EmptyState>Нет платежей</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Платежи по типу счётчика</h2><div className="panel-note">Типы не смешиваются</div></div></div>{typeMonths.length ? <><BarChart labels={typeMonths.map(monthLabel)} series={paymentSeries} /><div className="legend">{paymentSeries.map((item) => <span key={item.name}><i style={{ background: item.color }} />{item.name}</span>)}</div></> : <EmptyState>Нет платежей по типам</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Соотношение платежей</h2><div className="panel-note">Круговая диаграмма по суммам за период</div></div></div><PaymentShareChart items={paymentShares} /></section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Средний платёж</h2><div className="panel-note">Точечный график по месяцам</div></div></div>{averagePayments.values.length ? <PointChart data={averagePayments.values} labels={averagePayments.labels} /> : <EmptyState>Нет данных</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Расход воды</h2><div className="panel-note">Среднее потребление · точечный график</div></div></div>{consumptionMonths.length ? <><PointChart data={consumption("COLD_WATER")} second={consumption("HOT_WATER")} labels={consumptionMonths.map(monthLabel)} color={typeColor.COLD_WATER} secondColor={typeColor.HOT_WATER} /><div className="legend"><span><i style={{ background: typeColor.COLD_WATER }} />Холодная вода</span><span><i style={{ background: typeColor.HOT_WATER }} />Горячая вода</span></div></> : <EmptyState>Нет данных о расходе воды</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Расход газа</h2><div className="panel-note">Отдельный ряд для газа</div></div></div>{consumptionMonths.length ? <LineChart data={consumption("GAS")} labels={consumptionMonths.map(monthLabel)} color={typeColor.GAS} /> : <EmptyState>Нет данных о расходе газа</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Переданные показания</h2><div className="panel-note">Ручные и OCR-показания за месяц</div></div></div>{readingCounts.values.length ? <BarChart labels={readingCounts.labels} series={[{ name: "Показания", color: "#6d8f86", values: readingCounts.values }]} /> : <EmptyState>Нет показаний</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Очередь OCR</h2><div className="panel-note">FIFO: сохранение файла, очередь, обработка</div></div></div>{ocrTotals.some((item) => item.count) ? <><div className="ocr-cards five">{ocrTotals.map((item) => <div className="ocr-card" key={item.status}><strong>{formatNumber(item.count, 0)}</strong><small>{statusLabels[item.status]}</small></div>)}</div><div className="callout">Загруженное изображение сначала сохраняется в локальное хранилище, затем запрос получает статус QUEUED и обрабатывается worker в порядке очереди.</div></> : <EmptyState>Нет запросов OCR</EmptyState>}</section>
        <section className="panel chart-panel"><div className="panel-head"><div><h2>Средняя уверенность OCR</h2><div className="panel-note">Ежемесячно после распознавания</div></div></div>{ocrConfidence.values.length ? <LineChart data={ocrConfidence.values} labels={ocrConfidence.labels} color="#7659a5" /> : <EmptyState>Нет данных об уверенности</EmptyState>}</section>
        <section className="panel chart-panel formula-panel"><div className="panel-head"><div><h2>Формула платежей</h2><div className="panel-note">Для тарифов Московской области и других регионов</div></div></div><div className="formula-box"><strong>Платёж = расход × эффективный тариф за 1 м³</strong><span>Расход считается как max(текущее показание − предыдущее показание, 0). Для ХВС и газа эффективный тариф равен ставке за м³. Для ГВС поддержан двухкомпонентный вариант: компонент воды + компонент тепловой энергии × норматив Гкал/м³. Если тариф менялся внутри периода, backend делит расход пропорционально по дням.</span></div></section>
      </div></>}
  </>;
}

function TariffForm({ tariff, busy, onCancel, onSaved }: { tariff: TariffResponse | null; busy: boolean; onCancel: () => void; onSaved: () => Promise<void> }) {
  const [billingMethod, setBillingMethod] = useState<TariffBillingMethod>(tariff?.billingMethod || "SINGLE_RATE");
  const [meterType, setMeterType] = useState<MeterType>(tariff?.meterType || "COLD_WATER");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSaving(true); setError("");
    const form = new FormData(event.currentTarget);
    const numberOrNull = (name: string) => {
      const value = String(form.get(name) || "").trim();
      return value ? Number(value) : null;
    };
    const body: TariffRequest = {
      meterType,
      billingMethod,
      pricePerUnit: numberOrNull("pricePerUnit") ?? 0,
      waterComponentPerM3: numberOrNull("waterComponentPerM3"),
      heatComponentPerGcal: numberOrNull("heatComponentPerGcal"),
      heatNormGcalPerM3: numberOrNull("heatNormGcalPerM3"),
      currency: String(form.get("currency") || "RUB"),
      validFrom: String(form.get("validFrom")),
      validTo: String(form.get("validTo") || "") || null,
      region: String(form.get("region") || "Московская область"),
      sourceUrl: String(form.get("sourceUrl") || "") || null,
    };
    try {
      if (tariff) await adminApi.updateTariff(tariff.id, body);
      else await adminApi.createTariff(body);
      await onSaved(); onCancel();
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось сохранить тариф"); }
    finally { setSaving(false); }
  };
  const twoComponent = billingMethod === "TWO_COMPONENT_HOT_WATER";
  return <form className="form-grid admin-form" onSubmit={submit} key={tariff?.id || "new"}>{error && <div className="field full"><ErrorState message={error} /></div>}
    <div className="field"><label htmlFor="admin-meter-type">Тип</label><select id="admin-meter-type" value={meterType} onChange={(event) => setMeterType(event.target.value as MeterType)}><option value="COLD_WATER">Холодная вода</option><option value="HOT_WATER">Горячая вода</option><option value="GAS">Газ</option></select></div>
    <div className="field"><label htmlFor="billing-method">Метод</label><select id="billing-method" value={billingMethod} onChange={(event) => { const value = event.target.value as TariffBillingMethod; setBillingMethod(value); if (value === "TWO_COMPONENT_HOT_WATER") setMeterType("HOT_WATER"); }}><option value="SINGLE_RATE">Одиночная ставка</option><option value="TWO_COMPONENT_HOT_WATER">ГВС: вода + тепло</option></select></div>
    {!twoComponent && <div className="field"><label htmlFor="pricePerUnit">Цена за м³</label><input id="pricePerUnit" name="pricePerUnit" type="number" min="0" step="0.000001" defaultValue={tariff?.pricePerUnit ?? ""} required /></div>}
    {twoComponent && <><div className="field"><label htmlFor="waterComponentPerM3">Компонент воды</label><input id="waterComponentPerM3" name="waterComponentPerM3" type="number" min="0" step="0.000001" defaultValue={tariff?.waterComponentPerM3 ?? ""} required /></div><div className="field"><label htmlFor="heatComponentPerGcal">Компонент тепла</label><input id="heatComponentPerGcal" name="heatComponentPerGcal" type="number" min="0" step="0.000001" defaultValue={tariff?.heatComponentPerGcal ?? ""} required /></div><div className="field"><label htmlFor="heatNormGcalPerM3">Норматив Гкал/м³</label><input id="heatNormGcalPerM3" name="heatNormGcalPerM3" type="number" min="0" step="0.000000001" defaultValue={tariff?.heatNormGcalPerM3 ?? ""} required /></div></>}
    <div className="field"><label htmlFor="currency">Валюта</label><input id="currency" name="currency" maxLength={3} defaultValue={tariff?.currency || "RUB"} required /></div>
    <div className="field"><label htmlFor="validFrom">Действует с</label><input id="validFrom" name="validFrom" type="date" defaultValue={tariff?.validFrom || "2026-01-01"} required /></div>
    <div className="field"><label htmlFor="validTo">Действует по</label><input id="validTo" name="validTo" type="date" defaultValue={tariff?.validTo || ""} /></div>
    <div className="field"><label htmlFor="region">Регион</label><input id="region" name="region" maxLength={120} defaultValue={tariff?.region || "Московская область"} required /></div>
    <div className="field full"><label htmlFor="sourceUrl">Источник</label><input id="sourceUrl" name="sourceUrl" maxLength={500} defaultValue={tariff?.sourceUrl || ""} placeholder="URL постановления или страницы поставщика" /></div>
    <div className="form-actions"><button className="primary" disabled={busy || saving}>{saving ? "Сохранение…" : tariff ? "Обновить тариф" : "Добавить тариф"}</button>{tariff && <button type="button" className="secondary" onClick={onCancel} disabled={saving}>Отмена</button>}</div>
  </form>;
}

function AdminPanel({ role, currentUserId, notify }: { role: Role; currentUserId: string; notify: (message: string) => void }) {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [tariffs, setTariffs] = useState<TariffResponse[]>([]);
  const [editingTariff, setEditingTariff] = useState<TariffResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    if (role !== "ADMIN") return;
    setLoading(true); setError("");
    try {
      const [loadedUsers, loadedTariffs] = await Promise.all([adminApi.users(), adminApi.tariffs()]);
      setUsers(loadedUsers); setTariffs(loadedTariffs);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось загрузить администрирование"); }
    finally { setLoading(false); }
  }, [role]);
  useEffect(() => { load(); }, [load]);
  if (role !== "ADMIN") return <><div className="hero-row"><div><div className="eyebrow">Ограниченный раздел</div><h1>Администрирование</h1></div></div><section className="panel"><ErrorState message="Для управления пользователями и тарифами требуется роль ADMIN." /></section></>;
  const updateUser = async (user: AdminUser, patch: Partial<Pick<AdminUser, "role" | "enabled">>) => {
    try {
      const updated = await adminApi.updateUser(user.id, { role: patch.role ?? user.role, enabled: patch.enabled ?? user.enabled });
      setUsers((items) => items.map((item) => item.id === updated.id ? updated : item)); notify("Пользователь обновлён");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось обновить пользователя"); }
  };
  const removeTariff = async (tariff: TariffResponse) => {
    if (!window.confirm(`Удалить тариф ${typeLabels[tariff.meterType]} с ${tariff.validFrom}?`)) return;
    try { await adminApi.deleteTariff(tariff.id); await load(); notify("Тариф удалён"); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось удалить тариф"); }
  };
  return <><div className="hero-row"><div><div className="eyebrow">Системное управление</div><h1>Администрирование</h1><p className="subtitle">Роли пользователей, активность аккаунтов и тарифная сетка для расчёта платежей.</p></div><button className="secondary" onClick={load} disabled={loading}>{loading ? "Обновление…" : "Обновить"}</button></div>
    {error && <ErrorState message={error} retry={load} />}
    <div className="section-grid admin-grid"><section className="panel"><div className="panel-head"><div><h2>Пользователи</h2><div className="panel-note">Всего аккаунтов: {users.length}</div></div></div>{loading && !users.length ? <LoadingState /> : <div className="table-wrap"><table><thead><tr><th>Пользователь</th><th>Роль</th><th>Активен</th><th>Создан</th></tr></thead><tbody>{users.map((user) => <tr key={user.id}><td><strong>{user.username}</strong><br /><span className="muted-cell">{user.email}</span></td><td><select className="inline-select" value={user.role} onChange={(event) => updateUser(user, { role: event.target.value as Role })} disabled={user.id === currentUserId}><option value="USER">USER</option><option value="ANALYST">ANALYST</option><option value="ADMIN">ADMIN</option></select></td><td><input type="checkbox" checked={user.enabled} onChange={(event) => updateUser(user, { enabled: event.target.checked })} disabled={user.id === currentUserId} /></td><td>{formatDate(user.createdAt)}</td></tr>)}</tbody></table></div>}</section>
      <section className="panel"><div className="panel-head"><div><h2>{editingTariff ? "Редактирование тарифа" : "Новый тариф"}</h2><div className="panel-note">ГВС можно считать двухкомпонентно</div></div></div><TariffForm tariff={editingTariff} busy={loading} onCancel={() => setEditingTariff(null)} onSaved={load} /></section></div>
    <section className="panel" style={{ marginTop: 16 }}><div className="panel-head"><div><h2>Тарифы</h2><div className="panel-note">Используются backend при расчёте платежей и аналитики</div></div></div>{loading && !tariffs.length ? <LoadingState /> : <div className="table-wrap"><table><thead><tr><th>Тип</th><th>Метод</th><th>Эффективная ставка</th><th>Период</th><th>Регион</th><th>Источник</th><th /></tr></thead><tbody>{tariffs.map((tariff) => <tr key={tariff.id}><td>{typeLabels[tariff.meterType]}</td><td>{billingLabels[tariff.billingMethod]}</td><td>{formatMoney(tariff.effectivePricePerUnit, tariff.currency)} / м³</td><td>{tariff.validFrom} — {tariff.validTo || "текущий"}</td><td>{tariff.region}</td><td className="source-cell">{tariff.sourceUrl || "—"}</td><td><div className="row-actions"><button className="secondary compact" onClick={() => setEditingTariff(tariff)}>Изменить</button><button className="danger-button compact" onClick={() => removeTariff(tariff)}>Удалить</button></div></td></tr>)}</tbody></table></div>}</section>
  </>;
}

function Auth({ register, setRegister, enter, notice }: { register: boolean; setRegister: (value: boolean) => void; enter: (session: AuthResponse) => void; notice?: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setError("");
    const form = new FormData(event.currentTarget);
    try {
      const session = register
        ? await authApi.register({ username: String(form.get("username")), email: String(form.get("email")), password: String(form.get("password")) })
        : await authApi.login({ login: String(form.get("login")), password: String(form.get("password")) });
      saveSession(session); enter(session);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Не удалось выполнить вход"); }
    finally { setBusy(false); }
  };
  return <div className="auth-shell"><section className="auth-story"><div className="brand" style={{ padding: 0, marginBottom: 40 }}><div className="brand-mark">M</div><div><strong>Meterly</strong><small>Умный учёт ресурсов</small></div></div><div className="eyebrow" style={{ color: "#58c4a5" }}>Коммунальные ресурсы под контролем</div><h1>Каждое показание. Каждый счётчик. Одна надёжная история.</h1><p>Передавайте показания газа и воды вручную или по фотографии, проверяйте распознанное значение и анализируйте расход без смешения физических единиц.</p><div className="auth-highlights"><span>Защищённые личные данные</span><span>OCR на OpenCV и Tesseract</span><span>Расчёты по тарифам</span></div></section><section className="auth-panel"><form className="auth-card" onSubmit={submit}><h2>{register ? "Создать аккаунт" : "С возвращением"}</h2><p>{register ? "Начните контролировать домашние счётчики." : "Войдите по имени пользователя или электронной почте."}</p>{(error || notice) && <ErrorState message={error || notice || ""} />}<div className="form-grid">
    {register && <><div className="field"><label htmlFor="username">Имя пользователя</label><input id="username" name="username" required minLength={3} maxLength={80} autoComplete="username" placeholder="alexm" /></div><div className="field"><label htmlFor="email">Электронная почта</label><input id="email" name="email" type="email" required maxLength={254} autoComplete="email" placeholder="alex@example.com" /></div></>}
    {!register && <div className="field full"><label htmlFor="login">Имя пользователя или почта</label><input id="login" name="login" required autoComplete="username" placeholder="alex@example.com" /></div>}
    <div className="field full"><label htmlFor="password">Пароль</label><input id="password" name="password" type="password" required minLength={register ? 10 : undefined} maxLength={128} autoComplete={register ? "new-password" : "current-password"} placeholder={register ? "Не менее 10 символов" : "••••••••••"} /></div>
    <div className="form-actions"><button className="primary" disabled={busy}>{busy ? "Отправка…" : register ? "Создать аккаунт" : "Войти"}</button></div>
  </div><div className="auth-switch">{register ? "Уже есть аккаунт?" : "Впервые в Meterly?"} <button type="button" onClick={() => { setRegister(!register); setError(""); }} disabled={busy}>{register ? "Войти" : "Создать"}</button></div></form></section></div>;
}

export function MeterMonitorApp() {
  const [view, setView] = useState<View>("overview");
  const [session, setSession] = useState<AuthResponse | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [register, setRegister] = useState(false);
  const [authNotice, setAuthNotice] = useState("");
  const [meters, setMeters] = useState<MeterResponse[]>([]);
  const [readings, setReadings] = useState<ReadingResponse[]>([]);
  const [payments, setPayments] = useState<PaymentWithMeter[]>([]);
  const [loading, setLoading] = useState(false);
  const [dataError, setDataError] = useState("");
  const [toast, setToast] = useState("");
  const [apiState, setApiState] = useState<ApiState>("checking");
  const [ocrRequestId, setOcrRequestIdState] = useState("");
  const notify = useCallback((message: string) => { setToast(message); window.setTimeout(() => setToast(""), 3000); }, []);
  const logout = useCallback((expired = false) => {
    clearSession(); setSession(null); setMeters([]); setReadings([]); setPayments([]); setView("overview");
    if (expired) setAuthNotice("Сессия завершена. Войдите снова.");
  }, []);
  const setOcrRequestId = useCallback((id: string) => {
    setOcrRequestIdState(id);
    if (id) window.sessionStorage.setItem("meterly.ocrRequestId", id); else window.sessionStorage.removeItem("meterly.ocrRequestId");
  }, []);
  const refresh = useCallback(async () => {
    if (!getSession()) return;
    setLoading(true); setDataError("");
    try {
      const meterList = await meterApi.list();
      const [historyByMeter, paymentsByMeter] = await Promise.all([
        Promise.all(meterList.map(async (meter) => (await readingApi.history(meter.id)).content)),
        Promise.all(meterList.map(async (meter) => (await paymentApi.list(meter.id)).map((period) => ({ ...period, meterId: meter.id })))),
      ]);
      setMeters(meterList);
      setReadings(historyByMeter.flat().sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime()));
      setPayments(paymentsByMeter.flat().sort((a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime()));
      setApiState("ready");
    } catch (cause) {
      setDataError(cause instanceof Error ? cause.message : "Не удалось загрузить данные");
      if (getSession()) setApiState("offline");
    } finally { setLoading(false); }
  }, []);
  useEffect(() => {
    const restored = getSession(); setSession(restored); setHydrated(true);
    setOcrRequestIdState(window.sessionStorage.getItem("meterly.ocrRequestId") || "");
    onUnauthorized(() => logout(true));
    checkApiHealth().then((ready) => setApiState(ready ? "ready" : "offline")).catch(() => setApiState("offline"));
    return () => onUnauthorized(null);
  }, [logout]);
  useEffect(() => { if (session) refresh(); }, [session, refresh]);
  useEffect(() => { if (session?.role === "USER" && view === "analytics") setView("overview"); }, [session?.role, view]);
  useEffect(() => { if (session?.role !== "ADMIN" && view === "admin") setView("overview"); }, [session?.role, view]);
  if (!hydrated) return <div className="auth-loading" role="status">Загрузка Meterly…</div>;
  if (!session) return <Auth register={register} setRegister={setRegister} notice={authNotice} enter={(value) => { setDataError(""); setAuthNotice(""); setSession(value); notify("Вход выполнен"); }} />;
  const nav: [View, string][] = [["overview", "Обзор"], ["meters", "Счётчики"], ["readings", "Показания"], ["ocr", "Очередь OCR"], ...((session.role === "ANALYST" || session.role === "ADMIN") ? [["analytics", "Аналитика"] as [View, string]] : []), ...(session.role === "ADMIN" ? [["admin", "Администрирование"] as [View, string]] : [])];
  return <div className="app-shell"><aside className="sidebar"><div className="brand"><div className="brand-mark">M</div><div><strong>Meterly</strong><small>Умный учёт ресурсов</small></div></div><div className="nav-label">Рабочее пространство</div><nav className="nav" aria-label="Основная навигация">{nav.map(([id, label]) => <button key={id} className={view === id ? "active" : ""} onClick={() => setView(id)}><span className="nav-dot" /><span>{label}</span></button>)}</nav><div className="side-foot"><div className={`api-state ${apiState}`} role="status">{apiState === "ready" ? "API доступен" : apiState === "offline" ? "API недоступен" : "Проверка API"}</div>Java 21 · PostgreSQL<br />Защищённое личное пространство</div></aside>
    <main className="main"><header className="topbar"><div className="page-title">{nav.find(([id]) => id === view)?.[1]}</div><div className="top-actions"><button className="avatar" title="Выйти" aria-label="Выйти" onClick={() => logout(false)}>{initials(session.username)}</button><div className="user-meta">{session.username}<span>{session.email} · {roleLabel(session.role)}</span></div></div></header><div className="content">
      {view === "overview" && <Overview session={session} meters={meters} readings={readings} payments={payments} loading={loading} error={dataError} go={setView} retry={refresh} hasOcr={Boolean(ocrRequestId)} />}
      {view === "meters" && <Meters meters={meters} readings={readings} loading={loading} error={dataError} refresh={refresh} notify={notify} />}
      {view === "readings" && <Readings meters={meters} readings={readings} payments={payments} loading={loading} error={dataError} refresh={refresh} go={setView} notify={notify} setOcrRequestId={setOcrRequestId} />}
      {view === "ocr" && <OcrQueue key={ocrRequestId || "empty"} requestId={ocrRequestId} setRequestId={setOcrRequestId} refresh={refresh} notify={notify} />}
      {view === "analytics" && <Analytics role={session.role} />}
      {view === "admin" && <AdminPanel role={session.role} currentUserId={session.userId} notify={notify} />}
    </div></main>
    <nav className="mobile-nav" aria-label="Мобильная навигация">{nav.map(([id, label]) => <button key={id} className={view === id ? "active" : ""} onClick={() => setView(id)}><i />{label}</button>)}</nav>{toast && <div className="toast" role="status">{toast}</div>}
  </div>;
}
