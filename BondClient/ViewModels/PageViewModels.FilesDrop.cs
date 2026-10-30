using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Bond.ViewModels;

/// <summary>
/// task-116：从资源管理器往文件页/工作区文件页拖文件。
/// 交互层状态（整窗遮罩、目标目录提示）与落地上传入口。
/// 目录递归/建目录依赖后端能力，见 task-116 的阻塞说明。
/// </summary>
public partial class FilesPageViewModel
{
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private bool _isExternalDragActive;

    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    private string _externalDropHint = "";

    /// <summary>拖拽进入/离开时置位；目标目录仅用于提示文字。</summary>
    public void SetExternalDrag(bool active)
    {
        IsExternalDragActive = active;
        ExternalDropHint = active
            ? $"松开鼠标，把文件上传到 {_files.CurrentPath}"
            : "";
    }

    /// <summary>
    /// 拖放落地：过滤隐藏文件与系统垃圾文件，然后复用现成的分片上传队列。
    /// 返回 (接收数, 跳过数)，供代码后置弹 Toast。
    /// </summary>
    public async Task<(int accepted, int skipped)> DropFilesAsync(IReadOnlyList<string> paths)
    {
        SetExternalDrag(false);

        var accepted = new List<string>();
        var skipped = 0;
        foreach (var file in paths)
        {
            var name = System.IO.Path.GetFileName(file);
            if (string.IsNullOrWhiteSpace(name) || !File.Exists(file))
            {
                skipped++;
                continue;
            }

            if (name.StartsWith('.') || name.Equals("Thumbs.db", StringComparison.OrdinalIgnoreCase)
                || name.Equals("desktop.ini", StringComparison.OrdinalIgnoreCase))
            {
                skipped++;
                continue;
            }

            accepted.Add(file);
        }

        // 目录暂不处理（后端没有建目录接口）；由调用方根据返回值给提示。
        if (accepted.Count == 0)
        {
            return (0, skipped);
        }

        await _files.UploadAsync(accepted);
        return (accepted.Count, skipped);
    }
}
