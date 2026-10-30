using System.Globalization;
using System.Text;
using SkiaSharp;
using Svg.Skia;

namespace Bond.Tools.IconGen;

/// <summary>
/// task-148 / task-149：把 BondClient/Assets/logo.svg 渲染成白底圆角多尺寸应用图标。
/// 产物：BondClient/Assets/Icon.ico（PNG 大尺寸 + 32bpp BMP 小尺寸）、logo-256.png、logo-256.svg。
/// 圆角：半径 = 边长 22%（256 → 56），四角 alpha=0，不做黑/白补角。
/// 用法：dotnet run --project tools/IconGen [仓库根目录]
/// </summary>
internal static class Program
{
    // ICO 里放多档尺寸：Windows 会挑最接近的；16/24/32 另存小图便于任务栏/托盘取用。
    private static readonly int[] IcoSizes = [256, 128, 96, 64, 48, 40, 32, 24, 20, 16];

    // 小尺寸用 32bpp BMP（Windows 经典做法，浅色背景上更锐利）；
    // 大尺寸用 PNG 压缩。两者 Windows Vista+ 都支持。
    private static readonly int[] BmpSizes = [48, 32, 24, 20, 16];

    // 四周留白 8%（256 尺寸下约 20px，与工单要求一致）。
    private const double PaddingRatio = 0.08;

    // 圆角半径 = 边长 22%（256px → 56px），四角透明。
    private const double CornerRadiusRatio = 0.22;

    // 自检导出的小尺寸（从 ICO 里解回 PNG，供 read_image 人工核对）。
    private static readonly int[] CheckSizes = [32, 16];

