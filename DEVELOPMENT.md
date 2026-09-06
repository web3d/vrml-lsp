# 开发过程说明

这份文档记录 **怎么走到今天这一步**：取证、决策、走弯路、以及每次以为过了其实没过的地方。
它和另外两份文档的分工是——

| 文档 | 回答的问题 |
|---|---|
| [`README.md`](README.md) | 这是什么、怎么用、当前达成度 |
| [`GRAMMAR-MAPPING.md`](GRAMMAR-MAPPING.md) | 语法逐条对照依据、语料放宽清单、复核方法、Kate 接入细节 |
| 本文 | 为什么长成这样、途中错在哪、用什么办法把错误逼出来 |

写代码时的判断多数已经落在源码 javadoc 里（那里是最近的位置），本文取的是**跨模块的过程视角**。

---

## 时间线

| 阶段 | 干什么 | 出口判据 |
|---|---|---|
| 0 | 把素材弄到手（X3D-Edit 克隆、Xj3D 的 SVN 检出、GitHub 活跃版对比） | 拿到 `.jj` 语法源码与 263 语料，并证明 2019→2026 语法零语义变更 |
| 1 | 方案取证（`.jj` 容错切入点、元数据三源、工具链） | 决策完备的实现方案（plan）获批 |
| 2 | M0 骨架 | 握手、能力声明、增量同步到位 |
| 3 | M1 词法 + 语法 + CST | 263 语料 round-trip 逐字节相等 |
| 4 | M2 语法诊断 | golden 钉住码与行列，结构问题从 541 条降到 11 条 |
| 5 | M3 数据层 | 56 节点 / 21 类型的 `vrml97-spec.json` |
| 6 | M4 语义诊断 | 符号表 + 值校验 + DEF/USE/ROUTE |
| 7 | M5 补全 / hover / symbol / definition | 断言全部来自实测输出 |
| 8 | M6 增量 + 格式化 | 四项性能预算达标 |
| 9 | 交付化（语料入项目、独立仓库、Kate 接入、README） | 克隆即可复现全绿 |

例数演进：42（M1）→ 87（M4 中）→ 286（M6b/c/d）→ 292 → **293**。
规模：main 42 文件 / 9 644 行，test 19 文件 / 6 194 行，specgen 13 文件 / 1 704 行，spec 表 174 593 字节。
19 个测试类的 `@Test` 数逐类点过，相加正好 293 —— 所以这个数里没有参数化展开或被跳过造成的虚数。
测试代码与生产代码接近 1:1.6 —— 这个比例不是规划出来的，是「每修一个 bug 都要先证明旧代码会红」这条纪律的产物。

---

## 阶段 0 · 素材：三次工具链意外

**目标**：拿到 VRML97 的语法源码（不是字节码）和真实语料。

1. **X3D-Edit 在 SourceForge 上只有 SVN + 发布包**，现行源码在 GitHub。克隆 638 MB 后工作树没落地，
   重查才发现是 checkout 仍在进行——第一次学到：**别根据中途快照下结论**。项目是 NetBeans/Ant harness
   工程（同时支持 Maven），要手配 `platform-private.properties` 指向本机 NetBeans。
2. **本机没有 svn 客户端，也没有 root**。先 `dnf download` 解 rpm（无 root 可行），但缺 serf 库、
   Fedora 44 仓库已无该包 → 转纯 Java 的 **SVNKit**（只要 JDK + Central 一个 jar）。
3. **全量检出被二进制目录卡死**：`jars/` 处 6 分钟无字节流动，看着像服务停滞，其实是被大文件拖住。
   改稀疏检出只展开代码目录，之后确认远端体量并不大（jars 24.6 MB / lib 29.4 MB / natives 9.7 MB）
   再补齐 —— **瞬时停滞被当成了永久故障**，这一步多做了一次往返。

补齐过程中 SVNKit 留下两个脏状态，都靠直接改工作副本 SQLite 解决：

- `WORK_QUEUE` 卡 7 个 `file-install` 项（jar 的 pristine 数据其实已下完），SVNKit 的 cleanup 不处理；
- `WC_LOCK` 只是进程间互斥表、无数据依赖，删残留行等价于真 `svn cleanup` 的解锁动作。

