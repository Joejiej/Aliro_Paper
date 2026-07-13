# 后量子 Aliro：将 NIST 后量子密码标准集成到 NFC 门禁控制中

**摘要**

量子计算机的广泛部署对经典公钥密码的长期机密性和真实性保证构成威胁，使基于 NFC 的物理门禁控制等安全关键基础设施面临风险。CSA 发布的 NFC/BLE/UWB 门禁控制标准 Aliro 1.0 完全依赖椭圆曲线密码学原语——基于 P-256 曲线的 ECDH 密钥协商和 ECDSA 签名，这些原语在 Shor 算法面前不再安全。我们提出 *PQ-Aliro*，即 Aliro 的后量子改造版本：使用 ML-KEM-768（CRYSTALS-Kyber）替换 ECDH，使用 ML-DSA-65（CRYSTALS-Dilithium）替换 ECDSA，同时保留原有的三轮结构（AUTH0 / LOADCERT / AUTH1）、基于 HKDF 的会话密钥派生（HMAC-SHA256 + HKDF-Expand）以及 AES-256-GCM 消息加密。我们在 Schäge 等人 [PKC:SchSchLau20] 的 AKE 安全模型中（该模型在 Bellare–Rogaway 框架 [C:BelRog93] 基础上引入了原始密钥配对和响应者身份隐私）证明：在 ML-KEM-768 的 IND-1CCA 安全性、ML-DSA-65 的 EUF-CMA 安全性、HKDF 的 PRF 安全性以及 AES-256-GCM 的 AEAD 安全性假设下，PQ-Aliro 可实现双向认证、（在长期密钥被攻陷情形下的）前向保密以及身份隐私。我们在 Android HCE 和 PC NFC 读卡器上使用 BouncyCastle 1.78 BCPQC 完成了完整原型实现，覆盖 9 种 PQC 配置的 20 项端到端断言测试全部无错通过。默认配置在 75 个 APDU 中传输约 16.4 KB 数据——约为 1.2 KB 的 ECC 基线的 14 倍——而 *key-slot*（密钥槽）缓存优化可将其降至 11.2 KB，最轻量的可用配置（D2$\times$K512）则达到 11.9 KB，使在单一 ISO 14443 NFC 信道上的部署切实可行。

---

## 1 引言

大规模量子计算的出现对支撑当今数字基础设施的公钥密码构成了根本性威胁。Shor 算法 [SIAM:Shor97] 可以在多项式时间内进行整数因子分解和计算离散对数，使 RSA 和椭圆曲线密码（ECC）不再安全；而 Grover 算法 [STOC:Grover96] 则将对称原语的有效安全性减半。尽管密码学相关量子计算机出现的时间表仍不确定，但战略风险是真实存在的：敌手可能现在窃取密文，待量子能力成熟后再行解密——这种威胁模型被称为"先收集，后解密"（harvest now, decrypt later）。为应对这一威胁，美国国家标准与技术研究院（NIST）已最终确定两项后量子密码（PQC）标准：用于密钥封装的 FIPS 203（ML-KEM，基于 CRYSTALS-Kyber）[NIST:FIPS203]，以及用于数字签名的 FIPS 204（ML-DSA，基于 CRYSTALS-Dilithium）[NIST:FIPS204]。NIST IR 8547 [NIST:IR8547] 进一步要求联邦机构在 2035 年前完成从 RSA 和 ECC 的迁移。物理门禁控制系统是特别敏感的目标：基于 NFC 的门锁和车辆进入系统守护着关键基础设施和人身安全，但几乎所有已部署的协议都依赖 ECC。因此，在量子威胁成为现实之前将这些系统迁移到后量子安全是一项紧迫的工程挑战。除了被动收集者外，NFC 门禁协议还面临能够实施中间人、重放和中继攻击的主动敌手；我们的形式化模型捕获了前两者，而中继攻击则通过 NFC 物理接近性假设加以缓解。

由连接标准联盟（CSA）发布的 Aliro 1.0 [CSA:Aliro10] 是一个近期标准化的多模态门禁控制协议，支持 NFC、BLE 和 UWB 三种承载方式。其密码核心构建于 ECC 之上：使用 P-256 上的 ECDH 密钥交换进行会话密钥建立，使用 ECDSA 进行基于证书的实体认证。类似设计也出现在汽车领域的 CCC Digital Key 3.0 [CCC:DigKey3] 中，同样依赖 ECDH/ECDSA 完成车辆与手机的安全配对。将 ML-KEM 和 ML-DSA 集成到此类协议中并非易事，原因有三且相互关联。第一，*对象尺寸膨胀*：ML-DSA-65 公钥占 1,952 字节、签名占 3,309 字节，而 P-256 上 ECDSA 的公钥仅 65 字节、签名约 72 字节——分别增长约 $30\times$ 和 $46\times$。ML-KEM-768 的密文为 1,088 字节，而 ECDH 临时公钥仅 65 字节。第二，*NFC 信道约束*：ISO/IEC 14443 [ISOIEC14443] ISO-DEP 帧每个 APDU 最多承载 255 字节应用数据，物理层比特率从 106 到 848 kbit/s 不等。即使在 Android HCE 通常协商的 424 kbit/s 下，默认 ML-DSA-65 $\times$ ML-KEM-768 配置下一次 PQ-Aliro 交易也会在约 75 个 APDU 中传输约 16.4 KB 数据，约为 1.2 KB 的经典 ECC 交易的 $14\times$。第三，*平台限制*：Android 的 `KeyStore` API 不原生支持格基密码算法，要求实现完全使用 BouncyCastle 等第三方库在软件中管理 PQC 密钥材料。

随着 ML-KEM 和 ML-DSA 在 2024 年作为 FIPS 203/204 最终确定，且 BouncyCastle 1.78 BCPQC 提供了生产级实现，对 Aliro 进行符合标准的 PQC 升级的先决条件已经具备。本文提出 **PQ-Aliro**，据我们所知，这是对 Aliro NFC 加速流程（Expedited flow）的首次 PQC 升级（参见 §\ref{sec:related}）。我们的贡献如下：

1. **协议设计与实现。** 我们规范了 Aliro 加速流程的 PQC 实例化版本，使用 ML-KEM-768 密钥封装替换 ECDH、使用 ML-DSA-65 签名替换 ECDSA，并保留三轮的 AUTH0/LOADCERT/AUTH1 消息结构。我们提供完整的开源实现：一款 Android HCE 用户设备应用和一款 PC NFC 读卡器，两者均使用 BouncyCastle 1.78 BCPQC 作为密码后端。我们的实现仅限于 NFC 承载方式；BLE 和 UWB 传输以及安全元件卸载不在本文范围内。

2. **APDU 级开销分析。** 我们对所有九种 PQC 参数组合（Dilithium $\{2,3,5\}$ $\times$ Kyber $\{512,768,1024\}$）进行了详尽的字节级通信开销分析。开销范围从约 11.9 KB（D2$\times$K512）到约 23.2 KB（D5$\times$K1024），相比之下经典 ECC 实例化约为 1.2 KB。默认 ML-DSA-65 $\times$ ML-KEM-768 配置每次交易产生约 16.4 KB 和约 75 个 APDU；最轻量的 ML-DSA-44 $\times$ ML-KEM-512 配置可降至约 11.9 KB 和约 55 个 APDU。我们进一步表明，*key-slot*（密钥槽）优化——将读卡器证书预先缓存在用户设备上——可省去 LOADCERT 轮次，节省约 5–7 KB。据我们所知，此前没有工作对 PQC 升级后的 NFC 门禁协议提供完整的 APDU 粒度通信开销分析。

3. **形式化安全分析。** 基于 Schäge 等人 [PKC:SchSchLau20] 的 AKE 安全模型（在 Bellare–Rogaway [C:BelRog93] 基础上引入原始密钥配对和响应者身份隐私），以及 ML-KEM [EuroSP:BDKLLSSSS18] 和 ML-DSA [TCHES:DKLLSSS18] 的可证明安全性，我们用 PQC 原语实例化 Schäge 等人的框架，证明 PQ-Aliro 在 AUTH1 验证后实现双向认证、（在长期密钥被攻陷情形下）会话完成后的前向保密，以及对被动窃听者的读卡器身份隐私。安全界约至 ML-KEM-768 的 IND-1CCA 安全性、ML-DSA-65 的 EUF-CMA 安全性、HKDF 的 PRF 安全性以及 AES-256-GCM 的 AEAD 安全性（完整模型与证明见 §6–§7）。我们的模型不捕获侧信道攻击、临时状态泄露或中继攻击；后者通过 NFC 固有的约 10 cm 接近距离限制在物理层得到缓解。

4. **受限 Android HCE 平台的工程洞察。** 我们识别并解决了两个在协议层不可见的 Android 专属风险：Dilithium 私钥因 JVM 垃圾回收不确定性而泄露（典型 GC 暂停窗口为 50–200 ms，通过命名进程隔离并立即自我终止加以解决），以及因阻塞式 ML-DSA 签名调用导致的 HCE 主线程死锁（5 秒后会触发"应用无响应"（ANR）事件，通过异步分派加以解决）。此前没有 PQC-over-HCE 实现记录过这两种风险。这些风险及其缓解措施适用于任何在 Android HCE/HAL 场景中调度长时延 PQC 私钥操作的情形，包括 CCC Digital Key、PQC X.509 证书验证和 PQC-FIDO2。

贡献 1 和 2 直接应对前两个挑战（对象尺寸膨胀和 NFC 信道约束）；贡献 3 建立形式化安全保证；贡献 4 解决平台特定的实现风险。

贡献 3 并非形式主义：用 KEM/签名替换 ECDH/ECDSA 将认证结构从显式（对转录本签名）转变为 KEM 隐式，需要新的证明以表明隐式认证的会话密钥在 Schäge 等人模型下仍能满足双向认证。

本文余下部分组织如下：§\ref{sec:related} 调研相关工作；§\ref{sec:prelim} 介绍预备知识；§\ref{sec:design:kdf} 给出协议；§\ref{sec:model}–§\ref{sec:proof} 建立安全性；§\ref{sec:implementation}–§\ref{sec:evaluation} 描述实现与评估；§\ref{sec:discussion} 讨论优化与局限；§10 总结。

---

## 2 相关工作

### 2.1 后量子密钥交换与认证

PQ-Aliro 中使用的 ML-KEM 和 ML-DSA 算法在第 §\ref{sec:prelim} 节正式描述；本节聚焦于已有的基于格原语构建的 AKE 构造。

后量子密码原语的标准化推动了一批工作，研究如何从格基构造模块构建高效且可证明安全的认证密钥交换（AKE）协议。

KEMTLS [CCS:SchSteWig20] 是一项里程碑式的工程贡献，它用基于 KEM 的双向认证替换了 TLS 1.3 中基于签名的握手，完全消除了关键路径上的握手签名。PQ-Aliro 采取互补但不同的方法：我们不是重新设计握手，而是保留 Aliro 的 APDU 命令结构，仅替换底层原语，确保与现有门禁基础设施的线级结构兼容。至关重要的是，KEMTLS 面向基于 TCP/IP 的 TLS 记录层语义，载荷分片由传输层透明处理；而 PQ-Aliro 必须在 NFC 物理层 255 字节的硬性 ISO-DEP APDU 边界内工作，使字节级通信开销成为首要设计约束，而非事后补救。

在理论方面，Fujioka 等人 [ASIACCS:FSXY13] 从单向 CCA 安全的 KEM 构造了单遍 AKE 协议，为在标准模型下从 KEM 安全性构建 AKE 安全性确立了原则性框架。PQ-Aliro 的认证结构遵循类似思路：ML-KEM 封装提供会话密钥建立，而 ML-DSA 证书将临时密钥绑定到长期身份。Pan 等人 [C:PanWagZen23] 等具有紧密安全归约的格基 AKE 构造证实，可证明安全且归约紧密的格基 AKE 是可实现的，加强了 PQ-Aliro 所依赖的理论基础。早期工作 [PKC:FSXY12] 在 PKC 设定下从格假设出发构建了强安全的 AKE，进一步证明该研究方向的成熟性。然而，这些理论结果并未直接处理促使 PQ-Aliro 设计的字节级约束问题；下一节我们将考察 AKE 安全模型中的相关工作。

### 2.2 AKE 安全模型

认证密钥交换协议的安全性通过一个不断增强的敌手模型层级体系来形式化。奠基性的 Bellare–Rogaway（BR93）模型 [C:BelRog93] 建立了核心 AKE 框架：通过匹配会话定义会话配对，形式化包括长期密钥攻陷在内的敌手查询，并捕获会话密钥不可区分性和实体认证。Canetti 和 Krawczyk [EC:CanKra01] 在此基础上扩展出 CK 模型，额外捕获会话特定内部状态泄露，并允许更精细的前向保密概念。LaMacchia、Lauter 和 Mityagin 的 eCK 模型 [PROVSEC:LaMLauMit07] 进一步增强敌手能力，同时向敌手暴露长期密钥和临时随机性，被广泛视为最强的标准 AKE 安全定义之一。

