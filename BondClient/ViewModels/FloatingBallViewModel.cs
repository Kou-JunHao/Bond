using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace BondClient.ViewModels;

public class FloatingBallViewModel : INotifyPropertyChanged
{
    private bool _isSnapped;
    private bool _isExpanded;
    private double _x;
    private double _y;

    public bool IsSnapped
    {
        get => _isSnapped;
        set => SetField(ref _isSnapped, value);
    }

    public bool IsExpanded
    {
        get => _isExpanded;
        set => SetField(ref _isExpanded, value);
    }

    public double X
    {
        get => _x;
        set => SetField(ref _x, value);
    }

    public double Y
    {
        get => _y;
        set => SetField(ref _y, value);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }

    private void SetField<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        OnPropertyChanged(propertyName);
    }
}
