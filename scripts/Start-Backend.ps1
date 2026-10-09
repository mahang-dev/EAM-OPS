$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
if(Test-Path '.env'){Get-Content '.env' | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}}
$jdk=Get-ChildItem "$root/.tools" -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue | Select-Object -First 1
if($jdk){$env:JAVA_HOME=$jdk.FullName;$env:PATH="$env:JAVA_HOME/bin;$env:PATH"}
$maven="$root/.tools/apache-maven-3.9.11/bin/mvn.cmd"
if(!(Test-Path $maven)){$maven='mvn.cmd'}
& $maven -f backend/pom.xml "-Dmaven.repo.local=$root/.tools/m2" spring-boot:run
