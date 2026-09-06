# vrml-lsp —— VRML97 语言服务器

Java 17 + Maven + LSP4J 0.24.0 的 **stdio 单 jar** 语言服务器，只支持 **VRML97 `.wrl`**，
且是 X3D-Edit 实际使用的 **Relaxed 变体**（允许 C 风格 `//` 注释、`#RRGGBB` 颜色、松散转义等）。
语法与语义的对齐目标是 Xj3D 的解析器 —— 它是这些文件在现实里真正会遇到的解析器。

| | |
|---|---|
| 版本 | `0.1.0`（`java -jar target/vrml-lsp.jar --version`） |
| 构建 | `mvn -B -o package` → `target/vrml-lsp.jar`（约 1.3 MB） |
| 测试 | `mvn -B -o clean test` → **293 例**，不需要项目外任何东西（语料随项目走） |
| 能力 | 诊断（push）、补全、hover、documentSymbol、definition/declaration/references、格式化 + 区域格式化 |
| 远端 | `git@gitee.com:web3d/vrml-lsp.git`（独立仓库，与同工作区的 `Xj3D/`、`xj3d/` 检出无关） |

## 快速开始

```bash
mvn -B -o -DskipTests package
java -jar target/vrml-lsp.jar            # 作为 stdio 服务器，等客户端连
```

不带客户端就能自查的三个 CLI：

```bash
java -jar target/vrml-lsp.jar --check-file parsetest/error_handling/import.wrl
# parsetest/error_handling/import.wrl:8:8: [VRL1002] ERROR: expected '{' after the node name …

java -jar target/vrml-lsp.jar --spec-dump            # 全部 56 个节点
java -jar target/vrml-lsp.jar --spec-dump IndexedFaceSet.coordIndex   # 一个字段的全部元数据
```

`--check-file` 走的是与服务器完全相同的 `DocumentAnalyzer` 代码路径（含头检查与语义），
所以它报得干净的文件在编辑器里也干净；反过来若两边结论不一致，那是 bug，值得开一条 issue。

## 架构

```
文本 ──▶ [1] lexer/     永不失败，坏字符成 BadToken
      ──▶ [2] parser/    递归下降 + 同步恢复 → 全保真 CST + 语法诊断（cst/）
      ──▶ [3] semantic/  SymbolTable：DEF/USE、PROTO/EXTERNPROTO、ROUTE 索引、作用域栈
      ──▶ [4] spec/      vrml97-spec.json：56 节点 / 21 字段类型 / 默认值 / component-level
      ──▶ [5] services/  诊断规则、补全上下文机、hover、symbol、definition、格式化（纯函数）
      ──▶ [6] server/    LSP4J TextDocumentService / WorkspaceService，版本与增量同步（text/ 是行索引）
```

两条纪律，是整个代码库形状的来源：

1. **`[2]` 产出的 CST 必须能逐字节打印回原文**（`printCst()`），由 round-trip 测试硬约束。格式化与编辑
   安全全部建立在这条之上 —— 一个在重建文档时丢过字符的服务器，之后再也不能格式化或改写任何东西。
2. **`services` 层是纯函数、返回自己的 record**；LSP4J 的协议对象只允许出现在 `server/` 层的编解码处。
   于是补全/悬停/格式化可以被单测直接调用，不必拉起协议栈。

诊断日志**永不写 stdout**（`Log` 集中负责）：stdout 是 JSON-RPC 通道，多一个字节就变成对端的
Framing error，而报错位置会离真因很远。`-Dvrml.lsp.log=<路径>` 另开文件日志。

## 关键决策：不生成、不改造 JavaCC，改自研容错递归下降

四条理由各自独立成立：

1. JavaCC 6 没有可用的错误恢复选项（`ERROR_RECOVERY` 早已移除）；要做同步恢复只能大改生成前代码。
2. Xj3D 的 parser 是 **SAX/SAV 推模型**（直接回调 `ContentHandler` 建场景图），不产出可查询、带位置的树
   —— 而 LSP 要的恰好是带 span 的树。
3. 生成的 parser 与 renderer/NodeFactory/DefaultWorldLoader 耦合，脱离 Xj3D 运行时跑不起来（Xj3D 仍是
   Ant + nbproject 工程，没有 Maven 化）。