最终完整性核对：**4 540 个磁盘文件 == DB 记录数，0 脏锁、0 未完成项**，trunk @ r12741（SF 上最后变更
是 2019-12-23）。

**然后才是关键取证**：GitHub 上的 `Web3DConsortium/Xj3D` 只有 79 个提交、起点 2025-06-18，首条写的是
"copy … from **gitlab.nps.edu**" —— 真历史在 NPS。把两份 4 个语法文件对比：**忽略空白后所有差异行都含
"Locator"**（`org.xml.sax.Locator` → 自研 `DocumentLocator`），非 Locator 差异只是 javadoc `<p>`、
`Synchronisation`→`synchronization` 拼写与版权年。结论：

> **VRML 解析通路 2019→2026 语义零变更** ⇒ 照 `.jj` 移植语法是安全的，不必担心在追一个正在移动的靶子。

途中还自己造过一次假差异：归一化用的 `sed` 过度替换，把方法名 `getDocumentLocator` 也改了。**归一化脚本
本身要能被证伪**，否则它产出的"差异"没有意义。

---

## 阶段 1 · 方案：先钉死事实，再写方案

方案要决策完备才动手，所以先把六件事查成事实：

| 要钉的事 | 查到的事实 |
|---|---|
| JavaCC 能不能改造出错误恢复 | options 段**没有** `ERROR_RECOVERY`（该选项早被移除）；`Scene()` 只有 `try/finally` 无 `catch` |
| 语法能不能直接当移植蓝图 | `VRML97RelaxedParser.jj` 1531 行、31 条产生式，**每条带 ISO Annex A 规则号与 BNF 注释** |
| 语义错误是否已容错 | `convertException()` 把 `InvalidField*Exception` 走 `warningReport()` 后**继续解析** ⇒ 语义分级可沿用，要补的是语法层同步恢复 |
| 节点清单元数据在哪 | `config/2.0/profiles.xml`：56 个节点、34 个 component 分组，本地已有、机器可读 |
| 字段元数据怎么提取 | **我猜的 `xxxFieldName = "…"` 模式实测 0 命中**；真实机制是静态块 `new VRMLFieldDeclaration(FieldConstants.FIELD, "MFInt32", "coordIndex")` |
| 默认值/说明从哪来 | UOM 4.1 XML 2.6 MB（267 ConcreteNode / 5205 field），但**是 X3D 命名**（全文 "vrml" 仅 10 次）⇒ 只能作第三源 |

四个理由合起来定下路线：**不生成、不改造 JavaCC，自研容错递归下降**（详见 README「关键决策」）。
代价是语法等价性无法机器证明，对冲方式就是后面一直守着的两条：`GRAMMAR-MAPPING.md` 逐条对照 +
263 语料 round-trip / 差分。

一条被后面验证很值的判断：**不把项目塞进 X3D-Edit**（NetBeans/Ant harness 工程，混 Maven 会污染它的构建），
而是做兄弟目录，只读消费 `xj3d/parsetest` 与 `Xj3D/xj3d/src`。

---

## 阶段 2 · M0：不猜 API，从 jar 里读

沙箱只允许写工作区，而 Maven 默认写 `~/.m2` → 本地仓库指进工作区（`.mvn/maven.config`，
后来因为它含机器相关的绝对路径而被 `.gitignore` 排除）。

第一版 `Launcher` 写完，**几处 LSP4J API 假设是错的**。没有继续猜，直接把 jar 里的签名读出来：
`LanguageClientAware.connect(LanguageClient)`（不是 `LanguageServer`）、
`setDocumentFormattingProvider`、返回型是 `List<Either<...>>`。摸清后批量修正。

从第一天就定下的两条纪律：

- **stdout 是协议通道，日志一律 stderr**；后来升级为「永不写 stdout，要落文件就 `-Dvrml.lsp.log=`」。
- 验握手写的协议测试客户端，后来长成 `LspProtocolIntegrationTest`（真 JSON-RPC 走内存管道）。

M0 出口：fat jar 1.05 MB，握手、能力声明、snippet 协商、增量同步全部到位。

---

## 阶段 3–4 · M1/M2：round-trip 是结构性质，不是技巧

