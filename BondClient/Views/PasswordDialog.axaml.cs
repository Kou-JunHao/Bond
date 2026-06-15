using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Input;
using Avalonia.Input.Platform;
using BondClient.Services;

namespace BondClient.Views;

public partial class PasswordDialog : Window
{
    private readonly PasswordManager _password;

    public PasswordDialog(PasswordManager password)
    {
        _password = password;
        InitializeComponent();
        PasswordBox.Text = _password.HasPassword ? _password.Password : "未设置";
        DisableBtn.IsEnabled = _password.HasPassword;
    }

    private void OnRegenerate(object? s, RoutedEventArgs e)
    {
        _password.RegeneratePassword();
        PasswordBox.Text = _password.Password;
        DisableBtn.IsEnabled = true;
    }

    private void OnDisable(object? s, RoutedEventArgs e)
    {
        _password.DisablePassword();
        PasswordBox.Text = "未设置";
        DisableBtn.IsEnabled = false;
    }

    private async void OnCopy(object? s, RoutedEventArgs e)
    {
        if (_password.HasPassword)
        {
            var top = TopLevel.GetTopLevel(this);
            if (top?.Clipboard != null)
                await top.Clipboard.SetTextAsync(_password.Password);
        }
    }

    private void OnDone(object? s, RoutedEventArgs e) => Close();
}
