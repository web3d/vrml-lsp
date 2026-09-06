# VRML97 语法移植对照表

本文件是「自研容错递归下降解析器」与上游 JavaCC 语法之间的等价性契约。
我们**不生成、不改造** JavaCC 产物，所以语法等价性无法由生成器保证，只能逐条对照 + 语料对拍。
这张表就是那份逐条对照，M1 之后每次改语法都要回到这里更新状态列。

- 上游语法：`Xj3D/xj3d/src/javacc/vrml/VRML97RelaxedParser.jj`（1531 行，Relaxed 变体）
- 我方实现：`src/main/java/org/vrml/lsp/lexer/VrmlLexer.java`（词法）、`src/main/java/org/vrml/lsp/parser/VrmlParser.java`（语法）
- 行号是**上游文件的行号**，用于「打开两个文件对读」；我方方法行号会漂移，故只写方法名

### 口径

`.jj` 里共 **31 处 `Rule n` 注释**，覆盖 **28 个规则号**：`Rule 2` 被复用在 3 个产生式上
（`NodeStatement`/`ProtoStatement`/`RouteStatement`，ISO 把这三者都写作 nodeStatement 级别的产生式），
且**没有 `Rule 8`**。所以「31 条规则」指的是 31 个对照点，本表逐条列出，一条不省。

`Id()`（`.jj` L1391）在规则号之外，是 17/18/19/22 共用的小产生式，一并列在表里。

---

## 1. 产生式对照（31 个对照点）

状态列取值：**已移植**（行为一致，且有证据）／**已移植·有意偏离**（见第 5 节）／**上游死代码**（无可移植行为）。