PQ-Aliro 的安全分析在 BR 风格的、并附加身份隐私实验的扩展模型中进行。隐私维度的动机来自 Lyu 等人 [AC:LLHG22] 的工作，他们在标准模型下形式化了隐私保护 AKE，并证明即使面对主动敌手，用户身份仍可保持跨会话不可链接。在 NFC 门禁场景下，抗追踪性是一项实际需求：被动窃听者记录 NFC 交易时，不应能够将重复访问链接到单一凭证持有者。我们的身份隐私实验测试敌手能否基于观察到的 APDU 转录本区分两个目标用户，其定义方法遵循 [AC:LLHG22] 的思路并适配于双方门禁场景。Dowling 等人 [JC:DFGS21] 对 TLS 1.3 的密码分析为多阶段 AKE 模型提供了进一步的比较参考，尽管其记录层关注点与我们的 APDU 层关注点正交。我们采用 Schäge 等人 [PKC:SchSchLau20] 的框架，因为据我们所知，这是唯一同时捕获响应者身份隐私（抗追踪性）和原始密钥配对的 BR 风格 AKE 模型——两者对 Aliro 门禁场景都是本质属性。eCK 模型 [PROVSEC:LaMLauMit07] 针对临时状态泄露提供更强保证，但不涉及身份隐私；Lyu 等人 [AC:LLHG22] 对身份隐私建模，但其假设的单会话设定不适用于多用户的 Aliro 部署场景。这种对 APDU 层约束的强调自然引出一个问题：PQC 原语在 NFC 门禁典型的受限平台上表现如何。

### 2.3 受限平台上的 PQC

在资源或带宽受限的环境中部署后量子密码仍是活跃研究方向。Lima 等人 [NIST3PQC:Lima21] 评估了 Kyber KEM 在原生 Android 移动应用中的性能，报告称封装和解封装速度足以在商用智能手机上进行交互式使用。他们的发现为 PQ-Aliro 的 Android HCE 实现中移动端 PQC 的可行性提供了直接的经验支持。后量子 WireGuard [SP:HNSWZ21] 将格基 KEM 集成进 WireGuard VPN 协议，证明 NIST 第 3 轮 KEM 的握手开销可被受限网络协议吸收而不破坏功能兼容性。与 WireGuard 的 1,232 字节 UDP 载荷预算 [SP:HNSWZ21] 不同，PQ-Aliro 必须在 255 字节的 ISO-DEP APDU 限制 [ISOIEC14443] 内工作，使字节级分片成为头等设计约束而非事后优化。

上述成果聚焦于基于 IP 的传输或一般移动计算场景。尽管如此，仍存在显著的研究空白。现有工作处理 TLS 和 VPN 类协议中的 PQC 集成，其中消息分片由传输层的 TCP 或 UDP 处理，字节级开销不构成硬性的单消息限制。NFC 场景有本质不同：ISO/IEC 14443 ISO-DEP APDU 最多承载 255 字节应用数据，424 kbit/s 的物理层比特率使每千字节开销都转化为可感知的时延。据我们所知，*此前没有工作对 PQC 升级后的 NFC 门禁协议提供完整的 APDU 粒度通信开销分析*。

在标准层面，Aliro 1.0 [CSA:Aliro10] 和 CCC Digital Key 3.0 [CCC:DigKey3]——面向智能手机数字门禁的两份领先规范——均未提供后量子变体、PQC 迁移路线图，或对其 AKE 核心的形式化安全证明。PQ-Aliro 同时弥补这三方面缺陷——提供线兼容的 PQC 升级、对所有九种 ML-KEM/ML-DSA 参数组合的字节级精确开销刻画，以及建立双向认证、（在长期密钥被攻陷情形下的）前向保密和身份隐私的形式化安全证明。

---

## 3 预备知识

### 3.1 Aliro 门禁控制协议

Aliro 1.0 [CSA:Aliro10] 是由连接标准联盟（CSA）于 2026 年发布的统一门禁控制规范。它定义了面向 NFC、BLE 和 UWB 设备的基于凭证的认证框架，旨在用单一可互操作协议替代专有卡模拟方案。在 NFC 场景下，每次交易涉及两个主体：*用户设备*（UD），通常是运行 Android 主机卡模拟（HCE）的智能手机；以及 *读卡器*，即启动交换的门控制器或闸机。

**加速阶段协议流程。** 认证在 ISO/IEC 14443 Type A/B 非接触式信道上通过四条逻辑消息完成：

1. **SELECT（AID = `F0 10 20 30 40 50`）**：读卡器在 UD 上选择 Aliro 小程序。此步骤不交换任何载荷，仅用于激活 HCE 服务。

2. **AUTH0（`INS = 0x80`），读卡器 $\to$ UD**：读卡器发送一个新鲜临时公钥，连同 `transaction-identifier`（16 B）和 `reader-identifier`（32 B）。在经典 ECC 实例化中这是 P-256 ECDH 临时公钥；在 PQ-Aliro 中这是 ML-KEM-768 临时公钥。UD 回复密钥封装密文（或 ECDH 公钥份额）和 32 字节噪声贡献。此时双方均可通过 HKDF 派生共享会话密钥。

3. **LOADCERT（`INS = 0xD1`），读卡器 $\to$ UD**：读卡器以 Profile0000 DER 格式发送其长期证书。该证书携带读卡器的长期公钥及颁发者对其的签名。验证成功后 UD 返回两字节状态字 `0x9000`。

4. **AUTH1（`INS = 0x81`），读卡器 $\to$ UD**：读卡器对会话转录本签名并将签名发送给 UD。UD 验证转录本签名后返回 AEAD 加密的载荷，其中包含其自身长期公钥、转录本签名和 `signaling-bitmap`（2 B），使用先前派生的会话密钥以 AES-GCM 进行认证加密。

**NFC 物理层约束。** ISO/IEC 14443 对 APDU 载荷大小施加严格限制：读卡器端将应用数据分块为不超过 200 字节的帧，UD 端则以不超过 240 字节的块响应。长度超过 255 字节的字段使用 BER-TLV 长形式长度编码（`0x82` 后跟两字节大端长度），每个此类字段增加 4 字节帧开销。每个命令 APDU 增加 6 字节头（CLA、INS、P1、P2、Lc、Le），每个 HCE 响应附加 2 字节状态字。

在原始 ECC 实例化（P-256 ECDH + ECDSA）中，每次交易的应用层载荷总量约为 1.2 KB，通常在少量 APDU 往返内完成。由于 Shor 算法使椭圆曲线离散对数问题在足够大的量子计算机上变得可解，这种基于 ECC 的设计必须迁移到后量子原语——这一迁移会显著增加密码对象尺寸，进而增加 NFC 通信开销。

### 3.2 ML-KEM 与 ML-DSA

**ML-KEM（Kyber）。** ML-KEM 最初作为 CRYSTALS-Kyber [EuroSP:BDKLLSSSS18] 提出，后由 NIST 标准化为 FIPS 203 [NIST:FIPS203]。它是一种密钥封装机制（KEM），其安全性归约至模块带误差学习（Module-LWE）问题的困难性。该方案提供一组三元组 $(\mathsf{Gen}, \mathsf{Encap}, \mathsf{Decap})$：接收方生成密钥对 $(\mathit{pk}, \mathit{sk}) \leftarrow \mathsf{Gen}()$；发送方产生密文和共享秘密 $(c, K) \leftarrow \mathsf{Encap}(\mathit{pk})$；接收方恢复 $K \leftarrow \mathsf{Decap}(\mathit{sk}, c)$。ML-KEM 通过 Fujisaki–Okamoto 变换 [JC:FujOka13] 在随机预言机模型下达到 IND-CCA2 安全性。三个参数集分别对应 NIST 安全等级 1、3、5：ML-KEM-512、ML-KEM-768、ML-KEM-1024。PQ-Aliro 默认采用 **ML-KEM-768**（等级 3），其公钥 1,184 字节、密文 1,088 字节、共享秘密 32 字节。该 KEM 替换 AUTH0 步骤中的 P-256 ECDH 临时交换。

**ML-DSA（Dilithium）。** ML-DSA 最初作为 CRYSTALS-Dilithium [TCHES:DKLLSSS18] 提出，后标准化为 FIPS 204 [NIST:FIPS204]。其安全性基于 Module-LWE 和模块短整数解（Module-SIS）问题的困难性，方案达到 EUF-CMA 安全。三个参数集覆盖 NIST 安全等级 2、3、5：ML-DSA-44、ML-DSA-65、ML-DSA-87。PQ-Aliro 默认采用 **ML-DSA-65**（等级 3），其公钥 1,952 字节、签名 3,309 字节。ML-DSA 在两处替换 ECDSA：读卡器在 LOADCERT 期间传输的 Profile0000 证书中嵌入其长期 ML-DSA 公钥，并在 AUTH1 中对会话转录本签名；UD 同样用其自身的 ML-DSA 密钥对转录本签名，并将签名加密后包含在 AUTH1 响应中返回。

表 \ref{tab:sizes} 总结了经典 P-256 对象与后量子对应物之间的尺寸差异。公钥和签名增长约 18–46$\times$，这是 PQ-Aliro 相对于原始 ECC 设计在 NFC 流量上观察到约 $13\times$ 增长的主要驱动因素。

| 对象 | ECC P-256 | ML-KEM-768 | ML-DSA-65 |
|------|-----------|------------|-----------|
| 公钥 | 65 B | 1,184 B | 1,952 B |
| 密文 / 签名 | 72 B | 1,088 B | 3,309 B |
| 共享秘密 | 32 B | 32 B | --- |
| NIST 安全等级 | --- | 等级 3 | 等级 3 |

### 3.3 密码学定义

**记号。** 我们以 $y\sample \mathcal{Y}$ 表示 $y$ 从集合 $\mathcal{Y}$ 中均匀随机选取。记号 $x\coloneqq z$ 表示 $x$ 被赋值为 $z$。对于随机化算法 $f$，$y\sample f(x)$ 表示在输入 $x$ 上执行 $f$ 得到的新鲜随机输出，而 $y\gets f(x;r)$ 表示使用显式随机性 $r$ 对 $f$ 进行确定性求值。对于正整数 $N$，$[N]$ 表示集合 $\{1,2,3,\dots,N\}$。设 $\lambda$ 为安全参数；$\negli(\lambda)$ 表示 $\lambda$ 的可忽略函数，$p(\lambda)$ 为任意多项式。对所有多项式 $p$ 和充分大的 $\lambda$，$\negli(\lambda) < 1/p(\lambda)$。

#### 3.3.1 抗碰撞哈希函数

**定义 1（抗碰撞哈希函数）。** 哈希函数 $\msh : \{0,1\}^* \to \{0,1\}^\lambda$ 是抗碰撞的，若对每个 PPT 敌手 $\mathcal{A}$，