**先搞清 "Relaxed" 到底放宽了什么**（这正是 plan 里列的风险点，当时无法逐条枚举）：读 `.jj` 词法段得到
四条 —— **逗号算空白**、注释吃到行尾、`NUMBER` 故意宽松、标识符极宽。这几条决定词法器的分组。

**CST 用「区间树 + 平铺 token 流」**，让逐字节回打印成为结构性质而不是补丁技巧。但第一版
`toSourceText` **只打印叶子、丢掉 trivia**，根本无法逐字节等于原文 → 改成「平铺流游标补齐」式打印。
这个改动的额外收益很大：round-trip 断言从此同时验证「每个显著 token 恰好入树一次」，
比"节点齐全"强得多。

M1 出口：**263/263 逐字节相等、0 holes、全语料 0.26 s**。

紧接着是本项目最赚的一次流程：**没有急着写诊断，先把 51 个文件报出的 541 条结构问题分诊**。
为此给 `Launcher` 加 `--check-file` CLI；而它需要 offset→行列映射，那东西当时埋在 `server` 包里且依赖
lsp4j 类型 → 抽出 `text/LineIndex`，文档与 CLI 共用（纯函数层的第 0 个副产品）。

分诊出两类真因，都回 `.jj` 核对而不是就地打补丁：

1. **JavaCC 的 `STRING_LITERAL` 用 `(~["\"","\\"])*`，允许裸换行** —— 语料里 `url "javascript:` 后面跟着
   整段脚本是合法输入，而我方词法器停在行尾，一次造成 **74 + 302 条误报**。
2. `ExternInterfaceDecl()` 的实际形态与 plan 假设有差，`#X3D` 头需要单独处理。

三处修复：BOM 当前导 trivia、`#X3D` 头给一条根因提示（而不是在每一行上复读语法错）、
加**结构问题棘轮**基线文件。541 条 → 今天语料只剩 **10 个文件 11 条 issue**（最慢 70 ms）。

**这里长出本项目最重要的一条测试纪律**：golden 与 baseline 都不允许"跑挂了改期望"，必须显式
`-Dvrml.updateGolden=true` / `-Dvrml.updateBaseline=true` 才重生成，让每一次期望漂移都成为一次可见的决定。

M1 收尾补 `GRAMMAR-MAPPING.md`（从 `.jj` 精确抽 31 条规则号与产生式名），此后每阶段回填。

---

## 阶段 5 · M3：数据层的三类偏差

写生成器之前，先把 56 个节点的真实字段**手工抽一遍**，用来校验生成器规则 —— 这一步省下了后面无数次
"正则悄悄漏项"。同时确认：56 个节点名到 `Base<Name>.java` **一一对应、零歧义**（plan 担心的
`BaseText`/`BaseText2D`、多包同名并未触发，但 fail-fast 的机制保留着，一旦上游新增节点就会立刻炸出来）。

查出的偏差分三类：

| 偏差 | 现象 | 处理 |
|---|---|---|
| **数据格式** | UOM 的 `acceptableNodeTypes` 是 **`\|` 分隔**，而我只按空白切分 | 修切分 |
| **X3D 化（m3e）** | `Group.children` 候选表里**没有任何几何节点** | 生成器里把 X3D 抽象类型映射回 VRML97 节点；语料证据：`Transform` 出现在 10 个文件的 `children` 里 ⇒ "自排除"必错 |
| **X3D 4 化（m5d）** | 表内 `Appearance.texture` 缺 `MovieTexture` | 同类偏差：VRML97 的 `MovieTexture` 是纹理节点，X3D 4 把它从 `X3DTextureNode` 摘掉了 |

`set_*` / `*_changed` 是 X3D 化留下的事件名，**不进字段补全**（只在 ROUTE 里用），否则会造出大量在
VRML97 里非法的候选噪声 —— 这条是 plan 定的，实现时照做。

**未决**：仍有若干字段名在本机三源里不能同时自洽（`wrapS`/`wrapT` 一类），plan 要求这类冲突清单**人工签核**，
至今未决（见文末）。

---

## 阶段 6 · M4：前提没核实就写值校验，必翻车