| 规则 | 上游产生式（行） | ISO BNF（照抄上游注释） | 我方实现 | CST 节点 | 证据 | 状态 |
|---|---|---|---|---|---|---|
| 0 | `Scene()` L372 | `vrmlScene ::= statement*` | `scene()` | `SCENE` | `CorpusRoundTripTest` 263 文件；`rule0And12EmptyNodeIsASceneWithOneStatement` | 已移植 |
| 1 | `Statement()` L396 | `statement ::= nodeStatement \| protoStatement \| routeStatement` | `statement()` | `STATEMENT` | `rule0And12…`、`rules4To7ProtoWithInterfaceAndBody` | 已移植 |
| 2 | `NodeStatement()` L413 | `nodeStatement ::= node \| DEF NodeNameId node \| USE NodeNameId` | `nodeStatement()` | `NODE_STATEMENT`+`DEF_CLAUSE`/`USE_CLAUSE` | `rule2DefAndUseShareOneName`；语料 `vrml97/field2.wrl`（USE） | 已移植 |
| 3 | `RootNodeStatement()` L456 | `rootNodeStatement ::= node \| DEF NodeNameId node` | `rootNodeStatement()` | 同 `NODE_STATEMENT` | `rule5ProtoBodyNeedsARootNodeAndRule3RefusesUseThere` | 已移植·有意偏离（USE 出现在此处时报 1013 后继续，上游直接 `ParseException`） |
| 2 | `ProtoStatement()` L476 | `protoStatement ::= proto \| externproto` | `protoStatement()` | `PROTO_DECL`/`EXTERN_PROTO_DECL` | `rules4To7…`、`rules9To11…` | 已移植 |
| 4 | `Proto()` L491 | `proto ::= PROTO NodeTypeId [ interfaceDeclaration* ] { protoBody }` | `proto()` | `PROTO_DECL`+`PROTO_NAME` | `rules4To7ProtoWithInterfaceAndBody`；语料 47 个文件含 PROTO（`exporter/NancyPrototypes.wrl`） | 已移植 |
| 5 | `ProtoBody()` L530 | `protoBody ::= protoStatement* rootNodeStatement statement*` | `protoBody(int)` | `PROTO_BODY` | `rule5ProtoBodyNeedsARootNodeAndRule3RefusesUseThere` | 已移植 |
| 6 | `RestrictedInterfaceDecl()` L545 | `restrictedInterfaceDeclaration ::= eventIn fieldNameId eventId \| eventOut fieldNameId eventId \| field fieldNameId eventId fieldValue` | `interfaceDecl()`（6/7 合一） | `INTERFACE_DECL`+`ACCESS_TYPE`/`FIELD_TYPE`/`FIELD_NAME` | `rules4To7…`（Script 侧由 `scriptElement()` 承担） | 已移植（BNF 注释的名字顺序是陈的，见第 3 节） |
| 7 | `InterfaceDecl()` L623 | `interfaceDeclaration ::= restrictedInterfaceDeclaration \| exposedField fieldNameId eventId fieldValue` | `interfaceDecl()` 的 `KW_EXPOSED_FIELD` 分支 | `INTERFACE_DECL` | `rules4To7…`；语料 20 个文件含 exposedField | 已移植 |
| 9 | `ExternProto()` L675 | `externproto ::= EXTERNPROTO NodeTypeId [ externInterfaceDeclaration* ] URLList` | `externProto()` | `EXTERN_PROTO_DECL` | `rules9To11…`；语料 `exporter/ep_use.wrl` | 已移植·有意偏离（缺 `[` 时报 1004 并继续，X3D 允许省略括号） |
| 10 | `ExternInterfaceDecl()` L710 | `externInterfaceDeclaration ::= accessType fieldNameId eventId` | `externInterfaceDecl()` | `INTERFACE_DECL` | `rules9To11…`；语料 `vrml97/externproto3.wrl` | 已移植（**按代码而非注释**：实际三段 `accessType fieldType fieldNameId`，见第 3 节） |
| 2 | `RouteStatement()` L741 | `routeStatement ::= ROUTE NodeNameId . eventOutId TO NodeNameId . eventInId` | `route()` | `ROUTE_DECL` | `routeStatementKeepsBothEndpointsForDefinitionLookup`、`missingToInRouteIsReportedAndTheRestStillParses`；语料 67 个文件（`scripts/click_test.wrl`） | 已移植 |
| 11 | `URIList()` L778 | `URLList ::= "[" STRING_LITERAL* "]" \| STRING_LITERAL` | `uriList()` | `URI_LIST` | `rules9To11…`；语料 `exporter/ep_use.wrl`（含 `#` 片段、`file:///C|/…`、`urn:` 的 URL） | 已移植 |
| 12 | `Node(String)` L812 | `node ::= nodeTypeId { nodeBody } \| "Script" { scriptBody }`（注释：空 body 与空 script 在此处理） | `node()` | `NODE`+`NODE_TYPE` | `rule0And12…`；语料 263 文件几乎全部 | 已移植 |
| 13 | `NodeBody()` L860 | `nodeBody ::= nodeBodyElement+`（空由 Rule 12 处理） | `nodeBody()` | `NODE_BODY` | `rule14FieldAssignmentsKeepNameAndValueApart` | 已移植 |
| 14 | `NodeBodyElement()` L875 | `nodeBodyElement ::= fieldNameId fieldValue \| fieldNameId IS fieldNameId \| routeStatement \| protoStatement` | `nodeBodyElement()`、`isClause()` | `FIELD_ASSIGNMENT`/`IS_CLAUSE` | `rule14…`、`rule14IsClauseInsideANodeInstance`；语料 24 个文件含 IS | 已移植（上游靠 `LOOKAHEAD(2)` 分流，我方用「下一个 token 是否 IS」的显式判断，等价且无需回退） |
| 15 | `ScriptBody()` L961 | `scriptBody ::= scriptBodyElement+` | `scriptBody()` | `SCRIPT_BODY` | `rules15To16ScriptBodyMixesDeclarationsAndFields`；语料 22 个 Script 文件 | 已移植 |
| 16 | `ScriptBodyElement()` L976 | `scriptBodyElement ::= nodeBodyElement \| restrictedInterfaceDeclaration \| eventIn/Out fieldType id [IS id] \| field fieldType id (IS id \| fieldValue)` | `scriptElement()` | `SCRIPT_ELEMENT` | 同上；`exposedField` 出现在 Script 时报 1014（上游也没有该分支，见 `scripts/exposed_field.wrl` 的自我说明） | 已移植（`LOOKAHEAD(3)` 的分流在词法上就是「IDENT / ROUTE / PROTO / 访问类型关键字」四选一，我方写成 switch） |
| 17 | `NodeNameId()` L1086 | `nodeNameId ::= Id` | `expectName(NODE_NAME, …)` | `NODE_NAME` | `rule2DefAndUse…`、DEF 83 文件 | 已移植 |
| 18 | `NodeTypeId()` L1100 | `nodeTypeId ::= Id` | `expectName(NODE_TYPE, …)` | `NODE_TYPE` | 全部节点语句 | 已移植 |
| 19 | `FieldNameId()` L1114 | `fieldNameId ::= Id`（同一产生式兼作 `eventInId`/`eventOutId`/`exposedFieldId`） | `expectName(FIELD_NAME, …)` | `FIELD_NAME` | 全部字段赋值与 ROUTE 端点 | 已移植 |
| 20 | `AccessType()` L1131 | `"field" \| "eventIn" \| "eventOut" \| "exposedField"` → FieldConstants 常量 | `isAccessType()` + `consume(ACCESS_TYPE)` | `ACCESS_TYPE` | `rules4To7…`、`rules9To11…` | 已移植（我方不映射成整数常量，access 的语义留给 M3 的 spec 表） |
| 21 | `RestrictedFieldType()` L1160 | 同 20 但去掉 `exposedField` | —（由调用点约束：`scriptElement()` 只接受 `field`/`eventIn`/`eventOut`） | — | 上游 `grep RestrictedFieldType()` 只有定义、**零调用点** | 上游死代码，无行为可移植 |
| 22 | `FieldId()` L1169 | `Id`（字段类型名，允许用户写全称） | `expectName(FIELD_TYPE, …)` | `FIELD_TYPE` | `rules9To11…`；语料 `exporter/ep_use.wrl` 的 `MFString`/`SFNode` | 已移植 |
| 23 | `FieldValue(String)` L1185 | `fieldValue ::= singleFieldValue \| multiFieldValue` | `fieldValue()` | `VALUE` | `rule23And24…`、`rule25…` | 已移植（上游传 fieldName 是为了回调，纯语法无需） |
| 24 | `SingleFieldValue()` L1208 | `singleFieldValue ::= NodeStatement \| "NULL" \| LiteralValue` | `singleFieldValue()` | `SF_VALUE` | `rule23And24…`、`rule24NullIsAValueNotAnIdentifier` | 已移植 |
| 25 | `MultiFieldValue(String)` L1233 | `multiFieldValue ::= "[" (NodeStatement)+ "]" \| "[" (SingleFieldValue)* "]"` | `multiFieldValue()` | `MF_VALUE`+`NODE_LIST`/`NUMBER_ARRAY`/`STRING_ARRAY` | `rule25MultipleValuePicksItsElementKindFromTheFirstItem`；语料 193 文件含 `[` | 已移植·有意偏离（接受 `[]`，见第 5 节） |
| 26 | `LiteralValue()` L1284 | `literalValue ::= TRUE \| FALSE \| NumberArray \| STRING_LITERAL`（注释明说：允许超过 SFRotation 的 4 个数字，「更快但不那么正确」） | `literalValue()` | `LITERAL_VALUE` | `rule23And24…`；语料 `Appearance/pixeltexture.wrl`（`image 2 2 3 0x0 …` 无括号 6 项） | 已移植（限长检查归 M4 语义层） |
| 27 | `StringArray()` L1313 | `stringArray ::= STRING_LITERAL+` | `multiFieldValue()` 的 STRING 分支 | `STRING_ARRAY` | `rule25…`（`ImageTexture { url [ "a.png" "b.png" ] }`） | 已移植（内联，不单独成方法：只有一行循环） |
| 28 | `NumberArray()` L1333 | `numberArray ::= NUMBER_LITERAL+`（`LOOKAHEAD(2)`） | `multiFieldValue()` 的 NUMBER 分支 | `NUMBER_ARRAY` | `rule25…`；语料 `ImageTexture1.wrl` 的 `point [...]` | 已移植（内联；`LOOKAHEAD(2)` 的作用是「后面还有数字才进数组」，被 `while (check(NUMBER))` 取代） |
| 29 | `FixedNumberArray()` L1358 | 同 28，但把数字拼回一个带空格的字符串 | `literalValue()` 的 NUMBER 分支 | `NUMBER_ARRAY` | `rule23And24…` | 已移植（我方不需要拼字符串，CST 直接保留各 token 原文） |
| — | `Id()` L1391 | `id ::= <ID>` | `expectName()`（4 个规则共用） | 按语义给 `NODE_NAME`/`NODE_TYPE`/`FIELD_NAME`/`FIELD_TYPE` | 同 17-19、22 | 已移植 |

