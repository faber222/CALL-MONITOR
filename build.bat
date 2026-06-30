@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  build.bat - gera o CallMonitor standalone (JRE + ffmpeg embutidos)
REM  e, em seguida, o instalador wizard via Inno Setup.
REM
REM  No JDK 24+ o jpackage nao usa mais Inno Setup para o .exe (passou a
REM  exigir WiX). Para evitar a dor de cabeca do WiX, aqui o jpackage so
REM  monta a app-image (sem ferramenta externa) e o Inno Setup faz o wizard.
REM
REM  Pre-requisitos (so na sua maquina):
REM    1. JDK 21+ instalado, com "java" acessivel no terminal
REM    2. Inno Setup 6 instalado (https://jrsoftware.org/isdl.php)
REM    3. ffmpeg.exe (build estatico) nesta pasta
REM       (https://www.gyan.dev/ffmpeg/builds/ -> essentials)
REM    4. CallMonitor.ico        (icone do app)
REM    5. callmonitor-icon.png   (icone da janela)
REM    6. CallMonitor.java
REM    7. CallMonitor.iss        (script do Inno Setup, vai junto)
REM ============================================================

set APP_NAME=CallMonitor
set APP_VERSION=1.0.0
set MAIN_CLASS=CallMonitor
set SRC=CallMonitor.java
set ICON=CallMonitor.ico
set ICON_PNG=callmonitor-icon.png
set FFMPEG=ffmpeg.exe
set ISS=CallMonitor.iss

set BUILD=build
set CLASSES=%BUILD%\classes
set INPUT=%BUILD%\input
set DIST=dist

REM ----- Descobre a pasta do JDK a partir do java em uso -----
echo [0/5] Localizando o JDK...
set "JH="
for /f "tokens=2 delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /i "java.home"') do set "JH=%%a"
for /f "tokens=* delims= " %%b in ("%JH%") do set "JH=%%b"
if not defined JH (
  echo  ERRO: nao localizei o JDK pelo "java" do terminal.
  goto :erro
)
set "JAVAC=%JH%\bin\javac.exe"
set "JAR=%JH%\bin\jar.exe"
set "JPKG=%JH%\bin\jpackage.exe"
if not exist "%JAR%"  ( echo  ERRO: jar.exe nao esta em %JH%\bin & goto :erro )
if not exist "%JPKG%" ( echo  ERRO: jpackage.exe nao esta em %JH%\bin & goto :erro )
echo       JDK: %JH%

if exist "%BUILD%" rmdir /s /q "%BUILD%"
if exist "%DIST%"  rmdir /s /q "%DIST%"
mkdir "%CLASSES%"
mkdir "%INPUT%"

echo [1/5] Compilando o codigo...
"%JAVAC%" -encoding UTF-8 -d "%CLASSES%" "%SRC%"
if errorlevel 1 goto :erro

echo       Embutindo o icone da janela no jar...
if exist "%ICON_PNG%" (
  copy /y "%ICON_PNG%" "%CLASSES%\callmonitor-icon.png" >nul
) else (
  echo  AVISO: %ICON_PNG% nao encontrado. O programa usara o icone padrao do Java.
)

echo [2/5] Gerando o jar...
"%JAR%" --create --file "%INPUT%\%APP_NAME%.jar" --main-class %MAIN_CLASS% -C "%CLASSES%" .
if errorlevel 1 goto :erro

echo [3/5] Embutindo o ffmpeg...
if not exist "%FFMPEG%" ( echo  ERRO: %FFMPEG% nao encontrado nesta pasta. & goto :erro )
copy /y "%FFMPEG%" "%INPUT%\ffmpeg.exe" >nul

echo [4/5] Montando a app-image (programa + JRE)...
"%JPKG%" ^
  --type app-image ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --input "%INPUT%" ^
  --main-jar "%APP_NAME%.jar" ^
  --main-class %MAIN_CLASS% ^
  --add-modules java.desktop ^
  --java-options "-Xmx2g" ^
  --icon "%ICON%" ^
  --dest "%DIST%"
if errorlevel 1 goto :erro

echo [5/5] Gerando o instalador com Inno Setup...
set "PF=%ProgramFiles%"
set "PF86=%ProgramFiles(x86)%"
set "LAP=%LOCALAPPDATA%"
set "ISCC="

REM 1) iscc no PATH
where iscc >nul 2>&1 && set "ISCC=iscc"

REM 2) locais de instalacao comuns (all-users e por-usuario)
if not defined ISCC for %%D in (
  "%PF86%\Inno Setup 6"
  "%PF%\Inno Setup 6"
  "%LAP%\Programs\Inno Setup 6"
  "%PF86%\Inno Setup 5"
  "%PF%\Inno Setup 5"
) do if not defined ISCC if exist "%%~D\ISCC.exe" set "ISCC=%%~D\ISCC.exe"

REM 3) caminho lido do registro do Windows
if not defined ISCC for %%K in (
  "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1"
  "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1"
  "HKLM\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1"
) do if not defined ISCC for /f "tokens=2,*" %%a in ('reg query %%K /v InstallLocation 2^>nul ^| findstr /i "InstallLocation"') do if exist "%%~bISCC.exe" set "ISCC=%%~bISCC.exe"

if not defined ISCC (
  echo.
  echo  Inno Setup nao encontrado no PATH nem no local padrao.
  echo  A app-image pronta ja esta em %DIST%\%APP_NAME%\ e roda standalone.
  echo  Para gerar o instalador, instale o Inno Setup 6 e:
  echo    - abra %ISS% no Inno Setup e clique em Compile, ou
  echo    - rode novamente este build.bat com o iscc no PATH.
  goto :fim
)

"%ISCC%" "%ISS%"
if errorlevel 1 goto :erro

echo.
echo ================================================
echo  Pronto.
echo  App standalone : %DIST%\%APP_NAME%\
echo  Instalador     : dist_installer\
echo ================================================
goto :fim

:erro
echo.
echo  Falhou. Veja a mensagem de erro acima.
exit /b 1

:fim
endlocal