$$
\mathsf{Adv}_{\msh}^{\mathsf{coll}}(\mathcal{A}, \lambda) := \Pr\bigl[(x, x') \gets \mathcal{A}(1^\lambda) : x \neq x' \wedge \msh(x) = \msh(x')\bigr] \leq \negli(\lambda).
$$

在我们的协议中，$\msh$ 被建模为抗碰撞哈希函数；转录本 $\mathit{trans} = \msh(pk_e \| c_e)$ 继承该性质。

#### 3.3.2 密钥封装机制

密钥封装机制（KEM）[EPRINT:Shoup01] 由以下四个算法定义：

- $\setup(1^{\lambda})$：输入安全参数 $\lambda$，产生公共参数 $\mathsf{pp}$，指定密钥空间 $\mathcal{K}$、公钥空间 $\mathcal{PK}$、私钥空间 $\mathcal{SK}$ 和密文空间 $\mathcal{C}$。
- $\mathsf{Gen}(\mathsf{pp})$：以公共参数 $\mathsf{pp}$ 为输入，输出新鲜密钥对 $(pk,sk)$。
- $\mathsf{Encap}(pk)$：给定公钥 $pk$，生成密文 $c$ 及封装密钥 $K$。
- $\mathsf{Decap}(sk,c)$：使用私钥 $sk$ 和密文 $c$，恢复密钥 $K$。

**正确性。** 对所有 $\ppp\sample \setup(1^{\lambda})$、所有 $(pk,sk)\sample \gen(\ppp)$ 和所有 $(c,K)\sample \mathsf{Encap}(pk)$，必须满足 $\prob{\mathsf{Decap}(sk,c)\neq K}\leq \negli(\lambda)$。

*关于公共参数的注记。* 为简洁起见，下文省略 **Setup** 算法和公共参数 $\mathsf{pp}$，因 ML-KEM 将其隐式嵌入安全参数中。

**定义 2（KEM 的 IND-1CCA 安全性）。** KEM 的 $\textrm{IND-1CCA}$ 实验如图 \ref{fig:kem-ind-cpa} 所示。敌手 $\mathcal{A}$ 针对 $\textsf{KEM}$ 的 IND-1CCA 安全性的优势定义为

$$
\mathrm{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{A},\lambda) = \left| \Pr\left[ \mathsf{IND}\text{-}\mathsf{1CCA}(\mathcal{A}, \lambda, \kem) = 1 \right] - \frac{1}{2} \right|.
$$

**图 IND-1CCA 博弈：**

```
Exp IND-1CCA(A, λ, KEM):                  O(ct):
  pp <- KEM.Setup(1^λ)                       if ct ≠ ct* 且为首次调用:
  (pk*, sk*) <- KEM.Gen(pp)                      return KEM.Decap(sk*, ct)
  b <- {0, 1}                                else:
  (ss0, ct*) <- KEM.Encap(pk*)                   return ⊥
  ss1 <- K (均匀随机)
  b' <- A^O(pk*, ct*, ss_b)
  return b' = b
```

**注 1.** ML-KEM-768（CRYSTALS-Kyber）[NIST:FIPS203] 通过 Fujisaki–Okamoto 变换 [JC:FujOka13] 在随机预言机模型下达到 IND-CCA2 安全性。由于 IND-CCA2 蕴含 IND-1CCA，§\ref{sec:proof} 中的证明在使用 ML-KEM-768 实例化时直接适用。

#### 3.3.3 数字签名方案

我们采用标准的数字签名方案概念 [GolMicRiv88]。

**定义 3（数字签名方案）。** 消息空间 $\mathcal{M}$ 上的数字签名方案是一组三算法 $\mathsf{SIG} = (\mathsf{SIG.Gen}, \mathsf{SIG.Sign}, \mathsf{SIG.\verif})$，其中：

1. $\mathsf{SIG.Gen}(1^{\lambda})$ 是随机化密钥生成算法，输入安全参数 $\lambda$，输出公钥（验证密钥）$pk$ 和私钥（签名密钥）$sk$。
2. $\mathsf{SIG.Sign}(sk, m)$ 是随机化签名算法，给定签名密钥 $sk$ 和消息 $m \in \mathcal{M}$，产生签名 $\sigma$。
3. $\mathsf{SIG.\verif}(pk, m, \sigma)$ 是确定性验证算法，返回 $0$（无效）或 $1$（有效）。

**正确性。** 签名方案 $\mathsf{SIG}$ 是 **正确的**，若对任意消息 $m \in \mathcal{M}$ 和 $\mathsf{SIG.Gen}(1^{\lambda})$ 支持下的任意密钥对 $(pk, sk)$，都有 $\mathsf{SIG.\verif}(pk, m, \mathsf{SIG.Sign}(sk, m)) = 1$。

**自适应选择消息攻击下的存在性不可伪造性。** 数字签名的标准安全要求是自适应选择消息攻击下的存在性不可伪造性（$\mathsf{EUF}\text{-}\mathsf{CMA}$）[GolMicRiv88]。

**定义 4（$\mathsf{EUF}\text{-}\mathsf{CMA}$ 安全性）。** 设 $\mathsf{SIG}$ 为数字签名方案（定义 3）。实验 $\mathsf{EUF}\text{-}\mathsf{CMA}(\mathcal{A}, \lambda, \mathsf{SIG})$ 进行如下：

1. 挑战者生成 $(pk, sk) \sample \mathsf{SIG.Gen}(1^{\lambda})$，初始化 $Q_{\mathsf{Sign}} := \emptyset$，并将 $pk$ 给 $\mathcal{A}$。
2. $\mathcal{A}$ 自适应发起签名查询；对每个查询 $m$，挑战者返回 $\sigma \gets \mathsf{SIG.Sign}(sk, m)$ 并将 $m$ 加入 $Q_{\mathsf{Sign}}$。
3. $\mathcal{A}$ 输出 $(m^*, \sigma^*)$。当且仅当 $\mathsf{SIG.\verif}(pk, m^*, \sigma^*) = 1$ 且 $m^* \not\in Q_{\mathsf{Sign}}$ 时实验输出 $1$。

优势为 $\mathsf{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{A}, \lambda) := \Pr[ \mathsf{EUF}\text{-}\mathsf{CMA}(\mathcal{A}, \lambda, \mathsf{SIG}) = 1 ]$。

#### 3.3.4 密钥派生函数

密钥派生采用基于 HMAC 的密钥派生函数（$\hkdf$）[RFC5869]。$\hkdf$ 遵循 extract-then-expand 范式，以 HMAC [DBLP:journals/rfc/rfc2104] 为构造模块。$\hkdf$ 包含两个过程：

- $\hkdf.\extr(\mathit{salt},\mathit{IKM})$：使用可选盐值从输入密钥材料提取伪随机密钥，即 $\hmac.\mathsf{Mac}(\mathit{salt},\mathit{IKM})$。
- $\hkdf.\expand(\mathit{PRK},\mathit{info},L)$：使用上下文字符串 $\mathit{info}$ [JC:DFGS21] 将伪随机密钥扩展为 $L$ 比特。

**定义 5（伪随机函数 PRF）。** 伪随机函数 $\mathsf{PRF}: \mathcal{K}_{\mathsf{PRF}} \times \mathcal{L}_{\mathsf{PRF}} \to \{0,1\}^\lambda$ 是安全的，若对每个 PPT 敌手 $\mathcal{A}$，

$$
\mathsf{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{A}, \lambda) = \left| \Pr\left[ k \gets \mathcal{K}_{\mathsf{PRF}} : \mathcal{A}^{\mathsf{PRF}(k,\cdot)} = 1 \right] - \Pr\left[ \mathcal{A}^{R(\cdot)} = 1 \right] \right| \leq \negli(\lambda),
$$

其中 $R: \mathcal{L}_{\mathsf{PRF}} \to \{0,1\}^\lambda$ 是均匀随机函数。

在我们的协议中，$\mathsf{HKDF.Extract}$ 和 $\mathsf{HKDF.Expand}$ [RFC5869] 各被建模为一个 PRF 实例；输入域 $\mathcal{L}_{\mathsf{PRF}}$ 对应转录本空间，输出为会话密钥材料。

#### 3.3.5 认证加密

我们将带相关数据的对称认证加密（AEAD）方案建模为一对算法 $\mathsf{AEAD} = (\mathsf{Enc}, \mathsf{Dec})$，定义于密钥空间 $\mathcal{K}_\mathsf{AEAD}$ 和消息空间 $\mathcal{M}_\mathsf{AEAD}$ 之上，遵循 Rogaway 和 Shrimpton [EC:RogShr06] 的 AE 安全定义。

**博弈** $\mathsf{AEAD\text{-}sec}(\mathcal{A}, \lambda, \mathsf{AEAD})$：博弈分三阶段进行：(1) **设置阶段**：挑战者采样新鲜密钥 $k \sample \mathcal{K}_\mathsf{AEAD}$ 和挑战比特 $b \sample \{0,1\}$；(2) **加密预言机**：对每次查询 $(m_0, m_1, \mathit{ad})$，挑战者返回 $\mathsf{Enc}(k, m_b, \mathit{ad})$；(3) **解密预言机**：对每次查询 $(c, \mathit{ad})$，挑战者返回 $\mathsf{Dec}(k, c, \mathit{ad})$，除非 $c$ 是挑战密文，此时返回 $\bot$。$\mathcal{A}$ 在上述博弈中的优势为：

**定义 6（安全 AEAD）。** AEAD 方案 $\mathsf{AEAD}$ 是安全的，若对每个与加密和解密预言机交互的 PPT 敌手 $\mathcal{A}$（解密预言机拒绝解密挑战密文），

$$
\mathrm{Adv}_{\mathsf{AEAD}}^{\mathsf{AEAD}\text{-}\mathsf{sec}}(\mathcal{A}, \lambda) = \left| \Pr\left[b' = b\right] - \frac{1}{2} \right| \leq \negli(\lambda).
$$

上述五个原语定义——抗碰撞哈希（定义 1）、IND-1CCA KEM（定义 2）、EUF-CMA 签名（定义 4）、PRF（定义 5）和安全 AEAD（定义 6）——构成安全模型（§\ref{sec:model}）和形式化安全证明（§\ref{sec:proof}）的构造模块。ML-KEM-768 满足 IND-1CCA（如上注记所述），ML-DSA-65 满足 EUF-CMA [TCHES:DKLLSSS18]，这正是 §\ref{sec:proof} 中博弈跳跃归约所需要的。

---

## 4 协议描述

**通用构造。** PQ-Aliro 协议如图 \ref{high-level ake2} 所示。涉及两个用户：读卡器和用户设备。每一方持有一对长期密钥。如 Aliro 1.0 规范所述："读卡器 SHALL 持有一对密钥（reader-PubK/reader-PrivK），并 MAY 持有包含读卡器公钥的证书（reader-Cert）。读卡器使用此密钥对向用户设备进行自我认证。$\cdots$ 用户设备 SHALL 持有一个 **访问凭证**，其中包含对该用户设备唯一的密钥对（credential-PubK/credential-PrivK）。若存在访问文档，则它 SHALL 包含该访问凭证的公钥。"

PQ-Aliro 协议运行如下。首先，读卡器使用 $\kem.\gen$ 生成一对临时密钥 $({pk}_{e}, {sk}_{e})$。

为表述清晰起见，图 \ref{high-level ake2} 给出使用通用 $\mathsf{KEM}$ 和 $\mathsf{SIG}$ 记号的抽象协议规范；具体的 ML-KEM-768 和 ML-DSA-65 实例化、APDU 层分片以及扩展转录本哈希在下面小节及 §\ref{sec:implementation} 中详述。

**PQ-Aliro 认证密钥交换协议（图示）：**

```
Reader(pk_R, sk_R)                                User Device(pk_UD, sk_UD)
─────────────────                                ────────────────────────
(pk_e, sk_e) <- KEM.Gen(1^λ)
                       ────── pk_e ──────►
                                                  (c_e, K_e) <- KEM.Encap(pk_e)
                       ◄────── c_e ──────
K_e <- KEM.Decap(sk_e, c_e)
trans = H(pk_e || c_e)
σ_Reader <- SIG.Sign(sk_R, trans)
                       ── σ_Reader, cert_R[pk_R] ──►
                                                  if SIG.Verif(pk_R, trans, σ_Reader) == 0: abort

         ┌─────────────────────────────────────────────────────────┐
         │ HS <- HKDF.Extract(trans, K_e)                          │
         │ SKReader <- HKDF.Expand(HS, "R"||trans)                 │
         │ SKDevice <- HKDF.Expand(HS, "UD"||trans)                │
         │ StepUpSK <- HKDF.Expand(HS, "StepUp"||trans)            │
         │ BleSK <- HKDF.Expand(HS, "Ble"||trans)                  │
         │ URSK <- HKDF.Expand(HS, "UWB Ranging"||trans)           │
         └─────────────────────────────────────────────────────────┘

                                                  σ_UD <- SIG.Sign(sk_UD, trans)
                                                  c_UD <- AEAD.Enc(SKDevice, pk_UD || σ_UD)
                       ◄── AUTH1 response: c_UD ──
pk_UD || σ_UD <- AEAD.Dec(SKDevice, c_UD)
if SIG.Verif(pk_UD, trans, σ_UD) == 0: abort
```

*图：PQ-Aliro 认证密钥交换协议。KEM 提供临时密钥建立；签名方案提供双向认证。双方通过 HKDF [RFC5869] 从共享握手秘密 $\mathsf{HS}$ 派生出五个会话密钥（$\mathsf{SKReader}$、$\mathsf{SKDevice}$、$\mathsf{StepUpSK}$、$\mathsf{BleSK}$、$\mathsf{URSK}$）。*

**注 2.** 实现使用 SHAKE256-512 [NIST:FIPS202] 对完整的 TLV 编码 AUTH0 命令和响应（包括 `trans-id`、`reader-id` 和 UD 噪声）进行哈希，而非形式化模型中简化的 $H(pk_e \| c_e)$。这些附加字段在不影响安全论证的前提下增强了会话绑定。具体而言，将 $\msh$ 建模为抗碰撞哈希函数（定义 1）时，将这些字段纳入哈希输入可保持会话新鲜性和转录本唯一性；由于 §\ref{sec:proof} 博弈 1 中的抗碰撞论证适用于转录本的任意扩展，安全归约保持不变。

### 4.1 APDU 消息映射

PQ-Aliro 加速阶段的四条逻辑消息并不直接映射到单一 ISO-DEP APDU。由于后量子密码对象远超 255 字节的 ISO/IEC 14443 APDU 载荷限制，每条消息在传输前必须 *分片* 为一串命令或响应 APDU 链。在读卡器（命令）端，实现为 AUTH0（`INS = 0x80`）和 AUTH1（`INS = 0x81`）设置 `MAX-CHUNK-SIZE = 200 B`，为 LOADCERT（`INS = 0xD1`）设置 `MAX-CHUNK-SIZE = 255 B`。每个中间块携带 `P1 = 0x10` 以表示后续还有数据；最后一块设置 `P1 = 0x00`。在用户设备（响应）端，`MAX-APDU-CHUNK = 240 B`；UD 为每个非末尾响应块附加状态字 `0x61 xx`，为最后一个响应块附加 `0x9000`。读卡器使用 `GET RESPONSE`（`INS = 0xC0`）命令获取后续块。

编码长度超过 255 字节的字段使用 BER-TLV *长形式* 长度编码封装：前导 `0x82` 字节后跟两字节大端长度，每个此类字段增加 4 字节帧开销。这影响 ML-KEM-768 临时公钥（1,184 B → 1,188 B 在线）、KEM 密文（1,088 B → 1,092 B）以及所有 ML-DSA-65 签名（3,309 B → 3,313 B）。

表 \ref{tab:apdu} 列举了默认 ML-DSA-65 $\times$ ML-KEM-768 配置下 PQ-Aliro 单次交易的六条 APDU 层消息。

| 消息 | 方向 | INS | 载荷摘要 | 分块 |
|------|------|-----|----------|------|
| AUTH0 命令 | R→UD | `0x80` | $pk_e$（1,184 B），*trans-id*（16 B），*reader-id*（32 B） | 200 B |
| AUTH0 响应 | UD→R | --- | KEM 密文（1,088 B），UD 噪声（32 B） | 240 B |
| LOADCERT 命令 | R→UD | `0xD1` | Profile0000 证书（约 5,500 B）：$pk_R$（1,952 B），$\sigma$（3,309 B），DN（约 200 B） | 255 B |
| LOADCERT 响应 | UD→R | --- | 仅 `0x9000` | --- |
| AUTH1 命令 | R→UD | `0x81` | 转录本上的 $\sigma_{\mathrm{reader}}$（3,309 B） | 200 B |
| AUTH1 响应 | UD→R | --- | $\mathrm{AEAD}(pk_{\mathrm{ud}} \| \sigma_{\mathrm{ud}} \| \mathit{bmap})$，5,273 B + 16 B 标签 | 240 B |

在该配置下，六条消息共需约 75 个 APDU 并传输约 16.4 KB 应用层数据，相比经典 ECC 实例化的约 1.2 KB 增长约 $14\times$。主要贡献来自 LOADCERT 轮次（约 5,500 B，22 个 APDU）和 AUTH1 响应（约 5,289 B，23 个 APDU），两者均由 ML-DSA-65 公钥和签名的大尺寸驱动。

**与安全模型的关系。** 图 \ref{high-level ake2} 显示双方从握手秘密 $\mathsf{HS}$ 派生出五个会话密钥：$\mathsf{SKDevice}$（NFC 记录层加密）、$\mathsf{SKReader}$、$\mathsf{StepUpSK}$、$\mathsf{BleSK}$ 和 $\mathsf{URSK}$（BLE 和 UWB 信道）。§\ref{sec:model} 中的形式化安全模型将此密钥编排抽象为单一会话密钥 $k_i^s$，对应于 $\mathsf{SKDevice}$——用于 AUTH1 响应 AEAD 加密的密钥，因此其不可区分性是关键安全目标。其余四个密钥（$\mathsf{SKReader}$、$\mathsf{StepUpSK}$、$\mathsf{BleSK}$、$\mathsf{URSK}$）通过从相同 $\mathsf{HS}$ 出发的独立 $\mathsf{HKDF.Expand}$ 调用派生；其安全性在标准密钥分离论证下由 $\mathsf{HKDF.Expand}$ 的 PRF 安全性（定义 5）得出，不需要多阶段 AKE 模型。

---

## 5 安全模型

加速标准阶段旨在实现以下安全目标：

- 双向认证。
- （在长期密钥被攻陷情形下的）前向保密。
- 抗追踪性。
- 完整性与机密性。

**抗追踪性。** 为实现抗追踪性，任何可作为用户设备稳定标识的信息（如其身份）在传输过程中都必须得到机密性保护。用户设备 SHALL 构造一个 `AUTH1 响应`，使得外部观察者通过检查该响应无法推断设备是否知晓某个特定读卡器公钥或访问凭证。具体而言，`AUTH1 响应` 是加密的，从而阻止恶意读卡器提取持久标识符或测试是否与某个特定读卡器存在先前关系。

我们的安全模型建立在 Schäge 等人 [PKC:SchSchLau20] 的框架之上，该框架扩展了 Bellare–Rogaway（BR）模型 [C:BelRog93]，自然地捕获长期密钥攻陷和双向认证。由于 Aliro 并不旨在抵抗 *会话状态* 或 *临时随机性* 的攻陷，我们不向敌手授予这些攻陷能力。为纳入抗追踪性，模型明确考虑用户设备身份的隐私。相比之下，读卡器身份无需隐藏，因为读卡器证书以明文传输。

在 [PKC:SchSchLau20] 的安全模型中，有两种方式选择要使用的身份。然而在我们的场景中只有一种方式，即每一方自行决定身份。此外，需要将参与方的公钥而非待使用身份传输给对方。每一方都有其独特角色，即发起者或响应者。

我们通过为协议中使用的响应者身份引入不可区分性准则来扩展该模型。为此，我们为每个响应方配备两个不同的身份。然后定义一个局部变量，即选择位 $d$，建模响应者使用哪个身份来认证数据。然而，与 [PKC:SchSchLau20] 模型不同的是，我们假设响应者使用的身份始终由其自行决定。

为建模响应者身份的隐私，敌手可通过扩展的 $\mathsf{Test}$ 查询向挑战者请求两种不同的挑战：

- 通过 $\mathsf{Test}(\pi_{i}^{s},\mathsf{KEY})$，请求经典的密钥不可区分性；
- 通过 $\mathsf{Test}(\pi_{i}^{s}, \mathsf{ID})$，请求身份不可区分性挑战。

我们遵循 Schäge 等人定义 $\mathsf{Unmask}$ 查询，允许敌手了解某个预言机中响应者的身份。

**AKE 的计算模型。** 执行环境以安全参数 $\lambda$ 参数化；所有参与方及敌手均建模为 PPT 算法。

*执行环境。* 设 $\mathcal{P} = \{P_1, \ldots, P_n\}$ 为协议参与方集合。每个发起方 $P_i$ 拥有一个身份 $\mathsf{ID}_i$ 和一对长期密钥 $(\sk_i, \pk_i)$。每个响应方 $P_i$ 拥有两个身份 $\mathsf{ID}_i^{0}$ 和 $\mathsf{ID}_i^{1}$，每个身份关联一对长期密钥 $(\sk_i^{b}, \pk_i^{b})$，$b\in \{0,1\}$。在执行过程中，$P_i$ 可生成多个进程实例，称为 *预言机*，记为 $\{\pi_i^s : s \in \{1, \ldots, \ell\}\}$。下标和上标表明 $\pi_i^s$ 是参与方 $P_i$ 的第 $s$ 个预言机；$P_i$ 也称为 $\pi_i^s$ 的 *持有者*。发送首条协议消息的预言机称为 *发起者*；否则为 *响应者*。

*局部与全局变量。* 每个预言机 $\pi_i^s$ 共享其持有者 $P_i$ 的全局变量，并可能使用 $P_i$ 的私钥进行解密或签名。它在自身内存中维护以下局部变量：

- 会话密钥 $k = k_i^s$。
- 持有者 $P_i$ 的角色：$\mathsf{role}= \mathsf{init}/\mathsf{resp}$。
- 身份选择位 $d = d_{i}^{s}\in \{0,1\}$，确定在协议运行中使用身份 $\mathsf{ID}_{i}^{d}$（及 ${sk}_{i}^{d}$）。
- 预期对端指示符 $\mathsf{Partner} = (j,f)$，包含指向 $P_j \in \{P_1, \ldots, P_n\}$（通常称为 *对端*）的指针 $j \in [1;n]$ 和对端身份位 $f\in \{0,1\}$。值 $\mathsf{Partner}_{i}^{s}$ 表示 $\pi_i^s$ 使用公钥 ${pk}_{j}^{f}$ 对所接收消息进行认证。若 $P_i$ 是响应者，我们总设 $f = 0$。

所有局部变量初始化为符号 $\bot$，该符号位于任何有效域之外。在协议运行期间，每个预言机按规范更新其变量，并最终 *接受* 或 *拒绝*。AKE 协议的最终目的是建立会话密钥 $k$。敌手 $\adv \notin \{P_1, \ldots, P_n\}$ 是一种特殊实体，可通过发起查询与所有预言机交互。

**敌手能力。** 授予敌手的能力定义如下：

- $\textsf{Send}(\pi_i^s, m)$：主动敌手可向预言机 $\pi_i^s$ 投递任意消息 $m$，后者按协议响应。若 $m = (\emptyset, P_j)$（其中 $\emptyset$ 为特殊起始字符串），$\pi_i^s$ 被激活为以 $P_j$ 为预期对端的发起者并返回首条协议消息。
- $\textsf{Reveal}(\pi_i^s)$：允许敌手获取 $\pi_i^s$ 计算出的会话密钥 $k_i^s$。仅当 $k_i^s \neq \bot$（即预言机已接受）时才回答此查询。
- $\textsf{Unmask}(\pi_i^s)$：若 $P_i$ 为发起者，敌手获得 $P_i$ 的身份选择位；否则敌手获得 $\pi_i^s$ 中 $P_i$ 对端的身份选择位。
- $\textsf{Corrupt}(P_i)$：敌手可查询 $P_i$ 的任意预言机并接收其长期密钥对 $(\sk_i, \pk_i)$。此后 $P_i$ 被标记为 *已攻陷*。
- $\textsf{Test}(\pi_i^s,m)$：此查询至多发起一次；该预言机随后称为 *Test 预言机*。若 $\pi_i^s$ 尚未接受，返回 $\bot$。否则公平抛掷硬币 $b$，该预言机按如下方式处理：
  - $m = \mathsf{KEY}$：若 $b = 0$ 返回真实会话密钥 $k_i^s$；若 $b = 1$ 返回密钥空间中均匀随机元素。结果称为 *候选密钥*。
  - $m = \mathsf{ID}$：敌手获得 $d_{i}^{s} \oplus b$。此时 Test 预言机的输出称为 *候选身份选择位*。若 $m = \mathsf{ID}$，要求 $P_i$ 是响应者。若 $P_i$ 是发起者，该查询无效，Test 预言机输出 $\bot$。

**原始密钥配对。** 为排除平凡攻击，文献中提出了多种配对定义，从匹配会话 [C:BelRog93] 和会话标识符 [EC:CanKra01]，到贡献式与匹配标识符的区分 [CCS:FisGun14]。最近 Li 和 Schäge [CCS:LiSch17] 证明基于匹配会话的概念易受所谓"无匹配"攻击。因此我们采用其概念上新颖的配对定义，称为 *原始密钥配对*，该定义独立于协议的具体消息流。如 [CCS:LiSch17] 所论证，这种方法可得到概念上更简洁的安全证明，因为只需分析主动攻击的子集。

**定义 7（原始密钥）。** 对一对通信预言机 $\pi_i^s$（发起者）和 $\pi_j^t$（响应者），*原始密钥* 是在完全被动敌手下的协议运行中每个预言机将计算出的会话密钥。我们记此密钥为 $\mathsf{Origin}(\pi_i^s, \pi_j^t)$。

**定义 8（原始密钥配对）。** 两个预言机 $\pi_i^s$ 和 $\pi_j^t$ 称为 *配对* 的，若两者都计算出了其原始密钥 $\mathsf{Origin}(\pi_i^s, \pi_j^t)$。

设 $\mathcal{M}_i^s$ 表示与 $\pi_i^s$ 配对的所有预言机集合。

**安全博弈。** 我们通过一个博弈定义 AKE 协议的安全性。若一个高效（PPT）敌手能以不可忽略的概率赢得博弈，则协议被视为不安全。

**定义 9（安全博弈）。** 以下博弈在挑战者 $\mathcal{C}$ 与敌手 $\adv$ 之间进行：

1. $\mathcal{C}$ 模拟 $n$ 个参与方 $P_i$，$i \in \{1, \ldots, n\}$。对每个发起方 $P_i$，$\mathcal{C}$ 生成身份 $\mathsf{ID}_i$ 和长期密钥对 $(\sk_i, \pk_i)$。对每个响应方 $P_i$，$\mathcal{C}$ 生成两个身份 $\mathsf{ID}_i^{0}$ 和 $\mathsf{ID}_i^{1}$，每个身份关联一对长期密钥 $(\sk_i^{b}, \pk_i^{b})$，$b\in \{0,1\}$。
2. $\adv$ 可自适应地向任意预言机 $\pi_i^s$（$i \in [n]$，$s \in [\ell]$）发起 $\textsf{Send}$、$\textsf{Reveal}$、$\textsf{Unmask}$、$\textsf{Corrupt}$ 查询和一次 $\textsf{Test}$ 查询。
3. 最后，$\adv$ 输出对隐藏位 $b$ 的猜测 $b' \in \{0, 1\}$。

**定义 10（安全 AKE）。** 设 $\adv$ 为与 $\mathcal{C}$ 在上述安全博弈中交互的 PPT 敌手。设 $\adv$ 调用 $\textsf{Test}(\pi_i^s)$（内部使用位 $b$），并设 $\mathsf{Partner}_{i}^{s} = P_j$ 为 $\pi_i^s$ 的预期对端。设 $b'$ 为 $\adv$ 的输出。当 $b = b'$ 且以下条件之一成立时，称 $\adv$ *赢得* 博弈：

- $m = \mathsf{KEY}$，则以下条件全部满足：
  1. $\textsf{Reveal}(\pi_i^s)$ 从未被查询；
  2. 对与 $\pi_i^s$ 配对的任意预言机 $\pi_j^t$，从未发起 $\textsf{Reveal}(\pi_j^t)$ 查询；
  3. 在 $\mathcal{M}_i^s = \emptyset$（即没有配对预言机）时，$\mathsf{Partner}_{i}^{s} = P_j$ 未被攻陷。
- $m = \mathsf{ID}$，则要求：
  - 从未发起 $\mathsf{Unmask}(\pi_i^s)$ 查询；
  - 在 $M_i^s = \emptyset$ 时，$\mathsf{Partner}_{i}^{s} = (P_{j},f)$ 未被攻陷。

定义 $\mathrm{Adv}_{\Pi}^{\mathsf{AKE}\text{-}\mathsf{sec}}(\adv, \lambda) = \bigl|\Pr[b = b'] - 1/2\bigr|$。若对每个 PPT 敌手 $\adv$，

$$
\mathrm{Adv}_{\Pi}^{\mathsf{AKE}\text{-}\mathsf{sec}}(\adv, \lambda) \leq \negli(\lambda),
$$

则称 AKE 协议 $\Pi$ 是 *安全的*。

注意我们的模型提供了原始 Bellare–Rogaway 模型 [C:BelRog93] 中的所有攻击查询。此外，它捕获了涉及私钥攻陷的重要变体。为建模密钥攻陷冒充（KCI）攻击 [C:Krawczyk05]，我们允许敌手在任何时间攻陷 Test 预言机的持有者。允许攻陷预言机的长期密钥（在通常的平凡攻击限制下）建模（完全）完美前向保密。最后，由于对发起者和响应者密钥材料之间的关系未加限制，我们的模型也考虑了反射攻击 [C:Krawczyk05]，即一方与自身通信（例如两个设备共享同一长期密钥）。

---

## 6 安全证明

我们证明 PQ-Aliro 在上述安全模型中是安全的。

**定理 1.** 设 $n$ 为参与方数量，$t$ 为每方会话数。假设签名是 EUF-CMA 安全的，HKDF 是 PRF 安全的，KEM 是 IND-1CCA 安全的。则对任意针对 PQ-Aliro 协议的 PPT 敌手 $\adv$，有

$$
\operatorname{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{sec}}(\adv, \lambda)
\leq 2\cdot \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ 2n^{2} t^{2} \cdot \bigl( \operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\bigr)
+ 2n^{2} t \cdot \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda)
+ \mathrm{Adv}_{\mathsf{AEAD}}^{\mathsf{AEAD}\text{-}\mathsf{sec}}(\mathcal{B}_{5},\lambda),
$$

其中 $\mathcal{B}_{1}$–$\mathcal{B}_{5}$ 分别为针对 $\mathsf{H}$ 的抗碰撞、$\mathsf{SIG}$ 的 EUF-CMA、$\kem$ 的 IND-1CCA、$\hkdf$ 的 PRF 以及 AEAD 安全性的敌手。

*证明草图。* 我们区分两类敌手：

- *发起者* 敌手 $\adv_{\sf init}$，其目标是当 $\pi_{i}^{s}$ 担任发起者角色时正确猜测 $\mathsf{Test}(\pi_{i}^{s}, m)$ 的结果；
- *响应者* 敌手 $\adv_{\sf resp}$，通过预测响应者会话 $\pi_{i}^{s}$ 的 $\mathsf{Test}(\pi_{i}^{s}, m)$ 结果获胜。

我们证明所有派生密钥都是伪随机的，并证明用户设备身份的隐私保护性质。该论证建立在匿名密钥交换范式之上，并辅以用于身份认证的数字签名。

**证明。** 设 $\adv_{\sf init}$ 为任意针对图 \ref{high-level ake2} 所示 PQ-Aliro 协议的概率多项式时间发起者敌手。则显然

$$
\operatorname{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{sec}}(\adv_{\mathsf{init}}, \lambda)
\leq \mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{key}}(\adv_{\sf init},\lambda) + \mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{pp}}(\adv_{\sf init},\lambda),
$$

其中 $\mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{key}}(\adv_{\sf init},\lambda)$ 与 $\mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{pp}}(\adv_{\sf init},\lambda)$ 分别为 $\adv$ 通过正确回答 $\mathsf{Test}(\cdot, \mathsf{KEY})$ 与 $\mathsf{Test}(\cdot, \mathsf{ID})$ 而赢得定义 9 中 AKE 安全博弈的优势。