---

## 2. 词法分组对照

上游 7 个 token 分组（`.jj` L1400-1531）→ 我方 `TokenType` 与 `VrmlLexer` 方法。

| `.jj` 行 | 分组 | 上游声明 | 我方 |
|---|---|---|---|
| L1400 | `<*> SKIP` 空白 | `" " \| "\t" \| "\n" \| "\r" \| "\f" \| ","`（注释：*comma is white space in VRML!*） | `WHITESPACE`（trivia），`isWhitespace()` |
| L1410 | `<*> SPECIAL_TOKEN` 注释 | `<COMMENT: "#" (~["\n","\r"])* ("\n"\|"\r"\|"\r\n")>` | `COMMENT`（trivia），`comment()`；行尾无换行时吃到 EOF |
| L1415 | `TOKEN` 字面量 | `<NUMBER_LITERAL>`、`<STRING_LITERAL>` | `NUMBER`（`scanNumberEnd()`）、`STRING`（`string()`） |
| L1436 | `TOKEN` 分隔符 | `{ } [ ]` | `LBRACE`/`RBRACE`/`LBRACKET`/`RBRACKET` |
| L1444 | `TOKEN` VRML 关键字 | `DEF USE NULL PROTO eventIn eventOut field exposedField EXTERNPROTO ROUTE TO` **`.`** | 11 个 `KW_*` + `DOT`（上游把 `.` 放在关键字组，我方归分隔符，行为相同） |
| L1461 | `TOKEN` 其它关键字 | `Script TRUE FALSE`（`HEADER` 被注释掉） | `KW_SCRIPT`/`KW_TRUE`/`KW_FALSE`；**没有 HEADER token** —— 上游把头检查留在语法之外（`VRML97Reader`），我方同理：见 `diagnostics/HeaderCheck`，不属于 `VrmlParser` |
| L1469 | `TOKEN` 标识符 | `<ID: <ID_FIRST> (<ID_REST>)*>`，`#ID_FIRST`/`#ID_REST` 见 L1476-1497 | `IDENT`，`isIdentFirst()`/`isIdentRest()`；关键字按整词匹配（L1499-1529 那段被注释掉的「规范排除集」我方同样不采用） |