4. LSP 需要增量重解析与全保真 CST（phpls 的 LostToken/SkippedToken 思路），JavaCC 的 token 流模型不友好。

**代价**是语法等价性无法机器证明。对冲方式：以 `VRML97RelaxedParser.jj`（1531 行、31 条产生式，每条带
ISO Annex A 规则号与 BNF 注释）为 checklist 建立 [`GRAMMAR-MAPPING.md`](GRAMMAR-MAPPING.md) 逐条对照，
再用 263 个真实语料做 round-trip 与差分（Xj3D 能解析的我们必须能解析，`bad_*` 必须报错且位置合理）。

## 数据层：`src/main/resources/spec/vrml97-spec.json`

三源 join，冲突时**以 Xj3D 为准**（它就是解析器实际接受集合）：

| 源 | 提供 | 说明 |
|---|---|---|
| `Xj3D/xj3d/src/config/2.0/profiles.xml` | 节点名 + component/level | 56 个节点、34 个 component 分组 |
| `vrml/renderer/common/nodes/**/Base*.java` 静态块 | 字段名/类型/`field`·`exposedField`·`eventIn`·`eventOut` | 机械正则提取；类名↔节点名冲突时 fail-fast，不猜 |
| X3D UOM 4.1 XML（2.6 MB） | 默认值、描述、`acceptableNodeTypes` | X3D 命名，不含 VRML97 改名 ⇒ 只作第三源；`inheritedFrom="X3DNode"` 的过滤掉 |

产物：56 节点、21 种 `SF*/MF*` 字段类型、每节点的子节点候选集（UOM 抽象类型手工映射回 VRML97 节点，
约 15 条 allowlist，内联在生成器里）。`set_*` / `*_changed` 只在 ROUTE 场景暴露，**不进字段补全**
—— 否则会造出大量在 VRML97 场景文件里非法的候选噪声。

重新生成（离线手跑，产物进版本控制；`src/specgen` 编译为**测试**源，所以任何一点都漏不进发布的 jar）：

```bash
mvn -Pspecgen process-test-classes        # 需要同级 Xj3D 检出 + .cache/ 下的 UOM
mvn -B -o test                            # json 由 process-resources 读，跑在它之后，故要再来一遍
```

## 诊断码

`Codes.java` 里 37 个码，上线格式化为 `VRL%04d`。族划分：

- **`VRL10xx` 语法**：缺 `{`/`}`/`[`/`]`/`.`、缺关键字/名字/值、字符串未闭合、括号失衡、节点未闭合、
  statement 未结束、意外 token、坏字符。恢复策略：在下一个 `}`、下一个合法字段名或下一 statement
  处同步，**单文件内继续报错**，绝不整体放弃。
- **`VRL2xxx` 语义**：未知节点、未知字段、字段类型不符、MF 值元素个数与类型不符（21 种类型各有元素宽度：
  `SFVec3f`=3、`MFRotation`=4…）、枚举与区间越界（`creaseAngle ≥ 0`、`alpha ∈ [0,1]`…）、DEF 重名、
  USE 引用未定义、ROUTE 端点或字段不存在、方向非法（不能 out→out）。
- **`VRL3xxx` Hint**：名字像 X3D 而非 VRML97 时给"改名为 …"的建议（来源是表里 UOM-only 的字段名单）。

严重度沿 Xj3D 而非趣味：它 `VRML97Reader.convertException()` 把非法字段名/字段值走
`errorHandler.warningReport()` 后**继续解析**，其它才 `errorReport()`。

## 里程碑与验收（plan 判据 → 实测）

开发过程的记录（取证顺序、走过的弯路、四次假绿）在 [`DEVELOPMENT.md`](DEVELOPMENT.md)；
下表只给结论。

