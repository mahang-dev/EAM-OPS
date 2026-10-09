param([switch]$Integration)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
if(Test-Path '.env'){Get-Content '.env' | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}}
if($Integration){
 if(!$env:TEST_DB_URL){$env:TEST_DB_URL=$env:DB_URL.Replace('/eam_ops?','/eam_ops_test?')}
 if($env:TEST_DB_URL -notmatch '/eam_ops_test(?:\?|$)'){throw '集成测试只允许使用独立 eam_ops_test 数据库'}
 $env:DB_URL=$env:TEST_DB_URL;$env:SPRING_PROFILES_ACTIVE='test';$env:BOOTSTRAP_PASSWORD=$env:DEMO_PASSWORD;$env:EAM_MYSQL_IT='true'
}else{$env:EAM_MYSQL_IT='false'}
$jdk=Get-ChildItem .tools -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName}
$maven='./.tools/apache-maven-3.9.11/bin/mvn.cmd'
if(!(Test-Path $maven)){$maven='mvn.cmd'}
& $maven -f backend/pom.xml "-Dmaven.repo.local=$root/.tools/m2" test
exit $LASTEXITCODE
