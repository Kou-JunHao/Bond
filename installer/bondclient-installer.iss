; Bond 客户端安装脚本（BondClient / Bond 2.x）
; 编译： "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\BondClient-installer.iss
; 依赖：先执行 dotnet publish BondClient\Bond.csproj -c Release -r win-x64 --self-contained true -o publish\BondClient-win-x64

#define MyAppName "Bond"
#define MyAppVersion "2.1.0"
#define MyAppPublisher "Bond"
#define MyAppExeName "Bond.exe"
#define MyAppUrl "https://bond-admin.sg.skkk.uno"

[Setup]
AppId={{2F4C1E0A-9B7D-4A2E-8C61-5D7E3F9A1B24}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppUrl}
AppSupportURL={#MyAppUrl}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=no
AllowNoIcons=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\publish\installer
OutputBaseFilename=Bond-{#MyAppVersion}-win-x64-setup
SetupIconFile=..\BondClient\Assets\Icon.ico
UninstallDisplayIcon={app}\BondIcon.ico
UninstallDisplayName={#MyAppName} {#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
WizardImageFile=wizard.bmp
WizardSmallImageFile=wizard-small.bmp
ShowLanguageDialog=yes
LanguageDetectionMethod=uilanguage
DefaultDialogFontName=Microsoft YaHei UI
VersionInfoVersion={#MyAppVersion}.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} 客户端安装程序
VersionInfoProductName={#MyAppName}
VersionInfoCopyright=Copyright 2026 {#MyAppPublisher}
MinVersion=10.0.17763

[Languages]
Name: "chinesesimplified"; MessagesFile: "..\localsend\scripts\Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加选项:"; Languages: chinesesimplified
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional options:"; Languages: english
Name: "autostart"; Description: "开机自动启动 Bond（悬浮球常驻）"; GroupDescription: "附加选项:"; Languages: chinesesimplified; Flags: unchecked
Name: "autostart"; Description: "Start Bond automatically on Windows startup"; GroupDescription: "Additional options:"; Languages: english; Flags: unchecked

[Files]
Source: "..\publish\BondClient-win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\BondClient\Assets\Icon.ico"; DestDir: "{app}"; DestName: "BondIcon.ico"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\BondIcon.ico"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; Languages: chinesesimplified
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"; Languages: english
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\BondIcon.ico"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Bond"; ValueData: """{app}\{#MyAppExeName}"""; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent; Languages: chinesesimplified
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent; Languages: english

[UninstallDelete]
Type: filesandordirs; Name: "{app}\libvlc"

[Code]
procedure InitializeWizard;
begin
  WizardForm.Font.Name := 'Microsoft YaHei UI';
  WizardForm.Font.Size := 9;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    { 与客户端保持一致的用户数据目录 }
    ForceDirectories(ExpandConstant('{localappdata}\Bond'));
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\Bond');
    if DirExists(DataDir) then
    begin
      if ActiveLanguage = 'chinesesimplified' then
      begin
        { 默认按钮是「否」（保留数据），删除必须由用户主动点「是」 }
        if MsgBox('是否连同登录状态、偏好设置与上传队列一起删除？' + #13#10 + #13#10 +
                  '默认保留。选“是”会删除 ' + DataDir + '（不可恢复）。', mbConfirmation,
                  MB_YESNO or MB_DEFBUTTON2) = IDYES then
          DelTree(DataDir, True, True, True);
      end
      else
      begin
        if MsgBox('Also delete sign-in state, preferences and upload queue?' + #13#10 + #13#10 +
                  'Default keeps them. Choosing Yes removes ' + DataDir + ' (cannot be undone).', mbConfirmation,
                  MB_YESNO or MB_DEFBUTTON2) = IDYES then
          DelTree(DataDir, True, True, True);
      end;
    end;
  end;
end;
