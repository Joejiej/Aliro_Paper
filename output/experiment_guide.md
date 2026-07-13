# PQ-Aliro 实验操作手册

> **适用人群**：拿到代码仓库但未读论文的实验者  
> **目标**：通过真实 NFC 硬件测量 PQ-Aliro 协议的端到端时延和密码学操作性能  
> **预计时间**：环境准备 2 小时 + 实验运行 1 小时

---

## 第一部分：实验背景（5 分钟读完）

**PQ-Aliro 是什么？**

一个用于门禁系统的 NFC 通信协议。手机靠近读卡器，完成以下握手：

```
手机（Android App） ←──── NFC ────→ 读卡器（PC 程序）
    AUTH0：交换临时密钥
    LOADCERT：传输证书
    AUTH1：互相签名认证，建立会话密钥
```

区别于传统门禁：本协议使用了**抗量子密码算法**（ML-KEM + ML-DSA），密钥和签名数据比传统 ECC 大 10-30 倍，因此需要把数据切成很多 255 字节以内的小包（APDU）发送。

**实验要测什么？**

| 测量目标 | 意义 |
|---------|------|
| 完整握手时延（ms） | 用户靠近读卡器到完成认证的实际时间 |
| 各阶段时延（AUTH0/LOADCERT/AUTH1）| 瓶颈在哪个阶段 |
| ML-KEM 封装时间（ms） | 手机端 Kyber 密钥封装耗时 |
| ML-DSA 签名时间（ms） | 手机端 Dilithium 签名耗时 |
| 实际 APDU 帧数 | 验证理论分析 |

---

## 第二部分：所需硬件与软件

### 2.1 硬件清单

| 设备 | 具体要求 | 推荐型号 |
|------|---------|---------|
| NFC USB 读卡器 | 支持 ISO 14443-4，PC/SC 协议 | ACS ACR122U（约 ¥100-200） |
| Android 手机 | Android 10+，支持 NFC，开发者模式已开启 | 任意主流安卓手机 |
| Mac 电脑 | macOS 12+，USB-A 或 USB-C 转接口 | 已有设备即可 |

> **注意**：ACR122U 是最通用的选择，macOS 无需额外驱动。其他品牌（Identiv uTrust、ACS ACR1255U）也可以，但需确认支持 PC/SC。

### 2.2 软件清单

| 软件 | 版本要求 | 下载地址 |
|------|---------|---------|
| Java JDK | 11 或以上 | https://adoptium.net |
| Apache Maven | 3.6+ | `brew install maven`（见下） |
| Android Studio | 最新稳定版 | https://developer.android.com/studio |
| adb（随 Android Studio 安装）| — | 随 Android Studio |
| Homebrew（Mac 包管理器）| — | https://brew.sh |

---

## 第三部分：环境安装

### 3.1 安装 Homebrew（如果没有）

打开 Terminal，运行：

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 3.2 安装 Java 和 Maven

```bash
# 安装 Java 17
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# 安装 Maven
brew install maven

# 验证安装
java --version     # 应显示 17.x.x
mvn --version      # 应显示 Apache Maven 3.x.x
```

### 3.3 安装 Android Studio

1. 下载安装 Android Studio
2. 打开 Android Studio → 完成初始向导（下载 SDK）
3. 安装完成后，在 Terminal 中运行：

```bash
# 添加 adb 到 PATH（路径根据实际安装位置可能不同）
echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.zshrc
source ~/.zshrc

# 验证
adb version   # 应显示 Android Debug Bridge version x.x.x
```

### 3.4 开启手机开发者模式和 USB 调试

1. 手机 → **设置 → 关于手机 → 版本号**，连续点击 7 次，提示"开发者模式已开启"
2. 返回设置 → **开发者选项 → USB 调试**，开启
3. 手机通过 USB 连接电脑，在手机上弹出的窗口中选择"允许 USB 调试"
4. 验证连接：

```bash
adb devices
# 应显示类似：List of devices attached
#              R3CN90XXXXX    device
```

---

## 第四部分：获取代码

### 4.1 克隆两个仓库