| 里程碑 | plan 的判据 | 实测 | 状态 |
|---|---|---|---|
| M0 骨架 | 编辑器连上、日志见 initialize | Kate 26 实连接（见下节），`initialize` 握手由内存管道协议测试覆盖 | ✅ |
| M1 词法+语法+CST | 263 语料 round-trip **逐字节相等**；`bad_*` 不崩不 hang | 263/263 逐字节相等；无崩溃、无超时 | ✅ |
| M2 语法诊断 | `error_handling/` 逐个报对位置；错误数不失控 | golden 文件钉住每条码+行列；`issues-baseline.txt` 做棘轮 | ✅ |
| M3 数据层 | 56/56 节点有字段表；字段总数可核；未 join 字段在白名单内 | 56 节点、21 类型；差异报告 `vrml97-vs-uom-report.txt` | ⚠️ 见「待签核」 |
| M4 语义诊断 | 未知节点/字段/值个数可复现；DEF/USE/ROUTE 用例通过 | `SemanticsTest` 46 例 + 语料基线里的 10 个 issue | ✅ |
| M5 补全+hover+symbol+definition | 每类 10 个点位 + 自动化断言 | `CompletionsTest` 27、`DefinitionsTest` 27、`HoverTest` 25、`ContextTest` 25、`SymbolsTest` 12 例 | ✅ |
| M6 增量+格式化 | 单字符 didChange p95 < 15 ms；格式化幂等；破损文档拒绝格式化 | p95 0 ms（126 KB）/ 8 ms（5 MB）；211 文件二次格式化零 diff；10 文件被拒格式 | ✅ 但**实现路径偏离**，见下 |

### 一处偏离，需要签核

plan M6 写的是「重解析粒度 = 受影响顶层 statement：CST 维护 `{}` 深度前缀索引，只重跑跨越的区间，
其余子树复用」，并给了退化预案「仅重算诊断、不重算补全索引」。**实际没有实现 statement 级复用**，
改成：`VrmlDocument.analyze()` 按**文本身份**缓存整份分析（`String` 引用相等即未被编辑），并把缓存的
锁从文档监视器里拆出来。理由是量出来的：

- `Token` 是不可变 final class ⇒ 编辑后偏移量整体平移，后缀必重分配；125 KB 文档实测 0–3 ms、
  6.7 MB 14–32 ms，只省一次词法。
- `SymbolTable.of` + `Semantics.check` 是 O(文档) 且 DEF/USE/ROUTE 天然跨 statement，复用不上。
- 两项相加，6.7 MB 上最多 3–4× ⇒ 仍远在 15 ms 之外。

即：判据（p95 < 15 ms）达成，但达成方式不是 plan 指定的那条，也不是 plan 写明的退化分支
（我们没有放弃补全索引，而是让整份分析每文本只做一次）。这一点在 [`GRAMMAR-MAPPING.md`](GRAMMAR-MAPPING.md)
与 `VrmlDocument` 的 javadoc 里有同样口径的记录。

## 性能预算（plan 定的四项，实测）

| 项 | 预算 | 实测（本机，OpenJDK 25.0.4.1） |
|---|---|---|
| 5 MB 首次解析 | < 250 ms | **98 ms**（2 180 852 tokens） |
| 单字符 `didChange` | p95 < 15 ms | 126 KB：**0 ms**（max 33）；5 MB：**8 ms**（max 48） |
| completion | < 30 ms | 126 KB 冷 6 ms / 热 2 ms；5 MB 冷 328 ms → 热 **4 ms** p95 |
| 堆占用 | < 300 MB | 5 MB 文档 + 其分析净占 **248 MB** |

5 MB completion 冷启动那 328 ms 是第一次分析的成本（不是每次编辑的成本），落在预算内是因为预算说的是
「补全」这一动作在文档已打开、已同步之后的耗时。想拉大内存余量，下一步该做的是把 `List<Token>` 换成
并行数组（`int[] start/end` + `byte[] type`）与 `sigOf` 去装箱 —— 尚未做。

顺带一条实测教训（写进了 `PerformanceBudgetTest`）：**性能测试必须计整个请求路径**。只测字符串拼接
测的是唯一不会阻塞的一步；真实客户端发的是行号，服务器要先换算成偏移量，而阻塞恰好发生在换算里。

## 测试策略与语料

四类断言，`mvn -B -o clean test` 一次跑完（293 例）：

- **语料回归** `CorpusRoundTripTest`：263 个 `.wrl` 逐字节打印回来比对。按 **ISO-8859-1** 读：一字节
  一字符，于是断言是关于字节而不是关于解码器的；语料里 6 个带 UTF-8 BOM 的文件也在其中。
  实测：263 文件、10 个有 issue、共 11 issues、最慢 70 ms。