接下来需证明引理 1 和引理 2。

**引理 1.** 设 $\adv_{\sf init}$ 为任意针对图 \ref{high-level ake2} 所示 PQ-Aliro 协议的概率多项式时间敌手。则 $\adv_{\sf init}$ 正确回答 $\mathsf{Test}(\cdot, \mathsf{KEY})$ 挑战的概率不超过 $\frac{1}{2} + \mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{key}}(\adv_{\sf init},\lambda)$，其中

$$
\mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{key}}(\adv_{\sf init},\lambda)
\leq 2\cdot \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ 2n^{2} t^{2} \cdot \bigl( \operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\bigr)
+ 2n^{2} t \cdot \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda),
$$

其中 $\mathcal{B}_{1}$–$\mathcal{B}_{4}$ 分别为针对 $\mathsf{H}$ 的抗碰撞、$\mathsf{SIG}$ 的 EUF-CMA、$\kem$ 的 IND-1CCA、$\hkdf$ 的 PRF 的敌手。

我们定义一系列在敌手 $\adv$ 与挑战者 $\mathcal{C}$ 之间的博弈，其中挑战者在第一个博弈中运行 PQ-Aliro 协议，通过一系列假设约束博弈之间的差异。最终我们证明 $\adv$ 赢得最后一个博弈的优势为 0。设 $\textsf{Adv}_{i}$ 为 $\adv$ 在博弈 $i$ 中的优势，$i = 1,2, \cdots$。