    private static int Main(string[] args)
    {
        try
        {
            var repoRoot = args.Length > 0 ? Path.GetFullPath(args[0]) : FindRepoRoot();
            var assetsDir = Path.Combine(repoRoot, "BondClient", "Assets");
            var svgPath = Path.Combine(assetsDir, "logo.svg");
            if (!File.Exists(svgPath))
            {
                Console.Error.WriteLine($"找不到输入 SVG：{svgPath}");
                return 1;
            }

            using var svg = new SKSvg();
            var picture = svg.Load(svgPath);
            if (picture is null)
            {
                Console.Error.WriteLine("Svg.Skia 未能解析 logo.svg（Picture 为 null）。");
                return 2;
            }

            var cull = picture.CullRect;
            Console.WriteLine($"svg cull = {cull.Width:0.##} x {cull.Height:0.##}（left={cull.Left:0.##}, top={cull.Top:0.##}）");

            // 每个尺寸都直接矢量光栅化（比放大缩小更锐利，且小尺寸不会糊成一团）。
            using (var logo256 = RenderAt(picture, cull, 256, boostContrast: false))
            {
                var pngPath = Path.Combine(assetsDir, "logo-256.png");
                SavePng(logo256, pngPath);
                VerifyRounded(logo256, "logo-256.png");
            }

            // Icon.ico：大尺寸 PNG 压缩、小尺寸 32bpp BMP（均含 alpha），符合 Vista+ 的 ICO 约定。
            var entries = new List<(int Size, byte[] Data, bool IsPng)>(IcoSizes.Length);
            foreach (var size in IcoSizes)
            {
                using var scaled = RenderAt(picture, cull, size, boostContrast: size <= 32);
                var useBmp = BmpSizes.Contains(size);
                var bytes = useBmp ? EncodeBmp32(scaled) : EncodePng(scaled);
                entries.Add((size, bytes, !useBmp));
            }

            var icoPath = Path.Combine(assetsDir, "Icon.ico");
            WriteIco(icoPath, entries);
            Console.WriteLine($"Icon.ico = {new FileInfo(icoPath).Length:n0} 字节");
            foreach (var (size, bytes, isPng) in entries)
            {
                Console.WriteLine($"  {size,3}x{size,-3} {(isPng ? "png" : "bmp"),3} {bytes.Length,7:n0} 字节");
            }

            // 自检素材：把 ICO 里的 32x32、16x16 原样导出成 PNG，供人工/Copilot 视觉核对。
            foreach (var checkSize in CheckSizes)
            {
                var checkEntry = entries.First(e => e.Size == checkSize);
                var checkPath = Path.Combine(Path.GetTempPath(), $"task148-ico-{checkSize}.png");
                using var decoded = DecodeIcoEntry(checkEntry.Data, checkEntry.IsPng);
                SavePng(decoded, checkPath);
                VerifyRounded(decoded, $"{checkSize} 条目");
            }

            // logo-256.svg：256 画布 + 白底圆角 rect（rx/ry = 22%），原 logo 内容按 8% 留白缩放居中。
            WriteRoundedSvg(Path.Combine(assetsDir, "logo-256.svg"), File.ReadAllText(svgPath), cull);

            Console.WriteLine("完成。");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex);
            return 3;
        }
    }

    private static int CornerRadiusPx(int size)
        => (int)Math.Round(size * CornerRadiusRatio, MidpointRounding.AwayFromZero);

    private static SKPath CreateRoundedRect(int size, int radius)
    {
        var path = new SKPath();
        path.AddRoundRect(new SKRect(0, 0, size, size), radius, radius);
        return path;
    }

    private static SKBitmap RenderAt(SKPicture picture, SKRect cull, int size, bool boostContrast)
    {
        var bitmap = new SKBitmap(new SKImageInfo(size, size, SKColorType.Rgba8888, SKAlphaType.Premul));
        using (var canvas = new SKCanvas(bitmap))
        {
            // 四角透明（不做白/黑补角）。
            canvas.Clear(SKColors.Transparent);

            var radius = CornerRadiusPx(size);
            using var rounded = CreateRoundedRect(size, radius);
            using (var white = new SKPaint
            {
                Color = SKColors.White,
                IsAntialias = true,
                Style = SKPaintStyle.Fill,
            })
            {
                canvas.DrawPath(rounded, white);
            }

            var content = size * (1 - (2 * PaddingRatio));
            var longest = Math.Max(cull.Width, cull.Height);
            if (longest > 0)
            {
                var scale = (float)(content / longest);
                var drawWidth = cull.Width * scale;
                var drawHeight = cull.Height * scale;
                var offsetX = (float)(((size - drawWidth) / 2) - (cull.Left * scale));
                var offsetY = (float)(((size - drawHeight) / 2) - (cull.Top * scale));

                canvas.Save();
                // 夹到圆角里，避免抗锯齿边缘把叶片画到圆角外。
                canvas.ClipPath(rounded, SKClipOperation.Intersect, antialias: true);
                canvas.Translate(offsetX, offsetY);
                canvas.Scale(scale);
                canvas.DrawPicture(picture);
                canvas.Restore();
            }

            canvas.Flush();
        }

        if (boostContrast)
        {
            BoostContrast(bitmap);
        }

        return bitmap;
    }

    private static void BoostContrast(SKBitmap bitmap)
    {
        // 白底上的橙色风车：把非白像素的 RGB 稍微压深，避免缩小后被白底冲淡（不改形状）。
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                var c = bitmap.GetPixel(x, y);
                if (c.Alpha == 0)
                {
                    continue;
                }

                var whiteness = Math.Min(c.Red, Math.Min(c.Green, c.Blue));
                if (whiteness >= 250)
                {
                    continue;
                }

                const float factor = 0.94f;
                bitmap.SetPixel(x, y, new SKColor(
                    (byte)Math.Clamp(c.Red * factor, 0, 255),
                    (byte)Math.Clamp(c.Green * factor, 0, 255),
                    (byte)Math.Clamp(c.Blue * factor, 0, 255),
                    c.Alpha));
            }
        }
    }

    private static byte[] EncodePng(SKBitmap bitmap)
    {
        using var image = SKImage.FromBitmap(bitmap);
        using var data = image.Encode(SKEncodedImageFormat.Png, 100);
        return data.ToArray();
    }

    private static void SavePng(SKBitmap bitmap, string path)
    {
        File.WriteAllBytes(path, EncodePng(bitmap));
        Console.WriteLine($"{Path.GetFileName(path)} = {new FileInfo(path).Length:n0} 字节");
    }

    /// <summary>校验圆角：四角 alpha=0、边缘中点不透明、主体不透明占比符合圆角矩形。</summary>
    private static void VerifyRounded(SKBitmap bitmap, string label)
    {
        var size = bitmap.Width;
        var tl = bitmap.GetPixel(0, 0);
        var tr = bitmap.GetPixel(size - 1, 0);
        var bl = bitmap.GetPixel(0, size - 1);
        var br = bitmap.GetPixel(size - 1, size - 1);
        var topMid = bitmap.GetPixel(size / 2, 0);
        var bottomMid = bitmap.GetPixel(size / 2, size - 1);
        var leftMid = bitmap.GetPixel(0, size / 2);
        var rightMid = bitmap.GetPixel(size - 1, size / 2);
        var center = bitmap.GetPixel(size / 2, size / 2);

        Console.WriteLine(
            $"{label} 角 alpha：TL={tl.Alpha} TR={tr.Alpha} BL={bl.Alpha} BR={br.Alpha}；" +
            $"边中点 alpha：T={topMid.Alpha} B={bottomMid.Alpha} L={leftMid.Alpha} R={rightMid.Alpha}；" +
            $"中心 (r,g,b,a)=({center.Red},{center.Green},{center.Blue},{center.Alpha})");

        if (tl.Alpha != 0 || tr.Alpha != 0 || bl.Alpha != 0 || br.Alpha != 0)
        {
            throw new InvalidOperationException($"{label} 四角不是透明（alpha 应为 0）");
        }

        if (topMid.Alpha != 255 || bottomMid.Alpha != 255 || leftMid.Alpha != 255 || rightMid.Alpha != 255)
        {
            throw new InvalidOperationException($"{label} 边缘中点应为不透明");
        }

        // 圆角矩形面积比例 ≈ 1 - (1 - π/4) * (2r/边长)^2 ≈ 94.7%（r=22%）。
        var opaque = 0;
        var total = size * size;
        for (var y = 0; y < size; y++)
        {
            for (var x = 0; x < size; x++)
            {
                if (bitmap.GetPixel(x, y).Alpha == 255)
                {
                    opaque++;
                }
            }
        }

        var ratio = (double)opaque / total;
        Console.WriteLine($"{label} 不透明像素占比 = {ratio:P2}（圆角矩形理论约 94.7%）");
        if (ratio < 0.90 || ratio > 0.98)
        {
            throw new InvalidOperationException($"{label} 不透明占比异常：{ratio:P2}");
        }
    }

    private static void WriteIco(string path, List<(int Size, byte[] Data, bool IsPng)> entries)
    {
        using var stream = File.Create(path);
        using var writer = new BinaryWriter(stream);

        writer.Write((ushort)0);                 // reserved
        writer.Write((ushort)1);                 // type = icon
        writer.Write((ushort)entries.Count);     // count

        var offset = 6 + (entries.Count * 16);
        foreach (var (size, data, _) in entries)
        {
            writer.Write((byte)(size >= 256 ? 0 : size));   // width（0 表示 256）
            writer.Write((byte)(size >= 256 ? 0 : size));   // height
            writer.Write((byte)0);                          // color count
            writer.Write((byte)0);                          // reserved
            writer.Write((ushort)1);                        // planes
            writer.Write((ushort)32);                       // bit count
            writer.Write((uint)data.Length);
            writer.Write((uint)offset);

            offset += data.Length;
        }

        foreach (var (_, data, _) in entries)
        {
            writer.Write(data);
        }
    }

    /// <summary>32bpp BMP 条目（BITMAPINFOHEADER + 自底向上 BGRA + AND 掩码：透明像素置 1）。</summary>
    private static byte[] EncodeBmp32(SKBitmap bitmap)
    {
        var width = bitmap.Width;
        var height = bitmap.Height;
        var xorSize = width * height * 4;
        var maskStride = ((width + 31) / 32) * 4;
        var maskSize = maskStride * height;

        using var stream = new MemoryStream(40 + xorSize + maskSize);
        using var writer = new BinaryWriter(stream);
        writer.Write(40);                        // biSize
        writer.Write(width);                     // biWidth
        writer.Write(height * 2);                // biHeight（XOR + AND）
        writer.Write((ushort)1);                 // biPlanes
        writer.Write((ushort)32);                // biBitCount
        writer.Write(0);                         // biCompression = BI_RGB
        writer.Write(xorSize + maskSize);        // biSizeImage
        writer.Write(0);                         // biXPelsPerMeter
        writer.Write(0);                         // biYPelsPerMeter
        writer.Write(0);                         // biClrUsed
        writer.Write(0);                         // biClrImportant

        for (var y = height - 1; y >= 0; y--)
        {
            for (var x = 0; x < width; x++)
            {
                var c = bitmap.GetPixel(x, y);
                writer.Write(c.Blue);
                writer.Write(c.Green);
                writer.Write(c.Red);
                writer.Write(c.Alpha);
            }
        }

        // AND 掩码：位=1 表示透明（圆角四角），位=0 表示不透明。行按 32 位对齐、自底向上。
        var mask = new byte[maskSize];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var c = bitmap.GetPixel(x, y);
                if (c.Alpha < 128)
                {
                    var row = height - 1 - y;
                    mask[(row * maskStride) + (x / 8)] |= (byte)(0x80 >> (x % 8));
                }
            }
        }

        writer.Write(mask);
        return stream.ToArray();
    }

    private static SKBitmap DecodeIcoEntry(byte[] data, bool isPng)
    {
        if (isPng)
        {
            return SKBitmap.Decode(data) ?? throw new InvalidOperationException("ICO 内 PNG 条目解码失败");
        }

        var width = BitConverter.ToInt32(data, 4);
        var height = BitConverter.ToInt32(data, 8) / 2;
        var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Rgba8888, SKAlphaType.Premul));
        const int offset = 40;
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var i = offset + (((height - 1 - y) * width) + x) * 4;
                bitmap.SetPixel(x, y, new SKColor(data[i + 2], data[i + 1], data[i], data[i + 3]));
            }
        }

        return bitmap;
    }

    /// <summary>生成 256×256 画布、白底圆角 rect（rx/ry=22%）的 SVG，原 logo 内容按 8% 留白缩放居中。</summary>
    private static void WriteRoundedSvg(string path, string source, SKRect cull, int canvasSize = 256)
    {
        var svgStart = source.IndexOf("<svg", StringComparison.Ordinal);
        var tagEnd = svgStart < 0 ? -1 : source.IndexOf('>', svgStart);
        var closeStart = source.LastIndexOf("</svg>", StringComparison.Ordinal);
        if (tagEnd < 0 || closeStart <= tagEnd)
        {
            throw new InvalidOperationException("logo.svg 结构无法识别（<svg> 或 </svg> 缺失）");
        }

        var inner = source[(tagEnd + 1)..closeStart].Trim('\r', '\n');

        var content = canvasSize * (1 - (2 * PaddingRatio));
        var longest = Math.Max(cull.Width, cull.Height);
        var scale = content / longest;
        var drawWidth = cull.Width * scale;
        var drawHeight = cull.Height * scale;
        var offsetX = ((canvasSize - drawWidth) / 2) - (cull.Left * scale);
        var offsetY = ((canvasSize - drawHeight) / 2) - (cull.Top * scale);
        var radius = CornerRadiusPx(canvasSize);

        var sb = new StringBuilder();
        sb.Append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.Append(Inv($"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{canvasSize}\" height=\"{canvasSize}\" viewBox=\"0 0 {canvasSize} {canvasSize}\">\n"));
        sb.Append("  <!-- task-149：白底圆角（半径 22%），四角透明、不补角 -->\n");
        sb.Append(Inv($"  <rect x=\"0\" y=\"0\" width=\"{canvasSize}\" height=\"{canvasSize}\" rx=\"{radius}\" ry=\"{radius}\" fill=\"#FFFFFF\" />\n"));
        sb.Append("  <!-- 原 logo.svg 内容（viewBox 与图形未改），按 8% 留白缩放居中 -->\n");
        sb.Append(Inv($"  <g transform=\"translate({offsetX:0.###} {offsetY:0.###}) scale({scale:0.######}) translate({-cull.Left:0.###} {-cull.Top:0.###})\">\n"));
        sb.Append("    ").Append(inner.Replace("\n", "\n    ")).Append('\n');
        sb.Append("  </g>\n</svg>\n");

        File.WriteAllText(path, sb.ToString());
        Console.WriteLine($"logo-256.svg 已写入（256 画布 + rect rx/ry={radius} + 原内容 group transform）");
    }

    private static string Inv(FormattableString text) => text.ToString(CultureInfo.InvariantCulture);

    private static string FindRepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "BondClient", "Bond.csproj")))
        {
            dir = dir.Parent;
        }

        return dir?.FullName ?? throw new InvalidOperationException("未找到仓库根目录（BondClient/Bond.csproj）");
    }
}