```bash
# 在你想放代码的目录下运行
mkdir ~/PQAliro_experiment && cd ~/PQAliro_experiment

# 克隆 Android App 代码
git clone https://github.com/Joejiej/Ailiro_UD.git

# 克隆 PC Reader + 论文仓库
git clone https://github.com/Joejiej/Aliro_Paper.git
```

克隆完成后目录结构应该是：

```
~/PQAliro_experiment/
├── Ailiro_UD/                      ← Android App 代码
│   └── app/src/main/java/...
└── Aliro_Paper/
    └── source/
        └── Aliro_Reader_PC_NFC/   ← PC 读卡器程序代码
```

---

## 第五部分：编译与安装

### 5.1 编译并安装 Android App

```bash
cd ~/PQAliro_experiment/Ailiro_UD

# 编译 Debug 版本
./gradlew assembleDebug

# 安装到已连接的手机（手机必须通过 USB 连接）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装成功后提示：`Success`

如果遇到 Gradle 错误，尝试：
```bash
./gradlew assembleDebug --stacktrace  # 查看详细错误
```

### 5.2 编译 PC Reader

```bash
cd ~/PQAliro_experiment/Aliro_Paper/source/Aliro_Reader_PC_NFC

# 编译并打包
mvn clean package -q

# 验证编译结果
ls target/Aliro_Reader-1.0-SNAPSHOT.jar  # 应显示该文件存在
```

---

## 第六部分：运行实验

### 6.1 物理设备连接

```
┌─────────────────────────────────────────────┐
│                                              │
│   NFC 读卡器 ──USB──► Mac 电脑              │
│                         （运行 PC Reader 程序）│
│                                              │
│   Android 手机（屏幕朝上放在读卡器上方 1-3cm）│
│   运行 Ailiro_UD App                         │
│                                              │
└─────────────────────────────────────────────┘
```

**连接步骤**：
1. 将 NFC 读卡器插入 Mac 的 USB 口
2. Android 手机通过 USB 线连接 Mac（用于 adb logcat 读取数据）
3. 在手机上打开 **Ailiro_UD** App，确保 App 显示在前台

> **重要**：HCE（Host Card Emulation）要求 App 必须在前台运行，否则无法响应读卡器。

### 6.2 验证读卡器被识别

在新的 Terminal 窗口中运行 PC Reader，在菜单中选 `1`：

```bash
cd ~/PQAliro_experiment/Aliro_Paper/source/Aliro_Reader_PC_NFC
java -jar target/Aliro_Reader-1.0-SNAPSHOT.jar
```

菜单出现后输入 `1`，应看到类似：
```
=== NFC Reader Client Menu ===
...
Choose option: 1

Available NFC Readers:
  [0] ACS ACR122U PICC Interface 0   ← 读卡器被识别
```

如果列表为空，读卡器未被系统识别（见第八部分排错）。

### 6.3 开启 Android 计时日志

打开**第二个 Terminal 窗口**，运行：

```bash
# 只显示计时相关的日志，保存到文件
adb logcat -s PQAliro-Timing:I 2>/dev/null | tee ~/Desktop/timing_results.log
```

这个窗口保持运行，实验过程中会自动记录数据。

### 6.4 运行基准测试（默认配置：D3×K768）

回到运行 PC Reader 的 Terminal 窗口：

```
=== NFC Reader Client Menu ===
1. List NFC Readers
2. Wait for Android Device
...
9. Benchmark (5 iterations, real timing)
0. Exit

Choose option: 2
```

输入 `2`，Reader 进入等待状态：
```
Using NFC reader: ACS ACR122U PICC Interface 0
Waiting for Android device...
```

**将手机放在读卡器上**（NFC 天线通常在手机背面中上部），Reader 自动检测到手机后会提示连接成功。

连接成功后输入 `9` 开始基准测试：

```
Choose option: 9