**博弈 0.** 此博弈恰好是定义 9 中的 AKE 安全实验。故

$$
\mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{key}}(\adv_{\sf init},\lambda) = \mathsf{Adv}_{0}.
$$

**博弈 1.** 若两个诚实会话从不同输入产生相同哈希值，挑战者中止。若出现此类碰撞，我们立即得到敌手 $\mathcal{B}_1$ 通过输出两个碰撞原像打破 $\mathsf{H}$ 的抗碰撞性。故

$$
|\mathsf{Adv}_{0} - \mathsf{Adv}_{1}| \leq \mathrm{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda).
$$

**博弈 2.** 我们猜测将被测试的发起者会话及其预期对端。挑战者均匀随机采样 $(i^\ast, s^\ast, j^\ast) \sample [n] \times [t] \times [n]$。若敌手的 $\textsf{Test}$ 查询不匹配 $(\pi_{i^\ast}^{s^\ast}, j^\ast)$，即 $(i,s) \neq (i^\ast, s^\ast)$，或 $\pi_{i^\ast}^{s^\ast}$ 不是发起者，或其对端不是 $j^\ast$，则中止。有

$$
\mathsf{Adv}_{1} \leq n^{2} t \cdot \mathsf{Adv}_{2}.
$$

**博弈 3.** 定义事件 $\textsf{Forge}$：发起者会话 $\pi_{i^\ast}^{s^\ast}$ 接受来自 $j^\ast$ 方的有效签名 $\sigma^\ast$，但 $j^\ast$ 的任何响应者预言机都未生成该签名。在此博弈中，挑战者在 $\textsf{Forge}$ 发生时中止。**博弈 2** 和 **博弈 3** 在 $\textsf{Forge}$ 发生前完全相同，故

$$
|\mathsf{Adv}_{2} - \mathsf{Adv}_{3}| \leq \Pr[\textsf{Forge}].
$$

我们通过构造针对签名方案的 EUF-CMA 伪造者 $\mathcal{B}_{2}$ 来约束 $\Pr[\textsf{Forge}]$。$\mathcal{B}_{2}$ 接收挑战公钥 $\pk^\ast$ 并为预期对端设 $\pk_{j^\ast} \gets \pk^\ast$。所有其他密钥对都诚实地生成。$\mathcal{B}_{2}$ 为 $\adv$ 运行 AKE 环境，将对 $\pk_{j^\ast}$ 的签名请求转发给自身的签名预言机。若 $\adv$ 在新消息上产生 $\pk_{j^\ast}$ 下的有效签名（转录本防止重放，因为哈希碰撞已被排除），$\mathcal{B}_{2}$ 将其作为伪造提交。故

$$
\text{Pr}[\textsf{Forge}] \leq \mathrm{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda).
$$

合并上述两式得

$$
|\mathsf{Adv}_{2} - \mathsf{Adv}_{3}| \leq \mathrm{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda).
$$

从现在起，我们可假设发起者接收的所有签名实际上都由预期的响应者会话生成。

**博弈 4.** 进一步猜测 $P_{j^\ast}$ 中签发了被 $\pi_{i^\ast}^{s^\ast}$ 接受的签名的具体响应者预言机 $\pi_{j^\ast}^{t^\ast}$。由于每方至多 $t$ 个会话，有

$$
\mathsf{Adv}_{3} \leq t \cdot \mathsf{Adv}_{4}.
$$

**博弈 5.** 在此博弈中，将 $\pi_{j^\ast}^{t^\ast}$ 中的临时共享秘密 $K_{e}$ 替换为均匀随机的 $\widetilde{K}_{e}$。若 $\pi_{i^\ast}^{s^\ast}$ 接收到与 $\pi_{j^\ast}^{t^\ast}$ 发送的相同密文 $c_{e}$，其 $K_{e}$ 也设为 $\widetilde{K}_{e}$。若敌手 $\adv$ 能以不可忽略概率检测到此变化，则我们可构造敌手 $\mathcal{B}_{3}$ 以不可忽略概率打破 $\kem$ 的 IND-1CCA 安全性。

$\mathcal{B}_{3}$ 从其挑战者获得挑战 $({pk}^*, c^*, K^*)$。它将 ${pk}^*$ 嵌入为目标会话的 ${pk}_{e}$，在 $\pi_{j^\ast}^{t^\ast}$ 中设 $c_{e} \gets c^*$，并使用 $K^*$ 作为派生的 $K_{e}$。若 $\adv$ 忠实投递 $c^*$，$\mathcal{B}_{3}$ 将相同 $K^*$ 分配给 $\pi_{i^\ast}^{s^\ast}$；否则它对修改后的密文查询自身的解封装预言机。当 $K^*$ 为真实密钥时，敌手视图与 **博弈 4** 一致；当 $K^*$ 为随机时，视图与 **博弈 5** 一致。故

$$
|\mathsf{Adv}_{4} - \mathsf{Adv}_{5}| \leq \mathrm{Adv}_{\kem}^{\textsf{IND-1CCA}}(\mathcal{B}_{3},\lambda).
$$

**博弈 6.** 接下来，将最终会话密钥 $K = \mathsf{HKDF.Expand}(\mathsf{HS}, \textit{trans})$ 替换为随机 $K^*$。当 $\pi_{i^\ast}^{s^\ast}$ 和 $\pi_{j^\ast}^{t^\ast}$ 都计算出相同握手秘密 $\mathsf{HS}$ 时，也将响应者输出设为 $K^*$。**博弈 5** 和 **博弈 6** 的区分者给出针对 $\mathsf{HKDF}$ 伪随机性的敌手 $\mathcal{B}_{4}$。故

$$
|\mathsf{Adv}_{5} - \mathsf{Adv}_{6}| \leq \mathrm{Adv}_{\mathsf{HKDF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4},\lambda).
$$

此外，在 **博弈 6** 中，$\textsf{Test}$ 查询总返回均匀随机密钥，故

$$
\mathsf{Adv}_{6} = 0.
$$

合并上述安全界，得：

$$
\mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{key}}(\adv_{\sf init},\lambda)
\leq \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ n^{2} t \cdot \Big[
t\cdot \big(\operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\big)
+ \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda)
\Big].
$$

接下来我们证明 PQ-Aliro 的抗追踪性。

**引理 2.** 对任意针对图 \ref{high-level ake2} 中 PQ-Aliro 协议的 PPT 敌手 $\adv_{\sf init}$，其正确回答某预言机的 $\mathsf{Test}(\cdot,\mathsf{ID})$ 挑战的概率至多为 $\frac{1}{2} + \mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{pp}}(\adv_{\sf init},\lambda)$，其中

$$
\begin{aligned}
\mathrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{pp}}(\adv_{\sf init},\lambda)
&\leq \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ n^{2} t^{2}
\cdot \big( \operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\big) \\
&\quad + n^{2} t \cdot \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda) + \mathrm{Adv}_{\mathsf{AEAD}}^{\mathsf{AEAD}\text{-}\mathsf{sec}}(\mathcal{B}_{5},\lambda),
\end{aligned}
$$