我方额外新增、上游没有的分组：`BAD`（匹配不上任何 token 的单字符，词法器永不失败）与 `EOF`。
上游对应位置的行为是抛 `TokenParseException`。

字符区间照抄，勿凭记忆改：`ID_FIRST = \u0021, \u0024-\u0026, \u0028-\u002a, \u002f, \u003a-\u005a, \u005e-\u007a, \u0080-\ufaff`；
`ID_REST = ID_FIRST + \u002b, \u002d, \u007c, \u007e`（注意 `+ -` 只在 REST 里，`(` `)` 两处都在）。
**`\ufeff` 不在任何区间内**，所以 BOM 只有靠第 5 节的特例才不被当成坏字符。

---

## 3. 上游 BNF 注释与代码不符之处（以代码为准）

这些是本轮逐条对照时踩到的坑：注释是陈的，**代码才是 Xj3D 实际接受的集合**。
以后再发现新的，加进这张表，不要改代码去迁就注释。

| 规则 | 注释声称 | 代码实际 | 依据 |
|---|---|---|---|
| 10 | `accessType fieldNameId eventId`（两段名） | `AccessType() FieldId() FieldNameId()`（访问类型 + **字段类型** + 名字，三段） | `vrml97/externproto3.wrl` 写的是 `eventIn SFInt32 setter`；本方修复见 `externInterfaceDecl()` |
| 6 / 7 | `eventIn fieldNameId eventId` | `FieldId()` 在前、`FieldNameId()` 在后（即 `eventIn SFTime frac_changed`） | 语料所有 PROTO/Script 声明都是「类型在前」；表 1 第 6/7 行的 BNF 列保留注释原文以便核对 |
| 25 | `"[" (SingleFieldValue)* "]"` | 三分支：`LOOKAHEAD(2)` 节点+ / `NumberArray()` / `StringArray()`，**数组必须同种且非空** | 混合数组 `[1 "a"]` 上游在 NumberArray 后等不到 `]` 直接失败；我方报 1 条 1001 并同步到 `]` |
| 12/13 | `nodeBody ::= nodeBodyElement+`（无空） | 空 body 由 `Node()` 里的 `(NodeBody())?` 允许 | 语料大量 `X {}`；我方 `nodeBody()` 的 while 天然允许零个元素 |
| 15/16 | `scriptBody ::= scriptBodyElement+` | 同上，空由 `Node()` 允许 | 语料 `Script {}` |

---

## 4. Relaxed 方言放宽清单（每条一个语料用例）

「放宽」是相对 ISO VRML97 严格写法而言。右列的语料路径相对于 `parsetest/`（项目自带的 Xj3D parse-test 副本，来历见 `parsetest/PROVENANCE.txt`）。
最后一列是对我方服务的影响，因为放宽决定了补全/高亮不能假设什么。

