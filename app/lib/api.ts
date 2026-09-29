export type Role = "USER" | "ANALYST" | "ADMIN";
export type MeterType = "COLD_WATER" | "HOT_WATER" | "GAS";
export type ReadingSource = "MANUAL" | "IMAGE_OCR";
export type ProcessingStatus = "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED" | "REQUIRES_CONFIRMATION";
export type TariffBillingMethod = "SINGLE_RATE" | "TWO_COMPONENT_HOT_WATER";

export type AuthResponse = {
  token: string;
  userId: string;
  username: string;
  email: string;
  role: Role;
};

export type MeterResponse = {
  id: string;
  serialNumber: string;
  meterType: MeterType;
  description: string | null;
  region: string | null;
  active: boolean;
  createdAt: string;
};

export type ReadingResponse = {
  id: string;
  meterId: string;
  meterType: MeterType;
  value: number;
  submittedAt: string;
  source: ReadingSource;
  processingStatus: ProcessingStatus;
  ocrConfidence: number | null;
  meterReset: boolean;
  resetReason: string | null;
  correctedAt: string | null;
};

export type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
};

export type OcrQueuedResponse = { requestId: string; status: ProcessingStatus };
export type OcrStatusResponse = {
  requestId: string;
  status: ProcessingStatus;
  recognizedValue: number | null;
  ocrConfidence: number | null;
  validationErrors: string[];
  meterId: string;
  serialNumber: string;
  meterType: MeterType;
  readingId: string | null;
  submittedAt: string;
  processedAt: string | null;
};

export type PaymentStatus = "MISSING_PREVIOUS" | "RESET_BASELINE" | "MISSING_TARIFF" | "INVALID_INCREASE" | "CALCULATED";
export type PaymentPeriod = {
  readingId: string;
  submittedAt: string;
  consumption: number | null;
  payment: number | null;
  currency: string;
  status: PaymentStatus;
};

export type AnalyticsSummary = {
  total_consumption: number;
  total_payments: number;
  active_users: number;
  active_meters: number;
  submitted_readings: number;
};
export type MonthlyPayment = { month: string; total_payment: number; average_payment: number };
export type PaymentByMeterType = { month: string; meter_type: MeterType; total_payment: number };
export type MonthlyConsumption = { month: string; meter_type: MeterType; average_consumption: number; reading_count: number };
export type MonthlyReading = { month: string; source: ReadingSource; reading_count: number };
export type OcrStatistic = { month: string; processing_status: ProcessingStatus; request_count: number; percentage: number; average_confidence: number | null };
export type AdminUser = { id: string; username: string; email: string; role: Role; enabled: boolean; createdAt: string };
export type TariffResponse = {
  id: string;
  meterType: MeterType;
  billingMethod: TariffBillingMethod;
  pricePerUnit: number;
  waterComponentPerM3: number | null;
  heatComponentPerGcal: number | null;
  heatNormGcalPerM3: number | null;
  effectivePricePerUnit: number;
  currency: string;
  validFrom: string;
  validTo: string | null;
  region: string;
  sourceUrl: string | null;
  createdAt: string;
};
export type TariffRequest = Omit<TariffResponse, "id" | "effectivePricePerUnit" | "createdAt">;

type FieldIssue = { field: string; message: string };
type ApiErrorBody = { status?: number; error?: string; message?: string; details?: FieldIssue[] };
type RequestOptions = RequestInit & { anonymous?: boolean };

const baseUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const SESSION_KEY = "meterly.session";
let unauthorizedHandler: (() => void) | null = null;

export class ApiClientError extends Error {
  readonly status: number;
  readonly details: FieldIssue[];
  constructor(status: number, message: string, details: FieldIssue[] = []) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.details = details;
  }
}

export function getSession(): AuthResponse | null {
  if (typeof window === "undefined") return null;
  try {
    const value = window.sessionStorage.getItem(SESSION_KEY);
    return value ? JSON.parse(value) as AuthResponse : null;
  } catch {
    window.sessionStorage.removeItem(SESSION_KEY);
    return null;
  }
}

export function saveSession(session: AuthResponse) {
  window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession() {
  if (typeof window !== "undefined") window.sessionStorage.removeItem(SESSION_KEY);
}

export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

function errorMessage(status: number, payload: ApiErrorBody | null) {
  const summary = payload?.error || payload?.message || `Ошибка запроса (${status})`;
  const details = payload?.details?.map((item) => item.message).filter(Boolean) ?? [];
  return details.length ? `${summary}: ${details.join("; ")}` : summary;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { anonymous = false, ...init } = options;
  const headers = new Headers(init.headers);
  if (init.body != null && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");
  const session = getSession();
  if (!anonymous && session?.token) headers.set("Authorization", `Bearer ${session.token}`);

  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, { ...init, headers });
  } catch {
    throw new ApiClientError(0, "Не удалось связаться с API. Проверьте, что backend запущен.");
  }

  const contentType = response.headers.get("content-type") ?? "";
  const payload = response.status === 204
    ? null
    : contentType.includes("application/json")
      ? await response.json().catch(() => null) as ApiErrorBody | T | null
      : await response.text().catch(() => "");

  if (!response.ok) {
    const errorBody = typeof payload === "object" && payload !== null ? payload as ApiErrorBody : null;
    if (response.status === 401 && !anonymous) {
      clearSession();
      unauthorizedHandler?.();
    }
    throw new ApiClientError(response.status, errorMessage(response.status, errorBody), errorBody?.details ?? []);
  }
  return payload as T;
}

