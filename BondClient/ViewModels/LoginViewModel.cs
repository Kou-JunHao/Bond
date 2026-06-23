using System.ComponentModel;
using System.Runtime.CompilerServices;
using BondClient.Services;

namespace BondClient.ViewModels;

public class LoginViewModel : INotifyPropertyChanged
{
    private readonly AuthService _authService;
    private string _username = "";
    private string _password = "";
    private string _nickname = "";
    private string _errorMessage = "";
    private bool _isLoading;
    private bool _isRegisterMode;

    public string Username
    {
        get => _username;
        set { _username = value; OnPropertyChanged(); }
    }

    public string Password
    {
        get => _password;
        set { _password = value; OnPropertyChanged(); }
    }

    public string Nickname
    {
        get => _nickname;
        set { _nickname = value; OnPropertyChanged(); }
    }

    public string ErrorMessage
    {
        get => _errorMessage;
        set { _errorMessage = value; OnPropertyChanged(); }
    }

    public bool IsLoading
    {
        get => _isLoading;
        set { _isLoading = value; OnPropertyChanged(); }
    }

    public bool IsRegisterMode
    {
        get => _isRegisterMode;
        set { _isRegisterMode = value; OnPropertyChanged(); }
    }

    public event Action? LoginSuccess;
    public event PropertyChangedEventHandler? PropertyChanged;

    public LoginViewModel(AuthService authService)
    {
        _authService = authService;
    }

    public async Task SubmitAsync()
    {
        if (string.IsNullOrWhiteSpace(Username) || string.IsNullOrWhiteSpace(Password))
        {
            ErrorMessage = "请输入用户名和密码";
            return;
        }

        IsLoading = true;
        ErrorMessage = "";

        try
        {
            if (_isRegisterMode)
            {
                var nickname = string.IsNullOrWhiteSpace(Nickname) ? Username : Nickname;
                var (success, error) = await _authService.RegisterAsync(Username, Password, nickname);
                if (success)
                    LoginSuccess?.Invoke();
                else
                    ErrorMessage = error ?? "注册失败";
            }
            else
            {
                var (success, error) = await _authService.LoginAsync(Username, Password);
                if (success)
                    LoginSuccess?.Invoke();
                else
                    ErrorMessage = error ?? "登录失败";
            }
        }
        catch (Exception ex)
        {
            ErrorMessage = ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    public void ToggleMode()
    {
        IsRegisterMode = !IsRegisterMode;
        ErrorMessage = "";
    }

    private void OnPropertyChanged([CallerMemberName] string? name = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