| # | 放宽 | 上游依据 | 语料用例 | 我方处理 | 影响 |
|---|---|---|---|---|---|
| R1 | **逗号当空白**，可出现在任何空白位置 | L1407 `SKIP` 含 `","` | `ImageTexture1.wrl:18` `point [0 0, 3 0, 3 3, 0 3]`（61 个文件含逗号） | `WHITESPACE` trivia，不产生 token | 格式化必须能把逗号原样吐回；分隔符补全不能插逗号 |
| R2 | **字符串可跨行**（裸换行合法） | L1424 主体是 `(~["\"","\\"])*`，未排除换行 | `ecmascript/constructors.wrl`、`exporter/DiamondManLOA-0.wrl` 的 `info " …多行散文… "`（36 个文件的字符串含换行） | `string()` 允许裸换行 | 行列统计不能按「一行一个 token」近似；未闭合字符串的危害见 D3 |
| R3 | **NUMBER 正则刻意宽松** | L1417-1420 注释「让字符串转数字去发现非法情形更快」；体为 `(["-","+"])? (".")? [0-9] [0-9a-fA-Fx.+-]*` | `Appearance/pixeltexture.wrl` `image 2 2 3 0x0 0x00FF00 0x0000FF 0xFF0000`（十六进制不是 VRML 数字） | 与上游同规则；`1.5f`、`0x00FF00` 都是一个 NUMBER | 值合法性判定属 M4；「数字高亮」不能等同「数字有效」 |
| R4 | **单值位允许整串数字**（超类型限长） | Rule 26 注释：允许超过 SFRotation 的 4 个值 | `Appearance/pixeltexture.wrl` 的 `image` 无括号 6 项 | `literalValue()` 里 NUMBER 分支吃掉全部数字成 `NUMBER_ARRAY` | 元素个数/宽度校验（SFVec3f=3 等）只能在 M4 做，需要 spec 表 |
| R5 | **标识符字符集含标点** | L1471-1472 注释：`"!^\%&|~" is a valid ID!` | `performance/highpoly2.wrl` `DEF Cylinder01_FACES(1) { … }`（`(` `)` 都是 ID 字符） | 与上游同区间，见第 2 节 | DEF/USE 名字、补全 word 边界不能按 `\w+` 算；格式化不得给名字加引号 |
| R6 | **Script url 的多字符串写法** | Rule 16 的 `field` 分支允许 `field MFString url <多行串>` | `scripts/route_change.wrl`、`background/single_sky_interp.wrl`（22 个 Script 文件） | 同上游 | — |
| R7 | **`[]` 空多值** | 上游 `NumberArray`/`StringArray` 都是 `+`，因此**不接受** `[]`（注释 L1263-1265 自陈是为兼容零长字段而特殊处理） | 语料无（仅出现在注释里，如 `exporter/ep_use.wrl` 的 `# []`） | 我方接受 `[]`，零元素 `MF_VALUE` | 这是规范正确性优先的偏离，见 D4 |

### 已证伪的猜测（不要再去实现）

| 猜测 | 结论 | 依据 |
|---|---|---|
| Relaxed 支持 C 风格 `//` 注释 | **不支持** | `.jj` 的 SKIP 只有空白与 `#`；`/` 是 ID_FIRST 字符，所以 `//foo` 会 lex 成一个 IDENT。语料 263 文件中 `//` 出现在注释或字符串之外的次数为 **0**，无需处理 |
| Relaxed 支持 `#RRGGBB` 颜色字面量 | **不支持** | `#` 一律是注释起点。语料里的颜色十六进制写法是 `0x00FF00`（`Appearance/pixeltexture.wrl`），靠的是 R3 的宽松 NUMBER，而不是新的词法 |
| 转义集合比 VRML97 更宽 | **等价** | 上游 = `n t b r f \ ' "` + 八进制（`[0-7]{1,2}` 或 `[0-3][0-7][0-7]`），我方 `escapeLength()` 逐分支对齐，含 `\777`→`\77`+`7`、`\400`→`\40`+`0` 的贪心细节 |

---

## 5. 有意偏离上游之处

偏离必须是清单化的、有理由的，否则等价性检查就失去意义。

| # | 偏离 | 上游行为 | 我方行为 | 理由 |
|---|---|---|---|---|
| D1 | 语法错误 | 抛 `ParseException`，整个文件解析中止（`Scene()` 只有 try/finally，options 无 ERROR_RECOVERY） | 记 `Issue` + `MISSING` 零宽占位 + `ERROR` 包裹不可放置 token，并在同步点继续 | LSP 的核心需求就是破损文档仍要能用；见 `errorRun()`、`recoverIn*()` |
| D2 | 词法错误字符 | `TokenParseException` | 单字符 `BAD` token，前进 1 字符；`scanLexical()` 统一报 1011 | 同上。「词法永不失败」这条不变式由 `VrmlLexerTest` 的逐 token 断言与 `CorpusRoundTripTest` 里 54 个垃圾/边界输入共同钉住 |
| D3 | 未闭合字符串 | 一直吞到文件尾（因为换行合法） | 停在**本行行尾**并置 `truncated`，报 1010 | 一个漏掉的引号不该把后面的语句全藏起来；语料里所有多行字符串都有闭合引号（R2 已量化 36 文件），故此特例不误伤 |
| D4 | `[ ]` | 失败（见 R7） | 接受 | ISO VRML97 允许空多值；编辑器在用户刚敲出 `[` 时也必须能用 |
| D5 | `USE` 出现在 PROTO 体 / PROTO 体为空 | `ParseException` | 分别报 1013、2010 后继续 | 这两条本质是语义约束，报错比崩掉有用 |
| D6 | EXTERNPROTO 省略 `[...]` | 失败 | 报 1004 后继续读 URLList | X3D 允许省略，用户从 X3D 迁移时会写错；报一次就够 |
| D7 | `\ufeff` | 匹配不到任何 token | 仅在 **offset 0** 当 trivia | 语料 6 个文件带 BOM（如 `geometry/text/chinese1-bom.wrl`）；BOM 出现在名字中间仍按 BAD 报，不掩盖 |
| D8 | 上游 `Rule 21` | 有定义无调用 | 不实现 | 见第 1 节，避免造出一个没人用的分支 |
| D9 | 首行 `#VRML V2.0 utf8` 检查 | 由 `VRML97Reader`/HeaderInfo 在语法之外做（`.jj` 的 HEADER token 被注释掉 L1466） | **同上游**：`diagnostics/HeaderCheck` 通过 `DocumentAnalyzer` 施加，报 1 条 1016（缺头）或 1017（头形不对），跨度 = 首行整行；X3D 头改报 1 条 3005（Information）并抑制头错 | 上游把 HEADER 注释掉的正说明它不是语法；塞进 `scene()` 会让「`Box {}` 这样的片段」在单测里全部假报错。规则逐条见 `HeaderCheckTest`，五语料位置见 golden |

