# PQ-Aliro 理论通信开销分析

> 基于 `Reader.java`（PC 侧）与 `UserDevice.java`（UD 侧）当前实现的逐字段静态计算。
> 默认配置：`Dilithium3 × Kyber768`（NIST Level 3）。同时给出 Dilithium {2,3,5} × Kyber {512,768,1024} 全部 9 组对比。
> 数值不含 NFC 物理层（ISO/IEC 14443 Type A/B）帧头与 CRC。

---

## 1. 协议总览

PQ-Aliro Expedited 流程逻辑上分 3 个命令-应答对，因 APDU 单包数据上限受限（Reader 端 200 B chunk，UD 端 240 B chunk），每一对都会被分片成多条 APDU：

| # | 逻辑消息 | INS | 方向 | 主体内容 |
|---|---|---|---|---|
| 1 | AUTH0 Command | `0x80` | Reader → UD | Kyber 临时公钥 + trans_id + reader_id + 策略字段 |
| 2 | AUTH0 Response | — | UD → Reader | Kyber 封装密文 + UD 随机噪声 |
| 3 | LOADCERT Command | `0xD1` | Reader → UD | Reader 长期 Dilithium 证书（Profile0000 DER） |
| 4 | LOADCERT Response | — | UD → Reader | 状态字 `9000` |
| 5 | AUTH1 Command | `0x81` | Reader → UD | Reader Dilithium 签名（对 transcript） |
| 6 | AUTH1 Response | — | UD → Reader | AEAD(UD 长期公钥 ‖ UD 签名 ‖ signaling_bitmap) |

> **可选优化**：若 UD 端预置了 Reader 证书或使用 `key_slot` 索引，则 LOADCERT 步骤可省去 —— 可节省 ~5–7 KB。

---

## 2. 关键密码学对象的字节大小

| 对象 | Kyber512 / Dilithium2 | Kyber768 / Dilithium3 | Kyber1024 / Dilithium5 |
|---|---|---|---|
| Kyber 公钥 (`pk_e`) | 800 B | **1184 B** | 1568 B |
| Kyber 密文 (`encap`) | 768 B | **1088 B** | 1568 B |
| Kyber 共享密钥 (`Se`) | 32 B | 32 B | 32 B |
| Dilithium 公钥 (`PK_ud`) | 1312 B | **1952 B** | 2592 B |
| Dilithium 签名 (`σ`) | 2420 B | **3309 B** | 4627 B |

| 协议固定字段 | 大小 |
|---|---|
| `transaction_identifier` | 16 B |
| `reader_identifier` | 32 B (group 16 ‖ subgroup 16) |
| `UD_noise` | 32 B |
| `transAH0` (SHAKE256-512) | 64 B |
| `SK_device` (HKDF-Expand) | 32 B |
| `signaling_bitmap` | 2 B |
| AES-GCM tag | 16 B |
| BER-TLV 长格式头 | 4 B（`0x82` + 2 字节长度，长度 > 255 时） |
| Command APDU 框架 | 6 B（CLA‖INS‖P1‖P2‖Lc‖Le） |
| Response APDU 状态字 | 2 B（SW1‖SW2，HCE 自动追加） |

---

## 3. 逐阶段载荷拆解（Dilithium3 × Kyber768）

### 3.1 AUTH0 Command（Reader → UD）

| 字段 | 字节构成 | 长度 |
|---|---|---|
| `0x41‖0x01‖cmd_params` | command_parameters | 3 B |
| `0x42‖0x01‖auth_policy` | authentication_policy | 3 B |
| `0x5C‖0x02‖ver` | expedited_phase_protocol_version | 4 B |
| `0x87‖0x82‖<len_hi><len_lo>‖pk_e` | Reader Kyber768 临时公钥 | 4 + 1184 = **1188 B** |
| `0x4C‖0x10‖trans_id` | 16 B 事务 ID | 18 B |
| `0x4D‖0x20‖reader_id` | 32 B Reader ID | 34 B |
| **TLV 主体合计** | | **1250 B** |

**分片**：`ceil(1250 / 200) = 7` 个 APDU，每个 APDU 框架 6 B（CLA‖INS‖P1‖P2‖Lc‖…‖Le）。
**链路总字节** = `1250 + 7 × 6 = 1292 B`

### 3.2 AUTH0 Response（UD → Reader）

| 字段 | 长度 |
|---|---|
| `0x86‖0x82‖<len>‖encap` (Kyber768 密文 1088 B) | **1092 B** |
| `0x43‖0x20‖UD_noise` | 34 B |
| **TLV 主体合计** | **1126 B** |