其中 $\mathcal{B}_{1}, \mathcal{B}_{2}, \mathcal{B}_{3}, \mathcal{B}_{4}, \mathcal{B}_{5}$ 分别为针对 $\mathsf{H}$ 的抗碰撞、$\mathsf{SIG}$ 的 EUF-CMA、$\kem$ 的 IND-1CCA、$\hkdf$ 的 PRF 以及 $\mathsf{AEAD}$ 安全性的敌手。

*证明。* 响应者身份的隐私性对应于 AEAD 所使用的密钥。因此我们从引理 1 证明中 **博弈 6** 之后的安全博弈开始。

**博弈 0.** 在此博弈中，我们扩展引理 1 证明中的 **博弈 6**。有

$$
\begin{aligned}
\textrm{Adv}_{0}
&\leq \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ n^{2} t^{2}
\cdot \big( \operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\big) \\
&\quad + n^{2} t \cdot \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda).
\end{aligned}
$$

**博弈 0** 之后，$\mathsf{AEAD}$ 的加密密钥由 Test 预言机 $\pi_{i^*}^{s^*}$ 均匀随机选取。

**博弈 1.** 在此博弈中，将 $d = d_{j^*}^{t^*}$ 替换为 $d'=d_{j^*}^{t^*} \oplus 1$，并将响应者使用 ${sk}_{i^*}^{(d)}$ 构造的签名替换为使用另一私钥 ${sk}_{i^*}^{(d')}$ 的签名。若敌手能区分 **博弈 1** 与 **博弈 0**，则可构造敌手 $\mathcal{B}_{5}$ 打破 $\mathsf{AEAD}$ 的安全性。$\mathcal{B}_{5}$ 设 $m_0 \gets ({pk}_{\mathsf{UD}} \| \sigma_\mathsf{UD})$（对应 $\mathsf{ID}_{d}$），$m_1 \gets ({pk}_{\mathsf{UD}} \| \sigma_\mathsf{UD})$（对应 $\mathsf{ID}_{d'}$）。然后 $\mathcal{B}_{5}$ 在 $\mathsf{AEAD}$ 安全实验中将 $m_0,m_1$ 发送给其挑战者。收到挑战者返回的密文 $c^*$ 后，$\mathcal{B}_{5}$ 将 $c^*$ 设为 $\pi_{j^*}^{t^*}$ 的 \textsf{AUTH1 响应} 消息。$\mathcal{B}_{5}$ 生成 $b\sample \{0,1\}$，计算 $w\gets d_{j^*}^{t^*}\oplus b$ 并将 $w$ 作为对 $\mathsf{Test}(\pi_i^s, \mathsf{ID})$ 的响应。收到 $\adv$ 的 $b'$ 后，$\mathcal{B}_{5}$ 将 $w\oplus b'$ 返回给其挑战者。有

$$
\mathrm{Adv}_{0} \leq \mathrm{Adv}_{1} + \mathrm{Adv}_{\mathsf{AEAD}}^{\mathsf{AEAD}\text{-}\mathsf{sec}}(\mathcal{B}_{5},\lambda).
$$

在 **博弈 1** 中，由于响应者身份已被替换，敌手在赢得安全博弈上没有优势。故

$$
\mathrm{Adv}_{1} = 0.
$$

综上，

$$
\begin{aligned}
\textrm{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{pp}}(\adv,\lambda)
&\leq \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ n^{2} t^{2}
\cdot \big( \operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\big) \\
&\quad + n^{2} t \cdot \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda) + \mathrm{Adv}_{\mathsf{AEAD}}^{\mathsf{AEAD}\text{-}\mathsf{sec}}(\mathcal{B}_{5},\lambda).
\end{aligned}
$$

对于响应者敌手，有如下引理。

**引理 3.** 设 $\adv_{\sf resp}$ 为任意针对图 \ref{high-level ake2} 所示 PQ-Aliro 协议的概率多项式时间响应者敌手。则

$$
\begin{aligned}
\operatorname{Adv}_{\mathsf{PQ}\text{-}\mathsf{Aliro}}^{\mathsf{AKE}\text{-}\mathsf{sec}}(\adv_{\mathsf{resp}}, \lambda)
&\leq 2\cdot \operatorname{Adv}_{\mathsf{H}}^{\mathsf{coll}}(\mathcal{B}_{1}, \lambda)
+ 2n^{2} t^{2}
\cdot \big( \operatorname{Adv}_{\mathsf{PRF}}^{\mathsf{PRF}\text{-}\mathsf{sec}}(\mathcal{B}_{4}, \lambda)
+ \operatorname{Adv}_{\kem}^{\mathsf{IND}\text{-}\mathsf{1CCA}}(\mathcal{B}_{3},\lambda)\big) \\
&\quad + 2n^{2} t \cdot \operatorname{Adv}_{\mathsf{SIG}}^{\mathsf{EUF}\text{-}\mathsf{CMA}}(\mathcal{B}_{2}, \lambda) + \mathrm{Adv}_{\mathsf{AEAD}}^{\mathsf{AEAD}\text{-}\mathsf{sec}}(\mathcal{B}_{5},\lambda),
\end{aligned}
$$

其中 $\mathcal{B}_{1}, \mathcal{B}_{2}, \mathcal{B}_{3}, \mathcal{B}_{4}, \mathcal{B}_{5}$ 分别为针对 $\mathsf{H}$ 的抗碰撞、$\mathsf{SIG}$ 的 EUF-CMA、$\kem$ 的 IND-1CCA、$\hkdf$ 的 PRF 以及 $\mathsf{AEAD}$ 安全性的敌手。

证明采用相同的博弈跳跃策略，在归约论证中互换发起者和响应者的角色。重复细节从略。

---

## 7 实现

本节描述 PQ-Aliro 在两个平台上的工程实现：一个作为用户设备（UD）的 Android 主机卡模拟（HCE）应用，以及一个 PC 端 NFC 读卡器应用。两个实现均为开源且以 Java 编写。

**工程挑战。** 将 PQ-Aliro 移植到 Android HCE 引入了协议层不存在的三个挑战：(i) *密钥隔离*：Dilithium 私钥无法原生存储于 Android Keystore，必须防止 JVM 垃圾回收不确定性导致的堆内容泄露；(ii) *死锁预防*：HCE 主线程不能在 ML-DSA 签名上阻塞，否则会触发"应用无响应"（ANR）事件；(iii) *APDU 分片*：后量子对象（ML-DSA-65 签名最大达 3,309 B）必须拆分为 200–255 B 的 APDU 链，并正确传递延续信号。下面各小节描述每个挑战所采用的解决方案。

### 7.1 系统架构

UD 应用运行于 Android 上，通过主机卡模拟框架（`HostApduService`）暴露 PQ-Aliro 小程序。读卡器应用运行于配备 NFC 读卡器外设的标准 PC，通过 ISO/IEC 14443 Type-A 非接触式信道与 UD 通信。双方共享一个公共的消息状态机，依次推进四个阶段：SELECT $\to$ AUTH0 $\to$ LOADCERT $\to$ AUTH1。每个阶段对应一对命令和响应 APDU，详见 §\ref{sec:apdu-mapping}。

所有后量子密码操作由 BouncyCastle 1.78 BCPQC 扩展提供。在 UD 上，提供者在应用启动时注册：

```java
Security.addProvider(new BouncyCastlePQCProvider());
```

PC 读卡器使用相同提供者。无需原生（JNI）代码：ML-KEM 和 ML-DSA 均在 BouncyCastle 库内以纯 Java 实现，使代码库可移植到任何支持 JVM 的平台。UD 独立于 Android Keystore 管理 Dilithium 长期密钥材料，因 Android Keystore API 尚未暴露格基算法支持；因此所有 PQC 密钥操作均保留在应用层，Keystore 仅用于保护对称加密密钥（见 §\ref{sec:impl:isolation}）。

### 7.2 ML-KEM 集成

ML-KEM [NIST:FIPS203] 使用 BouncyCastle BCPQC 中的 CRYSTALS-Kyber [EuroSP:BDKLLSSSS18] 实现。读卡器为每次交易生成一对新鲜临时密钥：

```java
KeyPairGenerator kpg =
    KeyPairGenerator.getInstance("Kyber", "BCPQC");
kpg.initialize(KyberParameterSpec.kyber768, new SecureRandom());
KeyPair ephemeral = kpg.generateKeyPair();
```

1,184 字节的公钥以原始字节数组提取（去除 SPKI 外层封装），并在 AUTH0 命令中通过 TLV 标签 `0x87` 传输。在 UD 上，接收到的字节通过辅助函数 `decodeKyberPublicKey(rawBytes)` 重构为 `KyberPublicKeyParameters` 对象，该函数在将密钥传递给 JCA 层前重新附加算法标识符。

UD 上的封装使用带 `KEMGenerateSpec` 的 `KeyGenerator`：

```java
KeyGenerator kg =
    KeyGenerator.getInstance("Kyber", "BCPQC");
kg.init(new KEMGenerateSpec(readerPublicKey, "AES"));
SecretKeyWithEncapsulation ske =
    (SecretKeyWithEncapsulation) kg.generateKey();
```

1,088 字节的封装 $c$ 在 AUTH0 响应中通过标签 `0x86` 返回。32 字节的共享秘密 $S_e$ 立即以别名 `SSKey` 导入 Android Keystore；原始字节数组随后通过 `Arrays.fill(rawBytes, (byte) 0x00)` 清零，以防秘密残留在 JVM 堆上。读卡器通过 `KEMExtractSpec` 对称地执行解封装以恢复 $S_e$，此后双方按 §\ref{sec:design:kdf} 描述的 KDF 链派生会话密钥 $\mathit{SK-device}$。

### 7.3 ML-DSA 集成与密钥隔离

ML-DSA [NIST:FIPS204] 由 BouncyCastle BCPQC 中的 CRYSTALS-Dilithium 提交 [TCHES:DKLLSSS18] 实现。

**读卡器端签名。** 在 PC 读卡器上，长期 Dilithium 私钥在应用生命周期内保存在进程内存中。签名使用标准 JCA `Signature` 接口：

```java
Signature signer =
    Signature.getInstance("Dilithium", "BCPQC");
signer.initSign(readerPrivateKey);
signer.update(transcriptBytes);
byte[] sigma = signer.sign();
```

**UD 端密钥隔离。** UD 不能安全地在主应用进程中持有 Dilithium 私钥。根本原因是 `DilithiumPrivateKeyParameters` 在 BouncyCastle 1.78 中未实现 `javax.security.auth.Destroyable`；调用 `destroy()` 后密钥材料仍留在 JVM 堆上，直到垃圾回收器决定覆盖该内存区域，且无时序保证。因此被攻陷的主进程可在密钥生成到 GC 回收之间的任意时刻从堆内存读取私钥。

为消除此攻击面，UD 将所有 Dilithium 签名委托给一个专用的 `PQCSignatureService`，该服务运行于通过 Android 清单属性 `android:process=":pqc-signer"` 声明的独立命名进程中。命名进程运行在拥有独立地址空间的单独 Linux 进程中，共享应用 UID，但在物理上将所有私钥操作与主进程内存隔离。这有别于 `android:isolatedProcess="true"`（后者会分配独立 UID 并拒绝文件系统访问）；此处采用命名进程方法，因 `PQCSignatureService` 需要访问 `SharedPreferences` 以获取加密密钥。主进程通过 Binder IPC 接口将待签名字节发送给 `PQCSignatureService`；后者加载加密的私钥（见下文），完全在其自身地址空间内执行签名操作，通过同一 Binder 通道返回 3,309 字节签名，并立即调用 `Process.killProcess(Process.myPid())` 终止自身，将密钥暴露窗口限制在单次签名调用持续时间内（在中端 Android 硬件上通常 $\le 150$ ms）。由于命名进程的整个生命周期——从私钥加载到进程退出——都限定在单次签名调用内，Dilithium 私钥材料绝不会出现在主应用地址空间。主进程的崩溃或被攻陷都无法访问 `:pqc-signer` 地址空间中持有的签名密钥。

**持久化密钥存储。** Dilithium 私钥无法直接存储于 Android Keystore。取而代之，它以 AES-256-GCM [NIST:SP80038D] 加密后存储于 `SharedPreferences`：原始私钥字节在配置时一次性加密（`KEY-KYB-PRIV-CT` 存储密文，`KEY-KYB-PRIV-IV` 存储 GCM IV），256 位 AES 包装密钥保存在 Android Keystore 中，在具备可信执行环境的设备上提供硬件支持保护。隔离进程在每次调用时检索并解密私钥，仅使用一次，然后随进程一起丢弃。

### 7.4 NFC 分片与异步签名

**APDU 分片。** 如 §\ref{sec:apdu-mapping} 所述，PQ-Aliro 载荷必须拆分为 APDU 链以遵守 ISO/IEC 14443 ISO-DEP 帧限制。读卡器发送不超过 200 字节的 AUTH0 和 AUTH1 命令块，以及不超过 255 字节的 LOADCERT 块。UD 发送不超过 240 字节的响应块。长度超过 255 字节的字段使用 BER-TLV 长形式长度前缀 `0x82 len-hi len-lo` 编码，每个受影响字段增加 4 字节开销。此编码应用于 ML-KEM-768 临时公钥（标签 `0x87`）、KEM 密文（标签 `0x86`）、所有 ML-DSA-65 公钥（标签 `0x5A`）以及 AUTH1 命令和 AUTH1 响应中的所有 ML-DSA-65 签名（标签 `0x9E`）。