### 5.1 恢复的具体机制（D1 展开）

「记 Issue 并在同步点继续」要说清楚的是**同步点怎么选**，否则一个错误会级联成一串。
VRML 没有语句终止符，所以「下一个换行」不是同步点；四条机制从小到大：

| 机制 | 位置 | 判据 | 效果 |
|---|---|---|---|
| 一次报错一个 token | `errorRun()`（M1 起） | `lastErrorSig` 相同的相邻错误合并 | 同一位置的重复投诉只留一条 |
| 语句级 fallout | `scene()` + `skipFallout()` + `plausibleStatementStart()` | 该语句报了错、且 `bracesConsumed` 没增加（`{}` 是 Rule 12 的骨架，没吃到 `{` 就说明它根本不是语句） | 静默扫到下一个可信起点：`DEF`/`USE`/`ROUTE`/`PROTO`/`EXTERNPROTO` 关键字，或「IDENT 紧跟 `{`」 |
| 体内 fallout | `nodeBody()`/`scriptBody()` + `plausibleElementStart()` | 同上判据，但作用在 Rule 13/15 的元素循环上 | 同一个坏语句写在节点体里（`Box { ... IMPORT INLINE.foo AS bar }`）也只一条，而不是每个词一条 |
| 非法声明自吞 | `skipBadDeclaration()`（`nodeBodyElement()`/`scriptElement()`） | 访问类型关键字出现在不允许的位置 | 连自己的 `type name [IS name]` 一起吞，避免剩下的词被 Rule 14 读成 `字段名 值` 而 invent 第二条错 |

两条 fallout 都必须**在 `}` / `]` 前停下**（`closesSomething()`）：吞掉 enclosing 节点自己的收尾括号，
会把一条错变成「未闭合节点 + 后面整个文件」。

实测收益（同一个文件，`--check-file` 诊断条数）：
`error_handling/import.wrl` 5→1、`nurbs/simple_nurbssurface.wrl` 4→1、`scripts/exposed_field.wrl` 2→1、
`events/boolean_filter.wrl` 7→3（一条 PROFILE、一条三个相同的 `inputOnly` 声明、一条 VRL3005 说明它本来是 X3D）。
机制的行为由 `VrmlParserTest` 的 `aStatementThatCannotBecomeAWholeOneIsSweptInOneGo` /
`falloutResumesAtAKeywordNotAtALineBreak` / `aBrokenElementInsideANodeBodyIsSweptLikeABrokenStatement` /
`anIllegalDeclarationSwallowsItsOwnWords` 钉住。

级联排查过程中发现并修掉的一个 CST 缺陷（影响 M5 的游标查找，不属语法偏离）：
空构造（`Sphere {}` 的 `NODE_BODY`、空文档的 `SCENE`）的 span 是 `-1..-1`，
而 `CstNode.addChild()` 拿 `Math.min` 合并时会把父节点的 start 拉到 -1，下个叶子的 `min(-1, x)`
仍为 -1，于是整条链的起点变成「最后一个不 poison 的位置」。修在 `addChild()` 里（子节点 `start < 0`
则不参与 span 计算），钉为 `anEmptyBodyDoesNotStealItsAncestorsPositions`。

---

## 6. 覆盖面与可信度边界

上游语法能写、语料却没写到的东西，等价性只能靠单测保证。当前状态：

| 语法构造 | 语料文件数 | 仅靠单测覆盖 |
|---|---|---|
| 节点语句 / 空 body | 263（普遍） | 否 |
| DEF | 83 | 否 |
| USE 语句 | 3 | 否 |
| ROUTE | 67 | 否 |
| PROTO | 47 | 否 |
| EXTERNPROTO（含 URLList） | 10 | 否 |
| exposedField | 20 | 否 |
| Script（含多行 url） | 22 | 否 |
| NULL | 9 | 否 |
| MF 方括号值 | 193 | 否 |
| 逗号当分隔符 | 61 | 否 |
| 跨行字符串 | 36 | 否 |
| 十六进制数字 | 1 | 否 |
| `[]` 空多值 | **0** | **是**（`rule25…`） |
| 混合数组 `[1 "a"]` | **0** | **是**（`rule25…` 末段：只报 1 条 1001，仍逐字节可打印） |
| 单独 `\r` 行尾 | **0** | **是**（`LineIndex` 相关测试） |
| 文件末尾无换行 | 47 | 否 |
| CRLF | 148 | 否 |
| BOM | 6 | 否 |
| 非 UTF-8 字节（`geometry/text/japanese3-bom.wrl` 的 Shift_JIS 串） | 1 | 否 |
| 非 BMP 字符（需代理对，影响 LSP 列号） | **0** | 是（`LineIndex` 测试里手写 `\ud83d\ude00`） |

