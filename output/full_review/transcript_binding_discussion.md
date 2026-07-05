# Transcript 身份绑定问题讨论记录

**发现时间**：2026-06-18  
**来源**：第三轮同行评审（R1 PQC理论专家 + R3 协议安全专家）  
**状态**：待决策

---

## 问题描述

### 当前协议定义

论文 §5 Fig. 1 中，session transcript 定义为：

```
trans = H(pk_e ∥ c_e)
```

其中：
- `pk_e`：Reader 生成的 **临时** KEM 公钥（ephemeral，per-session）
- `c_e`：UD 生成的 KEM 密文（基于 pk_e 封装）

### 形式化安全问题

`trans` 不包含任何长期公钥（`pk_R` 或 `pk_UD`），理论上存在 **identity-misbinding** 风险：

> 敌手截获合法 session 的 `(pk_e, c_e, σ_Reader)` 后，将其重放给另一个 UD'。  
> 由于 σ_Reader = MLDSA.Sign(sk_R, trans) 对 trans 是合法签名，UD' 会验证通过。

R1 评审原文（§5+§6, type: major）：
> "The transcript hash trans = H(pk_e || c_e) does not include the long-term public keys of either party. This means the session transcript does not bind to the communicating parties' identities..."

R3 评审原文（§5/§6, type: major）：
> "The HKDF derivation in Fig. 1 uses only trans = H(pk_e || c_e); the UD's noise contribution (UD_noise, 32 bytes) does not appear in the key derivation formula..."

---

## 实际影响评估

在 PQ-Aliro 中，该攻击的实际危害是**受限的**：

| 安全属性 | 实现机制 | 是否依赖 trans 显式含身份 |
|---------|---------|------------------------|
| **Reader 认证** | `σ_Reader = MLDSA.Sign(sk_R, trans)`，需要 sk_R | **否**——签名本身绑定了 Reader |
| **Session key 新鲜性** | `K_e` 从 ephemeral `pk_e` 派生，天然 per-session | **否**——K_e 已天然新鲜 |
| **UD 认证** | `pk_UD ∥ σ_UD` 在 `AEAD(SKDevice_UD)` 中，用 session key 加密 | **否**——密文绑定 session |

**真正的残余风险**：**跨 Reader 重放**（cross-reader misbinding）
- 若不同 Reader R1、R2 碰巧使用了相同 `pk_e`（概率密码学上可忽略），则 `trans` 相同
- R1 生成的 `σ_Reader` 原则上可被用于 R2 的场景
- 实践中这要求 Reader 复用临时密钥，属于实现层面的错误而非协议设计缺陷

### 实现层面的缓解

当前代码中的实际 transcript 计算（比形式化定义更强）：

```java
// MyHostApduService.java 实际实现
transAH0 = SHAKE256-512(AUTH0_cmd_bytes ∥ AUTH0_resp_bytes)
```

其中 `AUTH0_cmd_bytes` 包含：
- `transaction_identifier`（16 B，per-session 随机）
- `reader_identifier`（32 B，唯一标识 Reader）
- `pk_e`（KEM 临时公钥）

`AUTH0_resp_bytes` 包含：
- `c_e`（KEM 密文）
- `UD_noise`（32 B，UD 提供的新鲜随机数）

**结论**：实现已通过包含 `reader_identifier` 和 `UD_noise` 防御了跨 Reader 重放，但形式化定义未体现这一点，导致形式模型与实现不一致。

---

## 三种解决方案

### 方案 A：最小改动（推荐）

**修改**：将形式 transcript 改为包含 `reader_identifier`

```
trans = H(pk_e ∥ c_e ∥ reader_identifier)
```

**优点**：
- 与实现保持一致（实现已包含 reader_identifier）
- 明确阻断跨 Reader 重放攻击
- §7 证明草稿的归约逻辑更清晰
- 改动范围小：仅 Fig. 1 和 §6 中的 transcript 定义

**缺点**：
- reader_identifier 在 AUTH0 中明文传输，不能替代长期 pk_R 作为身份锚
- 不包含 UD 的新鲜随机性贡献（UD_noise）

**需要修改的位置**：
- `main-Full-v2.tex` §5 Fig. 1 中 `trans = H(pk_e ∥ c_e)` 这一行
- §6 安全模型中所有涉及 transcript 定义的地方
- §5 中已有的 UD_noise Remark（需要更新说明）

---

### 方案 B：完全对齐实现

**修改**：

```
trans = H(pk_e ∥ c_e ∥ reader_identifier ∥ UD_noise)
```

**优点**：
- 完全匹配代码实现
- UD 通过 UD_noise 对 session key 派生有直接贡献，更强的安全语义
- 形式模型与实现完全一致，消除 R2 评审指出的不一致问题

**缺点**：
- UD_noise 在 AUTH0 Response 中传输，需要在 Fig. 1 中显式标注
- 协议图需要同步更新（AUTH0 Response 需加 UD_noise 字段）
- 改动范围稍大

**需要修改的位置**：
- Fig. 1 中 AUTH0 Response 消息（加入 UD_noise 字段）
- Fig. 1 中 trans 定义
- §6 安全模型中的 transcript 定义
- §5 UD_noise Remark（简化，因为不再是"仅实现层有"的内容）

---

### 方案 C：仅文字说明，不改公式

**修改**：保持 `trans = H(pk_e ∥ c_e)` 不变

在 §5（Fig. 1 附近）和 §7 中增加安全论证：
> "虽然 trans 不显式包含长期公钥，但 Reader 身份通过 σ_Reader 的签名验证隐式绑定，UD 身份通过 AEAD 加密下的 pk_UD 传输绑定。形式化简化的 transcript 不影响安全性结论，因为 pk_e 是 per-session 临时生成的，跨 session 重放不可行。"

**优点**：
- 不改动任何公式或协议定义
- 无需同步修改多处内容

**缺点**：
- 形式模型与实现代码仍不一致（R2 指出的问题未解决）
- 评审人可能不接受纯文字论证，尤其是 R1 这种形式化专家
- 理论上确实存在的 misbinding 弱点未被修复

---

## 待决策

> **请选择修改方案（A / B / C），或提供其他意见。**
>
> 推荐：方案 A（最小改动，可消除主要评审意见，与实现一致）

---

## 关联文件

| 文件 | 说明 |
|------|------|
| `PQ_Aliro_v2/main-Full-v2.tex` | 论文主文件，§5 Fig. 1 和 §6 需修改 |
| `source/Ailiro_UD/.../MyHostApduService.java` | 实际 transcript 计算代码 |
| `source/communication-overhead.md` | 协议字段说明（含 UD_noise 描述） |
| `output/full_review/R1_PQC_Theory.json` | R1 评审原始意见 |
| `output/full_review/R3_Protocol_Security.json` | R3 评审原始意见 |