=== BENCHMARK: D3×K768 (5 iterations) ===
Phase,Iter,ms
AUTH0,1,418
LOADCERT,1,1312
AUTH1,1,954
TOTAL,1,2684
AUTH0,2,395
...
=== RESULTS: D3×K768 ===
Phase      | min(ms) | max(ms) | avg(ms)
AUTH0      |     381 |     436 |     408
LOADCERT   |    1241 |    1348 |    1289
AUTH1      |     871 |     964 |     921
TOTAL      |    2493 |    2748 |    2618
```

**重复 10 次**：每次测试完成后，将手机拿开再放回，重新输入 `2`（等待设备）→ `9`（测试），共做 10 次独立测试。

### 6.5 运行 ECC 经典模式（对照组）

找到 PC Reader 的配置文件并修改：

```bash
# 打开配置文件
open ~/PQAliro_experiment/Aliro_Paper/source/Aliro_Reader_PC_NFC/src/main/java/org/example/PQCConfig.java
```

将第 22 行：
```java
public static Mode MODE = Mode.PQ;
```
改为：
```java
public static Mode MODE = Mode.CLASSIC;
```

重新编译并重复实验：
```bash
mvn clean package -q
java -jar target/Aliro_Reader-1.0-SNAPSHOT.jar
```

同样完成 10 次测试，logcat 中 `config=ECC` 的行即为 ECC 数据。

---

## 第七部分：数据收集与分析

### 7.1 PC Reader 端数据

基准测试完成后，从 Terminal 输出中手动记录数据，或直接截图。每次测试产生一行 RESULTS 输出。

**建议格式**（填入下表）：

| 运行# | 配置 | AUTH0(ms) | LOADCERT(ms) | AUTH1(ms) | TOTAL(ms) |
|------|------|-----------|-------------|-----------|-----------|
| 1 | D3×K768 | | | | |
| 2 | D3×K768 | | | | |
| ... | | | | | |
| 1 | ECC | | | | |
| ... | | | | | |

### 7.2 Android 端数据（logcat）

logcat 文件保存在 `~/Desktop/timing_results.log`。用以下命令提取：

```bash
# 查看所有 SUMMARY 行
grep "\[SUMMARY\]" ~/Desktop/timing_results.log

# 示例输出：
# I PQAliro-Timing: [SUMMARY] config=D3xK768 total=2684 auth0=418 loadcert=1312 auth1=954 kem=21 sign_ipc=87 sign_comp=74 apdu_count=75
```

**字段说明**：

| 字段 | 含义 |
|------|------|
| `total` | 端到端总时延（ms） |
| `auth0` | AUTH0 阶段耗时（ms） |
| `loadcert` | LOADCERT 阶段耗时（ms） |
| `auth1` | AUTH1 阶段耗时（ms，含签名等待）|
| `kem` | ML-KEM-768 封装耗时（ms，手机端）|
| `sign_ipc` | 签名 IPC 往返耗时（ms，含进程通信）|
| `sign_comp` | ML-DSA-65 实际签名计算耗时（ms）|
| `apdu_count` | 本次事务发出的响应 APDU 帧数 |

### 7.3 用 Python 计算统计数据

将 TOTAL 列数据粘贴到如下 Python 脚本：

```python
import statistics

# 粘贴你的实测数据
d3k768_total = [2684, 2531, 2712, 2598, 2645, 2589, 2671, 2544, 2709, 2602]
ecc_total    = [312, 298, 324, 307, 315, 301, 318, 309, 322, 311]

def summary(data, label):
    n    = len(data)
    mean = statistics.mean(data)
    std  = statistics.stdev(data)
    mn   = min(data)
    mx   = max(data)
    print(f"{label}: n={n} mean={mean:.0f}ms std={std:.0f}ms min={mn}ms max={mx}ms")