**分片**：`ceil(1126 / 240) = 5` 个响应 APDU，HCE 每包追加 2 B 状态字。
**链路总字节** = `1126 + 5 × 2 = 1136 B`

### 3.3 LOADCERT Command（Reader → UD）

Reader 证书以 `Profile0000` 自定义 DER 结构传输，包含字段：

| 字段 | 估计长度 (D3) |
|---|---|
| Dilithium 公钥 raw | 1952 B |
| Dilithium 签名 BitString | 3309 B |
| Serial Number | ~10 B |
| Issuer DN | ~80 B |
| notBefore + notAfter | ~30 B |
| Subject DN | ~80 B |
| Profile header + 各 TLV 标签 | ~50 B |
| **合计估算** | **≈ 5500 B** |

**分片**：`ceil(5500 / 255) = 22` 个 APDU。
**链路总字节** ≈ `5500 + 22 × 6 ≈ 5632 B`

> 实测时该值会随证书 Subject/Issuer DN 长度浮动 ±200 B，以下表格按 5500 B 估算。

### 3.4 LOADCERT Response（UD → Reader）

UD 仅返回 `0x90 0x00`（user-payload），HCE 再追加 2 B SW。**约 4 B**，1 个 APDU。

### 3.5 AUTH1 Command（Reader → UD）

| 字段 | 长度 |
|---|---|
| `0x41‖0x01‖cmd_params` | 3 B |
| `0x9E‖0x82‖<len>‖σ_reader` (Dilithium3 签名 3309 B) | **3313 B** |
| **TLV 主体合计** | **3316 B** |

**分片**：`ceil(3316 / 200) = 17` 个 APDU。
**链路总字节** = `3316 + 17 × 6 = 3418 B`

### 3.6 AUTH1 Response（UD → Reader）

**AEAD 明文**：

| 字段 | 长度 |
|---|---|
| `0x5A‖0x82‖<len>‖PK_ud` (Dilithium3 公钥 1952 B) | **1956 B** |
| `0x9E‖0x82‖<len>‖σ_ud` (Dilithium3 签名 3309 B) | **3313 B** |
| `0x5E‖0x02‖signal_bitmap` | 4 B |
| **明文合计** | **5273 B** |

**AEAD 输出** = 明文 + 16 B GCM tag = **5289 B**（IV 不入流，由 counter 派生）。

**分片**：`ceil(5289 / 240) = 23` 个响应 APDU。
**链路总字节** = `5289 + 23 × 2 = 5335 B`

---

## 4. 单次交易总开销

### 4.1 默认配置（Dilithium3 × Kyber768）

| 阶段 | APDU 数 | 应用层字节 | 含 APDU/SW 开销的链路字节 |
|---|---:|---:|---:|
| AUTH0 Command | 7 | 1 250 | 1 292 |
| AUTH0 Response | 5 | 1 126 | 1 136 |
| LOADCERT Command | ~22 | ~5 500 | ~5 632 |
| LOADCERT Response | 1 | 2 | 4 |
| AUTH1 Command | 17 | 3 316 | 3 418 |
| AUTH1 Response | 23 | 5 289 | 5 335 |
| **合计** | **~75** | **~16 483** | **~16 817 ≈ 16.4 KB** |

**省略 LOADCERT 时**（key_slot 模式）：约 **52 APDU / 11.2 KB**。

### 4.2 各等级组合对比（含 LOADCERT，估算）

| Dilithium × Kyber | AUTH0↓ | AUTH0↑ | LOADCERT↓ | AUTH1↓ | AUTH1↑ | **总 APDU** | **总字节** |
|---|---:|---:|---:|---:|---:|---:|---:|
| D2 × K512 | 866 B / 5 | 806 B / 4 | 4 000 B / 16 | 2 427 B / 13 | 3 760 B / 16 | **55** | **~11.9 KB** |
| D2 × K768 | 1 250 B / 7 | 1 126 B / 5 | 4 000 B / 16 | 2 427 B / 13 | 3 760 B / 16 | **58** | **~12.6 KB** |
| D2 × K1024 | 1 634 B / 9 | 1 606 B / 7 | 4 000 B / 16 | 2 427 B / 13 | 3 760 B / 16 | **62** | **~13.5 KB** |
| D3 × K512 | 866 B / 5 | 806 B / 4 | 5 500 B / 22 | 3 316 B / 17 | 5 289 B / 23 | **72** | **~15.8 KB** |
| **D3 × K768 (default)** | **1 250 B / 7** | **1 126 B / 5** | **5 500 B / 22** | **3 316 B / 17** | **5 289 B / 23** | **75** | **~16.4 KB** |
| D3 × K1024 | 1 634 B / 9 | 1 606 B / 7 | 5 500 B / 22 | 3 316 B / 17 | 5 289 B / 23 | **79** | **~17.3 KB** |
| D5 × K512 | 866 B / 5 | 806 B / 4 | 7 500 B / 30 | 4 634 B / 24 | 7 247 B / 31 | **95** | **~21.6 KB** |
| D5 × K768 | 1 250 B / 7 | 1 126 B / 5 | 7 500 B / 30 | 4 634 B / 24 | 7 247 B / 31 | **98** | **~22.3 KB** |
| D5 × K1024 | 1 634 B / 9 | 1 606 B / 7 | 7 500 B / 30 | 4 634 B / 24 | 7 247 B / 31 | **102** | **~23.2 KB** |

