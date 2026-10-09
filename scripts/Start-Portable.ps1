param([switch]$InitializeOnly)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$runtime=Join-Path $root '.runtime'
New-Item -ItemType Directory -Force $runtime | Out-Null
$mysql=(Get-ChildItem (Join-Path $root '.tools') -Directory -Filter 'mysql-*' | Select-Object -First 1).FullName
if(!$mysql){throw '请先安装 MySQL 或下载便携版本到 .tools。'}
$data=Join-Path $runtime 'mysql-data'
if(!(Test-Path $data)){
 & "$mysql/bin/mysqld.exe" --initialize-insecure "--basedir=$mysql" "--datadir=$data" --console 2> "$runtime/mysql-init.log"
 if($LASTEXITCODE -ne 0){throw 'MySQL 初始化失败，请检查 .runtime/mysql-init.log'}
}
if(!(Test-NetConnection 127.0.0.1 -Port 13306 -InformationLevel Quiet -WarningAction SilentlyContinue)){
 Start-Process -FilePath "$mysql/bin/mysqld.exe" -ArgumentList "--basedir=$mysql","--datadir=$data",'--port=13306','--bind-address=127.0.0.1','--mysqlx=0','--console' -WindowStyle Hidden -RedirectStandardOutput "$runtime/mysql.log" -RedirectStandardError "$runtime/mysql-error.log"
}
for($i=0;$i -lt 30;$i++){& "$mysql/bin/mysqladmin.exe" --host=127.0.0.1 --port=13306 --user=root ping 2>$null | Out-Null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Seconds 1}
if(!(Test-Path '.env')){
 $secret=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
 $password='Eam!'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))
 $dbpass=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20))
 @("DB_URL=jdbc:mysql://127.0.0.1:13306/eam_ops?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai","DB_USER=eam","DB_PASSWORD=$dbpass","REDIS_HOST=127.0.0.1","REDIS_PORT=16379","JWT_SECRET=$secret","DEMO_PASSWORD=$password","SPRING_PROFILES_ACTIVE=demo") | Set-Content -LiteralPath '.env' -Encoding utf8
}
Get-Content '.env' | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}
if(!(Test-Path "$runtime/db-ready")){
 $sql="CREATE DATABASE IF NOT EXISTS eam_ops CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'eam'@'localhost' IDENTIFIED BY '$env:DB_PASSWORD'; GRANT ALL ON eam_ops.* TO 'eam'@'localhost'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$env:DB_PASSWORD';"
 $sql | & "$mysql/bin/mysql.exe" --host=127.0.0.1 --port=13306 --user=root
 if($LASTEXITCODE -ne 0){throw '数据库账号初始化失败'}
 New-Item "$runtime/db-ready" -ItemType File | Out-Null
}
if(!(Test-NetConnection 127.0.0.1 -Port 16379 -InformationLevel Quiet -WarningAction SilentlyContinue)){
 Start-Process -FilePath "$root/.tools/redis/redis-server.exe" -ArgumentList '--bind','127.0.0.1','--port','16379','--dir',$runtime -WindowStyle Hidden -RedirectStandardOutput "$runtime/redis.log" -RedirectStandardError "$runtime/redis-error.log"
}
if($InitializeOnly){Write-Host 'MySQL 与 Redis 已准备；本机演示密码保存在 .env 的 DEMO_PASSWORD。';exit}
& "$PSScriptRoot/Start-Backend.ps1"
