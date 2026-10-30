using System;
using System.Collections.Generic;
using Avalonia.Media;
using Material.Icons;

namespace Bond.Models;

/// <summary>文件的视觉分类（决定图标形状与配色）。</summary>
public enum FileVisual
{
    Folder,
    Image,
    Video,
    Audio,
    Pdf,
    Word,
    Sheet,
    Slide,
    Archive,
    Code,
    App,
    Markdown,
    Text,
    Other
}

/// <summary>
/// 按扩展名把文件映射到「图标 + 配色」。
///
/// 图标统一使用 Material.Icons 的 File*Box 家族——这是专门为文件类型设计的一组
/// 同构轮廓图标（13,645 个图标集的一部分），一套笔画风格就能覆盖图片/视频/音频/
/// PDF/Office/压缩包/代码/安装包，比通用形状（一张白纸）可辨识得多。
/// </summary>
public static class FileVisuals
{
    private static readonly Dictionary<string, FileVisual> ByExtension = new(StringComparer.OrdinalIgnoreCase)
    {
        // 图片
        [".png"] = FileVisual.Image, [".jpg"] = FileVisual.Image, [".jpeg"] = FileVisual.Image,
        [".gif"] = FileVisual.Image, [".bmp"] = FileVisual.Image, [".webp"] = FileVisual.Image,
        [".svg"] = FileVisual.Image, [".ico"] = FileVisual.Image, [".psd"] = FileVisual.Image,
        [".ai"] = FileVisual.Image, [".fig"] = FileVisual.Image, [".sketch"] = FileVisual.Image,

        // 视频
        [".mp4"] = FileVisual.Video, [".mov"] = FileVisual.Video, [".avi"] = FileVisual.Video,
        [".mkv"] = FileVisual.Video, [".webm"] = FileVisual.Video, [".flv"] = FileVisual.Video,

        // 音频
        [".mp3"] = FileVisual.Audio, [".wav"] = FileVisual.Audio, [".flac"] = FileVisual.Audio,
        [".aac"] = FileVisual.Audio, [".m4a"] = FileVisual.Audio, [".ogg"] = FileVisual.Audio,

        // 文档
        [".pdf"] = FileVisual.Pdf,
        [".doc"] = FileVisual.Word, [".docx"] = FileVisual.Word, [".rtf"] = FileVisual.Word,
        [".xls"] = FileVisual.Sheet, [".xlsx"] = FileVisual.Sheet, [".csv"] = FileVisual.Sheet,
        [".ppt"] = FileVisual.Slide, [".pptx"] = FileVisual.Slide, [".key"] = FileVisual.Slide,

        // 压缩包
        [".zip"] = FileVisual.Archive, [".rar"] = FileVisual.Archive, [".7z"] = FileVisual.Archive,
        [".tar"] = FileVisual.Archive, [".gz"] = FileVisual.Archive, [".iso"] = FileVisual.Archive,

        // 代码 / 配置
        [".json"] = FileVisual.Code, [".xml"] = FileVisual.Code, [".yml"] = FileVisual.Code,
        [".yaml"] = FileVisual.Code, [".html"] = FileVisual.Code, [".css"] = FileVisual.Code,
        [".js"] = FileVisual.Code, [".ts"] = FileVisual.Code, [".cs"] = FileVisual.Code,
        [".java"] = FileVisual.Code, [".py"] = FileVisual.Code, [".sh"] = FileVisual.Code,
        [".ps1"] = FileVisual.Code, [".sql"] = FileVisual.Code,

        // 可执行 / 安装包
        [".exe"] = FileVisual.App, [".msi"] = FileVisual.App, [".dll"] = FileVisual.App,
        [".apk"] = FileVisual.App, [".jar"] = FileVisual.App, [".dmg"] = FileVisual.App,
        [".pkg"] = FileVisual.App,

        // 文本
        [".md"] = FileVisual.Markdown, [".markdown"] = FileVisual.Markdown,
        [".txt"] = FileVisual.Text, [".log"] = FileVisual.Text,
        [".ini"] = FileVisual.Text, [".conf"] = FileVisual.Text
    };

    public static FileVisual Classify(string? fileName, NodeKind kind)
    {
        if (kind == NodeKind.Folder) return FileVisual.Folder;

        var ext = GetExtension(fileName);
        if (!string.IsNullOrEmpty(ext) && ByExtension.TryGetValue(ext, out var visual)) return visual;

        return kind == NodeKind.Document ? FileVisual.Markdown : FileVisual.Other;
    }

    /// <summary>Material.Icons 图标（文件类型专用家族）。</summary>
    public static MaterialIconKind Icon(FileVisual visual) => visual switch
    {
        FileVisual.Folder => MaterialIconKind.Folder,
        FileVisual.Image => MaterialIconKind.FileImage,
        FileVisual.Video => MaterialIconKind.FileVideo,
        FileVisual.Audio => MaterialIconKind.FileMusic,
        FileVisual.Pdf => MaterialIconKind.FilePdfBox,
        FileVisual.Word => MaterialIconKind.FileWordBox,
        FileVisual.Sheet => MaterialIconKind.FileExcelBox,
        FileVisual.Slide => MaterialIconKind.FilePowerpointBox,
        FileVisual.Archive => MaterialIconKind.FolderZip,
        FileVisual.Code => MaterialIconKind.FileCode,
        FileVisual.App => MaterialIconKind.PackageVariant,
        FileVisual.Markdown => MaterialIconKind.Notebook,
        FileVisual.Text => MaterialIconKind.FileDocument,
        _ => MaterialIconKind.FileDocument
    };

    /// <summary>图标前景色（暖底上保证可读）。</summary>
    public static Color AccentColor(FileVisual visual) => visual switch
    {
        FileVisual.Folder => Parse("#C9973F"),
        FileVisual.Image => Parse("#5F8F63"),
        FileVisual.Video => Parse("#7A6BC4"),
        FileVisual.Audio => Parse("#C4719A"),
        FileVisual.Pdf => Parse("#C0564A"),
        FileVisual.Word => Parse("#4E79A7"),
        FileVisual.Sheet => Parse("#3F8A5C"),
        FileVisual.Slide => Parse("#C97B3C"),
        FileVisual.Archive => Parse("#B0863F"),
        FileVisual.Code => Parse("#5E7F86"),
        FileVisual.App => Parse("#6F6A63"),
        FileVisual.Markdown => Parse("#6B7A86"),
        FileVisual.Text => Parse("#8A8578"),
        _ => Parse("#9A948B")
    };

    public static IBrush Brush(FileVisual visual) => new SolidColorBrush(AccentColor(visual));

    /// <summary>图标底座背景：同色、低透明度，形成"彩色标签"的观感。</summary>
    public static IBrush Background(FileVisual visual)
    {
        var c = AccentColor(visual);
        return new SolidColorBrush(Color.FromArgb(30, c.R, c.G, c.B));
    }

    public static string? GetExtension(string? fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName)) return null;

        var dot = fileName.LastIndexOf('.');
        if (dot < 0 || dot == fileName.Length - 1) return null;

        return dot == 0 ? null : fileName[dot..];
    }

    private static Color Parse(string hex) => Avalonia.Media.Color.Parse(hex);
}
