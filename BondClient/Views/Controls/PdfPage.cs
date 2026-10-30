using System;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Avalonia.Media.Imaging;

namespace Bond.Views.Controls;

/// <summary>PDF 阅读器里的一页：按需渲染，未渲染时显示占位文案。</summary>
public sealed class PdfPage : INotifyPropertyChanged
{
    public PdfPage(int pageNumber) => PageNumber = pageNumber;

    public int PageNumber { get; }

    private Bitmap? _bitmap;
    public Bitmap? Bitmap
    {
        get => _bitmap;
        set
        {
            if (ReferenceEquals(_bitmap, value)) return;
            _bitmap = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(HasBitmap));
            OnPropertyChanged(nameof(PlaceholderText));
        }
    }

    public bool HasBitmap => _bitmap is not null;

    private bool _isLoading;
    public bool IsLoading
    {
        get => _isLoading;
        set
        {
            if (_isLoading == value) return;
            _isLoading = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(PlaceholderText));
        }
    }

    public string PlaceholderText => _isLoading ? $"第 {PageNumber} 页 · 渲染中…" : $"第 {PageNumber} 页";

    private double _displayWidth = 720;
    public double DisplayWidth
    {
        get => _displayWidth;
        set
        {
            if (Math.Abs(_displayWidth - value) < 0.01) return;
            _displayWidth = value;
            OnPropertyChanged();
        }
    }

    private double _displayHeight = 1018;
    public double DisplayHeight
    {
        get => _displayHeight;
        set
        {
            if (Math.Abs(_displayHeight - value) < 0.01) return;
            _displayHeight = value;
            OnPropertyChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