**为防止 HCE 死锁的异步签名。** Android 的 HCE 框架在专用系统线程上调用 `processCommandApdu()`。若此回调在到隔离进程的同步 Binder 调用上阻塞，系统线程调用栈将被无限期持有：读卡器的 NFC 控制器会让 APDU 交换超时，Android 可能触发"应用无响应"（ANR）对话框。为避免此情况，UD 对 AUTH1 命令采用非阻塞响应策略。在收到 AUTH1 的最后一块时，`processCommandApdu()` 派生一个后台线程执行基于 Binder 的签名调用，并立即向 NFC 系统线程返回两字节响应 `0x61 0x00`（"有更多数据可用，零字节就绪"），立即释放系统线程。读卡器将 `0x61 xx` 解释为延续指示符，进入轮询循环，定期发送 `GET RESPONSE`（`INS = 0xC0`）命令。一旦后台签名线程完成并将完整组装的 AUTH1 响应写入 `responseQueue`，下一次 `GET RESPONSE` 取回首个 240 字节块，随后的 `GET RESPONSE` 命令收集其余 22 个块。

**AEAD 与 IV 管理。** AUTH1 响应载荷由 AES-256-GCM 使用会话密钥 $\mathit{SK-device}$ 保护。GCM IV 派生为 12 字节值 `0x00 00 00 00 00 00 00 01 || ctr`，其中 $\mathit{ctr}$ 为 4 字节大端计数器，初始化为 1，并在会话内每次后续 AEAD 调用时递增。IV 不通过 NFC 传输；双方独立从共享计数器状态派生它。16 字节 GCM 认证标签附加在密文后，得到 5,289 字节的 AUTH1 响应载荷。所有对 Aliro 1.0 规范 [CSA:Aliro10] 中 APDU 命令结构和状态字约定的引用均在本实现中保留。

### 7.5 端到端测试

实现通过一个全面的端到端测试套件验证，覆盖所有九种 PQC 参数组合（$\text{Dilithium}\{2,3,5\} \times \text{Kyber}\{512,768,1024\}$）。测试框架完全在内存中运行：PC 端读卡器和 Android HCE 逻辑在同一个 JVM 进程中实例化，字节数组通道替代物理 NFC 接口。这消除了对真实 NFC 硬件的需求，使测试在标准 CI 环境中完全可复现。

每个测试配置执行 20 项断言，涵盖：(i) KEM 正确性——读卡器通过 `KEMExtractSpec` 恢复的共享秘密 $S_e$ 与 UD 封装的值匹配；(ii) 转录本哈希一致性——$\mathit{transAH0} = \mathrm{SHAKE256\text{-}512}(\mathit{AUTH0\text{-}cmd} \| \mathit{AUTH0\text{-}resp})$ [NIST:FIPS202] 在双方相同；(iii) KDF 一致性——双方派生的 $\mathit{SK-device}$ 相同；(iv) AEAD 往返正确性；(v) 双向 ML-DSA 签名验证（读卡器签名由 UD 验证，UD 签名由读卡器验证）；(vi) 所有 TLV 字段在分片和重组管道中的序列化完整性。所有 20 项断言在全部九种参数组合下均通过，确认实现对于 PQ-Aliro 支持的全部 NIST 安全等级的正确性 [NIST3PQC:Lima21]。除正向断言外，测试框架还包含负向测试用例，验证协议在以下情况下中止：(a) AUTH1 命令中的转录本哈希被篡改；(b) AUTH0 响应中的 ML-KEM 密文无效；(c) 读卡器证书上的 ML-DSA-65 签名被伪造。

这些实现结果为下一节给出的定量通信开销分析和 NFC 时延估计提供了基础。

---

## 8 评估

我们从三个互补维度评估 PQ-Aliro：后量子密码原语引入的原始通信开销、由此产生的 NFC 传输时延，以及所有九种参数组合下安全等级与开销的权衡。所有字节数源自已实现的 APDU 消息结构的静态分析；核心开销数据无需物理测试床测量，但我们将经验验证视为未来工作。

### 8.1 通信开销分析

**方法论。** 本节自始至终采用以下计数约定。(1) *应用层字节* 指 TLV 载荷体，不含 APDU 命令头和状态字。(2) *链路字节* 加上每帧的帧成本：每个命令 APDU 6 B（CLA $\|$ INS $\|$ P1 $\|$ P2 $\|$ Lc $\|$ Le），每个响应 APDU 2 B 状态字（SW1 $\|$ SW2，由 Android HCE 自动附加）。(3) NFC 物理层开销——PCB 字节、CRC 和 SOF/EOF 帧——不计入，故数据代表 ISO-DEP 层上 *有用载荷的上界* [CSA:Aliro10]。(4) LOADCERT 载荷大小为估计值；实际值随读卡器证书中 X.500 可分辨名称长度有 $\pm 10\%$ 的波动。(5) AES-GCM IV（12 B）由计数器派生，从不在链路上传输，故不贡献链路字节。

每字段开销的一个显著来源是 BER-TLV 长形式长度编码。任何编码长度超过 255 B 的字段需要三字节长度头（`0x82` $\|$ len-hi $\|$ len-lo）而非一字节，每字段贡献 4 B 的标签-长度开销。由于每个 PQC 对象——Kyber 公钥（$\ge 800$ B）、密文（$\ge 768$ B）、Dilithium 公钥（$\ge 1312$ B）和签名（$\ge 2420$ B）——均超过此阈值，每个此类字段都承担此惩罚。

**默认配置：ML-DSA-65 $\times$ ML-KEM-768。** 表 \ref{tab:overhead_default} 分解了默认参数集（Dilithium3 $\times$ Kyber768，NIST 安全等级 3 [NIST:FIPS203,NIST:FIPS204]）在加速流程六个逻辑消息交换中的通信开销。

| 消息 | APDU 数 | 应用字节 | 链路字节 |
|------|---------|----------|----------|
| AUTH0 命令 | 7 | 1,250 | 1,292 |
| AUTH0 响应 | 5 | 1,126 | 1,136 |
| LOADCERT 命令 | 约 22 | 约 5,500 | 约 5,632 |
| LOADCERT 响应 | 1 | 2 | 4 |
| AUTH1 命令 | 17 | 3,316 | 3,418 |
| AUTH1 响应 | 23 | 5,289 | 5,335 |
| **总计** | **约 75** | **约 16,483** | **约 16,817 ≈ 16.4 KB** |

*表：默认 PQ-Aliro 配置（ML-DSA-65 $\times$ ML-KEM-768，NIST 等级 3）的逐阶段通信开销。分块大小：读卡器 $\le 200$ B/APDU，UD $\le 240$ B/APDU。*

**与 ECC Aliro 比较。** 经典 ECC Aliro 流程（P-256 曲线）每次交易传输约 1.2 KB（包括 65 B 的临时 ECDH 公钥、约 72 B 的 ECDSA 签名、约 1,000 B 的 P-256 证书，以及约 8 个 APDU 的帧开销，总计约 1.2 KB）：65 B 的 EC 临时公钥、约 72 B 的 ECDSA 签名以及长期证书。PQ-Aliro（D3 $\times$ K768）的链路字节约 $16.4/1.2 \approx \mathbf{14\times}$ 更多。主要贡献来自 Dilithium3 公钥（1,952 B，约为 65 B EC 密钥的 $30\times$）和 Dilithium3 签名（3,309 B，约为 72 B ECDSA 签名的 $46\times$）；Kyber768 密文（1,088 B）约为 ECDH 临时点的 $17\times$。最大的单一贡献阶段是 LOADCERT，单独约占 5.5 KB（约占总量的 33%），因为它必须传输读卡器完整的 Dilithium 证书。

**LOADCERT 消除。** 当用户设备已缓存读卡器证书（通过 `key-slot` 索引或预配置）时，可完全跳过 LOADCERT 交换，节省约 22 个 APDU 和 5.5 KB。总开销降至约 52 个 APDU / 11.2 KB——32% 的降幅，在保持 NIST 等级 3 安全的同时基本弥合了与 D2 配置之间的差距。

**所有参数组合。** 表 \ref{tab:overhead_configs} 总结了九种 ML-DSA/ML-KEM 参数组合。在每个 Dilithium 层级内，Kyber 变体带来 0.7–1.7 KB 的适度差异；跨 Dilithium 层级（D2 $\to$ D3 $\to$ D5）则引入 4–5 KB 的跳跃，完全由 Dilithium 公钥和签名尺寸的增长驱动 [NIST:FIPS204]。

| 配置 | NIST 等级 | 总 APDU 数 | 总字节 | 启用 `key-slot` |
|------|----------|-----------|--------|----------------|
| ML-DSA-44 $\times$ ML-KEM-512 | 2 | 55 | 约 11.9 KB | 约 7.9 KB |
| ML-DSA-44 $\times$ ML-KEM-768 | 2 | 58 | 约 12.6 KB | 约 8.6 KB |
| ML-DSA-44 $\times$ ML-KEM-1024 | 2 | 62 | 约 13.5 KB | 约 9.5 KB |
| ML-DSA-65 $\times$ ML-KEM-512 | 3 | 72 | 约 15.8 KB | 约 10.3 KB |
| **ML-DSA-65 $\times$ ML-KEM-768** | **3** | **75** | **约 16.4 KB** | **约 11.2 KB** |
| ML-DSA-65 $\times$ ML-KEM-1024 | 3 | 79 | 约 17.3 KB | 约 11.8 KB |
| ML-DSA-87 $\times$ ML-KEM-512 | 5 | 95 | 约 21.6 KB | 约 14.1 KB |
| ML-DSA-87 $\times$ ML-KEM-768 | 5 | 98 | 约 22.3 KB | 约 14.8 KB |
| ML-DSA-87 $\times$ ML-KEM-1024 | 5 | 102 | 约 23.2 KB | 约 15.7 KB |

*表：所有九种 PQC 参数组合的通信开销（含 LOADCERT）。安全等级遵循 NIST PQC 标准 [NIST:FIPS203,NIST:FIPS204]。"启用 key-slot"列显示通过在用户设备上预缓存读卡器证书消除 LOADCERT 后的开销（等级 2 节省约 4.0 KB，等级 3 节省约 5.5 KB，等级 5 节省约 7.5 KB）。*

### 8.2 NFC 传输时延

**注。** 本小节所有时延数据均为基于链路字节数和已发布的 NFC 比特率规范得出的理论估计；未进行物理 NFC 测试床测量。数据代表总交易时间的下界；经验验证范围见 §\ref{subsec:limitations}。

**物理层模型。** ISO/IEC 14443 Type A 支持四种比特率：106、212、424 和 848 kbit/s。在 106 kbit/s 下，块级开销和周转延迟将有效应用吞吐量降至约 8 kB/s；更高比特率以准线性方式扩展，并附加适度的帧成本 [CSA:Aliro10]。Android HCE 通常与兼容读卡器协商 424 kbit/s，使该速率成为基于智能手机的门禁部署的预期工作点。

**估计方法论与范围。** 表 \ref{tab:latency} 中的时延数据仅计入比特传输时间（总链路字节除以比特率）。每 APDU 往返开销——包括 ISO/IEC 14443-4 [ISOIEC14443] 帧等待时间（FWT）和读卡器轮询间隔——未计入；对于 75 个 APDU 的交换，这些往返成本在真实硬件上可能增加数百毫秒。此外，实现的异步签名机制引入了每个签名周期 500 ms 的固定轮询间隔（每个 AUTH1 命令一个周期），为真实设备时延额外贡献约 0.5 s。因此所报告数据应视为总交易时间的下界。

**传输时间估计。** 表 \ref{tab:latency} 给出三种代表性参数集在三种比特率下的估计 NFC 传输时间。这些值仅反映 APDU 链路字节，不包括读卡器端和 UD 端的密码计算时间。

| 比特率 | D3$\times$K768（16.4 KB） | D2$\times$K512（11.9 KB） | D5$\times$K1024（23.2 KB） |
|--------|---------------------------|---------------------------|----------------------------|
| 106 kbit/s | 约 2.0 s | 约 1.5 s | 约 2.9 s |
| 424 kbit/s | 约 0.5 s | 约 0.4 s | 约 0.7 s |
| 848 kbit/s | 约 0.25 s | 约 0.20 s | 约 0.36 s |

*表：估计的 NFC 传输时间（仅链路字节，不含密码计算）。数据为理论估计；物理 CRC/PCB 帧未计入。*

