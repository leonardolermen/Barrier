#!/usr/bin/env python3
"""Receptor local de webhooks do Barrier, para teste ponta a ponta via ngrok.

Sobe um HTTP server burro que aceita o callback do `webhook-api`, **verifica a assinatura
HMAC** e imprime o envelope formatado. Verificar a assinatura aqui é o ponto: sem isso o
teste prova que o POST chegou, não que ele é autêntico — e é justamente a assinatura que o
parceiro vai ter de implementar do lado dele.

Uso:

    export BARRIER_WEBHOOK_SECRET="<segredo devolvido no PUT /v1/webhook-endpoints/{tenant}>"
    python3 tools/webhook-receiver.py            # porta 9000

Sem o segredo no ambiente, o receptor ainda aceita e imprime o payload, mas marca a
assinatura como NÃO VERIFICADA — útil para o primeiro contato, inútil como teste.

Este processo é o que o ngrok expõe à internet. Ele é deliberadamente burro: não lê
arquivo, não executa nada do payload, não fala com o banco. O que chega aqui vira texto na
tela e nada mais.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("BARRIER_WEBHOOK_PORT", "9000"))
SECRET = os.environ.get("BARRIER_WEBHOOK_SECRET", "")

SIGNATURE_HEADER = "X-Barrier-Signature"
PREVIOUS_SIGNATURE_HEADER = "X-Barrier-Signature-Previous"
EVENT_ID_HEADER = "X-Barrier-Event-Id"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def expected_signature(body: bytes, secret: str) -> str:
    """Mesmo esquema do `HmacSigner`: HMAC-SHA256 do corpo cru, prefixado por `sha256=`."""
    digest = hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    return f"sha256={digest}"


def verify(body: bytes, received: str | None, previous: str | None) -> tuple[str, str]:
    """Devolve (rótulo, cor). Durante a janela de rotação, a entrega leva duas assinaturas."""
    if not SECRET:
        return "NÃO VERIFICADA (BARRIER_WEBHOOK_SECRET ausente)", YELLOW
    if not received:
        return f"AUSENTE (sem header {SIGNATURE_HEADER})", RED

    expected = expected_signature(body, SECRET)
    # compare_digest e não `==`: comparação de assinatura vaza informação por tempo, e um
    # receptor de exemplo que ensina o jeito errado é pior que nenhum exemplo.
    if hmac.compare_digest(expected, received):
        return "VÁLIDA", GREEN
    if previous and hmac.compare_digest(expected, previous):
        return "VÁLIDA (segredo anterior — janela de rotação)", GREEN
    return "INVÁLIDA — o corpo não bate com o segredo configurado", RED


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 (assinatura da stdlib)
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)

        label, color = verify(
            body,
            self.headers.get(SIGNATURE_HEADER),
            self.headers.get(PREVIOUS_SIGNATURE_HEADER),
        )

        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        print(f"\n{DIM}{'─' * 78}{RESET}")
        print(f"{now}  POST {self.path}")
        print(f"  event-id : {self.headers.get(EVENT_ID_HEADER, '(ausente)')}")
        print(f"  assinatura: {color}{label}{RESET}")

        try:
            print(json.dumps(json.loads(body), indent=2, ensure_ascii=False))
        except json.JSONDecodeError:
            print(f"  corpo não-JSON ({len(body)} bytes): {body[:400]!r}")

        # 200 sempre: exercitar o retry do webhook-api é outro teste, feito derrubando este
        # processo — não devolvendo erro de mentira aqui.
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"received":true}')

    def do_GET(self) -> None:  # noqa: N802
        """Só para conferir que o túnel está de pé antes de disparar uma avaliação."""
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write("receptor de webhook do Barrier — no ar\n".encode("utf-8"))

    def log_message(self, fmt: str, *args: object) -> None:
        """Silencia o log padrão da stdlib: a impressão do handler já diz mais e melhor."""


def main() -> int:
    # Sem isto, rodar o receptor com `> arquivo.log` ou `| tee` engole a saída: o Python troca
    # para buffer de bloco quando stdout não é terminal, e o teste parece não estar recebendo
    # nada. Reconfigurar é mais simples que exigir `python3 -u` de quem for usar.
    sys.stdout.reconfigure(line_buffering=True)

    print(f"Receptor de webhook do Barrier em http://localhost:{PORT}")
    if SECRET:
        print(f"Assinatura: {GREEN}verificação LIGADA{RESET}")
    else:
        print(
            f"Assinatura: {YELLOW}verificação DESLIGADA{RESET} — exporte BARRIER_WEBHOOK_SECRET"
            " com o segredo devolvido no registro do endpoint."
        )
    print("Ctrl-C para parar.\n")
    try:
        HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nencerrado.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