写 MF 元素个数校验之前，先确认一个词法前提：**`1-2` 这种粘连数字会作为一个 NUMBER 到达**
（词法是贪婪数字规则，`Values` 的注释里明写了这条，还包括它吞下非法数字时返回 `null` 的走法）——
它直接决定"个数"对不对。不先问这一条就写校验，必翻车。

第一版 `Semantics` 草稿依赖了**不存在的 API**（`textOf` 实际返回 `toString`、`parentOf`/`order()` 根本没有），
没有修修补补，整体重写。补上的是语义层自己的五个类（`SymbolTable`/`Members`/`Semantics`/
`Values`/`ValueConstraints`）：CST 是 `CstKind`（37 项）+ `CstNode` 的通用形状，`PROTO_DECL` /
`EXTERN_PROTO_DECL` / `ROUTE_DECL` 都是 kind 而不是独立类，所以语义要的信息从 kind + 子节点取，
不在别处二次解析。

出口：编译与 87 测试全绿；语义分级沿 Xj3D（坏字段名/坏字段值是 Warning 并继续解析，其它才 Error）。

---

## 阶段 7 · M5：先 dump 真实输出，再写断言

补全上下文机是整个项目里最容易"靠想象写测试"的地方，所以做法反过来：
**先用临时驱动把 CST 光标路径与 `Completions` 真实输出 dump 出来，再据此写断言与修逻辑**。

据此抓到的缺陷（每个都是先有实测证据）：

- `Context` 的下降/上爬逻辑错（据 dump 修）；
- **词法只认大写 `TRUE`/`FALSE`** ⇒ 布尔字段的 snippet 模板会插入非法文本，修 `valueTemplates`；
- PROTO 实例在受限节点字段里被漏掉，且未约束字段会**重复给同一个 PROTO**，修 `nodeValues`/`prototypes`；
- 表内别名与 `TimeSensor` 可读端方向问题；`PROTO` 空体路径。

同时回收一个 M1/M2 遗留的**真语法缺陷**：Script 体内「声明不带默认值」被判 `VRL1009`，
而这在 VRML97 里合法 —— 对照 `.jj` 修正，由 `VrmlParserTest.rules15To16ScriptBodyMixesDeclarationsAndFields`
钉住（方法名里的 15/16 就是 ISO Annex A 的规则号）。
`MovieTexture { duration` 返回空列表一路查到是表内候选缺项（即 m5d），不是补全逻辑错。

hover 的 25 例每例写成"用户在气泡里读到的那句话"而不是树遍历断言（`HoverTest` 的 javadoc 就是这句）——
措辞漂移会以一条自报家门的失败用例出现，而不是悄悄全绿；definition/references 27 例
（plan 的验收线是每类 10 个手测点位，实现成了 27 例）；documentSymbol 覆盖节点 / PROTO /
EXTERNPROTO / ROUTE 四种形状。

---

## 阶段 8 · M6：一次自己引入的缺陷，和四次假绿

**格式化器**第一版草稿是 known-broken 的，重写前先重新核一遍它依赖的 CST 形状，
再用**单一状态机**（一个 `out`、一个 `column`、一个 `lineOpen`，`indent()` 在输出为空时不补，
"换行"永远由下一次真实输出兑现）实现：树强制换行 + 宽度驱动折行。折行分两种：
**节点列表按规则断行、字面量列表按宽度折行**；`maxColumn` 被夹在 20–400（比一个 token 还短的行
会折到无法收敛），默认 2 空格 / 80 列 —— 语料大多本来就是这个形状。破损文档拒格式（返回 `null` +
`window/showMessage(Warning)`）—— 语料里正好 10 个这样的文件，`FormatterCorpusTest` 逐个确认被拒、
其余 211 个二次格式化零 diff。

**m6e 的实现方式是用测量否决的**：plan 指定 statement 级复用。量下来 —— 6.7 MB 语料 parse 51–103 ms、
语义遍历 55–117 ms、整分析 p95 266 ms；而 `Token` 不可变 ⇒ 编辑后**其后所有偏移都要重分配**，
只省一次词法；DEF/USE/ROUTE 又天然跨 statement。结论：达不到自己的目的，于是改做
**按文本身份缓存整份分析 + 把缓存锁从文档监视器里拆出来**。这条偏离被记为「需签核」而不是「已定案」。