语料自身的两个命名陷阱（不是我方问题，但引用时别看错）：`geometry/text/english-nobom.wrl` **确实带 BOM**，
而 `geometry/text/japanese3-bom.wrl` **不带 BOM**（它特殊的是内部那串非 UTF-8 字节）。

M1+M2 的量化结果（`mvn test`，72 个测试全绿）：263/263 文件逐字节 round-trip、CST holes 为 0、
全语料解析 0.17 s、最慢单文件 `performance/highpoly2.wrl` 约 0.1 s（多次跑在 62-125 ms 间浮动）。
服务器视角（UTF-8 读法 + `DocumentAnalyzer`）的结构诊断从 547 条降到 **12 条、只落在 9 个文件上**
= 5 条真语法错（4 条 VRL1002 + 1 条 VRL1014，全在 5.1 的四个机制之后）
+ 2 条 VRL3005（.wrl 文件名里的 X3D）+ 5 条 VRL1017（`bad_header1..5.wrl`），
逐条理由见 `src/test/resources/corpus/issues-baseline.txt`，逐条位置见 `diagnostics-golden.txt`。
round-trip 测试自己打印的是 11 条 / 10 个文件，因为它只跑解析器（不含头检查那 5 条与 VRL3005）、
且按 ISO-8859-1 读以保证「字符相等即字节相等」，BOM 的 3 个字节在那里是 3 个 Latin-1 字符
（`ï»¿`，恰好都落在 ID 区间）、不触发 D7 的特例 —— 多出的 6 条 = 6 个带 BOM 的文件各 1 条 VRL1002
（`geometry/text/*-bom.wrl` 与 `english-nobom.wrl`）。两种读法只在 BOM 文件上有差异，
其余 257 个文件完全一致。

M4 加完语义后（`mvn -B -o test`，133 个测试全绿）：同一读法下全语料 **49 条、落在 24 个文件上** ——
上面那 10 条结构码与 2 条 VRL3005 一字未动（基线 diff 只有新增，无一条旧目变化），加进来的是
35 条语义码 + 2 条 VRL3001 建议：`VRL2003*18`（Script 里 `field SFInt32 constant 0 0 0` 给单值字段写了三个数）、
`VRL2001*10`（`combo*/field*` 五个语料用的 `TransformGroup` 是 Java3D 的类名，不是 VRML97 节点）、
`VRL2002*4`、`VRL2018*2`、`VRL2019*1`。这两节的差值就是语义层的全部代价，
每一条的构成见 `ValueConstraints` 与 `Semantics` 的 javadoc（区间表若只按字段名单键，
首跑会在 9 个文件的 `ElevationGrid.height` 上凭空造出 1120 条投诉；ROUTE 里 `set_x`/`x_changed`
这一对隐式事件若不按语言规则补，会再造出 103 条 VRL2008）。

---

## 7. 怎么复核这张表

```bash
# 1) 上游规则点数量没变（应为 31）
grep -c "Rule [0-9]" ../Xj3D/xj3d/src/javacc/vrml/VRML97RelaxedParser.jj

# 2) 四条硬约束：逐字节保真、诊断棘轮（不无中生有）、golden（报对位置）、逐产生式单测
#    加上握手与推送：LspProtocolIntegrationTest 用内存管道跑真 JSON-RPC（initialize/didOpen/didChange/didClose）
mvn -B -o test

# 3) 单文件肉眼看诊断（与服务器同一代码路径：走 DocumentAnalyzer，含头检查与语义）
#    语料取项目自带副本，因此这一步不需要旁边的检出
mvn -q -B -o -DskipTests package
java -jar target/vrml-lsp.jar --check-file parsetest/vrml97/externproto3.wrl
java -jar target/vrml-lsp.jar --check-file parsetest/error_handling/import.wrl
java -jar target/vrml-lsp.jar --check-file parsetest/error_handling/eventIn_set.wrl

# 4) 改动了恢复策略或语义规则时，先重定向到临时文件逐行对比，确认差异都能说出理由，再覆写基线
cp src/test/resources/corpus/issues-baseline.txt /tmp/before.txt
mvn -B -o test -Dtest=CorpusIssueBaselineTest -Dvrml.updateBaseline=true
diff /tmp/before.txt src/test/resources/corpus/issues-baseline.txt
# golden 同理：-Dtest=CorpusGoldenDiagnosticsTest -Dvrml.updateGolden=true

# 5) 想知道解析器为何这么报：把 CST 打出来看，不要凭代码走读猜
jshell --class-path target/vrml-lsp.jar -q /tmp/dump.jsh

# 6) 确认过某条放宽的语料分布时，改的是第 4/6 节的数字，不是解析器
```