- **golden / 棘轮** `CorpusGoldenDiagnosticsTest`（码+行列）与 `CorpusIssueBaselineTest`（每文件 issue 数）。
  期望文件在 `src/test/resources/corpus/`，**只在人确认过差异后**显式重生成：
  `-Dtest=CorpusIssueBaselineTest -Dvrml.updateBaseline=true`、
  `-Dtest=CorpusGoldenDiagnosticsTest -Dvrml.updateGolden=true`。
- **LSP 集成** `LspProtocolIntegrationTest`：LSP4J + 内存管道跑真 JSON-RPC
  （initialize / didOpen / didChange / didClose / 补全 / 悬停 / 格式化 / 配置推送），断言能力与响应结构。
- **性能预算** `PerformanceBudgetTest`：上表四项 + 「编辑不得排在后台分析后面」的并发回归。

语料**随项目走**：`parsetest/` 是 `xj3d/parsetest/` 里 263 个 `.wrl` 的逐字节副本（来历、校验命令、刷新
方法见 [`parsetest/PROVENANCE.txt`](parsetest/PROVENANCE.txt)）。`parsetest.dir` 属性默认指向它，所以
`mvn test` 不需要项目外任何东西；要对拍上游：`-Dparsetest.dir=../xj3d/parsetest`。

## 在 Kate 里接入

Kate 的 LSP 客户端按**高亮模式名**绑服务器，而 Kate 26 自带 `VRML` 模式：`ksyntaxhighlighter6 -l` 里有
这一项，且对 `.wrl` 的自动探测输出与强制 `-s VRML` 逐字节相同 ⇒ **不需要自定义语法文件**。

配置在 `~/.config/kate/lspclient/settings.json`，`servers` 是**以服务器名为键的对象**（不是数组）。
模板在 `tools/kate/kate-lsp-settings.json`，里面 `/ABSOLUTE/PATH/TO` 代表**本项目目录**，打印出可粘的成品：

```bash
mvn -B -o -DskipTests package
sed "s|/ABSOLUTE/PATH/TO|$PWD|" tools/kate/kate-lsp-settings.json
```

粘到 设置 → 配置 Kate → LSP 客户端 → 用户服务器设置（User Settings）面板，**只把 `vrml` 这个键并进已有的
`servers` 对象**（整份替换会抹掉别的语言段）。**不要在 Kate 运行中直接编辑那个 json** —— Kate 退出时会把
手里的配置写回去，外部改动被抹平。之后第一次打开 `.wrl` 会弹框问是否允许这条命令行，批准记录进 `katerc`
的 `[lspclient] AllowedServerCommandLines`；拒绝过一次就不再弹，得去「Allowed && Blocked Servers」页清。

排障用 `commandDebug`：菜单里勾 Debug Server 会改走它，即 `-Dvrml.lsp.log=/tmp/vrml-lsp.log`（stderr 在
Kate 里看不见）。改源码后要重新 `package` 并在 Kate 里 Restart Server —— jar 是启动时读的。

已经核对过的契合点：

| 协议点 | 我方 | Kate 26 | 依据 |
|---|---|---|---|
| 位置 | 声明 UTF-16 列 | 发 UTF-16 | `VrmlLanguageServer.initialize` |
| 同步 | 声明 Incremental | `IncrementalSync=false` 时可发**无 range 的全文事件** | `VrmlDocument.applyEdit` 有 `range == null` 分支，两种都吃 |
| `languageId` | 完全不读 | 按模式给什么都行 | 主源码里没有 `getLanguageId()` 调用 |
| 诊断 | 只 push，未声明 pull | 支持 push | 不声明 `diagnosticProvider`，客户端就不会去拉 |
| 缩进 | 请求里的 `FormattingOptions.tabSize` 优先于配置 | 取当前文档缩进 | `FormattingSettings.forRequest` |
| `vrml.maxColumn` | **只能**从配置来（协议对象里没这字段） | Server Configuration 页 → `didChangeConfiguration` | 填 `{"vrml": {"indent": 2, "maxColumn": 100}}`；该消息在 `LspProtocolIntegrationTest` 里走真 JSON-RPC 测过 |

`maxColumn` 到没到位看日志，收到配置时 server 打一行 `formatting settings: indent=…, maxColumn=…`。
Kate 的 SemanticHighlighting / SignatureHelp / Code Action 开关不生效是正常的：我们没声明这些能力。