**自己引入的真实缺陷**：plan 的判据预算的是 **`didChange`**，不只是分析。盯着驱动看才发现
p95 达标（5 MB 4.7 ms）而 **max 冲到 211 ms** —— 编辑落在后台分析进行中会被 Java 的**可重入写锁**
的准入策略挡住。修完：5 MB p95 4.97 ms / max 58 ms，126 KB p95 0.14 ms（原本 5.56/78）。

为了让「编辑不得排在分析后面」这条回归测试真的有效，连吃四次假绿：

| # | 假绿的原因 | 怎么发现 |
|---|---|---|
| 1 | Maven 打印 `Nothing to compile`，"证明它会红"跑的是**旧字节码** | 强制重编并用 `javap` 校验字节码里确实没有 `monitor_enter` |
| 2 | 计时区间漏掉真正阻塞的位置换算调用（等待发生在 `lineCount()`） | 读测试与真实客户端路径的差异 |
| 3 | 5 ms 窗口让编辑抢在线程启动之前 | 改成确定性窗口 |
| 4 | 独立驱动能复现（edit 等 234 ms）而测试不能 ⇒ helper 在计时开始**之前**就把行号解析完了 | 把测试序列原样当驱动跑，看真实时间线 |

第 4 次的教训被写成通用做法：**别猜时间线，把测试序列当驱动跑一遍**。最后两条新断言对旧锁实现都失败
（5 MB 单字符 p95 34 ms、分析进行中 edit 213 ms），才算这条测试可用。同期还修了两处：
缓存读取顺序（先读树再读 key 会把新 key 配旧树）与内存测试的测量口径 ——
内存要从**绝对值**改成**增量**，同一 JVM fork 里前序测试的文档会被活着的诊断线程 root 住，
绝对值 871 MB 是假警报。

出口：**293 例全绿、稳定重复**（248 MB / p95 7 ms），四项性能预算达标（README 有表）。

---

## 阶段 9 · 交付化：让"离线可构建"成为可证伪的事实

- **语料入项目**：plan 原文写的是"不复制，只读引用"，用户改判为复制副本。只取 `.wrl`
  （测试与 SpecGen 都只按该扩展名遍历），tar 管道保结构，**双端 `sha256sum` 集合 diff 证明 263/263
  逐字节一致**（7.6 MB），写 `parsetest/PROVENANCE.txt`（来历、校验命令、刷新命令、"不要为了过测试改这些
  文件"），`parsetest.dir` 默认指向副本、`-Dparsetest.dir=../xj3d/parsetest` 仍可回退对拍。
- **独立仓库**：`.gitignore` 排除 `target/`、`.cache/`（抓来的 UOM）、**`.mvn/maven.config`**
  （内含机器相关的 `-Dmaven.repo.local` 绝对路径，入库即坑他人），语料刻意不排除。
  初始提交 345 文件 / 115 846 行。
- **真正的自足性验证不是"状态干净"，而是克隆一份跑测试**：`git clone --no-hardlinks` 到空目录、
  只补 gitignore 掉的 `maven.config`，副本内 `mvn -B -o clean test` **293 例全绿**。
- **Kate 接入**：schema 不靠记忆，从本机已生效的 phpls 条目反推（`~/.config/kate/lspclient/settings.json`，
  `servers` 是**以服务器名为键的对象**，靠 `highlightingModeRegex` 绑定）。此处有一次值得记录的自我纠正：
  先用 `strings` 扫 `libKF6SyntaxHighlighting.so` 没命中 vrml，几乎断言"Kate 无 VRML 模式、要手写语法文件"；
  改跑 `ksyntaxhighlighter6 -l` 发现**确有 `VRML` 模式**，再用自动探测与强制 `-s VRML` 的输出 `cmp`
  → 逐字节相同 ⇒ 整套自定义语法文件方案直接取消。**`strings` 没命中不等于没有**。
  另一处边界：插件界面字串在翻译目录里抠不全，就把文档改成不依赖版本的说法（"去 快捷键 里搜 format/LSP"），
  而不是凭印象写菜单名和键位。