**密码计算开销。** 上述传输数据必须加上设备上的计算时间。在中端 Android 设备上，ML-DSA-65 签名生成约需 50–100 ms，签名验证约 5 ms，与 Android 硬件上 Kyber/Dilithium 家族的报告结果一致 [NIST3PQC:Lima21,TCHES:BKHYY22]。ML-KEM 封装和解封装各贡献 $<$5 ms。结合传输和计算，默认 D3$\times$K768 配置在 424 kbit/s 下的总预期时延落在 **0.6–1.5 s** 范围内。

**用户体验影响。** 相比之下，经典 ECC Aliro 传输约 1.2 KB，在 424 kbit/s 下端到端完成约 0.2 s。因此 PQ-Aliro 引入 3–7$\times$ 的时延增长。在低端（0.6 s），额外延迟在正常刷门手势中可能难以察觉；在高端（1.5 s），接近门禁交互的用户体验阈值，这促使了 §\ref{sec:eval:overhead} 描述的 LOADCERT 消除优化。我们注意到表 \ref{tab:latency} 中所有数据均为理论估计；在物理 NFC 硬件上的经验测量是重要的未来工作。

### 8.3 安全等级与开销的权衡

表 \ref{tab:overhead_configs} 中的九种配置跨越三个 NIST 安全等级和 1.95$\times$ 的开销范围（11.9 KB 到 23.2 KB）。在安全-开销空间上的 Pareto 分析识别出以下部署画像。

**NIST 等级 2（D2 配置，11.9–13.5 KB）。** D2$\times$K512 配置达到约 11.9 KB 的最低开销——相对于默认配置减少 27%——同时仍满足 NIST 等级 2 安全目标 [NIST:FIPS203,NIST:FIPS204]。此画像适用于时延敏感的部署（如高吞吐闸机），且组织威胁模型接受相对于未来密码分析进展的较小安全裕度。

**NIST 等级 3（D3 配置，15.8–17.3 KB）。** D3$\times$K768 配置代表安全与开销之间的 Pareto 最优平衡。它为密钥封装和签名原语都提供 NIST 安全等级 3（AES-192 等价，约 96 比特后量子安全），其约 16.4 KB 的占用在 424 kbit/s 下可管理。我们将其作为 PQ-Aliro 的 *默认* 配置 [NIST:FIPS203,NIST:FIPS204]。

**NIST 等级 5（D5 配置，21.6–23.2 KB）。** D5$\times$K1024 配置提供最高的可用安全（256 比特后量子），代价是相对于默认配置 +41% 的开销。在 106 kbit/s 下估计 2.9 s 的传输时间在最慢 NFC 速率下不切实际，但在 424 kbit/s 下完全可行（约 0.7 s），且假设理论时延估计在真实硬件上成立，将适用于高保障安装，如数据中心门禁或政府设施。

**小结。** 部署方可根据自身威胁模型选择最匹配的参数集：D2$\times$K512 适用于时延敏感或资源受限环境，D3$\times$K768 作为平衡的默认，D5$\times$K1024 适用于最大长期安全。`key-slot` 优化（§\ref{sec:eval:overhead}）正交地在所有层级上减少约 5.5 KB 开销，只要部署架构允许在读卡器上预配置证书至用户设备，就应启用此优化。

上述开销数据与时延估计为下一节讨论的优化策略和部署权衡提供了动机。

---

## 9 讨论与优化

### 9.1 优化策略

默认 PQ-Aliro 配置（ML-DSA-65 $\times$ ML-KEM-768）在 75 个 APDU 交换中产生约 16.4 KB 开销——约为经典基于 ECC 的 Aliro 流程开销的 $14\times$。我们识别三种互补策略可大幅降低此成本。

**通过 `key-slot` 消除 LOADCERT。** Aliro 1.0 规范 [CSA:Aliro10] 定义了 `key-slot` 字段，允许用户设备（UD）引用已存储在其凭证库中的读卡器证书，从而绕过显式的 `LOADCERT` 交换。消除 LOADCERT 移除约 22 个 APDU 交换并节省约 5.5 KB，将总占用降至约 11.2 KB / 52 个 APDU——32% 的降幅。此策略适用于企业部署，其中一组固定的读卡器被信任，其 Dilithium 证书可在注册时预配置。权衡是运营性的：它需要带外证书配置基础设施，并使读卡器证书轮换复杂化。然而，对于公司办公室门等高频访问场景，跳过 22 轮子流程的时延收益显著。

**降级至 NIST 安全等级 2。** FIPS 203 [NIST:FIPS203] 和 FIPS 204 [NIST:FIPS204] 均在安全等级 2、3、5 下指定参数集。从等级 3 组合（ML-DSA-65 $\times$ ML-KEM-768）切换到等级 2 组合（ML-DSA-44 $\times$ ML-KEM-512）可获得约 11.9 KB 的总开销——比默认配置节省 27%。此配置适用于风险较低的部署（如健身房或图书馆门禁），其中量子威胁时间表较远，但不适用于数据中心入口或政府设施等高保障用例，这些场景需要等级 3 或等级 5 保护。

**Profile0000 证书压缩。** Aliro Profile0000 证书使用自定义 DER 编码，包含总计约 200 字节的完整可分辨名称（DN）字段。在 `AUTH1 响应` 中使用 `key-slot` 索引替代内联传输 UD 的 Dilithium 公钥的场景下，可额外节省约 1.9 KB。进一步压缩 DN 字段或采用紧凑证书格式将在不损害 Aliro 规范 [CSA:Aliro10] 定义的认证语义的前提下提供增量收益。

### 9.2 局限性

本文结果存在若干重要保留。

**仅理论字节数。** 所有通信开销数据均源自静态 APDU 层字节核算，不包括 ISO/IEC 14443 物理层帧（PCB 字节、CRC-16、SOF/EOF 分隔符）。实际空口数据量会略高；各配置之间的相对比较仍然有效。

**无真实 NFC 硬件。** 端到端协议执行在软件仿真环境中验证：基于 PC 的读卡器进程与运行 HCE 栈的 Android 模拟器通信。真实 NFC 硬件引入仿真未捕获的射频场建立时延、读卡器轮询间隔和设备特定的射频栈开销。真实部署的时延数据可能与我们的估计不同。

**估计的密码时序。** §\ref{sec:evaluation} 中引用的 ML-DSA-65 签名时间 50–100 ms 是从可比 ARM 处理器上已发表的基准测试外推得出；未在测试设备上直接测量。性能会随 JVM 预热状态、并发后台进程以及目标手机的特定 Android 版本和安全芯片配置而变化。

**证书预配置复杂性。** LOADCERT 消除优化假设凭证颁发者能在注册时可靠地向每个 UD 预配置读卡器证书，并在证书更新时保持同步。此要求增加了非平凡的基础设施负担，在开放式部署中可能不可行——那里任何合规读卡器都应能在没有预先配对的情况下与任何 UD 交互。

**key-slot 模式下的证书撤销。** `key-slot` 优化在用户设备上预配置读卡器证书。未指定撤销机制：若读卡器设备被攻陷或退役，已缓存其证书的用户设备将继续向其认证。在 NFC 会话内纳入证书状态检查（如 OCSP 装订或短期证书）将增加开销，但对于读卡器可能被撤销的企业部署至关重要。

**读卡器端可链接性。** 我们的抗追踪性定理（§\ref{sec:model}）仅捕获 *用户设备身份隐私*；读卡器端身份明确在模型范围之外，因 `reader-identifier` 在 Aliro 1.0 规范 [CSA:Aliro10] 设计上以明文在 AUTH0 命令中传输。`reader-identifier` 字段（32 字节）在 `AUTH0` 命令中以明文传输，允许被动窃听者关联固定读卡器位置的所有 NFC 交易。虽然 Aliro 1.0 规范 [CSA:Aliro10] 设计上以未加密方式传输此字段，但它构成一种读卡器端可链接性，我们的抗追踪性模型（§\ref{sec:design:kdf}）未捕获。未来工作可考虑从 `transaction-identifier` 派生会话特定的读卡器假名以缓解此泄露。

**中继攻击暴露。** PQ-Aliro 与所有基于 NFC 的门禁协议一样，依赖 ISO/IEC 14443 [ISOIEC14443] 物理层范围限制（约 10 cm）而非密码学距离边界来防止中继攻击。中继敌手通过更长距离隧道 NFC 信道可在合法用户不知情的情况下开门。`transaction-identifier` 提供重放保护但不提供中继保护。集成距离边界机制 [CSA:Aliro10] 或 UWB 测距（Aliro 规范中可用）可应对此威胁。

**单一传输协议。** 当前实现和分析仅覆盖 NFC（ISO/IEC 14443）。Aliro 规范还定义了 BLE 和 UWB 传输路径，每个都有独特的帧大小约束和会话密钥派生流程。PQC 对这些传输的开销影响是一个独立的开放问题。

### 9.3 未来工作

若干方向扩展本文贡献。

**真实硬件评估。** 在商用支持 NFC 的智能手机和读卡器上测量端到端协议时延——包括射频场建立时间和读卡器端处理——将验证我们的理论估计并暴露实现特定的瓶颈。

**Falcon-512 集成。** 在代表性 Android 设备上实现并基准测试 PQ-Aliro 的 Falcon-512（FIPS 206）变体，将确定显著的签名尺寸优势是否在实践中转化为可接受的密钥生成时延。

**BLE 和 UWB 传输。** 将 PQC 集成扩展到 Aliro BLE 和 UWB 流程——特别是 BleSK 和 URSK 会话密钥的派生——将提供跨所有支持传输的量子安全 Aliro 的完整图景。

**与 CCC Digital Key 3.0 的互操作性。** 探索与 CCC Digital Key 3.0 生态 [CCC:DigKey3] 的跨规范互操作性，将澄清鉴于两者重叠的部署场景，是否能在两个标准之间实现统一的 PQC 升级路径。

**key-slot 配置协议。** 设计并形式化支持大规模 LOADCERT 消除的证书预配置协议——支持证书轮换和撤销——将使此优化可在真实企业门禁基础设施中部署。核心形式化挑战是在 AKE 安全博弈中建模预配置的读卡器证书：LOADCERT 轮次必须由证书注册预言机替换，同时捕获正确性（UD 仅接受来自授权颁发者的证书）和隐私（预配置证书不泄露读卡器-UD 配对历史）。此模型中的证书轮换和撤销仍是开放问题。

---

## 10 结论

本文提出 PQ-Aliro，即 Aliro NFC 门禁协议 [CSA:Aliro10] 的首个完整后量子变体。我们的设计保留三轮加速流程（AUTH0/LOADCERT/AUTH1），用 NIST 标准化的对应物替换经典原语：ECDH 被 ML-KEM-768 取代，ECDSA 被 ML-DSA-65 取代 [NIST:FIPS203,NIST:FIPS204]，会话机密性则由 HKDF（HMAC-SHA256 + HKDF-Expand）和 AES-256-GCM 维持。在 Schäge 等人 [PKC:SchSchLau20] 的 AKE 安全模型中，我们证明 PQ-Aliro 实现双向认证、（在长期密钥被攻陷情形下的）前向保密和身份隐私，安全界约至 ML-KEM-768 的 IND-1CCA 安全性、ML-DSA-65 的 EUF-CMA 安全性、HKDF 的 PRF 安全性以及 AES-256-GCM 的 AEAD 安全性。使用 IND-1CCA——比 IND-CCA2 更弱的假设——即已足够，因 PQ-Aliro 每会话恰好执行一次 KEM 封装，可得到比基于完整 CCA2 的证明更紧的安全归约。

我们使用 BouncyCastle 1.78 BCPQC 开发了面向 Android HCE 和 PC NFC 读卡器的完整开源实现，包含 9 种 PQC 配置下 20 项断言的端到端测试套件全部无例外通过。关键工程发现——在专用 Android 命名进程内通过立即自我终止实现 Dilithium 私钥隔离，以及通过异步 ML-DSA 签名防止 HCE 主线程死锁——已记录成文，以益于将格基协议移植到 Android HCE 环境的从业者。

此升级的主要代价是通信开销。默认 D3$\times$K768 配置在 75 个 APDU 上交换约 16.4 KB，约为原始基于 ECC 流程（约 1.2 KB）占用的 $14\times$，膨胀大部分归因于 ML-DSA-65 较大的公钥和签名。启用 `key-slot` 优化——一旦证书缓存在读卡器上即抑制 LOADCERT 轮次——可将其降至约 11.2 KB，节省 32%。或者，更轻量的 D2$\times$K512 配置在 NIST 安全等级 2 下达到约 11.9 KB。在 424 kbit/s NFC 比特率下，预计 0.6–1.5 s 的端到端时延对绝大多数物理门禁场景仍可接受。

在 NIST IR 8547 [NIST:IR8547] 要求 2035 年前迁移到后量子算法的背景下，加固 NFC 门禁系统以抵御"先收集后解密"攻击的紧迫性不容忽视。PQ-Aliro 为构建于 Aliro [CSA:Aliro10] 生态的部署提供了一条具体、符合标准的迁移路径。未来工作将聚焦于生产 NFC 硬件上的经验时延测量、评估 Falcon-512 作为更轻量签名替代方案，以及将 PQ-Aliro 握手扩展到 Aliro 规范中已定义的 BLE 和 UWB 传输信道。
