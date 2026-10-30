; Bond Client Installer Script
; Requires Inno Setup 6.x

#define MyAppName "Bond"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Bond Team"
#define MyAppExeName "BondClient.exe"
#define MyAppAssocName MyAppName + " File"
#define MyAppAssocExt ".bond"
#define MyAppAssocKey StringChange(MyAppAssocName, " ", "") + MyAppAssocExt

[Setup]
AppId={{B7A3F8D2-E5C1-4A9B-8D6F-2C3E5A7B9D1F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DisableDirPage=no
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=installer
OutputBaseFilename=BondSetup-v1.0.0
SetupIconFile=BondIcon.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=classic
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\BondIcon.ico
UninstallDisplayName={#MyAppName}
VersionInfoVersion={#MyAppVersion}.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} Installer
VersionInfoCopyright=Copyright 2026 {#MyAppPublisher}
WizardImageFile=wizard.bmp
WizardSmallImageFile=wizard-small.bmp
ShowLanguageDialog=yes
LanguageDetectionMethod=uilanguage
DefaultDialogFontName=Microsoft YaHei UI
DirExistsWarning=no

[Languages]
Name: "chinesesimplified"; MessagesFile: "..\localsend\scripts\Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加选项:"; Languages: chinesesimplified
Name: "autostart"; Description: "开机自动启动 Bond"; GroupDescription: "附加选项:"; Languages: chinesesimplified; Flags: unchecked
Name: "firewall"; Description: "添加防火墙规则（局域网设备发现所需）"; GroupDescription: "附加选项:"; Languages: chinesesimplified; Flags: checkedonce
Name: "desktopicon"; Description: "Create desktop shortcut"; GroupDescription: "Additional options:"; Languages: english
Name: "autostart"; Description: "Start Bond on Windows startup"; GroupDescription: "Additional options:"; Languages: english; Flags: unchecked
Name: "firewall"; Description: "Add firewall rules (required for LAN device discovery)"; GroupDescription: "Additional options:"; Languages: english; Flags: checkedonce

[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\BondIcon.ico"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; Languages: chinesesimplified
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"; Languages: english
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\BondIcon.ico"; Tasks: desktopicon

[Registry]
; Auto-start on boot
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Bond"; ValueData: """{app}\{#MyAppExeName}"""; Tasks: autostart; Flags: uninsdeletevalue
; File association
Root: HKA; Subkey: "Software\Classes\{#MyAppAssocExt}\OpenWithProgids"; ValueType: string; ValueName: "{#MyAppAssocKey}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\{#MyAppAssocKey}"; ValueType: string; ValueName: ""; ValueData: "{#MyAppAssocName}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\{#MyAppAssocKey}\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\{#MyAppExeName},0"
Root: HKA; Subkey: "Software\Classes\{#MyAppAssocKey}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#MyAppExeName}"" ""%1"""

[Run]
; Add firewall rules
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Bond UDP Discovery"" dir=in action=allow protocol=UDP localport=19851 profile=any enable=yes"; Tasks: firewall; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Bond Multicast"" dir=in action=allow protocol=UDP localport=19852 profile=any enable=yes"; Tasks: firewall; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Bond TCP Transfer"" dir=in action=allow protocol=TCP localport=19850 profile=any enable=yes"; Tasks: firewall; Flags: runhidden
; Launch after install
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent; Languages: chinesesimplified
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent; Languages: english

[UninstallRun]
; Remove firewall rules
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Bond UDP Discovery"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Bond Multicast"""; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Bond TCP Transfer"""; Flags: runhidden

[Code]
const
  BondBg = $F5F9FA;
  BondSurface = $E8EFF3;
  BondPrimary = $131414;
  BondSecondary = $636A6F;

procedure InitializeWizard;
begin
  WizardForm.Color := BondBg;
  WizardForm.Font.Name := 'Microsoft YaHei UI';
  WizardForm.Font.Size := 9;

  if Assigned(WizardForm.SelectTasksPage) then
  begin
    WizardForm.SelectTasksPage.Color := BondBg;
  end;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpWelcome then
  begin
    WizardForm.WelcomeLabel1.Font.Color := BondPrimary;
    WizardForm.WelcomeLabel2.Font.Color := BondSecondary;
  end
  else if CurPageID = wpFinished then
  begin
    WizardForm.FinishedHeadingLabel.Font.Color := BondPrimary;
    WizardForm.FinishedLabel.Font.Color := BondSecondary;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    CreateDir(ExpandConstant('{localappdata}\BondClient'));
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Lang: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    Lang := ActiveLanguage;
    if Lang = 'chinesesimplified' then
    begin
      if MsgBox('是否保留用户数据（配置、传输记录等）？', mbConfirmation, MB_YESNO) = IDNO then
        DelTree(ExpandConstant('{localappdata}\BondClient'), True, True, True);
    end
    else
    begin
      if MsgBox('Do you want to keep user data (settings, transfer history, etc.)?', mbConfirmation, MB_YESNO) = IDNO then
        DelTree(ExpandConstant('{localappdata}\BondClient'), True, True, True);
    end;
  end;
end;
