import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '20s',
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1500'], checks: ['rate==1'] },
};
const base = __ENV.BASE_URL || 'http://localhost:8080';
export function setup() {
  const login = http.post(`${base}/api/v1/auth/login`, JSON.stringify({ email: 'viewer@nascon.test', password: 'Nascon@26' }),
    { headers: { 'Content-Type': 'application/json' } });
  check(login, { 'login succeeds': r => r.status === 200 });
  return { token: login.json('token') };
}
export default function (data) {
  const params = { headers: { Authorization: `Bearer ${data.token}` } };
  for (const route of ['/health', '/api/v1/fixtures/highlights', '/api/v1/standings/league-alpha-1']) {
    const response = http.get(base + route, params);
    check(response, { 'read succeeds': r => r.status === 200 });
  }
  // Five devices, three requests per two seconds, below the shared-IP rate limit.
  sleep(2);
}