**格式化对破损文档是拒绝的**：有 Error 级诊断时返回 `null` 并 `window/showMessage(Warning)` 说明原因
（宁可不格式化，也不要在破损语法上重排 —— phpls 的教训）。语料里正好有 10 个这样的文件，
`FormatterCorpusTest` 会逐个确认它们被拒、而其余 211 个二次格式化零 diff。

## 其他客户端

不依赖 Kate 的手测工具：`tools/lspclient.py` 按同样的报文序列打 stdio，能给出 Kate 给不了的行号。
plan 首版还打算用 Neovim / Zed / helix 这类纯 stdio 客户端交叉验证，接入方式与上面同理（命令换成
`java -jar <绝对路径>/target/vrml-lsp.jar`，filetype 认 `vrml`）。

## 项目结构

```
vrml-lsp/
  pom.xml                       # release 17、lsp4j 0.24.0、junit 5.11.4、shade 打 fat jar、specgen profile
  README.md                     # 本文：用法与当前达成度
  DEVELOPMENT.md                # 开发过程：取证、决策、弯路、四次假绿
  GRAMMAR-MAPPING.md            # .jj 31 条规则 → 我方产生式，逐条依据；语料放宽清单；复核方法；Kate 接入
  parsetest/                    # 263 个 .wrl 的项目内副本 + PROVENANCE.txt
  src/main/java/org/vrml/lsp/   # Launcher、Log + lexer/ parser/ cst/ semantic/ spec/ services/ server/ diagnostics/ text/
  src/main/resources/spec/vrml97-spec.json
  src/specgen/java/             # profiles + Base*.java + UOM → json + 差异报告（测试源，不进 jar）
  src/test/java/                # 293 例：语料回归、golden、LSP 集成、性能预算
  src/test/resources/corpus/    # issues-baseline.txt、diagnostics-golden.txt（棘轮与期望）
  tools/lspclient.py            # 脱离编辑器的 stdio 手测客户端
  tools/kate/                   # Kate LSP 客户端配置模板
```

`.gitignore` 的取舍：排除 `target/`、`.cache/`（抓来的 UOM，specgen 的输入）、`.mvn/maven.config`
（内含机器相关的 `-Dmaven.repo.local` 绝对路径）、IDE 目录；**语料副本刻意不排除** —— `mvn test` 应当
只需要这个目录。验证过：`git clone` 到空目录后（补一份自己的 `maven.config`）`mvn -B -o clean test` 全绿。

## 边界（首版不做）

`.x3d`(XML)、`.x3dv`、X3D 4.x 节点、EAI/JavaScript、渲染与场景求值、跨文件 PROTO 的全量索引重建、
语义 `codeAction` 自动修复（只在诊断的 `data` 里预留结构）、VS Code 扩展打包。
`implementation`/`location` 跳转也不做 —— 没有类层次语义需求。

扩展节点集（CADGeometry、H-Anim、NURBS…）不在核心 56 之内，经 `PROTO`/`EXTERNPROTO` 走通用路径，
报 Hint 不报 Error。

## 待办与待签核

1. **M3 的字段名签核**：表里有若干字段名在本机三源中不能同时自洽（`wrapS`/`wrapT` 一类 X3D 化写法）。
   plan 要求这类冲突清单人工签核：保留（并按 Xj3D 行为）还是删掉该行。**未决。**
2. **M6 的实现偏离**（上一节）：不做 statement 级复用。**待签核。**
3. 命名：目录 `vrml-lsp/`、坐标 `org.vrml:vrml-lsp`、主类 `org.vrml.lsp.Launcher` —— plan 问是否换成
   你的偏好，目前按现状；仓库刚建，改的成本还很低。
4. 小噪声：`Launcher.VERSION` 是 `0.1.0` 而 Maven 版本是 `0.1.0-SNAPSHOT`，两者没联动。
5. 内存余量：`List<Token>` → 并行数组、`sigOf` 去装箱（见性能一节）。
6. `EXTERNPROTO` 的 URI 补全只看**同目录**的 `.wrl` / `.wrl.gz`（`CompletionEncoder.siblingScenes`，
   有 `MAX_URIS` 上限、读盘失败就当没候选）：跳目录的 import 与 `.wrz` 不给候选，
   也没有 workspace 级文件索引（plan 里的「全量重建」本来就在不做之列）。