- **README**：把 plan 的每条判据与实测并排写。写的时候自查出两处会误导人的断言：
  ① 「`#` 后非十六进制」并**没有**独立诊断码（`Codes.java` 里 grep `HEX|COLOR` 零命中）；
  ② 「`.wrl.gz` 不支持」是错的 —— `CompletionEncoder.siblingScenes` 明确支持**同目录** `.wrl`/`.wrl.gz`
  （有 `MAX_URIS` 上限、读盘失败即空候选），不给候选的是跨目录 import 与 `.wrz`。两处都已改。

---

## 反复起作用的做法

1. **取证先于写作**：不猜 API（读 jar 签名）、不猜数据（先手工抽 56 节点字段）、不猜断言
   （先 dump 真实补全输出）、不猜 UI（从本机生效配置反推）。
2. **每次修 bug 都要证明旧代码会红**。做不到这一点，测试就只是装饰 —— M6 那四次假绿全是因为
   "证伪动作"本身有漏洞（旧字节码、计时区错位、窗口不确定）。
3. **能用数据否决的，不要用直觉接受**。m6e 的 statement 级复用是被 51–103 ms / 55–117 ms 这组数字
   否决的；否决后**明写偏离待签核**，不伪装成 plan 达成。
4. **把"不许悄悄变好/变坏"写进测试**：golden、baseline 棘轮、round-trip 逐字节、二次格式化零 diff。
   漂移必须显式批准。
5. **测量口径比测量值更容易错**：绝对内存 vs 增量内存、只测拼接 vs 测整个请求路径、
   状态干净 vs 克隆能跑。口径错了，数字越漂亮越危险。
6. **文档只写验证过的东西**，没验证的写成"怎么自查"。宁可不写键位，也不要写一个错的键位。

---

## 附 · 编写本文时的一次自我纠偏

本文初稿有**五处查无此项**，全部来自跨会话摘要的压缩损失（不是仓库事实）：

| 初稿写法 | 实况 |
|---|---|
| 测试类 `ScriptDeclarationTest` | 不存在；真实落点是 `VrmlParserTest.rules15To16…` |
| 测试类 `HoverPathTest` | 不存在；是 `HoverTest` |
| 「hover 用语料 `AvatarInfo.wrl` 验」 | 测试里没引过任何语料文件做 hover |
| CST 类 `InterfaceDeclaration` 及其 `isProto`/`protoName` | CST 只有 `CstKind` + `CstNode`，PROTO 是 kind |
| 「折行只在 ASCII 边界切（`&nbsp;`）」 | 全仓 `nbsp` 零命中；折行规则是另一套 |

处理方式：逐条 grep 后改写或删除，而不是保留一个"听着合理"的说法。
这恰好是阶段 7 那条纪律用在文档上的版本：**引用一个名字之前先把它查出来**。比初稿更值得信的不是文笔，
是每个专名背后可复现的那一条命令。

---

## 未决 / 后续

| 事项 | 状态 |
|---|---|
| M3 字段名冲突清单（`wrapS`/`wrapT` 一类） | **待人工签核**：保留还是删表 |
| M6 实现偏离（不做 statement 级复用，改按文本身份缓存 + 独立 `analysisLock`） | **待签核**；判据已达标，路径与 plan 不同 |
| 命名（`vrml-lsp/`、`org.vrml:vrml-lsp`、`org.vrml.lsp.Launcher`） | 保持现状；仓库仅 3 条提交，改的成本还很低 |
| `Launcher.VERSION` `0.1.0` 与 Maven `0.1.0-SNAPSHOT` 未联动 | 已知噪声 |
| 内存余量（`List<Token>` → 并行数组、`sigOf` 去装箱） | 未做 |
| git 历史只有 3 条（初始导入式），过程细节不在提交里 | 后续按里程碑小步提交；本文即为此而写 |

## 本文的事实来源

阶段叙述取自本次开发的完整会话记录；数字分两类：**能在仓库里复现的**（例数、语料统计、代码量、
`--check-file` 结论、性能预算，随仓库版本有效）与**当时环境的观测**（JDK 25.0.4.1、
Maven 3.9.11、SVN r12741、GitHub 79 提交、Kate 26.08.0、638/4 540 等），后者是历史事实、不承诺复现。
提交时间戳显示开发工作跨 2026-09-05 与 09-06 两个自然日。
