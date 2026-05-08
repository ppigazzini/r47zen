@echo off
setlocal

set "APP_DIR=%~dp0"
if "%APP_DIR:~-1%"=="\" set "APP_DIR=%APP_DIR:~0,-1%"

set "APP_EXE=%~n0.exe"
if not exist "%APP_DIR%\%APP_EXE%" (
	echo Missing packaged executable: %APP_DIR%\%APP_EXE%
	exit /b 1
)

cd /d "%APP_DIR%"

set "PATH=%APP_DIR%;%PATH%"
set "GTK_EXE_PREFIX=%APP_DIR%"
set "GTK_DATA_PREFIX=%APP_DIR%"
set "GTK_PATH=%APP_DIR%\lib\gtk-3.0"
if defined XDG_DATA_DIRS (
	set "XDG_DATA_DIRS=%APP_DIR%\share;%XDG_DATA_DIRS%"
) else (
	set "XDG_DATA_DIRS=%APP_DIR%\share"
)
set "GSETTINGS_SCHEMA_DIR=%APP_DIR%\share\glib-2.0\schemas"

if exist "%APP_DIR%\lib\gio\modules" set "GIO_MODULE_DIR=%APP_DIR%\lib\gio\modules"

set "GTK_IM_MODULE_DIR="
for /d %%D in ("%APP_DIR%\lib\gtk-3.0\*") do (
	if not defined GTK_IM_MODULE_DIR if exist "%%~fD\immodules" set "GTK_IM_MODULE_DIR=%%~fD"
)
if defined GTK_IM_MODULE_DIR set "GTK_IM_MODULE_FILE=%GTK_IM_MODULE_DIR%\immodules.cache"

set "GDK_PIXBUF_BINARY_DIR="
for /d %%D in ("%APP_DIR%\lib\gdk-pixbuf-2.0\*") do (
	if not defined GDK_PIXBUF_BINARY_DIR if exist "%%~fD\loaders" set "GDK_PIXBUF_BINARY_DIR=%%~fD"
)
if defined GDK_PIXBUF_BINARY_DIR set "GDK_PIXBUF_MODULEDIR=%GDK_PIXBUF_BINARY_DIR%\loaders"
if defined GDK_PIXBUF_BINARY_DIR set "GDK_PIXBUF_MODULE_FILE=%GDK_PIXBUF_BINARY_DIR%\loaders.cache"

if exist "%APP_DIR%\glib-compile-schemas.exe" if exist "%GSETTINGS_SCHEMA_DIR%" (
	"%APP_DIR%\glib-compile-schemas.exe" "%GSETTINGS_SCHEMA_DIR%" >nul 2>&1
)

if defined GIO_MODULE_DIR if exist "%APP_DIR%\gio-querymodules.exe" (
	"%APP_DIR%\gio-querymodules.exe" "%GIO_MODULE_DIR%" >nul 2>&1
)

if defined GTK_IM_MODULE_DIR call :refresh_cache "%APP_DIR%\gtk-query-immodules-3.0.exe" "%GTK_IM_MODULE_FILE%" "%GTK_IM_MODULE_DIR%\immodules"
if defined GDK_PIXBUF_BINARY_DIR call :refresh_cache "%APP_DIR%\gdk-pixbuf-query-loaders.exe" "%GDK_PIXBUF_MODULE_FILE%" "%GDK_PIXBUF_MODULEDIR%"

start "" "%APP_DIR%\%APP_EXE%" "--portrait" %*
exit /b 0

:refresh_cache
if not exist "%~1" goto :eof
if not exist "%~3" goto :eof

"%~1" > "%~2.tmp" 2>nul
for %%F in ("%~2.tmp") do if %%~zF gtr 0 move /y "%~2.tmp" "%~2" >nul
if exist "%~2.tmp" del "%~2.tmp" >nul 2>&1
goto :eof

rem To see the options, use c47.exe --help

rem Examples of command line parameters
rem start "" "c47.exe" "--functionkeys"
rem start "" "c47.exe" "--auto"
rem start "" "c47.exe" "--portrait" "--functionkeys"
rem start "" "c47.exe" "--landscape" "--functionkeys"
rem start "" "c47.exe" "--auto" "--functionkeys"
rem start "" "c47.exe" "--auto" "--functionkeys"
rem start "" "c47.exe" "--auto" "--functionkeys" "--background" "R47custom.png"