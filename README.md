# STV Instalador (Android / Android TV)

App nativo em Kotlin para TV Box e Android TV. O cliente digita o código de ativação, o painel valida no servidor e só então libera o download do APK do STV, que é conferido por assinatura SHA-256 antes da instalação.

## Como compilar o APK gratuitamente (GitHub Actions)
1. Acesse a aba **Actions** neste repositório.
2. Selecione o workflow **Gerar APK STV Instalador**.
3. Clique em **Run workflow** (o APK também é compilado automaticamente a cada push).
4. Aguarde ~4 minutos até o término da compilação.
5. Na página da execução concluída, baixe o artefato **STV-Instalador-apk** contendo o arquivo `STV-Instalador.apk`.

## Configuração do Painel
O endereço do painel está definido em `app/build.gradle.kts` → `PANEL_URL` (padrão `https://app-claim-code-hub.lovable.app`).

## Contrato da API do Painel
O app envia uma requisição `POST /api/public/ativar`:
```json
{
  "code": "CODIGO123",
  "device_id": "9774d56d682e549c"
}
```

- **Liberado (HTTP 200):**
  ```json
  {"ok": true, "download_url": "https://...", "sha256": "hash64hex", "size": 12345}
  ```
- **Recusado (HTTP 4xx):**
  ```json
  {"ok": false, "reason": "invalido|expirado|bloqueado|usado", "message": "Mensagem opcional"}
  ```