summary(d3k768_total, "D3×K768")
summary(ecc_total,    "ECC Classic")
print(f"Slowdown ratio: {statistics.mean(d3k768_total)/statistics.mean(ecc_total):.1f}×")
```

运行：
```bash
python3 analyze.py
```

---

## 第八部分：常见问题排除

### Q1：读卡器插入 Mac 后，选项 1 列表为空

**macOS Ventura/Sonoma 问题**：Apple 更新了 PCSC 框架，部分读卡器需要重置服务：

```bash
# 重启 PCSC 守护进程
sudo killall -9 pcscd 2>/dev/null
sleep 2
/usr/sbin/pcscd
```

然后重新运行 PC Reader，再选 1 查看读卡器列表。

### Q2：手机放在读卡器上没有反应

1. 确认 Ailiro_UD App 在**前台**（屏幕亮着显示 App 界面）
2. 确认手机 NFC 已开启：**设置 → 连接 → NFC**
3. 调整手机位置：NFC 天线在手机背面中上部，尝试不同位置
4. 确认 PC Reader 已选 `2`（等待设备状态）
5. 检查 adb logcat 是否有错误：
   ```bash
   adb logcat | grep -i "error\|exception\|fail"
   ```

### Q3：AUTH1 一直等待，最终超时

这是签名操作耗时过长的表现。正常的 ML-DSA-65 签名约 50-100ms，若手机性能较低可能更久。

检查：
```bash
# 查看签名时间
grep "\[SIGN-COMP\]" ~/Desktop/timing_results.log
```

若签名超过 300ms，可以在 `MyHostApduService.java` 中找到 `Thread.sleep(500)` 改为 `Thread.sleep(1000)` 后重新安装 App。

### Q4：`adb devices` 显示 `unauthorized`

手机屏幕上应该有弹窗询问是否允许 USB 调试，点击"允许"并勾选"始终允许"。

### Q5：Maven 编译报错

```bash
# 查看详细错误
mvn clean package -e 2>&1 | tail -30
```

常见原因：Java 版本不匹配。确认 `java --version` 显示 Java 11 或更高版本。

---

## 第九部分：实验数据记录表

> 复印此表格用于手动记录数据

### 实验环境

- Android 设备型号：_______________
- Android 版本：_______________
- NFC 读卡器型号：_______________
- 实验日期：_______________

### D3×K768（默认配置）测量结果

| 运行# | AUTH0(ms) | LOADCERT(ms) | AUTH1(ms) | TOTAL(ms) | APDU数 |
|------|-----------|-------------|-----------|-----------|-------|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |
| **均值** | | | | | |
| **标准差** | | | | | |

### ECC 经典模式（对照组）测量结果

| 运行# | AUTH0(ms) | AUTH1(ms) | TOTAL(ms) | APDU数 |
|------|-----------|-----------|-----------|-------|
| 1 | | | | |
| 2 | | | | |
| ... | | | | |
| **均值** | | | | |
| **标准差** | | | | |

### 密码学操作时间（从 logcat 提取，D3×K768）

| 运行# | KEM封装(ms) | 签名计算(ms) | 签名IPC(ms) |
|------|-----------|------------|-----------|
| 1 | | | |
| ... | | | |
| **均值** | | | |

### 关键指标

| 指标 | 实测值 |
|------|-------|
| D3×K768 总时延（均值 ± 标准差）| _____ ± _____ ms |
| ECC 总时延（均值 ± 标准差）| _____ ± _____ ms |
| 时延倍数（PQ/ECC）| _____× |
| ML-KEM 封装时间 | _____ ms |
| ML-DSA 签名时间 | _____ ms |
| 实测 APDU 总帧数（理论值 75）| _____ |

---

## 附录：代码仓库说明

```
Ailiro_UD/                          Android HCE App（手机端）
├── app/src/main/java/com/example/ailiro_ud/
│   ├── TimingLogger.java           ← 计时工具（实验专用，不影响正常功能）
│   ├── MyHostApduService.java      ← NFC 数据接收与分发
│   ├── UserDevice.java             ← 协议状态机
│   ├── PQKeyManager.java           ← ML-KEM 封装
│   └── PQCSignatureService.java    ← ML-DSA 签名（独立进程）

Aliro_Paper/source/Aliro_Reader_PC_NFC/   PC 读卡器程序
├── src/main/java/org/example/
│   ├── Main.java                   ← 程序入口
│   ├── Reader.java                 ← 协议实现 + Benchmark（选项9）
│   └── PQCConfig.java              ← 算法参数配置（改这里切换 ECC/PQ）
└── src/main/resources/cert/        ← 预置证书（实验所需，已包含）
```

**如何切换 PQ/ECC 模式**：编辑 `Aliro_Reader_PC_NFC/src/main/java/org/example/PQCConfig.java`：

```java
// PQ 模式（默认）：
public static Mode MODE = Mode.PQ;

// ECC 模式（对照组）：
public static Mode MODE = Mode.CLASSIC;
```

修改后需重新编译：`mvn clean package -q`

---

*文档版本：2.0（自包含版）| 最后更新：2026-07-05*
