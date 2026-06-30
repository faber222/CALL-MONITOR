; CallMonitor.iss - script do Inno Setup
; Empacota a app-image gerada pelo jpackage (dist\CallMonitor) num
; instalador wizard, com escolha de pasta, atalhos e desinstalador.
; O programa ja vem com JRE e ffmpeg embutidos, sem precisar de Java no PC.

#define AppName "CallMonitor"
#define AppVersion "1.0.0"
#define AppPublisher "Intelbras"
#define AppExe "CallMonitor.exe"

[Setup]
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=dist_installer
OutputBaseFilename={#AppName}-Setup-{#AppVersion}
SetupIconFile=CallMonitor.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na area de trabalho"; GroupDescription: "Atalhos adicionais:"

[Files]
; Copia tudo que o jpackage gerou (exe + runtime + app com jar e ffmpeg)
Source: "dist\{#AppName}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Desinstalar {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Abrir o {#AppName} agora"; Flags: nowait postinstall skipifsilent
