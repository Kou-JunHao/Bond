# 内嵌字体：MiSans（子集）

Bond 客户端（Avalonia）在 `App.axaml` 里通过 `BondUIFont` 使用本目录字体，全局替换系统默认中文字体。

## 来源与版本

- 字体：**MiSans**（小米 HyperOS 系统字体），官方页面 <https://hyperos.mi.com/font/>
- 下载包：`https://hyperos.mi.com/font-download/MiSans.zip`（227,880,072 B）
- 取用字重：`MiSans-Regular.ttf`（8,122,324 B）、`MiSans-Medium.ttf`（8,103,840 B）
- 取得日期：2026-10-27

## 子集方式

用 `fontTools` 的 `pyftsubset` 按字符集裁剪，只保留本应用实际需要的字形：

```bash
python -m fontTools.subset MiSans-Regular.ttf \
  --text-file=misans-chars.txt \
  --layout-features="kern,ccmp,locl,mark,mkmk,rvrn" \
  --name-IDs="*" --recalc-bounds \
  --drop-tables+=DSIG,FFTM \
  --output-file=MiSans-Regular.ttf
```

- 字符集 6,945 字符 = `BondClient/**/*.axaml|*.cs|*.xaml` 里出现的全部非 ASCII 字符（1,062）
  ∪ GB2312 全量汉字（6,763）∪ ASCII 可见字符 ∪ 字体支持的常用标点（→ ← ↑ ↓ ✓ ✕ • … 等）。
- 子集后体积：`MiSans-Regular.ttf` **1,612,796 B**、`MiSans-Medium.ttf` **1,608,944 B**
  （合计约 3.2 MB；原字体合计约 16.2 MB）。
- 两个文件用 fontTools 重写 name 表：family 统一为 `MiSans`，subfamily 为 `Regular` / `Medium`，
  PS 名为 `MiSans-Regular` / `MiSans-Medium`，OS/2 字重 400 / 500，使 Avalonia 能把两份文件
  识别为同一字族的不同字重（`FontWeight="Medium"` 走 Medium 文件）。
- 源字体本身不含 `↔ ⌘ ▶ ◀ ✅ ✔ ✖ ❌` 等 8 个符号；其中只有 `⌘` 出现在 UI 文案里，
  已改写为 `Cmd`（macOS WebDAV 接入指南），其余仅存在于代码注释，不渲染。

## 许可

- 官方许可：**MiSans 字体知识产权许可协议**（小米免费商用授权）。
  原文 PDF：<https://hyperos.mi.com/font-download/MiSans字体知识产权许可协议.pdf>
- 本目录 `LICENSE-MiSans.txt` 是从该 PDF 直接提取的许可全文（另有 `LICENSE-MiSans.pdf` 原件留档）。
- 本字体**仅随本应用嵌入分发**，不做单独再分发，也不作为字体文件对外提供下载。

## 使用位置

- `BondClient/App.axaml`：`<FontFamily x:Key="BondUIFont">avares://Bond/Assets/fonts#MiSans, Microsoft YaHei UI, Segoe UI</FontFamily>`
  （逗号后代为系统回退链，用于 MiSans 子集里没有的 emoji 等字符）。
- `BondClient/Styles/Typography.axaml`：`Window` 与 `TextBlock` 的默认 `FontFamily` 均指向 `BondUIFont`。
- 文件通过 `Bond.csproj` 现有的 `<AvaloniaResource Include="Assets\**" ...>` 打包（`avares://Bond/Assets/fonts`）。