新增产生式或放宽时：先在第 1/2 节加行并写明上游行号与依据，再在第 6 节补一个语料或单测点，
最后才动 `VrmlLexer`/`VrmlParser`。

---

## 8. 在 Kate 里接入

Kate 的 LSP 客户端按**高亮模式名**绑服务器，而它自带 `VRML` 模式：`ksyntaxhighlighter6 -l` 里有这一项，
且对语料里任一 `.wrl` 的自动探测输出与强制 `-s VRML` 逐字节相同 —— 所以下面那句
`"highlightingModeRegex": "^VRML$"` 就够，不需要自定义语法文件。

配置落在 `~/.config/kate/lspclient/settings.json`，`servers` 是**以服务器名为键的对象**（不是数组）。
本项目那一段在 `tools/kate/kate-lsp-settings.json`，里面 `/ABSOLUTE/PATH/TO` 就代表**这个项目目录**；
在项目根目录里跑下面两句，打印出可直接粘的成品（Kate 不认相对路径，粘之前必须先换成真路径）：

```bash
mvn -B -o -DskipTests package          # Kate 要的是 jar，`clean test` 之后它已被删掉
sed "s|/ABSOLUTE/PATH/TO|$PWD|" tools/kate/kate-lsp-settings.json
```

粘到 设置 → 配置 Kate → LSP 客户端 → 用户服务器设置（User Settings）面板，**只把 `vrml` 这个键并进
已有的 `servers` 对象里**：整份替换会抹掉你正在用的 `php` 段。不要在 Kate 运行中直接改那个 json，
Kate 退出时会把它手里的配置写回去，外部改动被抹平 —— 要么走面板，要么先关 Kate。

第一次启动会弹框问是否允许这条命令行，批准结果写进 `katerc` 的 `[lspclient] AllowedServerCommandLines`；
拒绝过一次就不再弹，得去 LSP 客户端的「Allowed && Blocked Servers」页清掉。

验收用同一条语料、两条路径，结论必须一致：

```bash
java -jar target/vrml-lsp.jar --check-file parsetest/error_handling/import.wrl
# Kate 里打开同一个文件，应当亮在 8:8 的 VRL1002
```

格式化动作的菜单文字随 Kate 版本与翻译变，别照抄：去 设置 → 配置 Kate → 快捷键 里搜 `format`
与 `LSP`，能找到当前版本那几个动作（顺便给自己绑个键）。

### 两处容易怀疑到对方、其实已核对的契合点

| 协议点 | 我方 | Kate 26 | 依据 |
|---|---|---|---|
| 位置 | 声明 UTF-16 列 | 发 UTF-16 | `VrmlLanguageServer.initialize` |
| 同步 | 声明 Incremental | `[lspclient] IncrementalSync=false`，可以发无 range 的全文事件 | `VrmlDocument.applyEdit` 有 `range == null` 分支，两种都吃 |
| `languageId` | 完全不读 | 按模式给什么都行 | 主源码里没有 `getLanguageId()` 调用 |
| 诊断 | 只 push，不声明 pull | 支持 push | 不声明 `diagnosticProvider`，Kate 就不会去拉 |
| 缩进 | 请求里的 `FormattingOptions.tabSize` 优先于配置 | 取当前文档的缩进 | `FormattingSettings.forRequest` |
| `vrml.maxColumn` | 只能从配置来，协议对象里没这个字段 | 「Server Configuration」页 → `didChangeConfiguration` | 填 `{"vrml": {"indent": 2, "maxColumn": 100}}`；这条消息在 `LspProtocolIntegrationTest` 里是走真 JSON-RPC 测的 |

`maxColumn` 有没有真到位，看日志而不是猜：收到配置时 server 打一行
`formatting settings: indent=..., maxColumn=...`。排障统一走文件日志 —— 在 LSP 汉堡菜单里勾
Debug Server 会改走 `commandDebug`，即 `-Dvrml.lsp.log=/tmp/vrml-lsp.log`。server 的 stderr 在
Kate 里看不见，而 `Log` 永不写 stdout：stdout 是 JSON-RPC 通道，多写一个字节就变成对端的 Framing error。

改了源码后要重新 `package` 并在 Kate 里 Restart Server —— jar 是启动时读的，不是每次请求读的。
想绕开编辑器复现同一个故障，用 `tools/lspclient.py`：它按同样的报文序列打 stdio，能给出 Kate 给不了的行号。

