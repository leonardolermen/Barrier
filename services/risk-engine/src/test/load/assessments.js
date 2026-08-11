import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// Ramp em degraus até o joelho. Cada degrau segura tempo suficiente para o backlog
// assíncrono (AssessmentProcessor, poll de 2s) mostrar se drena ou se acumula.
export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '60s', target: 10 },
        { duration: '30s', target: 50 },
        { duration: '60s', target: 50 },
        { duration: '30s', target: 150 },
        { duration: '60s', target: 150 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'submit_duration': ['p(95)<500', 'p(99)<1500'],
    'get_duration': ['p(95)<300'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const BASE = __ENV.BASE_URL;
const KEY = __ENV.API_KEY;

const submitDuration = new Trend('submit_duration', true);
const getDuration = new Trend('get_duration', true);
const submitOk = new Rate('submit_ok');

const params = {
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
  tags: { name: 'assessments' },
};

// CPF com dígitos verificadores válidos — o domínio (Cpf.java) rejeita qualquer outra coisa,
// e um teste que só mede caminho de erro 400 não mede nada.
function randomCpf() {
  const n = [];
  for (let i = 0; i < 9; i++) n.push(Math.floor(Math.random() * 10));
  n.push(checkDigit(n, 10));
  n.push(checkDigit(n, 11));
  return n.join('');
}

function checkDigit(digits, startWeight) {
  let sum = 0;
  let weight = startWeight;
  for (let i = 0; i < startWeight - 1; i++) sum += digits[i] * weight--;
  const mod = sum % 11;
  return mod < 2 ? 0 : 11 - mod;
}

const FIRST = ['Ana', 'Bruno', 'Carla', 'Diego', 'Elisa', 'Fabio', 'Gabriela', 'Heitor'];
const LAST = ['Silva', 'Souza', 'Oliveira', 'Pereira', 'Costa', 'Almeida', 'Ramos'];

function randomName() {
  const p = (a) => a[Math.floor(Math.random() * a.length)];
  return `${p(FIRST)} ${p(LAST)} ${p(LAST)}`;
}

export default function () {
  const payload = JSON.stringify({
    documentType: 'CPF',
    document: randomCpf(),
    name: randomName(),
  });

  const res = http.post(`${BASE}/v1/assessments`, payload, params);
  submitDuration.add(res.timings.duration);
  const ok = check(res, { 'submit 202': (r) => r.status === 202 });
  submitOk.add(ok);

  // 1 em 5 consulta o resultado logo em seguida — é o que um cliente real faz enquanto
  // espera o processamento assíncrono terminar.
  if (ok && Math.random() < 0.2) {
    const id = res.json('id');
    const g = http.get(`${BASE}/v1/assessments/${id}`, params);
    getDuration.add(g.timings.duration);
    check(g, { 'get 200': (r) => r.status === 200 });
  }
}
