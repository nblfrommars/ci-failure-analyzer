param(
    [switch]$SkipDocker
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# Nạp key/token từ .env.local nếu có; không in giá trị ra màn hình.
$localEnvFile = Join-Path $projectRoot ".env.local"
if (Test-Path -LiteralPath $localEnvFile) {
    foreach ($line in Get-Content -LiteralPath $localEnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$' -and $line -notmatch '^\s*#') {
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

# Dự án yêu cầu JDK 21; chỉ ảnh hưởng tới terminal/process con này.
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "Không tìm thấy JDK 21 tại $env:JAVA_HOME"
}

# Phải trùng với CFA_TESTGEN_INTERNAL_TOKEN trong OpenClaw testgen-mcp.
if ([string]::IsNullOrWhiteSpace($env:CFA_TESTGEN_INTERNAL_TOKEN)) {
    throw "Thiếu CFA_TESTGEN_INTERNAL_TOKEN. Đặt token MCP trước khi chạy script. Không commit token vào repo."
}

$env:CFA_TESTGEN_OPEN_PR_URL = "http://127.0.0.1:8080/internal/testgen/open-pr"
$env:SPRING_APPLICATION_JSON = (@{
    server = @{ port = 8080 }
    spring = @{
        rabbitmq = @{
            host = "127.0.0.1"
            port = 5672
            username = "cfa_app"
            password = "cfa_app_password"
            "virtual-host" = "/"
        }
    }
} | ConvertTo-Json -Compress)

if (-not $SkipDocker) {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up -d thất bại"
    }
}

Write-Host "Spring Boot: http://127.0.0.1:8080"
Write-Host "RabbitMQ: cfa_app@127.0.0.1:5672"
$llmProfile = if ([string]::IsNullOrWhiteSpace($env:LLM_PROFILE)) { "local" } else { $env:LLM_PROFILE }
Write-Host "LLM profile: $llmProfile"

& mvn.cmd spring-boot:run
exit $LASTEXITCODE
