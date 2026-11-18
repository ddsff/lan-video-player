# 局域网播放器

使用 Kotlin 编写，借助 AndroidX Media3 ExoPlayer 播放。应用通过 SMB 2/3 访问 Windows 11 的文件夹共享。可以浏览子目录、直接播放视频、暂停和拖动进度。

## 连接 Windows 11

1. 让手机和电脑连接同一个家庭网络。在 Windows 11 上，将该网络设为“专用”，并在“高级共享设置”中启用“文件和打印机共享”。
2. 在资源管理器中右键单击视频文件夹，选择“显示更多选项” → “授予访问权限” → “特定用户”。为用于连接的账户授予读取权限，并记下共享名称，例如 `Videos`。
3. 在电脑上运行 `ipconfig`，查看当前网卡的 IPv4 地址，例如 `192.168.1.20`。
4. 打开安卓应用，点击“扫描局域网”并点选设备，即可填入电脑地址。也可以手动填写。再填写共享名称和获授权的 Windows 用户名及密码。本地账户可填写 `电脑名\用户名`，密码填写账户密码。点击“连接共享”并选择视频。

[Windows 文件共享说明](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/file-sharing-over-a-network-in-windows)

建议用 H.264 视频和 AAC 音频的 MP4 做首次测试。应用会列出 MKV、WebM 等常见视频文件，实际播放取决于手机系统支持的容器与编解码器。

连接失败后，可打开“SMB 连接日志”查看并复制本次进程中的诊断记录。Android Studio 的 Logcat 也可用 `tag:LanVideoSMB` 筛选实时日志。

成功连接后，应用会在本机加密保存最近 10 组连接信息。新连接默认只保存电脑地址、共享名称和用户名，勾选“记住密码”才保存密码。打开已有记录后取消勾选可以删除该记录已保存的密码。也可以在“连接历史”中清除全部记录。

连接页可直接打开[隐私政策](app/src/main/assets/privacy-policy.html)。上架前可参照 [Google Play 数据安全填报草稿](docs/play-data-safety.md)。

播放时轻点画面可以显示控件，在右上角选择 0.5×–2× 倍速。左右滑动可预览跳转位置，松手后跳转；双击画面左侧或右侧、或使用底部按钮，可快退或快进 10 秒。

通过播放页的“音轨”菜单可以查看并切换视频内的音轨。播放器优先使用 Android 音频解码器，并使用 Media3 FFmpeg 音频扩展补充 DTS、AC-3、E-AC-3 和 TrueHD 等格式。

FFmpeg 扩展由 [put.io 构建发布](https://github.com/putdotio/media3-ffmpeg-decoder/tree/v1.11.1%2B1)。APK 的 `META-INF/media3-ffmpeg-decoder/` 包含许可和第三方声明。[对应的 FFmpeg 源码、构建及重链接说明](https://repo.maven.apache.org/maven2/io/put/media3-ffmpeg-decoder/1.11.1%2B1/media3-ffmpeg-decoder-1.11.1%2B1-ffmpeg-source.tar.xz)

## 构建

使用 JDK 21、Android SDK 36.1 和 Gradle 9.3.1。可用 Android Studio 打开项目，或在项目根目录运行 `.\gradlew.bat :app:assembleDebug`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，支持 Android 8.0 及以上。