function query(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  const value = search.toString();
  return value ? `?${value}` : "";
}

export const authApi = {
  register: (body: { username: string; email: string; password: string }) =>
    apiRequest<AuthResponse>("/api/auth/register", { method: "POST", body: JSON.stringify(body), anonymous: true }),
  login: (body: { login: string; password: string }) =>
    apiRequest<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify(body), anonymous: true }),
};

export const meterApi = {
  list: () => apiRequest<MeterResponse[]>("/api/meters"),
  create: (body: { serialNumber: string; meterType: MeterType; description: string | null; region: string | null }) =>
    apiRequest<MeterResponse>("/api/meters", { method: "POST", body: JSON.stringify(body) }),
  update: (id: string, body: { serialNumber: string; meterType: MeterType; description: string | null; region: string | null; active: boolean }) =>
    apiRequest<MeterResponse>(`/api/meters/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  disable: (id: string) => apiRequest<void>(`/api/meters/${id}`, { method: "DELETE" }),
};

export const readingApi = {
  history: (meterId: string, params: { page?: number; size?: number; from?: string; to?: string; meterType?: MeterType; source?: ReadingSource; status?: ProcessingStatus } = {}) =>
    apiRequest<Page<ReadingResponse>>(`/api/meters/${meterId}/readings${query({ page: params.page ?? 0, size: params.size ?? 100, from: params.from, to: params.to, meterType: params.meterType, source: params.source, status: params.status })}`),
  submit: (meterId: string, body: { value: number; submittedAt: string | null; meterReset: boolean; resetReason: string | null }) =>
    apiRequest<ReadingResponse>(`/api/meters/${meterId}/readings`, { method: "POST", body: JSON.stringify(body) }),
  upload: (meterId: string, image: File) => {
    const body = new FormData();
    body.append("image", image);
    return apiRequest<OcrQueuedResponse>(`/api/meters/${meterId}/readings/image`, { method: "POST", body });
  },
};

export const ocrApi = {
  status: (requestId: string) => apiRequest<OcrStatusResponse>(`/api/reading-requests/${requestId}`),
  confirm: (requestId: string, value: number) => apiRequest<OcrStatusResponse>(`/api/reading-requests/${requestId}/confirm`, { method: "PATCH", body: JSON.stringify({ value }) }),
};

export const paymentApi = {
  list: (meterId: string) => apiRequest<PaymentPeriod[]>(`/api/meters/${meterId}/payments`),
};

export type AnalyticsBundle = {
  summary: AnalyticsSummary;
  monthlyPayments: MonthlyPayment[];
  paymentsByType: PaymentByMeterType[];
  consumption: MonthlyConsumption[];
  readings: MonthlyReading[];
  ocr: OcrStatistic[];
};

export const analyticsApi = {
  load: async (params: { from: string; to: string; meterType?: MeterType }): Promise<AnalyticsBundle> => {
    const common = query(params);
    const [summary, monthlyPayments, paymentsByType, consumption, readings, ocr] = await Promise.all([
      apiRequest<AnalyticsSummary>(`/api/analytics/summary${common}`),
      apiRequest<MonthlyPayment[]>(`/api/analytics/payments/monthly${common}`),
      apiRequest<PaymentByMeterType[]>(`/api/analytics/payments/by-meter-type${common}`),
      apiRequest<MonthlyConsumption[]>(`/api/analytics/consumption/monthly${common}`),
      apiRequest<MonthlyReading[]>(`/api/analytics/readings/monthly${common}`),
      apiRequest<OcrStatistic[]>(`/api/analytics/ocr/statistics${common}`),
    ]);
    return { summary, monthlyPayments, paymentsByType, consumption, readings, ocr };
  },
};

export const adminApi = {
  users: () => apiRequest<AdminUser[]>("/api/admin/users"),
  updateUser: (id: string, body: { role: Role; enabled: boolean }) =>
    apiRequest<AdminUser>(`/api/admin/users/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
  tariffs: () => apiRequest<TariffResponse[]>("/api/admin/tariffs"),
  createTariff: (body: TariffRequest) =>
    apiRequest<TariffResponse>("/api/admin/tariffs", { method: "POST", body: JSON.stringify(body) }),
  updateTariff: (id: string, body: TariffRequest) =>
    apiRequest<TariffResponse>(`/api/admin/tariffs/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  deleteTariff: (id: string) => apiRequest<void>(`/api/admin/tariffs/${id}`, { method: "DELETE" }),
};

export async function checkApiHealth() {
  const response = await apiRequest<{ status: string }>("/actuator/health", { anonymous: true });
  return response.status === "UP";
}
