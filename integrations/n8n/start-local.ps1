param(
  [string]$N8nPath = "$env:USERPROFILE/.codex/tools/todentro-n8n/node_modules/n8n/bin/n8n",
  [string]$DataFolder = "$env:USERPROFILE/.codex/tools/todentro-n8n/local",
  [int]$DbPort = 55432,
  [string]$DbUser = 'pelada',
  [string]$DbName = 'todentro_n8n',
  [int]$Port = 5679
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $N8nPath)) { throw 'Instale n8n@2.42.5 em uma pasta local ou informe -N8nPath.' }
New-Item -ItemType Directory -Force -Path $DataFolder | Out-Null
$env:DB_TYPE = 'postgresdb'
$env:DB_POSTGRESDB_HOST = '127.0.0.1'
$env:DB_POSTGRESDB_PORT = "$DbPort"
$env:DB_POSTGRESDB_DATABASE = $DbName
$env:DB_POSTGRESDB_USER = $DbUser
# DB_POSTGRESDB_PASSWORD pode ser configurada no terminal; nunca salvar no repositório.
$env:N8N_USER_FOLDER = $DataFolder
$env:N8N_LISTEN_ADDRESS = '127.0.0.1'
$env:N8N_HOST = 'localhost'
$env:N8N_PORT = "$Port"
$env:N8N_RUNNERS_BROKER_PORT = "$($Port + 1)"
$env:N8N_PROTOCOL = 'http'
$env:N8N_SECURE_COOKIE = 'false'
$env:N8N_DIAGNOSTICS_ENABLED = 'false'
$env:N8N_VERSION_NOTIFICATIONS_ENABLED = 'false'
$env:N8N_PERSONALIZATION_ENABLED = 'false'
$env:EXECUTIONS_DATA_SAVE_ON_ERROR = 'none'
$env:EXECUTIONS_DATA_SAVE_ON_SUCCESS = 'none'
$env:EXECUTIONS_DATA_SAVE_MANUAL_EXECUTIONS = 'false'
$env:GENERIC_TIMEZONE = 'America/Sao_Paulo'
$process = Start-Process -FilePath (Get-Command node).Source -ArgumentList @("`"$N8nPath`"", 'start') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$DataFolder/server.log" -RedirectStandardError "$DataFolder/server-error.log"
Write-Output "n8n local iniciado: http://localhost:$Port (processo $($process.Id)). Os fluxos importados permanecem desativados."