> 表中"X B / N"表示"应用层字节 / APDU 帧数"。LOADCERT 大小因证书 DN 长度浮动 ~±10%。

---

## 5. 与经典 ECC Aliro 的对比（粗略）

| 协议 | 单次交易总字节 | 主要差异 |
|---|---:|---|
| ECC Aliro (Curve P-256) | **~1.2 KB** | EC 公钥 65 B，ECDSA 签名 ~72 B |
| **PQ-Aliro (D3 × K768)** | **~16.4 KB** | Dilithium 签名 3.3 KB，公钥 1.95 KB；Kyber 密文 1.1 KB |
| **PQ-Aliro (无 LOADCERT)** | ~11.2 KB | 假设 Reader 证书预置或 key_slot 索引 |

**量级**：PQC 路径约为 ECC 路径的 **10–20 倍**通信开销，主要来自 Dilithium 公钥（×30）和签名（×46）。

---

## 6. NFC 物理层吞吐与时延参考

> 仅作为时延估算参考，与 APDU 层字节计数解耦。

- ISO/IEC 14443 Type A 速率：106 / 212 / 424 / 848 kbit/s
- 实际吞吐受 ATQA/SAK、Block 长度、错误重传影响，常用 106 kbit/s 时净吞吐 ≈ 8 kB/s。

**估算单次交易传输时间**（不含 Reader/UD 端密码运算）：

| 链路速率 | D3 × K768 (16.4 KB) | D2 × K512 (11.9 KB) | D5 × K1024 (23.2 KB) |
|---|---:|---:|---:|
| 106 kbit/s | ~ 2.0 s | ~ 1.5 s | ~ 2.9 s |
| 424 kbit/s | ~ 0.5 s | ~ 0.4 s | ~ 0.7 s |
| 848 kbit/s | ~ 0.25 s | ~ 0.20 s | ~ 0.36 s |

> Android HCE 通常协商至 424 kbit/s。叠加 Dilithium 签名生成（移动端 ~50–100 ms）与验证（~5 ms）后，单次完整 PQ-Aliro 交易时延期望落在 **0.6–1.5 s**（D3/K768，424 kbit/s）。

---

## 7. 优化路径

| 方向 | 节省量 | 取舍 |
|---|---|---|
| **降低 Dilithium 等级**（D3 → D2） | -25% 总字节（-4 KB） | 安全级从 Level 3 降到 Level 2 |
| **省略 LOADCERT** | -5 KB / -22 APDU | 需 UD 预置 Reader 证书或使用 key_slot |
| **增大 Kyber chunk** | 边际：APDU 框架占比 < 3% | 受 NFC ISO-DEP I-Block 上限约束 |
| **AEAD 压缩 PK_ud** | -2 KB | 等价于 LOADCERT 反向方案（key_slot 替代发送） |
| **使用 ML-DSA-44 (Falcon-512)** | 签名 -2 KB | 需替换签名算法栈 |

---

## 8. 计算约定与口径

1. **应用层字节** = TLV 主体（不含 APDU 头与 SW）。
2. **链路字节** = 应用层字节 + 每个 APDU 帧 6 B 头（命令侧）或 2 B SW（响应侧）。
3. NFC 物理层 PCB/CRC、帧 SOF/EOF、ACK/NAK 重传**未计入**。
4. LOADCERT 大小为估算值，实测随 X.500 DN 长度浮动 ±10%。
5. AEAD GCM 的 IV（12 B）由 `1L(8B) ‖ counter(4B)` 派生，**不上链**，故不计入。
6. 当前实现 chunk 上限：Reader = 200 B/APDU，UD = 240 B/APDU。

---

*生成于 2026-05-24，对应分支 `develop`，commit `e406777` (Reader) / `5065ae7` (UD)。*


